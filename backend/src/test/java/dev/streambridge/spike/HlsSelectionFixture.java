package dev.streambridge.spike;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

/** Synthetic assets only. Two loopback origins permit a real cross-origin iframe test. */
final class HlsSelectionFixture implements AutoCloseable {
    private record Asset(String type, byte[] bytes) { }
    private final Map<String, Asset> assets = new HashMap<>();
    private final ConcurrentLinkedQueue<String> requests = new ConcurrentLinkedQueue<>();
    private final HttpServer top;
    private final HttpServer child;

    HlsSelectionFixture(Path directory) throws IOException {
        if (!Files.isRegularFile(directory.resolve("ready"))) throw new IOException("Prepare the selection fixture with --prepare first");
        byte[] player = Files.readAllBytes(directory.resolve("hls.min.js"));
        LocalHlsFixture.verifyPlayer(player);
        assets.put("/hls.min.js", new Asset("application/javascript", player));
        for (String clip : List.of("a", "b")) {
            putText("/" + clip + "/master", "application/vnd.apple.mpegurl", masterText());
            for (String track : List.of("v180", "v360", "uk", "en")) {
                String prefix = "/" + clip + "/" + track + "/";
                Path trackDirectory = directory.resolve(clip).resolve(track);
                String playlist = Files.readString(trackDirectory.resolve("index.m3u8"));
                // FFmpeg follows nested references itself. Keep even those references within this
                // generated fixture: reject key/map/variable/other URI-bearing tags, not just bad segments.
                for (String line : playlist.lines().filter(s -> s.startsWith("#")).toList()) {
                    if (!(line.equals("#EXTM3U") || line.equals("#EXT-X-ENDLIST")
                            || line.equals("#EXT-X-PLAYLIST-TYPE:VOD")
                            || List.of("#EXT-X-VERSION:", "#EXT-X-TARGETDURATION:", "#EXT-X-MEDIA-SEQUENCE:", "#EXTINF:")
                            .stream().anyMatch(line::startsWith))) {
                        throw new IOException("Unsupported tag in controlled media playlist: " + line);
                    }
                }
                putText(prefix + "index.m3u8", "application/vnd.apple.mpegurl", playlist);
                for (String line : playlist.lines().filter(s -> !s.isBlank() && !s.startsWith("#")).toList()) {
                    if (!line.matches("segment-[0-9]{3}\\.ts")) throw new IOException("Unexpected generated segment name");
                    assets.put(prefix + line, new Asset("video/mp2t", Files.readAllBytes(trackDirectory.resolve(line))));
                }
            }
        }
        top = newServer();
        try {
            child = newServer();
        } catch (IOException failure) {
            top.stop(0);
            throw failure;
        }
        try {
            String template;
            try (var resource = getClass().getResourceAsStream("/spike/hls/selection.html")) {
                if (resource == null) throw new IOException("Missing selection HTML");
                template = new String(resource.readAllBytes(), StandardCharsets.UTF_8);
            }
            for (String mode : List.of("single", "multiple", "iframe", "frame")) {
                putText("/" + mode, "text/html; charset=utf-8", template.replace("@@MODE@@", mode)
                        .replace("@@FRAME_ORIGIN@@", childOrigin()));
            }
            top.start();
            child.start();
        } catch (IOException | RuntimeException failure) {
            close();
            throw failure;
        }
    }

    private HttpServer newServer() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try (exchange) {
                if (!exchange.getRequestMethod().equals("GET")) {
                    exchange.sendResponseHeaders(405, -1);
                    return;
                }
                String path = exchange.getRequestURI().getRawPath();
                Asset asset = assets.get(path);
                if (asset == null) {
                    exchange.sendResponseHeaders(404, -1);
                    return;
                }
                requests.add(path);
                exchange.getResponseHeaders().set("Content-Type", asset.type());
                exchange.getResponseHeaders().set("Cache-Control", "no-store");
                exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
                exchange.sendResponseHeaders(200, asset.bytes().length);
                exchange.getResponseBody().write(asset.bytes());
            }
        });
        return server;
    }

    String origin() { return "http://127.0.0.1:" + top.getAddress().getPort(); }
    String childOrigin() { return "http://127.0.0.1:" + child.getAddress().getPort(); }
    List<String> requests() { return List.copyOf(requests); }
    void clearRequests() { requests.clear(); }

    void requireAllowed(URI uri) {
        String authority = uri.getScheme() + "://" + uri.getRawAuthority();
        if (!(authority.equals(origin()) || authority.equals(childOrigin())) || uri.getRawQuery() != null
                || uri.getFragment() != null || !assets.containsKey(uri.getRawPath())) {
            throw new IllegalArgumentException("Outside exact fixture allowlist: " + uri);
        }
    }

    private void putText(String path, String type, String text) {
        assets.put(path, new Asset(type, text.getBytes(StandardCharsets.UTF_8)));
    }

    private static String masterText() {
        // Both independent clips deliberately reuse GROUP-ID and NAME values.
        return """
                #EXTM3U
                #EXT-X-VERSION:3
                #EXT-X-INDEPENDENT-SEGMENTS
                #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="sound",NAME="Synthetic, UK",LANGUAGE="uk",DEFAULT=YES,AUTOSELECT=YES,URI="uk/index.m3u8"
                #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="sound",NAME="Synthetic, EN",LANGUAGE="en",DEFAULT=NO,AUTOSELECT=YES,URI="en/index.m3u8"
                #EXT-X-STREAM-INF:BANDWIDTH=500000,RESOLUTION=320x180,AUDIO="sound"
                v180/index.m3u8
                #EXT-X-STREAM-INF:BANDWIDTH=900000,RESOLUTION=640x360,AUDIO="sound"
                v360/index.m3u8
                """;
    }

    static void prepare(Path directory, Path playerDirectory, Path ffmpeg) throws IOException, InterruptedException {
        byte[] player = Files.readAllBytes(playerDirectory.resolve("hls.min.js"));
        LocalHlsFixture.verifyPlayer(player);
        Files.createDirectory(directory); // Refuse overwrite, including an incomplete earlier preparation.
        Files.write(directory.resolve("hls.min.js"), player);
        Files.copy(playerDirectory.resolve("hls.LICENSE"), directory.resolve("hls.LICENSE"));
        for (String clip : List.of("a", "b")) {
            for (String track : List.of("v180", "v360", "uk", "en")) {
                Path output = Files.createDirectories(directory.resolve(clip).resolve(track));
                var command = new ArrayList<>(List.of(ffmpeg.toString(), "-hide_banner", "-nostdin", "-n", "-v", "warning", "-f", "lavfi"));
                if (track.startsWith("v")) {
                    String size = track.equals("v180") ? "320x180" : "640x360";
                    String color = clip.equals("a") ? "red" : "blue";
                    command.addAll(List.of("-i", "color=c=black:size=" + size + ":rate=25,"
                            + "drawbox=color=white:t=fill:enable='lt(mod(t,2),0.12)',drawbox=x=0:y=0:w=16:h=16:color=" + color + ":t=fill",
                            "-t", "8", "-an", "-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p",
                            "-g", "50", "-keyint_min", "50", "-sc_threshold", "0"));
                } else {
                    int frequency = (clip.equals("a") ? 0 : 880) + (track.equals("uk") ? 440 : 880);
                    command.addAll(List.of("-i", "aevalsrc=if(lt(mod(t\\,2)\\,0.12)\\,0.2*sin(2*PI*" + frequency + "*t)\\,0):s=48000",
                            "-t", "8", "-vn", "-c:a", "aac", "-b:a", "128k"));
                }
                command.addAll(List.of("-f", "hls", "-hls_time", "2", "-hls_playlist_type", "vod",
                        "-hls_segment_filename", output.resolve("segment-%03d.ts").toString(), output.resolve("index.m3u8").toString()));
                HlsDownloadSpike.runChecked(command, output.resolve("generation.log"), 30_000);
            }
        }
        Files.writeString(directory.resolve("ready"), "Synthetic selection fixture v1\n");
        System.out.println("Prepared: " + directory);
    }

    @Override public void close() {
        top.stop(0);
        child.stop(0);
    }
}
