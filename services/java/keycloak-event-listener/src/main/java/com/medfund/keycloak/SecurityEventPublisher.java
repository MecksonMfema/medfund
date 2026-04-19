package com.medfund.keycloak;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;

import java.util.Properties;
import java.util.logging.Logger;

/**
 * Thin Kafka producer wrapper used by the Keycloak event listener.
 * Intentionally does NOT use Spring or any framework — this runs inside the
 * Keycloak JVM, not in a Spring application context.
 */
public class SecurityEventPublisher implements AutoCloseable {

    private static final Logger log = Logger.getLogger(SecurityEventPublisher.class.getName());
    static final String TOPIC = "medfund.security.events";

    private final KafkaProducer<String, String> producer;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public SecurityEventPublisher(String bootstrapServers) {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,   StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.ACKS_CONFIG, "1");
        props.put(ProducerConfig.RETRIES_CONFIG, 3);
        // Never block the Keycloak request thread for more than 3 seconds
        props.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 3_000);
        this.producer = new KafkaProducer<>(props);
    }

    public void publish(SecurityEventMessage event) {
        try {
            String json = objectMapper.writeValueAsString(event);
            producer.send(
                new ProducerRecord<>(TOPIC, event.userId(), json),
                (meta, ex) -> {
                    if (ex != null) {
                        log.warning("[medfund-events] publish failed for "
                                + event.eventType() + ": " + ex.getMessage());
                    }
                }
            );
        } catch (Exception e) {
            log.warning("[medfund-events] serialization error: " + e.getMessage());
        }
    }

    @Override
    public void close() {
        producer.close();
    }
}
