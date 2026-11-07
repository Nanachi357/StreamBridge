package dev.streambridge.spike;

import java.io.IOException;
import java.nio.file.*;
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
        return checkedDirectory(WindowsDownloads.path());
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
