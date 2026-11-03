package dev.streambridge.spike;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;

/** Public URL/address rules; preflight alone is not a network boundary. Pin connections separately. */
final class PublicWebPolicy {
    static String host(URI uri) {
        String scheme = uri.getScheme();
        String host = uri.getHost();
        if (!("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme)) || host == null
                || uri.getUserInfo() != null || (uri.getPort() != -1 && uri.getPort() != 80 && uri.getPort() != 443)) {
            throw new IllegalArgumentException("Unsupported URL scheme, authority or port");
        }
        host = host.toLowerCase(Locale.ROOT);
        if (host.endsWith(".") || !host.contains(".") && !host.contains(":")) throw new IllegalArgumentException("Non-public host syntax");
        if (host.equals("localhost") || host.endsWith(".localhost") || host.endsWith(".local") || host.endsWith(".internal")) {
            throw new IllegalArgumentException("Local host name");
        }
        return host;
    }

    static void check(URI uri) throws UnknownHostException {
        InetAddress[] addresses = InetAddress.getAllByName(host(uri));
        if (addresses.length == 0) throw new IllegalArgumentException("No resolved address");
        for (InetAddress address : addresses) {
            if (!isPublic(address.getAddress())) throw new IllegalArgumentException("Non-public destination");
        }
    }

    static boolean isPublic(byte[] bytes) {
        if (bytes == null || bytes.length != 4 && bytes.length != 16) return false;
        int a = bytes[0] & 255, b = bytes[1] & 255;
        if (bytes.length == 4) {
            int c = bytes[2] & 255;
            return !(a == 0 || a == 10 || a == 127 || a >= 224 || a == 169 && b == 254
                    || a == 100 && b >= 64 && b <= 127 || a == 172 && b >= 16 && b <= 31
                    || a == 192 && (b == 168 || b == 0 && (c == 0 || c == 2) || b == 88 && c == 99)
                    || a == 198 && (b == 18 || b == 19 || b == 51 && c == 100)
                    || a == 203 && b == 0 && c == 113);
        }
        // Only global-unicast IPv6, conservatively excluding special/transition/documentation prefixes.
        return bytes.length == 16 && (a & 0xe0) == 0x20
                && !(a == 0x20 && b == 1 && (bytes[2] & 255) <= 1)
                && !(a == 0x20 && b == 1 && (bytes[2] & 255) == 0x0d && (bytes[3] & 255) == 0xb8)
                && !(a == 0x20 && b == 2) && !(a == 0x3f && b == 0xff);
    }
}
