package com.oms.payment.api;

import com.oms.payment.domain.Payment;
import com.oms.payment.service.PaymentService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/payments")
public class PaymentController {

    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    public record PaymentView(UUID id, UUID orderId, String customerId, BigDecimal amount,
                              String status, String authorizationRef, String failureReason,
                              Instant createdAt, Instant updatedAt) {
        static PaymentView of(Payment p) {
            return new PaymentView(p.getId(), p.getOrderId(), p.getCustomerId(), p.getAmount(),
                    p.getStatus().name(), p.getAuthorizationRef(), p.getFailureReason(),
                    p.getCreatedAt(), p.getUpdatedAt());
        }
    }

    @GetMapping
    public List<PaymentView> recent(@RequestParam(defaultValue = "50") int limit) {
        return paymentService.recent(Math.min(limit, 200)).stream().map(PaymentView::of).toList();
    }

    @GetMapping("/order/{orderId}")
    public ResponseEntity<PaymentView> byOrder(@PathVariable UUID orderId) {
        return paymentService.findByOrder(orderId)
                .map(PaymentView::of)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}