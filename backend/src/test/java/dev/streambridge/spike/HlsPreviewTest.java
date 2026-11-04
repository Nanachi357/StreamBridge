package dev.streambridge.spike;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.net.URI;
import java.math.BigDecimal;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class HlsPreviewTest {
    @TempDir Path root;
    @Test void tinyDurationsCannotResetRequestBudgetAndNeverStartFfmpeg() throws Exception {
        Files.createFile(root.resolve("ffmpeg.exe"));
        Path work=Files.createDirectory(root.resolve("budget"));
        String manifest="#EXTM3U\n#EXT-X-TARGETDURATION:1\n"+"#EXTINF:0.001,\na.ts\n".repeat(6000)+"#EXT-X-ENDLIST\n";
        var p=HlsVodPlaylist.parse(URI.create("https://example.org/list"),manifest);
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        var shared=new HashSet<BoundedResourceFetcher.RequestBudget>();
        var error=assertThrows(HlsPreview.LimitException.class,()->HlsPreview.create(List.of(p),work,(uri,limits,budget)->{
            shared.add(budget);budget.claim();calls.incrementAndGet();return packet(uri);
        },root,TransferFeedback.NONE,5000));
        assertEquals("PREVIEW_REQUEST_LIMIT",error.getMessage());assertEquals(128,calls.get());assertEquals(1,shared.size());
        try(var files=Files.list(work)){assertEquals(0,files.count());}
    }
    @Test void redirectsAndBothTracksShareOneBudget() throws Exception {
        Files.createFile(root.resolve("ffmpeg.exe"));
        var p=playlist(URI.create("https://example.org/a.ts"));
        var budgets=new HashSet<BoundedResourceFetcher.RequestBudget>();var calls=new java.util.concurrent.atomic.AtomicInteger();
        var error=assertThrows(HlsPreview.LimitException.class,()->HlsPreview.create(List.of(p,p),root,(uri,limits,budget)->{
            calls.incrementAndGet();budgets.add(budget);budget.claim();budget.claim(); // Initial request and a redirect.
            return packet(uri);
        },root,TransferFeedback.NONE,5000,new HlsPreview.Limits(1000,3,java.time.Duration.ofSeconds(10)),System::nanoTime));
        assertEquals("PREVIEW_REQUEST_LIMIT",error.getMessage());assertEquals(2,calls.get());assertEquals(1,budgets.size());
        assertFalse(Files.exists(root.resolve("preview-input-0.ts")));assertFalse(Files.exists(root.resolve("preview-input-1.ts")));
    }
    @Test void byteBudgetIncludesBothTracksAndCapsNextResponse() throws Exception {
        Files.createFile(root.resolve("ffmpeg.exe"));
        var p=playlist(URI.create("https://example.org/a.ts"));var calls=new java.util.concurrent.atomic.AtomicInteger();
        var error=assertThrows(HlsPreview.LimitException.class,()->HlsPreview.create(List.of(p,p),root,(uri,limits,budget)->{
            budget.claim();assertEquals(calls.getAndIncrement()==0?300:112,limits.maxBytes());
            return packet(uri); // Test seam deliberately ignores the cap; consumer still rejects it.
        },root,TransferFeedback.NONE,5000,new HlsPreview.Limits(300,10,java.time.Duration.ofSeconds(10)),System::nanoTime));
        assertEquals("PREVIEW_BYTE_LIMIT",error.getMessage());assertEquals(2,calls.get());
        assertFalse(Files.exists(root.resolve("preview-input-0.ts")));
    }
    @Test void absoluteDeadlineRejectsLateResponseBeforeRemuxAndClampsFetchTimeout() throws Exception {
        Files.createFile(root.resolve("ffmpeg.exe"));var clock=new java.util.concurrent.atomic.AtomicLong();
        var p=playlist(URI.create("https://example.org/a.ts"));
        var error=assertThrows(HlsPreview.LimitException.class,()->HlsPreview.create(List.of(p),root,(uri,limits,budget)->{
            budget.claim();assertEquals(75,limits.total().toMillis());assertEquals(75,limits.connect().toMillis());
            clock.set(76_000_000);return packet(uri);
        },root,TransferFeedback.NONE,5000,new HlsPreview.Limits(1000,10,java.time.Duration.ofMillis(75)),clock::get));
        assertEquals("PREVIEW_TIME_LIMIT",error.getMessage());assertFalse(Files.exists(root.resolve("preview-input-0.ts")));
    }
    private static BoundedResourceFetcher.Resource packet(URI uri) {
        byte[] bytes=new byte[188];bytes[0]=0x47;return new BoundedResourceFetcher.Resource(uri,bytes);
    }
    @Test void durationAllowsPacketBoundaryRoundingButNotUnboundedMedia() throws Exception {
        HlsPreview.validateDuration(5, 5000); HlsPreview.validateDuration(5.08, 5000);
        for (double value : new double[]{0, -1, 6.001, Double.NaN, Double.POSITIVE_INFINITY})
            assertThrows(java.io.IOException.class, () -> HlsPreview.validateDuration(value, 5000));
    }
    @Test void maximumIsShortestRequiredTrackRoundedDownAndValidatedBeforeIo() throws Exception {
        var v = playlist(URI.create("https://example.org/video.ts"));
        var a = new HlsVodPlaylist(v.segments(), new BigDecimal("3.456789"), 3, HlsVodPlaylist.CacheDirective.YES);
        assertEquals(3456, HlsPreview.maximumMillis(List.of(v, a)));
        for (long millis : new long[]{0, -1, 3457, Long.MAX_VALUE})
            assertThrows(IllegalArgumentException.class, () -> HlsPreview.create(List.of(v,a), root,
                    (uri, limits, budget) -> { throw new AssertionError("No network for invalid duration"); },
                    root, TransferFeedback.NONE, millis));
    }
    @Test void failedOrCancelledAcquisitionCleansOwnedInputsWithoutPublishing() throws Exception {
        Files.createFile(root.resolve("ffmpeg.exe")); // Sentinel for path validation; must never be executed.
        for (boolean cancellation : List.of(false, true)) {
            Path directory = Files.createDirectory(root.resolve(cancellation ? "cancelled" : "invalid"));
            var stopped = new java.util.concurrent.atomic.AtomicBoolean();
            var fetched = new java.util.concurrent.atomic.AtomicBoolean();
            var feedback = new TransferFeedback() { public boolean cancelled() { return stopped.get(); } };
            var playlist = playlist(URI.create("https://example.org/video.ts"));
            Class<? extends Exception> expected = cancellation ? InterruptedException.class : java.io.IOException.class;
            assertThrows(expected, () ->
                    HlsPreview.create(List.of(playlist), directory, (uri, limits, budget) -> {
                        budget.claim(); fetched.set(true); stopped.set(cancellation);
                        byte[] packet = new byte[188]; if (cancellation) packet[0] = 0x47;
                        return new BoundedResourceFetcher.Resource(uri, packet);
                    }, root, feedback));
            assertTrue(fetched.get());
            try (var files = Files.list(directory)) { assertEquals(0, files.count()); }
        }
    }
    @Test void remuxesMuxedSeparateAndSilentMediaWithoutChangingDecodedFramesOrAudio() throws Exception {
        String configured = System.getenv("STREAMBRIDGE_FFMPEG_DIR");
        assumeTrue(configured != null, "Optional encoded-media check requires FFmpeg/FFprobe directory");
        Path tools = Path.of(configured), ffmpeg = HlsDownloadSpike.executable(tools, "ffmpeg.exe");
        Path generator = HlsDownloadSpike.executable(Path.of(System.getenv()
                .getOrDefault("STREAMBRIDGE_FIXTURE_FFMPEG_DIR", configured)), "ffmpeg.exe");
        for (String mode : List.of("muxed", "separate", "silent")) {
            Path directory = Files.createDirectory(root.resolve(mode));
            Path video = directory.resolve("fixture.ts"), audio = directory.resolve("audio.ts");
            var command = new ArrayList<>(List.of(generator.toString(), "-nostdin", "-v", "error", "-f", "lavfi", "-i", "testsrc2=size=960x540:rate=24000/1001"));
            if (mode.equals("muxed")) command.addAll(List.of("-f", "lavfi", "-i", "sine=frequency=440"));
            command.addAll(List.of("-t", "8", "-c:v", "libx264", "-threads", "2", "-preset", "fast", "-bf", "3", "-c:a", "aac", "-f", "mpegts", video.toString()));
            HlsDownloadSpike.runChecked(command, directory.resolve("generation.log"), 20_000);
            if (mode.equals("separate")) HlsDownloadSpike.runChecked(List.of(generator.toString(), "-nostdin", "-v", "error", "-f", "lavfi", "-i", "sine=frequency=880",
                    "-t", "8", "-c:a", "aac", "-f", "mpegts", audio.toString()), directory.resolve("audio-generation.log"), 20_000);
            URI v = URI.create("https://example.org/video.ts"), a = URI.create("https://example.org/audio.ts");
            var playlists = new ArrayList<HlsVodPlaylist>(); playlists.add(playlist(v));
            if (mode.equals("separate")) playlists.add(playlist(a));
            var requested = new ArrayList<URI>();
            byte[] videoBytes = Files.readAllBytes(video), audioBytes = mode.equals("separate") ? Files.readAllBytes(audio) : new byte[0];
            Path result = HlsPreview.create(playlists, directory, (uri, limits, budget) -> {
                budget.claim(); requested.add(uri);
                if (!Set.of(v, a).contains(uri)) throw new AssertionError("Unexpected URI");
                return new BoundedResourceFetcher.Resource(uri, uri.equals(v) ? videoBytes : audioBytes);
            }, tools, TransferFeedback.NONE);
            assertTrue(Files.size(result) > 0); assertEquals(playlists.size(), requested.size());
            assertFalse(Files.exists(directory.resolve("preview-input-0.ts"))); assertFalse(Files.exists(directory.resolve("preview.part")));
            HlsDownloadSpike.runChecked(List.of(ffmpeg.toString(), "-nostdin", "-v", "error", "-xerror", "-protocol_whitelist", "file", "-i", result.toString(),
                    "-map", "0:v", "-map", mode.equals("silent") ? "0:a?" : "0:a", "-f", "null", "-"), directory.resolve("decode.log"), 15_000);
            // The independent full-tool oracle needs framemd5/PCM output; runtime decoding above
            // and all application operations still use the candidate under test.
            assertDecodedPrefixUnchanged(generator, video, result, "v:0", directory);
            if (!mode.equals("silent")) assertDecodedPrefixUnchanged(generator, mode.equals("separate") ? audio : video, result, "a:0", directory);
            for (Path media : List.of(video, result)) {
                Path metadata = directory.resolve(media.getFileName() + "-dimensions.log");
                HlsDownloadSpike.runChecked(List.of(HlsDownloadSpike.executable(tools, "ffprobe.exe").toString(),
                        "-v", "error", "-select_streams", "v:0", "-show_entries", "stream=width,height,r_frame_rate",
                        "-of", "csv=p=0", media.toString()), metadata, 10_000);
                assertTrue(Files.readString(metadata).contains("960,540,24000/1001"));
            }
            if (mode.equals("muxed") && "1".equals(System.getenv("STREAMBRIDGE_UI_BROWSER_CHECK"))) verifyUiPlayback(result);
            if (mode.equals("muxed")) verifyPreviewThenDecline(tools, videoBytes);
            // Replace a previous clip with a longer one; selected quality and cleanup still hold.
            HlsPreview.create(playlists, directory, (uri, limits, budget) -> {
                budget.claim(); return new BoundedResourceFetcher.Resource(uri, uri.equals(v) ? videoBytes : audioBytes);
            }, tools, TransferFeedback.NONE, 7500);
            double longer = Double.parseDouble(Files.readString(directory.resolve("preview-probe.log")).strip());
            assertTrue(longer >= 7 && longer <= 8.5);
            assertDecodedPrefixUnchanged(generator, video, result, "v:0", directory);
        }
    }
    private void verifyPreviewThenDecline(Path tools, byte[] video) throws Exception {
        var catalog = new HlsCandidateCatalog(uri -> "fixture");
        catalog.observe("https://example.org/master", "https://example.org/page", "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1000\n/list\n");
        var plan = HlsTransferPlan.build(catalog, catalog.choose(catalog.versionedSnapshot().revision(), "C1", "V1", "unknown"));
        var downloaded = new ArrayList<String>();
        var previewPath = new java.util.concurrent.atomic.AtomicReference<Path>();
        var cleaned = new java.util.concurrent.atomic.AtomicBoolean();
        var feedback = new TransferFeedback() {
            public boolean alwaysReview() { return true; }
            public boolean approve(DownloadReview review, java.util.function.Predicate<DownloadReview> fallback, Preview preview) {
                try { previewPath.set(preview.create()); assertTrue(Files.exists(previewPath.get())); }
                catch (Exception e) { throw new AssertionError(e); }
                return false; // Watching the clip must not approve the full transfer.
            }
            public void cleanupComplete() { cleaned.set(true); }
        };
        String manifest = "#EXTM3U\n#EXT-X-TARGETDURATION:8\n#EXTINF:8,\n/video.ts\n#EXT-X-ENDLIST\n";
        var result = RealHlsSampleProbe.run(plan, root, line -> {}, true, 1000, review -> false,
                (uri, limits, budget) -> {
                    budget.claim(); downloaded.add(uri.getPath());
                    return new BoundedResourceFetcher.Resource(uri, uri.getPath().equals("/list")
                            ? manifest.getBytes(java.nio.charset.StandardCharsets.UTF_8) : video);
                }, tools, feedback);
        assertEquals(RealHlsSampleProbe.Result.CANCELLED, result); assertTrue(cleaned.get());
        assertNotNull(previewPath.get()); assertFalse(Files.exists(previewPath.get()));
        assertEquals(List.of("/list", "/video.ts"), downloaded);
    }
    private void verifyUiPlayback(Path clip) throws Exception {
        Path work = Files.createDirectory(root.resolve("ui"));
        var session = new LocalUiSession(work, (url, directory) -> new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java.exe").toString(), "-cp",
                System.getProperty("streambridge.testClasspath"), LocalUiWorkerFixture.class.getName(),
                directory.toString(), url, clip.toString()).redirectErrorStream(true).start());
        try (var app = new LocalUiSpike(session);
             var playwright = com.microsoft.playwright.Playwright.create(new com.microsoft.playwright.Playwright.CreateOptions()
                     .setEnv(Map.of("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1")));
             var browser = ChromiumLaunch.launch(playwright.chromium(), true);
             var context = browser.newContext()) {
            var page = context.newPage(); page.setDefaultTimeout(15_000);
            page.navigate(app.launchUrl());
            page.locator("#page-url").fill("https://example.org/fixture"); page.locator("#start").click();
            page.locator("#source-C1").check(); page.locator("#variant").selectOption("V1");
            assertTrue(page.locator(".source-card").innerText().contains("00:12"));
            assertTrue(page.locator(".source-card").innerText().contains("640×360"));
            assertTrue(page.locator(".source-card").innerText().contains("F1"));
            page.setViewportSize(390, 844);
            assertEquals(true, page.evaluate("() => document.documentElement.scrollWidth <= innerWidth"));
            page.setViewportSize(1200, 900);
            page.locator("#prepare").click();
            page.waitForFunction("() => !document.querySelector('#preview').disabled");
            assertEquals(false, page.locator("#preview-settings").evaluate("v => v.open"));
            assertEquals("5", page.locator("#preview-seconds").inputValue());
            page.locator("#preview-settings summary").click();
            page.locator("#preview-seconds").fill("9");
            assertTrue(page.locator("#preview").isDisabled());
            page.locator("#preview-default").click(); page.locator("#preview").click();
            page.waitForFunction("() => !document.querySelector('#preview-video').hidden");
            page.locator("#preview-video").evaluate("v => { v.muted=true; return v.play(); }");
            page.waitForFunction("() => document.querySelector('#preview-video').currentTime > 1");
            Number duration = (Number)page.locator("#preview-video").evaluate("v => v.duration");
            assertTrue(duration.doubleValue() > 0 && duration.doubleValue() <= 6);
            assertEquals(960, ((Number)page.locator("#preview-video").evaluate("v => v.videoWidth")).intValue());
            assertEquals(540, ((Number)page.locator("#preview-video").evaluate("v => v.videoHeight")).intValue());
            assertEquals("AWAITING_CONFIRMATION", session.snapshot().get("phase"));
            page.locator("#preview-seconds").fill("7"); page.locator("#preview").click();
            page.waitForFunction("() => document.querySelector('#preview-video').getAttribute('src')?.includes('v=2')");
            page.locator("#preview-video").evaluate("v => { v.muted=true; return v.play(); }");
            page.waitForFunction("() => document.querySelector('#preview-video').currentTime > 1");
            page.locator("#cancel").click();
            page.waitForFunction("() => document.querySelector('#preview-video').hidden");
        }
    }
    private static HlsVodPlaylist playlist(URI uri) {
        return new HlsVodPlaylist(List.of(new HlsVodPlaylist.Segment(uri, BigDecimal.valueOf(8))), BigDecimal.valueOf(8), 3, HlsVodPlaylist.CacheDirective.YES);
    }
    private void assertDecodedPrefixUnchanged(Path ffmpeg, Path source, Path clip, String stream, Path directory) throws Exception {
        var hashes = new ArrayList<List<String>>();
        for (Path file : List.of(source, clip)) {
            Path log = directory.resolve(file.getFileName() + "-" + stream.charAt(0) + "-hash.log");
            HlsDownloadSpike.runChecked(List.of(ffmpeg.toString(), "-v", "error", "-i", file.toString(),
                    "-map", "0:" + stream, "-t", "4", "-f", "framemd5", "-"), log, 20_000);
            hashes.add(Files.readAllLines(log).stream().filter(line -> !line.startsWith("#") && line.contains(","))
                    .map(line -> line.substring(line.lastIndexOf(',') + 1).strip()).toList());
        }
        assertFalse(hashes.get(1).isEmpty());
        assertEquals(hashes.get(0).subList(0, hashes.get(1).size()), hashes.get(1), "Decoded original prefix must be bit-identical: " + stream);
    }
}
