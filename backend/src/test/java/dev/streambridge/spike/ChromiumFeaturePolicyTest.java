package dev.streambridge.spike;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import static org.junit.jupiter.api.Assertions.*;

class ChromiumFeaturePolicyTest {
    @Test void defaultsMatchTheActualBundledDriverNotJustOurOwnSnapshot() throws Exception {
        assertEquals(ChromiumFeaturePolicy.DRIVER_VERSION, ChromiumFeaturePolicy.driverVersion());
        try (var input = getClass().getResourceAsStream("/driver/package/lib/coreBundle.js")) {
            assertNotNull(input, "Review the driver layout when upgrading Playwright");
            String source = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            int start = source.indexOf("var init_chromiumSwitches = __esm({");
            assertTrue(start >= 0, "Review changed driver module layout");
            int end = source.indexOf("\n});", start);
            assertTrue(end > start, "Missing Chromium switch module boundary");
            source = source.substring(start, end);
            var blocks = Pattern.compile("disabledFeatures = \\[([\\s\\S]*?)\\]\\.filter\\(Boolean\\);").matcher(source);
            assertTrue(blocks.find(), "Review changed Playwright feature generation");
            String body = blocks.group(1).replaceAll("(?m)//[^\\r\\n]*", "");
            var entries = Pattern.compile("\"([A-Za-z0-9]+)\"").matcher(body);
            List<String> actual = entries.results().map(m -> m.group(1)).toList();
            assertTrue(body.replaceAll("\"[A-Za-z0-9]+\"", "").matches("[\\s,]*"),
                    "Conditional/computed defaults require explicit review");
            assertFalse(blocks.find(), "Ambiguous driver feature definition");
            assertEquals(actual, ChromiumFeaturePolicy.DEFAULT_DISABLED);
            assertTrue(source.contains("\"--disable-features=\" + disabledFeatures.join(\",\")"));
            assertTrue(source.contains("process.env.PLAYWRIGHT_LEGACY_SCREENSHOT ? \"\" : \"--enable-features="
                    + ChromiumFeaturePolicy.DEFAULT_ENABLED + "\""), "Review enabled features and environment semantics");
            // Every generated enable-features literal must be covered; a new source path needs review.
            var enabled = Pattern.compile("\"--enable-features=([^\"]*)\"").matcher(source);
            assertEquals(List.of(ChromiumFeaturePolicy.DEFAULT_ENABLED), enabled.results().map(m -> m.group(1)).toList());
        }
    }

    @Test void legacyScreenshotOverrideKeepsDriverSemantics() {
        for (String value : new String[]{null, "", "0", "1"}) {
            String enabled = ChromiumFeaturePolicy.arguments(value).get(1);
            assertEquals(value == null || value.isEmpty(), enabled.contains("CDPScreenshotNewSurface"));
            assertTrue(enabled.endsWith("SkipListAccountsWithoutGaiaCookies"));
            assertTrue(ChromiumFeaturePolicy.arguments(value).getFirst()
                    .endsWith(",PreconnectToSearch,AutofillServerCommunication"));
            assertEquals(ChromiumFeaturePolicy.arguments(null).getFirst(), ChromiumFeaturePolicy.arguments(value).getFirst());
        }
    }

    @Test void unknownOrMissingDriverVersionCannotSilentlyDropDefaults() {
        for (String version : new String[]{null, "", "1.65.0", "1.64.0-modified"}) {
            assertThrows(IllegalStateException.class, () -> ChromiumFeaturePolicy.requireDriverVersion(version));
        }
    }

    static void assertEffectiveFeatures(String commandLine) {
        // Chromium takes the last occurrence: merely finding our names anywhere is insufficient.
        for (String expected : ChromiumFeaturePolicy.arguments()) {
            String prefix = expected.substring(0, expected.indexOf('=') + 1);
            var matches = Pattern.compile(Pattern.quote(prefix) + "([^\\s\"]+)").matcher(commandLine);
            String effective = null;
            while (matches.find()) effective = matches.group(1);
            assertNotNull(effective, prefix);
            assertEquals(Arrays.asList(expected.substring(prefix.length()).split(",")),
                    Arrays.asList(effective.split(",")), prefix);
        }
    }
}
