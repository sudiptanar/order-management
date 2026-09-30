package com.oms.inventory.api;

import com.oms.inventory.domain.InventoryItem;
import com.oms.inventory.domain.InventoryReservation;
import com.oms.inventory.service.FailureSimulator;
import com.oms.inventory.service.InventoryService;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/inventory")
public class InventoryController {

    private final InventoryService inventoryService;
    private final FailureSimulator failureSimulator;

    public InventoryController(InventoryService inventoryService, FailureSimulator failureSimulator) {
        this.inventoryService = inventoryService;
        this.failureSimulator = failureSimulator;
    }

    public record StockView(String productId, int availableQty, int reservedQty, int totalQty) {
        static StockView of(InventoryItem item) {
            return new StockView(item.getProductId(), item.getAvailableQty(),
                    item.getReservedQty(), item.totalQty());
        }
    }

    public record ReservationView(UUID id, UUID orderId, String productId, int quantity, String status) {
        static ReservationView of(InventoryReservation r) {
            return new ReservationView(r.getId(), r.getOrderId(), r.getProductId(),
                    r.getQuantity(), r.getStatus().name());
        }
    }

    public record UpdateStockRequest(@NotNull @Min(0) Integer availableQty) {
    }

    @GetMapping
    public List<StockView> stock() {
        return inventoryService.allStock().stream().map(StockView::of).toList();
    }

    @GetMapping("/{productId}")
    public ResponseEntity<StockView> stockOf(@PathVariable String productId) {
        return inventoryService.stockOf(productId)
                .map(StockView::of)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PutMapping("/{productId}")
    public StockView upsert(@PathVariable String productId, @RequestBody UpdateStockRequest request) {
        return StockView.of(inventoryService.upsertStock(productId, request.availableQty()));
    }

    @GetMapping("/reservations/{orderId}")
    public List<ReservationView> reservations(@PathVariable UUID orderId) {
        return inventoryService.reservationsFor(orderId).stream().map(ReservationView::of).toList();
    }

    public record SimulationView(String failingProductId) {
    }

    public record SimulationRequest(String failingProductId) {
    }

    /**
     * Test hook. Retargeting or clearing the simulated failure is what makes the dead-letter
     * replay workflow demonstrable: remove the cause, then replay. See {@code docs/DEMO.md}.
     */
    @GetMapping("/simulation")
    public SimulationView simulation() {
        return new SimulationView(failureSimulator.getFailingProductId());
    }

    @PutMapping("/simulation")
    public SimulationView setSimulation(@RequestBody SimulationRequest request) {
        failureSimulator.setFailingProductId(request.failingProductId());
        return new SimulationView(failureSimulator.getFailingProductId());
    }
}