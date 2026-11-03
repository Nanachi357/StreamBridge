package dev.streambridge.spike;

import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.ManagedHttpClientConnectionFactory;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.config.Http1Config;
import org.apache.hc.core5.io.CloseMode;
import org.apache.hc.core5.util.Timeout;
import java.io.*;
import java.net.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** One bounded HTTP resource, not a complete media downloader. Test scope; no browser credentials. */
final class BoundedResourceFetcher implements AutoCloseable {
    enum Failure { BLOCKED_ADDRESS, INVALID_URI, REDIRECT_LIMIT, REDIRECT_LOOP, DOWNGRADE,
        HTTP_STATUS, ENCODED_BODY, TOO_LARGE, TIMEOUT, INTERRUPTED, CANCELLED, IO, BUSY, CLOSED, REQUEST_LIMIT }
    /** Shared across resources; every redirect hop needs a permit too. */
    static final class RequestBudget {
        private final int maximum;
        private final AtomicInteger remaining;
        RequestBudget(int maximum) {
            if (maximum < 1 || maximum > 10000) throw new IllegalArgumentException("Invalid request budget");
            this.maximum = maximum;
            remaining = new AtomicInteger(maximum);
        }
        void claim() throws FetchException {
            if (remaining.getAndUpdate(value -> value > 0 ? value - 1 : 0) == 0) throw new FetchException(Failure.REQUEST_LIMIT);
        }
        int used() { return maximum - remaining.get(); }
    }
    static final class FetchException extends IOException {
        private final Failure failure;
        private final String diagnosticCode;
        FetchException(Failure failure) { this(failure, failure.name()); }
        private FetchException(Failure failure, String diagnosticCode) {
            super(diagnosticCode); this.failure = failure; this.diagnosticCode = diagnosticCode;
        }
        Failure failure() { return failure; }
        String diagnosticCode() { return diagnosticCode; }
    }
    /** Classify known types only: never copy messages, class names, causes or signed URIs. */
    static FetchException sanitizeFailure(Throwable failure) {
        var causes = new ArrayList<Throwable>();
        var seen = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
        for (Throwable c = failure; c != null && causes.size() < 16 && seen.add(c); c = c.getCause()) causes.add(c);
        String detail;
        if (causes.stream().anyMatch(c -> c instanceof java.security.cert.CertificateException
                || c instanceof java.security.cert.CertPathValidatorException
                || c instanceof java.security.cert.CertPathBuilderException
                || c instanceof javax.net.ssl.SSLPeerUnverifiedException)) detail = "TLS_CERTIFICATE";
        else if (causes.stream().anyMatch(c -> c instanceof javax.net.ssl.SSLHandshakeException)) detail = "TLS_HANDSHAKE";
        else if (causes.stream().anyMatch(c -> c instanceof javax.net.ssl.SSLException)) detail = "TLS";
        else if (causes.stream().anyMatch(c -> c instanceof UnknownHostException)) detail = "DNS";
        else if (causes.stream().anyMatch(c -> c instanceof SocketTimeoutException)) return new FetchException(Failure.TIMEOUT);
        else if (causes.stream().anyMatch(c -> c instanceof NoRouteToHostException)) detail = "UNREACHABLE";
        else if (causes.stream().anyMatch(c -> c instanceof ConnectException)) detail = "CONNECT";
        else if (causes.stream().anyMatch(c -> c instanceof SocketException)) detail = "SOCKET";
        else if (causes.stream().anyMatch(c -> c instanceof EOFException
                || c instanceof org.apache.hc.core5.http.ConnectionClosedException)) detail = "CONNECTION_CLOSED";
        else if (causes.stream().anyMatch(c -> c instanceof org.apache.hc.core5.http.ProtocolException)) detail = "HTTP_PROTOCOL";
        else if (causes.stream().anyMatch(c -> c instanceof LinkageError)) detail = "DEPENDENCY_LINKAGE";
        else detail = "UNCLASSIFIED";
        return new FetchException(Failure.IO, "IO_" + detail);
    }
    record Limits(int maxBytes, int maxRedirects, Duration total, Duration connect, Duration idle) {
        Limits {
            if (maxBytes < 1 || maxBytes > 32 * 1024 * 1024 || maxRedirects < 0 || maxRedirects > 5) throw new IllegalArgumentException("Invalid fetch limits");
            for (Duration value : List.of(total, connect, idle)) {
                if (value.toMillis() < 1 || value.compareTo(Duration.ofMinutes(2)) > 0) throw new IllegalArgumentException("Invalid timeout");
            }
        }
    }
    record Resource(URI finalUri, byte[] bytes) {
        Resource { bytes = bytes.clone(); }
        @Override public byte[] bytes() { return bytes.clone(); }
        int size() { return bytes.length; }
        @Override public String toString() { return "Resource[bytes=" + bytes.length + ", URI omitted]"; }
    }
    @FunctionalInterface interface Lookup { InetAddress[] resolve(String host) throws IOException; }
    @FunctionalInterface interface DestinationPolicy { InetAddress[] resolve(URI uri) throws IOException; }

    private final DestinationPolicy destinations;
    private final AtomicReference<Attempt> active = new AtomicReference<>();
    private final AtomicBoolean closed = new AtomicBoolean();

    BoundedResourceFetcher() { this(uri -> publicAddresses(uri, InetAddress::getAllByName)); }
    // Package-private injection for EXACT owned fixture origins, never a CLI allow-private switch.
    BoundedResourceFetcher(DestinationPolicy destinations) { this.destinations = Objects.requireNonNull(destinations); }

    static InetAddress[] publicAddresses(URI uri, Lookup lookup) throws IOException {
        try { validateSyntax(uri); PublicWebPolicy.host(uri); }
        catch (IllegalArgumentException invalid) { throw new FetchException(Failure.INVALID_URI); }
        InetAddress[] addresses = lookup.resolve(uri.getHost());
        if (addresses == null || addresses.length == 0 || addresses.length > 16) throw new FetchException(Failure.BLOCKED_ADDRESS);
        for (InetAddress address : addresses) {
            if (address == null || !PublicWebPolicy.isPublic(address.getAddress())) throw new FetchException(Failure.BLOCKED_ADDRESS);
        }
        return addresses.clone();
    }

    Resource fetch(URI uri, Limits limits) throws FetchException {
        return fetch(uri, limits, new RequestBudget(6));
    }

    Resource fetch(URI uri, Limits limits, RequestBudget requests) throws FetchException {
        Objects.requireNonNull(limits, "limits");
        Objects.requireNonNull(requests, "requests");
        if (closed.get()) throw new FetchException(Failure.CLOSED);
        Attempt attempt = new Attempt();
        if (!active.compareAndSet(null, attempt)) throw new FetchException(Failure.BUSY);
        try {
            FutureTask<Resource> future = new FutureTask<>(() -> {
                try { return perform(uri, limits, requests, attempt); }
                finally { active.compareAndSet(attempt, null); }
            });
            // A dedicated daemon per attempt avoids interrupting a later request on a reused thread.
            attempt.thread = Thread.ofPlatform().daemon().name("bounded-resource-fetch").unstarted(future);
            if (closed.get()) attempt.cancel();
            attempt.thread.start();
            return future.get(limits.total().toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException deadline) {
            attempt.cancel(); throw new FetchException(Failure.TIMEOUT);
        } catch (InterruptedException interrupted) {
            attempt.cancel(); Thread.currentThread().interrupt(); throw new FetchException(Failure.INTERRUPTED);
        } catch (ExecutionException failure) {
            if (failure.getCause() instanceof FetchException expected) throw expected;
            throw sanitizeFailure(failure.getCause());
        }
    }

    private Resource perform(URI initial, Limits limits, RequestBudget requests, Attempt attempt) throws IOException {
        URI uri = initial;
        Set<URI> visited = new HashSet<>();
        for (int hop = 0; ; hop++) {
            attempt.check();
            validateSyntax(uri);
            if (!visited.add(uri)) throw new FetchException(Failure.REDIRECT_LOOP);
            InetAddress[] addresses = destinations.resolve(uri).clone();
            attempt.check(); // DNS can be uninterruptible; NEVER connect after a timed-out lookup returns.
            requests.claim(); // Reserve BEFORE opening a connection, including redirect hops.
            String host = uri.getHost();
            DnsResolver pinned = new DnsResolver() {
                public InetAddress[] resolve(String requested) throws UnknownHostException {
                    if (!host.equalsIgnoreCase(requested)) throw new UnknownHostException("Unexpected host");
                    return addresses.clone();
                }
                public String resolveCanonicalHostname(String requested) throws UnknownHostException {
                    if (!host.equalsIgnoreCase(requested)) throw new UnknownHostException("Unexpected host");
                    return host;
                }
            };
            var factory = ManagedHttpClientConnectionFactory.builder().http1Config(Http1Config.custom()
                    .setMaxHeaderCount(64).setMaxLineLength(8192).setMaxEmptyLineCount(4).build()).build();
            var manager = PoolingHttpClientConnectionManagerBuilder.create().setDnsResolver(pinned)
                    .setConnectionFactory(factory).setDefaultConnectionConfig(ConnectionConfig.custom()
                            .setConnectTimeout(Timeout.ofMilliseconds(limits.connect().toMillis()))
                            .setSocketTimeout(Timeout.ofMilliseconds(limits.idle().toMillis())).build()).build();
            var client = HttpClients.custom().setConnectionManager(manager)
                    .disableAutomaticRetries().disableRedirectHandling().disableCookieManagement()
                    .disableContentCompression().disableAuthCaching()
                    .setConnectionReuseStrategy((request, response, context) -> false)
                    .setDefaultRequestConfig(RequestConfig.custom().setAuthenticationEnabled(false)
                            .setResponseTimeout(Timeout.ofMilliseconds(limits.idle().toMillis())).build()).build();
            HttpGet request = new HttpGet(uri);
            request.setHeader("Accept-Encoding", "identity");
            attempt.request.set(request);
            org.apache.hc.core5.http.ClassicHttpResponse response = null;
            try {
                attempt.check();
                // URI keeps original host for Host/SNI/certificate checks. Only socket DNS is pinned.
                response = client.executeOpen(null, request, null);
                int status = response.getCode();
                if (Set.of(301, 302, 303, 307, 308).contains(status)) {
                    if (hop >= limits.maxRedirects()) throw new FetchException(Failure.REDIRECT_LIMIT);
                    var locations = response.getHeaders("Location");
                    if (locations.length != 1) throw new FetchException(Failure.INVALID_URI);
                    uri = redirectTarget(uri, locations[0].getValue());
                    // Body is NOT consumed; next iteration revalidates/resolves destination.
                    continue;
                }
                if (status != 200) throw new FetchException(Failure.HTTP_STATUS,
                        status >= 100 && status <= 599 ? "HTTP_STATUS_" + status : "HTTP_STATUS");
                for (var encoding : response.getHeaders("Content-Encoding")) {
                    if (!encoding.getValue().equalsIgnoreCase("identity")) throw new FetchException(Failure.ENCODED_BODY);
                }
                var entity = response.getEntity();
                if (entity == null) return new Resource(uri, new byte[0]);
                if (entity.getContentLength() > limits.maxBytes()) throw new FetchException(Failure.TOO_LARGE);
                InputStream input = entity.getContent();
                var output = new ByteArrayOutputStream(Math.min(limits.maxBytes(), 8192));
                byte[] buffer = new byte[8192];
                while (true) {
                    attempt.check();
                    int read = input.read(buffer, 0, Math.min(buffer.length, limits.maxBytes() - output.size() + 1));
                    if (read == -1) break;
                    if (read > limits.maxBytes() - output.size()) throw new FetchException(Failure.TOO_LARGE);
                    output.write(buffer, 0, read);
                }
                attempt.check();
                return new Resource(uri, output.toByteArray());
            } catch (java.net.SocketTimeoutException timeout) {
                attempt.check();
                throw new FetchException(Failure.TIMEOUT);
            } catch (IOException failure) {
                attempt.check();
                throw failure;
            } finally {
                // Abort BEFORE closing streams/entities: close can otherwise drain an oversized body.
                request.cancel();
                client.close(CloseMode.IMMEDIATE);
                if (response != null) {
                    // The transport is already aborted. Entity cleanup may report a truncated
                    // body; that must not replace the result or original policy failure.
                    try { response.close(); } catch (IOException ignoredAfterAbort) { }
                }
                attempt.request.compareAndSet(request, null);
            }
        }
    }

    static URI redirectTarget(URI from, String location) throws FetchException {
        URI next;
        try { next = from.resolve(location); }
        catch (IllegalArgumentException | NullPointerException invalid) { throw new FetchException(Failure.INVALID_URI); }
        validateSyntax(next);
        if ("https".equalsIgnoreCase(from.getScheme()) && "http".equalsIgnoreCase(next.getScheme())) throw new FetchException(Failure.DOWNGRADE);
        return next;
    }

    private static void validateSyntax(URI uri) throws FetchException {
        if (uri == null || uri.toString().length() > 8192 || uri.getHost() == null || uri.getUserInfo() != null
                || uri.getFragment() != null || !("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))) {
            throw new FetchException(Failure.INVALID_URI);
        }
    }

    private static final class Attempt {
        final AtomicBoolean cancelled = new AtomicBoolean();
        final AtomicReference<HttpGet> request = new AtomicReference<>();
        volatile Thread thread;
        void cancel() {
            cancelled.set(true);
            HttpGet current = request.get(); if (current != null) current.cancel();
            Thread running = thread; if (running != null) running.interrupt();
        }
        void check() throws FetchException {
            if (cancelled.get() || Thread.currentThread().isInterrupted()) throw new FetchException(Failure.CANCELLED);
        }
    }
    @Override public void close() {
        closed.set(true);
        Attempt attempt = active.get(); if (attempt != null) attempt.cancel();
        // Never wait indefinitely for an OS DNS call. Its daemon stays counted as active until it exits.
    }
}
