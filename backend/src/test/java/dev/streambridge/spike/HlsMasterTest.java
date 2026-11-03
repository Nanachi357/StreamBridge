package dev.streambridge.spike;

import java.net.URI;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HlsMasterTest {
    private static final URI BASE = URI.create("https://example.test/a/master?token=one");
    private static final String MASTER = """
            #EXTM3U
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="shared",NAME="Tone, UK",LANGUAGE="uk",URI="sound/index.m3u8?token=two"
            #EXT-X-STREAM-INF:BANDWIDTH=500000,RESOLUTION=640x360,CODECS="avc1.42e01e,mp4a.40.2",AUDIO="shared"
            video/index.m3u8
            """;

    @Test void resolvesRelativeUrisAndQuotedCommasWithoutInventingInheritedQuery() {
        var master = HlsMaster.parse(BASE, MASTER);
        assertEquals(URI.create("https://example.test/a/video/index.m3u8"), master.variants().getFirst().uri());
        assertEquals(URI.create("https://example.test/a/sound/index.m3u8?token=two"), master.audio().getFirst().uri());
        assertEquals("Tone, UK", master.audio().getFirst().name());
        assertEquals("uk", master.audio().getFirst().language());
    }

    @Test void sameGroupIdInDifferentMastersNeverMixesAudio() {
        var a = HlsMaster.parse(BASE, MASTER);
        var b = HlsMaster.parse(URI.create("https://example.test/b/master"), MASTER);
        assertTrue(a.select(0, 0).audio().uri().getPath().startsWith("/a/"));
        assertTrue(b.select(0, 0).audio().uri().getPath().startsWith("/b/"));
        assertThrows(IllegalArgumentException.class, () -> a.audioFor(b.variants().getFirst()));
    }

    @Test void selectionListsOnlyCompatibleAudio() {
        var master = HlsMaster.parse(BASE, MASTER + """
                #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="other",NAME="Other",URI="other/index.m3u8"
                """);
        assertEquals(2, master.audio().size());
        assertEquals(1, master.audioFor(master.variants().getFirst()).size());
        assertThrows(IllegalArgumentException.class, () -> master.select(0, 1));
        assertThrows(IllegalArgumentException.class, () -> master.select(-1, 0));
    }

    @Test void rejectsDanglingGroupsAndMissingVariantUri() {
        assertThrows(IllegalArgumentException.class, () -> HlsMaster.parse(BASE, MASTER.replace("AUDIO=\"shared\"", "AUDIO=\"missing\"")));
        assertThrows(IllegalArgumentException.class, () -> HlsMaster.parse(BASE, MASTER.replace("video/index.m3u8", "")));
    }

    @Test void rejectsMalformedOrAmbiguousAttributes() {
        for (String invalid : new String[]{MASTER.replace("500000", "500000,BANDWIDTH=1"),
                MASTER.replace("NAME=\"Tone, UK\"", "NAME=\"Unclosed"),
                MASTER.replace("AUDIO=\"shared\"", "AUDIO=\"shared\","),
                MASTER.replace("640x360", "0x360")}) {
            assertThrows(IllegalArgumentException.class, () -> HlsMaster.parse(BASE, invalid));
        }
    }

    @Test void rejectsNonHttpAndMuxedOrEncryptedCasesRatherThanGuessing() {
        for (String invalid : new String[]{MASTER.replace("video/index.m3u8", "file:///secret"),
                MASTER.replace(",URI=\"sound/index.m3u8?token=two\"", ""),
                MASTER + "#EXT-X-SESSION-KEY:METHOD=AES-128,URI=\"key\"\n"}) {
            assertThrows(IllegalArgumentException.class, () -> HlsMaster.parse(BASE, invalid));
        }
    }

    @Test void rejectsMediaPlaylistAndOversizedInput() {
        assertFalse(HlsMaster.isMaster("#EXTM3U\n#EXTINF:2,\nsegment.ts"));
        assertThrows(IllegalArgumentException.class, () -> HlsMaster.parse(BASE, "#EXTM3U\n#EXTINF:2,\nsegment.ts"));
        assertThrows(IllegalArgumentException.class, () -> HlsMaster.parse(BASE, MASTER + "#".repeat(65_536)));
    }
}
