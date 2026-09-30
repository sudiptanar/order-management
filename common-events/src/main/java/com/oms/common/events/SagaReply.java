package com.oms.common.events;

import java.time.Instant;
import java.util.UUID;

/**
 * A single reply type for every step, carried on one topic.
 *
 * <p>This is deliberate. The alternative - a distinct reply class per step - forces Jackson
 * polymorphic deserialization ({@code @JsonTypeInfo}) onto the orchestrator for no real gain,
 * because the orchestrator already switches on the step to decide what to do next.
 */
public record SagaReply(
        UUID messageId,
        UUID sagaId,
        UUID orderId,
        SagaStep step,
        ReplyStatus status,
        String reason,
        Instant occurredAt) {

    public static SagaReply succeeded(UUID sagaId, UUID orderId, SagaStep step) {
        return new SagaReply(UUID.randomUUID(), sagaId, orderId, step, ReplyStatus.SUCCEEDED, null, Instant.now());
    }

    public static SagaReply failed(UUID sagaId, UUID orderId, SagaStep step, String reason) {
        return new SagaReply(UUID.randomUUID(), sagaId, orderId, step, ReplyStatus.FAILED, reason, Instant.now());
    }

    public boolean isSuccess() {
        return status == ReplyStatus.SUCCEEDED;
    }
}