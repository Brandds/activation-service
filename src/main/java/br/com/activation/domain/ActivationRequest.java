package br.com.activation.domain;

import java.time.Instant;
import java.util.UUID;

public record ActivationRequest(UUID id, String externalId, String customerId, String productCode,
        Status status, Instant createdAt, Instant updatedAt, int attempts,
        Instant nextAttemptAt, String failureCode, String traceId) {

    public static ActivationRequest create(String externalId, String customerId, String productCode,
            Instant now, String traceId) {
        return new ActivationRequest(UUID.randomUUID(), externalId, customerId, productCode,
                Status.PENDING, now, now, 0, now, null, traceId);
    }

    public boolean sameContent(String customerId, String productCode) {
        return this.customerId.equals(customerId) && this.productCode.equals(productCode);
    }

    public ActivationRequest start(Instant now, Instant leaseUntil) {
        boolean ready = status == Status.PENDING || status == Status.PROCESSING;
        if (!ready || nextAttemptAt.isAfter(now)) {
            throw new IllegalStateException("Solicitação não está disponível para processamento");
        }
        return copy(Status.PROCESSING, now, attempts + 1, leaseUntil, null);
    }

    public ActivationRequest finish(boolean successful, boolean retryable, String code,
            int maxAttempts, Instant now, Instant retryAt) {
        if (status != Status.PROCESSING) {
            throw new IllegalStateException("Apenas solicitações em processamento podem ser finalizadas");
        }
        Status target = successful ? Status.COMPLETED
                : retryable && attempts < maxAttempts ? Status.PENDING : Status.FAILED;
        return copy(target, now, target == Status.PENDING ? retryAt : now, code);
    }

    private ActivationRequest copy(Status target, Instant now, Instant next, String code) {
        return copy(target, now, attempts, next, code);
    }

    private ActivationRequest copy(Status target, Instant now, int count, Instant next, String code) {
        return new ActivationRequest(id, externalId, customerId, productCode, target,
                createdAt, now, count, next, code, traceId);
    }
}
