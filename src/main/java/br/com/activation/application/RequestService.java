package br.com.activation.application;

import br.com.activation.domain.ActivationRequest;
import java.time.Clock;
import java.util.UUID;

public class RequestService {
    private final RequestStore store;
    private final Clock clock;

    public RequestService(RequestStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    public Creation create(String externalId, String customerId, String productCode, String traceId) {
        var existing = store.findByExternalId(externalId);
        if (existing.isPresent()) return replay(existing.get(), customerId, productCode);
        try {
            return new Creation(store.insert(ActivationRequest.create(
                    externalId, customerId, productCode, clock.instant(), traceId)), true);
        } catch (RequestStore.DuplicateExternalId exception) {
            // The losing insert has already rolled back in the persistence adapter.
            return replay(store.findByExternalId(externalId).orElseThrow(() -> exception),
                    customerId, productCode);
        }
    }

    public ActivationRequest get(UUID id) {
        return store.findById(id).orElseThrow(NotFound::new);
    }

    private Creation replay(ActivationRequest request, String customerId, String productCode) {
        if (!request.sameContent(customerId, productCode)) throw new Conflict();
        return new Creation(request, false);
    }

    public record Creation(ActivationRequest request, boolean created) { }
    public static class NotFound extends RuntimeException { }
    public static class Conflict extends RuntimeException { }
}
