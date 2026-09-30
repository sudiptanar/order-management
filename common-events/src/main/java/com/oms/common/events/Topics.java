package com.oms.common.events;

/**
 * Every topic in the system, in one place.
 *
 * <p>Naming convention:
 * <ul>
 *   <li>{@code order.commands.*} - a directed instruction from the orchestrator to one participant.
 *       Exactly one consumer group is expected to act on it.</li>
 *   <li>{@code order.replies} - participants report back to the orchestrator here.</li>
 *   <li>{@code order.events} - facts about what happened, broadcast. Any number of subscribers
 *       may listen; nobody is obliged to.</li>
 * </ul>
 *
 * <p>Every message is keyed by {@code orderId}, so all messages for one order land on the same
 * partition and are therefore processed in order.
 */
public final class Topics {

    /** Orchestrator -> inventory-service: hold stock for this order. */
    public static final String RESERVE_INVENTORY = "order.commands.reserve-inventory";

    /** Orchestrator -> inventory-service: compensation for RESERVE_INVENTORY. */
    public static final String RELEASE_INVENTORY = "order.commands.release-inventory";

    /** Orchestrator -> payment-service: charge the customer. */
    public static final String PROCESS_PAYMENT = "order.commands.process-payment";

    /** Orchestrator -> payment-service: compensation for PROCESS_PAYMENT. */
    public static final String REFUND_PAYMENT = "order.commands.refund-payment";

    /** Participants -> orchestrator: the outcome of a command. */
    public static final String SAGA_REPLIES = "order.replies";

    /** Public fan-out of order lifecycle facts. */
    public static final String ORDER_EVENTS = "order.events";

    /** Suffix appended by {@code DeadLetterPublishingRecoverer}. */
    public static final String DLT_SUFFIX = ".DLT";

    /** Topics that have a dead-letter counterpart (i.e. topics someone consumes). */
    public static final String[] CONSUMED_TOPICS = {
            RESERVE_INVENTORY, RELEASE_INVENTORY, PROCESS_PAYMENT, REFUND_PAYMENT,
            SAGA_REPLIES, ORDER_EVENTS
    };

    public static String dlt(String topic) {
        return topic.endsWith(DLT_SUFFIX) ? topic : topic + DLT_SUFFIX;
    }

    private Topics() {
    }
}