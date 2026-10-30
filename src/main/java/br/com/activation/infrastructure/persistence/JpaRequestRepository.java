package br.com.activation.infrastructure.persistence;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

interface JpaRequestRepository extends JpaRepository<RequestEntity, UUID> {
    Optional<RequestEntity> findByExternalId(String externalId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from RequestEntity r where r.id = :id")
    Optional<RequestEntity> lockById(UUID id);

    @Query("select r.id from RequestEntity r where r.status in (br.com.activation.domain.Status.PENDING, br.com.activation.domain.Status.PROCESSING) and r.nextAttemptAt <= :now order by r.nextAttemptAt, r.id")
    List<UUID> findDue(Instant now, Pageable pageable);
}
