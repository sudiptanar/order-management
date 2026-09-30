package com.oms.common.messaging.config;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import java.util.HashMap;
import java.util.Map;

/**
 * Two producers, on purpose.
 *
 * <p>The JSON template publishes business payloads. The byte-array template exists for the
 * dead-letter path: when a message fails because it could not be <em>deserialized</em>, there is
 * no object to re-serialize - only the original bytes. Handing those to a JSON serializer would
 * base64 them into a string and destroy the evidence you opened the DLT to read.
 *
 * <p>Both are declared explicitly because Spring Boot's auto-configured {@code KafkaTemplate} is
 * {@code @ConditionalOnMissingBean(KafkaTemplate.class)} - that condition ignores generics, so
 * declaring any template of our own would have silently suppressed it.
 *
 * <p>Both also route their config through {@link KafkaConnectionConfigSupport}, because building a
 * client from {@link KafkaProperties} alone misses a broker address supplied at runtime.
 */
@Configuration
public class KafkaProducerConfig {

    private final KafkaConnectionConfigSupport connectionSupport;

    public KafkaProducerConfig(KafkaConnectionConfigSupport connectionSupport) {
        this.connectionSupport = connectionSupport;
    }

    @Bean
    public ProducerFactory<String, Object> jsonProducerFactory(KafkaProperties properties) {
        return new DefaultKafkaProducerFactory<>(producerConfig(properties));
    }

    @Bean
    public KafkaTemplate<String, Object> kafkaTemplate(ProducerFactory<String, Object> jsonProducerFactory) {
        return new KafkaTemplate<>(jsonProducerFactory);
    }

    @Bean
    public KafkaTemplate<String, byte[]> byteArrayKafkaTemplate(KafkaProperties properties) {
        Map<String, Object> config = producerConfig(properties);
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
        return new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(config));
    }

    private Map<String, Object> producerConfig(KafkaProperties properties) {
        return connectionSupport.apply(new HashMap<>(properties.buildProducerProperties(null)));
    }
}