package dev.streambridge.spike;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.net.URI;
import static org.junit.jupiter.api.Assertions.*;

class HlsVodPlaylistTest {
    private static final URI BASE = URI.create("https://media.example.org/video/index.m3u8?secret=parent");
    static final String VOD = """
            #EXTM3U
            #EXT-X-VERSION:3
            #EXT-X-TARGETDURATION:2
            #EXT-X-MEDIA-SEQUENCE:0
            #EXT-X-PLAYLIST-TYPE:VOD
            #EXTINF:2.0,
            segment-000.ts
            #EXTINF:1.5,
            https://cdn.example.org/segment-001.ts?secret=child
            #EXT-X-ENDLIST
            """;

    @Test void enumeratesOrderedSegmentsAndDeclaredDurationWithoutCopyingParentQuery() {
        var parsed = parse(VOD);
        assertEquals(2, parsed.segments().size());
        assertEquals(new BigDecimal("3.5"), parsed.durationSeconds());
        assertEquals("https://media.example.org/video/segment-000.ts", parsed.segments().get(0).uri().toString());
        assertEquals("https://cdn.example.org/segment-001.ts?secret=child", parsed.segments().get(1).uri().toString());
        assertFalse(parsed.toString().contains("secret"));
        assertFalse(parsed.segments().toString().contains("secret"));
        assertThrows(UnsupportedOperationException.class, () -> parsed.segments().clear());
    }

    @Test void requiresAFinitePlaylistButDoesNotRequireAnExplicitVodTypeTag() {
        assertEquals(2, parse(VOD.replace("#EXT-X-PLAYLIST-TYPE:VOD\n", "")).segments().size());
        rejected(VOD.replace("#EXT-X-ENDLIST", ""));
        rejected(VOD.replace("PLAYLIST-TYPE:VOD", "PLAYLIST-TYPE:EVENT"));
    }

    @Test void parsesExplicitLegacyCacheDirectiveWithoutChangingSegments() {
        assertEquals(HlsVodPlaylist.CacheDirective.UNSPECIFIED, parse(VOD).cacheDirective());
        for (String value : new String[]{"YES", "NO"}) {
            var parsed = parse(VOD.replace("#EXT-X-VERSION:3", "#EXT-X-VERSION:3\n#EXT-X-ALLOW-CACHE:" + value));
            assertEquals(HlsVodPlaylist.CacheDirective.valueOf(value), parsed.cacheDirective());
            assertEquals(parse(VOD).segments(), parsed.segments());
            assertEquals(parse(VOD).durationSeconds(), parsed.durationSeconds());
        }
    }

    @Test void rejectsMalformedDuplicateAndConflictingLegacyCacheDirectives() {
        for (String value : new String[]{"", "yes", "no", "1", "YES,NO", "YES:extra", " YES"}) {
            rejected(VOD.replace("#EXT-X-VERSION:3", "#EXT-X-VERSION:3\n#EXT-X-ALLOW-CACHE:" + value));
        }
        rejected(VOD.replace("#EXT-X-VERSION:3", "#EXT-X-VERSION:3\n#EXT-X-ALLOW-CACHE"));
        for (String value : new String[]{"YES", "NO"}) {
            rejected(VOD.replace("#EXT-X-VERSION:3", "#EXT-X-VERSION:3\n#EXT-X-ALLOW-CACHE:YES\n#EXT-X-ALLOW-CACHE:" + value));
        }
        rejected(VOD + "#EXT-X-ALLOW-CACHE:YES\n");
    }

    @Test void retainsDeclaredCompatibilityVersionAndUsesDefaultOne() {
        assertEquals(1, parse(VOD.replace("#EXT-X-VERSION:3\n", "")).protocolVersion());
        for (int version = 1; version <= 7; version++) {
            assertEquals(version, parse(VOD.replace("#EXT-X-VERSION:3", "#EXT-X-VERSION:" + version)).protocolVersion());
        }
    }

    @Test void rejectsFeaturesNotRepresentedInThePlanInsteadOfIgnoringThem() {
        for (String tag : new String[]{"#EXT-X-KEY:METHOD=NONE", "#EXT-X-KEY:METHOD=AES-128,URI=\"secret-key\"",
                "#EXT-X-MAP:URI=\"init.mp4\"", "#EXT-X-BYTERANGE:100@0", "#EXT-X-DISCONTINUITY",
                "#EXT-X-GAP", "#EXT-X-PART:DURATION=1,URI=\"part\"", "#EXT-X-DEFINE:NAME=\"a\",VALUE=\"b\"",
                "#EXT-X-UNKNOWN:URI=\"secret\"", "#EXT-X-STREAM-INF:BANDWIDTH=1"}) {
            rejected(VOD.replace("#EXTINF:2.0,", tag + "\n#EXTINF:2.0,"));
        }
    }

    @Test void rejectsMissingDuplicateOrInvalidStructuralFields() {
        for (String body : new String[]{VOD.replace("#EXTM3U", "garbage"), VOD.replace("#EXT-X-TARGETDURATION:2\n", ""),
                VOD.replace("#EXT-X-VERSION:3", "#EXT-X-VERSION:3\n#EXT-X-VERSION:3"),
                VOD.replace("#EXTINF:2.0,", "#EXTINF:NaN,"), VOD.replace("#EXTINF:2.0,", "#EXTINF:-1,"),
                VOD.replace("#EXTINF:2.0,", "#EXTINF:0,"), VOD.replace("#EXTINF:2.0,", "#EXTINF:2.0"),
                VOD.replace("segment-000.ts", "# a missing segment"), VOD.replace("#EXTINF:2.0,\n", ""),
                VOD.replace("#EXTINF:2.0,", "#EXTINF:2.0,\n#EXTINF:2.0,"), VOD + "#EXTINF:1,\nlate.ts\n",
                VOD.replace("#EXT-X-VERSION:3", "#EXT-X-VERSION:99")}) rejected(body);
    }

    @Test void validatesRoundedDurationAgainstTarget() {
        parse(VOD.replace("#EXTINF:2.0,", "#EXTINF:2.49,"));
        rejected(VOD.replace("#EXTINF:2.0,", "#EXTINF:2.5,"));
    }

    @Test void rejectsUnsafeUriSyntaxWithoutPerformingDnsOrLeakingInput() {
        for (String uri : new String[]{"file:///secret", "https://user:secret@example.org/s.ts", "http://localhost/secret",
                "https://example.org:8080/s.ts", "https://example.org/s.ts#secret", "{$secret}/s.ts", "broken\\secret"}) {
            rejected(VOD.replace("segment-000.ts", uri));
        }
    }

    @Test void boundsInputAndSegmentCountAndPreservesRepeatedSegmentsInOrder() {
        rejected("#EXTM3U\n" + "#comment\n".repeat(40000));
        rejected("#EXTM3U\n#EXT-X-TARGETDURATION:1\n" + "#EXTINF:1,\na\n".repeat(10001) + "#EXT-X-ENDLIST\n");
        var repeated = parse(VOD.replace("https://cdn.example.org/segment-001.ts?secret=child", "segment-000.ts"));
        assertEquals(2, repeated.segments().size());
        assertEquals(repeated.segments().get(0).uri(), repeated.segments().get(1).uri());
    }

    private HlsVodPlaylist parse(String body) { return HlsVodPlaylist.parse(BASE, body); }
    private void rejected(String body) {
        var failure = assertThrows(IllegalArgumentException.class, () -> parse(body));
        assertEquals("UNSUPPORTED_OR_INVALID_VOD_PLAYLIST", failure.getMessage());
        assertNull(failure.getCause());
    }
}
