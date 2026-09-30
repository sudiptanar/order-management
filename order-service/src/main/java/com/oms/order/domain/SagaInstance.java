package com.oms.order.domain;

import com.oms.common.events.SagaStep;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

/**
 * The persisted position of one saga.
 *
 * <p>This row is the reason to prefer orchestration over choreography in a system you have to
 * operate: "why is order 42 stuck?" is a single query here, rather than a reconstruction from the
 * logs of four services.
 */
@Entity
@Table(name = "saga_instance")
public class SagaInstance {

    @Id
    @Column(name = "saga_id", nullable = false)
    private UUID sagaId = UUID.randomUUID();

    @Column(name = "order_id", nullable = false, unique = true)
    private UUID orderId;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 48)
    private SagaState state;

    /** The command currently outstanding, or {@code null} once the saga is finished. */
    @Enumerated(EnumType.STRING)
    @Column(name = "current_step", length = 48)
    private SagaStep currentStep;

    @Column(name = "failure_reason", length = 512)
    private String failureReason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    protected SagaInstance() {
    }

    public SagaInstance(UUID orderId, SagaState initialState, SagaStep initialStep) {
        this.orderId = orderId;
        this.state = initialState;
        this.currentStep = initialStep;
    }

    public void transitionTo(SagaState newState, SagaStep newStep) {
        this.state = newState;
        this.currentStep = newStep;
        this.updatedAt = Instant.now();
    }

    public boolean isInFlight() {
        return state == SagaState.AWAITING_INVENTORY
                || state == SagaState.AWAITING_PAYMENT
                || state == SagaState.COMPENSATING_PAYMENT
                || state == SagaState.COMPENSATING_INVENTORY;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    public UUID getSagaId() {
        return sagaId;
    }

    public UUID getOrderId() {
        return orderId;
    }

    public SagaState getState() {
        return state;
    }

    public SagaStep getCurrentStep() {
        return currentStep;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public void setFailureReason(String failureReason) {
        this.failureReason = failureReason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}