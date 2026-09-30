package com.oms.order.ops;

import com.oms.common.events.Topics;
import com.oms.common.messaging.config.KafkaConnectionConfigSupport;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Lets an operator look inside a dead-letter topic and push messages back onto the topic they
 * came from.
 *
 * <p>Deliberately built on the plain Kafka clients rather than {@code @KafkaListener}: replay is an
 * on-demand, bounded operation driven by an HTTP call, not a continuously running subscription.
 *
 * <p>Replay is safe here precisely because every consumer is idempotent - a message that turns out
 * to have partially applied before failing will not apply twice.
 */
@Service
public class DltAdminService {

    private static final Logger log = LoggerFactory.getLogger(DltAdminService.class);
    private static final Duration POLL = Duration.ofSeconds(2);
    private static final int EMPTY_POLLS_BEFORE_GIVING_UP = 2;
    private static final String PEEK_GROUP = "dlt-admin-peek";
    private static final String REPLAY_GROUP = "dlt-admin-replay";

    private final KafkaProperties kafkaProperties;
    private final KafkaConnectionConfigSupport connectionSupport;

    public DltAdminService(KafkaProperties kafkaProperties,
                           KafkaConnectionConfigSupport connectionSupport) {
        this.kafkaProperties = kafkaProperties;
        this.connectionSupport = connectionSupport;
    }

    /** Reads from the beginning without committing, so it can be called repeatedly. */
    public List<DltRecordView> peek(String originTopic, int max) {
        String dlt = Topics.dlt(originTopic);
        try (Consumer<String, byte[]> consumer = consumer(PEEK_GROUP)) {
            List<TopicPartition> partitions = partitionsOf(consumer, dlt);
            if (partitions.isEmpty()) {
                return List.of();
            }
            consumer.assign(partitions);
            consumer.seekToBeginning(partitions);
            return drain(consumer, max).stream().map(DltAdminService::toView).toList();
        }
    }

    /**
     * Republishes to the origin topic, then commits. Committing last means a crash mid-replay
     * repeats work rather than losing it.
     */
    public ReplayResult replay(String originTopic, int max) {
        String dlt = Topics.dlt(originTopic);
        try (Consumer<String, byte[]> consumer = consumer(REPLAY_GROUP);
             Producer<String, byte[]> producer = producer()) {

            List<TopicPartition> partitions = partitionsOf(consumer, dlt);
            if (partitions.isEmpty()) {
                return new ReplayResult(dlt, originTopic, 0, "Dead-letter topic does not exist yet");
            }
            consumer.assign(partitions);
            resumeFromCommitted(consumer, partitions);

            List<ConsumerRecord<String, byte[]>> records = drain(consumer, max);
            for (ConsumerRecord<String, byte[]> record : records) {
                ProducerRecord<String, byte[]> out =
                        new ProducerRecord<>(originTopic, null, record.key(), record.value());
                copyForwardableHeaders(record, out);
                producer.send(out).get();
            }
            producer.flush();
            if (!records.isEmpty()) {
                consumer.commitSync();
            }
            log.info("Replayed {} record(s) from {} back to {}", records.size(), dlt, originTopic);
            return new ReplayResult(dlt, originTopic, records.size(),
                    records.isEmpty() ? "Nothing left to replay" : "Replayed and offsets committed");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Replay interrupted", e);
        } catch (Exception e) {
            throw new IllegalStateException("Replay of " + dlt + " failed: " + e.getMessage(), e);
        }
    }

    /**
     * Reports both the total written and what replay has yet to work through.
     *
     * <p>The distinction matters because Kafka is a log, not a queue: replaying a record does not
     * remove it. A single "depth" number would therefore never go back down after a successful
     * drain, which makes it useless as an alert. {@code pendingReplay} is measured against the
     * replay group's committed offsets, so it does.
     */
    public Map<String, DltDepth> depths() {
        Map<String, DltDepth> depths = new java.util.LinkedHashMap<>();
        try (Consumer<String, byte[]> consumer = consumer(REPLAY_GROUP)) {
            for (String origin : Topics.CONSUMED_TOPICS) {
                String dlt = Topics.dlt(origin);
                List<TopicPartition> partitions = partitionsOf(consumer, dlt);
                if (partitions.isEmpty()) {
                    depths.put(dlt, new DltDepth(0L, 0L));
                    continue;
                }
                Map<TopicPartition, Long> begin = consumer.beginningOffsets(partitions);
                Map<TopicPartition, Long> end = consumer.endOffsets(partitions);
                Map<TopicPartition, OffsetAndMetadata> committed =
                        consumer.committed(new java.util.HashSet<>(partitions));

                long total = 0L;
                long pending = 0L;
                for (TopicPartition partition : partitions) {
                    long first = begin.getOrDefault(partition, 0L);
                    long last = end.getOrDefault(partition, 0L);
                    total += last - first;

                    OffsetAndMetadata offset = committed.get(partition);
                    long resumeFrom = offset == null ? first : Math.max(offset.offset(), first);
                    pending += Math.max(0L, last - resumeFrom);
                }
                depths.put(dlt, new DltDepth(total, pending));
            }
        }
        return depths;
    }

    // ---------------------------------------------------------------- internals

    private void resumeFromCommitted(Consumer<String, byte[]> consumer, List<TopicPartition> partitions) {
        for (TopicPartition partition : partitions) {
            OffsetAndMetadata committed = consumer.committed(Set.of(partition)).get(partition);
            if (committed == null) {
                consumer.seekToBeginning(List.of(partition));
            } else {
                consumer.seek(partition, committed.offset());
            }
        }
    }

    private List<ConsumerRecord<String, byte[]>> drain(Consumer<String, byte[]> consumer, int max) {
        List<ConsumerRecord<String, byte[]>> collected = new ArrayList<>();
        int emptyPolls = 0;
        while (collected.size() < max && emptyPolls < EMPTY_POLLS_BEFORE_GIVING_UP) {
            ConsumerRecords<String, byte[]> polled = consumer.poll(POLL);
            if (polled.isEmpty()) {
                emptyPolls++;
                continue;
            }
            emptyPolls = 0;
            for (ConsumerRecord<String, byte[]> record : polled) {
                collected.add(record);
                if (collected.size() >= max) {
                    break;
                }
            }
        }
        return collected;
    }

    /**
     * Carries the business headers across but drops the {@code kafka_dlt-*} forensics, so a record
     * that fails again gets fresh diagnostics instead of stale ones. The Jackson type header must
     * survive, or the listener cannot rebuild the payload.
     */
    private void copyForwardableHeaders(ConsumerRecord<String, byte[]> from,
                                        ProducerRecord<String, byte[]> to) {
        for (Header header : from.headers()) {
            String key = header.key();
            if (key.startsWith("kafka_dlt") || key.startsWith("kafka_exception")) {
                continue;
            }
            to.headers().add(header);
        }
    }

    private List<TopicPartition> partitionsOf(Consumer<String, byte[]> consumer, String topic) {
        List<PartitionInfo> info = consumer.partitionsFor(topic);
        if (info == null || info.isEmpty()) {
            return Collections.emptyList();
        }
        return info.stream().map(p -> new TopicPartition(p.topic(), p.partition())).toList();
    }

    private Consumer<String, byte[]> consumer(String groupId) {
        Map<String, Object> config =
                connectionSupport.apply(new HashMap<>(kafkaProperties.buildConsumerProperties(null)));
        config.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
        config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        // Raw bytes only - never let the JSON deserializer near a topic full of broken payloads.
        config.remove("spring.deserializer.value.delegate.class");
        config.remove("spring.deserializer.key.delegate.class");
        return new KafkaConsumer<>(config);
    }

    private Producer<String, byte[]> producer() {
        Map<String, Object> config =
                connectionSupport.apply(new HashMap<>(kafkaProperties.buildProducerProperties(null)));
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
        return new KafkaProducer<>(config);
    }

    private static DltRecordView toView(ConsumerRecord<String, byte[]> record) {
        return new DltRecordView(
                record.topic(),
                record.partition(),
                record.offset(),
                record.key(),
                record.value() == null ? null : new String(record.value(), StandardCharsets.UTF_8),
                header(record, "kafka_dlt-original-topic"),
                header(record, "kafka_dlt-exception-fqcn"),
                header(record, "kafka_dlt-exception-message"),
                Instant.ofEpochMilli(record.timestamp()));
    }

    private static String header(ConsumerRecord<String, byte[]> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }
}