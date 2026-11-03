package dev.streambridge.spike;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HlsTransferPlanTest {
    private static final String MASTER_URL = "https://media.example.org/master?secret=master";
    private static final String FRAME = "https://example.org/watch";
    private static final String MASTER = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\nvideo/index.m3u8\n";
    private final HlsCandidateCatalog catalog = new HlsCandidateCatalog(new RealDiscoverySpike()::label);

    private HlsCandidateCatalog.Choice choose(String audio) {
        return catalog.choose(catalog.versionedSnapshot().revision(), "C1", "V1", audio);
    }

    @Test void missingSelectedPlaylistIsAnExplicitNeedNotAFetchOrGuessedTrack() {
        catalog.observe(MASTER_URL, FRAME, MASTER);
        var plan = HlsTransferPlan.build(catalog, choose("unknown"));
        assertEquals(HlsTransferPlan.Stage.NEEDS_PLAYLISTS, plan.stage());
        assertEquals(1, plan.inputs().size());
        assertNull(plan.inputs().getFirst().metadata());
        assertEquals(HlsCandidateCatalog.AudioSource.UNKNOWN, plan.choice().audioSource());
        assertFalse(plan.toString().contains("secret"));
    }

    @Test void knownPlaylistGivesAnInventoryButNeverPermissionToExecute() {
        catalog.observe(MASTER_URL, FRAME, MASTER);
        catalog.observe("https://media.example.org/video/index.m3u8", FRAME, HlsVodPlaylistTest.VOD);
        var plan = HlsTransferPlan.build(catalog, choose("unknown"));
        assertEquals(HlsTransferPlan.Stage.NEEDS_PREFLIGHT, plan.stage());
        assertEquals(2, plan.inputs().getFirst().metadata().segments().size());
        assertTrue(plan.requiredChecks().contains(HlsTransferPlan.RequiredCheck.ACTUAL_TRACKS_AND_MAPPING));
        assertTrue(plan.requiredChecks().contains(HlsTransferPlan.RequiredCheck.NETWORK_POLICY_AND_ACCESS));
        assertThrows(UnsupportedOperationException.class, () -> plan.inputs().clear());
    }

    @Test void separateAudioRequiresExactlyTheChosenTwoPlaylists() {
        catalog.observe(MASTER_URL, FRAME, """
                #EXTM3U
                #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="g",NAME="Original",URI="audio/index.m3u8"
                #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="g",NAME="Other",URI="other/index.m3u8"
                #EXT-X-STREAM-INF:BANDWIDTH=1,AUDIO="g"
                video/index.m3u8
                """);
        catalog.observe("https://media.example.org/video/index.m3u8", FRAME, HlsVodPlaylistTest.VOD);
        var partial = HlsTransferPlan.build(catalog, choose("A1"));
        assertEquals(2, partial.inputs().size());
        assertEquals(HlsTransferPlan.Stage.NEEDS_PLAYLISTS, partial.stage());
        assertEquals(HlsTransferPlan.Role.SEPARATE_AUDIO, partial.inputs().get(1).role());
        assertEquals("https://media.example.org/audio/index.m3u8", partial.inputs().get(1).playlist().toString());
        catalog.observe("https://media.example.org/audio/index.m3u8", FRAME, HlsVodPlaylistTest.VOD);
        var complete = HlsTransferPlan.build(catalog, choose("A1"));
        assertEquals(HlsTransferPlan.Stage.NEEDS_PREFLIGHT, complete.stage());
        assertEquals(4, complete.inputs().stream().mapToInt(i -> i.metadata().segments().size()).sum());
        assertFalse(complete.inputs().stream().anyMatch(i -> i.playlist().toString().contains("other")));
    }

    @Test void declaredInBandAudioDoesNotInventASecondPlaylist() {
        catalog.observe(MASTER_URL, FRAME, """
                #EXTM3U
                #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="g",NAME="Original"
                #EXT-X-STREAM-INF:BANDWIDTH=1,AUDIO="g"
                video/index.m3u8
                """);
        var plan = HlsTransferPlan.build(catalog, choose("A1"));
        assertEquals(1, plan.inputs().size());
        assertEquals(HlsCandidateCatalog.AudioSource.IN_BAND_DECLARED, plan.choice().audioSource());
    }

    @Test void noSelectionAndStaleSelectionAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> HlsTransferPlan.build(catalog, null));
        catalog.observe(MASTER_URL, FRAME, MASTER);
        var choice = choose("unknown");
        catalog.observe("https://media.example.org/video/index.m3u8", FRAME, HlsVodPlaylistTest.VOD);
        assertThrows(IllegalArgumentException.class, () -> HlsTransferPlan.build(catalog, choice));
    }

    @Test void changingOnlyASegmentUriInvalidatesTheChoiceAndDraft() {
        catalog.observe(MASTER_URL, FRAME, MASTER);
        catalog.observe("https://media.example.org/video/index.m3u8", FRAME, HlsVodPlaylistTest.VOD);
        var plan = HlsTransferPlan.build(catalog, choose("unknown"));
        var oldSegment = plan.inputs().getFirst().metadata().segments().getFirst();
        catalog.observe("https://media.example.org/video/index.m3u8", FRAME,
                HlsVodPlaylistTest.VOD.replace("segment-000.ts", "segment-000.ts?new-token"));
        assertFalse(plan.isCurrent(catalog));
        assertFalse(oldSegment.uri().toString().contains("new-token"));
    }

    @Test void repeatedIdenticalMediaObservationKeepsTheDraftCurrent() {
        catalog.observe(MASTER_URL, FRAME, MASTER);
        catalog.observe("https://media.example.org/video/index.m3u8", FRAME, HlsVodPlaylistTest.VOD);
        var plan = HlsTransferPlan.build(catalog, choose("unknown"));
        catalog.observe("https://media.example.org/video/index.m3u8", FRAME, HlsVodPlaylistTest.VOD);
        assertTrue(plan.isCurrent(catalog));
    }

    @Test void observationParserAcceptanceDoesNotMeanTransferParserAcceptance() {
        catalog.observe(MASTER_URL, FRAME, MASTER);
        catalog.observe("https://media.example.org/video/index.m3u8", FRAME,
                HlsVodPlaylistTest.VOD.replace("#EXTINF:2.0,", "#EXT-X-MAP:URI=\"secret.mp4\"\n#EXTINF:2.0,"));
        assertThrows(IllegalArgumentException.class, () -> HlsTransferPlan.build(catalog, choose("unknown")));
    }

    @Test void rejectsUnsupportedInputSyntaxBeforeEvenAskingForAPlaylist() {
        catalog.observe(MASTER_URL, FRAME, MASTER.replace("video/index.m3u8", "http://localhost/private-secret"));
        var failure = assertThrows(IllegalArgumentException.class, () -> HlsTransferPlan.build(catalog, choose("unknown")));
        assertEquals("UNSUPPORTED_INPUT_URI", failure.getMessage());
        assertNull(failure.getCause());
    }
}
