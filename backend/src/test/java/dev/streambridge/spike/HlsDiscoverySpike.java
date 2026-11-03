package dev.streambridge.spike;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.TimeoutError;

/** Bounded local experiment, not a production media detector or a JUnit test. */
public final class HlsDiscoverySpike {
    public static void main(String[] args) throws IOException {
        boolean autoPlay = false;
        int timeoutMs = 60_000;
        for (String arg : args) {
            if (arg.equals("--auto-play")) {
                autoPlay = true;
            } else if (arg.startsWith("--timeout-ms=")) {
                timeoutMs = Integer.parseInt(arg.substring("--timeout-ms=".length()));
                if (timeoutMs < 1 || timeoutMs > 120_000) {
                    throw new IllegalArgumentException("Timeout must be between 1 and 120000 ms");
                }
            } else {
                throw new IllegalArgumentException("Unknown argument: " + arg);
            }
        }
        Path fixtureDirectory = Path.of(System.getProperty("streambridge.hlsFixture", "build/hls-fixture"));
        var options = new Playwright.CreateOptions().setEnv(Map.of("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1"));

        // Resources close in reverse order, including on timeout or failed assertions.
        try (var fixture = new LocalHlsFixture(fixtureDirectory);
             Playwright playwright = Playwright.create(options);
             Browser browser = ChromiumLaunch.launch(playwright.chromium(), false);
             BrowserContext context = browser.newContext()) {
            System.out.println("Fixture: " + fixture.origin());
            System.out.println("Chromium version: " + browser.version());

            discover(fixture, context, autoPlay, timeoutMs);
        }
        System.out.println("Browser context, browser, Playwright and local fixture server closed.");
    }

    /** Shared by the two local experiments, not a general-purpose discovery API. */
    static URI discover(LocalHlsFixture fixture, BrowserContext context, boolean autoPlay, int timeoutMs) {
        // Callbacks are dispatched on this Playwright thread; no concurrent collection is needed.
        Set<String> playlists = new LinkedHashSet<>();
        Set<String> segments = new LinkedHashSet<>();
        Set<String> completedSegments = new LinkedHashSet<>();
        List<String> errors = new ArrayList<>();

        // Observe actual requests before navigation; do not inject the expected URL into these sets.
        context.onRequest(request -> {
            String path = URI.create(request.url()).getPath();
            if (path == null) return;
            if (path.endsWith(".m3u8")) {
                playlists.add(request.url());
            } else if (path.endsWith(".ts")) {
                segments.add(request.url());
            } else {
                return;
            }
            System.out.printf("REQUEST %s [%s] %s%n", request.method(), request.resourceType(), request.url());
        });
        context.onRequestFinished(request -> {
            String path = URI.create(request.url()).getPath();
            if (path != null && path.endsWith(".ts") && request.response().ok()) {
                completedSegments.add(request.url());
            }
        });
        context.onResponse(response -> {
            if (!response.ok()) errors.add("HTTP " + response.status() + " " + response.url());
        });
        context.onRequestFailed(request -> errors.add("Request failed: " + request.url() + " " + request.failure()));

        Page page = context.newPage();
        page.onPageError(errors::add);
        page.onConsoleMessage(message -> {
            if (message.type().equals("error")) errors.add(message.text());
        });
        page.navigate(fixture.origin() + "/");
        // Deliberate negative observation window, not a sleep used to guess playback readiness.
        // Unlike Thread.sleep/readLine, this continues dispatching Playwright events.
        page.waitForTimeout(750);
        require(errors.isEmpty(), "Page setup failed: " + errors);
        require(playlists.isEmpty() && segments.isEmpty(), "Media was requested before Play");
        System.out.println("PASS: no HLS requests during the pre-click observation window.");
        // A fast manual click must not race the pre-click assertion.
        page.locator("#play").evaluate("button => button.disabled = false");
        page.locator("#status").evaluate("element => element.textContent = 'Ready. Click Play test video.'");

        if (autoPlay) {
            page.locator("#play").click();
        } else {
            System.out.println("Click 'Play test video' in Chromium within " + timeoutMs + " ms.");
        }

        // Expected URL is used only as the test oracle, not as the source of discovered data.
        String expectedPlaylist = fixture.origin() + "/media/stream.m3u8";
        try {
            context.waitForCondition(
                    () -> !errors.isEmpty() || (playlists.contains(expectedPlaylist) && !completedSegments.isEmpty()),
                    new BrowserContext.WaitForConditionOptions().setTimeout(timeoutMs));
        } catch (TimeoutError e) {
            throw new IllegalStateException("Timed out waiting for the playlist and a completed segment. "
                    + "Was Play clicked? Observed playlists=" + playlists + ", segments=" + segments, e);
        }
        require(errors.isEmpty(), "Network/player failure: " + errors);

        // Requests alone are insufficient: confirm the synthetic media actually plays.
        page.waitForFunction("() => document.querySelector('#video').currentTime >= 1 || window.spikeError !== ''",
                null, new Page.WaitForFunctionOptions().setTimeout(10_000));
        require(errors.isEmpty(), "Playback failure: " + errors);
        require(Boolean.TRUE.equals(page.evaluate("() => document.querySelector('#video').currentTime >= 1")),
                "Playback did not advance: " + page.evaluate("() => window.spikeError"));
        System.out.println("PASS: observed playlist " + expectedPlaylist);
        System.out.println("PASS: completed segment downloads=" + completedSegments.size() + "; playback advanced >= 1 second.");
        return URI.create(playlists.stream().filter(expectedPlaylist::equals).findFirst().orElseThrow());
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
