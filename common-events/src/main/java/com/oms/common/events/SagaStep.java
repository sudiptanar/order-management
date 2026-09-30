package com.oms.common.events;

/** The unit of work a participant performs, and the unit a reply refers to. */
public enum SagaStep {
    RESERVE_INVENTORY,
    PROCESS_PAYMENT,
    REFUND_PAYMENT,
    RELEASE_INVENTORY
}