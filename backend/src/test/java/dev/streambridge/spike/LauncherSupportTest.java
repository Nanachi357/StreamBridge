package dev.streambridge.spike;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.*;

class LauncherSupportTest {
    @TempDir Path root;
    private static final String KEY = "a".repeat(72);
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
