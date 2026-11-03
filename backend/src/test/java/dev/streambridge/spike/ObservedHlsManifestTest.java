package dev.streambridge.spike;

import org.junit.jupiter.api.Test;
import java.net.URI;
import static org.junit.jupiter.api.Assertions.*;

class ObservedHlsManifestTest {
    private static final URI BASE = URI.create("https://example.org/clip/master?token=parent-secret");

    @Test void acceptsNoAudioGroupWithoutInventingAudioPresenceOrResolution() {
        var parsed = parse("#EXT-X-STREAM-INF:BANDWIDTH=1000\nlow.m3u8\n");
        var variant = parsed.variants().getFirst();
        assertEquals(ObservedHlsManifest.Kind.MASTER, parsed.kind());
        assertNull(variant.audioGroup());
        assertEquals("unknown", variant.resolution());
        assertTrue(parsed.audioFor(variant).isEmpty());
    }

    @Test void distinguishesInBandDeclarationFromSeparateAudioUri() {
        var parsed = parse("""
                #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="g",NAME="Original, stereo",LANGUAGE="uk"
                #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="g",NAME="English",LANGUAGE="en",URI="en/audio.m3u8"
                #EXT-X-STREAM-INF:BANDWIDTH=1000,RESOLUTION=1280x720,AUDIO="g"
                low.m3u8
                """);
        assertEquals(2, parsed.audioFor(parsed.variants().getFirst()).size());
        assertNull(parsed.audio().getFirst().uri());
        assertEquals("https://example.org/clip/en/audio.m3u8", parsed.audio().get(1).uri().toString());
        assertEquals("Original, stereo", parsed.audio().getFirst().name());
    }

    @Test void resolvesRelativeReferencesWithoutCopyingParentToken() {
        var parsed = parse("#EXT-X-STREAM-INF:BANDWIDTH=1000,RESOLUTION=854x462\n../low.m3u8?token=child-secret\n");
        assertEquals("https://example.org/low.m3u8?token=child-secret", parsed.variants().getFirst().uri().toString());
        assertFalse(parsed.toString().contains("secret"));
        assertFalse(parsed.variants().getFirst().toString().contains("secret"));
    }

    @Test void keepsKeyEvidenceWhenALaterSegmentIsUnencrypted() {
        var parsed = parse("""
                #EXT-X-TARGETDURATION:4
                #EXT-X-KEY:METHOD=AES-128,URI="key?METHOD=NONE"
                #EXTINF:4,
                segment.ts
                #EXT-X-KEY:METHOD=NONE
                #EXTINF:4,
                other.ts
                #EXT-X-ENDLIST
                """);
        assertEquals(ObservedHlsManifest.Kind.MEDIA, parsed.kind());
        assertEquals(2, parsed.segmentCount());
        assertTrue(parsed.keyTagObserved());
    }

    @Test void rejectsDanglingAndDuplicateAudioDeclarations() {
        assertThrows(IllegalArgumentException.class, () -> parse("#EXT-X-STREAM-INF:BANDWIDTH=1,AUDIO=\"absent\"\nv.m3u8"));
        String audio = "#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"g\",NAME=\"same\"\n";
        assertThrows(IllegalArgumentException.class, () -> parse(audio + audio + "#EXT-X-STREAM-INF:BANDWIDTH=1,AUDIO=\"g\"\nv.m3u8"));
    }

    @Test void rejectsMalformedMixedAndUnsupportedInputs() {
        for (String body : new String[]{"", "#EXT-X-STREAM-INF:BANDWIDTH=1", "#EXT-X-STREAM-INF:BANDWIDTH=0\nv.m3u8",
                "#EXT-X-STREAM-INF:BANDWIDTH=1,RESOLUTION=0x720\nv.m3u8",
                "#EXT-X-STREAM-INF:BANDWIDTH=1,BANDWIDTH=2\nv.m3u8",
                "#EXT-X-STREAM-INF:BANDWIDTH=1\nv.m3u8\n#EXTINF:4,\ns.ts",
                "#EXT-X-DEFINE:NAME=\"secret\",VALUE=\"value\"\n#EXT-X-STREAM-INF:BANDWIDTH=1\nv.m3u8",
                "#EXT-X-STREAM-INF:BANDWIDTH=1\nfile:///private-secret"}) {
            var failure = assertThrows(IllegalArgumentException.class, () -> parse(body));
            assertEquals("Invalid or unsupported HLS metadata", failure.getMessage());
            assertNull(failure.getCause());
        }
    }

    @Test void enforcesSizeAndVariantLimits() {
        assertThrows(IllegalArgumentException.class, () -> parse("x".repeat(262145)));
        assertThrows(IllegalArgumentException.class, () -> parse("#EXT-X-STREAM-INF:BANDWIDTH=1\nv.m3u8\n".repeat(33)));
    }

    @Test void returnedCollectionsCannotBeMutated() {
        var parsed = parse("#EXT-X-STREAM-INF:BANDWIDTH=1\nv.m3u8");
        assertThrows(UnsupportedOperationException.class, () -> parsed.variants().clear());
        assertThrows(UnsupportedOperationException.class, () -> parsed.audio().clear());
    }

    @Test void durationRequiresACompletedConsistentPlaylistAndPreservesFractions() {
        String media = "#EXT-X-TARGETDURATION:4\n#EXTINF:4.125,\na.ts\n#EXTINF:3.75,\nb.ts\n";
        assertNull(parse(media).durationSeconds(), "A live window is not the whole video's duration");
        assertEquals(new java.math.BigDecimal("7.875"), parse(media + "#EXT-X-ENDLIST").durationSeconds());
        for (String invalid : new String[]{"NaN", "Infinity", "-1", "0", "1e99999999", "1.234567890123456"}) {
            assertNull(parse(media.replace("4.125", invalid) + "#EXT-X-ENDLIST").durationSeconds());
        }
        assertNull(parse(media.replace("a.ts\n", "") + "#EXT-X-ENDLIST").durationSeconds());
        assertNull(parse(media + "#EXTINF:1,\n#EXT-X-ENDLIST").durationSeconds());
        assertNull(parse("#EXT-X-ENDLIST\n" + media).durationSeconds());
    }

    private ObservedHlsManifest parse(String body) { return ObservedHlsManifest.parse(BASE, "#EXTM3U\n" + body); }
}
