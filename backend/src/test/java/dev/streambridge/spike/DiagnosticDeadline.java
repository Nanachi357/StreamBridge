package dev.streambridge.spike;

import java.time.Duration;

/** Last-resort guard for this standalone diagnostic JVM, never an application-wide cancellation policy. */
final class DiagnosticDeadline implements AutoCloseable {
    private enum Phase { STARTING, OBSERVING, CLOSED, EXPIRED }
    private final Thread thread;
    private final long startupNanos, budgetNanos, silenceNanos;
    private final long started = System.nanoTime();
    private long observingSince = started, heartbeat = started;
    private Phase phase;

    DiagnosticDeadline(Duration budget, Runnable beforeTermination) {
        this(budget, budget, beforeTermination);
    }
    DiagnosticDeadline(Duration budget, Duration silence, Runnable beforeTermination) {
        this(Duration.ZERO, budget, silence, beforeTermination, beforeTermination);
    }
    static DiagnosticDeadline inactivityOnly(Duration silence, Runnable beforeTermination) {
        return new DiagnosticDeadline(Duration.ZERO, Duration.ZERO, silence, beforeTermination, beforeTermination);
    }
    static DiagnosticDeadline starting(Duration startup, Duration observationBudget, Duration silence,
                                       Runnable startupTimeout, Runnable unresponsive) {
        if (startup.isZero()) throw new IllegalArgumentException("Startup budget must be positive");
        return new DiagnosticDeadline(startup, observationBudget, silence, startupTimeout, unresponsive);
    }
    private DiagnosticDeadline(Duration startup, Duration budget, Duration silence,
                               Runnable startupTimeout, Runnable unresponsive) {
        if (startup.isNegative() || budget.isNegative() || silence.isNegative() || silence.isZero())
            throw new IllegalArgumentException("Invalid diagnostic deadline");
        startupNanos = startup.toNanos(); budgetNanos = budget.toNanos(); silenceNanos = silence.toNanos();
        phase = startup.isZero() ? Phase.OBSERVING : Phase.STARTING;
        thread = Thread.ofPlatform().daemon().name("diagnostic-deadline").start(() -> {
            Runnable termination;
            try {
                while (true) {
                    synchronized (this) {
                        if (phase == Phase.CLOSED) return;
                        long now = System.nanoTime();
                        if (phase == Phase.STARTING && now - started >= startupNanos) {
                            phase = Phase.EXPIRED; termination = startupTimeout; break;
                        }
                        if (phase == Phase.OBSERVING && ((budgetNanos > 0 && now - observingSince >= budgetNanos)
                                || now - heartbeat >= silenceNanos)) {
                            phase = Phase.EXPIRED; termination = unresponsive; break;
                        }
                    }
                    Thread.sleep(200);
                }
            } catch (InterruptedException cancelled) { return; }
            // Snapshot BEFORE stopping the driver: afterwards its children could be reparented.
            var owned = ProcessHandle.current().descendants().toList();
            try {
                termination.run();
            } finally {
                for (ProcessHandle child : owned.reversed()) {
                    try { child.destroyForcibly(); } catch (RuntimeException ignored) { }
                }
                // Do not wait for Playwright shutdown hooks which may be blocked themselves.
                Runtime.getRuntime().halt(124);
            }
        });
    }

    /** Called once, only after browser/context/page creation has completed. */
    synchronized void beginObservation() {
        if (phase != Phase.STARTING) throw new IllegalStateException("Not starting");
        long now = System.nanoTime();
        if (now - started >= startupNanos) throw new IllegalStateException("Startup deadline expired");
        observingSince = heartbeat = now;
        phase = Phase.OBSERVING;
    }
    @Override public synchronized void close() {
        if (phase != Phase.EXPIRED) { phase = Phase.CLOSED; thread.interrupt(); }
    }
    synchronized void pulse() {
        if (phase == Phase.OBSERVING) heartbeat = System.nanoTime();
    }
}
