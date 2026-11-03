package dev.streambridge.spike;

import java.net.URI;
import java.math.BigDecimal;
import java.util.*;
import java.util.function.Function;

/** Single-thread-owned observation catalog with revision-checked choices. Never fetches media. */
final class HlsCandidateCatalog {
    static final int RETAINED_MANIFESTS = 100;
    // Keep room for the protocol envelope below LocalUiSession's 256 KiB line bound.
    static final int SNAPSHOT_CHARACTERS = 192 * 1024;
    enum AudioSource { UNKNOWN, IN_BAND_DECLARED, SEPARATE_PLAYLIST }
    record AudioView(String id, String language, String name, AudioSource source, boolean playlistObserved) {
        @Override public String toString() {
            return "AudioView[id=" + id + ", language=" + language + ", source=" + source
                    + ", playlistObserved=" + playlistObserved + ", name omitted]";
        }
    }
    record PlayerView(String id, String resource) { }
    // An empty audio list means UNKNOWN, never a claim that the variant has no audio.
    record VariantView(String id, String resolution, long bandwidth, boolean playlistObserved,
                       List<AudioView> audio, BigDecimal durationSeconds) {
        VariantView { audio = List.copyOf(audio); }
    }
    record CandidateView(String id, String resource, ObservedHlsManifest.Kind kind, List<String> frames,
                         List<VariantView> variants, int segmentCount, boolean keyTagObserved, boolean metadataUsable,
                         List<PlayerView> players, BigDecimal durationSeconds, long selectionRevision) {
        CandidateView { frames = List.copyOf(frames); variants = List.copyOf(variants); players = List.copyOf(players); }
    }
    record Snapshot(long revision, List<CandidateView> candidates, long evictedManifests,
                    String pinnedCandidateId, String pinNotice) {
        Snapshot { candidates = List.copyOf(candidates); }
    }
    record Choice(long revision, String candidateId, String variantId, String audioId,
                  URI master, URI media, URI separateAudio, AudioSource audioSource, String resolution) {
        @Override public String toString() {
            return "Choice[revision=" + revision + ", " + candidateId + "/" + variantId + "/" + audioId
                    + ", resolution=" + resolution + ", audio=" + audioSource + ", URLs omitted]";
        }
    }

    private static final class Entry {
        final String id;
        final Set<String> frames = new LinkedHashSet<>();
        final Set<PlayerView> players = new LinkedHashSet<>();
        ObservedHlsManifest manifest;
        String mediaBody;
        boolean keyEverObserved;
        boolean lastParseValid = true;
        long lastSeen;
        long selectionRevision;
        SelectionState selectionState;
        Entry(String id) { this.id = id; }
    }
    // Deliberately excludes display provenance, observed duration and rolling media-segment bodies.
    private record SelectionState(ObservedHlsManifest manifest, boolean usable, boolean protectedMedia) { }
    // Identity is the exact observed URL, NOT its short display hash. URLs never leave via a view.
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private final Function<String, String> redact;
    private long revision;
    private long nextId, observationSequence, evictedManifests;
    private long nextSelectionRevision;
    private String pinnedCandidateId = "", pinNotice = "";

    HlsCandidateCatalog(Function<String, String> redact) { this.redact = Objects.requireNonNull(redact); }

    String observe(String url, String frameUrl, String body) {
        return observe(url, frameUrl, "", body);
    }

    String observe(String url, String frameUrl, String frameId, String body) {
        ObservedHlsManifest parsed;
        try {
            parsed = ObservedHlsManifest.parse(URI.create(url), body);
        } catch (IllegalArgumentException failure) {
            Entry previous = entries.get(url);
            if (previous != null && previous.lastParseValid) {
                previous.lastParseValid = false;
                revision++;
            }
            throw new IllegalArgumentException("Invalid or unsupported HLS metadata");
        }
        String frame = redact.apply(frameUrl);
        var player = new PlayerView(frameId, frame);
        Entry entry = entries.get(url);
        if (entry == null) {
            if (entries.size() >= RETAINED_MANIFESTS) {
                evictOldest();
            }
            entry = new Entry("C" + (++nextId));
            entries.put(url, entry);
        }
        entry.lastSeen = ++observationSequence;
        String mediaBody = parsed.kind() == ObservedHlsManifest.Kind.MEDIA ? body : null;
        boolean changed = !parsed.equals(entry.manifest) || !Objects.equals(mediaBody, entry.mediaBody) || !entry.lastParseValid
                || (entry.frames.size() < 8 && !entry.frames.contains(frame))
                || (entry.players.size() < 8 && !entry.players.contains(player));
        entry.manifest = parsed; // Retain old metadata on failure, but mark it unusable for choices.
        entry.mediaBody = mediaBody;
        entry.lastParseValid = true;
        entry.keyEverObserved |= parsed.keyTagObserved();
        if (entry.frames.size() < 8) entry.frames.add(frame);
        if (entry.players.size() < 8) entry.players.add(player);
        preserveKeyEvidence();
        if (changed) {
            revision++;
            // Bound the retained UI representation as well as the number of raw manifests.
            while (!entries.isEmpty() && UiProtocol.json(versionedSnapshot()).length() > SNAPSHOT_CHARACTERS) evictOldest();
        }
        return entry.id;
    }

    Snapshot versionedSnapshot() { return new Snapshot(revision, snapshot(), evictedManifests, pinnedCandidateId, pinNotice); }
    long revision() { return revision; }
    void clear() { entries.clear();evictedManifests=0;pinnedCandidateId="";pinNotice="";revision++; }

    void pin(long sourceRevision, String candidateId) {
        if(candidateId.isEmpty()) { pinnedCandidateId="";pinNotice="";revision++;return; }
        var source=sourceAtRevision(sourceRevision,candidateId);
        if(source.kind()!=ObservedHlsManifest.Kind.MASTER||!source.metadataUsable()||source.keyTagObserved())
            throw new IllegalArgumentException("SOURCE_NOT_SELECTABLE");
        pinnedCandidateId=candidateId;pinNotice="";revision++;
    }
    private CandidateView sourceAtRevision(long sourceRevision, String candidateId) {
        var source=snapshot().stream().filter(c->c.id().equals(candidateId)).findFirst()
                .orElseThrow(()->new IllegalArgumentException("SOURCE_NOT_AVAILABLE"));
        if(source.selectionRevision()!=sourceRevision)throw new IllegalArgumentException("SOURCE_CHANGED");
        return source;
    }
    Choice choosePinned(long sourceRevision,String candidateId,String variantId,String audioId) {
        if(!candidateId.equals(pinnedCandidateId))throw new IllegalArgumentException("SOURCE_NOT_PINNED");
        sourceAtRevision(sourceRevision,candidateId);
        // Rebase only after source-scoped validation; the frozen transfer still uses a global revision.
        return choose(revision,candidateId,variantId,audioId);
    }
    private Set<String> pinnedResources() {
        var protectedUrls=new HashSet<String>();
        entries.forEach((url,entry)->{
            if(entry.id.equals(pinnedCandidateId)) {
                protectedUrls.add(url);
                entry.manifest.variants().forEach(v->protectedUrls.add(v.uri().toString()));
                entry.manifest.audio().stream().filter(a->a.uri()!=null).forEach(a->protectedUrls.add(a.uri().toString()));
            }
        });
        return protectedUrls;
    }

    private void evictOldest() {
        var protectedUrls=pinnedResources();
        var oldest=entries.entrySet().stream().filter(e->!protectedUrls.contains(e.getKey()))
                .min(Comparator.comparingLong(e->e.getValue().lastSeen));
        if(oldest.isEmpty()) {
            // A changed pinned family alone exceeded the UI budget. Explicitly invalidate, never grow unbounded.
            pinnedCandidateId="";pinNotice="PIN_METADATA_TOO_LARGE";revision++;
            evictOldest();return;
        }
        // Retention is not permission to forget encryption evidence for a retained parent.
        preserveKeyEvidence();
        entries.remove(oldest.orElseThrow().getKey());evictedManifests++;revision++;
    }

    private void preserveKeyEvidence() {
        for (Entry entry : entries.values()) {
            if (entry.manifest == null) continue;
            entry.keyEverObserved |= entry.manifest.variants().stream().anyMatch(v -> keyObserved(v.uri()))
                    || entry.manifest.audio().stream().anyMatch(a -> a.uri()!=null && keyObserved(a.uri()));
        }
    }

    Optional<String> mediaPlaylistBody(URI uri) {
        Entry entry = entries.get(uri.toString());
        return entry == null || !entry.lastParseValid ? Optional.empty() : Optional.ofNullable(entry.mediaBody);
    }

    Choice choose(long expectedRevision, String candidateId, String variantId, String audioId) {
        if (expectedRevision != revision) throw new IllegalArgumentException("STALE_CATALOG: run catalog again");
        var root = snapshot().stream().filter(c -> c.id().equals(candidateId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("UNKNOWN_CANDIDATE"));
        if (!root.metadataUsable()) throw new IllegalArgumentException("METADATA_UNUSABLE: await a valid refresh");
        if (root.keyTagObserved()) throw new IllegalArgumentException("KEY_OBSERVED: encrypted candidates are not selectable in this spike");
        if (root.kind() != ObservedHlsManifest.Kind.MASTER) throw new IllegalArgumentException("MASTER_REQUIRED: unassociated media selection not implemented");
        var view = root.variants().stream().filter(v -> v.id().equals(variantId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("UNKNOWN_VARIANT"));
        var item = entries.entrySet().stream().filter(e -> e.getValue().id.equals(candidateId)).findFirst().orElseThrow();
        var manifest = item.getValue().manifest;
        var variant = manifest.variants().get(root.variants().indexOf(view));
        URI separate = null;
        AudioSource source = AudioSource.UNKNOWN;
        if (view.audio().isEmpty()) {
            if (!"unknown".equals(audioId)) throw new IllegalArgumentException("AUDIO_UNKNOWN: explicitly use unknown, not an invented track");
        } else {
            var audio = view.audio().stream().filter(a -> a.id().equals(audioId)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("INCOMPATIBLE_AUDIO: choose an A id listed for this variant"));
            // A ids index this master's audio list, never a global registry.
            var track = manifest.audio().get(Integer.parseInt(audio.id().substring(1)) - 1);
            separate = track.uri();
            source = audio.source();
        }
        return new Choice(revision, candidateId, variantId, audioId, URI.create(item.getKey()), variant.uri(),
                separate, source, variant.resolution());
    }

    boolean isCurrent(Choice choice) {
        if (choice == null || choice.revision() != revision) return false;
        try { return choice.equals(choose(choice.revision(), choice.candidateId(), choice.variantId(), choice.audioId())); }
        catch (IllegalArgumentException rejected) { return false; }
    }

    List<CandidateView> snapshot() {
        Set<String> children = new HashSet<>();
        for (Entry entry : entries.values()) {
            entry.manifest.variants().forEach(v -> children.add(v.uri().toString()));
            entry.manifest.audio().stream().filter(a -> a.uri() != null).forEach(a -> children.add(a.uri().toString()));
        }
        var result = new ArrayList<CandidateView>();
        entries.forEach((url, entry) -> {
            ObservedHlsManifest manifest = entry.manifest;
            // Arrival order must not turn each child playlist into another independent video.
            if (manifest.kind() == ObservedHlsManifest.Kind.MEDIA && children.contains(url)) return;
            var variants = new ArrayList<VariantView>();
            for (int v = 0; v < manifest.variants().size(); v++) {
                var variant = manifest.variants().get(v);
                var audio = new ArrayList<AudioView>();
                for (var track : manifest.audioFor(variant)) {
                    // NAME is associated through this variant's exact AUDIO group, not page text.
                    // This untrusted display label is local-UI-only; the shareable report is an allowlist.
                    String language = track.language().matches("[A-Za-z]{2,8}(-[A-Za-z0-9]{1,8})*") ? track.language() : "und";
                    audio.add(new AudioView("A" + (manifest.audio().indexOf(track) + 1), language, displayName(track.name()),
                            track.uri() == null ? AudioSource.IN_BAND_DECLARED : AudioSource.SEPARATE_PLAYLIST,
                            track.uri() != null && mediaObserved(track.uri())));
                }
                variants.add(new VariantView("V" + (v + 1), variant.resolution(), variant.bandwidth(), mediaObserved(variant.uri()), audio,
                        duration(variant.uri())));
            }
            boolean protectedMedia=entry.keyEverObserved
                    || manifest.variants().stream().anyMatch(v -> keyObserved(v.uri()))
                    || manifest.audio().stream().anyMatch(a -> a.uri() != null && keyObserved(a.uri()));
            boolean usable=entry.lastParseValid
                    && manifest.variants().stream().noneMatch(v -> parseRejected(v.uri()))
                    && manifest.audio().stream().noneMatch(a -> a.uri() != null && parseRejected(a.uri()));
            var selectionState=new SelectionState(manifest,usable,protectedMedia);
            if(!selectionState.equals(entry.selectionState)) { entry.selectionState=selectionState;entry.selectionRevision=++nextSelectionRevision; }
            result.add(new CandidateView(entry.id, redact.apply(url), manifest.kind(), List.copyOf(entry.frames),
                    variants, manifest.segmentCount(), protectedMedia, usable,
                    List.copyOf(entry.players), entry.lastParseValid ? manifest.durationSeconds() : null,entry.selectionRevision));
        });
        return List.copyOf(result);
    }

    private boolean mediaObserved(URI uri) {
        Entry entry = entries.get(uri.toString());
        return entry != null && entry.manifest.kind() == ObservedHlsManifest.Kind.MEDIA;
    }

    private BigDecimal duration(URI uri) {
        Entry entry = entries.get(uri.toString());
        return entry == null || !entry.lastParseValid ? null : entry.manifest.durationSeconds();
    }

    private static String displayName(String name) {
        // Do not render controls/bidi overrides; never turn labels into HTML or links.
        String clean = name.replaceAll("[\\p{Cc}\\p{Cf}]", "").strip();
        return clean.codePoints().limit(120).collect(StringBuilder::new,
                StringBuilder::appendCodePoint, StringBuilder::append).toString();
    }

    private boolean keyObserved(URI uri) {
        Entry entry = entries.get(uri.toString());
        return entry != null && entry.keyEverObserved;
    }

    private boolean parseRejected(URI uri) {
        Entry entry = entries.get(uri.toString());
        return entry != null && !entry.lastParseValid;
    }
}
