package dev.streambridge.spike;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.net.URI;
import java.util.Collections;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class DownloadReviewTest {
    private HlsVodPlaylist playlist(int count, String seconds) {
        return new HlsVodPlaylist(Collections.nCopies(count,
                new HlsVodPlaylist.Segment(URI.create("https://example.org/segment"), BigDecimal.ONE)),
                new BigDecimal(seconds), 3, HlsVodPlaylist.CacheDirective.YES);
    }

    @Test void segmentAndDurationThresholdsAreStrictlyAboveNotAtBoundary() {
        assertFalse(DownloadReview.of(List.of(playlist(1200, "3600")), 1).needsConfirmation());
        assertTrue(DownloadReview.of(List.of(playlist(1201, "60")), 1).needsConfirmation());
        assertTrue(DownloadReview.of(List.of(playlist(1, "3600.001")), 1).needsConfirmation());
    }

    @Test void unknownAndLargeSizeRequireConfirmationEvenWithFewSegments() {
        assertTrue(DownloadReview.of(List.of(playlist(1, "60")), 0).needsConfirmation());
        assertTrue(DownloadReview.of(List.of(playlist(1, "60")), -1).needsConfirmation());
        assertFalse(DownloadReview.of(List.of(playlist(1, "8")), DownloadReview.WARNING_BYTES).needsConfirmation());
        assertTrue(DownloadReview.of(List.of(playlist(1, "8")), DownloadReview.WARNING_BYTES + 1).needsConfirmation());
    }

    @Test void separateAudioCountsRequestsButDoesNotDoubleDurationOrBandwidth() {
        var review = DownloadReview.of(List.of(playlist(700, "1000"), playlist(700, "999.5")), 8000);
        assertEquals(1400, review.segments());
        assertEquals(new BigDecimal("1000"), review.seconds());
        assertEquals(0, review.estimatedBytes().compareTo(new BigDecimal("1000000")));
        assertTrue(review.needsConfirmation());
    }

    @Test void understatedMetadataDoesNotSuppressActualByteWarning() {
        var review = DownloadReview.of(List.of(playlist(10, "60")), 1);
        assertFalse(review.withTransferredBytes(DownloadReview.WARNING_BYTES).needsConfirmation());
        assertTrue(review.withTransferredBytes(DownloadReview.WARNING_BYTES + 1).needsConfirmation());
    }

    @Test void largeMetadataDoesNotOverflowOrLeakResourceUrls() {
        var review = DownloadReview.of(List.of(playlist(1, "99999999999999")), Long.MAX_VALUE);
        assertTrue(review.needsConfirmation());
        assertTrue(review.estimatedBytes().signum() > 0);
        assertFalse(review.summary().contains("example.org"));
        assertTrue(review.summary().contains("NOT guaranteed"));
    }
}
