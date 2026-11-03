package dev.streambridge.spike;

import com.microsoft.playwright.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Owned local UI and synthetic manifests only; no visible browser or third-party media. */
class SourceCardsTest {
    @TempDir Path root;

    @Test void actualCatalogRendersSafelyAndKeepsAudioSelectionWithinItsSource() throws Exception {
        assumeTrue("1".equals(System.getenv("STREAMBRIDGE_UI_BROWSER_CHECK")));
        var catalog = new HlsCandidateCatalog(new RealDiscoverySpike()::label);
        String master = """
                #EXTM3U
                #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="uk",NAME="Ukrainian <img src=x onerror=alert(1)>",LANGUAGE="uk",URI="uk.m3u8"
                #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="en",NAME="English",LANGUAGE="en",URI="en.m3u8"
                #EXT-X-STREAM-INF:BANDWIDTH=2000000,RESOLUTION=1920x1080,AUDIO="uk"
                high.m3u8
                #EXT-X-STREAM-INF:BANDWIDTH=1000000,RESOLUTION=1280x720,AUDIO="en"
                low.m3u8
                """;
        catalog.observe("https://example.org/master?private-token", "https://player.example/watch?secret", "F1", master);
        catalog.observe("https://example.org/high.m3u8", "https://player.example/watch?secret", "F1",
                "#EXTM3U\n#EXTINF:1444.026192,\na.ts\n#EXT-X-ENDLIST");
        catalog.observe("https://other.example/master", "https://player.example/watch?secret", "F2",
                "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1000\nv.m3u8");
        var session = new LocalUiSession(root, (url, dir) -> { throw new AssertionError("No real worker in UI rendering test"); });
        try (var app = new LocalUiSpike(session);
             var playwright = Playwright.create(new Playwright.CreateOptions().setEnv(Map.of("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1")));
             var browser = ChromiumLaunch.launch(playwright.chromium(), true)) {
            var page = browser.newPage();page.setDefaultTimeout(10_000);
            var version=new java.util.concurrent.atomic.AtomicInteger(100);
            page.route("**/api/pin",route->{
                var request=UiProtocol.object(route.request().postData());
                catalog.pin(((Number)request.get("revision")).longValue(),(String)request.get("candidate"));
                route.fulfill(new Route.FulfillOptions().setContentType("application/json")
                        .setBody(UiProtocol.json(Map.of("version",version.incrementAndGet(),"phase","DISCOVERING","status","RUNNING","catalog",catalog.versionedSnapshot()))));
            });
            page.navigate(app.launchUrl());
            page.waitForFunction("() => !document.querySelector('#start').disabled");
            var snapshot = Map.of("version", 100, "phase", "DISCOVERING", "status", "RUNNING", "catalog", catalog.versionedSnapshot());
            page.evaluate("json => display(JSON.parse(json))", UiProtocol.json(snapshot));
            assertEquals(2, page.locator(".source-card").count());
            String first = page.locator(".source-card").nth(0).innerText();
            assertTrue(first.contains("24:04"));assertTrue(first.contains("1/2"));assertTrue(first.contains("1920×1080, 1280×720"));
            assertTrue(first.contains("F1"));assertTrue(page.locator(".source-card").nth(1).innerText().contains("F2"));
            assertTrue(first.contains("<img src=x onerror=alert(1)>"));
            assertEquals(0, page.locator("#source-cards img").count());
            assertFalse(first.contains("private-token"));assertFalse(first.contains("?secret"));
            page.getByLabel("Обрати джерело C1", new Page.GetByLabelOptions().setExact(true)).check();
            page.locator("#variant").selectOption("V1");
            assertTrue(page.locator("#audio").innerText().contains("Ukrainian"));
            assertFalse(page.locator("#audio").innerText().contains("English"));
            page.locator("#audio").selectOption("A1");assertFalse(page.locator("#prepare").isDisabled());
            for(int i=0;i<130;i++)catalog.observe("https://ads.example/"+i+"/master","https://ads.example/",master);
            page.evaluate("json => display(JSON.parse(json))",UiProtocol.json(Map.of("version",version.incrementAndGet(),"phase","DISCOVERING","status","RUNNING","catalog",catalog.versionedSnapshot())));
            assertTrue(page.locator("#source-C1").isChecked());assertEquals("V1",page.locator("#variant").inputValue());assertEquals("A1",page.locator("#audio").inputValue());assertFalse(page.locator("#prepare").isDisabled());
            page.locator("#variant").selectOption("V2");assertTrue(page.locator("#prepare").isDisabled());
            assertTrue(page.locator("#audio").innerText().contains("English"));
            assertFalse(page.locator("#audio").innerText().contains("Ukrainian"));
            String other=catalog.observe("https://other.example/master","https://player.example/","#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1000\nv.m3u8");
            page.evaluate("json => display(JSON.parse(json))",UiProtocol.json(Map.of("version",version.incrementAndGet(),"phase","DISCOVERING","status","RUNNING","catalog",catalog.versionedSnapshot())));
            page.locator("#source-"+other).check(); // The original other source was evicted, not the pinned one.
            page.waitForFunction("() => !document.querySelector('#variant').disabled");
            assertTrue(page.locator("#audio").isDisabled());
            for (int width : new int[]{390, 1200}) {
                page.setViewportSize(width, 900);
                assertEquals(true, page.evaluate("() => document.documentElement.scrollWidth <= innerWidth"));
            }
            assertFalse(UiProtocol.json(session.report()).contains("Ukrainian"));
        }
    }
}
