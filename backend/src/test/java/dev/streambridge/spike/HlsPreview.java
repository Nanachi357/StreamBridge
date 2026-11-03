package dev.streambridge.spike;

import java.io.IOException;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

/** Selected-stream prefix, remuxed locally without decoding, resizing or re-encoding. */
final class HlsPreview {
    // Independent of untrusted EXTINF and the user's requested playback duration.
    record Limits(long bytes, int requests, Duration total) {
        Limits {
            if (bytes < 1 || bytes > 128L * 1024 * 1024 || requests < 1 || requests > 128
                    || total.toMillis() < 1 || total.compareTo(Duration.ofMinutes(2)) > 0)
                throw new IllegalArgumentException("Invalid preview limits");
        }
    }
    static final Limits DEFAULT_LIMITS = new Limits(128L * 1024 * 1024, 128, Duration.ofMinutes(2));
    static final class LimitException extends IOException {
        LimitException(String code) { super(code); }
    }
    private static final class Budget {
        final Limits limits;
        final BoundedResourceFetcher.RequestBudget requests;
        final java.util.function.LongSupplier clock;
        final long started;
        long bytes;
        Budget(Limits limits, java.util.function.LongSupplier clock) {
            this.limits=limits;this.clock=clock;started=clock.getAsLong();
            requests=new BoundedResourceFetcher.RequestBudget(limits.requests());
        }
        int remainingMillis(int maximum) throws LimitException {
            long remaining=limits.total().toNanos()-(clock.getAsLong()-started);
            if(remaining < 1_000_000)throw new LimitException("PREVIEW_TIME_LIMIT");
            return (int)Math.min(maximum, remaining/1_000_000);
        }
        int resourceBytes() throws LimitException {
            if(bytes>=limits.bytes())throw new LimitException("PREVIEW_BYTE_LIMIT");
            return (int)Math.min(8*1024*1024,limits.bytes()-bytes);
        }
    }
    static long maximumMillis(List<HlsVodPlaylist> playlists) {
        return playlists.stream().map(HlsVodPlaylist::durationSeconds).min(java.math.BigDecimal::compareTo)
                .orElseThrow().movePointRight(3).setScale(0, java.math.RoundingMode.DOWN).longValueExact();
    }
    static Path create(List<HlsVodPlaylist> playlists, Path directory, HlsAcquisition.Fetch fetch,
                       Path tools, TransferFeedback feedback) throws Exception {
        return create(playlists, directory, fetch, tools, feedback, Math.min(5000, maximumMillis(playlists)));
    }
    static Path create(List<HlsVodPlaylist> playlists, Path directory, HlsAcquisition.Fetch fetch,
                       Path tools, TransferFeedback feedback, long durationMillis) throws Exception {
        return create(playlists,directory,fetch,tools,feedback,durationMillis,DEFAULT_LIMITS,System::nanoTime);
    }
    static Path create(List<HlsVodPlaylist> playlists, Path directory, HlsAcquisition.Fetch fetch,
                       Path tools, TransferFeedback feedback, long durationMillis, Limits limits,
                       java.util.function.LongSupplier clock) throws Exception {
        if (durationMillis < 1 || durationMillis > maximumMillis(playlists))
            throw new IllegalArgumentException("INVALID_PREVIEW_DURATION");
        var temporary = new ArrayList<Path>();
        boolean stopped = true;
        long bytes = 0;
        var budget = new Budget(limits,clock);
        var targetSeconds = java.math.BigDecimal.valueOf(durationMillis, 3);
        try {
            checkCancelled(feedback);
            Files.deleteIfExists(directory.resolve("preview.mp4")); // Only our previous clip, never a user output.
            var command = new ArrayList<>(List.of(HlsDownloadSpike.executable(tools, "ffmpeg.exe").toString(),
                    "-hide_banner", "-nostdin", "-n", "-v", "error", "-xerror"));
            for (int i = 0; i < playlists.size(); i++) {
                Path input = directory.resolve("preview-input-" + i + ".ts"); temporary.add(input);
                var seconds = java.math.BigDecimal.ZERO;
                try (var output = Files.newOutputStream(input, StandardOpenOption.CREATE_NEW)) {
                    for (var segment : playlists.get(i).segments()) {
                        checkCancelled(feedback);
                        requireSpace(directory, bytes + 16L * 1024 * 1024);
                        int timeout=budget.remainingMillis(10_000), cap=budget.resourceBytes();
                        if(budget.requests.used()>=limits.requests())throw new LimitException("PREVIEW_REQUEST_LIMIT");
                        BoundedResourceFetcher.Resource resource;
                        try {
                            resource = fetch.get(segment.uri(), new BoundedResourceFetcher.Limits(
                                    cap, 2, Duration.ofMillis(timeout), Duration.ofMillis(Math.min(3000,timeout)),
                                    Duration.ofMillis(Math.min(5000,timeout))),budget.requests);
                        } catch(BoundedResourceFetcher.FetchException failure) {
                            budget.remainingMillis(10_000);
                            if(failure.failure()==BoundedResourceFetcher.Failure.REQUEST_LIMIT)
                                throw new LimitException("PREVIEW_REQUEST_LIMIT");
                            if(failure.failure()==BoundedResourceFetcher.Failure.TOO_LARGE && cap<8*1024*1024)
                                throw new LimitException("PREVIEW_BYTE_LIMIT");
                            throw failure;
                        }
                        budget.remainingMillis(10_000);
                        if(resource.size()>cap)throw new LimitException("PREVIEW_BYTE_LIMIT");
                        budget.bytes+=resource.size();
                        byte[] data = resource.bytes(); bytes += data.length;
                        if (data.length < 188 || data.length % 188 != 0) throw new IOException("PREVIEW_TS_FRAMING");
                        for (int offset = 0; offset < data.length; offset += 188)
                            if (data[offset] != 0x47) throw new IOException("PREVIEW_TS_FRAMING");
                        output.write(data); seconds = seconds.add(segment.durationSeconds());
                        if (seconds.compareTo(targetSeconds) >= 0) break;
                    }
                }
                command.addAll(List.of("-threads", "2", "-protocol_whitelist", "file", "-f", "mpegts", "-i", input.toString()));
            }
            Path partial = directory.resolve("preview.part"); temporary.add(partial);
            command.addAll(List.of("-map", "0:v:0", "-map", playlists.size() == 2 ? "1:a:0" : "0:a:0?",
                    "-t", targetSeconds.toPlainString(), "-c", "copy",
                    "-movflags", "+faststart", "-f", "mp4", partial.toString()));
            checkCancelled(feedback);
            requireSpace(directory, bytes * 2);
            HlsDownloadSpike.runChecked(command, directory.resolve("preview-remux.log"),
                    budget.remainingMillis((int)Math.max(30_000, Math.min(600_000, durationMillis))));
            Path probe = directory.resolve("preview-probe.log");
            HlsDownloadSpike.runChecked(List.of(HlsDownloadSpike.executable(tools, "ffprobe.exe").toString(),
                    "-v", "error", "-protocol_whitelist", "file", "-f", "mov", "-show_entries", "format=duration",
                    "-of", "default=noprint_wrappers=1:nokey=1", partial.toString()), probe, budget.remainingMillis(10_000));
            validateDuration(Double.parseDouble(Files.readString(probe).strip()), durationMillis);
            checkCancelled(feedback);
            budget.remainingMillis(10_000);
            Path result = directory.resolve("preview.mp4");
            Files.move(partial, result); return result;
        } catch (FfmpegTransfer.ProcessNotStoppedException failure) {
            stopped = false; feedback.cleanupUncertain(); throw failure;
        } catch (IOException failure) {
            budget.remainingMillis(1); // Include remux/probe timeout in the operation deadline.
            throw failure;
        } finally {
            if (stopped) for (Path file : temporary) {
                try { Files.deleteIfExists(file); }
                catch (IOException failure) { feedback.cleanupUncertain(); }
            }
        }
    }
    static void validateDuration(double seconds, long requestedMillis) throws IOException {
        // Stream copy cuts on packet boundaries; B-frame reordering may extend the container timeline.
        // Never claim sample-exact trimming. The UI displays the actual media duration.
        if (!Double.isFinite(seconds) || seconds <= 0 || seconds > requestedMillis / 1000.0 + 1)
            throw new IOException("PREVIEW_DURATION");
    }
    private static void requireSpace(Path directory, long additional) throws IOException {
        if (Files.getFileStore(directory).getUsableSpace() < additional + 64L * 1024 * 1024)
            throw new IOException("PREVIEW_DISK_SPACE");
    }
    private static void checkCancelled(TransferFeedback feedback) throws InterruptedException {
        if (feedback.cancelled() || Thread.currentThread().isInterrupted()) throw new InterruptedException("Preview cancelled");
    }
}
