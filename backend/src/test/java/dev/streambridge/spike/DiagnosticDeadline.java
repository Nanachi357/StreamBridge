package dev.streambridge.spike;

import java.time.Duration;

/** Last-resort guard for this standalone diagnostic JVM, never an application-wide cancellation policy. */
final class DiagnosticDeadline implements AutoCloseable {
    private final Thread thread;
    private final java.util.concurrent.atomic.AtomicLong heartbeat = new java.util.concurrent.atomic.AtomicLong(System.nanoTime());

    DiagnosticDeadline(Duration budget, Runnable beforeTermination) {
        this(budget,budget,beforeTermination);
    }
    DiagnosticDeadline(Duration budget, Duration silence, Runnable beforeTermination) {
        this(budget, silence, beforeTermination, true);
    }
    static DiagnosticDeadline inactivityOnly(Duration silence, Runnable beforeTermination) {
        return new DiagnosticDeadline(Duration.ZERO, silence, beforeTermination, false);
    }
    private DiagnosticDeadline(Duration budget, Duration silence, Runnable beforeTermination, boolean timed) {
        thread = Thread.ofPlatform().daemon().name("diagnostic-deadline").start(() -> {
            long deadline=System.nanoTime()+budget.toNanos();
            try {
                while((!timed||System.nanoTime()<deadline)&&System.nanoTime()-heartbeat.get()<silence.toNanos())Thread.sleep(200);
            } catch (InterruptedException cancelled) {
                return;
            }
            // Snapshot BEFORE stopping the driver: afterwards its children could be reparented.
            var owned = ProcessHandle.current().descendants().toList();
            try {
                beforeTermination.run();
            } finally {
                for (ProcessHandle child : owned.reversed()) {
                    try { child.destroyForcibly(); } catch (RuntimeException ignored) { }
                }
                // Do not wait for Playwright shutdown hooks which may be blocked themselves.
                Runtime.getRuntime().halt(124);
            }
        });
    }

    @Override public void close() { thread.interrupt(); }
    void pulse(){heartbeat.set(System.nanoTime());}
}
