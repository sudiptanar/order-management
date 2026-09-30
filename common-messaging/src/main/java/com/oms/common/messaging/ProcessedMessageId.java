package com.oms.common.messaging;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/**
 * Keyed by consumer group as well as message id: two different services (or two different
 * groups on the same topic) must each get their own chance to process a message.
 */
@Embeddable
public class ProcessedMessageId implements Serializable {

    private static final long serialVersionUID = 1L;

    @Column(name = "message_id", nullable = false)
    private UUID messageId;

    @Column(name = "consumer_group", nullable = false, length = 64)
    private String consumerGroup;

    protected ProcessedMessageId() {
    }

    public ProcessedMessageId(UUID messageId, String consumerGroup) {
        this.messageId = messageId;
        this.consumerGroup = consumerGroup;
    }

    public UUID getMessageId() {
        return messageId;
    }

    public String getConsumerGroup() {
        return consumerGroup;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ProcessedMessageId other)) {
            return false;
        }
        return Objects.equals(messageId, other.messageId)
                && Objects.equals(consumerGroup, other.consumerGroup);
    }

    @Override
    public int hashCode() {
        return Objects.hash(messageId, consumerGroup);
    }

    @Override
    public String toString() {
        return messageId + "@" + consumerGroup;
    }
}