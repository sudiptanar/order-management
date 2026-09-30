package com.oms.order.ops;

/**
 * @param total        every record ever written to the dead-letter topic. Kafka is a log, so a
 *                     replayed record is still there - "drained" means the replay group's offset
 *                     moved past it, not that it was removed.
 * @param pendingReplay records the replay group has not yet worked through. This is the number
 *                     that behaves like a queue depth, and the one to alert on.
 */
public record DltDepth(long total, long pendingReplay) {
}