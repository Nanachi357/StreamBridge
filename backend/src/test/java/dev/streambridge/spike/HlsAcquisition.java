package dev.streambridge.spike;

import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.LongSupplier;

/** Small, sequential, in-memory acquisition experiment. Does NOT validate codecs or produce a video. */
final class HlsAcquisition {
    enum Failure { STALE_OR_INVALID_PLAN, INVALID_PLAYLIST, PLAYLIST_CHANGED, PAYLOAD_LIMIT,
        TIMEOUT, INTERRUPTED, FETCH_FAILED, EMPTY_SEGMENT, CACHE_REPLAY_DISALLOWED }
    static final class AcquisitionException extends IOException {
        private final Failure failure;
        private final BoundedResourceFetcher.Failure fetchFailure;
        AcquisitionException(Failure failure) { this(failure, null); }
        AcquisitionException(Failure failure, BoundedResourceFetcher.Failure fetchFailure) {
            super(failure.name()); this.failure = failure; this.fetchFailure = fetchFailure;
        }
        Failure failure() { return failure; }
        BoundedResourceFetcher.Failure fetchFailure() { return fetchFailure; }
    }
    record Limits(long maxTotalBytes, int maxRequests, Duration total, BoundedResourceFetcher.Limits resource) {
        Limits {
            Objects.requireNonNull(total); Objects.requireNonNull(resource);
            if (maxTotalBytes < 1 || maxTotalBytes > 32 * 1024 * 1024 || maxRequests < 1 || maxRequests > 1000
                    || total.compareTo(Duration.ofMillis(1)) < 0 || total.compareTo(Duration.ofMinutes(2)) > 0) {
                throw new IllegalArgumentException("Invalid acquisition limits");
            }
        }
    }
    record Input(HlsTransferPlan.Role role, HlsVodPlaylist playlist, List<BoundedResourceFetcher.Resource> segments) {
        Input { segments = List.copyOf(segments); }
    }
    record Result(List<Input> inputs, long payloadBytes, int requestAttempts) {
        Result { inputs = List.copyOf(inputs); }
    }
    @FunctionalInterface interface Fetch {
        BoundedResourceFetcher.Resource get(URI uri, BoundedResourceFetcher.Limits limits,
                                           BoundedResourceFetcher.RequestBudget requests) throws BoundedResourceFetcher.FetchException;
    }
    private final Fetch fetch;
    private final LongSupplier nanoTime;
    HlsAcquisition(BoundedResourceFetcher fetcher) { this(fetcher::fetch); }
    // Test seam; production policy is owned by BoundedResourceFetcher, not by the playlist parser.
    HlsAcquisition(Fetch fetch) { this(fetch, System::nanoTime); }
    HlsAcquisition(Fetch fetch, LongSupplier nanoTime) {
        this.fetch = Objects.requireNonNull(fetch); this.nanoTime = Objects.requireNonNull(nanoTime);
    }

    Result acquire(HlsCandidateCatalog catalog, HlsTransferPlan plan, Limits limits) throws AcquisitionException {
        var budget = new Budget(limits, nanoTime);
        validatePlan(catalog, plan);
        var playlists = new ArrayList<HlsVodPlaylist>();
        // Refresh every selected playlist, even when observed earlier. Validate ALL before segments.
        for (var input : plan.inputs()) {
            var resource = get(input.playlist(), true, budget);
            HlsVodPlaylist parsed;
            try {
                String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(resource.bytes())).toString();
                parsed = HlsVodPlaylist.parse(resource.finalUri(), text);
            } catch (CharacterCodingException | IllegalArgumentException invalid) {
                throw new AcquisitionException(Failure.INVALID_PLAYLIST);
            }
            if (parsed.cacheDirective() == HlsVodPlaylist.CacheDirective.NO) {
                throw new AcquisitionException(Failure.CACHE_REPLAY_DISALLOWED);
            }
            if (input.metadata() != null && !input.metadata().equals(parsed)) {
                throw new AcquisitionException(Failure.PLAYLIST_CHANGED);
            }
            playlists.add(parsed);
            budget.check();
        }
        // Detect an impossible request budget before transferring any segment; redirects may need more.
        long segmentCount = playlists.stream().mapToLong(p -> p.segments().size()).sum();
        if (segmentCount > limits.maxRequests() - budget.requests.used()) {
            throw new AcquisitionException(Failure.FETCH_FAILED, BoundedResourceFetcher.Failure.REQUEST_LIMIT);
        }
        var acquired = new ArrayList<Input>();
        for (int i = 0; i < playlists.size(); i++) {
            var playlist = playlists.get(i);
            var segments = new ArrayList<BoundedResourceFetcher.Resource>();
            for (var segment : playlist.segments()) {
                var resource = get(segment.uri(), false, budget);
                if (resource.size() == 0) throw new AcquisitionException(Failure.EMPTY_SEGMENT);
                segments.add(resource); // Preserve order and repeated references; no cross-track guessing.
            }
            acquired.add(new Input(plan.inputs().get(i).role(), playlist, segments));
        }
        budget.check();
        validatePlan(catalog, plan);
        return new Result(acquired, budget.usedBytes, budget.requests.used());
    }

    private BoundedResourceFetcher.Resource get(URI uri, boolean playlist, Budget budget) throws AcquisitionException {
        budget.check();
        long remainingBytes = budget.limits.maxTotalBytes() - budget.usedBytes;
        if (remainingBytes <= 0) throw new AcquisitionException(Failure.PAYLOAD_LIMIT);
        var perResource = budget.limits.resource();
        int cap = (int) Math.min(remainingBytes, perResource.maxBytes());
        if (playlist) cap = Math.min(cap, HlsVodPlaylist.MAX_CHARACTERS); // A stricter UTF-8 BYTE cap.
        long remainingMillis = budget.remainingNanos() / 1_000_000;
        if (remainingMillis < 1) throw new AcquisitionException(Failure.TIMEOUT);
        var requestLimits = new BoundedResourceFetcher.Limits(cap, perResource.maxRedirects(),
                Duration.ofMillis(Math.min(remainingMillis, perResource.total().toMillis())), perResource.connect(), perResource.idle());
        BoundedResourceFetcher.Resource resource;
        try { resource = fetch.get(uri, requestLimits, budget.requests); }
        catch (BoundedResourceFetcher.FetchException error) {
            Failure failure = switch (error.failure()) {
                case TIMEOUT -> Failure.TIMEOUT;
                case INTERRUPTED -> Failure.INTERRUPTED;
                default -> Failure.FETCH_FAILED;
            };
            throw new AcquisitionException(failure, error.failure());
        }
        budget.check();
        int bytes = resource.size();
        if (bytes > cap) throw new AcquisitionException(Failure.PAYLOAD_LIMIT);
        budget.usedBytes += bytes;
        return resource;
    }

    private static void validatePlan(HlsCandidateCatalog catalog, HlsTransferPlan plan) throws AcquisitionException {
        try {
            if (plan == null || !HlsTransferPlan.build(catalog, plan.choice()).equals(plan)) throw new IllegalArgumentException();
        } catch (IllegalArgumentException invalid) { throw new AcquisitionException(Failure.STALE_OR_INVALID_PLAN); }
    }
    private static final class Budget {
        final Limits limits;
        final LongSupplier nanoTime;
        final long started;
        final long durationNanos;
        final BoundedResourceFetcher.RequestBudget requests;
        long usedBytes;
        Budget(Limits limits, LongSupplier nanoTime) {
            this.limits = Objects.requireNonNull(limits);
            this.nanoTime = nanoTime;
            started = nanoTime.getAsLong();
            durationNanos = limits.total().toNanos();
            requests = new BoundedResourceFetcher.RequestBudget(limits.maxRequests());
        }
        void check() throws AcquisitionException {
            if (Thread.currentThread().isInterrupted()) throw new AcquisitionException(Failure.INTERRUPTED);
            if (remainingNanos() <= 0) throw new AcquisitionException(Failure.TIMEOUT);
        }
        long remainingNanos() { return durationNanos - (nanoTime.getAsLong() - started); }
    }
}
