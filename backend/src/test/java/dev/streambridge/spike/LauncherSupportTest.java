package dev.streambridge.spike;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.Duration;
import java.io.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class LauncherSupportTest {
    @TempDir Path root;
    @Test void browserFailureIdentifiesExternalEdgePrerequisite() {
        String message = LauncherSupport.probeFailure(23);
        assertTrue(message.contains("Microsoft Edge Stable"));
        assertTrue(message.contains("https://www.microsoft.com/edge/download"));
        assertFalse(message.contains("chromiumDirectory"));
    }
    private static final String KEY = "a".repeat(72);
    private static Process probe(String mode) throws IOException {
        String java = System.getProperty("os.name").startsWith("Windows") ? "javaw.exe" : "java";
        return new ProcessBuilder(List.of(Path.of(System.getProperty("java.home"), "bin", java).toString(),
                "-cp", System.getProperty("java.class.path"), ProbeFixture.class.getName(), mode)).start();
    }
    @Test void dependencyBudgetLeavesTimeForDriverAndVerificationAfterNativeChecks() {
        assertTrue(StartupTimeouts.DEPENDENCIES.compareTo(
                StartupTimeouts.MEDIA.multipliedBy(2).plus(StartupTimeouts.BROWSER).plusSeconds(30)) > 0);
    }
    @Test void slowProbeStillRequiresSameRuntimeAcknowledgement() throws Exception {
        try (var log = new PrintWriter(new StringWriter())) {
            LauncherSupport.awaitProbe(probe("ready"), log, Duration.ofSeconds(10));
            IOException failure = assertThrows(IOException.class,
                    () -> LauncherSupport.awaitProbe(probe("wrong"), log, Duration.ofSeconds(10)));
            assertTrue(failure.getMessage().contains("same Java runtime"));
        }
    }
    @Test void globalDeadlineStopsProbeEvenBeforeItReportsAStage() throws Exception {
        Process process = probe("hang");
        try (var log = new PrintWriter(new StringWriter())) {
            IOException failure = assertThrows(IOException.class,
                    () -> LauncherSupport.awaitProbe(process, log, Duration.ofMillis(500)));
            assertTrue(failure.getMessage().contains("Перевірка запуску не завершилася"));
            assertFalse(process.isAlive());
        } finally { process.destroyForcibly(); }
    }
    @Test void mediaAndBrowserTimeoutsDoNotRecommendReinstallation() throws Exception {
        for (int code : new int[]{28,29,30}) {
            Process process = probe(Integer.toString(code));
            try (var log = new PrintWriter(new StringWriter())) {
                IOException failure = assertThrows(IOException.class,
                        () -> LauncherSupport.awaitProbe(process, log, Duration.ofSeconds(10)));
                assertEquals(LauncherSupport.probeFailure(code), failure.getMessage());
                assertTrue(failure.getMessage().contains("Таймаут не означає пошкодження"));
                assertFalse(failure.getMessage().contains("перевстанови"));
            }
        }
    }
    public static final class ProbeFixture {
        public static void main(String[] args) throws Exception {
            switch (args[0]) {
                case "ready" -> { Thread.sleep(250); System.out.println("JAVA_RUNTIME=" + System.getProperty("java.home")); }
                case "wrong" -> System.out.println("wrong runtime");
                case "hang" -> Thread.sleep(30_000);
                default -> System.exit(Integer.parseInt(args[0]));
            }
        }
    }
    @Test void onlyExactLoopbackLaunchAddressesAreAccepted() {
        assertEquals(12345, LauncherSupport.checkedLaunchUri("http://127.0.0.1:12345/#"+KEY).getPort());
        for (String url : new String[]{"https://example.org/#"+KEY,"http://localhost:12345/#"+KEY,
                "http://127.0.0.1:12345/?key=x#"+KEY,"http://user@127.0.0.1:12345/#"+KEY,
                "file:///test","http://127.0.0.1:12345/#wrong","http://127.0.0.1:99999/#"+KEY})
            assertThrows(IllegalArgumentException.class, () -> LauncherSupport.checkedLaunchUri(url));
    }
    @Test void readinessRequiresTheCorrectKeyAndRunningBackend() throws Exception {
        try (var app = new LocalUiSpike(new LocalUiSession(root))) {
            assertTrue(LauncherSupport.ready(app.launchUrl()));
            assertFalse(LauncherSupport.ready(app.origin()+"/#"+KEY));
        }
        assertFalse(LauncherSupport.ready("file:///test"));
    }
    @Test void missingCustomBrowserIsRejectedWithoutFallback() {
        assertNull(LauncherSupport.browserPath("default"));
        assertThrows(IllegalArgumentException.class, () -> LauncherSupport.browserPath(root.resolve("missing.exe").toString()));
        assertThrows(IllegalArgumentException.class, () -> LauncherSupport.browserPath("relative.exe"));
    }
    @Test @EnabledOnOs(OS.WINDOWS) void lockOwnerPublishesAndRemovesOnlyItsOwnSessionMetadata() throws Exception {
        Path directory = root.resolve("runtime");
        try (var app = new LocalUiSpike(new LocalUiSession(root.resolve("output")));
             var first = new LauncherSupport.Instance(directory)) {
            assertTrue(first.owner()); first.publish(app.launchUrl());
            try (var second = new LauncherSupport.Instance(directory)) {
                assertFalse(second.owner());
                assertEquals(app.launchUrl(), second.awaitExisting(Duration.ofSeconds(2)));
                assertThrows(IllegalStateException.class, () -> second.publish(app.launchUrl()));
            }
            assertTrue(Files.exists(directory.resolve("session.url")));
            Files.writeString(directory.resolve("keep.txt"),"unrelated");
        }
        assertFalse(Files.exists(directory.resolve("session.url")));
        assertEquals("unrelated",Files.readString(directory.resolve("keep.txt")));
        try(var next = new LauncherSupport.Instance(directory)) { assertTrue(next.owner()); }
    }
    @Test @EnabledOnOs(OS.WINDOWS) void staleAddressIsRemovedOnlyAfterAcquiringLock() throws Exception {
        Path directory = root.resolve("runtime"); Files.createDirectories(directory);
        Files.writeString(directory.resolve("session.url"),"stale");
        try(var owner = new LauncherSupport.Instance(directory)) {
            assertTrue(owner.owner()); assertFalse(Files.exists(directory.resolve("session.url")));
        }
    }
}
