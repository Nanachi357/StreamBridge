package dev.streambridge.spike;

import java.net.URI;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Arrays;
import com.sun.net.httpserver.HttpServer;

/** No visible windows or remote pages. Report is local diagnostics, not a share-safe export. */
final class PackagedSelfCheck {
    static int run(Path destination) throws Exception {
        if (!destination.isAbsolute()) throw new IllegalArgumentException("Self-check report path must be absolute");
        var report = new LinkedHashMap<String,Object>();
        report.put("javaHome", System.getProperty("java.home"));
        report.put("javaVersion", System.getProperty("java.version"));
        report.put("applicationLocation", Path.of(LocalUiLauncher.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString());
        int exit = 1;
        Path root = null;
        try {
            if (Runtime.version().feature() != 21) throw new IllegalStateException("Expected Java 21");
            verifyHttpTransport();
            report.put("boundedHttpTransport", "OK");
            report.put("downloadsDirectory", OutputFiles.downloads().toString());
            LauncherSupport.probeDependencies();
            report.put("dependenciesAndChildJava", "OK");
            Class.forName(RealDiscoverySpike.class.getName());
            root = Files.createTempDirectory("streambridge-package-check-");
            try (var app = new LocalUiSpike(new LocalUiSession(root));
                 var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()) {
                if (!LauncherSupport.ready(app.launchUrl())) throw new IllegalStateException("API readiness failed");
                for (String resource : new String[]{"/", "/app.js", "/app.css"}) {
                    var response = client.send(HttpRequest.newBuilder(URI.create(app.origin()+resource))
                            .timeout(Duration.ofSeconds(3)).GET().build(), HttpResponse.BodyHandlers.ofString());
                    if (response.statusCode() != 200 || response.body().isBlank()) throw new IllegalStateException("Missing UI resource: "+resource);
                }
            }
            report.put("localApiAndUiResources", "OK"); report.put("success", true); exit = 0;
        } catch (Exception failure) {
            report.put("success", false); report.put("error", failure.getClass().getSimpleName()+": "+failure.getMessage());
        } finally {
            if (root != null) Files.deleteIfExists(root); // Empty directory created by this check only.
            Files.createDirectories(destination.getParent());
            Files.writeString(destination, UiProtocol.json(report));
        }
        return exit;
    }

    /** Exercise the actual acquisition client, not just class loading or the UI's JDK client. */
    static void verifyHttpTransport() throws Exception {
        var loopback = InetAddress.getByName("127.0.0.1");
        var server = HttpServer.create(new InetSocketAddress(loopback, 0), 0);
        byte[] expected = new byte[]{83, 66, 10};
        server.createContext("/transport-check", exchange -> {
            try (exchange) {
                exchange.sendResponseHeaders(200, expected.length);
                exchange.getResponseBody().write(expected);
            }
        });
        server.start();
        try {
            var uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/transport-check");
            // This exception is confined to the exact fixture owned by this check.
            // Normal fetches still use the unchanged public-address policy.
            try (var fetcher = new BoundedResourceFetcher(target -> {
                if (!uri.equals(target)) throw new BoundedResourceFetcher.FetchException(BoundedResourceFetcher.Failure.BLOCKED_ADDRESS);
                return new InetAddress[]{loopback};
            })) {
                var resource = fetcher.fetch(uri, new BoundedResourceFetcher.Limits(32, 0,
                        Duration.ofSeconds(5), Duration.ofSeconds(2), Duration.ofSeconds(2)),
                        new BoundedResourceFetcher.RequestBudget(1));
                if (!uri.equals(resource.finalUri()) || !Arrays.equals(expected, resource.bytes()))
                    throw new IllegalStateException("HTTP transport fixture mismatch");
            }
        } finally { server.stop(0); }
    }
}
