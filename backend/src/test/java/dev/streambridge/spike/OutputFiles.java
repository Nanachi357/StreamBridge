package dev.streambridge.spike;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Base64;
import java.util.concurrent.TimeUnit;
import javax.swing.*;

/** User-selected destination, separate from owned staging; never overwrites existing videos. */
final class OutputFiles {
    @FunctionalInterface interface FolderChooser { Path choose(Path initial) throws Exception; }
    static final class CleanupException extends IOException {
        CleanupException(IOException cause) { super("OUTPUT_PARTIAL_CLEANUP_FAILED",cause); }
    }
    @FunctionalInterface interface Commit { Path move(Path partial, Path target) throws IOException; }
    @FunctionalInterface interface Publisher {
        Path publish(Path source, Path destination, java.util.function.BooleanSupplier cancelled, Commit commit) throws IOException;
    }

    static Path downloads() throws Exception {
        if (!System.getProperty("os.name").startsWith("Windows"))
            return Files.createDirectories(Path.of(System.getProperty("user.home"), "Downloads")).toRealPath();
        // Windows' Downloads known-folder registry value, including user relocation and environment expansion.
        // Fixed script: no path, URL or UI input is interpolated as executable code.
        String script = "$ProgressPreference='SilentlyContinue'; [Console]::OutputEncoding = New-Object System.Text.UTF8Encoding($false); "
                + "$k=[Microsoft.Win32.Registry]::CurrentUser.OpenSubKey('Software\\Microsoft\\Windows\\CurrentVersion\\Explorer\\User Shell Folders'); "
                + "try { $p=$k.GetValue('{374DE290-123F-4565-9164-39C4925E467B}'); if (!$p) { exit 2 }; [Console]::Write($p) } finally { $k.Dispose() }";
        String encoded = Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE));
        Path powershell = Path.of(System.getenv("SystemRoot"), "System32", "WindowsPowerShell", "v1.0", "powershell.exe");
        var process = new ProcessBuilder(powershell.toString(), "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden",
                "-OutputFormat", "Text", "-EncodedCommand", encoded).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        try {
            process.getOutputStream().close();
            if (!process.waitFor(8, TimeUnit.SECONDS)) throw new IOException("DOWNLOADS_LOOKUP_TIMEOUT");
            byte[] data = process.getInputStream().readNBytes(32769);
            if (process.exitValue() != 0 || data.length > 32768) throw new IOException("DOWNLOADS_LOOKUP_FAILED");
            return checkedDirectory(Path.of(new String(data, StandardCharsets.UTF_8).strip()));
        } finally { if (process.isAlive()) FfmpegTransfer.terminate(process); }
    }
    static Path workRoot() throws IOException {
        Path base = System.getenv("LOCALAPPDATA") == null ? Path.of(System.getProperty("java.io.tmpdir"))
                : Path.of(System.getenv("LOCALAPPDATA"));
        return Files.createDirectories(base.resolve("StreamBridge").resolve("jobs")).toRealPath();
    }
    static Path checkedDirectory(Path path) throws IOException {
        if (!path.isAbsolute()) throw new IllegalArgumentException("ABSOLUTE_DIRECTORY_REQUIRED");
        Path real = path.toRealPath();
        if (!Files.isDirectory(real) || !Files.isWritable(real)) throw new IOException("OUTPUT_DIRECTORY_UNAVAILABLE");
        return real;
    }
    static Path chooseFolder(Path initial) throws Exception {
        var selected = new java.util.concurrent.atomic.AtomicReference<Path>();
        SwingUtilities.invokeAndWait(() -> {
            var chooser = new JFileChooser(initial.toFile());
            chooser.setDialogTitle("Куди зберегти готове відео?");
            chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            chooser.setAcceptAllFileFilterUsed(false);
            if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION)
                selected.set(chooser.getSelectedFile().toPath());
        });
        return selected.get(); // Cancel keeps the previous choice.
    }
    static Path publish(Path source, Path destination) throws IOException {
        return publish(source, destination, () -> false);
    }
    static Path publish(Path source, Path destination, java.util.function.BooleanSupplier cancelled) throws IOException {
        return publish(source,destination,cancelled,(partial,target)->{
            if(cancelled.getAsBoolean()||Thread.currentThread().isInterrupted())throw new IOException("SAVE_CANCELLED");
            return moveCompleted(partial,target);
        });
    }
    // Serialize our final renames across sessions: some file-system providers perform
    // the no-replace existence check separately from the rename. Copying remains parallel.
    static synchronized Path moveCompleted(Path partial, Path target) throws IOException {
        return Files.move(partial, target);
    }
    static Path publish(Path source, Path destination, java.util.function.BooleanSupplier cancelled, Commit commit) throws IOException {
        Path folder = checkedDirectory(destination);
        Path partial = Files.createTempFile(folder, ".streambridge-", ".part");
        try {
            try (var input = Files.newInputStream(source); var output = Files.newOutputStream(partial)) {
                byte[] buffer = new byte[64 * 1024];
                for (int read; (read = input.read(buffer)) != -1;) {
                    if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new IOException("SAVE_CANCELLED");
                    output.write(buffer, 0, read);
                }
            }
            if (cancelled.getAsBoolean()) throw new IOException("SAVE_CANCELLED");
            for (int i = 0; i < 100_000; i++) {
                Path target = folder.resolve(i == 0 ? "video.mp4" : "video (" + i + ").mp4");
                if(cancelled.getAsBoolean()||Thread.currentThread().isInterrupted())throw new IOException("SAVE_CANCELLED");
                try { return commit.move(partial, target); } // Owner serializes cancellation with final publication.
                catch (FileAlreadyExistsException collision) { /* Try next name. */ }
            }
            throw new IOException("OUTPUT_NAMES_EXHAUSTED");
        } finally {
            try { Files.deleteIfExists(partial); }
            catch(IOException failure){throw new CleanupException(failure);}
        }
    }
}
