package dev.streambridge.spike;

import org.junit.jupiter.api.Test;
import java.io.*;
import java.net.*;
import java.time.Duration;
import java.util.Map;
import javax.net.ssl.*;
import static org.junit.jupiter.api.Assertions.*;

class NetworkFailureDetailTest {
    private static final String SECRET="https://private.example/media?token=do-not-export";
    @Test void knownTypesAreClassifiedWithoutRetainingAnyRawFailure() {
        var failures=Map.of(
                new UnknownHostException(SECRET), "IO_DNS",
                new SSLHandshakeException(SECRET), "IO_TLS_HANDSHAKE",
                new SSLPeerUnverifiedException(SECRET), "IO_TLS_CERTIFICATE",
                new SSLException(SECRET), "IO_TLS",
                new ConnectException(SECRET), "IO_CONNECT",
                new SocketException(SECRET), "IO_SOCKET",
                new SocketTimeoutException(SECRET), "TIMEOUT",
                new IOException(SECRET), "IO_UNCLASSIFIED",
                new NoClassDefFoundError(SECRET), "IO_DEPENDENCY_LINKAGE");
        failures.forEach((original, code)->{
            var safe=BoundedResourceFetcher.sanitizeFailure(original);
            assertEquals(code,safe.diagnosticCode());
            assertNull(safe.getCause()); assertEquals(0,safe.getSuppressed().length);
            var trace=new StringWriter();safe.printStackTrace(new PrintWriter(trace));
            assertFalse(trace.toString().contains(SECRET));
            assertFalse(trace.toString().contains("do-not-export"));
        });
    }
    @Test void nestedCertificateErrorWinsOverGenericHandshake() {
        var handshake=new SSLHandshakeException(SECRET);
        handshake.initCause(new java.security.cert.CertPathBuilderException(SECRET));
        assertEquals("IO_TLS_CERTIFICATE",BoundedResourceFetcher.sanitizeFailure(handshake).diagnosticCode());
    }
    @Test void causeCycleDoesNotHangClassification() {
        var a=new IOException(SECRET);var b=new IOException(SECRET);a.initCause(b);b.initCause(a);
        assertTimeoutPreemptively(Duration.ofSeconds(1),()->
                assertEquals("IO_UNCLASSIFIED",BoundedResourceFetcher.sanitizeFailure(a).diagnosticCode()));
    }
    @Test void actualFetchPathRetainsSafeDnsDetail() throws Exception {
        try(var fetcher=new BoundedResourceFetcher(uri->{throw new UnknownHostException(SECRET);})) {
            var limits=new BoundedResourceFetcher.Limits(1024,0,Duration.ofSeconds(2),Duration.ofSeconds(1),Duration.ofSeconds(1));
            var failure=assertThrows(BoundedResourceFetcher.FetchException.class,()->fetcher.fetch(URI.create(SECRET),limits));
            assertEquals("IO_DNS",failure.diagnosticCode());assertNull(failure.getCause());
        }
    }
}
