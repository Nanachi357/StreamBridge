package dev.streambridge.spike;

import java.io.IOException;
import java.util.List;
import java.util.Properties;

/** Versioned contract with the bundled Playwright driver, not a replacement for its defaults. */
final class ChromiumFeaturePolicy {
    static final String DRIVER_VERSION = "1.64.0";
    // Playwright has no public Java API for default arguments. Chromium uses the LAST
    // feature switch, so repeat the reviewed defaults before appending our additions.
    // ChromiumFeaturePolicyTest compares this list with the actual bundled JS driver;
    // upgrades must review that contract, not merely update DRIVER_VERSION.
    static final List<String> DEFAULT_DISABLED = List.of(
            "AvoidUnnecessaryBeforeUnloadCheckSync", "DestroyProfileOnBrowserClose",
            "DialMediaRouteProvider", "GlobalMediaControls", "HttpsUpgrades", "LensOverlay",
            "MediaRouter", "PaintHolding", "ThirdPartyStoragePartitioning",
            "BlockOriginHeaderModificationOnRedirect", "AvoidCorsURLLoaderRestartOnRedirect",
            "Translate", "AutoDeElevate", "OptimizationHints", "NetworkTimeServiceQuerying",
            "AimEnabled", "msForceBrowserSignIn", "msEdgeUpdateLaunchServicesPreferredVersion");
    static final String DEFAULT_ENABLED = "CDPScreenshotNewSurface";

    private ChromiumFeaturePolicy() { }

    static List<String> arguments() {
        requireDriverVersion(driverVersion());
        return arguments(System.getenv("PLAYWRIGHT_LEGACY_SCREENSHOT"));
    }

    static List<String> arguments(String legacyScreenshot) {
        // Match Node's truthiness: even "0" is a nonempty string and selects legacy mode.
        boolean legacy = legacyScreenshot != null && !legacyScreenshot.isEmpty();
        return List.of(
                "--disable-features=" + String.join(",", DEFAULT_DISABLED)
                        + ",PreconnectToSearch,AutofillServerCommunication",
                "--enable-features=" + (legacy ? "" : DEFAULT_ENABLED + ",")
                        + "SkipListAccountsWithoutGaiaCookies");
    }

    static String driverVersion() {
        try (var input = ChromiumFeaturePolicy.class.getResourceAsStream(
                "/META-INF/maven/com.microsoft.playwright/driver/pom.properties")) {
            if (input == null) throw new IllegalStateException("Playwright driver version metadata is missing");
            var properties = new Properties();
            properties.load(input);
            return properties.getProperty("version");
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot verify Playwright feature defaults", failure);
        }
    }

    static void requireDriverVersion(String version) {
        if (!DRIVER_VERSION.equals(version)) {
            throw new IllegalStateException("Unreviewed Playwright feature defaults for driver " + version
                    + "; expected " + DRIVER_VERSION);
        }
    }
}
