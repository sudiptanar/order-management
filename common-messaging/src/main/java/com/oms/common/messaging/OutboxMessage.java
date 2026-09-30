package com.oms.common.messaging;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One row per message that still has to reach Kafka.
 *
 * <p>Why this table exists: {@code repository.save(order)} followed by
 * {@code kafkaTemplate.send(event)} is two separate commits to two separate systems. Crash
 * between them and the order exists while the event never happened - silently, with no error
 * anywhere. Writing the intent to this table inside the business transaction makes the pair
 * atomic; a poller then moves rows to Kafka, retrying until it succeeds.
 *
 * <p>The cost is at-least-once publishing (a crash after send but before the status update
 * republishes the message), which is exactly what {@link IdempotencyGuard} absorbs on the
 * consumer side.
 */
@Entity
@Table(name = "outbox")
public class OutboxMessage {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "aggregate_type", nullable = false, length = 64)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false, length = 64)
    private String aggregateId;

    @Column(name = "topic", nullable = false, length = 128)
    private String topic;

    @Column(name = "message_key", length = 128)
    private String messageKey;

    /** Fully-qualified class of {@link #payload}, so the poller can rebuild the typed object. */
    @Column(name = "payload_type", nullable = false, length = 255)
    private String payloadType;

    @Column(name = "payload", nullable = false, columnDefinition = "text")
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private OutboxStatus status;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "last_error", length = 1024)
    private String lastError;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    protected OutboxMessage() {
    }

    public OutboxMessage(String aggregateType, String aggregateId, String topic,
                         String messageKey, String payloadType, String payload) {
        this.id = UUID.randomUUID();
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.topic = topic;
        this.messageKey = messageKey;
        this.payloadType = payloadType;
        this.payload = payload;
        this.status = OutboxStatus.PENDING;
        this.attempts = 0;
        this.createdAt = Instant.now();
    }

    public void markSent() {
        this.status = OutboxStatus.SENT;
        this.publishedAt = Instant.now();
        this.lastError = null;
    }

    /** Stays PENDING until the attempts are used up, then parks as FAILED for an operator. */
    public void markAttemptFailed(String error, int maxAttempts) {
        this.attempts++;
        this.lastError = error == null ? null : error.substring(0, Math.min(error.length(), 1024));
        if (this.attempts >= maxAttempts) {
            this.status = OutboxStatus.FAILED;
        }
    }

    public UUID getId() {
        return id;
    }

    public String getAggregateType() {
        return aggregateType;
    }

    public String getAggregateId() {
        return aggregateId;
    }

    public String getTopic() {
        return topic;
    }

    public String getMessageKey() {
        return messageKey;
    }

    public String getPayloadType() {
        return payloadType;
    }

    public String getPayload() {
        return payload;
    }

    public OutboxStatus getStatus() {
        return status;
    }

    public int getAttempts() {
        return attempts;
    }

    public String getLastError() {
        return lastError;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }
}