package br.com.activation;

import static org.assertj.core.api.Assertions.*;
import br.com.activation.application.RequestService;
import br.com.activation.application.RequestStore;
import br.com.activation.domain.Status;
import java.time.Instant;
import java.util.ArrayList;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

abstract class RequestStoreContract {
    @Autowired RequestService service;
    @Autowired RequestStore store;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach void clean() { jdbc.update("delete from activation_requests"); }

    @Test void simultaneousRequestsProduceExactlyOneRecord() throws Exception {
        var ready = new CountDownLatch(12);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(12)) {
            var tasks = new ArrayList<Callable<RequestService.Creation>>();
            for (int i = 0; i < 12; i++) tasks.add(() -> {
                ready.countDown();
                if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Start timeout");
                return service.create("EXT-RACE", "CUST", "NET", "a".repeat(32));
            });
            var futures = tasks.stream().map(executor::submit).toList();
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            var results = new ArrayList<RequestService.Creation>();
            for (var future : futures) results.add(future.get(30, TimeUnit.SECONDS));
            assertThat(results.stream().filter(RequestService.Creation::created).count()).isEqualTo(1);
            assertThat(results.stream().map(result -> result.request().id()).distinct().count()).isEqualTo(1);
            assertThat(jdbc.queryForObject("select count(*) from activation_requests", Integer.class)).isEqualTo(1);
        } finally { start.countDown(); }
    }

    @Test void onlyOneWorkerClaimsAndStaleCompletionCannotOverwriteRecovery() throws Exception {
        var id = service.create("EXT-LEASE", "CUST", "NET", "a".repeat(32)).request().id();
        var now = Instant.now().plusSeconds(1);
        try (var executor = Executors.newFixedThreadPool(8)) {
            var tasks = new ArrayList<Callable<Boolean>>();
            for (int i = 0; i < 8; i++) tasks.add(() -> store.claim(id, now, now.plusSeconds(30), 3).isPresent());
            int winners = 0;
            for (var future : executor.invokeAll(tasks)) if (future.get()) winners++;
            assertThat(winners).isEqualTo(1);
        }
        var old = service.get(id);
        var later = now.plusSeconds(31);
        var recovered = store.claim(id, later, later.plusSeconds(30), 3).orElseThrow();
        store.finish(old.finish(true, false, null, 3, later, later), old.attempts());
        assertThat(service.get(id).status()).isEqualTo(Status.PROCESSING);
        assertThat(service.get(id).attempts()).isEqualTo(2);
        store.finish(recovered.finish(true, false, null, 3, later, later), recovered.attempts());
        assertThat(service.get(id).status()).isEqualTo(Status.COMPLETED);
    }

    @Test void abandonedLastAttemptIsMarkedFailedAfterLeaseExpires() {
        var id = service.create("EXT-EXHAUSTED", "CUST", "NET", "a".repeat(32)).request().id();
        var now = Instant.now().plusSeconds(1);
        assertThat(store.claim(id, now, now.plusSeconds(30), 1)).isPresent();
        assertThat(store.claim(id, now.plusSeconds(31), now.plusSeconds(61), 1)).isEmpty();
        assertThat(service.get(id).status()).isEqualTo(Status.FAILED);
        assertThat(service.get(id).failureCode()).isEqualTo("RETRY_EXHAUSTED");
    }
}
