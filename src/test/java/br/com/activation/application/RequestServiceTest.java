package br.com.activation.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import br.com.activation.domain.ActivationRequest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class RequestServiceTest {
    @Test void rereadsWinnerAfterConcurrentInsertRollsBack() {
        var store = mock(RequestStore.class);
        var clock = Clock.fixed(Instant.parse("2026-10-30T12:00:00Z"), ZoneOffset.UTC);
        var winner = ActivationRequest.create("EXT", "CUST", "NET", clock.instant(), "a".repeat(32));
        when(store.findByExternalId("EXT")).thenReturn(Optional.empty(), Optional.of(winner));
        when(store.insert(any())).thenThrow(new RequestStore.DuplicateExternalId(new RuntimeException()));
        var result = new RequestService(store, clock).create("EXT", "CUST", "NET", "b".repeat(32));
        assertThat(result.created()).isFalse();
        assertThat(result.request().id()).isEqualTo(winner.id());
    }
}
