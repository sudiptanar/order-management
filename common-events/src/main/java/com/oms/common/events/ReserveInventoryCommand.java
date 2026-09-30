package com.oms.common.events;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * @param messageId unique per delivery attempt of a distinct logical message. The consumer stores
 *                  this to make itself idempotent, so it must be generated once by the producer
 *                  and never regenerated on retry.
 */
public record ReserveInventoryCommand(
        UUID messageId,
        UUID sagaId,
        UUID orderId,
        List<OrderLine> lines,
        Instant occurredAt) {
}