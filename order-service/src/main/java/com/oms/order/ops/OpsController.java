package com.oms.order.ops;

import com.oms.common.events.Topics;
import com.oms.common.messaging.OutboxRepository;
import com.oms.common.messaging.OutboxStatus;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * An operator's view of the messaging layer. In a real system this would sit behind
 * authentication and probably in its own admin application; here it keeps the interesting state
 * one curl away.
 */
@RestController
@RequestMapping("/api/ops")
public class OpsController {

    private final DltAdminService dltAdmin;
    private final OutboxRepository outboxRepository;

    public OpsController(DltAdminService dltAdmin, OutboxRepository outboxRepository) {
        this.dltAdmin = dltAdmin;
        this.outboxRepository = outboxRepository;
    }

    @GetMapping("/topics")
    public List<String> topics() {
        return Arrays.asList(Topics.CONSUMED_TOPICS);
    }

    /**
     * Per dead-letter topic: everything ever written, and how much replay has not yet drained.
     * Alert on {@code pendingReplay} - {@code total} never decreases, because Kafka is a log.
     */
    @GetMapping("/dlt")
    public Map<String, DltDepth> dltDepths() {
        return dltAdmin.depths();
    }

    /** Read a dead-letter topic without consuming it. Pass the ORIGIN topic name. */
    @GetMapping("/dlt/{originTopic}")
    public List<DltRecordView> peek(@PathVariable String originTopic,
                                    @RequestParam(defaultValue = "20") int max) {
        return dltAdmin.peek(originTopic, Math.min(max, 200));
    }

    /** Push dead-lettered messages back onto the origin topic. */
    @PostMapping("/dlt/{originTopic}/replay")
    public ReplayResult replay(@PathVariable String originTopic,
                               @RequestParam(defaultValue = "20") int max) {
        return dltAdmin.replay(originTopic, Math.min(max, 200));
    }

    /** Outbox health. A growing PENDING count means the publisher is not keeping up. */
    @GetMapping("/outbox")
    public Map<String, Object> outbox() {
        Map<String, Object> result = new LinkedHashMap<>();
        for (OutboxStatus status : OutboxStatus.values()) {
            result.put(status.name().toLowerCase(), outboxRepository.countByStatus(status));
        }
        result.put("recentFailures", outboxRepository
                .findByStatusOrderByCreatedAtDesc(OutboxStatus.FAILED, PageRequest.of(0, 10))
                .stream()
                .map(m -> Map.of(
                        "id", m.getId().toString(),
                        "topic", m.getTopic(),
                        "attempts", m.getAttempts(),
                        "lastError", String.valueOf(m.getLastError())))
                .toList());
        return result;
    }
}