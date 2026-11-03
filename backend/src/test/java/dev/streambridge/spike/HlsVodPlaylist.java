package dev.streambridge.spike;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.util.*;

/** Strict, intentionally narrow metadata parser. Resolves references but performs no I/O. */
record HlsVodPlaylist(List<Segment> segments, BigDecimal durationSeconds, int protocolVersion, CacheDirective cacheDirective) {
    enum CacheDirective { UNSPECIFIED, YES, NO }
    static final int MAX_CHARACTERS = 262144;
    static final int MAX_SEGMENTS = 10000;
    record Segment(URI uri, BigDecimal durationSeconds) {
        @Override public String toString() { return "Segment[duration=" + durationSeconds + ", URI omitted]"; }
    }
    HlsVodPlaylist { segments = List.copyOf(segments); }
    @Override public String toString() { return "HlsVodPlaylist[segments=" + segments.size() + ", seconds=" + durationSeconds
            + ", protocolVersion=" + protocolVersion + ", cache=" + cacheDirective + "]"; }

    static HlsVodPlaylist parse(URI base, String body) {
        try { return parseChecked(base, body); }
        catch (IllegalArgumentException invalid) {
            // Do not echo untrusted tags, titles, signed URLs or nested URI exceptions.
            throw new IllegalArgumentException("UNSUPPORTED_OR_INVALID_VOD_PLAYLIST");
        }
    }

    private static HlsVodPlaylist parseChecked(URI base, String body) {
        if (body.length() > MAX_CHARACTERS) throw new IllegalArgumentException();
        checkedUri(base, base.toString());
        var lines = body.lines().map(String::strip).filter(s -> !s.isEmpty()).toList();
        if (lines.isEmpty() || !lines.getFirst().equals("#EXTM3U")) throw new IllegalArgumentException();
        var result = new ArrayList<Segment>();
        Set<String> singletonTags = new HashSet<>();
        BigDecimal pending = null, total = BigDecimal.ZERO;
        Long target = null;
        int protocolVersion = 1; // HLS default when EXT-X-VERSION is absent, not a quality score.
        CacheDirective cacheDirective = CacheDirective.UNSPECIFIED;
        boolean ended = false;
        for (String line : lines.subList(1, lines.size())) {
            if (line.startsWith("#") && !line.startsWith("#EXT")) continue; // Ordinary comment.
            if (ended) throw new IllegalArgumentException();
            if (line.startsWith("#EXTINF:")) {
                if (pending != null) throw new IllegalArgumentException();
                int comma = line.indexOf(',');
                if (comma < 0) throw new IllegalArgumentException();
                String duration = line.substring(8, comma);
                if (!duration.matches("[0-9]{1,10}(\\.[0-9]{1,9})?")) throw new IllegalArgumentException();
                pending = new BigDecimal(duration);
                if (pending.signum() <= 0) throw new IllegalArgumentException();
            } else if (line.startsWith("#")) {
                String tag = line.split(":", 2)[0];
                if (!singletonTags.add(tag)) throw new IllegalArgumentException();
                switch (tag) {
                    case "#EXT-X-VERSION" -> {
                        long version = number(line, tag);
                        if (version < 1 || version > 7) throw new IllegalArgumentException();
                        protocolVersion = (int) version;
                    }
                    case "#EXT-X-TARGETDURATION" -> {
                        target = number(line, tag);
                        if (target < 1) throw new IllegalArgumentException();
                    }
                    case "#EXT-X-MEDIA-SEQUENCE" -> number(line, tag);
                    case "#EXT-X-ALLOW-CACHE" -> {
                        // Legacy syntax is explicit, not a blanket exemption for unknown tags.
                        if (line.equals(tag + ":YES")) cacheDirective = CacheDirective.YES;
                        else if (line.equals(tag + ":NO")) cacheDirective = CacheDirective.NO;
                        else throw new IllegalArgumentException();
                    }
                    case "#EXT-X-PLAYLIST-TYPE" -> {
                        if (!line.equals("#EXT-X-PLAYLIST-TYPE:VOD")) throw new IllegalArgumentException();
                    }
                    case "#EXT-X-INDEPENDENT-SEGMENTS" -> {
                        if (!line.equals(tag)) throw new IllegalArgumentException();
                    }
                    case "#EXT-X-ENDLIST" -> {
                        if (!line.equals(tag) || pending != null) throw new IllegalArgumentException();
                        ended = true;
                    }
                    // Includes KEY (even METHOD=NONE), MAP, BYTERANGE, GAP, DISCONTINUITY,
                    // PART/PRELOAD-HINT, variable substitution and unknown extensions.
                    default -> throw new IllegalArgumentException();
                }
            } else {
                if (pending == null || result.size() >= MAX_SEGMENTS) throw new IllegalArgumentException();
                result.add(new Segment(checkedUri(base, line), pending));
                total = total.add(pending);
                pending = null;
            }
        }
        if (!ended || target == null || result.isEmpty() || pending != null) throw new IllegalArgumentException();
        BigDecimal targetDuration = BigDecimal.valueOf(target);
        for (Segment segment : result) {
            if (segment.durationSeconds().setScale(0, RoundingMode.HALF_UP).compareTo(targetDuration) > 0) {
                throw new IllegalArgumentException();
            }
        }
        return new HlsVodPlaylist(result, total, protocolVersion, cacheDirective);
    }

    private static long number(String line, String tag) {
        if (!line.startsWith(tag + ":")) throw new IllegalArgumentException();
        String value = line.substring(tag.length() + 1);
        if (!value.matches("[0-9]{1,18}")) throw new IllegalArgumentException();
        return Long.parseLong(value);
    }

    static URI checkedUri(URI base, String value) {
        if (value.isEmpty() || value.length() > 8192 || value.contains("{$") || value.contains("\\")) {
            throw new IllegalArgumentException();
        }
        URI uri = base.resolve(value);
        PublicWebPolicy.host(uri); // Syntax only; deliberately does not resolve DNS.
        if (uri.getFragment() != null) throw new IllegalArgumentException();
        return uri;
    }
}
