package com.oms.order.repository;

import com.oms.order.domain.SagaInstance;
import com.oms.order.domain.SagaState;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SagaInstanceRepository extends JpaRepository<SagaInstance, UUID> {

    Optional<SagaInstance> findByOrderId(UUID orderId);

    List<SagaInstance> findByStateInAndUpdatedAtBefore(List<SagaState> states, Instant cutoff);

    long countByState(SagaState state);
}