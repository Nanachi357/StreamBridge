package dev.streambridge.spike;

import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Loopback-only UI pilot for existing experiments; not a remotely deployable service. */
public final class LocalUiSpike implements AutoCloseable {
    private final HttpServer server;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Semaphore streams = new Semaphore(4), files = new Semaphore(2);
    private final LocalUiSession session;
    private final String key = UUID.randomUUID().toString() + UUID.randomUUID();
    private final String cookieName = "sb_" + UUID.randomUUID().toString().substring(0,8);
    private final String origin;

    LocalUiSpike(LocalUiSession session) throws IOException {
        this.session = session;
        server = HttpServer.create(new InetSocketAddress(InetAddress.getByAddress(new byte[]{127,0,0,1}), 0), 16);
        origin = "http://127.0.0.1:" + server.getAddress().getPort();
        server.setExecutor(executor); server.createContext("/", this::handle); server.start();
    }
    String launchUrl() { return origin + "/#" + key; }
    String origin() { return origin; }
    String key() { return key; }

    public static void main(String[] args) throws Exception {
        Path output = null;
        for (String arg : args) {
            if (arg.startsWith("--output-dir=")) output = Path.of(arg.substring(13));
            else throw new IllegalArgumentException("Unknown UI option");
        }
        var app = new LocalUiSpike(LocalUiSession.forUser(output));
        Runtime.getRuntime().addShutdownHook(new Thread(app::close));
        System.out.println("StreamBridge local UI: " + app.launchUrl());
        System.out.println("Output directory: " + app.session.snapshot().get("outputDirectory"));
        System.out.println("Local pilot only. Keep this terminal open. Stop with Ctrl+C. Do not share the launch URL.");
        new CountDownLatch(1).await();
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            var headers = exchange.getResponseHeaders();
            headers.set("Cache-Control", "no-store"); headers.set("X-Content-Type-Options", "nosniff");
            headers.set("Referrer-Policy", "no-referrer"); headers.set("X-Frame-Options", "DENY");
            headers.set("Content-Security-Policy", "default-src 'none'; script-src 'self'; style-src 'self'; connect-src 'self'; img-src 'self'; media-src 'self'; base-uri 'none'; frame-ancestors 'none'; form-action 'none'");
            if (!trustedRequest(exchange)) { reply(exchange,403,"text/plain","Forbidden"); return; }
            String method = exchange.getRequestMethod(), path = exchange.getRequestURI().getPath();
            if (method.equals("GET") && Set.of("/", "/app.js", "/app.css").contains(path)) {
                String resource = path.equals("/") ? "index.html" : path.substring(1);
                try (var input = getClass().getResourceAsStream("/spike/ui/" + resource)) {
                    if (input == null) { reply(exchange,404,"text/plain","Not found"); return; }
                    reply(exchange,200, resource.endsWith("html")?"text/html":resource.endsWith("js")?"text/javascript":"text/css", input.readAllBytes());
                }
                return;
            }
            if (!path.startsWith("/api/")) { reply(exchange,404,"text/plain","Not found"); return; }
            boolean keyValid = key.equals(exchange.getRequestHeaders().getFirst("X-StreamBridge-Key"));
            boolean cookieValid = Arrays.stream(Optional.ofNullable(exchange.getRequestHeaders().getFirst("Cookie")).orElse("").split(";"))
                    .map(String::strip).anyMatch(value -> value.equals(cookieName + "=" + key));
            if (!(keyValid || cookieValid) || method.equals("POST") && (!keyValid || !origin.equals(exchange.getRequestHeaders().getFirst("Origin")))) {
                reply(exchange,403,"text/plain","Forbidden"); return;
            }
            if (method.equals("GET")) {
                switch (path) {
                    case "/api/state" -> json(exchange,200,session.snapshot());
                    case "/api/events" -> events(exchange);
                    case "/api/report" -> {
                        headers.set("Content-Disposition", "attachment; filename=streambridge-report.json");
                        json(exchange,200,session.report());
                    }
                    default -> {
                        if (path.startsWith("/api/file/")) file(exchange,path.substring(10));
                        else if (path.startsWith("/api/preview/")) preview(exchange, path.substring(13));
                        else reply(exchange,404,"text/plain","Not found");
                    }
                }
                return;
            }
            if (!method.equals("POST")) { reply(exchange,405,"text/plain","Method not allowed"); return; }
            if (!Optional.ofNullable(exchange.getRequestHeaders().getFirst("Content-Type")).orElse("").startsWith("application/json")) {
                reply(exchange,415,"text/plain","JSON required"); return;
            }
            byte[] body = exchange.getRequestBody().readNBytes(8193);
            if (body.length > 8192) { reply(exchange,413,"text/plain","Body too large"); return; }
            Map<String,Object> request = UiProtocol.object(new String(body,StandardCharsets.UTF_8));
            switch (path) {
                case "/api/session" -> headers.add("Set-Cookie",cookieName + "=" + key + "; HttpOnly; SameSite=Strict; Path=/");
                case "/api/start" -> session.start(string(request,"url"));
                case "/api/pin" -> session.pin(string(request,"id"),((Number)request.get("revision")).longValue(),string(request,"candidate"));
                case "/api/prepare" -> session.prepare(string(request,"id"), ((Number)request.get("revision")).longValue(),
                        string(request,"candidate"), string(request,"variant"), string(request,"audio"));
                case "/api/confirm" -> session.confirm(string(request,"id"),string(request,"token"));
                case "/api/preview" -> session.preview(string(request,"id"),string(request,"token"), millis(request));
                case "/api/output-folder" -> session.chooseOutputDirectory();
                case "/api/cancel" -> session.cancel(string(request,"id"));
                default -> { reply(exchange,404,"text/plain","Not found"); return; }
            }
            json(exchange,200,session.snapshot());
        } catch (IllegalStateException conflict) { safeError(exchange,409,"STATE_CONFLICT"); }
        catch (tools.jackson.core.JacksonException invalidJson) { safeError(exchange,400,"INVALID_JSON"); }
        catch (IllegalArgumentException | ClassCastException | NullPointerException invalid) { safeError(exchange,400,"INVALID_REQUEST"); }
        catch (Exception failure) { safeError(exchange,500,"LOCAL_OPERATION_FAILED"); }
        finally { exchange.close(); }
    }
    private boolean trustedRequest(HttpExchange exchange) {
        var h = exchange.getRequestHeaders();
        if (!exchange.getRemoteAddress().getAddress().isLoopbackAddress() || h.get("Host") == null || h.get("Host").size()!=1
                || !origin.substring(7).equals(h.getFirst("Host"))) return false;
        String other = h.getFirst("Origin"), site = h.getFirst("Sec-Fetch-Site");
        return (other == null || other.equals(origin)) && (site == null || site.equals("same-origin") || site.equals("none"));
    }
    private void events(HttpExchange exchange) throws IOException, InterruptedException {
        if (!streams.tryAcquire()) { reply(exchange,429,"text/plain","Too many streams"); return; }
        try {
            exchange.getResponseHeaders().set("Content-Type","text/event-stream; charset=utf-8");
            exchange.sendResponseHeaders(200,0);
            var out = exchange.getResponseBody(); long previous = -1;
            while (!Thread.currentThread().isInterrupted()) {
                var state = session.snapshot(); long current = ((Number)state.get("version")).longValue();
                String event = current != previous ? "id: " + current + "\nevent: state\ndata: " + UiProtocol.json(state) + "\n\n" : ": heartbeat\n\n";
                out.write(event.getBytes(StandardCharsets.UTF_8)); out.flush(); previous = current;
                session.awaitChange(previous,10000);
            }
        } finally { streams.release(); }
    }
    private void file(HttpExchange exchange, String id) throws IOException {
        if (!files.tryAcquire()) { reply(exchange,429,"text/plain","Too many file transfers"); return; }
        try {
            Path file = session.file(id);
            exchange.getResponseHeaders().set("Content-Type","video/mp4");
            exchange.getResponseHeaders().set("Content-Disposition","attachment; filename=video.mp4");
            exchange.sendResponseHeaders(200,Files.size(file));
            try (var input = Files.newInputStream(file)) { input.transferTo(exchange.getResponseBody()); }
        } finally { files.release(); }
    }
    private void preview(HttpExchange exchange, String id) throws IOException {
        if (!files.tryAcquire()) { reply(exchange,429,"text/plain","Too many file transfers"); return; }
        try {
            // Bounded memory even for an original-quality, long clip. One byte range supports seeking.
            // Open once: a replacement must not mix two generations in the same response.
            try (var input = session.openPreview(id)) {
                long size = input.size();
                if (size < 1) throw new IOException("INVALID_PREVIEW_SIZE");
                String range = exchange.getRequestHeaders().getFirst("Range");
                long start = 0, end = size - 1;
                if (range != null) {
                    try {
                        if (!range.matches("bytes=[0-9]*-[0-9]*")) throw new IllegalArgumentException();
                        String[] parts = range.substring(6).split("-", -1);
                        if (parts[0].isEmpty()) {
                            long suffix = Long.parseLong(parts[1]);
                            if (suffix < 1) throw new IllegalArgumentException();
                            start = Math.max(0, size - suffix);
                        } else {
                            start = Long.parseLong(parts[0]);
                            if (!parts[1].isEmpty()) end = Math.min(end, Long.parseLong(parts[1]));
                        }
                        if (start >= size || end < start) throw new IllegalArgumentException();
                    } catch (IllegalArgumentException invalid) {
                        exchange.getResponseHeaders().set("Content-Range", "bytes */" + size);
                        reply(exchange, 416, "text/plain", "Invalid range"); return;
                    }
                }
                var headers = exchange.getResponseHeaders();
                headers.set("Content-Type", "video/mp4"); headers.set("Content-Disposition", "inline");
                headers.set("Accept-Ranges", "bytes");
                if (range != null) headers.set("Content-Range", "bytes " + start + "-" + end + "/" + size);
                long remaining = end - start + 1;
                exchange.sendResponseHeaders(range == null ? 200 : 206, remaining);
                input.position(start);
                var buffer = java.nio.ByteBuffer.allocate(64 * 1024);
                while (remaining > 0) {
                    buffer.clear().limit((int)Math.min(buffer.capacity(), remaining));
                    int count = input.read(buffer);
                    if (count < 0) throw new EOFException("PREVIEW_TRUNCATED");
                    exchange.getResponseBody().write(buffer.array(), 0, count); remaining -= count;
                }
            }
        } finally { files.release(); }
    }
    private static String string(Map<String,Object> values,String key) {
        if (!(values.get(key) instanceof String s)) throw new IllegalArgumentException(); return s;
    }
    private static long millis(Map<String,Object> request) {
        Object value = request.get("durationMillis");
        if (!(value instanceof Number)) throw new IllegalArgumentException("INVALID_PREVIEW_DURATION");
        try { return new java.math.BigDecimal(value.toString()).longValueExact(); }
        catch (ArithmeticException invalid) { throw new IllegalArgumentException("INVALID_PREVIEW_DURATION"); }
    }
    private static void json(HttpExchange e,int status,Object value) throws IOException { reply(e,status,"application/json",UiProtocol.json(value)); }
    private static void reply(HttpExchange e,int status,String type,String text) throws IOException { reply(e,status,type,text.getBytes(StandardCharsets.UTF_8)); }
    private static void reply(HttpExchange e,int status,String type,byte[] body) throws IOException {
        e.getResponseHeaders().set("Content-Type",type+"; charset=utf-8"); e.sendResponseHeaders(status,body.length); e.getResponseBody().write(body);
    }
    private static void safeError(HttpExchange e,int code,String reason) { try { json(e,code,Map.of("error",reason)); } catch (IOException ignored) {} }
    @Override public void close() { server.stop(0); session.close(); executor.shutdownNow(); }
}
