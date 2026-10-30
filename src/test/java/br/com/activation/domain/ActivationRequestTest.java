package br.com.activation.domain;

import static org.assertj.core.api.Assertions.*;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class ActivationRequestTest {
    private final Instant now = Instant.parse("2026-10-30T12:00:00Z");

    @Test void completesOnlyAfterProcessing() {
        var pending = ActivationRequest.create("EXT-1", "CUST-1", "NET", now, "a".repeat(32));
        assertThatThrownBy(() -> pending.finish(true, false, null, 3, now, now))
                .isInstanceOf(IllegalStateException.class);
        var completed = pending.start(now, now.plusSeconds(30)).finish(true, false, null, 3, now, now);
        assertThat(completed.status()).isEqualTo(Status.COMPLETED);
        assertThatThrownBy(() -> completed.start(now, now.plusSeconds(30)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test void temporaryFailureRetriesUntilLimit() {
        var request = ActivationRequest.create("EXT-1", "CUST-1", "NET", now, "a".repeat(32));
        var retry = request.start(now, now.plusSeconds(30))
                .finish(false, true, "PROVIDER_500", 2, now, now.plusSeconds(2));
        assertThat(retry.status()).isEqualTo(Status.PENDING);
        assertThatThrownBy(() -> retry.start(now, now.plusSeconds(30)))
                .isInstanceOf(IllegalStateException.class);
        var failed = retry.start(now.plusSeconds(2), now.plusSeconds(32))
                .finish(false, true, "PROVIDER_500", 2, now.plusSeconds(2), now.plusSeconds(4));
        assertThat(failed.status()).isEqualTo(Status.FAILED);
        assertThat(failed.attempts()).isEqualTo(2);
    }

    @Test void rejectsPermanentFailureWithoutRetry() {
        var request = ActivationRequest.create("EXT-1", "CUST-1", "NET", now, "a".repeat(32))
                .start(now, now.plusSeconds(30)).finish(false, false, "PROVIDER_400", 3, now, now);
        assertThat(request.status()).isEqualTo(Status.FAILED);
    }
}
