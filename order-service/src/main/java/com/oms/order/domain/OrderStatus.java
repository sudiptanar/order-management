package com.oms.order.domain;

/** What the customer sees. Kept separate from {@link SagaState}, which is internal machinery. */
public enum OrderStatus {
    CREATED,
    INVENTORY_RESERVED,
    PAYMENT_COMPLETED,
    CONFIRMED,
    CANCELLED
}