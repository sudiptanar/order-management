package com.oms.common.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.oms.common.events.MessageHeaders;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Drains the outbox to Kafka.
 *
 * <p>Polling a table is the humble version of this pattern. The production answer is change data
 * capture - Debezium tailing the Postgres WAL - which removes the poll interval and the load.
 * The guarantees are the same; only the latency and the operational footprint differ.
 */
@Component
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private final OutboxRepository repository;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final int batchSize;
    private final int maxAttempts;
    private final long sendTimeoutMs;

    public OutboxPublisher(OutboxRepository repository,
                           KafkaTemplate<String, Object> kafkaTemplate,
                           ObjectMapper objectMapper,
                           @Value("${oms.outbox.batch-size:50}") int batchSize,
                           @Value("${oms.outbox.max-attempts:10}") int maxAttempts,
                           @Value("${oms.outbox.send-timeout-ms:10000}") long sendTimeoutMs) {
        this.repository = repository;
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.batchSize = batchSize;
        this.maxAttempts = maxAttempts;
        this.sendTimeoutMs = sendTimeoutMs;
    }

    @Scheduled(fixedDelayString = "${oms.outbox.poll-interval-ms:500}")
    @Transactional
    public void publishPending() {
        List<OutboxMessage> batch = repository.claimPendingBatch(PageRequest.of(0, batchSize));
        if (batch.isEmpty()) {
            return;
        }
        log.debug("Publishing {} outbox message(s)", batch.size());
        for (OutboxMessage message : batch) {
            publish(message);
        }
    }

    private void publish(OutboxMessage message) {
        try {
            Class<?> payloadClass = Class.forName(message.getPayloadType());
            Object payload = objectMapper.readValue(message.getPayload(), payloadClass);

            ProducerRecord<String, Object> record =
                    new ProducerRecord<>(message.getTopic(), null, message.getMessageKey(), payload);
            record.headers().add(MessageHeaders.OUTBOX_ID,
                    message.getId().toString().getBytes(StandardCharsets.UTF_8));
            record.headers().add(MessageHeaders.ORDER_ID,
                    String.valueOf(message.getAggregateId()).getBytes(StandardCharsets.UTF_8));

            // Block on the ack: the send must be confirmed before the row is marked SENT,
            // and it keeps a batch in insert order on its way to the partition.
            kafkaTemplate.send(record).get(sendTimeoutMs, TimeUnit.MILLISECONDS);
            message.markSent();
            log.info("Published outbox {} -> {} key={}", message.getId(), message.getTopic(), message.getMessageKey());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            message.markAttemptFailed("Interrupted while publishing", maxAttempts);
        } catch (Exception e) {
            message.markAttemptFailed(e.getMessage(), maxAttempts);
            log.warn("Outbox {} -> {} failed (attempt {}/{}): {}",
                    message.getId(), message.getTopic(), message.getAttempts(), maxAttempts, e.toString());
        }
    }
}