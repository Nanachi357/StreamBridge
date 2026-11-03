package dev.streambridge.spike;

import java.util.IdentityHashMap;
import java.util.Map;

/** Observation health, not proof that a dynamic page has no other streams. Browser-thread owned. */
final class DiscoveryActivity {
    enum State { LOADING_PAGE, READING_PLAYLIST, WAITING_FOR_ACTION }
    record Snapshot(State state, int remainingSeconds, int lastChangeAgoSeconds,
                    int pendingPlaylists, long inspectedBodies, boolean unsupportedMediaSeen,
                    int timedOutRequests, int failedRequests, int closedExtraTabs, int skippedEncodedManifests) { }
    private final long deadline;
    private final boolean timed;
    static final long REQUEST_TIMEOUT_NANOS = 15_000_000_000L;
    private static final class Pending {
        final long started;
        boolean playlist;
        Pending(long now, boolean playlist) { started=now;this.playlist=playlist; }
    }
    private final Map<Object, Pending> pending = new IdentityHashMap<>();
    private boolean navigated, changed, unsupported;
    private long lastChange;
    private int timeouts, failures, extraTabs;
    private int encodedSkipped;

    DiscoveryActivity(long startedNanos, int observeMillis) {
        if(observeMillis<0)throw new IllegalArgumentException("Negative observation duration");
        timed=observeMillis!=0;
        lastChange = startedNanos;
        deadline = startedNanos + observeMillis * 1_000_000L;
    }
    boolean expired(long now) { return timed && now>=deadline; }
    void navigationFinished() { navigated = true; }
    void newDocument() { pending.clear();navigated=false;changed=false;unsupported=false;encodedSkipped=0; }
    void encodedManifestSkipped() { if(encodedSkipped<Integer.MAX_VALUE)encodedSkipped++; }
    void extraTabClosed() { extraTabs++; }
    void requestStarted(Object request, String kind, long now) {
        if(pending.size()<1024)pending.put(request,new Pending(now,isPlaylist(kind)));
    }
    void response(Object request, String kind, boolean ok) {
        if ("DASH".equals(kind) || "MP4_OR_FRAGMENT".equals(kind) || "MEDIA_RESPONSE".equals(kind)) unsupported = true;
        Pending entry=pending.get(request);
        // Never resurrect a request already completed by a reentrant Playwright callback.
        if(entry!=null)entry.playlist=ok&&isPlaylist(kind);
        if(!ok) { pending.remove(request);failures++; }
    }
    boolean requestFinished(Object request,long now) {
        Pending entry=pending.remove(request);
        if(entry==null)return false;
        if(now-entry.started>=REQUEST_TIMEOUT_NANOS){if(entry.playlist)timeouts++;return false;}
        return true;
    }
    void requestFailed(Object request) { if(pending.remove(request)!=null)failures++; }
    void expire(long now) {
        pending.values().removeIf(entry->{
            if(now-entry.started<REQUEST_TIMEOUT_NANOS)return false;
            if(entry.playlist)timeouts++;
            return true;
        });
    }
    void catalogChanged(long now) { changed = true; lastChange = now; }
    Snapshot snapshot(long now, long inspectedBodies) {
        expire(now);
        int playlists=(int)pending.values().stream().filter(p->p.playlist).count();
        State state = !navigated ? State.LOADING_PAGE
                : playlists>0 ? State.READING_PLAYLIST : State.WAITING_FOR_ACTION;
        return new Snapshot(state, timed ? (int)Math.max(0, (deadline - now + 999_999_999L) / 1_000_000_000L) : -1,
                changed ? (int)Math.max(0, (now - lastChange) / 1_000_000_000L) : -1,
                playlists, inspectedBodies, unsupported,timeouts,failures,extraTabs,encodedSkipped);
    }
    private static boolean isPlaylist(String kind){return "HLS".equals(kind)||"DASH".equals(kind);}
}
