package com.oms.order.api.dto;

import com.oms.order.domain.Order;
import com.oms.order.domain.SagaInstance;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record OrderResponse(
        UUID orderId,
        String customerId,
        String status,
        BigDecimal totalAmount,
        String failureReason,
        UUID sagaId,
        String sagaState,
        String sagaStep,
        Instant createdAt,
        Instant updatedAt,
        List<Item> items) {

    public record Item(String productId, int quantity, BigDecimal unitPrice) {
    }

    public static OrderResponse from(Order order, SagaInstance saga) {
        return new OrderResponse(
                order.getId(),
                order.getCustomerId(),
                order.getStatus().name(),
                order.getTotalAmount(),
                order.getFailureReason(),
                saga == null ? null : saga.getSagaId(),
                saga == null ? null : saga.getState().name(),
                saga == null || saga.getCurrentStep() == null ? null : saga.getCurrentStep().name(),
                order.getCreatedAt(),
                order.getUpdatedAt(),
                order.getItems().stream()
                        .map(i -> new Item(i.getProductId(), i.getQuantity(), i.getUnitPrice()))
                        .toList());
    }
}