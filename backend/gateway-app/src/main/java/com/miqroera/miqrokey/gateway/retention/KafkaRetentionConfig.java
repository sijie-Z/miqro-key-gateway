package com.miqroera.miqrokey.gateway.retention;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * ADR-0014 R3 wiring: the Kafka retention publisher exists only when
 * {@code miqrokey.retention.kafka.bootstrap-servers} is configured — absent
 * that, the sidecar keeps the fail-closed no-op publisher. Marked
 * {@code @Primary} so it wins over the no-op regardless of configuration class
 * ordering.
 */
@Configuration
@EnableConfigurationProperties(KafkaRetentionConfig.KafkaRetentionProperties.class)
@ConditionalOnProperty(prefix = "miqrokey.retention.kafka", name = "bootstrap-servers")
public class KafkaRetentionConfig {

    @ConfigurationProperties(prefix = "miqrokey.retention.kafka")
    public record KafkaRetentionProperties(@DefaultValue("") String bootstrapServers,
            @DefaultValue("content-retention") String topic,
            @DefaultValue("miqrokey-gateway-retention") String clientId) {
    }

    @Bean
    @Primary
    RetentionPublisher kafkaRetentionPublisher(KafkaRetentionProperties props) {
        return new KafkaRetentionPublisher(props.bootstrapServers(), props.topic(), props.clientId());
    }
}
