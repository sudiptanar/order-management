package com.oms.order.saga;

import com.oms.common.events.OrderEvent;
import com.oms.common.events.OrderEventType;
import com.oms.common.events.ProcessPaymentCommand;
import com.oms.common.events.RefundPaymentCommand;
import com.oms.common.events.ReleaseInventoryCommand;
import com.oms.common.events.SagaReply;
import com.oms.common.events.SagaStep;
import com.oms.common.events.Topics;
import com.oms.common.messaging.IdempotencyGuard;
import com.oms.common.messaging.NonRetryableMessageException;
import com.oms.common.messaging.OutboxRecorder;
import com.oms.order.domain.Order;
import com.oms.order.domain.OrderStatus;
import com.oms.order.domain.SagaInstance;
import com.oms.order.domain.SagaState;
import com.oms.order.repository.OrderRepository;
import com.oms.order.repository.SagaInstanceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.UUID;

/**
 * The single place that knows the order of the steps and how to undo them.
 *
 * <p>Everything here happens in one transaction per reply: the idempotency ledger row, the state
 * change, and the next command's outbox row all commit together or not at all. That is what makes
 * an at-least-once message stream safe to build on.
 */
@Service
public class OrderSagaOrchestrator {

    /**
     * The Kafka consumer group for the reply listener, and the value written to the idempotency
     * ledger. The listener annotation references this same constant so the two cannot drift -
     * if they did, the ledger would record a group Kafka has never heard of.
     *
     * <p>One group per subscription, named after both. Changing it makes the service re-process
     * history from the beginning: occasionally what you want, otherwise a nasty surprise.
     */
    public static final String CONSUMER_GROUP = "order-service.saga-replies";

    private static final Logger log = LoggerFactory.getLogger(OrderSagaOrchestrator.class);

    private final OrderRepository orderRepository;
    private final SagaInstanceRepository sagaRepository;
    private final OutboxRecorder outbox;
    private final IdempotencyGuard idempotencyGuard;

    public OrderSagaOrchestrator(OrderRepository orderRepository,
                                 SagaInstanceRepository sagaRepository,
                                 OutboxRecorder outbox,
                                 IdempotencyGuard idempotencyGuard) {
        this.orderRepository = orderRepository;
        this.sagaRepository = sagaRepository;
        this.outbox = outbox;
        this.idempotencyGuard = idempotencyGuard;
    }

    @Transactional
    public void handle(SagaReply reply, String topic) {
        if (!idempotencyGuard.firstTimeSeeing(reply.messageId(), CONSUMER_GROUP, topic)) {
            return;
        }

        SagaInstance saga = sagaRepository.findByOrderId(reply.orderId())
                .orElseThrow(() -> new NonRetryableMessageException(
                        "Reply refers to order " + reply.orderId() + ", which has no saga instance"));
        Order order = orderRepository.findById(reply.orderId())
                .orElseThrow(() -> new NonRetryableMessageException(
                        "Reply refers to order " + reply.orderId() + ", which does not exist"));

        log.info("saga={} order={} state={} <- {} {} {}",
                saga.getSagaId(), order.getId(), saga.getState(),
                reply.step(), reply.status(), reply.reason() == null ? "" : "(" + reply.reason() + ")");

        switch (reply.step()) {
            case RESERVE_INVENTORY -> onInventoryReserveReply(saga, order, reply);
            case PROCESS_PAYMENT -> onPaymentReply(saga, order, reply);
            case REFUND_PAYMENT -> onRefundReply(saga, order, reply);
            case RELEASE_INVENTORY -> onReleaseReply(saga, order, reply);
        }
    }

    // ---------------------------------------------------------------- forward path

    private void onInventoryReserveReply(SagaInstance saga, Order order, SagaReply reply) {
        if (isOutOfPhase(saga, reply, SagaState.AWAITING_INVENTORY)) {
            return;
        }
        if (reply.isSuccess()) {
            order.markStatus(OrderStatus.INVENTORY_RESERVED);
            saga.transitionTo(SagaState.AWAITING_PAYMENT, SagaStep.PROCESS_PAYMENT);
            send(Topics.PROCESS_PAYMENT, order, new ProcessPaymentCommand(
                    UUID.randomUUID(), saga.getSagaId(), order.getId(),
                    order.getCustomerId(), order.getTotalAmount(), Instant.now()));
        } else {
            // Nothing was committed anywhere yet, so there is nothing to undo.
            finish(saga, order, SagaState.FAILED, reply.reason());
        }
    }

    private void onPaymentReply(SagaInstance saga, Order order, SagaReply reply) {
        if (isOutOfPhase(saga, reply, SagaState.AWAITING_PAYMENT)) {
            return;
        }
        if (!reply.isSuccess()) {
            // Payment refused. Stock is still held, so release it.
            saga.setFailureReason(reply.reason());
            saga.transitionTo(SagaState.COMPENSATING_INVENTORY, SagaStep.RELEASE_INVENTORY);
            send(Topics.RELEASE_INVENTORY, order, new ReleaseInventoryCommand(
                    UUID.randomUUID(), saga.getSagaId(), order.getId(), reply.reason(), Instant.now()));
            return;
        }

        order.markStatus(OrderStatus.PAYMENT_COMPLETED);

        // Final step, and it is local: no message, no participant, just this service committing.
        if (order.isSimulateConfirmFailure()) {
            String reason = "Simulated failure during final confirmation";
            log.warn("saga={} order={} local confirmation failed, compensating in reverse order",
                    saga.getSagaId(), order.getId());
            saga.setFailureReason(reason);
            saga.transitionTo(SagaState.COMPENSATING_PAYMENT, SagaStep.REFUND_PAYMENT);
            send(Topics.REFUND_PAYMENT, order, new RefundPaymentCommand(
                    UUID.randomUUID(), saga.getSagaId(), order.getId(),
                    order.getTotalAmount(), reason, Instant.now()));
            return;
        }

        order.markStatus(OrderStatus.CONFIRMED);
        saga.transitionTo(SagaState.COMPLETED, null);
        send(Topics.ORDER_EVENTS, order, OrderEvent.of(order.getId(), order.getCustomerId(),
                OrderEventType.ORDER_CONFIRMED, order.getTotalAmount(), "Order confirmed"));
        log.info("saga={} order={} COMPLETED", saga.getSagaId(), order.getId());
    }

    // ---------------------------------------------------------------- compensation path

    private void onRefundReply(SagaInstance saga, Order order, SagaReply reply) {
        if (isOutOfPhase(saga, reply, SagaState.COMPENSATING_PAYMENT)) {
            return;
        }
        if (!reply.isSuccess()) {
            stall(saga, order, "Refund failed: " + reply.reason());
            return;
        }
        saga.transitionTo(SagaState.COMPENSATING_INVENTORY, SagaStep.RELEASE_INVENTORY);
        send(Topics.RELEASE_INVENTORY, order, new ReleaseInventoryCommand(
                UUID.randomUUID(), saga.getSagaId(), order.getId(),
                saga.getFailureReason(), Instant.now()));
    }

    private void onReleaseReply(SagaInstance saga, Order order, SagaReply reply) {
        if (isOutOfPhase(saga, reply, SagaState.COMPENSATING_INVENTORY)) {
            return;
        }
        if (!reply.isSuccess()) {
            stall(saga, order, "Inventory release failed: " + reply.reason());
            return;
        }
        finish(saga, order, SagaState.COMPENSATED, saga.getFailureReason());
    }

    // ---------------------------------------------------------------- helpers

    /**
     * A reply that does not match the step we are waiting on is normal traffic, not a bug: a
     * duplicate that arrived after a rebalance, or a reply to a command we have already given up
     * on. Ignoring it is correct. Throwing would send a harmless message to the dead-letter topic.
     */
    private boolean isOutOfPhase(SagaInstance saga, SagaReply reply, SagaState expected) {
        if (saga.getState() == expected) {
            return false;
        }
        log.warn("saga={} ignoring {} reply: state is {}, expected {}. Late or replayed message.",
                saga.getSagaId(), reply.step(), saga.getState(), expected);
        return true;
    }

    private void finish(SagaInstance saga, Order order, SagaState finalState, String reason) {
        order.cancel(reason);
        saga.setFailureReason(reason);
        saga.transitionTo(finalState, null);
        send(Topics.ORDER_EVENTS, order, OrderEvent.of(order.getId(), order.getCustomerId(),
                OrderEventType.ORDER_CANCELLED, order.getTotalAmount(), reason));
        log.info("saga={} order={} {} ({})", saga.getSagaId(), order.getId(), finalState, reason);
    }

    /** A compensation failed. No automated path forward - this needs a person. */
    private void stall(SagaInstance saga, Order order, String reason) {
        saga.setFailureReason(reason);
        saga.transitionTo(SagaState.COMPENSATION_FAILED, saga.getCurrentStep());
        log.error("MANUAL INTERVENTION REQUIRED saga={} order={}: {}",
                saga.getSagaId(), order.getId(), reason);
    }

    private void send(String topic, Order order, Object payload) {
        outbox.record(topic, order.getId().toString(), payload, "Order", order.getId().toString());
    }
}