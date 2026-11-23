package dev.streambridge.spike;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.Duration;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;

class LocalUiStartupTest {
    @TempDir Path root;

    private LocalUiSession session(long delay) throws Exception {
        return new LocalUiSession(root, (url, directory) -> new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString(),
                "-cp", System.getProperty("streambridge.testClasspath"), Probe.class.getName(), Long.toString(delay))
                .redirectErrorStream(true).start());
    }
    @Test void parentDoesNotCancelAChildStillStartingAfterFortyFiveSeconds() throws Exception {
        try (var session = session(47_000)) {
            session.start("https://example.org");
            await(() -> !session.snapshot().get("phase").equals("STARTING"), Duration.ofSeconds(75));
            assertEquals("DISCOVERING", session.snapshot().get("phase"));
            assertEquals("", session.snapshot().get("error"));
            session.cancel(session.snapshot().get("discoveryId").toString());
            await(() -> session.snapshot().get("status").equals("CANCELLED")
                    && Boolean.FALSE.equals(session.snapshot().get("workerAlive")), Duration.ofSeconds(20));
            assertEquals("CANCELLED", session.snapshot().get("status"));
        }
    }
    @Test void userCanCancelDuringStartupWithoutWaitingForTheStartupBudget() throws Exception {
        try (var session = session(1500)) {
            session.start("https://example.org");
            session.cancel(session.snapshot().get("discoveryId").toString());
            await(() -> session.snapshot().get("status").equals("CANCELLED"), Duration.ofSeconds(20));
            assertEquals("", session.snapshot().get("error"));
            assertEquals(false, session.snapshot().get("workerAlive"));
        }
    }
    private static void await(BooleanSupplier condition, Duration budget) throws Exception {
        long end = System.nanoTime() + budget.toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < end) Thread.sleep(25);
        assertTrue(condition.getAsBoolean(), "Worker did not reach expected state");
    }
    public static class Probe {
        public static void main(String[] args) throws Exception {
            Thread.sleep(Long.parseLong(args[0]));
            UiProtocol.emit("phase", "DISCOVERING");
            var input = new BufferedReader(new InputStreamReader(System.in));
            for (String line; (line = input.readLine()) != null;) {
                if (line.equals("cancel")) {
                    UiProtocol.emit("cleanupComplete", true);
                    UiProtocol.emit("terminal", "CANCELLED"); return;
                }
            }
        }
    }
}
