package dev.streambridge.spike;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;

/** Manual launch/close experiment, not a JUnit test or application entry point. */
public final class BrowserSpike {

    public static void main(String[] args) throws IOException {
        // Browser installation is explicit: run installSpikeChromium first.
        Playwright.CreateOptions options = new Playwright.CreateOptions()
                .setEnv(Map.of("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1"));

        try (Playwright playwright = Playwright.create(options);
             Browser browser = ChromiumLaunch.launch(playwright.chromium(), false);
             BrowserContext context = browser.newContext()) {
            Page page = context.newPage();
            page.setContent("""
                    <!doctype html>
                    <html lang="en">
                    <head><meta charset="utf-8"><title>StreamBridge browser spike</title></head>
                    <body>
                        <h1>StreamBridge browser spike</h1>
                        <p>Return to the Java console and press Enter to close this browser.</p>
                    </body>
                    </html>
                    """);

            System.out.println("Chromium version: " + browser.version());
            System.out.println("Page title: " + page.title());
            System.out.println("Press Enter in this console to close the browser.");

            // This blocks Playwright event dispatch; suitable only for this static page.
            // Network observation will need a different waiting strategy.
            BufferedReader input = new BufferedReader(
                    new InputStreamReader(System.in, StandardCharsets.UTF_8));
            if (input.readLine() == null) {
                System.out.println("Console input ended (EOF); closing the browser.");
            }
        }

        System.out.println("Browser context, browser and Playwright closed.");
    }
}
