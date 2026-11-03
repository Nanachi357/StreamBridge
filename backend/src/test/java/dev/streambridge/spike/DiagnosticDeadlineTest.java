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

    private int run(String mode) throws Exception {
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        var process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", executable).toString(),
                "-cp", Path.of(DiagnosticDeadlineTest.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString(),
                Probe.class.getName(), mode).redirectErrorStream(true).start();
        try {
            assertTrue(process.waitFor(10, TimeUnit.SECONDS), "Child diagnostic must terminate");
            return process.exitValue();
        } finally { process.destroyForcibly(); }
    }

    public static class Probe {
        public static void main(String[] args) throws Exception {
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
