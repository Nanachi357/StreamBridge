package dev.streambridge.spike;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/** Loopback-only SOCKS5 CONNECT gateway. TLS stays end-to-end; no URLs, headers or bodies retained. */
final class BrowserNetworkProxy implements AutoCloseable {
    @FunctionalInterface interface Destinations {
        List<InetSocketAddress> resolve(String host, int port) throws IOException;
    }
    record Limits(int connections, int handshakeMs, int connectMs, int idleMs) {
        Limits {
            if (connections < 1 || connections > 64 || handshakeMs < 1 || handshakeMs > 10_000
                    || connectMs < 1 || connectMs > 5_000 || idleMs < 1 || idleMs > 60_000)
                throw new IllegalArgumentException("Invalid proxy limits");
        }
    }
    record Snapshot(long accepted, long rejected, int active) { }
    private final ServerSocket listener;
    private final Destinations destinations;
    private final Limits limits;
    private final Semaphore capacity;
    private final Set<Session> sessions = new HashSet<>();
    private final Object lifecycle = new Object();
    private final ScheduledThreadPoolExecutor deadlines = new ScheduledThreadPoolExecutor(1,
            Thread.ofPlatform().daemon().name("browser-proxy-deadline").factory());
    private final AtomicLong accepted = new AtomicLong(), rejected = new AtomicLong();
    private volatile boolean closed;
    private final Thread acceptor;

    BrowserNetworkProxy() throws IOException {
        this(BrowserNetworkProxy::publicDestinations, new Limits(64, 10_000, 5_000, 60_000));
    }

    // Test-only exact owned origins. No CLI switch or application setting allows private addresses.
    BrowserNetworkProxy(Destinations destinations, Limits limits) throws IOException {
        this.destinations = Objects.requireNonNull(destinations);
        this.limits = Objects.requireNonNull(limits);
        capacity = new Semaphore(limits.connections());
        listener = new ServerSocket();
        listener.bind(new InetSocketAddress(InetAddress.getByAddress(new byte[]{127, 0, 0, 1}), 0), 32);
        deadlines.setRemoveOnCancelPolicy(true);
        acceptor = Thread.ofPlatform().daemon().name("browser-proxy-accept").start(this::accept);
    }

    static List<InetSocketAddress> publicDestinations(String host, int port) throws IOException {
        return publicDestinations(host, port, InetAddress::getAllByName);
    }

    static List<InetSocketAddress> publicDestinations(String host, int port, BoundedResourceFetcher.Lookup lookup) throws IOException {
        if (port != 80 && port != 443) throw new IOException("Destination blocked");
        URI uri;
        try { uri = new URI("https", null, host, port, null, null, null); }
        catch (URISyntaxException invalid) { throw new IOException("Destination blocked"); }
        // Reject ALL answers if even one is private; never connect via a second DNS resolution.
        return Arrays.stream(BoundedResourceFetcher.publicAddresses(uri, lookup))
                .sorted(Comparator.comparingInt(address -> address.getAddress().length))
                .map(address -> new InetSocketAddress(address, port)).toList();
    }

    String endpoint() {
        if (closed) throw new IllegalStateException("Browser network gateway closed");
        return "socks5://127.0.0.1:" + listener.getLocalPort();
    }

    int port() { return listener.getLocalPort(); }
    Snapshot snapshot() {
        synchronized (lifecycle) { return new Snapshot(accepted.get(), rejected.get(), sessions.size()); }
    }

    private void accept() {
        try {
            while (!closed) {
                Socket client = listener.accept();
                if (!client.getInetAddress().isLoopbackAddress() || !capacity.tryAcquire()) {
                    rejected.incrementAndGet(); closeSocket(client); continue;
                }
                Session session = new Session(client);
                synchronized (lifecycle) {
                    if (closed) { session.close(); capacity.release(); break; }
                    sessions.add(session);
                }
                accepted.incrementAndGet();
                Thread.startVirtualThread(() -> serve(session));
            }
        } catch (IOException failure) {
            // An unavailable gateway must close existing tunnels, never switch to direct access.
        } finally { close(); }
    }

    private void serve(Session session) {
        ScheduledFuture<?> deadline = null;
        boolean established = false;
        try {
            deadline = deadlines.schedule(session::close, limits.handshakeMs(), TimeUnit.MILLISECONDS);
            Socket client = session.client;
            client.setSoTimeout(limits.handshakeMs());
            DataInputStream input = new DataInputStream(client.getInputStream());
            OutputStream output = client.getOutputStream();
            if (input.readUnsignedByte() != 5) return;
            int methods = input.readUnsignedByte();
            boolean anonymous = false;
            for (int i = 0; i < methods; i++) anonymous |= input.readUnsignedByte() == 0;
            output.write(new byte[]{5, (byte) (anonymous ? 0 : 255)}); output.flush();
            if (!anonymous) return;
            int version = input.readUnsignedByte(), command = input.readUnsignedByte(), reserved = input.readUnsignedByte();
            if (version != 5 || reserved != 0 || command != 1) { reply(output, 7); return; }
            String host = switch (input.readUnsignedByte()) {
                case 1 -> InetAddress.getByAddress(readExact(input, 4)).getHostAddress();
                case 4 -> InetAddress.getByAddress(readExact(input, 16)).getHostAddress();
                case 3 -> domain(readExact(input, input.readUnsignedByte()));
                default -> throw new IOException("Unsupported address type");
            };
            int port = input.readUnsignedShort();
            List<InetSocketAddress> addresses;
            try { addresses = destinations.resolve(host, port); }
            catch (IOException | IllegalArgumentException blocked) {
                rejected.incrementAndGet(); reply(output, 2); return;
            }
            if (addresses == null || addresses.isEmpty() || addresses.size() > 16)
                throw new IOException("Invalid destination results");
            Socket upstream = null;
            for (InetSocketAddress address : addresses) {
                if (address == null || address.isUnresolved()) throw new IOException("Unpinned destination");
                Socket attempt = session.newUpstream(); // Checks close/deadline AFTER possibly blocking DNS.
                try { attempt.connect(address, limits.connectMs()); upstream = attempt; break; }
                catch (IOException unavailable) { closeSocket(attempt); }
            }
            if (upstream == null) { reply(output, 4); return; }
            reply(output, 0);
            established = true;
            deadline.cancel(false);
            client.setSoTimeout(limits.idleMs()); upstream.setSoTimeout(limits.idleMs());
            Socket remote = upstream;
            Thread.startVirtualThread(() -> {
                try { copy(client.getInputStream(), remote.getOutputStream()); remote.shutdownOutput(); }
                catch (IOException ended) { session.close(); }
            });
            copy(remote.getInputStream(), output);
        } catch (IOException | RuntimeException ended) {
            if (!established) rejected.incrementAndGet();
            // Do not log peer-controlled bytes, destinations, or exception messages.
        } finally {
            if (deadline != null) deadline.cancel(false);
            session.close();
            synchronized (lifecycle) { sessions.remove(session); }
            capacity.release();
        }
    }

    private static byte[] readExact(DataInputStream input, int size) throws IOException {
        byte[] bytes = input.readNBytes(size);
        if (bytes.length != size) throw new EOFException();
        return bytes;
    }

    private static String domain(byte[] bytes) throws IOException {
        if (bytes.length == 0) throw new IOException("Empty host");
        for (byte value : bytes) {
            int c = value & 255;
            if (!(c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || c == '-' || c == '.'))
                throw new IOException("Invalid host");
        }
        return new String(bytes, StandardCharsets.US_ASCII);
    }

    private static void reply(OutputStream output, int status) throws IOException {
        output.write(new byte[]{5, (byte) status, 0, 1, 0, 0, 0, 0, 0, 0}); output.flush();
    }

    private static void copy(InputStream input, OutputStream output) throws IOException {
        byte[] buffer = new byte[16_384];
        for (int count; (count = input.read(buffer)) != -1;) { output.write(buffer, 0, count); output.flush(); }
    }

    private static void closeSocket(Socket socket) {
        if (socket != null) try { socket.close(); } catch (IOException ignored) { }
    }

    private static final class Session {
        final Socket client;
        private Socket upstream;
        private boolean closed;
        Session(Socket client) { this.client = client; }
        synchronized Socket newUpstream() throws IOException {
            if (closed) throw new IOException("Session closed");
            upstream = new Socket(Proxy.NO_PROXY); // Never inherit JVM/system proxy configuration.
            return upstream;
        }
        synchronized void close() { closed = true; closeSocket(client); closeSocket(upstream); }
    }

    @Override public void close() {
        synchronized (lifecycle) {
            if (closed) return;
            closed = true;
            try { listener.close(); } catch (IOException ignored) { }
            sessions.forEach(Session::close);
        }
        deadlines.shutdownNow();
    }
}
