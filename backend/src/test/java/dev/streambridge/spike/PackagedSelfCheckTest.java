package dev.streambridge.spike;

import org.junit.jupiter.api.Test;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import static org.junit.jupiter.api.Assertions.*;

class PackagedSelfCheckTest {
    @Test void exercisesAcquisitionTransportAgainstOwnedLoopbackFixture() {
        assertDoesNotThrow(PackagedSelfCheck::verifyHttpTransport);
    }

    @Test void coldHttpLibraryInitializationMayOutlastTheOldFiveSecondBudget() throws Exception {
        // Isolate the actual packaged classes from this test JVM's already-warm HTTP classes.
        var urls = Arrays.stream(System.getProperty("streambridge.testClasspath").split(Pattern.quote(java.io.File.pathSeparator)))
                .map(Path::of).map(path -> { try { return path.toUri().toURL(); } catch (Exception e) { throw new IllegalArgumentException(e); } })
                .toArray(java.net.URL[]::new);
        var delayed = new AtomicBoolean();
        try (var loader = new URLClassLoader(urls, ClassLoader.getPlatformClassLoader()) {
            @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.equals("org.apache.hc.client5.http.impl.classic.HttpClients") && delayed.compareAndSet(false, true)) {
                    try { Thread.sleep(6500); }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new ClassNotFoundException(name, interrupted); }
                }
                return super.loadClass(name, resolve);
            }
        }) {
            var check = loader.loadClass(PackagedSelfCheck.class.getName()).getDeclaredMethod("verifyHttpTransport");
            check.setAccessible(true);
            assertDoesNotThrow(() -> check.invoke(null));
            assertTrue(delayed.get(), "Must delay the real acquisition client's initialization");
        }
    }
}
