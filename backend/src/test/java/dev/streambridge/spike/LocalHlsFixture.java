package dev.streambridge.spike;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/** Test-only loopback server. Serves an explicit allowlist, never arbitrary paths. */
final class LocalHlsFixture implements AutoCloseable {
    private final HttpServer server;

    LocalHlsFixture(Path directory) throws IOException {
        Map<String, Asset> assets = new HashMap<>();
        try (var html = LocalHlsFixture.class.getResourceAsStream("/spike/hls/index.html")) {
            if (html == null) throw new IOException("Missing test resource: /spike/hls/index.html");
            assets.put("/", new Asset("text/html; charset=utf-8", html.readAllBytes()));
        }
        byte[] player = read(directory, "hls.min.js");
        verifyPlayer(player);
        assets.put("/hls.min.js", new Asset("application/javascript", player));
        assets.put("/media/stream.m3u8", new Asset("application/vnd.apple.mpegurl", read(directory, "stream.m3u8")));
        for (int i = 0; i < 4; i++) {
            String name = "segment-%03d.ts".formatted(i);
            assets.put("/media/" + name, new Asset("video/mp2t", read(directory, name)));
        }

        // Port 0 asks the OS for an available port. Never bind to all interfaces.
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> serve(exchange, assets));
        server.start();
    }

    String origin() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static byte[] read(Path directory, String name) throws IOException {
        Path file = directory.resolve(name);
        if (!Files.isRegularFile(file)) {
            throw new IOException("Missing fixture file: " + file + ". Run scripts/prepare-hls-fixture.ps1 first.");
        }
        return Files.readAllBytes(file);
    }

    static void verifyPlayer(byte[] player) {
        try {
            String actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(player));
            if (!actual.equals("a12e7ee1cd64a69dcdb314157e45dafcba705bfb0b1440b7935cb265d374423e")) {
                throw new IllegalStateException("Unexpected hls.js bytes; regenerate the fixture.");
            }
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", e);
        }
    }

    private static void serve(HttpExchange exchange, Map<String, Asset> assets) throws IOException {
        try (exchange) {
            if (!exchange.getRequestMethod().equals("GET")) {
                exchange.getResponseHeaders().set("Allow", "GET");
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            Asset asset = assets.get(exchange.getRequestURI().getPath());
            if (asset == null) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }
            exchange.getResponseHeaders().set("Content-Type", asset.contentType());
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
            exchange.sendResponseHeaders(200, asset.bytes().length);
            exchange.getResponseBody().write(asset.bytes());
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private record Asset(String contentType, byte[] bytes) { }
}
