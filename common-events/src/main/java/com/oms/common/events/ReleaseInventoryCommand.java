package com.oms.common.events;

import java.time.Instant;
import java.util.UUID;

/** Compensation for {@link ReserveInventoryCommand}. Must be safe to apply twice. */
public record ReleaseInventoryCommand(
        UUID messageId,
        UUID sagaId,
        UUID orderId,
        String reason,
        Instant occurredAt) {
}