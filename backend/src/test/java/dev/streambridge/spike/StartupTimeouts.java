package dev.streambridge.spike;

import java.time.Duration;

/** Cold Windows startup budgets, not delays: successful processes proceed immediately. */
final class StartupTimeouts {
    private StartupTimeouts() { }
    static final Duration MEDIA = Duration.ofSeconds(60);
    static final Duration BROWSER = Duration.ofSeconds(60);
    // Two media probes + browser launch, with room for driver
    // extraction, page verification and cleanup. The child remains bounded even
    // when native process creation or a library call does not honor its own timeout.
    static final Duration DEPENDENCIES = Duration.ofMinutes(4);
}
