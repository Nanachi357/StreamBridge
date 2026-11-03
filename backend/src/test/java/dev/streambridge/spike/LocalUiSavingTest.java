package dev.streambridge.spike;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.file.*;
import java.util.Map;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;

class LocalUiSavingTest {
    @TempDir Path root;
    @Test void cancellationBeforeWorkerExitRemovesCompletedStagingFile() throws Exception {
        checkCompletionBoundary(false, false);
    }
    @Test void shutdownBeforeWorkerExitRemovesCompletedStagingFile() throws Exception {
        checkCompletionBoundary(true, false);
    }
    @Test
    @org.junit.jupiter.api.condition.EnabledOnOs(org.junit.jupiter.api.condition.OS.WINDOWS)
    void failedStagingDeletionAfterCancellationReportsUncertainty() throws Exception {
        checkCompletionBoundary(false, true);
    }
    private void checkCompletionBoundary(boolean shutdown, boolean lockFile) throws Exception {
        Path destination = Files.createDirectory(root.resolve("destination"));
        Path unrelated = Files.writeString(root.resolve("keep.txt"), "unrelated");
        var owned = new java.util.concurrent.atomic.AtomicReference<Path>();
        try (var session = new LocalUiSession(root, destination, (url, directory) -> {
            owned.set(directory); return worker(directory, "https://example.org/completion-gate");
        }, initial -> null)) {
            String id = completeWorker(session);
            await(() -> session.snapshot().get("phase").equals("COMPLETED_WAITING"));
            Path staged = owned.get().resolve("video.mp4");
            assertTrue(Files.isRegularFile(staged));
            try (var lock = lockFile ? Files.newByteChannel(staged, java.util.Set.of(
                    StandardOpenOption.READ, com.sun.nio.file.ExtendedOpenOption.NOSHARE_DELETE)) : null) {
                var closed = new CountDownLatch(1);
                if (shutdown) Thread.ofVirtual().start(() -> { try { session.close(); } finally { closed.countDown(); } });
                else session.cancel(id);
                try {
                    await(() -> session.snapshot().get("phase").equals("CANCELLING"));
                } finally { Files.createFile(owned.get().resolve("exit-gate")); }
                await(() -> session.snapshot().get("phase").equals("FINISHED"));
                if (shutdown) assertTrue(closed.await(10, TimeUnit.SECONDS));
                assertEquals("CANCELLED", session.snapshot().get("status"));
                assertEquals(lockFile, session.snapshot().get("cleanupUncertain"));
                assertEquals(lockFile, Files.exists(staged));
                assertEquals(false, session.snapshot().get("fileReady"));
                try (var files = Files.list(destination)) { assertEquals(0, files.count()); }
                assertEquals("unrelated", Files.readString(unrelated));
            }
        }
    }
    @Test void cancellationAfterWorkerExitBeforePublicationRemovesCompletedStagingFile() throws Exception {
        Path destination = Files.createDirectory(root.resolve("destination"));
        var reached = new CountDownLatch(1); var release = new CountDownLatch(1);
        var owned = new java.util.concurrent.atomic.AtomicReference<Path>();
        try (var session = new LocalUiSession(root, destination, (url, directory) -> {
            owned.set(directory);
            Process child = worker(directory, url);
            // Delegate every Process operation; gate only the collector's exit observation.
            Process wrapped = org.mockito.Mockito.mock(Process.class, org.mockito.AdditionalAnswers.delegatesTo(child));
            try {
                org.mockito.Mockito.doAnswer(call -> {
                    int exit = child.waitFor(); pause(reached, release); return exit;
                }).when(wrapped).waitFor();
            } catch (InterruptedException impossible) { throw new AssertionError(impossible); }
            return wrapped;
        }, initial -> null)) {
            String id = completeWorker(session);
            try {
                assertTrue(reached.await(10, TimeUnit.SECONDS));
                assertEquals(false, session.snapshot().get("workerAlive"));
                session.cancel(id);
            } finally { release.countDown(); }
            await(() -> session.snapshot().get("phase").equals("FINISHED"));
            assertEquals("CANCELLED", session.snapshot().get("status"));
            assertEquals(false, session.snapshot().get("cleanupUncertain"));
            assertFalse(Files.exists(owned.get().resolve("video.mp4")));
            try (var files = Files.list(destination)) { assertEquals(0, files.count()); }
        } finally { release.countDown(); }
    }
    private static void await(BooleanSupplier condition) throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
        while(!condition.getAsBoolean()&&System.nanoTime()<end)Thread.sleep(10);
        assertTrue(condition.getAsBoolean());
    }
    private static void pause(CountDownLatch reached,CountDownLatch release) throws IOException {
        reached.countDown();
        try {if(!release.await(10,TimeUnit.SECONDS))throw new IOException("Test barrier timeout");}
        catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException(e);}
    }
    @Test void cancellationDuringCopyOrBeforeCommitStopsPublicationAfterWorkerExit() throws Exception {
        for(boolean atCommit:new boolean[]{false,true}) {
            Path work=Files.createDirectory(root.resolve(atCommit?"commit":"copy"));
            Path output=Files.createDirectory(work.resolve("output"));
            var reached=new CountDownLatch(1);var release=new CountDownLatch(1);
            var first=new java.util.concurrent.atomic.AtomicBoolean(true);
            OutputFiles.Publisher publisher=(source,destination,cancelled,commit)->OutputFiles.publish(source,destination,()->{
                if(!atCommit&&first.getAndSet(false))try{pause(reached,release);}catch(IOException e){throw new UncheckedIOException(e);}
                return cancelled.getAsBoolean();
            },(partial,target)->{if(atCommit)pause(reached,release);return commit.move(partial,target);});
            try(var session=session(work,output,publisher)) {
                String id=completeWorker(session);
                try {
                    assertTrue(reached.await(10,TimeUnit.SECONDS));
                    assertEquals("SAVING",session.snapshot().get("phase"));assertEquals(false,session.snapshot().get("workerAlive"));
                    session.cancel(id);session.cancel(id);
                    assertEquals("CANCELLING",session.snapshot().get("phase"));
                } finally {release.countDown();}
                await(()->session.snapshot().get("phase").equals("FINISHED"));
                assertEquals("CANCELLED",session.snapshot().get("status"));assertEquals("",session.snapshot().get("error"));
                assertEquals(false,session.snapshot().get("fileReady"));
                try(var files=Files.list(output)){assertEquals(0,files.count());}
                session.start("https://example.org/next"); // A cancelled save cannot poison the next job.
            } finally {release.countDown();}
        }
    }
    @Test void cancellationAfterCommitCannotDeleteCompletedFileOrClaimItWasCancelled() throws Exception {
        Path output=Files.createDirectory(root.resolve("output"));
        var reached=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var session=session(root,output,(source,destination,cancelled,commit)->{
            Path saved=OutputFiles.publish(source,destination,cancelled,commit);pause(reached,release);return saved;
        })) {
            String id=completeWorker(session);
            try {assertTrue(reached.await(10,TimeUnit.SECONDS));session.cancel(id);}
            finally {release.countDown();}
            await(()->session.snapshot().get("phase").equals("FINISHED"));
            assertEquals("SUCCEEDED",session.snapshot().get("status"));assertTrue(Files.size(session.file(id))>0);
        } finally {release.countDown();}
    }
    private LocalUiSession session(Path work,Path output,OutputFiles.Publisher publisher) throws IOException {
        return new LocalUiSession(work,new OutputPreferences.Selection(output,""),(url,directory)->worker(directory,url),initial->null,null,publisher);
    }
    private static Process worker(Path directory, String url) throws IOException {
        return new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString(),
                "-cp",System.getProperty("streambridge.testClasspath"),LocalUiWorkerFixture.class.getName(),directory.toString(),url)
                .redirectErrorStream(true).start();
    }
    private static String completeWorker(LocalUiSession session) throws Exception {
        session.start("https://example.org/fixture");await(()->session.snapshot().get("phase").equals("DISCOVERING"));
        String id=(String)session.snapshot().get("discoveryId");session.prepare(id,1,"C1","V1","unknown");
        await(()->session.snapshot().get("phase").equals("AWAITING_CONFIRMATION"));
        session.confirm(id,(String)((Map<?,?>)session.snapshot().get("review")).get("token"));return id;
    }
}
