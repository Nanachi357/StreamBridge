package dev.streambridge.spike;

import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Predicate;

/** Explicit public-HTTP diagnostic: prefix sample or opt-in whole finite MPEG-TS transfer. */
final class RealHlsSampleProbe {
    enum Result { SUCCESS, CANCELLED, FAILED }
    static void run(HlsTransferPlan plan, Path reportDirectory, Consumer<String> log) {
        run(plan, reportDirectory, log, false, 0, review -> false);
    }
    static Result runFull(HlsTransferPlan plan, Path reportDirectory, Consumer<String> log,
                          long declaredBandwidth, Predicate<DownloadReview> approval) {
        return run(plan, reportDirectory, log, true, declaredBandwidth, approval);
    }
    static Result runFull(HlsTransferPlan plan, Path reportDirectory, Consumer<String> log,
                          long declaredBandwidth, Predicate<DownloadReview> approval, TransferFeedback feedback) {
        try (var fetcher = new BoundedResourceFetcher()) {
            String tools = System.getenv("STREAMBRIDGE_FFMPEG_DIR");
            return run(plan, reportDirectory, log, true, declaredBandwidth, approval, fetcher::fetch,
                    tools == null ? null : Path.of(tools), feedback);
        }
    }
    private static Result run(HlsTransferPlan plan, Path reportDirectory, Consumer<String> outputLog, boolean full,
                              long declaredBandwidth, Predicate<DownloadReview> approval) {
        try (var fetcher = new BoundedResourceFetcher()) {
            String tools = System.getenv("STREAMBRIDGE_FFMPEG_DIR");
            return run(plan, reportDirectory, outputLog, full, declaredBandwidth, approval, fetcher::fetch,
                    tools == null ? null : Path.of(tools));
        }
    }

    // Test seam for exact fixture resources; CLI always uses the default public-address fetcher.
    static Result run(HlsTransferPlan plan, Path reportDirectory, Consumer<String> outputLog, boolean full,
                      long declaredBandwidth, Predicate<DownloadReview> approval, HlsAcquisition.Fetch fetcher, Path tools) {
        return run(plan, reportDirectory, outputLog, full, declaredBandwidth, approval, fetcher, tools, TransferFeedback.NONE);
    }
    static Result run(HlsTransferPlan plan, Path reportDirectory, Consumer<String> outputLog, boolean full,
                      long declaredBandwidth, Predicate<DownloadReview> approval, HlsAcquisition.Fetch fetcher, Path tools,
                      TransferFeedback feedback) {
        // Keep existing sample diagnostics stable while making full-transfer evidence unambiguous.
        Consumer<String> log = line -> outputLog.accept(full ? line.replaceFirst("^SAMPLE", "DOWNLOAD") : line);
        String stage = "SETUP";
        Path directory = null;
        var temporary = new ArrayList<Path>();
        boolean processMayBeRunning = false;
        try {
            checkCancelled(feedback);
            feedback.phase("PREFLIGHT");
            if (tools == null) throw new IllegalStateException("FFmpeg directory missing");
            Path ffmpeg = HlsDownloadSpike.executable(tools, "ffmpeg.exe");
            Path ffprobe = HlsDownloadSpike.executable(tools, "ffprobe.exe");
            directory = Files.createTempDirectory(reportDirectory, full ? "download-" : "sample-");
            log.accept("SAMPLE directory=" + directory + "; selected=" + plan.choice());
            // Full mode is bounded by finite parsed playlists and each resource's redirect limit,
            // not by an unrelated fixed aggregate request count. Prefix limits stay unchanged.
            var requests = full ? null : new BoundedResourceFetcher.RequestBudget(12);
            var budget = new Budget(full);
            var playlists = new ArrayList<HlsVodPlaylist>();
            for (var input : plan.inputs()) {
                checkCancelled(feedback);
                stage = "PLAYLIST_" + input.role();
                var resource = fetch(fetcher, input.playlist(), 262144, budget, requests);
                String body = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(resource.bytes())).toString();
                log.accept("SAMPLE manifest " + describe(body));
                var playlist = HlsVodPlaylist.parse(resource.finalUri(), body);
                log.accept("SAMPLE compatibility version=" + playlist.protocolVersion() + " cache=" + playlist.cacheDirective());
                if (playlist.cacheDirective() == HlsVodPlaylist.CacheDirective.NO) {
                    log.accept("SAMPLE_REJECTED CACHE_REPLAY_DISALLOWED; legacy NO forbids retention for replay");
                    feedback.failure("CACHE_REPLAY_DISALLOWED");
                    return Result.FAILED;
                }
                if (input.metadata() != null && !input.metadata().equals(playlist)) throw new IllegalStateException("Playlist changed");
                playlists.add(playlist);
                log.accept("SAMPLE playlist role=" + input.role() + " segments=" + playlist.segments().size() + " seconds=" + playlist.durationSeconds());
            }
            var review = DownloadReview.of(playlists, declaredBandwidth);
            boolean largeApproved = false;
            if (full) {
                stage = "CONFIRMATION";
                Path previewDirectory = directory;
                temporary.add(directory.resolve("preview.mp4"));
                if (!approve(review, r -> feedback.approve(r, approval,
                        new TransferFeedback.Preview(HlsPreview.maximumMillis(playlists),
                                millis -> HlsPreview.create(playlists, previewDirectory, fetcher, tools, feedback, millis))),
                        log, feedback.alwaysReview())) return Result.CANCELLED;
                largeApproved = review.needsConfirmation();
            }
            int allCompleted = 0;
            int allSegments = playlists.stream().mapToInt(p -> full ? p.segments().size() : Math.min(2, p.segments().size())).sum();
            long stagedBytes = 0;
            for (int i = 0; i < playlists.size(); i++) {
                checkCancelled(feedback);
                feedback.phase("FETCHING");
                stage = "SEGMENTS_" + plan.inputs().get(i).role();
                Path inputFile = directory.resolve("input-" + i + ".ts"); temporary.add(inputFile);
                try (var output = Files.newOutputStream(inputFile, StandardOpenOption.CREATE_NEW)) {
                    int completed = 0;
                    var selectedSegments = full ? playlists.get(i).segments() : playlists.get(i).segments().stream().limit(2).toList();
                    for (var segment : selectedSegments) {
                        checkCancelled(feedback);
                        if (full) requireSpace(directory, stagedBytes + 16L * 1024 * 1024);
                        var resource = fetch(fetcher, segment.uri(), 8 * 1024 * 1024, budget, requests);
                        // Metadata can understate size. Pause once on an unexpected actual-size crossing.
                        if (full && !largeApproved && budget.bytes > DownloadReview.WARNING_BYTES) {
                            if (!approve(review.withTransferredBytes(budget.bytes), approval, log)) return Result.CANCELLED;
                            largeApproved = true;
                        }
                        byte[] bytes = resource.bytes();
                        // Narrow MPEG-TS probe only: no guessing fMP4, image wrapping or encryption.
                        if (bytes.length < 188 || bytes.length % 188 != 0) throw new IllegalStateException("Not whole TS packets");
                        for (int offset = 0; offset < bytes.length; offset += 188) {
                            if (bytes[offset] != 0x47) throw new IllegalStateException("Unsupported segment framing");
                        }
                        output.write(bytes);
                        stagedBytes += bytes.length;
                        completed++;
                        allCompleted++;
                        if (allCompleted % 5 == 0 || allCompleted == allSegments) feedback.progress(allCompleted, allSegments, budget.bytes);
                        if (full && (completed % 25 == 0 || completed == selectedSegments.size())) {
                            log.accept("SAMPLE_PROGRESS input=" + i + " segments=" + completed + "/" + selectedSegments.size() + " payloadBytes=" + budget.bytes);
                        }
                    }
                }
                log.accept("SAMPLE acquired input=" + i + " bytes=" + Files.size(inputFile));
                stage = "PROBE_INPUT_" + i;
                checkCancelled(feedback);
                feedback.phase("PROBING");
                HlsDownloadSpike.runChecked(List.of(ffprobe.toString(), "-v", "error", "-protocol_whitelist", "file", "-f", "mpegts",
                        "-show_entries", "stream=codec_name,codec_type,width,height,sample_rate,channels:format=duration",
                        "-of", "default=noprint_wrappers=1", inputFile.toString()), directory.resolve("input-" + i + "-probe.log"), 10000);
            }
            stage = "REMUX";
            checkCancelled(feedback);
            feedback.phase("REMUXING");
            if (full) requireSpace(directory, stagedBytes * 2);
            Path partial = directory.resolve(full ? "video.part" : "sample.part"); temporary.add(partial);
            var command = new ArrayList<>(List.of(ffmpeg.toString(), "-hide_banner", "-nostdin", "-n", "-v", "error", "-xerror"));
            for (int i = 0; i < playlists.size(); i++) command.addAll(List.of("-protocol_whitelist", "file", "-f", "mpegts", "-i", directory.resolve("input-" + i + ".ts").toString()));
            // Only unknown embedded audio is optional; a selected rendition must be present.
            String audioMap = playlists.size() == 2 ? "1:a:0"
                    : plan.choice().audioSource() == HlsCandidateCatalog.AudioSource.UNKNOWN ? "0:a:0?" : "0:a:0";
            command.addAll(List.of("-map", "0:v:0", "-map", audioMap,
                    "-c", "copy", "-movflags", "+faststart", "-f", "mp4", partial.toString()));
            HlsDownloadSpike.runChecked(command, directory.resolve("remux.log"), full ? 60000 : 10000);
            stage = "DECODE";
            checkCancelled(feedback);
            feedback.phase("VERIFYING");
            HlsDownloadSpike.runChecked(List.of(ffmpeg.toString(), "-hide_banner", "-nostdin", "-v", "error", "-xerror",
                    "-err_detect", "explode", "-protocol_whitelist", "file", "-f", "mov", "-i", partial.toString(),
                    "-map", "0", "-f", "null", "-"), directory.resolve("decode.log"), full ? 120000 : 15000);
            stage = "OUTPUT_PROBE";
            HlsDownloadSpike.runChecked(List.of(ffprobe.toString(), "-v", "error", "-protocol_whitelist", "file", "-f", "mov",
                    "-show_entries", "stream=codec_name,codec_type,width,height,sample_rate,channels:format=duration",
                    "-of", "default=noprint_wrappers=1", partial.toString()), directory.resolve("output-probe.log"), 10000);
            if (full) verifyDuration(Files.readAllLines(directory.resolve("output-probe.log")), playlists.getFirst().durationSeconds().doubleValue());
            Path result = directory.resolve(full ? "video.mp4" : "sample.mp4");
            checkCancelled(feedback);
            Files.move(partial, result);
            feedback.file(result);
            log.accept("SAMPLE_PASS bytes=" + budget.bytes + " requests=" + budget.requests
                    + (full ? "; ALL selected segments acquired; selected streams mapped; duration and complete decode passed" : "; selected streams mapped; full sample decode passed; NOT full-video validation"));
            log.accept("SAMPLE_FILE " + result);
            return Result.SUCCESS;
        } catch (Exception failure) {
            processMayBeRunning = failure instanceof FfmpegTransfer.ProcessNotStoppedException;
            if (processMayBeRunning) feedback.cleanupUncertain();
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            if (feedback.cancelled() || failure instanceof InterruptedException) {
                log.accept("SAMPLE_CANCELLED; cleaning owned partials");
                return Result.CANCELLED;
            }
            String reason = failure instanceof BoundedResourceFetcher.FetchException f ? f.diagnosticCode() : failure.getClass().getSimpleName();
            feedback.failure(stage + "_" + reason);
            log.accept("SAMPLE_FAILED stage=" + stage + " reason=" + reason + "; no bypass or retry; output not published");
            return Result.FAILED;
        } finally {
            boolean cleaned = !processMayBeRunning;
            if (!processMayBeRunning) for (Path path : temporary) {
                try { Files.deleteIfExists(path); }
                catch (Exception failure) { cleaned = false; feedback.cleanupUncertain(); log.accept("SAMPLE_CLEANUP_FAILED; retain run directory for inspection"); }
            }
            if (cleaned) { feedback.cleanupComplete(); log.accept("SAMPLE_CLEANUP_COMPLETE; owned partials removed or never created"); }
        }
    }
    static boolean approve(DownloadReview review, Predicate<DownloadReview> approval, Consumer<String> log) {
        return approve(review, approval, log, false);
    }
    static boolean approve(DownloadReview review, Predicate<DownloadReview> approval, Consumer<String> log, boolean alwaysReview) {
        log.accept("DOWNLOAD_REVIEW " + review.summary());
        if (!alwaysReview && !review.needsConfirmation()) return true;
        if (approval.test(review)) { log.accept("DOWNLOAD_CONFIRMED for this reviewed transfer only"); return true; }
        log.accept("DOWNLOAD_CANCELLED; no output published");
        return false;
    }
    private static void checkCancelled(TransferFeedback feedback) throws InterruptedException {
        if (feedback.cancelled() || Thread.currentThread().isInterrupted()) throw new InterruptedException("Transfer cancelled");
    }

    private static void requireSpace(Path directory, long additionalBytes) throws java.io.IOException {
        // Best-effort headroom for staging/remux, not a reservation against other applications.
        if (Files.getFileStore(directory).getUsableSpace() < additionalBytes + 64L * 1024 * 1024) {
            throw new java.io.IOException("Insufficient staging space");
        }
    }

    private static BoundedResourceFetcher.Resource fetch(HlsAcquisition.Fetch fetcher, URI uri, int maxBytes,
                                                         Budget budget, BoundedResourceFetcher.RequestBudget requests) throws Exception {
        long remainingMillis = budget.full ? 10000 : 40000 - (System.nanoTime() - budget.started) / 1_000_000;
        long remainingBytes = budget.full ? maxBytes : 16L * 1024 * 1024 - budget.bytes;
        if (remainingMillis < 1 || remainingBytes < 1) throw new IllegalStateException("Sample budget exhausted");
        var resourceRequests = budget.full ? new BoundedResourceFetcher.RequestBudget(3) : requests;
        int before = resourceRequests.used();
        var resource = fetcher.get(uri, new BoundedResourceFetcher.Limits((int)Math.min(maxBytes, remainingBytes), 2,
                Duration.ofMillis(Math.min(remainingMillis, 10000)), Duration.ofSeconds(3), Duration.ofSeconds(5)), resourceRequests);
        budget.requests += resourceRequests.used() - before;
        budget.bytes += resource.size();
        return resource;
    }
    private static final class Budget {
        final long started = System.nanoTime(); long bytes; int requests;
        final boolean full;
        Budget(boolean full) { this.full = full; }
    }

    static void verifyDuration(List<String> probe, double expected) {
        double actual = Double.parseDouble(probe.stream().filter(s -> s.startsWith("duration=")).findFirst().orElseThrow().substring(9));
        if (!Double.isFinite(expected) || expected <= 0 || !Double.isFinite(actual) || Math.abs(actual - expected) > 1.0) {
            throw new IllegalStateException("Output duration differs from complete selected playlist");
        }
    }

    static String describe(String body) {
        Set<String> known = Set.of("#EXTM3U", "#EXTINF", "#EXT-X-VERSION", "#EXT-X-TARGETDURATION", "#EXT-X-MEDIA-SEQUENCE",
                "#EXT-X-PLAYLIST-TYPE", "#EXT-X-ENDLIST", "#EXT-X-KEY", "#EXT-X-MAP", "#EXT-X-BYTERANGE", "#EXT-X-DISCONTINUITY",
                "#EXT-X-ALLOW-CACHE", "#EXT-X-INDEPENDENT-SEGMENTS", "#EXT-X-STREAM-INF", "#EXT-X-MEDIA", "#EXT-X-PROGRAM-DATE-TIME");
        var tags = new TreeSet<String>();
        int references = 0;
        for (String line : body.lines().map(String::strip).toList()) {
            if (line.startsWith("#EXT")) {
                String tag = line.split(":", 2)[0]; tags.add(known.contains(tag) ? tag : "OTHER_TAG");
            } else if (!line.isEmpty() && !line.startsWith("#")) references++;
        }
        return "hlsHeader=" + body.startsWith("#EXTM3U") + " characters=" + body.length() + " references=" + references + " tags=" + tags;
    }
}
