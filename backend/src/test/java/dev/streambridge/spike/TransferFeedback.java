package dev.streambridge.spike;

import java.nio.file.Path;

/** Optional observer/cancellation boundary for the manual transfer, no UI or thread ownership. */
interface TransferFeedback {
    @FunctionalInterface interface PreviewFactory { Path create(long durationMillis) throws Exception; }
    record Preview(long maximumMillis, PreviewFactory factory) {
        Path create() throws Exception { return create(Math.min(5000, maximumMillis)); }
        Path create(long millis) throws Exception {
            if (millis < 1 || millis > maximumMillis) throw new IllegalArgumentException("INVALID_PREVIEW_DURATION");
            return factory.create(millis);
        }
    }
    TransferFeedback NONE = new TransferFeedback() {};
    default boolean alwaysReview() { return false; }
    default boolean cancelled() { return false; }
    default boolean approve(DownloadReview review, java.util.function.Predicate<DownloadReview> fallback, Preview preview) {
        return fallback.test(review);
    }
    default void phase(String phase) {}
    default void progress(int completed, int total, long bytes) {}
    default void file(Path file) {}
    default void failure(String code) {}
    default void cleanupUncertain() {}
    default void cleanupComplete() {}
}
