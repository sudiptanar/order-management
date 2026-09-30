package com.oms.order.saga;

import com.oms.common.events.SagaReply;
import com.oms.common.events.Topics;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

/**
 * Kept deliberately thin. Anything the listener does itself is outside the orchestrator's
 * transaction, and a retry would not repeat it.
 */
@Component
public class SagaReplyListener {

    private final OrderSagaOrchestrator orchestrator;

    public SagaReplyListener(OrderSagaOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    /**
     * {@code groupId} is explicit and shares its constant with the orchestrator's idempotency
     * ledger key, so the two cannot drift apart. Left unset, a listener's {@code id} quietly
     * becomes its {@code group.id} instead, and the ledger would record a group name that Kafka
     * has never heard of.
     */
    @KafkaListener(topics = Topics.SAGA_REPLIES,
            id = "saga-replies",
            groupId = OrderSagaOrchestrator.CONSUMER_GROUP)
    public void onSagaReply(SagaReply reply, @Header(KafkaHeaders.RECEIVED_TOPIC) String topic) {
        orchestrator.handle(reply, topic);
    }
}