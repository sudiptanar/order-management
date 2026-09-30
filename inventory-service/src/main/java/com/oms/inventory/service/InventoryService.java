package com.oms.inventory.service;

import com.oms.common.events.OrderLine;
import com.oms.common.events.ReleaseInventoryCommand;
import com.oms.common.events.ReserveInventoryCommand;
import com.oms.common.events.SagaReply;
import com.oms.common.events.SagaStep;
import com.oms.common.events.Topics;
import com.oms.common.messaging.IdempotencyGuard;
import com.oms.common.messaging.OutboxRecorder;
import com.oms.inventory.domain.InventoryItem;
import com.oms.inventory.domain.InventoryReservation;
import com.oms.inventory.domain.ReservationStatus;
import com.oms.inventory.repository.InventoryItemRepository;
import com.oms.inventory.repository.InventoryReservationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

@Service
public class InventoryService {

    /**
     * One consumer group per subscription rather than one per service.
     *
     * <p>Kafka can cope with a single group whose members subscribe to different topics, but it is
     * worth avoiding: the subscriptions then share a rebalance, and their committed offsets and
     * consumer lag are reported together - so a backlog of reservations looks exactly like a
     * backlog of releases.
     *
     * <p>Each value is also the ledger key for that subscription, and is shared with the listener
     * annotation so the two cannot drift.
     */
    public static final String GROUP_RESERVE = "inventory-service.reserve-inventory";
    public static final String GROUP_RELEASE = "inventory-service.release-inventory";

    private static final Logger log = LoggerFactory.getLogger(InventoryService.class);

    private final InventoryItemRepository itemRepository;
    private final InventoryReservationRepository reservationRepository;
    private final IdempotencyGuard idempotencyGuard;
    private final OutboxRecorder outbox;
    private final FailureSimulator failureSimulator;

    public InventoryService(InventoryItemRepository itemRepository,
                            InventoryReservationRepository reservationRepository,
                            IdempotencyGuard idempotencyGuard,
                            OutboxRecorder outbox,
                            FailureSimulator failureSimulator) {
        this.itemRepository = itemRepository;
        this.reservationRepository = reservationRepository;
        this.idempotencyGuard = idempotencyGuard;
        this.outbox = outbox;
        this.failureSimulator = failureSimulator;
    }

    @Transactional
    public void reserve(ReserveInventoryCommand command) {
        failureSimulator.failIfRequested(command.lines().stream().map(OrderLine::productId).toList());

        if (!idempotencyGuard.firstTimeSeeing(command.messageId(), GROUP_RESERVE, Topics.RESERVE_INVENTORY)) {
            return;
        }

        // Second line of defence. The ledger stops the same message twice; this stops a *different*
        // message asking for the same thing - which happens when an orchestrator times out and
        // reissues the command with a fresh id.
        if (reservationRepository.existsByOrderIdAndStatus(command.orderId(), ReservationStatus.ACTIVE)) {
            log.info("Order {} already holds an active reservation; replying SUCCEEDED without reserving again",
                    command.orderId());
            reply(command.sagaId(), command.orderId(),
                    SagaReply.succeeded(command.sagaId(), command.orderId(), SagaStep.RESERVE_INVENTORY));
            return;
        }

        // Merge repeated lines for the same product, and use a sorted map so every transaction
        // takes its row locks in the same order. Inconsistent lock ordering is how two concurrent
        // orders deadlock each other.
        Map<String, Integer> wanted = new TreeMap<>();
        for (OrderLine line : command.lines()) {
            wanted.merge(line.productId(), line.quantity(), Integer::sum);
        }

        Map<String, InventoryItem> locked = new LinkedHashMap<>();
        String rejection = null;
        for (Map.Entry<String, Integer> entry : wanted.entrySet()) {
            Optional<InventoryItem> found = itemRepository.lockByProductId(entry.getKey());
            if (found.isEmpty()) {
                rejection = "Unknown product " + entry.getKey();
                break;
            }
            InventoryItem item = found.get();
            if (item.getAvailableQty() < entry.getValue()) {
                rejection = "Insufficient stock for " + entry.getKey()
                        + ": requested " + entry.getValue() + ", available " + item.getAvailableQty();
                break;
            }
            locked.put(entry.getKey(), item);
        }

        if (rejection != null) {
            // A business rejection, not a failure. Replying FAILED is the correct, successful
            // handling of this message - throwing would dead-letter a message that worked fine.
            log.info("Rejecting reservation for order {}: {}", command.orderId(), rejection);
            reply(command.sagaId(), command.orderId(),
                    SagaReply.failed(command.sagaId(), command.orderId(), SagaStep.RESERVE_INVENTORY, rejection));
            return;
        }

        wanted.forEach((productId, quantity) -> {
            locked.get(productId).reserve(quantity);
            reservationRepository.save(new InventoryReservation(command.orderId(), productId, quantity));
        });

        log.info("Reserved {} for order {}", wanted, command.orderId());
        reply(command.sagaId(), command.orderId(),
                SagaReply.succeeded(command.sagaId(), command.orderId(), SagaStep.RESERVE_INVENTORY));
    }

    @Transactional
    public void release(ReleaseInventoryCommand command) {
        if (!idempotencyGuard.firstTimeSeeing(command.messageId(), GROUP_RELEASE, Topics.RELEASE_INVENTORY)) {
            return;
        }

        List<InventoryReservation> active =
                reservationRepository.findByOrderIdAndStatus(command.orderId(), ReservationStatus.ACTIVE);

        for (InventoryReservation reservation : active) {
            itemRepository.lockByProductId(reservation.getProductId())
                    .ifPresent(item -> item.release(reservation.getQuantity()));
            reservation.release();
        }

        // Nothing to release is still a success: either the reservation never happened, or a
        // previous attempt already undid it. A compensation that fails when there is nothing left
        // to undo would strand the saga forever.
        log.info("Released {} reservation(s) for order {} ({})",
                active.size(), command.orderId(), command.reason());
        reply(command.sagaId(), command.orderId(),
                SagaReply.succeeded(command.sagaId(), command.orderId(), SagaStep.RELEASE_INVENTORY));
    }

    // ---------------------------------------------------------------- queries used by the API

    @Transactional(readOnly = true)
    public List<InventoryItem> allStock() {
        return itemRepository.findAllByOrderByProductIdAsc();
    }

    @Transactional(readOnly = true)
    public Optional<InventoryItem> stockOf(String productId) {
        return itemRepository.findById(productId);
    }

    @Transactional(readOnly = true)
    public List<InventoryReservation> reservationsFor(UUID orderId) {
        return reservationRepository.findByOrderId(orderId);
    }

    @Transactional
    public InventoryItem upsertStock(String productId, int availableQty) {
        InventoryItem item = itemRepository.findById(productId)
                .orElseGet(() -> new InventoryItem(productId, 0));
        item.restock(availableQty);
        return itemRepository.save(item);
    }

    private void reply(UUID sagaId, UUID orderId, SagaReply reply) {
        outbox.record(Topics.SAGA_REPLIES, orderId.toString(), reply, "Saga", sagaId.toString());
    }
}