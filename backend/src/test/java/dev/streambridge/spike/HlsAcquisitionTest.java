package dev.streambridge.spike;

import com.sun.net.httpserver.*;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class HlsAcquisitionTest {
    private static final String ORIGIN = "http://fixture.example";
    private static final String MASTER = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\n/video/start\n";
    private static final String TWO_INPUTS = """
            #EXTM3U
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="g",NAME="Chosen",URI="/audio/index.m3u8"
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="g",NAME="Other",URI="/unused/audio.m3u8"
            #EXT-X-STREAM-INF:BANDWIDTH=1,AUDIO="g"
            /video/start
            #EXT-X-STREAM-INF:BANDWIDTH=2,AUDIO="g"
            /unused/video.m3u8
            """;
    private static String vod(String... segments) {
        var text = new StringBuilder("#EXTM3U\n#EXT-X-TARGETDURATION:1\n");
        for (String segment : segments) text.append("#EXTINF:1,\n").append(segment).append('\n');
        return text.append("#EXT-X-ENDLIST\n").toString();
    }
    private static HlsCandidateCatalog catalog(String master) {
        var catalog = new HlsCandidateCatalog(new RealDiscoverySpike()::label);
        catalog.observe(ORIGIN + "/master", "https://page.example/watch", master);
        return catalog;
    }
    private static HlsTransferPlan plan(HlsCandidateCatalog catalog, String audio) {
        return HlsTransferPlan.build(catalog, catalog.choose(catalog.versionedSnapshot().revision(), "C1", "V1", audio));
    }
    private static HlsAcquisition.Limits limits(long bytes, int requests, int millis) {
        return new HlsAcquisition.Limits(bytes, requests, Duration.ofMillis(millis),
                new BoundedResourceFetcher.Limits(1000000, 3, Duration.ofSeconds(5), Duration.ofSeconds(1), Duration.ofSeconds(1)));
    }
    private static final HlsAcquisition.Limits LIMITS = limits(1000000, 20, 10000);

    /** Translate ONLY this test origin's port. Parser/default public policy are NOT relaxed. */
    private static final class Fixture implements AutoCloseable {
        final HttpServer server;
        final ExecutorService executor = Executors.newCachedThreadPool(Thread.ofPlatform().daemon().factory());
        final List<String> paths = new CopyOnWriteArrayList<>();
        final BoundedResourceFetcher fetcher;
        final HlsAcquisition acquisition;
        final String wireOrigin;
        Fixture(HttpHandler handler) throws IOException {
            var loopback = InetAddress.getByAddress(new byte[]{127,0,0,1});
            server = HttpServer.create(new InetSocketAddress(loopback, 0), 0);
            server.setExecutor(executor);
            server.createContext("/", e -> { paths.add(e.getRequestURI().getPath()); try (e) { handler.handle(e); } });
            wireOrigin = ORIGIN + ":" + server.getAddress().getPort();
            fetcher = new BoundedResourceFetcher(uri -> {
                if (!"http".equals(uri.getScheme()) || !"fixture.example".equals(uri.getHost())
                        || uri.getPort() != server.getAddress().getPort()) {
                    throw new BoundedResourceFetcher.FetchException(BoundedResourceFetcher.Failure.BLOCKED_ADDRESS);
                }
                return new InetAddress[]{loopback};
            });
            acquisition = new HlsAcquisition((uri, bounds, requests) -> {
                if (!"http".equals(uri.getScheme()) || !"fixture.example".equals(uri.getHost()) || uri.getPort() != -1) {
                    throw new BoundedResourceFetcher.FetchException(BoundedResourceFetcher.Failure.BLOCKED_ADDRESS);
                }
                URI wireUri = URI.create(wireOrigin + uri.getRawPath() + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery()));
                var response = fetcher.fetch(wireUri, bounds, requests);
                URI finalUri = URI.create(ORIGIN + response.finalUri().getRawPath()
                        + (response.finalUri().getRawQuery() == null ? "" : "?" + response.finalUri().getRawQuery()));
                return new BoundedResourceFetcher.Resource(finalUri, response.bytes());
            });
            server.start();
        }
        public void close() { fetcher.close(); server.stop(0); executor.shutdownNow(); }
    }
    private static void send(HttpExchange e, byte[] bytes) throws IOException {
        e.sendResponseHeaders(200, 0); e.getResponseBody().write(bytes);
    }
    private static void send(HttpExchange e, String text) throws IOException { send(e, text.getBytes(StandardCharsets.UTF_8)); }
    private static HlsAcquisition.AcquisitionException fails(HlsAcquisition.Failure expected, org.junit.jupiter.api.function.Executable action) {
        var error = assertThrows(HlsAcquisition.AcquisitionException.class, action);
        assertEquals(expected, error.failure()); assertEquals(expected.name(), error.getMessage()); assertNull(error.getCause());
        return error;
    }

    @Test void acquiresOnlySelectedInputsAfterValidatingBothPlaylistsAndResolvesRedirectBase() throws Exception {
        var catalog = catalog(TWO_INPUTS); var plan = plan(catalog, "A1");
        String video = vod("part.ts?token=secret", "part.ts?token=secret"), audio = vod("sound.ts");
        try (var fixture = new Fixture(e -> {
            switch (e.getRequestURI().getPath()) {
                case "/video/start" -> { e.getResponseHeaders().add("Location", "/resolved/video.m3u8"); e.sendResponseHeaders(302, -1); }
                case "/resolved/video.m3u8" -> send(e, video);
                case "/audio/index.m3u8" -> send(e, audio);
                case "/resolved/part.ts" -> send(e, new byte[]{1,2});
                case "/audio/sound.ts" -> send(e, new byte[]{3});
                default -> e.sendResponseHeaders(404, -1);
            }
        })) {
            var result = fixture.acquisition.acquire(catalog, plan, LIMITS);
            assertEquals(List.of("/video/start", "/resolved/video.m3u8", "/audio/index.m3u8",
                    "/resolved/part.ts", "/resolved/part.ts", "/audio/sound.ts"), fixture.paths);
            assertEquals(6, result.requestAttempts());
            assertEquals(video.getBytes(StandardCharsets.UTF_8).length + audio.getBytes(StandardCharsets.UTF_8).length + 5, result.payloadBytes());
            assertEquals(HlsTransferPlan.Role.SEPARATE_AUDIO, result.inputs().get(1).role());
            assertArrayEquals(new byte[]{1,2}, result.inputs().getFirst().segments().get(1).bytes());
            assertFalse(result.toString().contains("secret")); assertFalse(result.toString().contains("fixture.example"));
            assertThrows(UnsupportedOperationException.class, () -> result.inputs().clear());
        }
    }

    @Test void staleAndTamperedPlansFailBeforeAnyRequest() throws Exception {
        var catalog = catalog(MASTER); var plan = plan(catalog, "unknown");
        try (var fixture = new Fixture(e -> send(e, "unexpected"))) {
            var forged = new HlsTransferPlan(plan.choice(), List.of());
            fails(HlsAcquisition.Failure.STALE_OR_INVALID_PLAN, () -> fixture.acquisition.acquire(catalog, forged, LIMITS));
            catalog.observe(ORIGIN + "/video/start", "https://page.example/watch", vod("part.ts"));
            fails(HlsAcquisition.Failure.STALE_OR_INVALID_PLAN, () -> fixture.acquisition.acquire(catalog, plan, LIMITS));
            assertTrue(fixture.paths.isEmpty());
        }
    }

    @Test void changedObservedPlaylistFailsBeforeSegments() throws Exception {
        var catalog = catalog(MASTER);
        catalog.observe(ORIGIN + "/video/start", "https://page.example/watch", vod("old.ts"));
        var plan = plan(catalog, "unknown");
        try (var fixture = new Fixture(e -> send(e, vod("changed.ts")))) {
            fails(HlsAcquisition.Failure.PLAYLIST_CHANGED, () -> fixture.acquisition.acquire(catalog, plan, LIMITS));
            assertEquals(List.of("/video/start"), fixture.paths);
        }
    }

    @Test void unchangedObservedPlaylistIsStillFetchedAndAccepted() throws Exception {
        var catalog = catalog(MASTER); String body = vod("part.ts");
        catalog.observe(ORIGIN + "/video/start", "https://page.example/watch", body);
        try (var fixture = new Fixture(e -> { if (e.getRequestURI().getPath().equals("/video/start")) send(e, body); else send(e, new byte[]{9}); })) {
            var result = fixture.acquisition.acquire(catalog, plan(catalog, "unknown"), LIMITS);
            assertEquals(2, result.requestAttempts()); assertEquals(List.of("/video/start", "/video/part.ts"), fixture.paths);
        }
    }

    @Test void unsupportedSecondPlaylistStopsBeforeVideoSegments() throws Exception {
        var catalog = catalog(TWO_INPUTS);
        try (var fixture = new Fixture(e -> send(e, e.getRequestURI().getPath().equals("/video/start")
                ? vod("part.ts") : vod("sound.ts").replace("#EXTINF", "#EXT-X-KEY:METHOD=NONE\n#EXTINF")))) {
            fails(HlsAcquisition.Failure.INVALID_PLAYLIST, () -> fixture.acquisition.acquire(catalog, plan(catalog, "A1"), LIMITS));
            assertEquals(List.of("/video/start", "/audio/index.m3u8"), fixture.paths);
        }
    }

    @Test void legacyCacheYesAllowsAcquisitionWithoutChangingSelectedResources() throws Exception {
        var catalog = catalog(MASTER);
        String body = vod("part.ts").replace("#EXTM3U", "#EXTM3U\n#EXT-X-ALLOW-CACHE:YES");
        try (var fixture = new Fixture(e -> { if (e.getRequestURI().getPath().equals("/video/start")) send(e, body); else send(e, new byte[]{1}); })) {
            var result = fixture.acquisition.acquire(catalog, plan(catalog, "unknown"), LIMITS);
            assertEquals(HlsVodPlaylist.CacheDirective.YES, result.inputs().getFirst().playlist().cacheDirective());
            assertEquals(List.of("/video/start", "/video/part.ts"), fixture.paths);
        }
    }

    @Test void legacyCacheNoIsParsedButBlocksRetentionBeforeSegments() throws Exception {
        var catalog = catalog(TWO_INPUTS);
        try (var fixture = new Fixture(e -> send(e, e.getRequestURI().getPath().equals("/video/start")
                ? vod("part.ts") : vod("sound.ts").replace("#EXTM3U", "#EXTM3U\n#EXT-X-ALLOW-CACHE:NO")))) {
            fails(HlsAcquisition.Failure.CACHE_REPLAY_DISALLOWED, () -> fixture.acquisition.acquire(catalog, plan(catalog, "A1"), LIMITS));
            assertEquals(List.of("/video/start", "/audio/index.m3u8"), fixture.paths);
        }
    }

    @Test void rejectsMalformedUtf8RatherThanReplacingBytes() throws Exception {
        var catalog = catalog(MASTER);
        try (var fixture = new Fixture(e -> send(e, new byte[]{(byte)0xc3, 0x28}))) {
            fails(HlsAcquisition.Failure.INVALID_PLAYLIST, () -> fixture.acquisition.acquire(catalog, plan(catalog, "unknown"), LIMITS));
        }
    }

    @Test void aggregatePayloadIncludesPlaylistAndAcceptsExactBoundary() throws Exception {
        var catalog = catalog(MASTER); var plan = plan(catalog, "unknown"); String body = vod("part.ts");
        int bytes = body.getBytes(StandardCharsets.UTF_8).length + 3;
        try (var fixture = new Fixture(e -> { if (e.getRequestURI().getPath().equals("/video/start")) send(e, body); else send(e, new byte[]{1,2,3}); })) {
            assertEquals(bytes, fixture.acquisition.acquire(catalog, plan, limits(bytes, 10, 5000)).payloadBytes());
            var error = fails(HlsAcquisition.Failure.FETCH_FAILED, () -> fixture.acquisition.acquire(catalog, plan, limits(bytes - 1, 10, 5000)));
            assertEquals(BoundedResourceFetcher.Failure.TOO_LARGE, error.fetchFailure());
        }
    }

    @Test void requestBudgetCountsRedirectsAndNeverContactsExcessHop() throws Exception {
        var catalog = catalog(MASTER);
        try (var fixture = new Fixture(e -> { e.getResponseHeaders().add("Location", "/redirected"); e.sendResponseHeaders(302, -1); })) {
            var error = fails(HlsAcquisition.Failure.FETCH_FAILED, () -> fixture.acquisition.acquire(catalog, plan(catalog, "unknown"), limits(10000, 1, 5000)));
            assertEquals(BoundedResourceFetcher.Failure.REQUEST_LIMIT, error.fetchFailure());
            assertEquals(List.of("/video/start"), fixture.paths);
        }
    }

    @Test void tooManyDeclaredSegmentsFailBeforeAnySegmentRequest() throws Exception {
        var catalog = catalog(MASTER);
        try (var fixture = new Fixture(e -> send(e, vod("one.ts", "two.ts")))) {
            var error = fails(HlsAcquisition.Failure.FETCH_FAILED, () -> fixture.acquisition.acquire(catalog, plan(catalog, "unknown"), limits(10000, 2, 5000)));
            assertEquals(BoundedResourceFetcher.Failure.REQUEST_LIMIT, error.fetchFailure());
            assertEquals(List.of("/video/start"), fixture.paths);
        }
    }

    @Test void overallDeadlineDoesNotResetForNextResource() throws Exception {
        var catalog = catalog(MASTER); var calls = new AtomicInteger(); var timeouts = new ArrayList<Long>();
        var nanos = new AtomicLong();
        var acquisition = new HlsAcquisition((uri, bounds, requests) -> {
            requests.claim(); timeouts.add(bounds.total().toMillis()); calls.incrementAndGet();
            nanos.addAndGet(Duration.ofMillis(100).toNanos());
            return new BoundedResourceFetcher.Resource(uri, vod("part.ts").getBytes(StandardCharsets.UTF_8));
        }, nanos::get);
        fails(HlsAcquisition.Failure.TIMEOUT, () -> acquisition.acquire(catalog, plan(catalog, "unknown"), limits(10000, 5, 180)));
        assertEquals(2, calls.get()); assertTrue(timeouts.get(1) < timeouts.getFirst());
    }

    @Test void interruptBeforeStartPreservesFlagAndDoesNotFetch() throws Exception {
        var catalog = catalog(MASTER); var plan = plan(catalog, "unknown");
        var acquisition = new HlsAcquisition((uri, bounds, requests) -> { fail("No fetch expected"); return null; });
        try {
            Thread.currentThread().interrupt();
            fails(HlsAcquisition.Failure.INTERRUPTED, () -> acquisition.acquire(catalog, plan, LIMITS));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
    }

    @Test void emptySegmentDoesNotBecomeSuccessfulResult() throws Exception {
        var catalog = catalog(MASTER);
        try (var fixture = new Fixture(e -> { if (e.getRequestURI().getPath().equals("/video/start")) send(e, vod("part.ts")); else send(e, new byte[0]); })) {
            fails(HlsAcquisition.Failure.EMPTY_SEGMENT, () -> fixture.acquisition.acquire(catalog, plan(catalog, "unknown"), LIMITS));
        }
    }

    @Test void exhaustedByteBudgetStopsBeforeNextRequest() throws Exception {
        var catalog = catalog(MASTER); String body = vod("part.ts");
        try (var fixture = new Fixture(e -> send(e, body))) {
            fails(HlsAcquisition.Failure.PAYLOAD_LIMIT, () -> fixture.acquisition.acquire(catalog, plan(catalog, "unknown"),
                    limits(body.getBytes(StandardCharsets.UTF_8).length, 10, 5000)));
            assertEquals(List.of("/video/start"), fixture.paths);
        }
    }

    @Test void segmentRedirectConsumesSharedBudgetAndBlocksFollowingSegment() throws Exception {
        var catalog = catalog(MASTER);
        try (var fixture = new Fixture(e -> {
            switch (e.getRequestURI().getPath()) {
                case "/video/start" -> send(e, vod("one.ts", "two.ts"));
                case "/video/one.ts" -> { e.getResponseHeaders().add("Location", "/resolved/one.ts"); e.sendResponseHeaders(302, -1); }
                default -> send(e, new byte[]{1});
            }
        })) {
            var error = fails(HlsAcquisition.Failure.FETCH_FAILED, () -> fixture.acquisition.acquire(catalog, plan(catalog, "unknown"), limits(10000, 3, 5000)));
            assertEquals(BoundedResourceFetcher.Failure.REQUEST_LIMIT, error.fetchFailure());
            assertEquals(List.of("/video/start", "/video/one.ts", "/resolved/one.ts"), fixture.paths);
        }
    }

    @Test void segmentFailureStopsBeforeLaterSegments() throws Exception {
        var catalog = catalog(MASTER);
        try (var fixture = new Fixture(e -> {
            if (e.getRequestURI().getPath().equals("/video/start")) send(e, vod("one.ts", "two.ts", "three.ts"));
            else if (e.getRequestURI().getPath().equals("/video/two.ts")) e.sendResponseHeaders(403, -1);
            else send(e, new byte[]{1});
        })) {
            var error = fails(HlsAcquisition.Failure.FETCH_FAILED, () -> fixture.acquisition.acquire(catalog, plan(catalog, "unknown"), LIMITS));
            assertEquals(BoundedResourceFetcher.Failure.HTTP_STATUS, error.fetchFailure());
            assertEquals(List.of("/video/start", "/video/one.ts", "/video/two.ts"), fixture.paths);
        }
    }

    @Test void syntacticallyValidButForbiddenSegmentIsNotFetched() throws Exception {
        var catalog = catalog(MASTER);
        try (var fixture = new Fixture(e -> send(e, vod("http://127.0.0.1/secret")))) {
            var error = fails(HlsAcquisition.Failure.FETCH_FAILED, () -> fixture.acquisition.acquire(catalog, plan(catalog, "unknown"), LIMITS));
            assertEquals(BoundedResourceFetcher.Failure.BLOCKED_ADDRESS, error.fetchFailure());
            assertEquals(List.of("/video/start"), fixture.paths);
        }
    }

    @Test void invalidAggregateLimitsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> limits(0, 10, 1000));
        assertThrows(IllegalArgumentException.class, () -> limits(33 * 1024 * 1024, 10, 1000));
        assertThrows(IllegalArgumentException.class, () -> limits(1000, 0, 1000));
        assertThrows(IllegalArgumentException.class, () -> limits(1000, 1001, 1000));
        assertThrows(IllegalArgumentException.class, () -> limits(1000, 10, 120001));
    }
}
