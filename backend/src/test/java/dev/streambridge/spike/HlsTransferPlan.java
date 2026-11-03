package dev.streambridge.spike;

import java.net.URI;
import java.util.*;

/** Non-executable draft: observed playlist structure is NOT network or track validation. */
record HlsTransferPlan(HlsCandidateCatalog.Choice choice, List<Input> inputs) {
    enum Role { SELECTED_VARIANT, SEPARATE_AUDIO }
    enum Stage { NEEDS_PLAYLISTS, NEEDS_PREFLIGHT }
    enum RequiredCheck { NETWORK_POLICY_AND_ACCESS, ACTUAL_TRACKS_AND_MAPPING, OUTPUT_AND_CONTAINER, TRANSFER_LIMITS }
    // metadata=null explicitly means the chosen playlist has not been successfully observed.
    record Input(Role role, URI playlist, HlsVodPlaylist metadata) {
        @Override public String toString() {
            return "Input[role=" + role + ", " + (metadata == null ? "playlist needed" : metadata) + ", URI omitted]";
        }
    }
    HlsTransferPlan { inputs = List.copyOf(inputs); }
    Stage stage() { return inputs.stream().anyMatch(i -> i.metadata() == null) ? Stage.NEEDS_PLAYLISTS : Stage.NEEDS_PREFLIGHT; }
    List<RequiredCheck> requiredChecks() { return List.of(RequiredCheck.values()); }
    boolean isCurrent(HlsCandidateCatalog catalog) { return catalog.isCurrent(choice); }

    static HlsTransferPlan build(HlsCandidateCatalog catalog, HlsCandidateCatalog.Choice choice) {
        if (choice == null) throw new IllegalArgumentException("NO_SELECTION");
        if (!catalog.isCurrent(choice)) throw new IllegalArgumentException("STALE_OR_INVALID_SELECTION");
        var inputs = new ArrayList<Input>();
        inputs.add(input(catalog, Role.SELECTED_VARIANT, choice.media()));
        if (choice.audioSource() == HlsCandidateCatalog.AudioSource.SEPARATE_PLAYLIST) {
            inputs.add(input(catalog, Role.SEPARATE_AUDIO, choice.separateAudio()));
        }
        return new HlsTransferPlan(choice, inputs);
    }

    private static Input input(HlsCandidateCatalog catalog, Role role, URI uri) {
        try { HlsVodPlaylist.checkedUri(uri, uri.toString()); }
        catch (IllegalArgumentException failure) { throw new IllegalArgumentException("UNSUPPORTED_INPUT_URI"); }
        var body = catalog.mediaPlaylistBody(uri);
        return new Input(role, uri, body.map(text -> HlsVodPlaylist.parse(uri, text)).orElse(null));
    }
}
