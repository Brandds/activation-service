package br.com.activation.infrastructure.persistence;

import br.com.activation.domain.ActivationRequest;
import br.com.activation.domain.Status;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "activation_requests")
class RequestEntity {
    @Id UUID id;
    @Column(nullable = false, length = 100) String externalId;
    @Column(nullable = false, length = 100) String customerId;
    @Column(nullable = false, length = 100) String productCode;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) Status status;
    @Column(nullable = false) Instant createdAt;
    @Column(nullable = false) Instant updatedAt;
    @Column(nullable = false) int attempts;
    @Column(nullable = false) Instant nextAttemptAt;
    @Column(length = 50) String failureCode;
    @Column(nullable = false, length = 32) String traceId;
    @Version long version;

    protected RequestEntity() { }

    static RequestEntity from(ActivationRequest request) {
        var entity = new RequestEntity();
        entity.id = request.id();
        entity.externalId = request.externalId();
        entity.customerId = request.customerId();
        entity.productCode = request.productCode();
        entity.createdAt = request.createdAt();
        entity.traceId = request.traceId();
        entity.apply(request);
        return entity;
    }

    void apply(ActivationRequest request) {
        status = request.status();
        updatedAt = request.updatedAt();
        attempts = request.attempts();
        nextAttemptAt = request.nextAttemptAt();
        failureCode = request.failureCode();
    }

    ActivationRequest toDomain() {
        return new ActivationRequest(id, externalId, customerId, productCode, status,
                createdAt, updatedAt, attempts, nextAttemptAt, failureCode, traceId);
    }
}
