package dev.streambridge.spike;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import java.util.ArrayList;
import com.microsoft.playwright.options.Proxy;

/** Shared by discovery and startup checks: a separate profile is not an OS sandbox. */
final class ChromiumLaunch {
    private ChromiumLaunch() { }

    static Browser launch(BrowserType chromium, boolean headless) {
        return launch(chromium, headless, null);
    }

    static Browser launch(BrowserType chromium, boolean headless, BrowserNetworkProxy network) {
        // Use installed Edge Stable with a Playwright-owned temporary profile in both modes.
        // Never retry with weaker settings if the sandboxed launch fails.
        var options = new BrowserType.LaunchOptions()
                .setChromiumSandbox(true)
                .setHeadless(headless)
                .setChannel("msedge")
                .setTimeout(StartupTimeouts.BROWSER.toMillis());
        var args = new ArrayList<>(ChromiumFeaturePolicy.arguments());
        if (network != null) {
            options.setProxy(new Proxy(network.endpoint()).setBypass("<-loopback>"));
            // Full Chrome uses webrtc-ip-handling-policy; the similarly named
            // force-webrtc-ip-handling-policy switch does not protect this binary.
            // Keep the real UDP/TURN receiver regression test when upgrading Chromium.
            args.add("--disable-quic");
            args.add("--webrtc-ip-handling-policy=disable_non_proxied_udp");
            args.add("--host-resolver-rules=MAP * ~NOTFOUND, EXCLUDE 127.0.0.1");
        }
        options.setArgs(args);
        return chromium.launch(options);
    }
}
