package br.com.activation.application;

import br.com.activation.domain.ActivationRequest;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RequestStore {
    ActivationRequest insert(ActivationRequest request);
    Optional<ActivationRequest> findByExternalId(String externalId);
    Optional<ActivationRequest> findById(UUID id);
    List<UUID> findDue(Instant now);
    Optional<ActivationRequest> claim(UUID id, Instant now, Instant leaseUntil, int maxAttempts);
    void finish(ActivationRequest result, int expectedAttempt);

    class DuplicateExternalId extends RuntimeException {
        public DuplicateExternalId(Throwable cause) { super(cause); }
    }
}
