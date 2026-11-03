package dev.streambridge.spike;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/** User-expectation warnings, NOT transport/parser security limits. No URLs or credentials. */
record DownloadReview(int segments, BigDecimal seconds, BigDecimal estimatedBytes, long transferredBytes) {
    static final long WARNING_BYTES = 256L * 1024 * 1024;

    static DownloadReview of(List<HlsVodPlaylist> playlists, long declaredBandwidth) {
        int segments = playlists.stream().mapToInt(p -> p.segments().size()).sum();
        // Inputs play concurrently: adding audio duration would double-count the movie.
        BigDecimal seconds = playlists.stream().map(HlsVodPlaylist::durationSeconds)
                .max(BigDecimal::compareTo).orElseThrow();
        // HLS variant BANDWIDTH describes the combination, including its associated audio.
        // This is a rough metadata-based estimate, not an actual or guaranteed maximum size.
        BigDecimal estimate = declaredBandwidth > 0
                ? seconds.multiply(BigDecimal.valueOf(declaredBandwidth)).divide(BigDecimal.valueOf(8)) : null;
        return new DownloadReview(segments, seconds, estimate, 0);
    }

    DownloadReview withTransferredBytes(long bytes) { return new DownloadReview(segments, seconds, estimatedBytes, bytes); }

    boolean needsConfirmation() {
        return segments > 1200 || seconds.compareTo(BigDecimal.valueOf(3600)) > 0
                || estimatedBytes == null || estimatedBytes.compareTo(BigDecimal.valueOf(WARNING_BYTES)) > 0
                || transferredBytes > WARNING_BYTES;
    }

    String summary() {
        return "segments=" + segments + "; durationMinutes=" + seconds.divide(BigDecimal.valueOf(60), 1, RoundingMode.HALF_UP)
                + "; estimatedMiB=" + (estimatedBytes == null ? "unknown" : estimatedBytes.divide(BigDecimal.valueOf(1024 * 1024), 1, RoundingMode.HALF_UP))
                + " (rough manifest estimate, NOT guaranteed); transferredBytes=" + transferredBytes;
    }
}
