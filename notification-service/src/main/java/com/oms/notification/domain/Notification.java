package com.oms.notification.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * A notification that would have been sent. Persisting it rather than only logging it means the
 * effect of an event is inspectable - and makes it possible to prove that a redelivered event
 * produced exactly one notification, not two.
 */
@Entity
@Table(name = "notification")
public class Notification {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id = UUID.randomUUID();

    @Column(name = "order_id", nullable = false)
    private UUID orderId;

    @Column(name = "customer_id", nullable = false, length = 64)
    private String customerId;

    @Column(name = "channel", nullable = false, length = 16)
    private String channel;

    @Column(name = "event_type", nullable = false, length = 32)
    private String eventType;

    @Column(name = "subject", nullable = false, length = 256)
    private String subject;

    @Column(name = "body", nullable = false, columnDefinition = "text")
    private String body;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected Notification() {
    }

    public Notification(UUID orderId, String customerId, String channel,
                        String eventType, String subject, String body) {
        this.orderId = orderId;
        this.customerId = customerId;
        this.channel = channel;
        this.eventType = eventType;
        this.subject = subject;
        this.body = body;
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

    public String getChannel() {
        return channel;
    }

    public String getEventType() {
        return eventType;
    }

    public String getSubject() {
        return subject;
    }

    public String getBody() {
        return body;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}