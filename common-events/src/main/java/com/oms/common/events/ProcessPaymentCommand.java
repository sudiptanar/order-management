package com.oms.common.events;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record ProcessPaymentCommand(
        UUID messageId,
        UUID sagaId,
        UUID orderId,
        String customerId,
        BigDecimal amount,
        Instant occurredAt) {
}