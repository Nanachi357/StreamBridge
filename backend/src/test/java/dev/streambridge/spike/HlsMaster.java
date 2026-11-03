package dev.streambridge.spike;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Small, deliberately limited HLS master parser for the selection experiment, not full HLS support. */
record HlsMaster(URI uri, List<Variant> variants, List<Audio> audio) {
    record Variant(URI uri, long bandwidth, int width, int height, String audioGroup) { }
    record Audio(URI uri, String group, String name, String language) { }
    record Selection(URI master, Variant video, Audio audio) { }

    HlsMaster {
        variants = List.copyOf(variants);
        audio = List.copyOf(audio);
    }

    List<Audio> audioFor(Variant variant) {
        if (!variants.contains(variant)) throw new IllegalArgumentException("Variant does not belong to this master");
        // Group IDs are LOCAL to this master, never keys in a global audio registry.
        return audio.stream().filter(track -> track.group().equals(variant.audioGroup())).toList();
    }

    Selection select(int videoIndex, int audioIndex) {
        if (videoIndex < 0 || videoIndex >= variants.size()) throw new IllegalArgumentException("Invalid video selection");
        Variant video = variants.get(videoIndex);
        List<Audio> compatible = audioFor(video);
        if (audioIndex < 0 || audioIndex >= compatible.size()) throw new IllegalArgumentException("Invalid compatible audio selection");
        return new Selection(uri, video, compatible.get(audioIndex));
    }

    static boolean isMaster(String text) {
        return text.lines().anyMatch(line -> line.startsWith("#EXT-X-STREAM-INF:"));
    }

    static HlsMaster parse(URI base, String text) {
        if (text.length() > 65_536) throw new IllegalArgumentException("Manifest exceeds spike limit");
        List<String> lines = text.lines().map(String::strip).filter(line -> !line.isEmpty()).toList();
        if (lines.isEmpty() || !lines.getFirst().equals("#EXTM3U")) throw new IllegalArgumentException("Not an HLS manifest");
        var variants = new ArrayList<Variant>();
        var audio = new ArrayList<Audio>();
        Map<String, String> pending = null;
        for (String line : lines.subList(1, lines.size())) {
            if (line.startsWith("#EXTINF:") || line.startsWith("#EXT-X-TARGETDURATION:")) {
                throw new IllegalArgumentException("Expected master, not media playlist");
            }
            if (line.startsWith("#EXT-X-SESSION-KEY:") || line.startsWith("#EXT-X-DEFINE:")) {
                throw new IllegalArgumentException("Encryption/variable substitution not supported by this spike");
            }
            if (line.startsWith("#EXT-X-MEDIA:")) {
                Map<String, String> attrs = attributes(line.substring("#EXT-X-MEDIA:".length()));
                if (!required(attrs, "TYPE").equals("AUDIO")) continue;
                audio.add(new Audio(resolve(base, required(attrs, "URI")), required(attrs, "GROUP-ID"),
                        required(attrs, "NAME"), attrs.getOrDefault("LANGUAGE", "und")));
            } else if (line.startsWith("#EXT-X-STREAM-INF:")) {
                if (pending != null) throw new IllegalArgumentException("Variant URI missing");
                pending = attributes(line.substring("#EXT-X-STREAM-INF:".length()));
            } else if (!line.startsWith("#")) {
                if (pending == null) throw new IllegalArgumentException("URI without variant attributes");
                String[] resolution = required(pending, "RESOLUTION").split("x", -1);
                if (resolution.length != 2) throw new IllegalArgumentException("Invalid resolution");
                int width = Integer.parseInt(resolution[0]);
                int height = Integer.parseInt(resolution[1]);
                long bandwidth = Long.parseLong(required(pending, "BANDWIDTH"));
                if (width <= 0 || height <= 0 || bandwidth <= 0) throw new IllegalArgumentException("Invalid variant dimensions/bandwidth");
                variants.add(new Variant(resolve(base, line), bandwidth, width, height, required(pending, "AUDIO")));
                pending = null;
            }
            if (audio.size() > 32 || variants.size() > 32) throw new IllegalArgumentException("Too many tracks for spike");
        }
        if (pending != null || variants.isEmpty()) throw new IllegalArgumentException("Missing variant URI or variants");
        var names = new HashSet<List<String>>();
        for (Audio track : audio) {
            if (!names.add(List.of(track.group(), track.name()))) throw new IllegalArgumentException("Duplicate audio name in group");
        }
        HlsMaster result = new HlsMaster(base, variants, audio);
        for (Variant variant : variants) {
            if (result.audioFor(variant).isEmpty()) throw new IllegalArgumentException("Unknown/empty audio group: " + variant.audioGroup());
        }
        return result;
    }

    private static URI resolve(URI base, String value) {
        URI result = base.resolve(value);
        if (!("http".equals(result.getScheme()) || "https".equals(result.getScheme()))
                || result.getHost() == null || result.getUserInfo() != null || result.getFragment() != null) {
            throw new IllegalArgumentException("Unsupported media URI: " + result);
        }
        // This is syntax validation only. Caller MUST enforce the fixture's exact URL allowlist.
        return result;
    }

    private static String required(Map<String, String> attributes, String key) {
        String value = attributes.get(key);
        if (value == null || value.isEmpty()) throw new IllegalArgumentException("Missing/unsupported attribute: " + key);
        return value;
    }

    private static final Pattern ATTRIBUTE = Pattern.compile("([A-Z0-9-]+)=(?:\"([^\"\\r\\n]*)\"|([^,\"\\s]+))(,|$)");

    static Map<String, String> attributes(String text) {
        var result = new HashMap<String, String>();
        var matcher = ATTRIBUTE.matcher(text);
        int position = 0;
        while (position < text.length()) {
            matcher.region(position, text.length());
            if (!matcher.lookingAt()) throw new IllegalArgumentException("Malformed HLS attribute list");
            String value = matcher.group(2) != null ? matcher.group(2) : matcher.group(3);
            if (result.putIfAbsent(matcher.group(1), value) != null) throw new IllegalArgumentException("Duplicate HLS attribute");
            position = matcher.end();
            if (position == text.length() && matcher.group(4).equals(",")) throw new IllegalArgumentException("Trailing comma");
        }
        return result;
    }
}
