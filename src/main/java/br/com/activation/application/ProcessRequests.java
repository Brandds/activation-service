package br.com.activation.application;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

public class ProcessRequests {
    private final RequestStore store;
    private final ActivationProvider provider;
    private final Clock clock;
    private final int maxAttempts;
    private final Duration lease;
    private final Duration backoff;

    public ProcessRequests(RequestStore store, ActivationProvider provider, Clock clock,
            int maxAttempts, Duration lease, Duration backoff) {
        this.store = store;
        this.provider = provider;
        this.clock = clock;
        this.maxAttempts = maxAttempts;
        this.lease = lease;
        this.backoff = backoff;
    }

    public void runBatch() {
        for (var id : store.findDue(clock.instant())) {
            var claimed = store.claim(id, clock.instant(), clock.instant().plus(lease), maxAttempts);
            if (claimed.isEmpty()) continue;
            var request = claimed.get();
            var outcome = provider.activate(request);
            long exponential = backoff.toMillis() * (1L << Math.min(request.attempts() - 1, 10));
            long delay = Math.min(exponential, 300_000) + ThreadLocalRandom.current().nextLong(251);
            var now = clock.instant();
            store.finish(request.finish(outcome.successful(), outcome.retryable(), outcome.code(),
                    maxAttempts, now, now.plusMillis(delay)), request.attempts());
        }
    }
}
