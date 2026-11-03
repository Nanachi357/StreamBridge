package dev.streambridge.spike;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Properties;

/** One per-user preference, outside the installation and separate from tool/browser settings. */
final class OutputPreferences {
    @FunctionalInterface interface DefaultDirectory { Path get() throws Exception; }
    record Selection(Path directory, String notice) {}
    private final Path file;

    OutputPreferences(Path file) { this.file = file.toAbsolutePath(); }
    static Path file() {
        String local = System.getenv("LOCALAPPDATA");
        Path base = local == null ? Path.of(System.getProperty("user.home"), ".streambridge")
                : Path.of(local, "StreamBridge");
        return base.resolve("output.properties");
    }
    Selection load(DefaultDirectory fallback) throws Exception {
        String notice = "";
        boolean settingsRead = false;
        try {
            var properties = new Properties();
            if (Files.size(file) > 65536) throw new IOException("PREFERENCE_TOO_LARGE");
            try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) { properties.load(reader); }
            settingsRead = true;
            String saved = properties.getProperty("outputDirectory");
            if (saved == null || saved.isBlank()) throw new IOException("MISSING_OUTPUT_DIRECTORY");
            return new Selection(OutputFiles.checkedDirectory(Path.of(saved)), "");
        } catch (NoSuchFileException missing) {
            // A missing preferences file is normal on first launch; a missing destination is not.
            if (settingsRead) notice = "SAVED_OUTPUT_UNAVAILABLE";
        } catch (IOException | IllegalArgumentException failure) { notice = "SAVED_OUTPUT_UNAVAILABLE"; }
        return new Selection(OutputFiles.checkedDirectory(fallback.get()), notice);
    }
    void save(Path directory) throws IOException {
        Path checked = OutputFiles.checkedDirectory(directory);
        var properties = new Properties(); properties.setProperty("outputDirectory", checked.toString());
        Files.createDirectories(file.getParent());
        Path temporary = Files.createTempFile(file.getParent(), ".output-", ".tmp");
        try {
            try (var writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                properties.store(writer, "StreamBridge output destination; no media or session credentials");
            }
            // Do not truncate the last valid choice if writing/replacing fails.
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }
}
