package com.oms.common.messaging.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.oms.common.events.Topics;
import com.oms.common.messaging.NonRetryableMessageException;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.KafkaException;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.converter.ConversionException;
import org.springframework.kafka.support.serializer.DeserializationException;
import org.springframework.util.backoff.ExponentialBackOff;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Retry policy and dead-letter routing for every listener in the service.
 *
 * <p>Spring Boot picks up a {@link CommonErrorHandler} bean and wires it into all
 * {@code @KafkaListener} containers, so declaring this once covers the whole service.
 *
 * <p>The distinction that matters is retryable versus not. A database that is briefly unreachable
 * deserves another attempt; a payload that does not parse will not parse on the third try either,
 * and retrying it just delays every message queued behind it on that partition. So the fatal
 * exceptions skip the backoff entirely and go straight to {@code <topic>.DLT}.
 *
 * <p>This is <em>blocking</em> retry: the backoff pauses the consumer, so one slow poison message
 * holds up its partition. The non-blocking alternative is {@code @RetryableTopic}, which forwards
 * to timed retry topics instead - better under load, more moving parts to explain. See
 * {@code docs/DESIGN.md}.
 */
@Configuration
public class KafkaErrorHandlingConfig {

    private static final Logger log = LoggerFactory.getLogger(KafkaErrorHandlingConfig.class);

    @Bean
    public CommonErrorHandler kafkaErrorHandler(
            KafkaTemplate<String, Object> kafkaTemplate,
            KafkaTemplate<String, byte[]> byteArrayKafkaTemplate,
            @Value("${oms.kafka.retry.max-attempts:3}") int maxAttempts,
            @Value("${oms.kafka.retry.initial-interval-ms:1000}") long initialInterval,
            @Value("${oms.kafka.retry.multiplier:2.0}") double multiplier,
            @Value("${oms.kafka.retry.max-interval-ms:10000}") long maxInterval) {

        // Order matters: the most specific value type first.
        Map<Class<?>, KafkaOperations<?, ?>> templates = new LinkedHashMap<>();
        templates.put(byte[].class, byteArrayKafkaTemplate);
        templates.put(Object.class, kafkaTemplate);

        DeadLetterPublishingRecoverer recoverer =
                new DeadLetterPublishingRecoverer(templates, this::resolveDeadLetterTopic);

        ExponentialBackOff backOff = new ExponentialBackOff(initialInterval, multiplier);
        backOff.setMaxInterval(maxInterval);
        // maxAttempts counts the original delivery, so the number of *retries* is one fewer.
        backOff.setMaxAttempts(Math.max(0, maxAttempts - 1));

        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, backOff);
        handler.addNotRetryableExceptions(
                NonRetryableMessageException.class,
                DeserializationException.class,
                ConversionException.class,
                JsonProcessingException.class,
                IllegalArgumentException.class,
                NullPointerException.class);
        handler.setLogLevel(KafkaException.Level.WARN);
        handler.setRetryListeners((record, ex, attempt) ->
                log.warn("Delivery attempt {} failed for {}-{}@{}: {}",
                        attempt, record.topic(), record.partition(), record.offset(), ex.toString()));
        return handler;
    }

    /**
     * Partition {@code -1} lets Kafka choose. Mirroring the source partition looks tidier but
     * breaks the moment a DLT has fewer partitions than the topic it shadows.
     */
    private TopicPartition resolveDeadLetterTopic(ConsumerRecord<?, ?> record, Exception exception) {
        String dlt = Topics.dlt(record.topic());
        log.error("Routing {}-{}@{} to {} after exhausting retries: {}",
                record.topic(), record.partition(), record.offset(), dlt, exception.toString());
        return new TopicPartition(dlt, -1);
    }
}