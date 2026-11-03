package dev.streambridge.spike;

import java.net.InetAddress;
import java.net.URI;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PublicWebPolicyTest {
    @Test void malformedAddressBytesAreNotPublic() {
        assertFalse(PublicWebPolicy.isPublic(null));
        assertFalse(PublicWebPolicy.isPublic(new byte[0]));
        assertFalse(PublicWebPolicy.isPublic(new byte[1]));
        assertFalse(PublicWebPolicy.isPublic(new byte[5]));
    }
    @Test void rejectsPrivateReservedAndTransitionAddressesWithoutDns() throws Exception {
        for (String ip : new String[]{"0.0.0.0", "10.0.0.1", "127.0.0.1", "169.254.169.254", "172.16.0.1",
                "192.168.1.1", "100.64.0.1", "192.0.0.1", "192.0.2.1", "198.18.0.1", "198.51.100.1", "203.0.113.1",
                "224.0.0.1", "255.255.255.255", "::1", "::", "fc00::1", "fe80::1", "2001:db8::1", "2002::1", "::ffff:127.0.0.1"}) {
            assertFalse(PublicWebPolicy.isPublic(InetAddress.getByName(ip).getAddress()), ip);
        }
    }
    @Test void permitsOrdinaryPublicAddresses() throws Exception {
        for (String ip : new String[]{"1.1.1.1", "8.8.8.8", "2606:4700:4700::1111"}) {
            assertTrue(PublicWebPolicy.isPublic(InetAddress.getByName(ip).getAddress()), ip);
        }
    }
    @Test void rejectsUnsafeUriForms() {
        for (String value : new String[]{"file:///tmp/video", "ftp://example.org/a", "http://user:pass@example.org/a",
                "http://localhost/a", "http://printer/a", "http://router.local/a", "https://example.org:8080/a"}) {
            assertThrows(IllegalArgumentException.class, () -> PublicWebPolicy.host(URI.create(value)));
        }
        assertEquals("example.org", PublicWebPolicy.host(URI.create("https://example.org/a?token=x")));
    }
}
