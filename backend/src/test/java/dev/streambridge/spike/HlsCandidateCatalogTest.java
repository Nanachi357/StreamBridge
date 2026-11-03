package dev.streambridge.spike;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HlsCandidateCatalogTest {
    private static final String MASTER = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1000,RESOLUTION=1280x720\nlow.m3u8\n";
    private static final String MEDIA = "#EXTM3U\n#EXT-X-TARGETDURATION:4\n#EXTINF:4,\ns.ts\n#EXT-X-ENDLIST\n";
    private static final String PAGE = "https://example.org/page?private-token";
    private final HlsCandidateCatalog catalog = new HlsCandidateCatalog(new RealDiscoverySpike()::label);

    @Test void pinProtectsMasterAndLateVideoAudioChildrenUnderContinuousEviction() {
        String master=MASTER.replace("#EXT-X-STREAM-INF:","#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"g\",NAME=\"Original\",URI=\"audio.m3u8\"\n#EXT-X-STREAM-INF:AUDIO=\"g\",");
        catalog.observe("https://example.org/master",PAGE,master);
        long sourceRevision=catalog.snapshot().getFirst().selectionRevision();
        catalog.pin(sourceRevision,"C1");
        for(int i=0;i<350;i++) {
            if(i==50){catalog.observe("https://example.org/low.m3u8",PAGE,MEDIA);catalog.observe("https://example.org/audio.m3u8",PAGE,MEDIA);}
            catalog.observe("https://ads.example/"+i+"/master",PAGE,MASTER);
        }
        assertEquals("C1",catalog.versionedSnapshot().pinnedCandidateId());
        assertTrue(catalog.mediaPlaylistBody(java.net.URI.create("https://example.org/low.m3u8")).isPresent());
        assertTrue(catalog.mediaPlaylistBody(java.net.URI.create("https://example.org/audio.m3u8")).isPresent());
        var choice=catalog.choosePinned(sourceRevision,"C1","V1","A1");
        assertEquals(catalog.revision(),choice.revision());assertTrue(catalog.isCurrent(choice));
        assertTrue(catalog.snapshot().size()<=100);
        catalog.pin(0,"");
        catalog.observe("https://ads.example/last/master",PAGE,MASTER);
        assertFalse(catalog.snapshot().stream().anyMatch(c->c.id().equals("C1")));
    }

    @Test void sourceRevisionIgnoresProvenanceAndDurationButRejectsChangedTrackAddresses() {
        catalog.observe("https://example.org/master",PAGE,MASTER);
        long sourceRevision=catalog.snapshot().getFirst().selectionRevision();catalog.pin(sourceRevision,"C1");
        catalog.observe("https://example.org/master",PAGE+"other",MASTER);
        catalog.observe("https://example.org/low.m3u8",PAGE,MEDIA);
        assertEquals(sourceRevision,catalog.snapshot().getFirst().selectionRevision());
        assertDoesNotThrow(()->catalog.choosePinned(sourceRevision,"C1","V1","unknown"));
        catalog.observe("https://example.org/master",PAGE,MASTER.replace("low.m3u8","different.m3u8"));
        assertThrows(IllegalArgumentException.class,()->catalog.choosePinned(sourceRevision,"C1","V1","unknown"));
        assertThrows(IllegalArgumentException.class,()->catalog.pin(sourceRevision,"C1"));
        assertEquals("C1",catalog.versionedSnapshot().pinnedCandidateId());
        var current=catalog.snapshot().getFirst();
        assertEquals("https://example.org/different.m3u8",catalog.choosePinned(current.selectionRevision(),"C1","V1","unknown").media().toString());
    }

    @Test void switchingPinReleasesOldFamilyAndNavigationClearsPin() {
        catalog.observe("https://example.org/one/master",PAGE,MASTER);
        catalog.observe("https://example.org/two/master",PAGE,MASTER);
        catalog.pin(catalog.snapshot().get(0).selectionRevision(),"C1");
        assertThrows(IllegalArgumentException.class,()->catalog.pin(999,"C2"));
        assertEquals("C1",catalog.versionedSnapshot().pinnedCandidateId());
        catalog.pin(catalog.snapshot().get(1).selectionRevision(),"C2");
        for(int i=0;i<110;i++)catalog.observe("https://ads.example/"+i+"/master",PAGE,MASTER);
        assertFalse(catalog.snapshot().stream().anyMatch(c->c.id().equals("C1")));
        assertTrue(catalog.snapshot().stream().anyMatch(c->c.id().equals("C2")));
        catalog.clear();assertEquals("",catalog.versionedSnapshot().pinnedCandidateId());
        assertThrows(IllegalArgumentException.class,()->catalog.choosePinned(2,"C2","V1","unknown"));
    }

    @Test void pinDoesNotBypassEncryptionOrInvalidMetadata() {
        catalog.observe("https://example.org/master",PAGE,MASTER);
        long sourceRevision=catalog.snapshot().getFirst().selectionRevision();catalog.pin(sourceRevision,"C1");
        assertThrows(IllegalArgumentException.class,()->catalog.observe("https://example.org/low.m3u8",PAGE,"bad"));
        catalog.observe("https://example.org/low.m3u8",PAGE,MEDIA.replace("#EXTINF", "#EXT-X-KEY:METHOD=AES-128,URI=\"key\"\n#EXTINF"));
        assertThrows(IllegalArgumentException.class,()->catalog.choosePinned(sourceRevision,"C1","V1","unknown"));
        assertThrows(IllegalArgumentException.class,()->catalog.choosePinned(catalog.snapshot().getFirst().selectionRevision(),"C1","V1","unknown"));
    }

    @Test void oversizedUpdateOfPinnedSourceExplicitlyReleasesPinWithoutBreakingProtocol() {
        catalog.observe("https://example.org/master",PAGE,MASTER);
        catalog.pin(catalog.snapshot().getFirst().selectionRevision(),"C1");
        var huge=new StringBuilder("#EXTM3U\n");
        for(int i=0;i<32;i++)huge.append("#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"g\",NAME=\"")
                .append("x".repeat(115)).append(i).append("\",URI=\"a").append(i).append(".m3u8\"\n");
        for(int i=0;i<32;i++)huge.append("#EXT-X-STREAM-INF:BANDWIDTH=1000,AUDIO=\"g\"\nv").append(i).append(".m3u8\n");
        catalog.observe("https://example.org/master",PAGE,huge.toString());
        assertEquals("",catalog.versionedSnapshot().pinnedCandidateId());
        assertEquals("PIN_METADATA_TOO_LARGE",catalog.versionedSnapshot().pinNotice());
        assertTrue(UiProtocol.json(catalog.versionedSnapshot()).length()<=HlsCandidateCatalog.SNAPSHOT_CHARACTERS);
    }

    @Test void foldsChildPlaylistsIntoTheirParentRegardlessOfArrivalOrder() {
        String childId = catalog.observe("https://example.org/low.m3u8", PAGE, MEDIA);
        var earlier = catalog.snapshot();
        String masterId = catalog.observe("https://example.org/master", PAGE, MASTER);
        var roots = catalog.snapshot();
        assertEquals(1, roots.size());
        assertEquals(masterId, roots.getFirst().id());
        assertTrue(roots.getFirst().variants().getFirst().playlistObserved());
        assertEquals(childId, earlier.getFirst().id()); // Earlier snapshots do not change under a reader.
        assertEquals(ObservedHlsManifest.Kind.MEDIA, earlier.getFirst().kind());
    }

    @Test void preservesCandidateIdentityOnDuplicatesAndAddsFrameProvenance() {
        String id = catalog.observe("https://example.org/master", PAGE, MASTER);
        catalog.observe("https://example.org/low.m3u8", PAGE, MEDIA);
        assertEquals(id, catalog.observe("https://example.org/master", "https://frame.example.org/player", MASTER));
        assertEquals(1, catalog.snapshot().size());
        assertEquals(2, catalog.snapshot().getFirst().frames().size());
    }

    @Test void audioGroupsAreScopedToEachMasterAndEachVariant() {
        String header = "#EXTM3U\n#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"same\",NAME=\"original\",LANGUAGE=\"%s\",URI=\"audio.m3u8\"\n";
        String variant = "#EXT-X-STREAM-INF:BANDWIDTH=1,AUDIO=\"same\"\nv.m3u8\n";
        catalog.observe("https://example.org/a/master", PAGE, header.formatted("uk") + variant);
        catalog.observe("https://example.org/b/master", PAGE, header.formatted("en") + variant);
        catalog.observe("https://example.org/b/audio.m3u8", PAGE, MEDIA);
        var roots = catalog.snapshot();
        assertEquals(2, roots.size());
        var a = roots.get(0).variants().getFirst().audio().getFirst();
        var b = roots.get(1).variants().getFirst().audio().getFirst();
        assertEquals("uk", a.language()); assertEquals("en", b.language());
        assertFalse(a.playlistObserved()); assertTrue(b.playlistObserved());
        assertEquals(HlsCandidateCatalog.AudioSource.SEPARATE_PLAYLIST, a.source());
    }

    @Test void listsOnlyAudioCompatibleWithEachVariantWithinOneMaster() {
        catalog.observe("https://example.org/master", PAGE, """
                #EXTM3U
                #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="stereo",NAME="Original",LANGUAGE="uk",URI="stereo.m3u8"
                #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="surround",NAME="Original",LANGUAGE="en",URI="surround.m3u8"
                #EXT-X-STREAM-INF:BANDWIDTH=1,AUDIO="stereo"
                low.m3u8
                #EXT-X-STREAM-INF:BANDWIDTH=2,AUDIO="surround"
                high.m3u8
                """);
        var variants = catalog.snapshot().getFirst().variants();
        assertEquals(1, variants.get(0).audio().size());
        assertEquals(1, variants.get(1).audio().size());
        assertEquals("A1", variants.get(0).audio().getFirst().id());
        assertEquals("A2", variants.get(1).audio().getFirst().id());
    }

    @Test void noGroupIsUnknownWhereasAudioWithoutUriIsDeclaredInBand() {
        catalog.observe("https://example.org/unknown", PAGE, MASTER);
        catalog.observe("https://example.org/inband", PAGE, """
                #EXTM3U
                #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="g",NAME="original"
                #EXT-X-STREAM-INF:BANDWIDTH=1,AUDIO="g"
                other.m3u8
                """);
        assertTrue(catalog.snapshot().get(0).variants().getFirst().audio().isEmpty());
        var audio = catalog.snapshot().get(1).variants().getFirst().audio().getFirst();
        assertEquals(HlsCandidateCatalog.AudioSource.IN_BAND_DECLARED, audio.source());
        assertFalse(audio.playlistObserved()); // There is no separate audio playlist to observe.
    }

    @Test void failedUpdateIsAtomicAndDoesNotEraseLastValidMetadata() {
        String id = catalog.observe("https://example.org/master", PAGE, MASTER);
        var earlier = catalog.snapshot();
        assertThrows(IllegalArgumentException.class, () -> catalog.observe("https://example.org/master", PAGE, "not a manifest"));
        assertEquals(earlier.getFirst().variants(), catalog.snapshot().getFirst().variants());
        assertFalse(catalog.snapshot().getFirst().metadataUsable());
        assertEquals(id, catalog.observe("https://example.org/master", PAGE, MASTER.replace("1280x720", "854x462")));
        assertEquals("854x462", catalog.snapshot().getFirst().variants().getFirst().resolution());
        assertTrue(catalog.snapshot().getFirst().metadataUsable());
    }

    @Test void snapshotDoesNotExposeSignedUrlsOrUseDisplayLabelsAsIdentity() {
        var collisionCatalog = new HlsCandidateCatalog(ignored -> "redacted");
        collisionCatalog.observe("https://example.org/master?secret=one", PAGE, MASTER);
        collisionCatalog.observe("https://example.org/master?secret=two", PAGE, MASTER);
        assertEquals(2, collisionCatalog.snapshot().size());
        String printed = collisionCatalog.snapshot().toString();
        assertFalse(printed.contains("secret")); assertFalse(printed.contains("example.org"));
        assertThrows(UnsupportedOperationException.class, () -> collisionCatalog.snapshot().clear());
        assertThrows(UnsupportedOperationException.class, () -> collisionCatalog.snapshot().getFirst().variants().clear());
    }

    @Test void keyEvidenceFromObservedChildrenPropagatesAndCannotBeErasedByRefresh() {
        catalog.observe("https://example.org/master", PAGE, MASTER);
        catalog.observe("https://example.org/low.m3u8", PAGE, MEDIA.replace("#EXTINF", "#EXT-X-KEY:METHOD=AES-128,URI=\"key\"\n#EXTINF"));
        assertTrue(catalog.snapshot().getFirst().keyTagObserved());
        catalog.observe("https://example.org/low.m3u8", PAGE, MEDIA);
        assertTrue(catalog.snapshot().getFirst().keyTagObserved());
    }

    @Test void boundsFramesAndCatalogEntries() {
        for (int f = 0; f < 12; f++) catalog.observe("https://example.org/master", PAGE + f, MASTER);
        assertEquals(8, catalog.snapshot().getFirst().frames().size());
        for (int i = 1; i < 100; i++) catalog.observe("https://example.org/master?" + i, PAGE, MASTER);
        // Refreshing an existing URL retains its ID, but doesn't reorder the UI or invalidate selection.
        long revision=catalog.revision();
        assertEquals("C1", catalog.observe("https://example.org/master", PAGE, MASTER));
        assertEquals(revision,catalog.revision());
        assertEquals("C101",catalog.observe("https://example.org/overflow", PAGE, MASTER));
        assertEquals(100,catalog.snapshot().size());
        assertTrue(catalog.snapshot().stream().anyMatch(c->c.id().equals("C1")));
        assertFalse(catalog.snapshot().stream().anyMatch(c->c.id().equals("C2")));
        assertEquals(1,catalog.versionedSnapshot().evictedManifests());
    }

    @Test void evictionInvalidatesOldChoicesAndNeverReusesIdsEvenAfterNavigation() {
        catalog.observe("https://example.org/master", PAGE, MASTER);
        var choice=catalog.choose(catalog.revision(),"C1","V1","unknown");
        for(int i=1;i<=250;i++)catalog.observe("https://example.org/master?"+i,PAGE,MASTER);
        assertFalse(catalog.isCurrent(choice));assertEquals(100,catalog.snapshot().size());
        assertEquals(151,catalog.versionedSnapshot().evictedManifests());
        assertEquals(100,catalog.snapshot().stream().map(HlsCandidateCatalog.CandidateView::id).distinct().count());
        assertEquals("C252",catalog.observe("https://example.org/master",PAGE,MASTER));
        catalog.clear();assertEquals(0,catalog.versionedSnapshot().evictedManifests());
        assertEquals("C253",catalog.observe("https://example.org/master",PAGE,MASTER));
    }

    @Test void evictingEncryptedChildCannotMakeRetainedParentSelectable() {
        catalog.observe("https://example.org/low.m3u8",PAGE,MEDIA.replace("#EXTINF", "#EXT-X-KEY:METHOD=AES-128,URI=\"key\"\n#EXTINF"));
        catalog.observe("https://example.org/master",PAGE,MASTER);
        for(int i=0;i<99;i++)catalog.observe("https://other.example/"+i+"/master",PAGE,MASTER);
        var parent=catalog.snapshot().stream().filter(c->c.id().equals("C2")).findFirst().orElseThrow();
        assertTrue(parent.keyTagObserved());assertFalse(parent.variants().getFirst().playlistObserved());
        assertThrows(IllegalArgumentException.class,()->catalog.choose(catalog.revision(),"C2","V1","unknown"));
    }

    @Test void largeMetadataCannotOverflowWorkerProtocolAndLaterSmallSourcesStillArrive() {
        var body=new StringBuilder("#EXTM3U\n");
        for(int i=0;i<32;i++)body.append("#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"g\",NAME=\"")
                .append("x".repeat(115)).append(i).append("\",URI=\"a").append(i).append(".m3u8\"\n");
        for(int i=0;i<32;i++)body.append("#EXT-X-STREAM-INF:BANDWIDTH=1000,AUDIO=\"g\"\nv").append(i).append(".m3u8\n");
        for(int i=0;i<8;i++)catalog.observe("https://large.example/"+i+"/master",PAGE,body.toString());
        assertTrue(UiProtocol.json(catalog.versionedSnapshot()).length()<=HlsCandidateCatalog.SNAPSHOT_CHARACTERS);
        assertTrue(catalog.versionedSnapshot().evictedManifests()>0);
        String small=catalog.observe("https://example.org/small",PAGE,MASTER);
        assertTrue(catalog.snapshot().stream().anyMatch(c->c.id().equals(small)));
    }

    @Test void durationBelongsOnlyToItsExactVariantAndIsRemovedOnInvalidRefresh() {
        catalog.observe("https://example.org/master", PAGE, MASTER + "#EXT-X-STREAM-INF:BANDWIDTH=2000\nhigh.m3u8\n");
        assertNull(catalog.snapshot().getFirst().variants().getFirst().durationSeconds());
        catalog.observe("https://example.org/low.m3u8", PAGE, MEDIA);
        var variants = catalog.snapshot().getFirst().variants();
        assertEquals(new java.math.BigDecimal("4"), variants.getFirst().durationSeconds());
        assertNull(variants.get(1).durationSeconds());
        assertThrows(IllegalArgumentException.class, () -> catalog.observe("https://example.org/low.m3u8", PAGE, "invalid"));
        assertNull(catalog.snapshot().getFirst().variants().getFirst().durationSeconds());
    }

    @Test void differentFramesAtTheSameAddressRemainDistinctAndNamesAreLocalUiOnly() {
        catalog.observe("https://example.org/master", PAGE, "F1", MASTER);
        catalog.observe("https://example.org/master", PAGE, "F2", MASTER);
        var players = catalog.snapshot().getFirst().players();
        assertEquals(2, players.size());assertEquals(players.get(0).resource(), players.get(1).resource());
        assertEquals("F1", players.get(0).id());assertEquals("F2", players.get(1).id());
        assertFalse(players.toString().contains("private-token"));
        catalog.observe("https://example.org/named", PAGE, """
                #EXTM3U
                #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="g",NAME="Dub <b>untrusted</b>\u202E",LANGUAGE="uk"
                #EXT-X-STREAM-INF:BANDWIDTH=1,AUDIO="g"
                v.m3u8
                """);
        var audio = catalog.snapshot().get(1).variants().getFirst().audio().getFirst();
        assertEquals("Dub <b>untrusted</b>", audio.name());
        assertFalse(audio.toString().contains("untrusted")); // CLI report must not log free-form labels.
        assertTrue(UiProtocol.json(audio).contains("untrusted")); // Authenticated local UI receives display text.
    }
}
