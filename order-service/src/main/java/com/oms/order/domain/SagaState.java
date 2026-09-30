package com.oms.order.domain;

/**
 * The orchestrator's state machine.
 *
 * <pre>
 *   AWAITING_INVENTORY --ok--> AWAITING_PAYMENT --ok--> [local confirm] --> COMPLETED
 *         |                          |                        |
 *       fail                       fail                      fail
 *         |                          |                        |
 *         v                          v                        v
 *       FAILED          COMPENSATING_INVENTORY <---- COMPENSATING_PAYMENT
 *                                    |
 *                                    v
 *                               COMPENSATED
 * </pre>
 *
 * <p>Compensation runs in reverse order of the forward steps, which is the rule that makes sagas
 * work: undo the most recent commitment first.
 */
public enum SagaState {

    AWAITING_INVENTORY,
    AWAITING_PAYMENT,

    /** Every forward step succeeded. */
    COMPLETED,

    /** Undoing the payment, because the final local step failed. */
    COMPENSATING_PAYMENT,

    /** Undoing the stock reservation. */
    COMPENSATING_INVENTORY,

    /** Rolled back cleanly; the order is cancelled and nothing is left held. */
    COMPENSATED,

    /** Failed before anything needed undoing. */
    FAILED,

    /**
     * A compensation itself failed. There is no automatic answer to this - money or stock is
     * stranded and a human has to look. Alert on this state.
     */
    COMPENSATION_FAILED
}