package com.oms.order;

import com.oms.common.events.Topics;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.ConfluentKafkaContainer;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Base for the saga tests. Runs a real Postgres and a real Kafka - no embedded substitutes and no
 * mocked repositories, because the behaviour under test is precisely the interaction between the
 * transaction boundary, the outbox and the broker.
 *
 * <p>The participant services are not started. This test plays their part by hand, which is what
 * makes it possible to assert on the orchestrator's state machine directly: it sends the replies a
 * participant would send, including the duplicates a real broker eventually produces.
 *
 * <p>Requires a running Docker daemon.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "oms.outbox.poll-interval-ms=200",
                // Silence the stuck-saga monitor; these tests finish in seconds.
                "oms.saga.monitor-interval-ms=600000",
                "oms.kafka.topic.partitions=1"
        })
@Testcontainers
abstract class AbstractSagaIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    @ServiceConnection
    static final ConfluentKafkaContainer KAFKA =
            new ConfluentKafkaContainer("confluentinc/cp-kafka:7.7.1");

    @Autowired
    protected TestRestTemplate rest;

    @Autowired
    protected KafkaTemplate<String, Object> kafkaTemplate;

    private Consumer<String, Object> observer;

    /**
     * Everything the observer has seen but no test has claimed yet. Records are matched by key -
     * which is always the order id - so tests never trip over each other's traffic.
     */
    private final List<ConsumerRecord<String, Object>> seen = new ArrayList<>();

    @BeforeEach
    void subscribeObserver() {
        Map<String, Object> config = new HashMap<>();
        config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        // A fresh group per test, reading from the start: no dependence on committed offsets.
        config.put(ConsumerConfig.GROUP_ID_CONFIG, "test-observer-" + UUID.randomUUID());
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JsonDeserializer.class);
        config.put(JsonDeserializer.TRUSTED_PACKAGES, "com.oms.common.events");
        observer = new KafkaConsumer<>(config);
        observer.subscribe(List.of(
                Topics.RESERVE_INVENTORY,
                Topics.RELEASE_INVENTORY,
                Topics.PROCESS_PAYMENT,
                Topics.REFUND_PAYMENT,
                Topics.ORDER_EVENTS));
        seen.clear();
    }

    @AfterEach
    void closeObserver() {
        if (observer != null) {
            observer.close();
        }
    }

    /** Sends a reply as a participant would, keyed by order id like every message in the system. */
    protected void replyAsParticipant(UUID orderId, Object reply) {
        kafkaTemplate.send(Topics.SAGA_REPLIES, orderId.toString(), reply);
        kafkaTemplate.flush();
    }

    /** Waits for one message of the given type for this order, and removes it from the buffer. */
    protected <T> T awaitMessage(String topic, UUID orderId, Class<T> type) {
        return awaitMessage(topic, orderId, type, Duration.ofSeconds(30));
    }

    protected <T> T awaitMessage(String topic, UUID orderId, Class<T> type, Duration timeout) {
        Instant deadline = Instant.now().plus(timeout);
        while (true) {
            T claimed = claim(topic, orderId, type);
            if (claimed != null) {
                return claimed;
            }
            if (Instant.now().isAfter(deadline)) {
                throw new AssertionError("Timed out waiting for a " + type.getSimpleName()
                        + " on " + topic + " for order " + orderId + ". Saw: " + describeSeen());
            }
            poll(Duration.ofMillis(250));
        }
    }

    /** Asserts nothing of this type turns up for this order within the window. */
    protected <T> void assertNoMessage(String topic, UUID orderId, Class<T> type, Duration window) {
        Instant deadline = Instant.now().plus(window);
        while (Instant.now().isBefore(deadline)) {
            poll(Duration.ofMillis(250));
            T claimed = claim(topic, orderId, type);
            if (claimed != null) {
                throw new AssertionError("Expected no further " + type.getSimpleName() + " on " + topic
                        + " for order " + orderId + ", but got one: " + claimed);
            }
        }
    }

    private <T> T claim(String topic, UUID orderId, Class<T> type) {
        String key = orderId.toString();
        Iterator<ConsumerRecord<String, Object>> it = seen.iterator();
        while (it.hasNext()) {
            ConsumerRecord<String, Object> record = it.next();
            if (record.topic().equals(topic)
                    && key.equals(record.key())
                    && type.isInstance(record.value())) {
                it.remove();
                return type.cast(record.value());
            }
        }
        return null;
    }

    private void poll(Duration duration) {
        ConsumerRecords<String, Object> records = observer.poll(duration);
        records.forEach(seen::add);
    }

    private String describeSeen() {
        return seen.stream()
                .map(r -> r.topic() + "/" + r.value().getClass().getSimpleName())
                .toList()
                .toString();
    }
}