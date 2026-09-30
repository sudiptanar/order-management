package com.oms.common.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;

/**
 * Kafka gives at-least-once delivery, so every consumer must assume it will see the same
 * message more than once - after a rebalance, after a failed commit, after an outbox republish.
 *
 * <p>Usage, always inside the handler's own transaction:
 * <pre>
 * if (!guard.firstTimeSeeing(cmd.messageId(), GROUP, topic)) {
 *     return;   // already applied; acknowledge and move on
 * }
 * </pre>
 *
 * <p>{@link Propagation#MANDATORY} is not decoration: it makes it a startup-visible error to call
 * this outside a transaction, where the ledger row could commit while the business change did not.
 */
@Service
public class IdempotencyGuard {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyGuard.class);

    private final ProcessedMessageRepository repository;

    public IdempotencyGuard(ProcessedMessageRepository repository) {
        this.repository = repository;
    }

    /**
     * @return {@code true} if this is the first time the group has seen the message, meaning the
     *         caller should go ahead and apply it. {@code false} if it is a duplicate.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean firstTimeSeeing(UUID messageId, String consumerGroup, String topic) {
        if (messageId == null) {
            // Without an id there is nothing to deduplicate on, and retrying cannot help.
            throw new NonRetryableMessageException("Message on " + topic + " carries no messageId");
        }
        ProcessedMessageId id = new ProcessedMessageId(messageId, consumerGroup);
        if (repository.existsById(id)) {
            log.info("Skipping duplicate message {} on {} (group {})", messageId, topic, consumerGroup);
            return false;
        }
        // If two consumers race past existsById, the composite primary key rejects the loser.
        // That rolls its transaction back, the message is redelivered, and this check then
        // returns false - so the duplicate is still only applied once.
        repository.save(new ProcessedMessage(messageId, consumerGroup, topic));
        return true;
    }
}