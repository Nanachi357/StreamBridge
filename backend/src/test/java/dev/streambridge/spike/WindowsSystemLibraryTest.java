package dev.streambridge.spike;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class WindowsSystemLibraryTest {
    @TempDir Path root;
    private Path system() { return Path.of(System.getenv("SystemRoot"), "System32"); }
    private int check(Path dll, StringWriter output) throws Exception {
        return WindowsSystemLibrary.verify(dll, system(), new PrintWriter(output, true));
    }
    @Test @EnabledOnOs(OS.WINDOWS) void actualSystemCompilerLoadsAndCompilesShader() throws Exception {
        var output = new StringWriter();
        assertEquals(0, check(system().resolve("d3dcompiler_47.dll"), output), output::toString);
        assertTrue(output.toString().contains("SYSTEM_D3DCOMPILER_OK:"));
    }
    @Test @EnabledOnOs(OS.WINDOWS) void missingDllHasDistinctDiagnosis() throws Exception {
        var output = new StringWriter();
        assertEquals(25, check(root.resolve("absent.dll"), output), output::toString);
        assertTrue(output.toString().contains("SYSTEM_D3DCOMPILER_MISSING:"));
    }
    @Test @EnabledOnOs(OS.WINDOWS) void corruptedFileIsRejectedBeforeLoading() throws Exception {
        Path broken = root.resolve("broken ' [x] d3dcompiler_47.dll");
        Files.write(broken, new byte[]{77,90,0,0,1});
        var output = new StringWriter();
        assertEquals(26, check(broken, output), output::toString);
        assertTrue(output.toString().contains("INVALID_SIGNATURE"));
    }
    @Test @EnabledOnOs(OS.WINDOWS) void validSignatureAloneDoesNotProveCompilerWorks() throws Exception {
        var output = new StringWriter();
        assertEquals(26, check(system().resolve("version.dll"), output), output::toString);
        assertTrue(output.toString().contains("D3DCompile export missing"), output::toString);
    }
    @Test void pathsAreEncodedDataAndCommandUsesExplicitHiddenSystemPowerShell() throws Exception {
        Path dll = root.resolve("quote ' dollar $ semicolon ;.dll");
        var command = WindowsSystemLibrary.command(dll, root);
        assertEquals(root.resolve("WindowsPowerShell/v1.0/powershell.exe").toString(), command.getFirst());
        assertTrue(command.contains("Hidden"));
        String script = new String(Base64.getDecoder().decode(command.getLast()), StandardCharsets.UTF_16LE);
        assertFalse(script.contains(dll.toString()));
        assertTrue(script.contains(Base64.getEncoder().encodeToString(dll.toString().getBytes(StandardCharsets.UTF_8))));
        assertThrows(IllegalArgumentException.class, () -> WindowsSystemLibrary.command(Path.of("relative.dll"), root));
    }
    private Process child(String mode) throws IOException {
        String java = System.getProperty("os.name").startsWith("Windows") ? "javaw.exe" : "java";
        return new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", java).toString(),
                "-cp", System.getProperty("java.class.path"), Fixture.class.getName(), mode).start();
    }
    @Test void timedOutDiagnosticStopsItsProcessAndDoesNotReportCorruption() throws Exception {
        Process process = child("sleep");
        var output = new StringWriter();
        assertEquals(27, WindowsSystemLibrary.await(process, new PrintWriter(output, true), 300));
        assertFalse(process.isAlive());
        assertTrue(output.toString().contains("CHECK_TIMEOUT"));
    }
    @Test void successWithoutAcknowledgementAndUnexpectedExitAreNotAccepted() throws Exception {
        for (String mode : List.of("empty", "error")) {
            var output = new StringWriter();
            assertEquals(27, WindowsSystemLibrary.await(child(mode), new PrintWriter(output, true), 10_000));
        }
    }
    @Test void launcherExplainsMissingUnusableAndUnavailableChecksSeparately() {
        assertTrue(LauncherSupport.probeFailure(25).contains("відсутня"));
        assertTrue(LauncherSupport.probeFailure(26).contains("підпису або працездатності"));
        assertTrue(LauncherSupport.probeFailure(27).contains("не обов’язково пошкодження"));
    }
    public static final class Fixture {
        public static void main(String[] args) throws Exception {
            if (args[0].equals("sleep")) Thread.sleep(30_000);
            if (args[0].equals("error")) System.exit(1);
        }
    }
}
