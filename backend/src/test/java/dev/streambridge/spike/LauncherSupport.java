package dev.streambridge.spike;

import java.awt.Desktop;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Windows pilot startup mechanics; no shell interpolation and no system-wide changes. */
final class LauncherSupport {
    static URI checkedLaunchUri(String value) {
        URI uri = URI.create(value);
        if (!"http".equals(uri.getScheme()) || !"127.0.0.1".equals(uri.getHost())
                || uri.getPort() < 1 || uri.getPort() > 65535 || !"/".equals(uri.getPath())
                || uri.getUserInfo() != null || uri.getQuery() != null
                || uri.getFragment() == null || !uri.getFragment().matches("[a-f0-9-]{72}"))
            throw new IllegalArgumentException("Invalid local launch address");
        return uri;
    }

    static boolean ready(String launchUrl) {
        try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()) {
            URI uri = checkedLaunchUri(launchUrl);
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + uri.getPort() + "/api/state"))
                    .timeout(Duration.ofSeconds(2)).header("X-StreamBridge-Key", uri.getFragment()).GET().build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200 && UiProtocol.object(response.body()).containsKey("workerAlive");
        } catch (Exception unavailable) { return false; }
    }

    static Path browserPath(String selection) {
        if (selection.equals("default")) return null;
        if (!selection.equals("chrome") && !selection.equals("edge")) {
            Path custom = Path.of(selection);
            if (!custom.isAbsolute() || !Files.isRegularFile(custom) || !selection.toLowerCase(Locale.ROOT).endsWith(".exe"))
                throw new IllegalArgumentException("Вибраний браузер не знайдено. Вкажи наявний .exe або вибери інший браузер.");
            return custom;
        }
        String relative = selection.equals("chrome") ? "Google/Chrome/Application/chrome.exe" : "Microsoft/Edge/Application/msedge.exe";
        for (String root : List.of("LOCALAPPDATA", "ProgramFiles", "ProgramFiles(x86)")) {
            String value = System.getenv(root);
            if (value != null && Files.isRegularFile(Path.of(value, relative))) return Path.of(value, relative);
        }
        throw new IllegalArgumentException("Браузер " + selection + " не знайдено. Вибери інший або вкажи .exe вручну.");
    }

    static void open(String selection, String launchUrl) throws IOException {
        URI uri = checkedLaunchUri(launchUrl);
        Path executable = browserPath(selection);
        if (executable == null) {
            if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.BROWSE))
                throw new IOException("Windows не має доступного браузера за замовчуванням. Вибери браузер або скопіюй посилання.");
            Desktop.getDesktop().browse(uri);
        } else new ProcessBuilder(executable.toString(), uri.toString()).start();
        // OS handoff is not proof that the browser rendered or authenticated the UI.
    }

    static void probeDependencies() throws Exception {
        Path logs = LauncherSettings.file().getParent().resolve("startup-logs");
        Files.createDirectories(logs);
        Path run = Files.createTempDirectory(logs, "check-");
        try (var log = new PrintWriter(Files.newBufferedWriter(run.resolve("launcher.log")), true)) {
            log.println("started=" + java.time.Instant.now());
            log.println("pid=" + ProcessHandle.current().pid());
            log.println("parentPid=" + ProcessHandle.current().parent().map(ProcessHandle::pid).orElse(-1L));
            log.println("user=" + System.getProperty("user.name"));
            log.println("javaHome=" + System.getProperty("java.home"));
            log.println("javaVersion=" + System.getProperty("java.version"));
            log.println("application=" + LauncherSupport.class.getProtectionDomain().getCodeSource().getLocation());
            log.println("workingDirectory=" + Path.of("").toAbsolutePath());
            log.println("settings=" + LauncherSettings.file() + " exists=" + Files.isRegularFile(LauncherSettings.file()));
            try {
                ProcessBuilder builder = LauncherSettings.childJava(LauncherDependencyProbe.class, run.resolve("probe.log").toString());
                for (String key : List.of("STREAMBRIDGE_FFMPEG_DIR", "PLAYWRIGHT_BROWSERS_PATH")) {
                    String inherited = System.getenv(key);
                    String value = builder.environment().get(key);
                    String source = inherited != null && !inherited.isBlank() ? "environment"
                            : value != null ? "tools.properties" : "not configured (tool default if supported)";
                    log.println(key + " source=" + source + " resolved=" + value);
                }
                log.println("childJava=" + builder.command().getFirst());
                Process process = builder.redirectError(run.resolve("child-stderr.log").toFile()).start();
                log.println("childPid=" + process.pid());
                awaitProbe(process, log);
                log.println("result=OK");
            } catch (Exception failure) {
                failure.printStackTrace(log);
                throw new IOException(failure.getMessage() + "\nДіагностика: " + run, failure);
            }
        }
    }

    private static void awaitProbe(Process process, PrintWriter log) throws Exception {
        if (!process.waitFor(60, TimeUnit.SECONDS)) {
            var descendants = process.descendants().toList();
            descendants.forEach(ProcessHandle::destroyForcibly); process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
            throw new IOException("Перевірка залежностей перевищила 60 секунд. Перевір Chromium і медіаінструменти.");
        }
        log.println("childExit=" + process.exitValue());
        if (process.exitValue() != 0) throw new IOException(probeFailure(process.exitValue()));
        String acknowledgement = new String(process.getInputStream().readNBytes(4096), java.nio.charset.StandardCharsets.UTF_8).strip();
        if (!acknowledgement.equals("JAVA_RUNTIME=" + System.getProperty("java.home")))
            throw new IOException("Dependency child did not confirm the same Java runtime.");
    }

    static String probeFailure(int exit) {
        return switch (exit) {
            case 21 -> "Не вдалося запустити FFmpeg. Перевір mediaToolsDirectory у " + LauncherSettings.file();
            case 22 -> "Не вдалося запустити FFprobe. Перевір mediaToolsDirectory у " + LauncherSettings.file();
            case 23 -> "Не вдалося запустити або перевірити Chromium з обов’язковою пісочницею. Захист не вимикався. Перевір chromiumDirectory у " + LauncherSettings.file();
            case 24 -> "Chromium не пройшов перевірку мережевої ізоляції WebRTC. Запуск зупинено; захист не вимикався.";
            case 25 -> "У Windows відсутня системна d3dcompiler_47.dll (System32). Онови або віднови компоненти Windows офіційними засобами Microsoft. Не завантажуй окремі DLL зі сторонніх сайтів.";
            case 26 -> "Системна d3dcompiler_47.dll не пройшла перевірку підпису або працездатності. Онови або віднови компоненти Windows офіційними засобами Microsoft. Деталі — у журналі діагностики.";
            case 27 -> "Не вдалося виконати перевірку системної d3dcompiler_47.dll. Це не обов’язково пошкодження файла: перевір доступність Windows PowerShell та обмеження політик. Деталі — у журналі діагностики.";
            default -> "Не вдалося запустити Chromium Playwright. Перевір chromiumDirectory у " + LauncherSettings.file();
        };
    }

    /** Private per-user runtime state. Key never goes into shared logs or project files. */
    static final class Instance implements AutoCloseable {
        private final FileChannel channel;
        private final FileLock lock;
        private final Path address;
        Instance(Path directory) throws IOException {
            Files.createDirectories(directory);
            // Fail closed if this Windows filesystem cannot protect the session key.
            var acl = Files.getFileAttributeView(directory, AclFileAttributeView.class);
            if (acl == null) throw new IOException("Launcher requires a Windows filesystem with user ACL support.");
            var owner = Files.getOwner(directory);
            var entry = AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(owner)
                    .setPermissions(EnumSet.allOf(AclEntryPermission.class))
                    .setFlags(AclEntryFlag.DIRECTORY_INHERIT, AclEntryFlag.FILE_INHERIT).build();
            acl.setAcl(List.of(entry));
            address = directory.resolve("session.url");
            channel = FileChannel.open(directory.resolve("instance.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            FileLock acquired;
            try { acquired = channel.tryLock(); }
            catch (OverlappingFileLockException busy) { acquired = null; }
            lock = acquired;
            if (lock != null) Files.deleteIfExists(address); // Only our stale metadata, never media.
        }
        boolean owner() { return lock != null; }
        void publish(String url) throws IOException {
            if (!owner()) throw new IllegalStateException();
            checkedLaunchUri(url);
            Files.writeString(address, url, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        }
        String awaitExisting(Duration timeout) throws InterruptedException, IOException {
            long end = System.nanoTime() + timeout.toNanos();
            do {
                try {
                    if (Files.size(address) < 256) {
                        String url = Files.readString(address);
                        if (ready(url)) return url;
                    }
                } catch (IOException | IllegalArgumentException notReady) { /* Owner is still starting. */ }
                Thread.sleep(250);
            } while (System.nanoTime() < end);
            throw new IOException("Інший запуск ще не готовий або завершується. Дочекайся його вікна й повтори запуск.");
        }
        @Override public void close() throws IOException {
            try { if (owner()) { Files.deleteIfExists(address); lock.release(); } }
            finally { channel.close(); }
        }
    }
}
