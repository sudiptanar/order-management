package com.oms.common.events;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A fact, published to {@link Topics#ORDER_EVENTS}. Past tense, no instruction implied.
 * Subscribers decide for themselves what it means to them.
 */
public record OrderEvent(
        UUID messageId,
        UUID orderId,
        String customerId,
        OrderEventType type,
        BigDecimal totalAmount,
        String detail,
        Instant occurredAt) {

    public static OrderEvent of(UUID orderId, String customerId, OrderEventType type,
                                BigDecimal totalAmount, String detail) {
        return new OrderEvent(UUID.randomUUID(), orderId, customerId, type, totalAmount, detail, Instant.now());
    }
}