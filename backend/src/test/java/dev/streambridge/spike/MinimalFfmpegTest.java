package dev.streambridge.spike;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Acceptance of the actual Windows candidate, separate from the full fixture encoder. */
@org.junit.jupiter.api.Tag("minimal-ffmpeg")
class MinimalFfmpegTest {
    @TempDir Path directory;
    private Path tools, ffmpeg, generator;

    @BeforeEach void tools() throws Exception {
        tools = Path.of(System.getenv("STREAMBRIDGE_FFMPEG_DIR"));
        ffmpeg = HlsDownloadSpike.executable(tools, "ffmpeg.exe");
        generator = HlsDownloadSpike.executable(Path.of(System.getenv("STREAMBRIDGE_FIXTURE_FFMPEG_DIR")), "ffmpeg.exe");
        assertNotEquals(ffmpeg.toRealPath(), generator.toRealPath(), "Must test the candidate, not the fixture generator");
    }

    @Test void candidateHasOnlyLocalProtocolsAndNoExternalEncoders() throws Exception {
        String protocols = output("protocols", List.of(ffmpeg.toString(), "-hide_banner", "-protocols"));
        var names = protocols.lines().map(String::strip).filter(s -> s.matches("[a-z0-9_]+"))
                .collect(java.util.stream.Collectors.toSet());
        assertEquals(java.util.Set.of("file", "pipe"), names);
        String build = output("build", List.of(ffmpeg.toString(), "-hide_banner", "-buildconf"));
        for (String option : List.of("--disable-gpl", "--disable-nonfree", "--disable-autodetect", "--disable-network"))
            assertTrue(build.contains(option), option);
        String encoders = output("encoders", List.of(ffmpeg.toString(), "-hide_banner", "-encoders"));
        assertFalse(encoders.contains("libx264")); assertFalse(encoders.contains("libx265"));
        assertTrue(encoders.contains("wrapped_avframe")); assertTrue(encoders.contains("pcm_s16le"));
    }

    @ParameterizedTest @ValueSource(strings = {"libx264", "libx265"})
    void previewAndFullTransferPreserveH264OrHevcAndAac(String codec) throws Exception {
        Path input = fixture(codec);
        byte[] data = Files.readAllBytes(input);
        var playlist = HlsVodPlaylist.parse(URI.create("https://example.org/list"),
                "#EXTM3U\n#EXT-X-TARGETDURATION:4\n#EXTINF:4,\nvideo.ts\n#EXT-X-ENDLIST\n");
        Path clip = HlsPreview.create(List.of(playlist), directory, (uri, limits, budget) -> {
            budget.claim(); return new BoundedResourceFetcher.Resource(uri, data);
        }, tools, TransferFeedback.NONE, 2000);
        String expected = codec.equals("libx264") ? "h264" : "hevc";
        String metadata = output("probe", List.of(tools.resolve("ffprobe.exe").toString(), "-v", "error",
                "-show_entries", "stream=codec_name,width,height", "-of", "csv=p=0", clip.toString()));
        assertTrue(metadata.contains(expected + ",128,72"), metadata); assertTrue(metadata.contains("aac"), metadata);
        output("decode", List.of(ffmpeg.toString(), "-v", "error", "-xerror", "-i", clip.toString(), "-f", "null", "-"));
        var catalog = new HlsCandidateCatalog(uri -> "fixture");
        catalog.observe("https://example.org/master", "https://example.org/page",
                "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=100000\n/list\n");
        var plan = HlsTransferPlan.build(catalog, catalog.choose(catalog.versionedSnapshot().revision(), "C1", "V1", "unknown"));
        var failure = new AtomicReference<String>();
        var result = RealHlsSampleProbe.run(plan, directory, line -> {}, true, 100000, review -> true,
                (uri, limits, budget) -> {
                    budget.claim();
                    return new BoundedResourceFetcher.Resource(uri, uri.getPath().equals("/list")
                            ? "#EXTM3U\n#EXT-X-TARGETDURATION:4\n#EXTINF:4,\nvideo.ts\n#EXT-X-ENDLIST\n"
                                    .getBytes(java.nio.charset.StandardCharsets.UTF_8) : data);
                }, tools, new TransferFeedback() { public void failure(String code) { failure.set(code); } });
        assertEquals(RealHlsSampleProbe.Result.SUCCESS, result, failure.get());
    }

    @Test void realRemuxProducesProgressAndFinalFile() throws Exception {
        Path input = fixture("libx264"), result = directory.resolve("result.mp4");
        var seen = new AtomicReference<FfmpegTransfer.Progress>();
        var outcome = FfmpegTransfer.run(List.of(ffmpeg.toString(), "-v", "error", "-i", input.toString(),
                        "-c", "copy", "-movflags", "+faststart", "-progress", "pipe:1", result.toString()),
                directory.resolve("stderr"), directory.resolve("progress"), Duration.ofSeconds(15), Duration.ofSeconds(2),
                () -> false, seen::set);
        assertFalse(outcome.cancelled()); assertNotNull(seen.get()); assertTrue(seen.get().end());
        assertTrue(seen.get().mediaTimeUs() > 0); assertTrue(Files.size(result) > 0);
        assertFalse(ProcessHandle.of(outcome.pid()).map(ProcessHandle::isAlive).orElse(false));
    }

    @Test void realRemuxStopsOnCancellationBeforePartialIsRemoved() throws Exception {
        var cancel = new AtomicBoolean();
        var outcome = FfmpegTransfer.run(slowRemux(fixture("libx264")), directory.resolve("stderr"), directory.resolve("progress"),
                Duration.ofSeconds(15), Duration.ofSeconds(2), cancel::get, p -> cancel.set(true));
        assertTrue(cancel.get()); assertTrue(outcome.cancelled());
        assertFalse(ProcessHandle.of(outcome.pid()).map(ProcessHandle::isAlive).orElse(false));
        Files.delete(directory.resolve("transfer.part"));
    }

    @Test void realRemuxStopsOnTimeoutBeforePartialIsRemoved() throws Exception {
        Path input = fixture("libx264");
        IOException failure = assertThrows(IOException.class, () -> FfmpegTransfer.run(slowRemux(input),
                directory.resolve("stderr"), directory.resolve("progress"), Duration.ofMillis(1500), Duration.ofMillis(100),
                () -> false, p -> {}));
        assertTrue(failure.getMessage().contains("exceeded"), failure.getMessage());
        try (var processes = ProcessHandle.allProcesses()) {
            assertTrue(processes.noneMatch(p -> p.info().arguments()
                    .map(args -> List.of(args).contains(input.toString())).orElse(false)), "Decoder still owns this test input");
        }
        Files.delete(input);
        Files.deleteIfExists(directory.resolve("transfer.part"));
    }

    private List<String> slowRemux(Path input) {
        return List.of(ffmpeg.toString(), "-v", "error", "-re", "-stream_loop", "-1", "-i", input.toString(),
                "-c", "copy", "-progress", "pipe:1", "-f", "mp4", directory.resolve("transfer.part").toString());
    }

    private Path fixture(String encoder) throws Exception {
        Path input = directory.resolve("fixture.ts");
        var args = new ArrayList<>(List.of(generator.toString(), "-nostdin", "-v", "error", "-f", "lavfi", "-i",
                "testsrc2=size=128x72:rate=10", "-f", "lavfi", "-i", "sine=frequency=440", "-t", "4",
                "-c:v", encoder, "-threads", "1", "-preset", "ultrafast"));
        if (encoder.equals("libx265")) args.addAll(List.of("-x265-params", "pools=1:frame-threads=1:log-level=error"));
        args.addAll(List.of("-c:a", "aac", "-f", "mpegts", input.toString()));
        HlsDownloadSpike.runChecked(args, directory.resolve("generate.log"), 30_000);
        return input;
    }

    private String output(String name, List<String> command) throws Exception {
        Path log = directory.resolve(name + ".log");
        HlsDownloadSpike.runChecked(command, log, 15_000);
        return Files.readString(log);
    }
}
