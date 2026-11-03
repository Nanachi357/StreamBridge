package dev.streambridge.spike;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RealDiscoverySpikeTest {
    @Test void recognizesExtensionlessManifestsByMimeType() {
        assertEquals("HLS", RealDiscoverySpike.mediaKind("https://example.org/stream?token=secret", "application/vnd.apple.mpegurl"));
        assertEquals("DASH", RealDiscoverySpike.mediaKind("https://example.org/stream", "application/dash+xml"));
        assertEquals("HLS", RealDiscoverySpike.mediaKind("https://example.org/data.ts", "application/x-mpegURL"));
    }

    @Test void doesNotCallEverySegmentAnIndependentVideo() {
        assertEquals("SEGMENT", RealDiscoverySpike.mediaKind("https://example.org/chunk.m4s?signature=x", null));
        assertEquals("SEGMENT", RealDiscoverySpike.mediaKind("https://example.org/chunk", "video/mp2t"));
        assertEquals("MP4_OR_FRAGMENT", RealDiscoverySpike.mediaKind("https://example.org/chunk.mp4", "video/mp4"));
        assertNull(RealDiscoverySpike.mediaKind("https://example.org/watch", "text/html"));
    }

    @Test void reportLabelsHidePathsQueriesAndCredentialsButRetainRelationshipsWithinRun() {
        var diagnostic = new RealDiscoverySpike();
        String raw = "https://name:password@example.org/private/title.m3u8?token=secret#fragment";
        String label = diagnostic.label(raw);
        assertTrue(label.matches("https://example\\.org/resource-[a-f0-9]{12}"));
        assertEquals(label, diagnostic.label(raw));
        assertNotEquals(label, diagnostic.label(raw + "2"));
        assertNotEquals(label, new RealDiscoverySpike().label(raw));
    }

    @Test void aRealMasterMayDeclareNoSeparateAudioGroup() {
        var attributes = HlsMaster.attributes("BANDWIDTH=2128000,RESOLUTION=1920x1080,CODECS=\"avc1.640028,mp4a.40.2\"");
        assertEquals("1920x1080", attributes.get("RESOLUTION"));
        assertFalse(attributes.containsKey("AUDIO"));
        // Observation can retain this variant; the earlier fixture's strict separate-audio parser cannot.
    }
}
