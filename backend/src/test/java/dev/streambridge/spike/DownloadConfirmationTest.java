package dev.streambridge.spike;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.concurrent.LinkedBlockingQueue;
import static org.junit.jupiter.api.Assertions.*;

class DownloadConfirmationTest {
    private final DownloadReview review = new DownloadReview(1326, new BigDecimal("6628.747391"), null, 0);

    @Test void onlyCurrentTokenApprovesAndOldApprovalIsNotReusable() {
        var commands = new LinkedBlockingQueue<String>();
        commands.add("confirm previous-token"); commands.add("yes"); commands.add("download");
        assertTrue(DownloadConfirmation.ask(review, commands, () -> false,
                line -> { if (line.startsWith("Type '")) commands.add("confirm current-token"); },
                Duration.ofSeconds(1), "current-token"));
    }

    @Test void cancellationAndEofNeverApprove() {
        for (String command : new String[]{"cancel", "stop"}) {
            var commands = new LinkedBlockingQueue<String>(); commands.add(command);
            assertFalse(DownloadConfirmation.ask(review, commands, () -> false, line -> {}, Duration.ofSeconds(1), "token"));
        }
        var commands = new LinkedBlockingQueue<String>(); commands.add("confirm token");
        assertFalse(DownloadConfirmation.ask(review, commands, () -> true, line -> {}, Duration.ofSeconds(1), "token"));
    }
    @Test void previewNeedsCurrentTokenAndBoundedDurationAndNeverApprovesTransfer() {
        var commands = new LinkedBlockingQueue<String>();
        commands.addAll(java.util.List.of("preview stale 5000", "preview token 0", "preview token 8001",
                "preview token 1.5", "preview token 5000", "preview token 8000", "cancel"));
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        assertFalse(DownloadConfirmation.ask(review, commands, () -> false, line -> {},
                Duration.ofSeconds(1), "token", 8000, millis -> calls.incrementAndGet()));
        assertEquals(2, calls.get());
    }
    @Test void previewPreparationDoesNotConsumeDecisionTimeout() {
        var commands = new LinkedBlockingQueue<String>(); commands.add("preview token 5000");
        assertTrue(DownloadConfirmation.ask(review, commands, () -> false, line -> {},
                Duration.ofMillis(50), "token", 8000, millis -> {
                    try { Thread.sleep(100); } catch (InterruptedException e) { throw new AssertionError(e); }
                    commands.add("confirm token");
                }));
    }

    @Test void timeoutAndInterruptionFailClosedAndPreserveInterrupt() {
        assertFalse(DownloadConfirmation.ask(review, new LinkedBlockingQueue<>(), () -> false, line -> {}, Duration.ZERO, "token"));
        Thread.currentThread().interrupt();
        try {
            assertFalse(DownloadConfirmation.ask(review, new LinkedBlockingQueue<>(), () -> false, line -> {}, Duration.ofSeconds(1), "token"));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
    }
}
