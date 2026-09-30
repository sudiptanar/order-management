package com.oms.payment.messaging;

import com.oms.common.events.ProcessPaymentCommand;
import com.oms.common.events.RefundPaymentCommand;
import com.oms.common.events.Topics;
import com.oms.payment.service.PaymentService;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** One consumer group per subscription - see {@code InventoryCommandListener} for the reasoning. */
@Component
public class PaymentCommandListener {

    private final PaymentService paymentService;

    public PaymentCommandListener(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @KafkaListener(topics = Topics.PROCESS_PAYMENT,
            id = "process-payment",
            groupId = PaymentService.GROUP_PROCESS)
    public void onProcess(ProcessPaymentCommand command) {
        paymentService.process(command);
    }

    @KafkaListener(topics = Topics.REFUND_PAYMENT,
            id = "refund-payment",
            groupId = PaymentService.GROUP_REFUND)
    public void onRefund(RefundPaymentCommand command) {
        paymentService.refund(command);
    }
}