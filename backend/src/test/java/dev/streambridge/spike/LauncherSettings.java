package dev.streambridge.spike;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Per-user paths only; no dependence on the working directory or checkout. */
final class LauncherSettings {
    static Path file() {
        return Path.of(System.getenv("LOCALAPPDATA"), "StreamBridge", "pilot-launcher", "tools.properties");
    }
    static Map<String,String> environment(Path file, Map<String,String> environment) throws IOException {
        var settings = new Properties();
        // exists() also returns false when access cannot be determined: do not hide read failures.
        try (Reader input = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            settings.load(input);
        } catch (NoSuchFileException missing) {
            if (environment.getOrDefault("STREAMBRIDGE_FFMPEG_DIR", "").isBlank())
                throw new IOException("Не знайдено файл налаштувань: " + file
                        + "\nСтвори його з tools.example.properties та вкажи mediaToolsDirectory і chromiumDirectory."
                        + "\nАльтернатива для медіаінструментів: STREAMBRIDGE_FFMPEG_DIR.", missing);
        } catch (IOException | IllegalArgumentException failure) {
            throw new IOException("Не вдалося прочитати файл налаштувань: " + file
                    + "\n" + failure.getClass().getSimpleName() + ": " + failure.getMessage(), failure);
        }
        var result = new HashMap<String,String>();
        for (var entry : Map.of("chromiumDirectory", "PLAYWRIGHT_BROWSERS_PATH",
                "mediaToolsDirectory", "STREAMBRIDGE_FFMPEG_DIR").entrySet()) {
            String value = environment.get(entry.getValue());
            if (value == null || value.isBlank()) value = settings.getProperty(entry.getKey());
            if (value != null && !value.isBlank()) {
                Path path = Path.of(value.strip());
                if (!path.isAbsolute()) throw new IOException("Tool directories must be absolute: " + entry.getKey());
                result.put(entry.getValue(), path.toString());
            }
        }
        if (!result.containsKey("STREAMBRIDGE_FFMPEG_DIR"))
            throw new IOException("Не задано mediaToolsDirectory у " + file
                    + " або змінну STREAMBRIDGE_FFMPEG_DIR. Перевірка FFmpeg ще не запускалася.");
        result.put("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1");
        return Map.copyOf(result);
    }
    static ProcessBuilder childJava(Class<?> mainClass, String... arguments) throws IOException {
        return childJava(mainClass, file(), System.getenv(), arguments);
    }
    static ProcessBuilder childJava(Class<?> mainClass, Path settingsFile,
                                    Map<String,String> environment, String... arguments) throws IOException {
        // javaw avoids a console flash when launched from the GUI executable on Windows.
        String executable = System.getProperty("os.name").startsWith("Windows") ? "javaw.exe" : "java";
        Path java = Path.of(System.getProperty("java.home"), "bin", executable);
        if (!Files.isRegularFile(java)) throw new IOException("Java runtime is incomplete: " + java);
        var command = new ArrayList<String>(List.of(java.toString(), "-Dfile.encoding=UTF-8",
                "-cp", System.getProperty("java.class.path"), mainClass.getName()));
        Collections.addAll(command, arguments);
        var builder = new ProcessBuilder(command);
        builder.environment().putAll(environment(settingsFile, environment));
        return builder;
    }
}
