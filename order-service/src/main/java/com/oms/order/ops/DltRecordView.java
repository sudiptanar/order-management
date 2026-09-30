package com.oms.order.ops;

import java.time.Instant;

/** A dead-lettered record, flattened into something readable over HTTP. */
public record DltRecordView(
        String topic,
        int partition,
        long offset,
        String key,
        String payload,
        String originalTopic,
        String exceptionType,
        String exceptionMessage,
        Instant timestamp) {
}