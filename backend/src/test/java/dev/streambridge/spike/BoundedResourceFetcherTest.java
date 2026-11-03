package dev.streambridge.spike;

import com.sun.net.httpserver.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import javax.net.ssl.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.KeyStore;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static dev.streambridge.spike.BoundedResourceFetcher.Failure.*;

class BoundedResourceFetcherTest {
    private static final BoundedResourceFetcher.Limits LIMITS = limits(1024, 3, 5000, 1000);
    private static BoundedResourceFetcher.Limits limits(int bytes, int redirects, int total, int idle) {
        return new BoundedResourceFetcher.Limits(bytes, redirects, Duration.ofMillis(total), Duration.ofSeconds(1), Duration.ofMillis(idle));
    }
    private static InetAddress loopback() throws UnknownHostException { return InetAddress.getByAddress(new byte[]{127,0,0,1}); }

    private static final class Fixture implements AutoCloseable {
        final HttpServer server;
        final ExecutorService executor = Executors.newCachedThreadPool(Thread.ofPlatform().daemon().factory());
        final AtomicInteger hits = new AtomicInteger();
        Fixture(HttpHandler handler) throws IOException {
            server = HttpServer.create(new InetSocketAddress(loopback(), 0), 0);
            server.setExecutor(executor);
            server.createContext("/", exchange -> { hits.incrementAndGet(); try (exchange) { handler.handle(exchange); } });
            server.start();
        }
        URI uri(String path) { return URI.create("http://fixture.example:" + server.getAddress().getPort() + path); }
        InetAddress[] allow(URI uri) throws IOException {
            if (!"http".equals(uri.getScheme()) || !"fixture.example".equals(uri.getHost())
                    || uri.getPort() != server.getAddress().getPort()) throw new BoundedResourceFetcher.FetchException(BLOCKED_ADDRESS);
            return new InetAddress[]{loopback()};
        }
        @Override public void close() { server.stop(0); executor.shutdownNow(); }
    }
    private static void send(HttpExchange exchange, int status, byte[] body) throws IOException {
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
    }
    private static void redirect(HttpExchange exchange, String location) throws IOException {
        exchange.getResponseHeaders().add("Location", location);
        exchange.sendResponseHeaders(302, -1);
    }
    private static void failure(BoundedResourceFetcher.Failure code, org.junit.jupiter.api.function.Executable operation) {
        var error = assertThrows(BoundedResourceFetcher.FetchException.class, operation);
        assertEquals(code, error.failure()); assertEquals(error.diagnosticCode(), error.getMessage()); assertNull(error.getCause());
    }

    @Test void httpFailureIncludesStatusButNotResponseBodyOrSignedUrl() throws Exception {
        try (var fixture = new Fixture(e -> send(e, 403, "secret response token".getBytes(StandardCharsets.UTF_8)));
             var fetcher = new BoundedResourceFetcher(fixture::allow)) {
            var error = assertThrows(BoundedResourceFetcher.FetchException.class,
                    () -> fetcher.fetch(fixture.uri("/video?token=hidden"), LIMITS));
            assertEquals(HTTP_STATUS, error.failure());
            assertEquals("HTTP_STATUS_403", error.diagnosticCode());
            assertNull(error.getCause());assertEquals(0,error.getSuppressed().length);
        }
    }

    @Test void downloadsBytesWithPinnedDnsAndOriginalHostWithoutLeakingTokens() throws Exception {
        var host = new AtomicReference<String>(); var resolutions = new AtomicInteger();
        try (var fixture = new Fixture(e -> { host.set(e.getRequestHeaders().getFirst("Host")); send(e, 200, new byte[]{1,2,3}); });
             var fetcher = new BoundedResourceFetcher(uri -> {
                 if (resolutions.incrementAndGet() != 1) throw new IOException("second DNS resolution must never occur");
                 return fixture.allow(uri);
             })) {
            var resource = fetcher.fetch(fixture.uri("/segment?token=secret"), LIMITS);
            assertArrayEquals(new byte[]{1,2,3}, resource.bytes());
            assertEquals("fixture.example:" + fixture.server.getAddress().getPort(), host.get());
            assertEquals(1, resolutions.get());
            assertFalse(resource.toString().contains("secret"));
            var changed = resource.bytes(); changed[0] = 99;
            assertEquals(1, resource.bytes()[0]);
        }
    }

    @Test void followsRelativeRedirectAndReportsActualFinalBaseWithoutForwardingCookies() throws Exception {
        var sawCookie = new AtomicBoolean(); var checked = new AtomicInteger();
        try (var fixture = new Fixture(e -> {
            if (e.getRequestURI().getPath().equals("/start")) {
                e.getResponseHeaders().add("Set-Cookie", "session=secret; Path=/");
                redirect(e, "/dir/playlist.m3u8?token=child");
            } else {
                sawCookie.set(e.getRequestHeaders().containsKey("Cookie") || e.getRequestHeaders().containsKey("Authorization")
                        || e.getRequestHeaders().containsKey("Referer"));
                send(e, 200, "#EXTM3U".getBytes(StandardCharsets.UTF_8));
            }
        }); var fetcher = new BoundedResourceFetcher(uri -> { checked.incrementAndGet(); return fixture.allow(uri); })) {
            var resource = fetcher.fetch(fixture.uri("/start?token=parent"), LIMITS);
            assertEquals("/dir/playlist.m3u8", resource.finalUri().getPath());
            assertEquals("token=child", resource.finalUri().getQuery());
            assertEquals(2, checked.get()); assertFalse(sawCookie.get());
        }
    }

    @Test void rejectsRedirectToForbiddenOriginBeforeContactingIt() throws Exception {
        try (var forbidden = new Fixture(e -> send(e, 200, new byte[]{1}));
             var fixture = new Fixture(e -> redirect(e, forbidden.uri("/private").toString()));
             var fetcher = new BoundedResourceFetcher(fixture::allow)) {
            failure(BLOCKED_ADDRESS, () -> fetcher.fetch(fixture.uri("/"), LIMITS));
            assertEquals(0, forbidden.hits.get());
        }
    }

    @Test void detectsLoopAndBoundsRedirectCount() throws Exception {
        try (var fixture = new Fixture(e -> redirect(e, e.getRequestURI().getPath().equals("/loop") ? "/loop" : e.getRequestURI().getPath() + "x"));
             var fetcher = new BoundedResourceFetcher(fixture::allow)) {
            failure(REDIRECT_LOOP, () -> fetcher.fetch(fixture.uri("/loop"), LIMITS));
            failure(REDIRECT_LIMIT, () -> fetcher.fetch(fixture.uri("/chain"), limits(100, 1, 5000, 1000)));
            assertEquals(3, fixture.hits.get());
        }
    }

    @Test void rejectsDeclaredOversizeBeforeReadingBody() throws Exception {
        var release = new CountDownLatch(1);
        try (var fixture = new Fixture(e -> {
            e.sendResponseHeaders(200, 500000);
            try { release.await(); } catch (InterruptedException ignored) { }
        });
             var fetcher = new BoundedResourceFetcher(fixture::allow)) {
            failure(TOO_LARGE, () -> fetcher.fetch(fixture.uri("/"), limits(10, 0, 3000, 1000)));
        } finally { release.countDown(); }
    }

    @Test void validatesRedirectSyntaxAndRejectsTlsDowngrade() throws Exception {
        URI origin = URI.create("https://fixture.example/dir/master.m3u8?token=parent");
        assertEquals(URI.create("https://fixture.example/child.m3u8"), BoundedResourceFetcher.redirectTarget(origin, "../child.m3u8"));
        failure(DOWNGRADE, () -> BoundedResourceFetcher.redirectTarget(origin, "http://fixture.example/child"));
        for (String location : new String[]{"file:///private", "https://user:secret@fixture.example/", "https://fixture.example/#fragment", "bad space"}) {
            failure(INVALID_URI, () -> BoundedResourceFetcher.redirectTarget(origin, location));
        }
    }

    @Test void truncatedBodyIsFailureNotPartialSuccess() throws Exception {
        try (var fixture = new Fixture(e -> {
            e.sendResponseHeaders(200, 100);
            e.getResponseBody().write(new byte[]{1, 2, 3}); e.getResponseBody().flush();
        }); var fetcher = new BoundedResourceFetcher(fixture::allow)) {
            failure(IO, () -> fetcher.fetch(fixture.uri("/truncated"), LIMITS));
        }
    }

    @Test void boundsChunkedBodyWithoutTrustingContentLengthAndAcceptsExactLimit() throws Exception {
        try (var fixture = new Fixture(e -> {
            e.sendResponseHeaders(200, 0);
            e.getResponseBody().write(new byte[e.getRequestURI().getPath().equals("/exact") ? 10 : 11]);
        }); var fetcher = new BoundedResourceFetcher(fixture::allow)) {
            assertEquals(10, fetcher.fetch(fixture.uri("/exact"), limits(10, 0, 3000, 1000)).bytes().length);
            failure(TOO_LARGE, () -> fetcher.fetch(fixture.uri("/extra"), limits(10, 0, 3000, 1000)));
        }
    }

    @Test void rejectsCompressionAndHttpErrorsWithoutRetryingOrReadingAnErrorBody() throws Exception {
        try (var fixture = new Fixture(e -> {
            if (e.getRequestURI().getPath().equals("/gzip")) e.getResponseHeaders().add("Content-Encoding", "gzip");
            e.sendResponseHeaders(e.getRequestURI().getPath().equals("/error") ? 503 : 200, 1000000);
        }); var fetcher = new BoundedResourceFetcher(fixture::allow)) {
            failure(ENCODED_BODY, () -> fetcher.fetch(fixture.uri("/gzip"), LIMITS));
            failure(HTTP_STATUS, () -> fetcher.fetch(fixture.uri("/error"), LIMITS));
            assertEquals(2, fixture.hits.get());
        }
    }

    @Test void responseIdleTimeoutStopsStalledHeaders() throws Exception {
        var release = new CountDownLatch(1);
        try (var fixture = new Fixture(e -> { try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException ignored) { } });
             var fetcher = new BoundedResourceFetcher(fixture::allow)) {
            failure(TIMEOUT, () -> fetcher.fetch(fixture.uri("/"), limits(100, 0, 3000, 150)));
        } finally { release.countDown(); }
    }

    @Test void totalDeadlineStopsBodyThatKeepsDrippingWithinIdleTimeout() throws Exception {
        try (var fixture = new Fixture(e -> {
            e.sendResponseHeaders(200, 0);
            for (int i = 0; i < 100; i++) {
                e.getResponseBody().write(1); e.getResponseBody().flush();
                try { Thread.sleep(40); } catch (InterruptedException stop) { return; }
            }
        }); var fetcher = new BoundedResourceFetcher(fixture::allow)) {
            long start = System.nanoTime();
            failure(TIMEOUT, () -> fetcher.fetch(fixture.uri("/"), limits(10000, 0, 300, 1000)));
            assertTrue(Duration.ofNanos(System.nanoTime() - start).toMillis() < 2000);
        }
    }

    @Test void dnsDeadlineReturnsButKeepsOneBusySlotAndNeverConnectsAfterLateResolution() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var worker = new AtomicReference<Thread>();
        try (var fixture = new Fixture(e -> send(e, 200, new byte[]{1}));
             var fetcher = new BoundedResourceFetcher(uri -> {
                 worker.set(Thread.currentThread());
                 entered.countDown();
                 boolean done = false;
                 while (!done) { try { done = release.await(3, TimeUnit.SECONDS); } catch (InterruptedException ignored) { } }
                 return fixture.allow(uri);
             })) {
            failure(TIMEOUT, () -> fetcher.fetch(fixture.uri("/"), limits(100, 0, 150, 1000)));
            assertEquals(0, entered.getCount());
            failure(BUSY, () -> fetcher.fetch(fixture.uri("/"), LIMITS));
            release.countDown();
            worker.get().join(2000);
            assertFalse(worker.get().isAlive());
            assertEquals(0, fixture.hits.get());
        } finally { release.countDown(); }
    }

    @Test void interruptionCancelsCallerAndPreservesInterruptFlag() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        try (var fixture = new Fixture(e -> { entered.countDown(); try { release.await(); } catch (InterruptedException ignored) { } });
             var fetcher = new BoundedResourceFetcher(fixture::allow)) {
            var observed = new AtomicReference<BoundedResourceFetcher.FetchException>();
            var interrupted = new AtomicBoolean();
            Thread caller = Thread.ofPlatform().start(() -> {
                try { fetcher.fetch(fixture.uri("/"), LIMITS); }
                catch (BoundedResourceFetcher.FetchException failure) { observed.set(failure); interrupted.set(Thread.currentThread().isInterrupted()); }
            });
            assertTrue(entered.await(2, TimeUnit.SECONDS)); caller.interrupt(); caller.join(2000);
            assertFalse(caller.isAlive()); assertEquals(INTERRUPTED, observed.get().failure()); assertTrue(interrupted.get());
        } finally { release.countDown(); }
    }

    @Test void publicPolicyRejectsAnyNonPublicDnsAnswerAndUnsupportedUrisWithoutConnections() throws Exception {
        for (String value : new String[]{"http://localhost/x", "file:///secret", "https://user:pass@example.org/x", "https://example.org:8080/x"}) {
            failure(INVALID_URI, () -> BoundedResourceFetcher.publicAddresses(URI.create(value), host -> { fail("DNS should not be used"); return null; }));
        }
        var publicIp = InetAddress.getByAddress(new byte[]{8,8,8,8});
        failure(BLOCKED_ADDRESS, () -> BoundedResourceFetcher.publicAddresses(URI.create("https://example.org/x"), h -> new InetAddress[]{publicIp, loopback()}));
        failure(BLOCKED_ADDRESS, () -> BoundedResourceFetcher.publicAddresses(URI.create("http://127.0.0.1/x"), h -> new InetAddress[]{loopback()}));
        assertArrayEquals(new InetAddress[]{publicIp}, BoundedResourceFetcher.publicAddresses(URI.create("https://example.org/x"), h -> new InetAddress[]{publicIp}));
    }

    @Test void closeCancelsActiveRequestAndRejectsFutureRequests() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        try (var fixture = new Fixture(e -> { entered.countDown(); try { release.await(); } catch (InterruptedException ignored) { } });
             var fetcher = new BoundedResourceFetcher(fixture::allow)) {
            var observed = new AtomicReference<BoundedResourceFetcher.FetchException>();
            Thread caller = Thread.ofPlatform().start(() -> {
                try { fetcher.fetch(fixture.uri("/"), LIMITS); }
                catch (BoundedResourceFetcher.FetchException failure) { observed.set(failure); }
            });
            assertTrue(entered.await(2, TimeUnit.SECONDS)); fetcher.close(); caller.join(2000);
            assertFalse(caller.isAlive()); assertNotNull(observed.get()); assertEquals(CANCELLED, observed.get().failure());
            failure(CLOSED, () -> fetcher.fetch(fixture.uri("/"), LIMITS));
        } finally { release.countDown(); }
    }

    @Test void closedFetcherAndInvalidLimitsFailWithoutIo() throws Exception {
        var fetcher = new BoundedResourceFetcher(); fetcher.close();
        failure(CLOSED, () -> fetcher.fetch(URI.create("https://example.org"), LIMITS));
        assertThrows(IllegalArgumentException.class, () -> limits(0, 0, 1000, 1000));
        assertThrows(IllegalArgumentException.class, () -> limits(10, 6, 1000, 1000));
    }

    @Test void defaultTlsRejectsUntrustedCertificate(@TempDir Path directory) throws Exception {
        Path store = directory.resolve("test.p12");
        String executable = System.getProperty("os.name").startsWith("Windows") ? "keytool.exe" : "keytool";
        var keytool = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", executable).toString(),
                "-genkeypair", "-alias", "fixture", "-keyalg", "RSA", "-keysize", "2048", "-validity", "2",
                "-dname", "CN=fixture.example", "-ext", "SAN=dns:fixture.example", "-storetype", "PKCS12",
                "-keystore", store.toString(), "-storepass", "fixture-only", "-keypass", "fixture-only", "-noprompt")
                .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        try { assertTrue(keytool.waitFor(15, TimeUnit.SECONDS)); assertEquals(0, keytool.exitValue()); }
        finally { keytool.destroyForcibly(); }
        KeyStore keys = KeyStore.getInstance("PKCS12");
        try (var input = Files.newInputStream(store)) { keys.load(input, "fixture-only".toCharArray()); }
        var managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        managers.init(keys, "fixture-only".toCharArray());
        var ssl = SSLContext.getInstance("TLS"); ssl.init(managers.getKeyManagers(), null, null);
        var server = HttpsServer.create(new InetSocketAddress(loopback(), 0), 0);
        var hits = new AtomicInteger();
        server.setHttpsConfigurator(new HttpsConfigurator(ssl));
        server.createContext("/", e -> { hits.incrementAndGet(); e.close(); }); server.start();
        URI uri = URI.create("https://fixture.example:" + server.getAddress().getPort() + "/secret");
        try (var fetcher = new BoundedResourceFetcher(u -> {
            if (!u.equals(uri)) throw new BoundedResourceFetcher.FetchException(BLOCKED_ADDRESS);
            return new InetAddress[]{loopback()};
        })) {
            var error = assertThrows(BoundedResourceFetcher.FetchException.class, () -> fetcher.fetch(uri, LIMITS));
            assertEquals(IO, error.failure());
            assertEquals("IO_TLS_CERTIFICATE", error.diagnosticCode());
            assertNull(error.getCause());
            assertEquals(0, hits.get());
        } finally { server.stop(0); }
    }
}
