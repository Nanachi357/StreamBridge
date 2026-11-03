package dev.streambridge.spike;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;

class OutputPreferencesTest {
    @TempDir Path root;
    @Test void firstLaunchUsesDefaultWithoutWritingAndLaterInstanceRestoresUnicodePath() throws Exception {
        Path file = root.resolve("settings/output.properties");
        var first = new OutputPreferences(file);
        assertEquals(new OutputPreferences.Selection(root.toRealPath(), ""), first.load(() -> root));
        assertFalse(Files.exists(file));
        Path chosen = Files.createDirectory(root.resolve("Мої відео з пробілами")); first.save(chosen);
        var restored = new OutputPreferences(file).load(() -> { throw new AssertionError("Default must not be needed"); });
        assertEquals(chosen.toRealPath(), restored.directory()); assertEquals("", restored.notice());
        try (var files = Files.list(file.getParent())) { assertEquals(1, files.count()); }
        first.save(root); // Replace an existing preference, not just create a new file.
        assertEquals(root.toRealPath(), new OutputPreferences(file).load(() -> chosen).directory());
        String before = Files.readString(file);
        assertThrows(java.io.IOException.class, () -> first.save(root.resolve("absent")));
        assertEquals(before, Files.readString(file));
    }
    @Test void unavailableDestinationFallsBackWithoutForgettingSavedChoice() throws Exception {
        Path file = root.resolve("output.properties"), removable = Files.createDirectory(root.resolve("removable"));
        var preferences = new OutputPreferences(file); preferences.save(removable);
        String before = Files.readString(file); Files.delete(removable);
        var restored = preferences.load(() -> root);
        assertEquals(root.toRealPath(), restored.directory()); assertEquals("SAVED_OUTPUT_UNAVAILABLE", restored.notice());
        assertEquals(before, Files.readString(file));
        Files.createDirectory(removable);
        assertEquals(removable.toRealPath(), new OutputPreferences(file).load(() -> root).directory());
    }
    @Test void corruptRelativeOrOversizedSettingsWarnAndDoNotGetOverwritten() throws Exception {
        Path file = root.resolve("output.properties");
        for (String content : new String[]{"outputDirectory=relative", "outputDirectory=\\uZZZZ", "wrong=value", "x".repeat(65537)}) {
            Files.writeString(file, content);
            var loaded = new OutputPreferences(file).load(() -> root);
            assertEquals(root.toRealPath(), loaded.directory()); assertEquals("SAVED_OUTPUT_UNAVAILABLE", loaded.notice());
            assertEquals(content, Files.readString(file));
        }
    }
    @Test void chooserSavesImmediatelyAndNewSessionRestoresWhileCancelKeepsChoice() throws Exception {
        Path file = root.resolve("settings/output.properties"), chosen = Files.createDirectory(root.resolve("chosen"));
        var preferences = new OutputPreferences(file);
        try (var session = session(preferences, initial -> chosen)) {
            session.chooseOutputDirectory(); await(() -> !(Boolean)session.snapshot().get("choosingFolder"));
            assertEquals(chosen.toRealPath().toString(), session.snapshot().get("outputDirectory"));
            assertEquals("", session.snapshot().get("folderNotice"));
            assertFalse(UiProtocol.json(session.report()).contains(chosen.toString()));
        }
        String stored = Files.readString(file);
        try (var reopened = session(new OutputPreferences(file), initial -> null)) {
            assertEquals(chosen.toRealPath().toString(), reopened.snapshot().get("outputDirectory"));
            reopened.chooseOutputDirectory(); await(() -> !(Boolean)reopened.snapshot().get("choosingFolder"));
            assertEquals(stored, Files.readString(file));
        }
    }
    @Test void writeFailureStillUsesChoiceThisSessionButClearlyWarns() throws Exception {
        Path blocker = root.resolve("blocked"); Files.writeString(blocker, "keep");
        var preferences = new OutputPreferences(blocker.resolve("output.properties"));
        Path chosen = Files.createDirectory(root.resolve("chosen"));
        try (var session = session(preferences, initial -> chosen)) {
            session.chooseOutputDirectory(); await(() -> !(Boolean)session.snapshot().get("choosingFolder"));
            assertEquals(chosen.toRealPath().toString(), session.snapshot().get("outputDirectory"));
            assertEquals("OUTPUT_PREFERENCE_NOT_SAVED", session.snapshot().get("folderNotice"));
            assertEquals("keep", Files.readString(blocker));
        }
    }
    @Test void closingBeforeChooserReturnsDoesNotPersistLateSelection() throws Exception {
        Path file = root.resolve("output.properties"); var release = new CountDownLatch(1);
        var session = session(new OutputPreferences(file), initial -> { release.await(); return root; });
        try {
            session.chooseOutputDirectory(); session.close();
        } finally { release.countDown(); }
        await(() -> !(Boolean)session.snapshot().get("choosingFolder"));
        assertFalse(Files.exists(file));
    }
    private LocalUiSession session(OutputPreferences preferences, OutputFiles.FolderChooser chooser) throws Exception {
        return new LocalUiSession(root.resolve("work"), preferences.load(() -> root),
                (url, directory) -> { throw new AssertionError("No worker expected"); }, chooser, preferences);
    }
    private static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(10);
        assertTrue(condition.getAsBoolean());
    }
}
