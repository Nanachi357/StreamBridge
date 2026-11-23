package dev.streambridge.spike;

import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class DiagnosticDeadlineTest {
    @Test void terminatesAnUnresponsiveDiagnostic() throws Exception { assertEquals(124, run("expire")); }
    @Test void closingDisarmsTheDeadline() throws Exception { assertEquals(0, run("close")); }
    @Test void silentWorkerEndsBeforeItsLongOverallBudget() throws Exception { assertEquals(124, run("silent")); }
    @Test void regularPulsesKeepAResponsiveWorkerAlive() throws Exception { assertEquals(0, run("pulse")); }
    @Test void unlimitedObservationHasNoOverallDeadlineWhileLoopIsResponsive() throws Exception { assertEquals(0, run("unlimited-pulse")); }
    @Test void unlimitedObservationStillTerminatesAHungWorker() throws Exception { assertEquals(124, run("unlimited-silent")); }
    @Test void startupMayOutlastObservationSilence() throws Exception { assertEquals(0, run("startup-delayed")); }
    @Test void observationBudgetStartsAfterStartup() throws Exception { assertEquals(0, run("startup-finite")); }
    @Test void startupStillHasAHardDeadlineEvenWithPulses() throws Exception {
        var result = runProbe("startup-hung");
        assertEquals(124, result.exit()); assertTrue(result.output().contains("STARTUP_TIMEOUT"));
        assertFalse(result.output().contains("WORKER_TIMEOUT"));
    }
    @Test void aStallAfterStartupUsesTheShortSilenceLimit() throws Exception {
        var result = runProbe("startup-then-silent");
        assertEquals(124, result.exit()); assertTrue(result.output().contains("WORKER_TIMEOUT"));
        assertFalse(result.output().contains("STARTUP_TIMEOUT"));
    }
    @Test void observationOverallDeadlineStillAppliesDespitePulses() throws Exception {
        var result = runProbe("startup-then-expire");
        assertEquals(124, result.exit()); assertTrue(result.output().contains("WORKER_TIMEOUT"));
    }
    @Test void closingDuringStartupDisarmsIt() throws Exception { assertEquals(0, run("startup-close")); }
    @Test void observationCannotRestartItsBudget() {
        try (var guard = DiagnosticDeadline.starting(Duration.ofSeconds(5), Duration.ZERO, Duration.ofSeconds(5), () -> {}, () -> {})) {
            guard.beginObservation();
            assertThrows(IllegalStateException.class, guard::beginObservation);
        }
    }
    @Test void closedStartupCannotBeReactivated() {
        var guard = DiagnosticDeadline.starting(Duration.ofSeconds(5), Duration.ZERO, Duration.ofSeconds(5), () -> {}, () -> {});
        guard.close(); assertThrows(IllegalStateException.class, guard::beginObservation);
    }

    private int run(String mode) throws Exception {
        return runProbe(mode).exit();
    }
    private record Result(int exit, String output) {}
    private Result runProbe(String mode) throws Exception {
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        var process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", executable).toString(),
                "-cp", Path.of(DiagnosticDeadlineTest.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString(),
                Probe.class.getName(), mode).redirectErrorStream(true).start();
        try {
            assertTrue(process.waitFor(10, TimeUnit.SECONDS), "Child diagnostic must terminate");
            return new Result(process.exitValue(), new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
        } finally { process.destroyForcibly(); }
    }

    public static class Probe {
        public static void main(String[] args) throws Exception {
            String mode = args[0];
            if (mode.startsWith("startup-")) {
                boolean hung = mode.equals("startup-hung") || mode.equals("startup-close");
                var startup = Duration.ofMillis(hung ? 500 : 5000);
                var budget = mode.equals("startup-finite") || mode.equals("startup-then-expire") ? Duration.ofMillis(1000) : Duration.ZERO;
                try (var guard = DiagnosticDeadline.starting(startup, budget, Duration.ofMillis(500),
                        () -> System.out.println("STARTUP_TIMEOUT"), () -> System.out.println("WORKER_TIMEOUT"))) {
                    if (mode.equals("startup-close")) { guard.close(); Thread.sleep(1500); return; }
                    if (mode.equals("startup-hung")) {
                        for (int i = 0; i < 20; i++) { guard.pulse(); Thread.sleep(100); }
                        return;
                    }
                    Thread.sleep(1200); // Longer than observation silence (and finite observation budget).
                    guard.beginObservation();
                    if (mode.equals("startup-then-silent")) { Thread.sleep(2000); return; }
                    int count = mode.equals("startup-finite") ? 6 : mode.equals("startup-delayed") ? 45 : 20;
                    for (int i = 0; i < count; i++) { guard.pulse(); Thread.sleep(100); }
                }
                return;
            }
            if(args[0].startsWith("unlimited-")) {
                try(var guard=DiagnosticDeadline.inactivityOnly(Duration.ofMillis(500),()->{})) {
                    for(int i=0;i<20;i++){if(args[0].equals("unlimited-pulse"))guard.pulse();Thread.sleep(100);}
                }
                return;
            }
            if(args[0].equals("silent")||args[0].equals("pulse")) {
                try(var guard=new DiagnosticDeadline(Duration.ofSeconds(30),Duration.ofMillis(500),()->{})) {
                    for(int i=0;i<15;i++){if(args[0].equals("pulse"))guard.pulse();Thread.sleep(100);}
                }
                return;
            }
            var deadline = new DiagnosticDeadline(Duration.ofMillis(300), () -> { });
            if (args[0].equals("close")) deadline.close();
            Thread.sleep(1000);
        }
    }
}
