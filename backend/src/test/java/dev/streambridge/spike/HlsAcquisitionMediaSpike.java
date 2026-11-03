package dev.streambridge.spike;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Manual acceptance check of acquisition using OWNED encoded media, never arbitrary URLs. */
public final class HlsAcquisitionMediaSpike {
    private static final String LOGICAL = "http://fixture.example";
    private static final BoundedResourceFetcher.Limits RESOURCE_LIMITS = new BoundedResourceFetcher.Limits(
            2 * 1024 * 1024, 2, Duration.ofSeconds(10), Duration.ofSeconds(2), Duration.ofSeconds(2));
    private static final HlsAcquisition.Limits LIMITS = new HlsAcquisition.Limits(
            16 * 1024 * 1024, 100, Duration.ofSeconds(30), RESOURCE_LIMITS);

    public static void main(String[] args) throws Exception {
        if (args.length != 0) throw new IllegalArgumentException("This diagnostic accepts no external URLs or arguments");
        String tools = System.getenv("STREAMBRIDGE_FFMPEG_DIR");
        if (tools == null) throw new IllegalArgumentException("Set STREAMBRIDGE_FFMPEG_DIR");
        Path ffmpeg = HlsDownloadSpike.executable(Path.of(tools), "ffmpeg.exe");
        Path ffprobe = HlsDownloadSpike.executable(Path.of(tools), "ffprobe.exe");
        Path root = Path.of("build/hls-acquisition-media").toAbsolutePath();
        Files.createDirectories(root);
        Path run = Files.createTempDirectory(root, "run-");
        System.out.println("Evidence directory: " + run);
        try (var fixture = new HlsSelectionFixture(Path.of("build/hls-selection-fixture"));
             var gateway = new Gateway(fixture.origin(), fixture::requireAllowed)) {
            for (String clip : List.of("a", "b")) {
                for (int variant = 1; variant <= 2; variant++) {
                    int audio = clip.equals("a") ? variant : 3 - variant;
                    String masterUri = LOGICAL + "/" + clip + "/master";
                    String master = new String(gateway.get(URI.create(masterUri), RESOURCE_LIMITS,
                            new BoundedResourceFetcher.RequestBudget(3)).bytes(), java.nio.charset.StandardCharsets.UTF_8);
                    var catalog = catalog(masterUri, master);
                    var choice = catalog.choose(catalog.versionedSnapshot().revision(), "C1", "V" + variant, "A" + audio);
                    fixture.clearRequests();
                    var acquired = new HlsAcquisition(gateway::get).acquire(catalog, HlsTransferPlan.build(catalog, choice), LIMITS);
                    // Master acquisition is discovery setup, deliberately outside the transfer budget.
                    List<String> expectedRequests = new ArrayList<>(List.of(choice.media().getPath(), choice.separateAudio().getPath()));
                    for (var input : acquired.inputs()) for (var segment : input.playlist().segments()) expectedRequests.add(segment.uri().getPath());
                    require(fixture.requests().equals(expectedRequests), "Unexpected HTTP requests/order");
                    Path directory = Files.createDirectory(run.resolve(clip + "-v" + variant + "-a" + audio));
                    Files.write(directory.resolve("requests.txt"), fixture.requests());
                    var expected = HlsMaster.parse(URI.create(masterUri), master).select(variant - 1, audio - 1);
                    verify(acquired, ffmpeg, ffprobe, directory, expected);
                    require(fixture.requests().equals(expectedRequests), "Media tools unexpectedly made HTTP requests");
                }
            }
        }
        try (var fixture = new LocalHlsFixture(Path.of("build/hls-av-fixture"));
             var gateway = new Gateway(fixture.origin(), uri -> {
                 String path = uri.getPath();
                 require(uri.toString().equals(fixture.origin() + path) && (path.equals("/media/stream.m3u8")
                         || path.matches("/media/segment-00[0-3]\\.ts")), "Outside muxed fixture");
             })) {
            var catalog = catalog(LOGICAL + "/master", "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\n/media/stream.m3u8\n");
            var choice = catalog.choose(catalog.versionedSnapshot().revision(), "C1", "V1", "unknown");
            var acquired = new HlsAcquisition(gateway::get).acquire(catalog, HlsTransferPlan.build(catalog, choice), LIMITS);
            verify(acquired, ffmpeg, ffprobe, Files.createDirectory(run.resolve("muxed-av")), null);
        }
        Files.writeString(run.resolve("verified.txt"), "PASS: four separate-audio selections and one muxed AV stream; full decode, tracks and synchronization.\n");
        System.out.println("PASS: all 5 real encoded-media acquisition scenarios. Results: " + run);
    }

    private static HlsCandidateCatalog catalog(String uri, String master) {
        var catalog = new HlsCandidateCatalog(new RealDiscoverySpike()::label);
        catalog.observe(uri, LOGICAL + "/page", master);
        return catalog;
    }

    // A test-only origin/port adapter. Default public destination/playlist rules remain unchanged.
    private static final class Gateway implements AutoCloseable {
        final String origin;
        final Consumer<URI> allow;
        final BoundedResourceFetcher fetcher;
        Gateway(String origin, Consumer<URI> allow) {
            this.origin = origin; this.allow = allow;
            fetcher = new BoundedResourceFetcher(uri -> {
                try { allow.accept(uri); }
                catch (IllegalArgumentException | IllegalStateException denied) {
                    throw new BoundedResourceFetcher.FetchException(BoundedResourceFetcher.Failure.BLOCKED_ADDRESS);
                }
                return new InetAddress[]{InetAddress.getByAddress(new byte[]{127,0,0,1})};
            });
        }
        BoundedResourceFetcher.Resource get(URI uri, BoundedResourceFetcher.Limits limits,
                                             BoundedResourceFetcher.RequestBudget budget) throws BoundedResourceFetcher.FetchException {
            if (!uri.toString().equals(LOGICAL + uri.getRawPath())) throw new BoundedResourceFetcher.FetchException(BoundedResourceFetcher.Failure.INVALID_URI);
            var resource = fetcher.fetch(URI.create(origin + uri.getRawPath()), limits, budget);
            return new BoundedResourceFetcher.Resource(URI.create(LOGICAL + resource.finalUri().getRawPath()), resource.bytes());
        }
        public void close() { fetcher.close(); }
    }

    private static void verify(HlsAcquisition.Result acquired, Path ffmpeg, Path ffprobe, Path directory,
                               HlsMaster.Selection expected) throws Exception {
        boolean separate = acquired.inputs().size() == 2;
        List<Path> ownedInputs = new ArrayList<>();
        Path partial = directory.resolve("video.part");
        boolean retainForRunningProcess = false;
        Exception original = null;
        try {
            for (int i = 0; i < acquired.inputs().size(); i++) {
                Path input = directory.resolve("input-" + i + ".ts");
                ownedInputs.add(input);
                // These fixtures use contiguous whole MPEG-TS segments, not fMP4/discontinuities.
                try (var output = Files.newOutputStream(input, StandardOpenOption.CREATE_NEW)) {
                    for (var segment : acquired.inputs().get(i).segments()) output.write(segment.bytes());
                }
                Path log = directory.resolve("input-" + i + "-probe.log");
                HlsDownloadSpike.runChecked(List.of(ffprobe.toString(), "-v", "error", "-protocol_whitelist", "file", "-f", "mpegts",
                        "-show_entries", "stream=codec_name,codec_type", "-of", "default=noprint_wrappers=1", input.toString()), log, 30000);
                // MPEG-TS probe repeats streams inside programs; check type/codec presence here.
                // The existing MP4 verifier checks the final track count and decodes the whole file.
                List<String> tracks = Files.readAllLines(log);
                if (separate) {
                    require(tracks.contains("codec_name=" + (i == 0 ? "h264" : "aac")), "Missing expected input codec");
                    require(!tracks.contains("codec_type=" + (i == 0 ? "audio" : "video")), "Unexpected track in separate input");
                } else require(tracks.containsAll(List.of("codec_name=h264", "codec_name=aac")), "Missing muxed AV input");
            }
            var command = new ArrayList<>(List.of(ffmpeg.toString(), "-hide_banner", "-nostdin", "-n", "-v", "warning", "-xerror"));
            for (Path input : ownedInputs) command.addAll(List.of("-protocol_whitelist", "file", "-f", "mpegts", "-i", input.toString()));
            command.addAll(List.of("-map", "0:v:0", "-map", separate ? "1:a:0" : "0:a:0", "-c", "copy", "-movflags", "+faststart", "-f", "mp4", partial.toString()));
            HlsDownloadSpike.runChecked(command, directory.resolve("remux.log"), 30000);
            if (expected != null) HlsSelectionVerification.verify(ffmpeg, ffprobe, partial, directory, expected);
            else {
                Path probe = directory.resolve("probe.log");
                HlsDownloadSpike.runChecked(List.of(ffprobe.toString(), "-v", "error", "-show_entries",
                        "stream=codec_name,codec_type,width,height,sample_rate,channels:format=duration",
                        "-of", "default=noprint_wrappers=1", partial.toString()), probe, 30000);
                List<String> lines = Files.readAllLines(probe);
                double duration = Double.parseDouble(lines.stream().filter(l -> l.startsWith("duration=")).findFirst().orElseThrow().substring(9));
                require(lines.stream().filter(l -> l.startsWith("codec_type=")).count() == 2
                        && lines.containsAll(List.of("codec_name=h264", "codec_name=aac", "width=320", "height=180", "sample_rate=48000", "channels=1"))
                        && Double.isFinite(duration) && Math.abs(duration - 8) < 0.1, "Wrong muxed output metadata");
                HlsDownloadSpike.runChecked(List.of(ffmpeg.toString(), "-hide_banner", "-nostdin", "-v", "error", "-xerror", "-err_detect", "explode",
                        "-i", partial.toString(), "-map", "0", "-f", "null", "-"), directory.resolve("decode.log"), 30000);
                HlsDownloadSpike.verifySynchronization(ffmpeg, partial, directory, 30000);
            }
            Files.move(partial, directory.resolve("video.mp4"));
            Files.writeString(directory.resolve("acquisition.txt"), "inputs=" + acquired.inputs().size() + "\nsegments="
                    + acquired.inputs().stream().mapToInt(i -> i.segments().size()).sum() + "\npayloadBytes=" + acquired.payloadBytes()
                    + "\nrequestAttempts=" + acquired.requestAttempts() + "\n");
            System.out.println("PASS: " + directory.getFileName() + "; bytes=" + acquired.payloadBytes() + "; HTTP attempts=" + acquired.requestAttempts());
        } catch (Exception failure) {
            original = failure;
            retainForRunningProcess = failure instanceof FfmpegTransfer.ProcessNotStoppedException;
            throw failure;
        } finally {
            // Only exact files created by this unique run; no recursive deletion or source removal.
            if (!retainForRunningProcess) {
                var cleanupTargets = new ArrayList<>(ownedInputs); cleanupTargets.add(partial);
                IOException cleanupError = null;
                for (Path path : cleanupTargets) {
                    try { Files.deleteIfExists(path); }
                    catch (IOException failure) { if (cleanupError == null) cleanupError = failure; else cleanupError.addSuppressed(failure); }
                }
                if (cleanupError != null) { if (original != null) original.addSuppressed(cleanupError); else throw cleanupError; }
            }
        }
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
