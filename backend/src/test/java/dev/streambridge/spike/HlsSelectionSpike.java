package dev.streambridge.spike;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Frame;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Bounded experiment: response -> master -> explicit selection -> checked local MP4. */
public final class HlsSelectionSpike {
    record Candidate(HlsMaster master, String frameUrl) { }

    public static void main(String[] args) throws Exception {
        boolean prepare = false, autoPlay = false, verifyAll = false;
        String scenario = "single";
        Integer candidateIndex = null, variantIndex = null, audioIndex = null;
        for (String arg : args) {
            if (arg.equals("--prepare")) prepare = true;
            else if (arg.equals("--auto-play")) autoPlay = true;
            else if (arg.equals("--verify-all")) verifyAll = true;
            else if (arg.startsWith("--scenario=")) scenario = arg.substring("--scenario=".length());
            else if (arg.startsWith("--candidate=")) candidateIndex = Integer.valueOf(arg.substring("--candidate=".length()));
            else if (arg.startsWith("--variant=")) variantIndex = Integer.valueOf(arg.substring("--variant=".length()));
            else if (arg.startsWith("--audio=")) audioIndex = Integer.valueOf(arg.substring("--audio=".length()));
            else throw new IllegalArgumentException("Unknown argument: " + arg);
        }
        if (!List.of("single", "multiple", "iframe").contains(scenario)) throw new IllegalArgumentException("Unknown scenario");
        String toolDirectory = System.getenv("STREAMBRIDGE_FFMPEG_DIR");
        if (toolDirectory == null) throw new IllegalArgumentException("Set STREAMBRIDGE_FFMPEG_DIR");
        Path ffmpeg = HlsDownloadSpike.executable(Path.of(toolDirectory), "ffmpeg.exe");
        Path ffprobe = HlsDownloadSpike.executable(Path.of(toolDirectory), "ffprobe.exe");
        Path fixtureDirectory = Path.of(System.getProperty("streambridge.selectionFixture", "build/hls-selection-fixture"));
        if (prepare) {
            HlsSelectionFixture.prepare(fixtureDirectory,
                    Path.of(System.getProperty("streambridge.hlsFixture", "build/hls-fixture")),
                    HlsDownloadSpike.executable(Path.of(System.getenv()
                            .getOrDefault("STREAMBRIDGE_FIXTURE_FFMPEG_DIR", toolDirectory)), "ffmpeg.exe"));
            return;
        }
        if (verifyAll && (candidateIndex != null || variantIndex != null || audioIndex != null)) {
            throw new IllegalArgumentException("Do not mix --verify-all with individual selections");
        }
        Path root = Path.of(System.getProperty("streambridge.selectionOutputs", "build/hls-selections")).toAbsolutePath();
        Files.createDirectories(root);
        try (var fixture = new HlsSelectionFixture(fixtureDirectory);
             var playwright = Playwright.create(new Playwright.CreateOptions().setEnv(Map.of("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1")));
             var browser = ChromiumLaunch.launch(playwright.chromium(), false)) {
            List<Candidate> candidates = discover(fixture, browser, scenario, autoPlay);
            for (int i = 0; i < candidates.size(); i++) {
                Candidate candidate = candidates.get(i);
                System.out.println("Candidate " + (i + 1) + ": " + candidate.master().uri() + " [frame=" + candidate.frameUrl() + "]");
                printChoices(candidate.master());
            }
            var selections = new ArrayList<HlsMaster.Selection>();
            if (verifyAll) {
                for (Candidate candidate : candidates) {
                    HlsMaster master = candidate.master();
                    for (int v = 0; v < master.variants().size(); v++) {
                        for (int a = 0; a < master.audioFor(master.variants().get(v)).size(); a++) selections.add(master.select(v, a));
                    }
                }
            } else {
                // Source browser context is already CLOSED, so console waiting cannot block its dispatch.
                var input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
                int c = choose(input, "Candidate", candidateIndex, candidates.size());
                HlsMaster master = candidates.get(c).master();
                printChoices(master);
                int v = choose(input, "Video variant", variantIndex, master.variants().size());
                int a = choose(input, "Audio within selected variant", audioIndex, master.audioFor(master.variants().get(v)).size());
                selections.add(master.select(v, a));
            }
            Path last = null;
            for (HlsMaster.Selection selection : selections) {
                last = downloadAndVerify(fixture, ffmpeg, ffprobe, root, selection);
            }
            // Full decoding/content verification ran for EVERY output; browser preview checks the last output.
            try (BrowserContext preview = browser.newContext()) {
                Page page = preview.newPage();
                page.navigate(last.toUri().toString());
                page.locator("video").evaluate("v => { v.muted = true; return v.play(); }");
                page.waitForFunction("() => document.querySelector('video').currentTime >= 1", null,
                        new Page.WaitForFunctionOptions().setTimeout(10_000));
            }
            System.out.println("PASS: " + scenario + ", verified outputs=" + selections.size() + "; final MP4 preview plays (muted).");
        }
        System.out.println("Closed source/preview contexts, browser, Playwright, both fixture servers and external processes.");
    }

    private static List<Candidate> discover(HlsSelectionFixture fixture, Browser browser, String scenario, boolean autoPlay) {
        var masters = new LinkedHashMap<URI, Candidate>();
        var errors = new ArrayList<String>();
        try (BrowserContext context = browser.newContext()) {
            // This loopback exception belongs ONLY to the closed fixture, not to production URL policy.
            context.route("**/*", route -> {
                try { fixture.requireAllowed(URI.create(route.request().url())); route.resume(); }
                catch (RuntimeException failure) { errors.add(failure.getMessage()); route.abort(); }
            });
            context.onRequestFinished(request -> {
                try {
                    var response = request.response();
                    if (response == null) return;
                    if (!response.ok()) { errors.add("HTTP " + response.status() + " " + response.url()); return; }
                    String type = response.headerValue("content-type");
                    if (type == null || !type.toLowerCase(Locale.ROOT).contains("mpegurl")) return;
                    URI uri = URI.create(response.url());
                    fixture.requireAllowed(uri);
                    // All served assets are owned and bounded; not a safe arbitrary-site body reader yet.
                    String body = response.text();
                    if (!HlsMaster.isMaster(body)) return;
                    HlsMaster master = HlsMaster.parse(uri, body);
                    master.variants().forEach(v -> fixture.requireAllowed(v.uri()));
                    master.audio().forEach(a -> fixture.requireAllowed(a.uri()));
                    masters.putIfAbsent(uri, new Candidate(master, response.frame().url()));
                    System.out.println("Observed master by Content-Type/body (no extension required): " + uri);
                } catch (RuntimeException failure) { errors.add(failure.toString()); }
            });
            context.onRequestFailed(request -> errors.add(request.failure() + " " + request.url()));
            Page page = context.newPage();
            page.onPageError(errors::add);
            page.onConsoleMessage(message -> { if (message.type().equals("error")) errors.add(message.text()); });
            System.out.println("Fixture: " + fixture.origin() + "/" + scenario + "; child origin: " + fixture.childOrigin());
            page.navigate(fixture.origin() + "/" + scenario);
            if (!scenario.equals("single")) {
                await(context, errors, () -> masters.size() == 1);
                require(masters.values().iterator().next().master().uri().getPath().equals("/b/master"), "Other clip must be first");
                page.waitForFunction("() => document.querySelector('#video-b').currentTime >= 0.5");
                System.out.println("PASS: unrelated clip B was discovered/playing before target A.");
            } else {
                page.waitForTimeout(500);
                require(masters.isEmpty(), "Media requested before target playback");
            }
            Frame target = scenario.equals("iframe") ? page.frame("target-frame") : page.mainFrame();
            require(target != null, "Missing target frame");
            target.locator("#start-a").evaluate("button => button.disabled = false");
            if (autoPlay) target.locator("#start-a").click();
            else System.out.println("Click Play clip A in the browser within 60 seconds.");
            int count = scenario.equals("single") ? 1 : 2;
            await(context, errors, () -> masters.size() == count);
            target.waitForFunction("() => document.querySelector('#video-a').currentTime >= 1", null,
                    new Frame.WaitForFunctionOptions().setTimeout(10_000));
            require(errors.isEmpty(), "Browser errors: " + errors);
            for (Candidate candidate : masters.values()) {
                require(candidate.master().variants().size() == 2 && candidate.master().audio().size() == 2, "Wrong inventory");
            }
            if (scenario.equals("iframe")) {
                Candidate a = masters.values().stream().filter(c -> c.master().uri().getPath().equals("/a/master")).findFirst().orElseThrow();
                require(a.frameUrl().equals(fixture.childOrigin() + "/frame"), "Lost cross-origin iframe provenance");
            }
            require(fixture.requests().stream().noneMatch(path -> path.contains("/v360/") || path.contains("/en/")),
                    "Browser unexpectedly fetched alternate tracks; cannot prove inventory of unplayed variants");
            System.out.println("PASS: " + count + " candidates; HIGH/EN discovered from metadata without being played/requested.");
        }
        return List.copyOf(masters.values());
    }

    private static void await(BrowserContext context, List<String> errors, java.util.function.BooleanSupplier condition) {
        context.waitForCondition(() -> !errors.isEmpty() || condition.getAsBoolean(),
                new BrowserContext.WaitForConditionOptions().setTimeout(60_000));
        require(errors.isEmpty(), "Discovery errors: " + errors);
    }

    private static void printChoices(HlsMaster master) {
        for (int i = 0; i < master.variants().size(); i++) {
            var variant = master.variants().get(i);
            System.out.println("  Video " + (i + 1) + ": " + variant.width() + "x" + variant.height());
            List<HlsMaster.Audio> audio = master.audioFor(variant);
            for (int a = 0; a < audio.size(); a++) System.out.println("    Audio " + (a + 1) + ": " + audio.get(a).name() + " [" + audio.get(a).language() + "]");
        }
    }

    private static int choose(BufferedReader input, String label, Integer supplied, int count) throws IOException {
        if (supplied == null) {
            System.out.println(label + " (1.." + count + "):");
            String line = input.readLine();
            if (line == null) throw new IOException("Console ended before explicit selection");
            supplied = Integer.valueOf(line.strip());
        }
        if (supplied < 1 || supplied > count) throw new IllegalArgumentException(label + " is out of range");
        return supplied - 1;
    }

    private static Path downloadAndVerify(HlsSelectionFixture fixture, Path ffmpeg, Path ffprobe, Path root,
                                          HlsMaster.Selection selection) throws Exception {
        fixture.requireAllowed(selection.master());
        fixture.requireAllowed(selection.video().uri());
        fixture.requireAllowed(selection.audio().uri());
        Path run = Files.createTempDirectory(root, "run-");
        Path partial = run.resolve("video.part");
        Path result = run.resolve("video.mp4");
        Files.writeString(run.resolve("selection.txt"), selection.toString());
        System.out.println("Selected: " + selection + "\nRun: " + run);
        fixture.clearRequests(); // Source context closed: no browser traffic can contaminate this evidence.
        try {
            var transfer = FfmpegTransfer.run(List.of(ffmpeg.toString(), "-hide_banner", "-nostdin", "-n", "-nostats",
                    "-progress", "pipe:1", "-rw_timeout", "10000000", "-i", selection.video().uri().toString(),
                    "-rw_timeout", "10000000", "-i", selection.audio().uri().toString(),
                    "-map", "0:v:0", "-map", "1:a:0", "-c", "copy", "-movflags", "+faststart", "-f", "mp4", partial.toString()),
                    run.resolve("download.log"), run.resolve("progress.log"), Duration.ofSeconds(30), Duration.ofSeconds(3),
                    () -> false, progress -> System.out.printf(Locale.ROOT, "Processed %.2fs%n", progress.mediaTimeUs() / 1_000_000.0));
            require(!transfer.cancelled(), "Unexpected cancellation");
            List<String> requests = fixture.requests();
            Files.write(run.resolve("requests.txt"), requests);
            String videoPrefix = selection.video().uri().resolve(".").getPath();
            String audioPrefix = selection.audio().uri().resolve(".").getPath();
            require(requests.contains(selection.video().uri().getPath()) && requests.contains(selection.audio().uri().getPath())
                    && requests.stream().allMatch(p -> p.startsWith(videoPrefix) || p.startsWith(audioPrefix)),
                    "FFmpeg fetched resources outside the selected pair");
            HlsSelectionVerification.verify(ffmpeg, ffprobe, partial, run, selection);
            Files.move(partial, result);
            System.out.println("PASS: selected content verified and published: " + result);
            return result;
        } catch (Exception failure) {
            if (!(failure instanceof FfmpegTransfer.ProcessNotStoppedException)) {
                try { Files.deleteIfExists(partial); }
                catch (IOException cleanup) { failure.addSuppressed(cleanup); }
            }
            System.err.println("Failed; diagnostic logs retained: " + run);
            throw failure;
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
