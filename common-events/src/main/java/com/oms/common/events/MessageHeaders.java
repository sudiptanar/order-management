package com.oms.common.events;

/** Custom Kafka headers this system attaches, for tracing a saga through the log. */
public final class MessageHeaders {

    public static final String MESSAGE_ID = "X-Message-Id";
    public static final String SAGA_ID = "X-Saga-Id";
    public static final String ORDER_ID = "X-Order-Id";
    public static final String OUTBOX_ID = "X-Outbox-Id";

    private MessageHeaders() {
    }
}