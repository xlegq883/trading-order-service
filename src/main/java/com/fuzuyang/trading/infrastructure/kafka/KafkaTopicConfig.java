package com.fuzuyang.trading.infrastructure.kafka;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Kafka Topic 声明。
 *
 * <p>由 {@code KafkaAdmin} 在启动时创建；broker 不可用时仅告警，不影响应用启动。</p>
 */
@Configuration
public class KafkaTopicConfig {

    @Bean
    public NewTopic orderEventsTopic(
            @Value("${app.outbox.topic:trading.order.events}") String topic,
            @Value("${app.outbox.partitions:3}") int partitions,
            @Value("${app.outbox.replication-factor:1}") int replicationFactor) {
        return TopicBuilder.name(topic)
                .partitions(partitions)
                .replicas(replicationFactor)
                .build();
    }
}
