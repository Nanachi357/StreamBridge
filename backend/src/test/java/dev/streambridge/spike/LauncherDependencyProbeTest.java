package dev.streambridge.spike;

import org.junit.jupiter.api.Test;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class LauncherDependencyProbeTest {
    private static List<String> command(String mode) {
        String executable = System.getProperty("os.name").startsWith("Windows") ? "javaw.exe" : "java";
        return List.of(Path.of(System.getProperty("java.home"), "bin", executable).toString(),
                "-cp", System.getProperty("java.class.path"), Fixture.class.getName(), mode);
    }
    @Test void retainsBoundedPrefixButConsumesWholeStream() throws Exception {
        var stream = new ByteArrayInputStream("x".repeat(100_000).getBytes(StandardCharsets.UTF_8));
        String result = LauncherDependencyProbe.outputPrefix(stream);
        assertEquals("x".repeat(8192) + "\n[truncated]", result);
        assertEquals(0, stream.available());
    }
    @Test void capturesBothStreamsAndExitCodeWithoutDeadlock() throws Exception {
        var result = new StringWriter();
        LauncherDependencyProbe.runCommand(command("verbose"), Duration.ofSeconds(10), new PrintWriter(result, true));
        String log = result.toString();
        assertTrue(log.contains("exitCode=0"));
        assertTrue(log.contains("stdout:\nOUT"));
        assertTrue(log.contains("stderr:\nERR"));
        assertTrue(log.contains("[truncated]"));
        // Gradle's test classpath is long; the limit applies to captured output, not command metadata.
        assertTrue(log.length() - command("verbose").toString().length() < 17_000);
    }
    @Test void nonzeroExitPreservesActualCodeAndStderr() {
        var result = new StringWriter();
        assertThrows(IOException.class, () -> LauncherDependencyProbe.runCommand(
                command("fail"), Duration.ofSeconds(10), new PrintWriter(result, true)));
        assertTrue(result.toString().contains("exitCode=17 exitHex=0x11"));
        assertTrue(result.toString().contains("fixture failure"));
    }
    @Test void timeoutIsDistinctAndOwnedChildIsStopped() {
        var result = new StringWriter();
        assertThrows(IOException.class, () -> LauncherDependencyProbe.runCommand(
                command("sleep"), Duration.ofMillis(500), new PrintWriter(result, true)));
        assertTrue(result.toString().contains("outcome=TIMEOUT"));
        long pid = result.toString().lines().filter(line -> line.startsWith("pid="))
                .mapToLong(line -> Long.parseLong(line.substring(4))).findFirst().orElseThrow();
        assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
    }
    @Test void startFailurePreservesOsException() {
        var result = new StringWriter();
        IOException failure = assertThrows(IOException.class, () -> LauncherDependencyProbe.runCommand(
                List.of(Path.of("missing-probe-executable-7e66334d").toAbsolutePath().toString()),
                Duration.ofSeconds(1), new PrintWriter(result, true)));
        assertTrue(failure.getMessage().contains("missing-probe-executable"));
        assertTrue(result.toString().contains("command="));
    }
    public static final class Fixture {
        public static void main(String[] args) throws Exception {
            switch (args[0]) {
                case "verbose" -> {
                    System.out.print("OUT" + "x".repeat(100_000));
                    System.err.print("ERR" + "y".repeat(100_000));
                }
                case "fail" -> { System.err.println("fixture failure"); System.exit(17); }
                case "sleep" -> Thread.sleep(30_000);
                default -> throw new IllegalArgumentException();
            }
        }
    }
}
