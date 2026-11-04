package dev.streambridge.spike;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/** Checks the OS component in an isolated, bounded process; never installs or repairs DLLs. */
final class WindowsSystemLibrary {
    static final int MISSING = 25, UNUSABLE = 26, CHECK_FAILED = 27;
    static int verify(PrintWriter log) {
        if (!System.getProperty("os.name").startsWith("Windows")) return 0;
        try {
            Path system = Path.of(Objects.requireNonNull(System.getenv("SystemRoot")), "System32");
            return verify(system.resolve("d3dcompiler_47.dll"), system, log);
        } catch (Exception failure) {
            failure.printStackTrace(log); return CHECK_FAILED;
        }
    }

    // Explicit paths let tests exercise missing/invalid fixtures without modifying Windows.
    static int verify(Path dll, Path system, PrintWriter log) throws Exception {
        var process = new ProcessBuilder(command(dll, system)).start();
        process.getOutputStream().close();
        return await(process, log, 15_000);
    }

    static List<String> command(Path dll, Path system) throws IOException {
        if (!dll.isAbsolute() || !system.isAbsolute()) throw new IllegalArgumentException("Absolute paths required");
        String script;
        try (var resource = WindowsSystemLibrary.class.getResourceAsStream("/spike/launcher/check-d3dcompiler.ps1")) {
            if (resource == null) throw new IOException("Missing system DLL diagnostic resource");
            script = new String(resource.readAllBytes(), StandardCharsets.UTF_8);
        }
        script = script.replace("@@DLL_PATH_BASE64@@", Base64.getEncoder()
                .encodeToString(dll.toString().getBytes(StandardCharsets.UTF_8)));
        return List.of(system.resolve("WindowsPowerShell/v1.0/powershell.exe").toString(),
                "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden", "-OutputFormat", "Text",
                "-EncodedCommand", Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE)));
    }

    static int await(Process process, PrintWriter log, long timeoutMillis) throws Exception {
        var stdout = new FutureTask<>(() -> LauncherDependencyProbe.outputPrefix(process.getInputStream()));
        var stderr = new FutureTask<>(() -> LauncherDependencyProbe.outputPrefix(process.getErrorStream()));
        Thread.startVirtualThread(stdout); Thread.startVirtualThread(stderr);
        try {
            if (!process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) {
                log.println("systemD3dCompiler=CHECK_TIMEOUT"); return CHECK_FAILED;
            }
            int exit = process.exitValue();
            log.println("systemD3dCompilerExit=" + exit);
            String output = stdout.get(2, TimeUnit.SECONDS);
            log.println(output);
            // Do not accept an unrelated successful PowerShell exit as a completed diagnostic.
            if (exit == 0) return output.contains("SYSTEM_D3DCOMPILER_OK: ") ? 0 : CHECK_FAILED;
            return exit == MISSING || exit == UNUSABLE ? exit : CHECK_FAILED;
        } finally {
            if (process.isAlive()) {
                // Add-Type may own a compiler process; a timeout must not leave it running.
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                FfmpegTransfer.terminate(process);
            }
            try { log.println("systemD3dCompilerStderr=" + stderr.get(2, TimeUnit.SECONDS)); }
            catch (Exception failure) { log.println("systemD3dCompilerOutput=" + failure); }
            process.getInputStream().close(); process.getErrorStream().close(); log.flush();
        }
    }
}
