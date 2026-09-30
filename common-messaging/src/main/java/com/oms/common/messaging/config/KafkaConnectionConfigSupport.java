package com.oms.common.messaging.config;

import org.apache.kafka.clients.CommonClientConfigs;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.kafka.KafkaConnectionDetails;
import org.springframework.stereotype.Component;
import java.util.Map;

/**
 * Applies the runtime broker address to clients this project builds by hand.
 *
 * <p>Why this exists: {@code KafkaProperties.build*Properties()} returns only what is written in
 * {@code application.yml}. When the broker address is supplied at runtime instead - Testcontainers
 * with {@code @ServiceConnection}, or a Docker Compose service connection - Spring Boot expresses it
 * as a {@link KafkaConnectionDetails} bean and applies it only to the factories <em>it</em>
 * auto-configures.
 *
 * <p>Any client assembled from {@code KafkaProperties} directly therefore misses it, and connects to
 * the configured host while the rest of the application talks to the real one. The failure is
 * particularly unhelpful: the producer reports "topic not present in metadata" rather than anything
 * about the wrong broker.
 */
@Component
public class KafkaConnectionConfigSupport {

    private final KafkaConnectionDetails connectionDetails;

    /**
     * @param connectionDetails an {@link ObjectProvider} because the bean is absent in ordinary
     *                          runs, where {@code application.yml} is already correct. It appears
     *                          only when something supplies the address at runtime.
     */
    public KafkaConnectionConfigSupport(ObjectProvider<KafkaConnectionDetails> connectionDetails) {
        this.connectionDetails = connectionDetails.getIfAvailable();
    }

    /** Overwrites {@code bootstrap.servers} when a connection-details bean is available. */
    public Map<String, Object> apply(Map<String, Object> clientConfig) {
        if (connectionDetails != null) {
            clientConfig.put(CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG,
                    connectionDetails.getBootstrapServers());
        }
        return clientConfig;
    }
}