package com.oms.common.events;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Compensation for {@link ProcessPaymentCommand}. Must be safe to apply twice. */
public record RefundPaymentCommand(
        UUID messageId,
        UUID sagaId,
        UUID orderId,
        BigDecimal amount,
        String reason,
        Instant occurredAt) {
}