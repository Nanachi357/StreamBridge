package dev.streambridge.spike;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DiscoveryActivityTest {
    private final DiscoveryActivity activity = new DiscoveryActivity(0, 180_000);

    @Test void encodedManifestWarningBelongsToCurrentDocument() {
        activity.encodedManifestSkipped();activity.encodedManifestSkipped();
        assertEquals(2,activity.snapshot(0,0).skippedEncodedManifests());
        activity.newDocument();
        assertEquals(0,activity.snapshot(0,0).skippedEncodedManifests());
    }

    @Test void unlimitedObservationNeverExpiresButRequestTimeoutStillApplies() {
        var unlimited=new DiscoveryActivity(0,0);unlimited.navigationFinished();
        long later=java.time.Duration.ofDays(2).toNanos();
        assertFalse(unlimited.expired(later));assertEquals(-1,unlimited.snapshot(later,0).remainingSeconds());
        assertEquals(DiscoveryActivity.State.WAITING_FOR_ACTION,unlimited.snapshot(later,0).state());
        var request=new Object();unlimited.requestStarted(request,"HLS",later);
        assertEquals(1,unlimited.snapshot(later,0).pendingPlaylists());
        var after=unlimited.snapshot(later+DiscoveryActivity.REQUEST_TIMEOUT_NANOS,0);
        assertEquals(0,after.pendingPlaylists());assertEquals(1,after.timedOutRequests());
        assertFalse(unlimited.expired(later+DiscoveryActivity.REQUEST_TIMEOUT_NANOS));
        assertFalse(activity.expired(179_999_999_999L));assertTrue(activity.expired(180_000_000_000L));
    }

    @Test void navigationQuietAndNewCatalogHaveDifferentStatesWithoutClaimingCompletion() {
        assertEquals(DiscoveryActivity.State.LOADING_PAGE, activity.snapshot(15_000_000_000L,0).state());
        activity.navigationFinished();
        assertEquals(DiscoveryActivity.State.WAITING_FOR_ACTION, activity.snapshot(9_000_000_000L,0).state());
        assertEquals(DiscoveryActivity.State.WAITING_FOR_ACTION, activity.snapshot(10_000_000_000L,0).state());
        assertEquals(-1, activity.snapshot(10_000_000_000L,0).lastChangeAgoSeconds());
        activity.catalogChanged(11_000_000_000L);
        assertEquals(DiscoveryActivity.State.WAITING_FOR_ACTION, activity.snapshot(11_000_000_000L,1).state());
        assertEquals(DiscoveryActivity.State.WAITING_FOR_ACTION, activity.snapshot(21_000_000_000L,1).state());
        assertEquals(10, activity.snapshot(21_000_000_000L,1).lastChangeAgoSeconds());
        assertEquals(159, activity.snapshot(21_000_000_000L,1).remainingSeconds());
        assertEquals(0, activity.snapshot(190_000_000_000L,1).remainingSeconds());
    }

    @Test void pendingPlaylistsAreTrackedUntilSuccessOrFailureWithoutCountingSegmentsOrAnalytics() {
        activity.navigationFinished();Object hls=new Object(),dash=new Object();
        activity.requestStarted(hls,"HLS",25_000_000_000L);activity.requestStarted(dash,"DASH",25_000_000_000L);
        activity.response(hls,"HLS",true);activity.response(dash,"DASH",true);
        activity.response(new Object(),"SEGMENT",true);activity.response(new Object(),null,true);
        activity.response(new Object(),"HLS",false);
        assertEquals(2, activity.snapshot(30_000_000_000L,0).pendingPlaylists());
        assertEquals(DiscoveryActivity.State.READING_PLAYLIST, activity.snapshot(30_000_000_000L,0).state());
        activity.requestFinished(hls,30_000_000_000L);activity.requestFailed(dash);activity.requestFailed(dash);
        assertEquals(DiscoveryActivity.State.WAITING_FOR_ACTION, activity.snapshot(30_000_000_000L,1).state());
        assertTrue(activity.snapshot(30_000_000_000L,1).unsupportedMediaSeen());
    }

    @Test void cumulativeManifestCountNeverStopsObservation() {
        activity.navigationFinished();
        for(int i=0;i<1000;i++){
            Object request=new Object();activity.requestStarted(request,"HLS",i);
            activity.response(request,"HLS",true);
            assertEquals(DiscoveryActivity.State.READING_PLAYLIST,activity.snapshot(i,i).state());
            assertTrue(activity.requestFinished(request,i+1));
        }
        var snapshot=activity.snapshot(1001,1000);
        assertEquals(DiscoveryActivity.State.WAITING_FOR_ACTION,snapshot.state());assertEquals(0,snapshot.pendingPlaylists());
        assertEquals(1000L,snapshot.inspectedBodies());
    }

    @Test void repeatedMediaDoesNotResetNewSourceTimerAndDoesNotExposeUrls() {
        activity.navigationFinished();activity.catalogChanged(1_000_000_000L);
        for(int i=0;i<200;i++)activity.response(new Object(),"MP4_OR_FRAGMENT",true);
        assertEquals(DiscoveryActivity.State.WAITING_FOR_ACTION,activity.snapshot(20_000_000_000L,0).state());
        assertTrue(activity.snapshot(20_000_000_000L,0).unsupportedMediaSeen());
    }
    @Test void lateResponseCannotResurrectCompletedTimedOutOrPreviousDocumentRequests() {
        activity.navigationFinished();Object done=new Object(),stuck=new Object(),old=new Object();
        activity.requestStarted(done,"HLS",0);assertTrue(activity.requestFinished(done,1));
        activity.response(done,"HLS",true);assertEquals(0,activity.snapshot(2,0).pendingPlaylists());
        activity.requestStarted(stuck,"HLS",0);activity.expire(DiscoveryActivity.REQUEST_TIMEOUT_NANOS);
        activity.response(stuck,"HLS",true);assertFalse(activity.requestFinished(stuck,DiscoveryActivity.REQUEST_TIMEOUT_NANOS+1));
        assertEquals(1,activity.snapshot(DiscoveryActivity.REQUEST_TIMEOUT_NANOS+1,0).timedOutRequests());
        activity.requestStarted(old,null,DiscoveryActivity.REQUEST_TIMEOUT_NANOS);activity.newDocument();
        activity.response(old,"HLS",true);assertFalse(activity.requestFinished(old,DiscoveryActivity.REQUEST_TIMEOUT_NANOS+1));
    }
}
