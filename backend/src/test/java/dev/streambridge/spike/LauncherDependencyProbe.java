package dev.streambridge.spike;

import com.microsoft.playwright.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/** Bounded child process: validates real executable startup without opening a site. */
public final class LauncherDependencyProbe {
    public static void main(String[] args) throws Exception {
        // A separate file avoids mixing diagnostic output with the runtime acknowledgement.
        try (var log = args.length == 1
                ? new PrintWriter(Files.newBufferedWriter(Path.of(args[0]), StandardCharsets.UTF_8), true)
                : new PrintWriter(System.err, true)) {
            int exit = run(log);
            log.println("probeExit=" + exit);
            if (exit != 0) { log.flush(); System.exit(exit); }
        }
        System.out.println("JAVA_RUNTIME=" + System.getProperty("java.home"));
    }

    private static int run(PrintWriter log) {
        log.println("javaHome=" + System.getProperty("java.home"));
        log.println("workingDirectory=" + Path.of("").toAbsolutePath());
        int systemLibrary = WindowsSystemLibrary.verify(log);
        if (systemLibrary != 0) return systemLibrary;
        for (String tool : new String[]{"ffmpeg", "ffprobe"}) {
            try {
                String directory = System.getenv("STREAMBRIDGE_FFMPEG_DIR");
                log.println("tool=" + tool + " directory=" + directory);
                if (directory == null) throw new IOException("STREAMBRIDGE_FFMPEG_DIR is not configured");
                Path executable = Path.of(directory, tool + ".exe");
                log.println("executable=" + executable + " regularFile=" + Files.isRegularFile(executable));
                if (!Files.isRegularFile(executable)) throw new FileNotFoundException(executable.toString());
                runCommand(List.of(executable.toString(), "-version"), Duration.ofSeconds(10), log);
            } catch (Exception failure) {
                failure.printStackTrace(log); log.flush();
                return tool.equals("ffmpeg") ? 21 : 22;
            }
        }
        log.println("chromiumCache=" + System.getenv("PLAYWRIGHT_BROWSERS_PATH"));
        try (var network = new BrowserNetworkProxy();
             var playwright = Playwright.create(new Playwright.CreateOptions().setEnv(Map.of("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1")));
             var browser = ChromiumLaunch.launch(playwright.chromium(), true, network);
             var context = browser.newContext()) {
            log.println("chromiumExecutable=" + ChromiumLaunch.executablePath(playwright.chromium()));
            log.println("chromiumSandboxRequested=true");
            log.println("browserNetworkGateway=public-only-socks5");
            var page = context.newPage(); page.setContent("<title>StreamBridge startup check</title>");
            if (!page.title().equals("StreamBridge startup check")) throw new IllegalStateException();
            try {
                verifyNoDirectWebRtc(page);
                log.println("browserWebRtcDirectReceiverCheck=OK");
            } catch (Exception failure) {
                failure.printStackTrace(log); log.flush(); return 24;
            }
        } catch (Exception failure) {
            failure.printStackTrace(log); log.flush(); return 23;
        }
        return 0;
    }

    /** Behavioral check of this installed binary, not merely the presence of a command-line flag. */
    static void verifyNoDirectWebRtc(Page page) throws IOException {
        var loopback = InetAddress.getByAddress(new byte[]{127, 0, 0, 1});
        try (var udp = new DatagramSocket(new InetSocketAddress(loopback, 0));
             var tcp = new ServerSocket(0, 1, loopback)) {
            Object result = page.evaluate("""
                    async ports => {
                      const peer = new RTCPeerConnection({iceServers: [
                        {urls: 'stun:127.0.0.1:' + ports[0]},
                        {urls: 'turn:127.0.0.1:' + ports[1] + '?transport=tcp',
                         username: 'fixture', credential: 'fixture'}]});
                      try {
                        peer.createDataChannel('fixture');
                        await peer.setLocalDescription(await peer.createOffer());
                        await new Promise(resolve => setTimeout(resolve, 2500));
                        return 'attempted';
                      } finally { peer.close(); }
                    }
                    """, List.of(udp.getLocalPort(), tcp.getLocalPort()));
            if (!"attempted".equals(result)) throw new IOException("WebRTC receiver check incomplete");
            udp.setSoTimeout(300);
            try {
                udp.receive(new DatagramPacket(new byte[2048], 2048));
                throw new IOException("Chromium sent direct WebRTC UDP outside gateway");
            } catch (SocketTimeoutException expected) { }
            tcp.setSoTimeout(300);
            try (var unexpected = tcp.accept()) {
                throw new IOException("Chromium sent direct TURN TCP outside gateway");
            } catch (SocketTimeoutException expected) { }
        }
    }

    /** Drain both streams even after the retained prefix fills, so verbose tools cannot deadlock. */
    static String outputPrefix(InputStream input) throws IOException {
        try (input; var retained = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096]; int count; long total = 0;
            while ((count = input.read(buffer)) != -1) {
                int keep = Math.min(count, 8192 - retained.size());
                if (keep > 0) retained.write(buffer, 0, keep);
                total += count;
            }
            return retained.toString(StandardCharsets.UTF_8) + (total > 8192 ? "\n[truncated]" : "");
        }
    }

    static void runCommand(List<String> command, Duration timeout, PrintWriter log) throws Exception {
        log.println("command=" + command + " timeoutMs=" + timeout.toMillis());
        long started = System.nanoTime();
        Process process = new ProcessBuilder(command).start();
        process.getOutputStream().close();
        log.println("pid=" + process.pid());
        var stdout = new FutureTask<String>(() -> outputPrefix(process.getInputStream()));
        var stderr = new FutureTask<String>(() -> outputPrefix(process.getErrorStream()));
        Thread.startVirtualThread(stdout); Thread.startVirtualThread(stderr);
        try {
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                log.println("outcome=TIMEOUT");
                throw new IOException("Tool exceeded " + timeout.toMillis() + " ms");
            }
            int exit = process.exitValue();
            log.println("exitCode=" + exit + " exitHex=0x" + Integer.toHexString(exit));
            if (exit != 0) throw new IOException("Tool exited with code " + exit);
        } finally {
            if (process.isAlive()) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly(); process.waitFor(3, TimeUnit.SECONDS);
            }
            log.println("elapsedMs=" + Duration.ofNanos(System.nanoTime() - started).toMillis());
            for (var output : Map.of("stdout", stdout, "stderr", stderr).entrySet()) {
                try { log.println(output.getKey() + ":\n" + output.getValue().get(2, TimeUnit.SECONDS)); }
                catch (Exception failure) { log.println(output.getKey() + " capture: " + failure); }
            }
            process.getInputStream().close(); process.getErrorStream().close(); log.flush();
        }
    }
}
