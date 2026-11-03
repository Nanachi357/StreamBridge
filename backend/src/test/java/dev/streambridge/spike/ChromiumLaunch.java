package dev.streambridge.spike;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import java.nio.file.Path;
import java.util.List;
import com.microsoft.playwright.options.Proxy;

/** Shared by discovery and startup checks: a separate profile is not an OS sandbox. */
final class ChromiumLaunch {
    private ChromiumLaunch() { }

    static Browser launch(BrowserType chromium, boolean headless) {
        return launch(chromium, headless, null);
    }

    static Browser launch(BrowserType chromium, boolean headless, BrowserNetworkProxy network) {
        // Use the same full Chromium binary for visible discovery and hidden checks.
        // Never retry with weaker settings if the sandboxed launch fails.
        var options = new BrowserType.LaunchOptions()
                .setChromiumSandbox(true)
                .setHeadless(headless)
                .setExecutablePath(Path.of(chromium.executablePath()))
                .setTimeout(20_000);
        if (network != null) {
            options.setProxy(new Proxy(network.endpoint()).setBypass("<-loopback>"));
            // Full Chrome uses webrtc-ip-handling-policy; the similarly named
            // force-webrtc-ip-handling-policy switch does not protect this binary.
            // Keep the real UDP/TURN receiver regression test when upgrading Chromium.
            options.setArgs(List.of("--disable-quic",
                    "--webrtc-ip-handling-policy=disable_non_proxied_udp",
                    "--host-resolver-rules=MAP * ~NOTFOUND, EXCLUDE 127.0.0.1"));
        }
        return chromium.launch(options);
    }
}
