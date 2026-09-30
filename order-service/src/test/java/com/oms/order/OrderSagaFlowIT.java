package com.oms.order;

import com.oms.common.events.OrderEvent;
import com.oms.common.events.OrderEventType;
import com.oms.common.events.ProcessPaymentCommand;
import com.oms.common.events.RefundPaymentCommand;
import com.oms.common.events.ReleaseInventoryCommand;
import com.oms.common.events.ReserveInventoryCommand;
import com.oms.common.events.SagaReply;
import com.oms.common.events.SagaStep;
import com.oms.common.events.Topics;
import com.oms.order.api.dto.CreateOrderRequest;
import com.oms.order.api.dto.OrderResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class OrderSagaFlowIT extends AbstractSagaIT {

    // ------------------------------------------------------------------ happy path

    @Test
    @DisplayName("every step succeeds: the order is confirmed and the customer is told")
    void happyPath() {
        UUID orderId = placeOrder("CUST-HAPPY", "P-LAPTOP", 2, "250.00", false);

        OrderEvent created = awaitMessage(Topics.ORDER_EVENTS, orderId, OrderEvent.class);
        assertThat(created.type()).isEqualTo(OrderEventType.ORDER_CREATED);

        ReserveInventoryCommand reserve =
                awaitMessage(Topics.RESERVE_INVENTORY, orderId, ReserveInventoryCommand.class);
        assertThat(reserve.lines()).hasSize(1);
        assertThat(reserve.lines().get(0).productId()).isEqualTo("P-LAPTOP");

        replyAsParticipant(orderId, SagaReply.succeeded(reserve.sagaId(), orderId, SagaStep.RESERVE_INVENTORY));

        ProcessPaymentCommand payment =
                awaitMessage(Topics.PROCESS_PAYMENT, orderId, ProcessPaymentCommand.class);
        assertThat(payment.amount()).isEqualByComparingTo("500.00");

        replyAsParticipant(orderId, SagaReply.succeeded(payment.sagaId(), orderId, SagaStep.PROCESS_PAYMENT));

        OrderEvent confirmed = awaitMessage(Topics.ORDER_EVENTS, orderId, OrderEvent.class);
        assertThat(confirmed.type()).isEqualTo(OrderEventType.ORDER_CONFIRMED);

        awaitState(orderId, "CONFIRMED", "COMPLETED");
    }

    // ------------------------------------------------------------------ idempotency

    @Test
    @DisplayName("a duplicated reply advances the saga exactly once")
    void duplicateReplyIsIgnored() {
        UUID orderId = placeOrder("CUST-DUP", "P-KEYBOARD", 1, "40.00", false);

        ReserveInventoryCommand reserve =
                awaitMessage(Topics.RESERVE_INVENTORY, orderId, ReserveInventoryCommand.class);

        // The same message, twice - exactly what an at-least-once broker delivers after a
        // rebalance or a failed offset commit. The messageId is identical, which is the whole
        // point: that is what the idempotency ledger keys on.
        SagaReply reply = SagaReply.succeeded(reserve.sagaId(), orderId, SagaStep.RESERVE_INVENTORY);
        replyAsParticipant(orderId, reply);
        replyAsParticipant(orderId, reply);

        ProcessPaymentCommand first =
                awaitMessage(Topics.PROCESS_PAYMENT, orderId, ProcessPaymentCommand.class);
        assertThat(first.orderId()).isEqualTo(orderId);

        // If the guard were missing, the duplicate would emit a second payment command - and the
        // customer would be charged twice.
        assertNoMessage(Topics.PROCESS_PAYMENT, orderId, ProcessPaymentCommand.class, Duration.ofSeconds(5));
    }

    // ------------------------------------------------------------------ compensation

    @Test
    @DisplayName("payment declined: the stock reservation is released and the order is cancelled")
    void paymentFailureReleasesInventory() {
        UUID orderId = placeOrder("CUST-DECLINE", "P-MONITOR", 1, "300.00", false);

        ReserveInventoryCommand reserve =
                awaitMessage(Topics.RESERVE_INVENTORY, orderId, ReserveInventoryCommand.class);
        replyAsParticipant(orderId, SagaReply.succeeded(reserve.sagaId(), orderId, SagaStep.RESERVE_INVENTORY));

        ProcessPaymentCommand payment =
                awaitMessage(Topics.PROCESS_PAYMENT, orderId, ProcessPaymentCommand.class);
        replyAsParticipant(orderId, SagaReply.failed(
                payment.sagaId(), orderId, SagaStep.PROCESS_PAYMENT, "Card declined"));

        ReleaseInventoryCommand release =
                awaitMessage(Topics.RELEASE_INVENTORY, orderId, ReleaseInventoryCommand.class);
        assertThat(release.reason()).isEqualTo("Card declined");

        replyAsParticipant(orderId, SagaReply.succeeded(release.sagaId(), orderId, SagaStep.RELEASE_INVENTORY));

        awaitState(orderId, "CANCELLED", "COMPENSATED");
        assertThat(fetch(orderId).failureReason()).isEqualTo("Card declined");
    }

    @Test
    @DisplayName("inventory rejected: the order is cancelled with nothing to compensate")
    void inventoryRejectionNeedsNoCompensation() {
        UUID orderId = placeOrder("CUST-NOSTOCK", "P-OUT-OF-STOCK", 1, "10.00", false);

        ReserveInventoryCommand reserve =
                awaitMessage(Topics.RESERVE_INVENTORY, orderId, ReserveInventoryCommand.class);
        replyAsParticipant(orderId, SagaReply.failed(
                reserve.sagaId(), orderId, SagaStep.RESERVE_INVENTORY, "Insufficient stock"));

        awaitState(orderId, "CANCELLED", "FAILED");

        // Nothing was committed anywhere, so no compensation should be issued at all.
        assertNoMessage(Topics.RELEASE_INVENTORY, orderId, ReleaseInventoryCommand.class, Duration.ofSeconds(5));
        assertNoMessage(Topics.REFUND_PAYMENT, orderId, RefundPaymentCommand.class, Duration.ofSeconds(1));
    }

    @Test
    @DisplayName("the final local step fails: payment is refunded, then stock is released, in that order")
    void fullCompensationChainRunsInReverse() {
        UUID orderId = placeOrder("CUST-CONFIRMFAIL", "P-HEADPHONES", 2, "75.00", true);

        ReserveInventoryCommand reserve =
                awaitMessage(Topics.RESERVE_INVENTORY, orderId, ReserveInventoryCommand.class);
        replyAsParticipant(orderId, SagaReply.succeeded(reserve.sagaId(), orderId, SagaStep.RESERVE_INVENTORY));

        ProcessPaymentCommand payment =
                awaitMessage(Topics.PROCESS_PAYMENT, orderId, ProcessPaymentCommand.class);
        replyAsParticipant(orderId, SagaReply.succeeded(payment.sagaId(), orderId, SagaStep.PROCESS_PAYMENT));

        // Compensation unwinds the most recent commitment first: refund before release.
        RefundPaymentCommand refund =
                awaitMessage(Topics.REFUND_PAYMENT, orderId, RefundPaymentCommand.class);
        assertThat(refund.amount()).isEqualByComparingTo("150.00");

        // Stock must still be held at this point - releasing it early would be out of order.
        assertNoMessage(Topics.RELEASE_INVENTORY, orderId, ReleaseInventoryCommand.class, Duration.ofSeconds(3));

        replyAsParticipant(orderId, SagaReply.succeeded(refund.sagaId(), orderId, SagaStep.REFUND_PAYMENT));

        ReleaseInventoryCommand release =
                awaitMessage(Topics.RELEASE_INVENTORY, orderId, ReleaseInventoryCommand.class);
        replyAsParticipant(orderId, SagaReply.succeeded(release.sagaId(), orderId, SagaStep.RELEASE_INVENTORY));

        awaitState(orderId, "CANCELLED", "COMPENSATED");
    }

    @Test
    @DisplayName("a reply for a step the saga is not waiting on is ignored, not dead-lettered")
    void outOfPhaseReplyIsIgnored() {
        UUID orderId = placeOrder("CUST-LATE", "P-MOUSE", 1, "20.00", false);
        awaitMessage(Topics.RESERVE_INVENTORY, orderId, ReserveInventoryCommand.class);

        // A payment reply while the saga still waits on inventory: a stale message from an earlier
        // attempt. Dropping it is correct; acting on it would corrupt the state machine.
        replyAsParticipant(orderId, SagaReply.succeeded(UUID.randomUUID(), orderId, SagaStep.PROCESS_PAYMENT));

        assertNoMessage(Topics.PROCESS_PAYMENT, orderId, ProcessPaymentCommand.class, Duration.ofSeconds(5));
        assertThat(fetch(orderId).sagaState()).isEqualTo("AWAITING_INVENTORY");
    }

    // ------------------------------------------------------------------ helpers

    private UUID placeOrder(String customerId, String productId, int quantity,
                            String unitPrice, boolean simulateConfirmFailure) {
        CreateOrderRequest request = new CreateOrderRequest(
                customerId,
                List.of(new CreateOrderRequest.Line(productId, quantity, new BigDecimal(unitPrice))),
                simulateConfirmFailure);

        ResponseEntity<OrderResponse> response =
                rest.postForEntity("/api/orders", request, OrderResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody()).isNotNull();
        return response.getBody().orderId();
    }

    private OrderResponse fetch(UUID orderId) {
        OrderResponse body = rest.getForObject("/api/orders/" + orderId, OrderResponse.class);
        assertThat(body).isNotNull();
        return body;
    }

    private void awaitState(UUID orderId, String expectedOrderStatus, String expectedSagaState) {
        Instant deadline = Instant.now().plusSeconds(30);
        OrderResponse last = null;
        while (Instant.now().isBefore(deadline)) {
            last = fetch(orderId);
            if (expectedOrderStatus.equals(last.status()) && expectedSagaState.equals(last.sagaState())) {
                return;
            }
            sleep(200);
        }
        throw new AssertionError("Order " + orderId + " never reached " + expectedOrderStatus
                + "/" + expectedSagaState + "; last seen "
                + (last == null ? "nothing" : last.status() + "/" + last.sagaState()));
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}