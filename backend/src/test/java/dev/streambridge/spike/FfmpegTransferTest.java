package dev.streambridge.spike;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

/** No installed browser/FFmpeg required. A child JVM simulates process lifecycle edge cases. */
class FfmpegTransferTest {
    @TempDir Path directory;

    @Test void parsesMicrosecondsAndUnknownInitialValues() {
        assertEquals(new FfmpegTransfer.Progress(2_500_000, 4096, false), FfmpegTransfer.parse(
                Map.of("out_time_us", "2500000", "total_size", "4096", "progress", "continue")));
        assertNull(FfmpegTransfer.parse(Map.of("out_time_us", "N/A", "progress", "continue")));
        assertNull(FfmpegTransfer.parse(Map.of("out_time_us", "-1")));
    }

    @Test void successfulExitDeliversFinalProgress() throws Exception {
        var seen = new AtomicReference<FfmpegTransfer.Progress>();
        var result = run("success", Duration.ofSeconds(10), Duration.ofSeconds(2), () -> false, seen::set);
        assertFalse(result.cancelled());
        assertTrue(seen.get().end());
        assertStopped(result.pid());
    }

    @Test void gracefulCancellationRemainsCancelledEvenWithExitZeroAndProgressEnd() throws Exception {
        var cancel = new AtomicBoolean();
        var result = run("graceful", Duration.ofSeconds(10), Duration.ofSeconds(2), cancel::get,
                progress -> cancel.set(true));
        assertTrue(result.cancelled());
        assertFalse(result.forced());
        assertTrue(Files.readString(directory.resolve("progress.log")).contains("progress=end"));
        assertStopped(result.pid());
    }

    @Test void ignoredQuitGetsForcedTermination() throws Exception {
        var cancel = new AtomicBoolean();
        var result = run("ignore", Duration.ofSeconds(10), Duration.ofMillis(100), cancel::get,
                progress -> cancel.set(true));
        assertTrue(result.cancelled());
        assertTrue(result.forced());
        assertStopped(result.pid());
    }

    @Test void timeoutStopsChildBeforeReturning() throws Exception {
        var failure = assertThrows(java.io.IOException.class, () -> run("ignore", Duration.ofMillis(1500),
                Duration.ofMillis(100), () -> false, progress -> { }));
        assertTrue(failure.getMessage().contains("exceeded"));
        assertStopped(recordedPid());
    }

    @Test void nonzeroExitIsFailureNotCompletion() {
        var failure = assertThrows(java.io.IOException.class, () -> run("fail", Duration.ofSeconds(10),
                Duration.ofMillis(100), () -> false, progress -> { }));
        assertTrue(failure.getMessage().contains("exited 7"));
    }

    @Test void interruptionStopsChildAndPreservesInterruptFlag() throws Exception {
        var caught = new AtomicReference<Throwable>();
        var preserved = new AtomicBoolean();
        Thread worker = Thread.ofPlatform().start(() -> {
            try {
                run("ignore", Duration.ofSeconds(10), Duration.ofMillis(100), () -> false,
                        progress -> Thread.currentThread().interrupt());
            } catch (Throwable failure) {
                caught.set(failure);
                preserved.set(Thread.currentThread().isInterrupted());
            }
        });
        assertTrue(worker.join(Duration.ofSeconds(15)));
        assertInstanceOf(InterruptedException.class, caught.get());
        assertTrue(preserved.get());
        assertStopped(recordedPid());
    }

    private FfmpegTransfer.Result run(String mode, Duration timeout, Duration grace,
                                     java.util.function.BooleanSupplier cancel,
                                     java.util.function.Consumer<FfmpegTransfer.Progress> progress) throws Exception {
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        Path java = Path.of(System.getProperty("java.home"), "bin", executable);
        Path classes = Path.of(Child.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        return FfmpegTransfer.run(List.of(java.toString(), "-cp", classes.toString(), Child.class.getName(),
                mode, directory.resolve("pid").toString()), directory.resolve("stderr.log"),
                directory.resolve("progress.log"), timeout, grace, cancel, progress);
    }

    private long recordedPid() throws Exception {
        return Long.parseLong(Files.readString(directory.resolve("pid")));
    }

    private static void assertStopped(long pid) {
        assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false), "Child still alive: " + pid);
    }

    public static class Child {
        public static void main(String[] args) throws Exception {
            Files.writeString(Path.of(args[1]), Long.toString(ProcessHandle.current().pid()));
            if (args[0].equals("fail")) System.exit(7);
            if (!args[0].equals("success")) {
                System.out.print("out_time_us=2000000\ntotal_size=4096\nprogress=continue\n");
                System.out.flush();
                if (args[0].equals("ignore")) Thread.sleep(60_000);
                else new BufferedReader(new InputStreamReader(System.in)).readLine();
            }
            System.out.print("out_time_us=8000000\ntotal_size=8192\nprogress=end\n");
            System.out.flush();
        }
    }
}
