package com.oms.payment.service;

import com.oms.common.events.ProcessPaymentCommand;
import com.oms.common.events.RefundPaymentCommand;
import com.oms.common.events.SagaReply;
import com.oms.common.events.SagaStep;
import com.oms.common.events.Topics;
import com.oms.common.messaging.IdempotencyGuard;
import com.oms.common.messaging.OutboxRecorder;
import com.oms.payment.domain.Payment;
import com.oms.payment.domain.PaymentStatus;
import com.oms.payment.repository.PaymentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class PaymentService {

    /** One consumer group per subscription - see {@code InventoryService} for the reasoning. */
    public static final String GROUP_PROCESS = "payment-service.process-payment";
    public static final String GROUP_REFUND = "payment-service.refund-payment";

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private final PaymentRepository paymentRepository;
    private final IdempotencyGuard idempotencyGuard;
    private final OutboxRecorder outbox;
    private final FailureSimulator failureSimulator;
    private final BigDecimal autoDeclineAbove;

    public PaymentService(PaymentRepository paymentRepository,
                          IdempotencyGuard idempotencyGuard,
                          OutboxRecorder outbox,
                          FailureSimulator failureSimulator,
                          @Value("${oms.payment.auto-decline-above:10000}") BigDecimal autoDeclineAbove) {
        this.paymentRepository = paymentRepository;
        this.idempotencyGuard = idempotencyGuard;
        this.outbox = outbox;
        this.failureSimulator = failureSimulator;
        this.autoDeclineAbove = autoDeclineAbove;
    }

    @Transactional
    public void process(ProcessPaymentCommand command) {
        failureSimulator.failIfRequested(command.customerId());

        if (!idempotencyGuard.firstTimeSeeing(command.messageId(), GROUP_PROCESS, Topics.PROCESS_PAYMENT)) {
            return;
        }

        // A payment already on file means this order has been through here before - under a
        // different message id, so the ledger did not catch it. Restate the earlier outcome
        // rather than charging again.
        Optional<Payment> existing = paymentRepository.findByOrderId(command.orderId());
        if (existing.isPresent()) {
            Payment payment = existing.get();
            log.info("Order {} already has a {} payment; restating that outcome",
                    command.orderId(), payment.getStatus());
            replyForExisting(command, payment);
            return;
        }

        Payment payment = new Payment(command.orderId(), command.customerId(), command.amount());

        if (command.amount().compareTo(autoDeclineAbove) > 0) {
            String reason = "Amount " + command.amount() + " exceeds the authorised limit of " + autoDeclineAbove;
            payment.decline(reason);
            paymentRepository.save(payment);
            log.info("Declined payment for order {}: {}", command.orderId(), reason);
            // A decline is a business outcome, correctly handled. Never an exception.
            reply(command.sagaId(), command.orderId(),
                    SagaReply.failed(command.sagaId(), command.orderId(), SagaStep.PROCESS_PAYMENT, reason));
            return;
        }

        payment.authorize();
        paymentRepository.save(payment);
        log.info("Authorised {} for order {} ({})",
                command.amount(), command.orderId(), payment.getAuthorizationRef());
        reply(command.sagaId(), command.orderId(),
                SagaReply.succeeded(command.sagaId(), command.orderId(), SagaStep.PROCESS_PAYMENT));
    }

    @Transactional
    public void refund(RefundPaymentCommand command) {
        if (!idempotencyGuard.firstTimeSeeing(command.messageId(), GROUP_REFUND, Topics.REFUND_PAYMENT)) {
            return;
        }

        Optional<Payment> found = paymentRepository.findByOrderId(command.orderId());
        if (found.isEmpty()) {
            // Nothing was ever charged, so there is nothing to give back. Reporting success is
            // correct - failing here would strand the saga in COMPENSATING_PAYMENT forever.
            log.info("No payment on file for order {}; nothing to refund", command.orderId());
            reply(command.sagaId(), command.orderId(),
                    SagaReply.succeeded(command.sagaId(), command.orderId(), SagaStep.REFUND_PAYMENT));
            return;
        }

        Payment payment = found.get();
        payment.refund(command.reason());
        log.info("Refunded {} for order {} ({})", payment.getAmount(), command.orderId(), command.reason());
        reply(command.sagaId(), command.orderId(),
                SagaReply.succeeded(command.sagaId(), command.orderId(), SagaStep.REFUND_PAYMENT));
    }

    @Transactional(readOnly = true)
    public Optional<Payment> findByOrder(UUID orderId) {
        return paymentRepository.findByOrderId(orderId);
    }

    @Transactional(readOnly = true)
    public List<Payment> recent(int limit) {
        return paymentRepository.findAllByOrderByCreatedAtDesc(PageRequest.of(0, limit));
    }

    private void replyForExisting(ProcessPaymentCommand command, Payment payment) {
        SagaReply reply = payment.getStatus() == PaymentStatus.AUTHORIZED
                ? SagaReply.succeeded(command.sagaId(), command.orderId(), SagaStep.PROCESS_PAYMENT)
                : SagaReply.failed(command.sagaId(), command.orderId(), SagaStep.PROCESS_PAYMENT,
                        payment.getFailureReason() == null
                                ? "Payment previously " + payment.getStatus()
                                : payment.getFailureReason());
        reply(command.sagaId(), command.orderId(), reply);
    }

    private void reply(UUID sagaId, UUID orderId, SagaReply reply) {
        outbox.record(Topics.SAGA_REPLIES, orderId.toString(), reply, "Saga", sagaId.toString());
    }
}