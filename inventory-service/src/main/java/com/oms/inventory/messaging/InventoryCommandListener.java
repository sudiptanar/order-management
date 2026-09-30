package com.oms.inventory.messaging;

import com.oms.common.events.ReleaseInventoryCommand;
import com.oms.common.events.ReserveInventoryCommand;
import com.oms.common.events.Topics;
import com.oms.inventory.service.InventoryService;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Two topics, two payload types, one container factory: the JSON serializer stamps the type onto
 * each record as a header, so the deserializer can hand each listener the right object without a
 * separate factory per type.
 *
 * <p>Each listener gets its own consumer group. Kafka can cope with one group whose members
 * subscribe to different topics, but it is worth avoiding: the two subscriptions then share a
 * rebalance and their offsets and lag are reported together, so a backlog of reservations is
 * indistinguishable from a backlog of releases.
 */
@Component
public class InventoryCommandListener {

    private final InventoryService inventoryService;

    public InventoryCommandListener(InventoryService inventoryService) {
        this.inventoryService = inventoryService;
    }

    @KafkaListener(topics = Topics.RESERVE_INVENTORY,
            id = "reserve-inventory",
            groupId = InventoryService.GROUP_RESERVE)
    public void onReserve(ReserveInventoryCommand command) {
        inventoryService.reserve(command);
    }

    @KafkaListener(topics = Topics.RELEASE_INVENTORY,
            id = "release-inventory",
            groupId = InventoryService.GROUP_RELEASE)
    public void onRelease(ReleaseInventoryCommand command) {
        inventoryService.release(command);
    }
}