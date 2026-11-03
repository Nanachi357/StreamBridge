package dev.streambridge.spike;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HlsCatalogSelectionTest {
    private static final String URL = "https://example.org/master?secret=parent";
    private static final String FRAME = "https://example.org/page?secret=frame";
    private static final String MASTER = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1,RESOLUTION=1280x720\nvideo.m3u8?secret=child\n";
    private static final String MEDIA = "#EXTM3U\n#EXT-X-TARGETDURATION:4\n#EXTINF:4,\ns.ts\n";
    private final HlsCandidateCatalog catalog = new HlsCandidateCatalog(new RealDiscoverySpike()::label);

    private long revision() { return catalog.versionedSnapshot().revision(); }
    private HlsCandidateCatalog.Choice chooseUnknown() { return catalog.choose(revision(), "C1", "V1", "unknown"); }

    @Test void selectsExactUriAndExplicitlyKeepsAudioUnknown() {
        catalog.observe(URL, FRAME, MASTER);
        var choice = chooseUnknown();
        assertEquals("https://example.org/video.m3u8?secret=child", choice.media().toString());
        assertEquals(URL, choice.master().toString());
        assertNull(choice.separateAudio());
        assertEquals(HlsCandidateCatalog.AudioSource.UNKNOWN, choice.audioSource());
        assertTrue(catalog.isCurrent(choice));
        assertFalse(choice.toString().contains("secret"));
        assertFalse(choice.toString().contains("example.org"));
        assertThrows(IllegalArgumentException.class, () -> catalog.choose(revision(), "C1", "V1", "A1"));
    }

    @Test void identicalObservationsDoNotInvalidateChoiceOrSnapshot() {
        catalog.observe(URL, FRAME, MASTER);
        var snapshot = catalog.versionedSnapshot();
        var choice = chooseUnknown();
        catalog.observe(URL, FRAME, MASTER);
        assertEquals(snapshot, catalog.versionedSnapshot());
        assertTrue(catalog.isCurrent(choice));
        assertThrows(UnsupportedOperationException.class, () -> snapshot.candidates().clear());
    }

    @Test void sameVisibleQualityButDifferentSignedUriInvalidatesChoice() {
        catalog.observe(URL, FRAME, MASTER);
        var before = catalog.versionedSnapshot();
        var choice = chooseUnknown();
        catalog.observe(URL, FRAME, MASTER.replace("secret=child", "secret=rotated"));
        // Labels omit signed child URLs, but the source revision must reveal that the choice changed.
        assertEquals(before.candidates().getFirst().variants(), catalog.snapshot().getFirst().variants());
        assertNotEquals(before.candidates().getFirst().selectionRevision(),catalog.snapshot().getFirst().selectionRevision());
        assertNotEquals(before.revision(), revision());
        assertFalse(catalog.isCurrent(choice));
        assertEquals("https://example.org/video.m3u8?secret=child", choice.media().toString());
        assertThrows(IllegalArgumentException.class, () -> catalog.choose(before.revision(), "C1", "V1", "unknown"));
    }

    @Test void versionNeverReturnsToAnOldValueAfterMetadataChangesBack() {
        catalog.observe(URL, FRAME, MASTER);
        var choice = chooseUnknown();
        catalog.observe(URL, FRAME, MASTER.replace("1280x720", "854x462"));
        catalog.observe(URL, FRAME, MASTER);
        assertFalse(catalog.isCurrent(choice));
        assertTrue(revision() > choice.revision());
    }

    @Test void unrelatedCandidateOrNewFrameConservativelyInvalidatesChoice() {
        catalog.observe(URL, FRAME, MASTER);
        var first = chooseUnknown();
        catalog.observe("https://example.org/other", FRAME, MASTER);
        assertFalse(catalog.isCurrent(first));
        var second = chooseUnknown();
        catalog.observe(URL, "https://example.org/other-frame", MASTER);
        assertFalse(catalog.isCurrent(second));
    }

    @Test void selectsOnlyCompatibleAudioWithExplicitInBandAndSeparateCases() {
        catalog.observe(URL, FRAME, """
                #EXTM3U
                #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="external",NAME="Original",URI="audio.m3u8?secret=audio"
                #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="internal",NAME="Original"
                #EXT-X-STREAM-INF:BANDWIDTH=1,AUDIO="external"
                low.m3u8
                #EXT-X-STREAM-INF:BANDWIDTH=2,AUDIO="internal"
                high.m3u8
                """);
        var separate = catalog.choose(revision(), "C1", "V1", "A1");
        assertEquals("https://example.org/audio.m3u8?secret=audio", separate.separateAudio().toString());
        assertEquals(HlsCandidateCatalog.AudioSource.SEPARATE_PLAYLIST, separate.audioSource());
        var inBand = catalog.choose(revision(), "C1", "V2", "A2");
        assertNull(inBand.separateAudio());
        assertEquals(HlsCandidateCatalog.AudioSource.IN_BAND_DECLARED, inBand.audioSource());
        assertThrows(IllegalArgumentException.class, () -> catalog.choose(revision(), "C1", "V1", "A2"));
        assertThrows(IllegalArgumentException.class, () -> catalog.choose(revision(), "C1", "V1", "unknown"));
    }

    @Test void malformedRefreshInvalidatesSelectionUntilSuccessfulRecovery() {
        catalog.observe(URL, FRAME, MASTER);
        var choice = chooseUnknown();
        assertThrows(IllegalArgumentException.class, () -> catalog.observe(URL, FRAME, "broken"));
        long failedRevision = revision();
        assertFalse(catalog.isCurrent(choice));
        assertThrows(IllegalArgumentException.class, this::chooseUnknown);
        assertThrows(IllegalArgumentException.class, () -> catalog.observe(URL, FRAME, "still broken"));
        assertEquals(failedRevision, revision());
        catalog.observe(URL, FRAME, MASTER);
        assertTrue(revision() > failedRevision);
        assertTrue(catalog.isCurrent(chooseUnknown()));
        assertFalse(catalog.isCurrent(choice));
    }

    @Test void rejectedChildRefreshMakesItsParentUnusable() {
        catalog.observe(URL, FRAME, MASTER);
        String child = "https://example.org/video.m3u8?secret=child";
        catalog.observe(child, FRAME, MEDIA);
        assertThrows(IllegalArgumentException.class, () -> catalog.observe(child, FRAME, "broken"));
        assertThrows(IllegalArgumentException.class, this::chooseUnknown);
    }

    @Test void observedKeyBlocksSelectionWithoutFetchingOrDecryptingAnything() {
        catalog.observe(URL, FRAME, MASTER);
        var choice = chooseUnknown();
        catalog.observe("https://example.org/video.m3u8?secret=child", FRAME,
                MEDIA + "#EXT-X-KEY:METHOD=AES-128,URI=\"key\"\n");
        assertFalse(catalog.isCurrent(choice));
        var failure = assertThrows(IllegalArgumentException.class, this::chooseUnknown);
        assertTrue(failure.getMessage().startsWith("KEY_OBSERVED"));
    }

    @Test void rejectsMissingIdsHiddenChildrenAndUnassociatedMedia() {
        catalog.observe(URL, FRAME, MASTER);
        assertThrows(IllegalArgumentException.class, () -> catalog.choose(revision(), "C99", "V1", "unknown"));
        assertThrows(IllegalArgumentException.class, () -> catalog.choose(revision(), "C1", "V99", "unknown"));
        catalog.observe("https://example.org/video.m3u8?secret=child", FRAME, MEDIA);
        assertThrows(IllegalArgumentException.class, () -> catalog.choose(revision(), "C2", "V1", "unknown"));
        catalog.observe("https://example.org/orphan.m3u8", FRAME, MEDIA);
        assertThrows(IllegalArgumentException.class, () -> catalog.choose(revision(), "C3", "V1", "unknown"));
    }

    @Test void commandParserRequiresExplicitRevisionAndEveryChoice() {
        assertEquals(new RealDiscoverySpike.SelectionRequest(2, "C1", "V2", "unknown"),
                RealDiscoverySpike.parseSelection(" select 2 C1 V2 unknown "));
        assertEquals("A3", RealDiscoverySpike.parseSelection("select 12 C1 V2 A3").audio());
        for (String input : new String[]{"select", "select C1 V2 A1", "select 0 C1 V2 A1",
                "select -1 C1 V2 A1", "select 2 C0 V2 A1", "select 2 C1 V0 A1",
                "select 2 C1 V1 auto", "select 2 C1 V1 A0", "select 2 C1 V1 unknown extra",
                "select 999999999999999999999999999 C1 V1 A1", "select 2 https://secret.example V1 A1"}) {
            var failure = assertThrows(IllegalArgumentException.class, () -> RealDiscoverySpike.parseSelection(input));
            assertFalse(failure.getMessage().contains("secret"));
            assertNull(failure.getCause());
        }
    }
}
