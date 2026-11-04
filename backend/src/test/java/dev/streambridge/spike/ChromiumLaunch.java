package dev.streambridge.spike;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.List;
import com.microsoft.playwright.options.Proxy;

/** Shared by discovery and startup checks: a separate profile is not an OS sandbox. */
final class ChromiumLaunch {
    private ChromiumLaunch() { }

    static Path executablePath(BrowserType chromium) {
        try {
            Path location = Path.of(ChromiumLaunch.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            return executablePath(location, Path.of(chromium.executablePath()));
        } catch (java.net.URISyntaxException failure) { throw new IllegalStateException(failure); }
    }

    static Path executablePath(Path codeLocation, Path external) {
        // Only a packaged JAR can opt into an adjacent native payload; never use the working directory.
        if (Files.isRegularFile(codeLocation)) {
            Path directory = codeLocation.getParent().resolve("native/chromium");
            if (Files.exists(codeLocation.getParent().resolve("bundled-browser.required")) || Files.exists(directory)) {
                Path executable = directory.resolve("chrome.exe");
                if (!Files.isRegularFile(executable)) throw new IllegalStateException("Packaged Chromium is incomplete: " + executable);
                return executable;
            }
        }
        return external;
    }

    static Browser launch(BrowserType chromium, boolean headless) {
        return launch(chromium, headless, null);
    }

    static Browser launch(BrowserType chromium, boolean headless, BrowserNetworkProxy network) {
        // Use the same full Chromium binary for visible discovery and hidden checks.
        // Never retry with weaker settings if the sandboxed launch fails.
        var options = new BrowserType.LaunchOptions()
                .setChromiumSandbox(true)
                .setHeadless(headless)
                .setExecutablePath(executablePath(chromium))
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
