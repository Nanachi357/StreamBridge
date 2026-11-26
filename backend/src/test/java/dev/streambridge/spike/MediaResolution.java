package dev.streambridge.spike;

import java.io.IOException;
import java.util.List;

/** Checks declared dimensions against the first video stream actually being saved. */
final class MediaResolution {
    static final class Mismatch extends IOException {
        Mismatch() { super("MEDIA_RESOLUTION_MISMATCH"); }
    }

    static void verify(String expected, List<String> probe) throws IOException {
        if (expected == null || expected.equals("unknown")) return;
        String width = value(probe, "width="), height = value(probe, "height=");
        if (!width.matches("[1-9][0-9]*") || !height.matches("[1-9][0-9]*"))
            throw new IOException("MEDIA_RESOLUTION_UNAVAILABLE");
        if (!expected.equals(width + "x" + height)) throw new Mismatch();
    }

    private static String value(List<String> lines, String prefix) {
        return lines.stream().filter(s -> s.startsWith(prefix)).findFirst()
                .map(s -> s.substring(prefix.length())).orElse("");
    }
}
