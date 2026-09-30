package com.oms.common.messaging;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import java.util.List;
import java.util.UUID;

public interface OutboxRepository extends JpaRepository<OutboxMessage, UUID> {

    /**
     * Claims the next batch for publishing.
     *
     * <p>{@code PESSIMISTIC_WRITE} plus a lock timeout of -2 becomes {@code FOR UPDATE SKIP LOCKED}
     * on Postgres, so running several replicas of a service does not mean publishing every message
     * several times: each poller walks past rows another one is already holding.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    @Query("select o from OutboxMessage o where o.status = com.oms.common.messaging.OutboxStatus.PENDING order by o.createdAt asc")
    List<OutboxMessage> claimPendingBatch(Pageable pageable);

    List<OutboxMessage> findByStatusOrderByCreatedAtDesc(OutboxStatus status, Pageable pageable);

    long countByStatus(OutboxStatus status);
}