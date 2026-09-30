package com.oms.common.messaging.config;

import com.oms.common.events.Topics;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;
import java.util.ArrayList;
import java.util.List;

/**
 * Declares every topic and its dead-letter counterpart. {@link KafkaAdmin} creates only what is
 * missing, so this is safe to run in all four services and on every restart - and it means any
 * one service can be started first without waiting for another to create its topics.
 *
 * <p>Broker-side auto-creation is switched off in {@code docker-compose.yml} deliberately: it
 * hides typos by silently creating {@code order.replys} and then never delivering anything.
 */
@Configuration
public class KafkaTopicsConfig {

    @Bean
    public KafkaAdmin.NewTopics omsTopics(@Value("${oms.kafka.topic.partitions:3}") int partitions,
                                          @Value("${oms.kafka.topic.replicas:1}") int replicas) {
        List<NewTopic> topics = new ArrayList<>();
        for (String topic : Topics.CONSUMED_TOPICS) {
            topics.add(TopicBuilder.name(topic).partitions(partitions).replicas(replicas).build());
            topics.add(TopicBuilder.name(Topics.dlt(topic)).partitions(partitions).replicas(replicas).build());
        }
        return new KafkaAdmin.NewTopics(topics.toArray(new NewTopic[0]));
    }
}