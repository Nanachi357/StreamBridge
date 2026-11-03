package dev.streambridge.spike;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Console-only approval for one reviewed transfer; never a persistent bypass flag. */
final class DownloadConfirmation {
    static boolean ask(DownloadReview review, BlockingQueue<String> commands, BooleanSupplier eof, Consumer<String> log) {
        return ask(review, commands, eof, log, Duration.ofMinutes(2), UUID.randomUUID().toString());
    }

    static boolean ask(DownloadReview review, BlockingQueue<String> commands, BooleanSupplier eof,
                       Consumer<String> log, Duration timeout, String token) {
        return ask(review, commands, eof, log, timeout, token, 0, null);
    }
    static boolean ask(DownloadReview review, BlockingQueue<String> commands, BooleanSupplier eof,
                       Consumer<String> log, Duration timeout, String token, long maximumMillis,
                       java.util.function.LongConsumer preview) {
        log.accept("DOWNLOAD_CONFIRM_REQUIRED " + review.summary());
        log.accept("Type 'confirm " + token + "' to continue THIS transfer, or 'cancel'. No answer/EOF cancels after " + timeout.toSeconds() + " seconds.");
        long deadline = System.nanoTime() + timeout.toNanos();
        try {
            while (!eof.getAsBoolean()) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) return false;
                String command = commands.poll(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(200)), TimeUnit.NANOSECONDS);
                if (command == null) continue;
                if (eof.getAsBoolean()) return false;
                command = command.strip();
                if (command.equals("confirm " + token)) return true;
                if (command.equals("cancel") || command.equals("stop")) return false;
                String prefix = "preview " + token + " ";
                if (command.startsWith(prefix) && preview != null) {
                    try {
                        long millis = Long.parseLong(command.substring(prefix.length()));
                        if (millis > 0 && millis <= maximumMillis) {
                            preview.accept(millis);
                            deadline = System.nanoTime() + timeout.toNanos(); // Exclude preparation time.
                            continue;
                        }
                    } catch (NumberFormatException invalid) { /* Not a valid preview command. */ }
                }
                log.accept("DOWNLOAD_CONFIRM_PENDING: use the current confirmation token or cancel; other commands are not approvals.");
            }
        } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        return false;
    }
}
