package br.com.activation.infrastructure.persistence;

import br.com.activation.application.RequestStore;
import br.com.activation.domain.ActivationRequest;
import br.com.activation.domain.Status;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;

@Repository
public class JpaRequestStore implements RequestStore {
    private final JpaRequestRepository repository;
    private final TransactionTemplate transactions;

    public JpaRequestStore(JpaRequestRepository repository, PlatformTransactionManager manager) {
        this.repository = repository;
        this.transactions = new TransactionTemplate(manager);
    }

    @Override
    public ActivationRequest insert(ActivationRequest request) {
        try {
            return transactions.execute(status -> repository.saveAndFlush(RequestEntity.from(request)).toDomain());
        } catch (DataIntegrityViolationException exception) {
            // Only classify an actual externalId collision as a duplicate.
            if (repository.findByExternalId(request.externalId()).isPresent()) {
                throw new DuplicateExternalId(exception);
            }
            throw exception;
        }
    }

    @Override
    public Optional<ActivationRequest> findByExternalId(String externalId) {
        return repository.findByExternalId(externalId).map(RequestEntity::toDomain);
    }

    @Override
    public Optional<ActivationRequest> findById(UUID id) {
        return repository.findById(id).map(RequestEntity::toDomain);
    }

    @Override
    public List<UUID> findDue(Instant now) {
        return repository.findDue(now, PageRequest.of(0, 20));
    }

    @Override
    public Optional<ActivationRequest> claim(UUID id, Instant now, Instant leaseUntil, int maxAttempts) {
        return transactions.execute(transaction -> repository.lockById(id).flatMap(entity -> {
            var request = entity.toDomain();
            if ((request.status() != Status.PENDING && request.status() != Status.PROCESSING)
                    || request.nextAttemptAt().isAfter(now)) return Optional.empty();
            if (request.attempts() >= maxAttempts) {
                entity.apply(request.finish(false, false, "RETRY_EXHAUSTED", maxAttempts, now, now));
                return Optional.empty();
            }
            var claimed = request.start(now, leaseUntil);
            entity.apply(claimed);
            return Optional.of(claimed);
        }));
    }

    @Override
    public void finish(ActivationRequest result, int expectedAttempt) {
        transactions.executeWithoutResult(transaction -> repository.lockById(result.id()).ifPresent(entity -> {
            // Fencing: an expired worker cannot overwrite a more recent attempt.
            if (entity.status == Status.PROCESSING && entity.attempts == expectedAttempt) entity.apply(result);
        }));
    }
}
