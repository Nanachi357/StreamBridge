package dev.streambridge.spike;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;

/** Controlled HLS-to-MP4 experiment. No arbitrary URLs or production downloader. */
public final class HlsDownloadSpike {
    public static void main(String[] args) throws IOException, InterruptedException {
        boolean autoPlay = false;
        boolean withAudio = false;
        boolean slow = false;
        boolean cancelOnEnter = false;
        double cancelAtSeconds = -1;
        int processTimeoutMs = 30_000;
        for (String arg : args) {
            if (arg.equals("--auto-play")) {
                autoPlay = true;
            } else if (arg.equals("--with-audio")) {
                withAudio = true;
            } else if (arg.equals("--slow")) {
                slow = true;
            } else if (arg.equals("--cancel-on-enter")) {
                cancelOnEnter = true;
            } else if (arg.startsWith("--cancel-at-seconds=")) {
                cancelAtSeconds = Double.parseDouble(arg.substring("--cancel-at-seconds=".length()));
                if (!Double.isFinite(cancelAtSeconds) || cancelAtSeconds <= 0 || cancelAtSeconds >= 7) {
                    throw new IllegalArgumentException("Cancellation point must be between 0 and 7 seconds, exclusive");
                }
            } else if (arg.startsWith("--process-timeout-ms=")) {
                processTimeoutMs = Integer.parseInt(arg.substring("--process-timeout-ms=".length()));
                if (processTimeoutMs < 1 || processTimeoutMs > 60_000) {
                    throw new IllegalArgumentException("Process timeout must be between 1 and 60000 ms");
                }
            } else {
                throw new IllegalArgumentException("Unknown argument: " + arg);
            }
        }
        String toolDirectory = System.getenv("STREAMBRIDGE_FFMPEG_DIR");
        if (toolDirectory == null || toolDirectory.isBlank()) {
            throw new IllegalArgumentException("Set STREAMBRIDGE_FFMPEG_DIR to the directory containing ffmpeg.exe and ffprobe.exe");
        }
        Path ffmpeg = executable(Path.of(toolDirectory), "ffmpeg.exe");
        Path ffprobe = executable(Path.of(toolDirectory), "ffprobe.exe");
        Path fixtureDirectory = Path.of(withAudio
                ? System.getProperty("streambridge.hlsAvFixture", "build/hls-av-fixture")
                : System.getProperty("streambridge.hlsFixture", "build/hls-fixture"));
        Path outputRoot = Path.of(System.getProperty("streambridge.hlsOutputs", "build/hls-downloads")).toAbsolutePath();
        var options = new Playwright.CreateOptions().setEnv(Map.of("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1"));

        // The fixture server must outlive both browser discovery AND the FFmpeg download.
        try (var fixture = new LocalHlsFixture(fixtureDirectory);
             Playwright playwright = Playwright.create(options);
             Browser browser = ChromiumLaunch.launch(playwright.chromium(), false);
             BrowserContext context = browser.newContext()) {
            System.out.println("Fixture: " + fixture.origin());
            URI discoveredPlaylist = HlsDiscoverySpike.discover(fixture, context, autoPlay, 60_000);
            // Deliberately restrict this spike to our own fixture, not user-supplied network input.
            if (!discoveredPlaylist.toString().equals(fixture.origin() + "/media/stream.m3u8")) {
                throw new IllegalStateException("Refusing a resource outside the controlled fixture");
            }

            Files.createDirectories(outputRoot);
            Path runDirectory = Files.createTempDirectory(outputRoot, "run-");
            Path partial = runDirectory.resolve("video.part");
            Path result = runDirectory.resolve("video.mp4");
            System.out.println("Run directory: " + runDirectory);
            System.out.println("FFmpeg input (observed by browser): " + discoveredPlaylist);
            try {
                // Separate arguments, no shell. -n refuses overwrite; explicit mp4 is needed for .part.
                var command = new ArrayList<>(List.of(ffmpeg.toString(), "-hide_banner", "-stdin", "-n",
                        "-loglevel", "info", "-nostats", "-stats_period", "0.25", "-progress", "pipe:1"));
                // This slows media ingestion, not the network. It makes progress/cancellation reproducible.
                if (slow || cancelOnEnter || cancelAtSeconds > 0) command.addAll(List.of("-readrate", "1"));
                command.addAll(List.of("-rw_timeout", "10000000", "-i", discoveredPlaylist.toString(), "-map", "0:v:0"));
                if (withAudio) command.addAll(List.of("-map", "0:a:0")); // Required audio, not optional '?'.
                command.addAll(List.of("-c", "copy", "-flush_packets", "1", "-movflags", "+faststart",
                        "-f", "mp4", partial.toString()));
                var cancellation = new AtomicBoolean();
                if (cancelOnEnter) {
                    System.out.println("Press Enter to cancel the transfer. EOF does not mean cancellation.");
                    // This manual spike exits after one run. Do not close the shared System.in stream.
                    Thread.ofVirtual().start(() -> {
                        try {
                            var input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
                            if (input.readLine() != null) cancellation.set(true);
                        } catch (IOException failure) {
                            System.err.println("Console input unavailable: " + failure.getMessage());
                        }
                    });
                }
                final double cancellationPoint = cancelAtSeconds;
                var transfer = FfmpegTransfer.run(command, runDirectory.resolve("download.log"),
                        runDirectory.resolve("progress.log"), Duration.ofMillis(processTimeoutMs), Duration.ofSeconds(3),
                        cancellation::get, progress -> {
                            double seconds = progress.mediaTimeUs() / 1_000_000.0;
                            System.out.printf(Locale.ROOT, "Progress: %.2f / 8.00 media seconds (%.1f%%), bytes=%d%n",
                                    seconds, Math.min(99.9, seconds / 8 * 100), progress.bytes());
                            // The known 8s duration belongs to this fixture; real downloads need duration discovery.
                            if (cancellationPoint > 0 && seconds >= cancellationPoint) {
                                try {
                                    long bytes = Files.exists(partial) ? Files.size(partial) : 0;
                                    if (bytes >= 1024) {
                                        System.out.println("Requesting cancellation with an existing partial: " + bytes + " bytes");
                                        cancellation.set(true);
                                    }
                                } catch (IOException failure) {
                                    throw new UncheckedIOException(failure);
                                }
                            }
                        });
                System.out.println("FFmpeg PID " + transfer.pid() + " stopped; forced=" + transfer.forced());
                if (transfer.cancelled()) {
                    long bytes = Files.exists(partial) ? Files.size(partial) : 0;
                    Files.deleteIfExists(partial);
                    if (Files.exists(partial) || Files.exists(result)) throw new IOException("Cancellation cleanup failed");
                    System.out.println("PASS: CANCELLED; removed own partial (" + bytes + " bytes); no published MP4.");
                    return;
                }
                if (cancellationPoint > 0) throw new IOException("Transfer finished before the cancellation check ran");
                if (!Files.isRegularFile(partial) || Files.size(partial) == 0) {
                    throw new IOException("FFmpeg produced no non-empty video file");
                }

                Path probeLog = runDirectory.resolve("probe.log");
                runChecked(List.of(ffprobe.toString(), "-v", "error", "-show_entries",
                        "stream=codec_name,codec_type,width,height,sample_rate,channels,start_time:format=duration", "-of", "default=noprint_wrappers=1",
                        partial.toString()), probeLog, processTimeoutMs);
                verifyFixtureMetadata(probeLog, withAudio);

                // Decode the entire file, not just its header. -xerror fails on decoder errors.
                runChecked(List.of(ffmpeg.toString(), "-hide_banner", "-nostdin", "-v", "error", "-xerror",
                        "-err_detect", "explode", "-i", partial.toString(), "-map", "0", "-f", "null", "-"),
                        runDirectory.resolve("decode.log"), processTimeoutMs);
                if (withAudio) verifySynchronization(ffmpeg, partial, runDirectory, processTimeoutMs);
                Files.move(partial, result); // No REPLACE_EXISTING: never overwrite a completed file.
                System.out.println("PASS: COMPLETED (100%); FFmpeg exited 0; metadata and full decoding verified.");
                System.out.println("Saved MP4: " + result);
            } catch (IOException | InterruptedException | RuntimeException failure) {
                // Only delete the temporary file owned by this unique run; preserve diagnostic logs.
                try {
                    if (failure instanceof FfmpegTransfer.ProcessNotStoppedException) {
                        System.err.println("Termination could not be confirmed; retaining partial: " + partial);
                    } else {
                        Files.deleteIfExists(partial);
                    }
                } catch (IOException cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                }
                System.err.println("Download/validation failed. Logs retained in: " + runDirectory);
                throw failure;
            }

            // Check the saved local MP4 in Chromium, not the source HLS player.
            // During Process.waitFor above no browser events were needed; this resumes dispatch.
            Page preview = context.newPage();
            preview.navigate(result.toUri().toString());
            preview.locator("video").evaluate("video => { video.muted = true; return video.play(); }");
            preview.waitForFunction("() => document.querySelector('video').currentTime >= 1",
                    null, new Page.WaitForFunctionOptions().setTimeout(10_000));
            System.out.println("PASS: saved MP4 plays in Chromium (time advanced >= 1 second).");
        }
        System.out.println("Browser, Playwright, fixture server and external processes finished.");
    }

    static Path executable(Path directory, String name) throws IOException {
        Path path = directory.resolve(name).toAbsolutePath();
        if (!Files.isRegularFile(path)) throw new IOException("Missing executable: " + path);
        return path;
    }

    private static void verifyFixtureMetadata(Path probeLog, boolean withAudio) throws IOException {
        List<String> lines = Files.readAllLines(probeLog);
        long streamCount = lines.stream().filter(line -> line.startsWith("codec_type=")).count();
        String durationValue = lines.stream().filter(line -> line.startsWith("duration="))
                .findFirst().orElseThrow(() -> new IOException("Missing duration; see " + probeLog))
                .substring("duration=".length());
        double duration = Double.parseDouble(durationValue);
        if (streamCount != (withAudio ? 2 : 1) || !lines.contains("codec_type=video") || !lines.contains("codec_name=h264")
                || !lines.contains("width=320") || !lines.contains("height=180")
                || (withAudio && (!lines.contains("codec_type=audio") || !lines.contains("codec_name=aac")
                    || !lines.contains("sample_rate=48000") || !lines.contains("channels=1")))
                || !Double.isFinite(duration) || Math.abs(duration - 8.0) > 0.1) {
            throw new IOException("Output differs from the expected fixture; see " + probeLog);
        }
        if (withAudio) {
            List<Double> starts = lines.stream().filter(line -> line.startsWith("start_time="))
                    .map(line -> Double.parseDouble(line.substring("start_time=".length()))).toList();
            if (starts.size() != 2 || starts.stream().anyMatch(t -> !Double.isFinite(t))
                    || Math.abs(starts.get(0) - starts.get(1)) > 0.05) {
                throw new IOException("Unexpected audio/video start timestamps; see " + probeLog);
            }
        }
        System.out.println("PASS: H.264 320x180" + (withAudio ? " + AAC 48000 Hz mono" : " (video only)")
                + ", duration=" + duration + " seconds.");
    }

    static void verifySynchronization(Path ffmpeg, Path video, Path directory, int timeoutMs)
            throws IOException, InterruptedException {
        Path flashesLog = directory.resolve("flashes.log");
        Path beepsLog = directory.resolve("beeps.log");
        runChecked(List.of(ffmpeg.toString(), "-hide_banner", "-nostdin", "-i", video.toString(),
                "-vf", "blackdetect=d=0.1:pix_th=0.5", "-an", "-f", "null", "-"), flashesLog, timeoutMs);
        runChecked(List.of(ffmpeg.toString(), "-hide_banner", "-nostdin", "-i", video.toString(),
                "-af", "silencedetect=n=-35dB:d=0.05", "-vn", "-f", "null", "-"), beepsLog, timeoutMs);
        List<Double> flashes = markers(flashesLog, "black_end:\\s*([0-9.]+)");
        List<Double> beeps = markers(beepsLog, "silence_end:\\s*([0-9.]+)");
        if (flashes.size() != 3 || beeps.size() != 3) throw new IOException("Expected markers at 2, 4, 6s: " + directory);
        for (int i = 0; i < 3; i++) {
            double expected = (i + 1) * 2;
            double offset = Math.abs(flashes.get(i) - beeps.get(i));
            if (Math.abs(flashes.get(i) - expected) > 0.08 || Math.abs(beeps.get(i) - expected) > 0.08 || offset > 0.08) {
                throw new IOException("Flash/beep synchronization differs by more than 80ms: " + directory);
            }
            System.out.printf(Locale.ROOT, "PASS: flash %.3fs / beep %.3fs; offset %.1f ms%n",
                    flashes.get(i), beeps.get(i), offset * 1000);
        }
    }

    private static List<Double> markers(Path log, String regex) throws IOException {
        var matcher = Pattern.compile(regex).matcher(Files.readString(log));
        var result = new ArrayList<Double>();
        while (matcher.find()) {
            double time = Double.parseDouble(matcher.group(1));
            if (time > 0.5 && time < 7.5) result.add(time); // Ignore initial priming and final silence/black.
        }
        return result;
    }

    static void runChecked(List<String> command, Path log, int timeoutMs)
            throws IOException, InterruptedException {
        // Redirect both streams to a file, so a full stdout/stderr pipe cannot deadlock waitFor.
        Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            process.getOutputStream().close();
            if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                throw new IOException("External process exceeded " + timeoutMs + " ms; see " + log);
            }
            if (process.exitValue() != 0) {
                throw new IOException("External process exited " + process.exitValue() + "; see " + log);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw e;
        } finally {
            if (process.isAlive()) {
                // Validation commands have an emergency timeout guard, not interactive cancellation.
                FfmpegTransfer.terminate(process);
            }
        }
    }
}
