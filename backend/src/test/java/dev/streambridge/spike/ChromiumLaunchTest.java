package dev.streambridge.spike;

import com.microsoft.playwright.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.*;

class ChromiumLaunchTest {
    @org.junit.jupiter.api.io.TempDir Path root;
    @Test void packagedBrowserWinsOverExternalCacheAndNeverUsesWorkingDirectory() throws Exception {
        Path jar = Files.writeString(root.resolve("streambridge.jar"), "fixture");
        Path external = root.resolve("external/chrome.exe");
        assertEquals(external, ChromiumLaunch.executablePath(jar, external));
        Path bundled = Files.createDirectories(root.resolve("native/chromium")).resolve("chrome.exe");
        Files.writeString(bundled, "fixture");
        assertEquals(bundled, ChromiumLaunch.executablePath(jar, external));
        assertEquals(external, ChromiumLaunch.executablePath(root, external));
        Files.delete(bundled);
        assertThrows(IllegalStateException.class, () -> ChromiumLaunch.executablePath(jar, external));
        Files.delete(bundled.getParent());
        Files.writeString(root.resolve("bundled-browser.required"), "required");
        assertThrows(IllegalStateException.class, () -> ChromiumLaunch.executablePath(jar, external));
    }
    @Test void visibleAndHiddenLaunchesRequireSandboxAndSameFullBinary() {
        for (boolean headless : new boolean[]{false, true}) {
            BrowserType type = mock(BrowserType.class);
            Browser browser = mock(Browser.class);
            Path executable = Path.of("owned-chromium", "chrome.exe").toAbsolutePath();
            when(type.executablePath()).thenReturn(executable.toString());
            when(type.launch(any(BrowserType.LaunchOptions.class))).thenReturn(browser);

            assertSame(browser, ChromiumLaunch.launch(type, headless));
            var options = ArgumentCaptor.forClass(BrowserType.LaunchOptions.class);
            verify(type).launch(options.capture());
            assertEquals(Boolean.TRUE, options.getValue().chromiumSandbox);
            assertEquals(headless, options.getValue().headless);
            assertEquals(executable, options.getValue().executablePath);
            assertEquals(20_000.0, options.getValue().timeout);
            assertEquals(ChromiumFeaturePolicy.arguments(), options.getValue().args);
            assertNull(options.getValue().ignoreAllDefaultArgs);
            assertNull(options.getValue().ignoreDefaultArgs);
        }
    }

    @Test void failedLaunchIsNotRetriedWithoutSandbox() {
        BrowserType type = mock(BrowserType.class);
        when(type.executablePath()).thenReturn(Path.of("missing-chromium.exe").toAbsolutePath().toString());
        var failure = new PlaywrightException("Synthetic sandbox launch failure");
        when(type.launch(any(BrowserType.LaunchOptions.class))).thenThrow(failure);
        assertSame(failure, assertThrows(PlaywrightException.class, () -> ChromiumLaunch.launch(type, false)));
        var options = ArgumentCaptor.forClass(BrowserType.LaunchOptions.class);
        verify(type, times(1)).launch(options.capture());
        assertEquals(Boolean.TRUE, options.getValue().chromiumSandbox);
    }

    @Test void discoveryUsesOnlyOwnedGatewayAndClosedGatewayNeverFallsBackToDirect() throws Exception {
        BrowserType type=mock(BrowserType.class);
        when(type.executablePath()).thenReturn(Path.of("owned-chromium","chrome.exe").toAbsolutePath().toString());
        try(var network=new BrowserNetworkProxy()) {
            ChromiumLaunch.launch(type,false,network);
            var options=ArgumentCaptor.forClass(BrowserType.LaunchOptions.class);verify(type).launch(options.capture());
            assertEquals(network.endpoint(),options.getValue().proxy.server);
            assertEquals("<-loopback>",options.getValue().proxy.bypass);
            assertTrue(options.getValue().args.contains("--disable-quic"));
            assertTrue(options.getValue().args.contains("--webrtc-ip-handling-policy=disable_non_proxied_udp"));
            assertTrue(options.getValue().args.containsAll(ChromiumFeaturePolicy.arguments()));
            assertEquals(Boolean.TRUE,options.getValue().chromiumSandbox);
            network.close();assertThrows(IllegalStateException.class,()->ChromiumLaunch.launch(type,false,network));
            verify(type,times(1)).launch(any(BrowserType.LaunchOptions.class));
        }
    }

    @Test void currentEntrypointsUseSharedPolicy() throws Exception {
        for (String name : new String[]{"RealDiscoverySpike", "LauncherDependencyProbe", "BrowserSpike",
                "HlsDiscoverySpike", "HlsDownloadSpike", "HlsSelectionSpike"}) {
            String source = Files.readString(Path.of("src/test/java/dev/streambridge/spike", name + ".java"));
            assertTrue(source.contains("ChromiumLaunch.launch("), name);
            assertFalse(source.contains(".chromium().launch("), name);
            assertFalse(source.contains("--no-sandbox"), name);
            if(name.equals("RealDiscoverySpike")||name.equals("LauncherDependencyProbe")) {
                assertTrue(source.contains("new BrowserNetworkProxy()"),name);
                assertTrue(source.contains(", network)"),name);
            }
        }
    }

    @Test void windowsChromiumReportsSandboxedRenderers() {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"));
        assumeTrue("1".equals(System.getenv("STREAMBRIDGE_UI_BROWSER_CHECK")));
        // Explicit operator opt-in only: this window can take desktop focus.
        boolean headless = !"1".equals(System.getenv("STREAMBRIDGE_VISIBLE_BROWSER_CHECK"));
        try (var playwright = Playwright.create(new Playwright.CreateOptions()
                .setEnv(Map.of("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1")));
             var browser = ChromiumLaunch.launch(playwright.chromium(), headless);
             var context = browser.newContext()) {
            var fixture = context.newPage();
            fixture.setContent("<title>Owned sandbox test</title><p>Static fixture</p>");
            assertEquals("Owned sandbox test", fixture.title());
            var diagnostics = context.newPage();
            diagnostics.setDefaultTimeout(10_000);
            diagnostics.navigate("chrome://version");
            String commandLine = diagnostics.locator("#command_line").innerText();
            ChromiumFeaturePolicyTest.assertEffectiveFeatures(commandLine);
            assertEquals(headless, commandLine.contains("--headless"));
            for (String flag : new String[]{"--no-sandbox", "--disable-gpu-sandbox",
                    "--disable-setuid-sandbox", "--single-process"}) {
                assertFalse(commandLine.contains(flag), flag);
            }
            diagnostics.navigate("chrome://sandbox");
            diagnostics.waitForFunction("() => document.body.innerText.includes('Renderer')");
            String report = diagnostics.locator("body").innerText();
            assertWindowsRendererPolicies(report);
            // The full diagnostic includes machine-local paths; retain only a summary.
            System.out.println("Chromium " + browser.version() + ": headless=" + headless
                    + "; renderer Lockdown/Untrusted verified; no sandbox-disabling launch flags.");
        }
    }

    @Test void utilityLockdownOrMissingRendererCannotPassRendererCheck() {
        for (String report : new String[]{
                "123\tUtility\tStorage Service\tService\tLockdown\tS-1-16-0 Untrusted",
                "123\tRenderer\t\tNot Sandboxed\t\t",
                "123\tRenderer\t\tRenderer\tLockdown\tS-1-16-0 Untrusted\n456\tRenderer",
                "123\tRenderer\t\tRenderer\tLockdown\tS-1-16-8192 Medium",
                "123\tRenderer\t\tRenderer\tLockdown\tS-1-16-0 Untrusted\n456\tRenderer\t\tNot Sandboxed\t\t"}) {
            assertThrows(AssertionError.class, () -> assertWindowsRendererPolicies(report));
        }
    }

    private static void assertWindowsRendererPolicies(String report) {
        var renderers = report.lines().map(line -> line.split("\t", -1))
                .filter(cells -> cells.length >= 2 && cells[1].equals("Renderer")).toList();
        assertFalse(renderers.isEmpty(), "No renderer policy rows found in chrome://sandbox");
        for (String[] cells : renderers) {
            assertTrue(cells.length >= 6, "Incomplete renderer policy row");
            assertEquals("Renderer", cells[3], "Renderer sandbox type");
            assertEquals("Lockdown", cells[4], "Renderer token lockdown");
            assertEquals("S-1-16-0 Untrusted", cells[5], "Renderer integrity level");
        }
    }
}
