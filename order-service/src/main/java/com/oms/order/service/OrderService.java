package com.oms.order.service;

import com.oms.common.events.OrderEvent;
import com.oms.common.events.OrderEventType;
import com.oms.common.events.OrderLine;
import com.oms.common.events.ReserveInventoryCommand;
import com.oms.common.events.SagaStep;
import com.oms.common.events.Topics;
import com.oms.common.messaging.OutboxRecorder;
import com.oms.order.api.dto.CreateOrderRequest;
import com.oms.order.api.dto.OrderResponse;
import com.oms.order.domain.Order;
import com.oms.order.domain.OrderItem;
import com.oms.order.domain.SagaInstance;
import com.oms.order.domain.SagaState;
import com.oms.order.repository.OrderRepository;
import com.oms.order.repository.SagaInstanceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private final OrderRepository orderRepository;
    private final SagaInstanceRepository sagaRepository;
    private final OutboxRecorder outbox;

    public OrderService(OrderRepository orderRepository,
                        SagaInstanceRepository sagaRepository,
                        OutboxRecorder outbox) {
        this.orderRepository = orderRepository;
        this.sagaRepository = sagaRepository;
        this.outbox = outbox;
    }

    /**
     * One transaction covering the order, the saga instance, and the two messages that start it.
     * No Kafka call happens here - only outbox rows - so there is no window in which the order
     * exists without its saga having been kicked off.
     */
    @Transactional
    public OrderResponse placeOrder(CreateOrderRequest request) {
        Order order = new Order(request.customerId(), request.simulateConfirmFailure());
        for (CreateOrderRequest.Line line : request.items()) {
            order.addItem(new OrderItem(line.productId(), line.quantity(), line.unitPrice()));
        }
        orderRepository.save(order);

        SagaInstance saga = new SagaInstance(
                order.getId(), SagaState.AWAITING_INVENTORY, SagaStep.RESERVE_INVENTORY);
        sagaRepository.save(saga);

        String key = order.getId().toString();

        outbox.record(Topics.ORDER_EVENTS, key,
                OrderEvent.of(order.getId(), order.getCustomerId(), OrderEventType.ORDER_CREATED,
                        order.getTotalAmount(), "Order placed"),
                "Order", key);

        List<OrderLine> lines = order.getItems().stream()
                .map(i -> new OrderLine(i.getProductId(), i.getQuantity(), i.getUnitPrice()))
                .toList();
        outbox.record(Topics.RESERVE_INVENTORY, key,
                new ReserveInventoryCommand(UUID.randomUUID(), saga.getSagaId(), order.getId(),
                        lines, Instant.now()),
                "Order", key);

        log.info("saga={} order={} started for customer {} total {}",
                saga.getSagaId(), order.getId(), order.getCustomerId(), order.getTotalAmount());

        return OrderResponse.from(order, saga);
    }

    @Transactional(readOnly = true)
    public OrderResponse findOrder(UUID orderId) {
        Order order = orderRepository.findWithItemsById(orderId)
                .orElseThrow(() -> new OrderNotFoundException(orderId));
        return OrderResponse.from(order, sagaRepository.findByOrderId(orderId).orElse(null));
    }

    @Transactional(readOnly = true)
    public List<OrderResponse> recentOrders(int limit) {
        return orderRepository.findAllByOrderByCreatedAtDesc(PageRequest.of(0, limit)).stream()
                .map(o -> OrderResponse.from(o, sagaRepository.findByOrderId(o.getId()).orElse(null)))
                .toList();
    }

    /** Counts every state, including the zeroes - a state that vanishes from a dashboard reads
     *  as "fine" when it should read as "none right now". */
    @Transactional(readOnly = true)
    public Map<String, Long> sagaSummary() {
        Map<String, Long> summary = new LinkedHashMap<>();
        for (SagaState state : SagaState.values()) {
            summary.put(state.name(), sagaRepository.countByState(state));
        }
        return summary;
    }
}