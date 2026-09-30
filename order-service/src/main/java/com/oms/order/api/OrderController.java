package com.oms.order.api;

import com.oms.order.api.dto.CreateOrderRequest;
import com.oms.order.api.dto.OrderResponse;
import com.oms.order.service.OrderService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    /**
     * Returns 202 rather than 201: the order exists, but whether it will be confirmed is not
     * known yet. Poll the returned resource to watch the saga progress. Promising a final answer
     * here would mean blocking on four services, which is the coupling this design avoids.
     */
    @PostMapping
    public ResponseEntity<OrderResponse> create(@Valid @RequestBody CreateOrderRequest request) {
        OrderResponse created = orderService.placeOrder(request);
        return ResponseEntity.accepted()
                .location(URI.create("/api/orders/" + created.orderId()))
                .body(created);
    }

    @GetMapping("/{orderId}")
    public OrderResponse get(@PathVariable UUID orderId) {
        return orderService.findOrder(orderId);
    }

    @GetMapping
    public List<OrderResponse> list(@RequestParam(defaultValue = "50") int limit) {
        return orderService.recentOrders(Math.min(limit, 200));
    }

    /** How many sagas sit in each state. The quickest read on whether anything is wedged. */
    @GetMapping("/status/summary")
    public Map<String, Long> summary() {
        return orderService.sagaSummary();
    }
}