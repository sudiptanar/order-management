package com.oms.payment.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One payment per order, enforced by a unique constraint on {@code order_id}.
 *
 * <p>That constraint is the real protection against double-charging. The idempotency ledger and
 * the application-level checks both sit in front of it, but this is the one guarantee that holds
 * even if the code above it is wrong.
 */
@Entity
@Table(name = "payment")
public class Payment {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id = UUID.randomUUID();

    @Column(name = "order_id", nullable = false, unique = true)
    private UUID orderId;

    @Column(name = "customer_id", nullable = false, length = 64)
    private String customerId;

    @Column(name = "amount", nullable = false)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private PaymentStatus status;

    /** Stands in for the reference a real gateway would hand back. */
    @Column(name = "authorization_ref", length = 64)
    private String authorizationRef;

    @Column(name = "failure_reason", length = 512)
    private String failureReason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    protected Payment() {
    }

    public Payment(UUID orderId, String customerId, BigDecimal amount) {
        this.orderId = orderId;
        this.customerId = customerId;
        this.amount = amount;
    }

    public void authorize() {
        this.status = PaymentStatus.AUTHORIZED;
        this.authorizationRef = "AUTH-" + UUID.randomUUID().toString().substring(0, 12).toUpperCase();
        this.failureReason = null;
        this.updatedAt = Instant.now();
    }

    public void decline(String reason) {
        this.status = PaymentStatus.DECLINED;
        this.failureReason = reason;
        this.updatedAt = Instant.now();
    }

    /** Idempotent: refunding an already-refunded payment is a no-op, not an error. */
    public void refund(String reason) {
        if (this.status == PaymentStatus.REFUNDED) {
            return;
        }
        this.status = PaymentStatus.REFUNDED;
        this.failureReason = reason;
        this.updatedAt = Instant.now();
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getOrderId() {
        return orderId;
    }

    public String getCustomerId() {
        return customerId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public PaymentStatus getStatus() {
        return status;
    }

    public String getAuthorizationRef() {
        return authorizationRef;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}