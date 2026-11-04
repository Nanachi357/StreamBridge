package dev.streambridge.spike;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RealHlsSampleProbeTest {
    @TempDir Path directory;

    @Test void silentVideoCanBePreviewedAndFullyDownloaded() throws Exception { encodedTransfer("silent"); }
    @Test void fullTransferKeepsEmbeddedAudio() throws Exception { encodedTransfer("muxed"); }
    @Test void fullTransferKeepsSelectedSeparateAudio() throws Exception { encodedTransfer("separate"); }
    @Test void fullTransferRejectsMissingSelectedSeparateAudio() throws Exception { encodedTransfer("missing-audio"); }
    @Test void fullTransferKeepsSelectedInBandAudio() throws Exception { encodedTransfer("in-band"); }
    @Test void fullTransferRejectsMissingSelectedInBandAudio() throws Exception { encodedTransfer("missing-in-band"); }

    private void encodedTransfer(String mode) throws Exception {
        String configured = System.getenv("STREAMBRIDGE_FFMPEG_DIR");
        org.junit.jupiter.api.Assumptions.assumeTrue(configured != null, "Encoded-media check requires FFmpeg/FFprobe");
        Path tools = Path.of(configured);
        Path generator = HlsDownloadSpike.executable(Path.of(System.getenv()
                .getOrDefault("STREAMBRIDGE_FIXTURE_FFMPEG_DIR", configured)), "ffmpeg.exe");
        boolean separate = mode.equals("separate") || mode.equals("missing-audio");
        boolean inBand = mode.equals("in-band") || mode.equals("missing-in-band");
        Path video = directory.resolve("fixture.ts"), audio = directory.resolve("audio.ts");
        var command = new ArrayList<>(List.of(generator.toString(), "-nostdin", "-v", "error", "-f", "lavfi", "-i", "testsrc2=size=96x64:rate=10"));
        if (mode.equals("muxed") || mode.equals("in-band")) command.addAll(List.of("-f", "lavfi", "-i", "sine=frequency=440"));
        command.addAll(List.of("-t", "2", "-c:v", "libx264", "-threads", "1", "-bf", "0", "-g", "10", "-c:a", "aac", "-f", "mpegts", video.toString()));
        HlsDownloadSpike.runChecked(command, directory.resolve("generate.log"), 10_000);
        if (mode.equals("separate")) HlsDownloadSpike.runChecked(List.of(generator.toString(), "-nostdin", "-v", "error", "-f", "lavfi", "-i", "sine=frequency=880",
                "-t", "2", "-c:a", "aac", "-f", "mpegts", audio.toString()), directory.resolve("generate-audio.log"), 10_000);
        byte[] videoBytes = Files.readAllBytes(video);
        // A selected audio playlist returning valid TS with video only must not silently lose audio.
        byte[] audioBytes = mode.equals("separate") ? Files.readAllBytes(audio) : videoBytes;
        var catalog = new HlsCandidateCatalog(uri -> "fixture");
        catalog.observe("https://example.org/master", "https://example.org/page", "#EXTM3U\n"
                + (separate || inBand ? "#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"sound\",NAME=\"Selected\"" + (separate ? ",URI=\"/audio\"" : "") + "\n" : "")
                + "#EXT-X-STREAM-INF:BANDWIDTH=100000,RESOLUTION=96x64" + (separate || inBand ? ",AUDIO=\"sound\"" : "") + "\n/media\n");
        var plan = HlsTransferPlan.build(catalog, catalog.choose(catalog.versionedSnapshot().revision(), "C1", "V1", separate || inBand ? "A1" : "unknown"));
        var output = new java.util.concurrent.atomic.AtomicReference<Path>();
        var previewed = new java.util.concurrent.atomic.AtomicBoolean();
        var failure = new java.util.concurrent.atomic.AtomicReference<String>("");
        var feedback = new TransferFeedback() {
            public boolean alwaysReview() { return true; }
            public boolean approve(DownloadReview review, java.util.function.Predicate<DownloadReview> fallback, Preview preview) {
                if (mode.equals("silent")) try {
                    assertTrue(Files.size(preview.create(1000)) > 0); previewed.set(true);
                } catch (Exception e) { throw new AssertionError("Silent preview failed", e); }
                return true;
            }
            public void file(Path path) { output.set(path); }
            public void failure(String code) { failure.set(code); }
        };
        var result = RealHlsSampleProbe.run(plan, directory, line -> {}, true, 100000, review -> true,
                (uri, limits, budget) -> {
                    budget.claim();
                    byte[] bytes = switch (uri.getPath()) {
                        case "/media", "/audio" -> ("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:2\n#EXTINF:2,\n"
                                + (uri.getPath().equals("/audio") ? "/audio.ts" : "/video.ts") + "\n#EXT-X-ENDLIST\n").getBytes(StandardCharsets.UTF_8);
                        case "/video.ts" -> videoBytes;
                        case "/audio.ts" -> audioBytes;
                        default -> throw new AssertionError("Unexpected fixture request");
                    };
                    return new BoundedResourceFetcher.Resource(uri, bytes);
                }, tools, feedback);
        if (mode.startsWith("missing-")) {
            assertEquals(RealHlsSampleProbe.Result.FAILED, result);
            assertEquals("REMUX_IOException", failure.get()); assertNull(output.get());
        } else {
            assertEquals(RealHlsSampleProbe.Result.SUCCESS, result, failure.get());
            assertNotNull(output.get()); assertTrue(Files.size(output.get()) > 0);
            assertEquals(mode.equals("silent"), previewed.get());
            Path probe = directory.resolve("assert-streams.log");
            HlsDownloadSpike.runChecked(List.of(tools.resolve("ffprobe.exe").toString(), "-v", "error", "-show_entries", "stream=codec_type",
                    "-of", "default=noprint_wrappers=1:nokey=1", output.get().toString()), probe, 10_000);
            assertEquals(mode.equals("silent") ? List.of("video") : List.of("video", "audio"), Files.readAllLines(probe));
        }
        try (var files = Files.walk(directory)) {
            assertTrue(files.noneMatch(p -> p.getFileName().toString().matches("input-.*\\.ts|video\\.part|preview.*\\.(part|mp4|ts)")));
        }
    }

    @Test void declinedLargeDownloadFetchesOnlyPlaylistAndCreatesNoMedia() throws Exception {
        var paths = new ArrayList<String>();
        var result = runLarge(false, paths);
        assertEquals(RealHlsSampleProbe.Result.CANCELLED, result);
        assertEquals(List.of("/media"), paths);
        try (var files = Files.walk(directory)) {
            assertTrue(files.noneMatch(p -> p.toString().endsWith(".ts") || p.toString().endsWith(".part") || p.toString().endsWith(".mp4")));
        }
    }

    @Test void approvedLargeDownloadCanExceedOldRequestCapAndCleansUpOnLaterFailure() throws Exception {
        var paths = new ArrayList<String>();
        var cleanup = new ArrayList<String>();
        var feedback = new TransferFeedback() {
            public void cleanupComplete() { cleanup.add("complete"); }
            public void cleanupUncertain() { cleanup.add("uncertain"); }
        };
        // Deliberately fail the final segment, before any media tool is launched.
        assertEquals(RealHlsSampleProbe.Result.FAILED, runLarge(true, paths, feedback));
        assertEquals(List.of("complete"),cleanup);
        assertEquals(1202, paths.size());
        assertEquals("/segment-1200", paths.getLast());
        try (var files = Files.walk(directory)) {
            assertTrue(files.noneMatch(p -> p.toString().endsWith(".ts") || p.toString().endsWith(".part") || p.toString().endsWith(".mp4")));
        }
    }
    @Test void playlistFailureExportsSafeDetailAndAcknowledgesNoPartials() throws Exception {
        Path tools=Files.createDirectory(directory.resolve("tools"));
        Files.createFile(tools.resolve("ffmpeg.exe"));Files.createFile(tools.resolve("ffprobe.exe"));
        var catalog=new HlsCandidateCatalog(uri->"redacted");
        catalog.observe("https://example.org/master","https://example.org/page",
                "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\n/media?token=hidden\n");
        var plan=HlsTransferPlan.build(catalog,catalog.choose(catalog.versionedSnapshot().revision(),"C1","V1","unknown"));
        var events=new ArrayList<String>();var logs=new ArrayList<String>();
        var feedback=new TransferFeedback() {
            public void failure(String code) {events.add(code);}
            public void cleanupComplete() {events.add("clean");}
            public void cleanupUncertain() {events.add("uncertain");}
        };
        assertEquals(RealHlsSampleProbe.Result.FAILED,RealHlsSampleProbe.run(plan,directory,logs::add,true,1,
                review->false,(uri,limits,budget)->{throw BoundedResourceFetcher.sanitizeFailure(
                        new javax.net.ssl.SSLHandshakeException(uri.toString()));},tools,feedback));
        assertEquals(List.of("PLAYLIST_SELECTED_VARIANT_IO_TLS_HANDSHAKE","clean"),events);
        assertFalse(String.join("\n",logs).contains("token=hidden"));
        try(var files=Files.walk(directory)) {assertTrue(files.noneMatch(p->p.toString().endsWith(".part")||p.toString().endsWith(".ts")));}
    }

    @Test void uiReviewsEvenSmallKnownDownloads() {
        var review = new DownloadReview(2, new java.math.BigDecimal("8"), new java.math.BigDecimal("1024"), 0);
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        assertFalse(RealHlsSampleProbe.approve(review, value -> { calls.incrementAndGet(); return false; }, line -> {}, true));
        assertEquals(1, calls.get());
        assertTrue(RealHlsSampleProbe.approve(review, value -> { fail("Console small transfer should not prompt"); return false; }, line -> {}));
    }

    @Test void cancellationBetweenSegmentsCleansOnlyOwnPartials() throws Exception {
        var paths = new ArrayList<String>();
        Path unrelated = directory.resolve("keep.txt"); Files.writeString(unrelated, "user data");
        try {
            var feedback = new TransferFeedback() { public boolean cancelled() { return paths.size() >= 3; } };
            assertEquals(RealHlsSampleProbe.Result.CANCELLED, runLarge(true, paths, feedback));
            assertEquals(3, paths.size());
            assertEquals("user data", Files.readString(unrelated));
            try (var files = Files.walk(directory)) { assertTrue(files.noneMatch(p -> p.toString().endsWith(".ts") || p.toString().endsWith(".part"))); }
        } finally { Thread.interrupted(); }
    }

    private RealHlsSampleProbe.Result runLarge(boolean approve, List<String> paths) throws Exception {
        return runLarge(approve, paths, TransferFeedback.NONE);
    }
    private RealHlsSampleProbe.Result runLarge(boolean approve, List<String> paths, TransferFeedback feedback) throws Exception {
        Path tools = Files.createDirectory(directory.resolve("tools"));
        Files.createFile(tools.resolve("ffmpeg.exe")); Files.createFile(tools.resolve("ffprobe.exe"));
        var catalog = new HlsCandidateCatalog(uri -> "redacted");
        catalog.observe("https://example.org/master", "https://example.org/page",
                "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\n/media\n");
        var plan = HlsTransferPlan.build(catalog, catalog.choose(catalog.versionedSnapshot().revision(), "C1", "V1", "unknown"));
        var body = new StringBuilder("#EXTM3U\n#EXT-X-TARGETDURATION:1\n");
        for (int i = 0; i < 1201; i++) body.append("#EXTINF:1,\n/segment-").append(i).append('\n');
        body.append("#EXT-X-ENDLIST\n");
        return RealHlsSampleProbe.run(plan, directory, line -> {}, true, 1, review -> approve,
                (uri, limits, budget) -> {
                    paths.add(uri.getPath()); budget.claim();
                    if (uri.getPath().equals("/media")) return new BoundedResourceFetcher.Resource(uri, body.toString().getBytes(StandardCharsets.UTF_8));
                    if (uri.getPath().equals("/segment-1200")) throw new BoundedResourceFetcher.FetchException(BoundedResourceFetcher.Failure.IO);
                    byte[] packet = new byte[188]; packet[0] = 0x47;
                    return new BoundedResourceFetcher.Resource(uri, packet);
                }, tools, feedback);
    }

    @Test void wholeTransferRejectsTruncatedOrNonFiniteDuration() {
        RealHlsSampleProbe.verifyDuration(java.util.List.of("duration=1443.95"), 1443.94);
        for (String duration : new String[]{"10.05", "NaN", "Infinity", "-1"}) {
            assertThrows(IllegalStateException.class, () -> RealHlsSampleProbe.verifyDuration(java.util.List.of("duration=" + duration), 1443.94));
        }
        assertThrows(IllegalStateException.class, () -> RealHlsSampleProbe.verifyDuration(java.util.List.of("duration=10"), Double.NaN));
    }
    @Test void manifestDescriptionDoesNotExposeUrlsKeysOrUnknownTagText() {
        String summary = RealHlsSampleProbe.describe("#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"https://secret.example/key\"\n"
                + "#EXT-SECRET-TOKEN:private\n#EXTINF:2,private title\nhttps://secret.example/segment?token=hidden\n#EXT-X-ENDLIST\n");
        assertTrue(summary.contains("#EXT-X-KEY")); assertTrue(summary.contains("OTHER_TAG")); assertTrue(summary.contains("references=1"));
        assertFalse(summary.contains("secret")); assertFalse(summary.contains("private")); assertFalse(summary.contains("hidden"));
        assertFalse(summary.contains("SECRET"));
    }
}
