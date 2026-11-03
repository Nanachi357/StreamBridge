package dev.streambridge.spike;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Experiment only: one FFmpeg process, structured progress and bounded cancellation. */
final class FfmpegTransfer {
    record Progress(long mediaTimeUs, long bytes, boolean end) { }
    record Result(boolean cancelled, boolean forced, long pid) { }
    static final class ProcessNotStoppedException extends IOException {
        ProcessNotStoppedException(long pid) {
            super("Could not stop external process PID " + pid + "; retain its partial file for diagnosis");
        }
    }

    static Result run(List<String> command, Path stderrLog, Path progressLog,
                      Duration timeout, Duration cancelGrace, BooleanSupplier cancelRequested,
                      Consumer<Progress> onProgress) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command).redirectError(stderrLog.toFile()).start();
        var latest = new AtomicReference<Progress>();
        var readFailure = new AtomicReference<IOException>();
        // Drain stdout independently: neither a full pipe nor a slow consumer may block FFmpeg.
        // A latest-value slot deliberately coalesces progress instead of growing an unbounded queue.
        Thread reader = Thread.ofVirtual().start(() -> {
            try (var input = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
                 var log = Files.newBufferedWriter(progressLog, StandardCharsets.UTF_8)) {
                var block = new HashMap<String, String>();
                for (String line; (line = input.readLine()) != null;) {
                    log.write(line);
                    log.newLine();
                    int separator = line.indexOf('=');
                    if (separator < 0) continue;
                    block.put(line.substring(0, separator), line.substring(separator + 1));
                    if (line.startsWith("progress=")) {
                        Progress progress = parse(block);
                        if (progress != null) latest.set(progress);
                        block.clear();
                        log.flush();
                    }
                }
            } catch (IOException failure) {
                readFailure.set(failure);
            }
        });
        long started = System.nanoTime();
        Progress delivered = null;
        try {
            while (process.isAlive()) {
                Progress next = latest.get();
                if (next != null && next != delivered) {
                    onProgress.accept(next);
                    delivered = next;
                }
                if (readFailure.get() != null) throw readFailure.get();
                if (cancelRequested.getAsBoolean() && process.isAlive()) {
                    // q lets FFmpeg finalize its output. Even exit 0 / progress=end still means CANCELLED.
                    try {
                        process.getOutputStream().write("q\n".getBytes(StandardCharsets.US_ASCII));
                        process.getOutputStream().flush();
                    } catch (IOException closedInput) {
                        // The process may have exited concurrently; otherwise the bounded fallback handles it.
                    }
                    boolean forced = !process.waitFor(cancelGrace.toMillis(), TimeUnit.MILLISECONDS);
                    if (forced) terminate(process);
                    return new Result(true, forced, process.pid());
                }
                if (System.nanoTime() - started >= timeout.toNanos()) {
                    throw new IOException("FFmpeg exceeded " + timeout.toMillis() + " ms; see " + stderrLog);
                }
                process.waitFor(50, TimeUnit.MILLISECONDS);
            }
            if (!reader.join(Duration.ofSeconds(2))) throw new IOException("Progress reader did not finish");
            if (readFailure.get() != null) throw readFailure.get();
            Progress last = latest.get();
            if (last != null && last != delivered) onProgress.accept(last);
            if (process.exitValue() != 0) {
                throw new IOException("FFmpeg exited " + process.exitValue() + "; see " + stderrLog);
            }
            return new Result(false, false, process.pid());
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw failure;
        } finally {
            // Do not return (and let the caller delete .part) until the writer process has stopped.
            boolean interrupted = Thread.interrupted();
            try {
                if (process.isAlive()) terminate(process);
                process.getOutputStream().close();
                if (!reader.join(Duration.ofSeconds(2))) {
                    process.getInputStream().close();
                    reader.interrupt();
                    if (!reader.join(Duration.ofSeconds(2))) throw new IOException("Progress reader is still running");
                }
            } catch (InterruptedException failure) {
                interrupted = true;
                throw failure;
            } finally {
                if (interrupted) Thread.currentThread().interrupt();
            }
        }
    }

    static void terminate(Process process) throws IOException {
        // Cleanup must finish even if another interrupt arrives while waiting for termination.
        boolean interrupted = Thread.interrupted();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        try {
            process.destroyForcibly();
            while (process.isAlive()) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) throw new ProcessNotStoppedException(process.pid());
                try {
                    process.waitFor(remaining, TimeUnit.NANOSECONDS);
                } catch (InterruptedException ignored) {
                    interrupted = true;
                }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    static Progress parse(Map<String, String> block) {
        try {
            long time = Long.parseLong(block.getOrDefault("out_time_us", "N/A"));
            long bytes = Long.parseLong(block.getOrDefault("total_size", "-1"));
            // Unknown values are normal before the first packet. Never display a made-up percentage.
            return time < 0 ? null : new Progress(time, bytes, "end".equals(block.get("progress")));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
