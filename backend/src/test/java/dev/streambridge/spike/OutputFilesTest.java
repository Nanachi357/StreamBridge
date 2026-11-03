package dev.streambridge.spike;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class OutputFilesTest {
    @TempDir Path root;
    @Test void publicationKeepsExistingVideosAndCleansPartial() throws Exception {
        Path source = Files.writeString(root.resolve("source"), "new content");
        Files.writeString(root.resolve("video.mp4"), "old content");
        Files.writeString(root.resolve("video (1).mp4"), "another video");
        Path result = OutputFiles.publish(source, root);
        assertEquals("video (2).mp4", result.getFileName().toString());
        assertEquals("new content", Files.readString(result));
        assertEquals("old content", Files.readString(root.resolve("video.mp4")));
        assertEquals("another video", Files.readString(root.resolve("video (1).mp4")));
        assertTrue(Files.exists(source));
        try (var paths = Files.list(root)) { assertFalse(paths.anyMatch(p -> p.toString().endsWith(".part"))); }
    }
    @Test void concurrentPublishersCannotOverwriteEachOther() throws Exception {
        Path existing = Files.writeString(root.resolve("video.mp4"), "existing video");
        int count = 24;
        var ready = new java.util.concurrent.CountDownLatch(count);
        var start = new java.util.concurrent.CountDownLatch(1);
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = new java.util.ArrayList<java.util.concurrent.Future<Path>>();
            var expected = new java.util.HashSet<String>();
            for (int i = 0; i < count; i++) {
                String content = "content-" + i; expected.add(content);
                Path source = Files.writeString(root.resolve("source-" + i), content);
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("Start gate timeout");
                    return OutputFiles.publish(source, root);
                }));
            }
            try { assertTrue(ready.await(10, java.util.concurrent.TimeUnit.SECONDS)); }
            finally { start.countDown(); }
            var paths = new java.util.HashSet<Path>(); var actual = new java.util.HashSet<String>();
            for (var future : futures) {
                Path saved = future.get(10, java.util.concurrent.TimeUnit.SECONDS);
                assertTrue(paths.add(saved), "Every publisher must receive its own destination");
                actual.add(Files.readString(saved));
            }
            assertEquals(expected, actual);
            assertEquals("existing video", Files.readString(existing));
        }
    }
    @Test void failedCopyCleansOnlyItsOwnPartial() throws Exception {
        Path foreign = Files.writeString(root.resolve("foreign.part"), "keep");
        assertThrows(java.io.IOException.class, () -> OutputFiles.publish(root.resolve("missing"), root));
        try (var paths = Files.list(root)) { assertEquals(java.util.List.of(foreign), paths.toList()); }
    }
    @Test void rejectsRelativeAndMissingDestination() {
        assertThrows(IllegalArgumentException.class, () -> OutputFiles.checkedDirectory(Path.of("relative")));
        assertThrows(java.io.IOException.class, () -> OutputFiles.checkedDirectory(root.resolve("missing")));
    }
    @Test void cancelledPublicationLeavesNoPartialAndPreservesSource() throws Exception {
        Path source = Files.writeString(root.resolve("source"), "data");
        assertThrows(java.io.IOException.class, () -> OutputFiles.publish(source, root, () -> true));
        try (var paths = Files.list(root)) { assertEquals(java.util.List.of(source), paths.toList()); }
    }
    @Test void windowsDownloadsLookupReturnsAPathNotPowerShellDiagnostics() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(System.getProperty("os.name").startsWith("Windows"));
        Path downloads = OutputFiles.downloads();
        assertTrue(downloads.isAbsolute()); assertTrue(Files.isDirectory(downloads));
    }
}
