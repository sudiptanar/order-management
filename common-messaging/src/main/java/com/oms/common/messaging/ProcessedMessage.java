package com.oms.common.messaging;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * The idempotency ledger. One row per (message, consumer group) that has been successfully
 * processed.
 *
 * <p>The row is written inside the same transaction as the business change, which is the whole
 * point: either both land or neither does. If the handler throws, the transaction rolls back,
 * the ledger row disappears, and the redelivery is free to try again.
 */
@Entity
@Table(name = "processed_message")
public class ProcessedMessage {

    @EmbeddedId
    private ProcessedMessageId id;

    @Column(name = "topic", length = 128)
    private String topic;

    @Column(name = "processed_at", nullable = false)
    private Instant processedAt;

    protected ProcessedMessage() {
    }

    public ProcessedMessage(UUID messageId, String consumerGroup, String topic) {
        this.id = new ProcessedMessageId(messageId, consumerGroup);
        this.topic = topic;
        this.processedAt = Instant.now();
    }

    public ProcessedMessageId getId() {
        return id;
    }

    public String getTopic() {
        return topic;
    }

    public Instant getProcessedAt() {
        return processedAt;
    }
}