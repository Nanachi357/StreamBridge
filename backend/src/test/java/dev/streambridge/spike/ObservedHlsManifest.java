package dev.streambridge.spike;

import java.net.URI;
import java.math.BigDecimal;
import java.util.*;

/** Metadata-only HLS subset. Never fetches URLs, keys or segments; not a download authorization. */
record ObservedHlsManifest(Kind kind, List<Variant> variants, List<Audio> audio,
                           int segmentCount, boolean keyTagObserved, BigDecimal durationSeconds) {
    enum Kind { MASTER, MEDIA }
    record Variant(URI uri, long bandwidth, Integer width, Integer height, String audioGroup) {
        String resolution() { return width == null ? "unknown" : width + "x" + height; }
        @Override public String toString() { return "Variant[" + resolution() + ", URI omitted]"; }
    }
    // Missing URI explicitly declares in-band audio. Missing group on a VARIANT means unknown audio.
    record Audio(String group, String name, String language, URI uri) {
        @Override public String toString() { return "Audio[metadata and URI omitted]"; }
    }

    ObservedHlsManifest {
        variants = List.copyOf(variants);
        audio = List.copyOf(audio);
    }

    List<Audio> audioFor(Variant variant) {
        if (!variants.contains(variant)) throw new IllegalArgumentException("Foreign variant");
        return audio.stream().filter(a -> a.group().equals(variant.audioGroup())).toList();
    }

    @Override public String toString() {
        return "ObservedHlsManifest[kind=" + kind + ", variants=" + variants.size() + ", audio=" + audio.size() + "]";
    }

    static ObservedHlsManifest parse(URI base, String text) {
        try {
            return parseChecked(base, text);
        } catch (IllegalArgumentException failure) {
            // URI/parser exceptions can include signed URLs. Do not attach the original cause.
            throw new IllegalArgumentException("Invalid or unsupported HLS metadata");
        }
    }

    private static ObservedHlsManifest parseChecked(URI base, String text) {
        if (text.length() > 262144) throw new IllegalArgumentException();
        resolve(base, base.toString());
        var lines = text.lines().map(String::strip).filter(s -> !s.isEmpty()).toList();
        if (lines.isEmpty() || !lines.getFirst().equals("#EXTM3U")) throw new IllegalArgumentException();
        var variants = new ArrayList<Variant>();
        var audio = new ArrayList<Audio>();
        Map<String, String> pending = null;
        boolean media = false, key = false;
        int segments = 0;
        for (String line : lines.subList(1, lines.size())) {
            if (line.startsWith("#EXT-X-DEFINE:")) throw new IllegalArgumentException();
            if (line.startsWith("#EXTINF:") || line.startsWith("#EXT-X-TARGETDURATION:")) media = true;
            if (line.startsWith("#EXTINF:")) segments++;
            if (line.startsWith("#EXT-X-KEY:") || line.startsWith("#EXT-X-SESSION-KEY:")) {
                String method = required(attributes(line), "METHOD");
                key |= !method.equals("NONE"); // Historical observation: later NONE cannot erase an earlier key.
            } else if (line.startsWith("#EXT-X-MEDIA:")) {
                var attrs = attributes(line);
                if (required(attrs, "TYPE").equals("AUDIO")) {
                    String uri = attrs.get("URI");
                    audio.add(new Audio(required(attrs, "GROUP-ID"), required(attrs, "NAME"),
                            attrs.getOrDefault("LANGUAGE", "und"), uri == null ? null : resolve(base, uri)));
                }
            } else if (line.startsWith("#EXT-X-STREAM-INF:")) {
                if (pending != null) throw new IllegalArgumentException();
                pending = attributes(line);
            } else if (!line.startsWith("#") && pending != null) {
                Integer width = null, height = null;
                if (pending.containsKey("RESOLUTION")) {
                    String size = pending.get("RESOLUTION");
                    if (!size.matches("[0-9]+x[0-9]+")) throw new IllegalArgumentException();
                    String[] dimensions = size.split("x");
                    width = Integer.valueOf(dimensions[0]); height = Integer.valueOf(dimensions[1]);
                    if (width <= 0 || height <= 0) throw new IllegalArgumentException();
                }
                long bandwidth = Long.parseLong(required(pending, "BANDWIDTH"));
                if (bandwidth <= 0) throw new IllegalArgumentException();
                variants.add(new Variant(resolve(base, line), bandwidth, width, height, pending.get("AUDIO")));
                pending = null;
            } else if (!line.startsWith("#") && !media) {
                throw new IllegalArgumentException();
            }
            if (variants.size() > 32 || audio.size() > 32) throw new IllegalArgumentException();
        }
        if (pending != null || (media && !variants.isEmpty()) || (!media && variants.isEmpty())
                || (media && !audio.isEmpty())) throw new IllegalArgumentException();
        var names = new HashSet<List<String>>();
        for (Audio track : audio) {
            if (!names.add(List.of(track.group(), track.name()))) throw new IllegalArgumentException();
        }
        var result = new ObservedHlsManifest(media ? Kind.MEDIA : Kind.MASTER, variants, audio, segments, key,
                media ? completedDuration(lines) : null);
        for (Variant variant : variants) {
            if (variant.audioGroup() != null && result.audioFor(variant).isEmpty()) throw new IllegalArgumentException();
        }
        return result;
    }

    // Display metadata only: a live window is NOT the duration of the entire video.
    // Unknown/malformed durations do not authorize a download or invalidate otherwise useful metadata.
    private static BigDecimal completedDuration(List<String> lines) {
        if (!lines.contains("#EXT-X-ENDLIST")) return null;
        BigDecimal total = BigDecimal.ZERO, pending = null;
        int count = 0;
        boolean ended = false;
        for (String line : lines) {
            if (line.equals("#EXT-X-ENDLIST")) { ended = true; continue; }
            if (line.startsWith("#EXTINF:")) {
                if (pending != null || ended) return null;
                String value = line.substring(8).split(",", 2)[0];
                if (!value.matches("[0-9]{1,9}(\\.[0-9]{1,9})?")) return null;
                pending = new BigDecimal(value);
                if (pending.signum() <= 0) return null;
            } else if (!line.startsWith("#")) {
                if (pending == null || ended) return null;
                total = total.add(pending); pending = null; count++;
            }
        }
        return count > 0 && pending == null ? total : null;
    }

    private static Map<String, String> attributes(String line) {
        return HlsMaster.attributes(line.substring(line.indexOf(':') + 1));
    }

    private static String required(Map<String, String> attrs, String name) {
        String value = attrs.get(name);
        if (value == null || value.isEmpty()) throw new IllegalArgumentException();
        return value;
    }

    private static URI resolve(URI base, String value) {
        if (value.isEmpty() || value.contains("{$") || value.length() > 8192) throw new IllegalArgumentException();
        URI uri = base.resolve(value);
        if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException();
        }
        // Syntax only. This class performs no DNS checks and MUST NOT initiate requests.
        return uri;
    }
}
