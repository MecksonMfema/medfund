package com.medfund.shared.testfixtures;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

/**
 * Base class for tests that need a real Kafka broker (audit assertion,
 * cross-service event flow). One Confluent broker is shared across the
 * class — start-up costs ~5 s once per test class.
 *
 * <p>The Confluent image (not the embedded {@code spring-kafka-test} broker)
 * is deliberate: embedded brokers drift from production on transactional
 * sends and consumer-group rebalances, hiding bugs that only surface in CI.
 *
 * <p>Use {@link #consumeAuditEvent(String, Duration)} to assert that a
 * mutation produced the expected audit envelope. The helper creates a
 * fresh consumer (unique group id) on each call so previous tests don't
 * leak offsets — each call sees every retained event in the topic.
 */
@Testcontainers
public abstract class AbstractKafkaIntegrationTest {

    @Container
    protected static final KafkaContainer KAFKA =
        new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.0"))
            .withReuse(true);

    @DynamicPropertySource
    static void kafkaProperties(DynamicPropertyRegistry registry) {
        registry.add("kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Read the first message on {@code topic} matching the optional {@code entityType}
     * filter, parsed as the audit envelope JSON shape. Returns {@code null} on timeout.
     *
     * <p>Used by mutation tests:
     * <pre>{@code
     * StepVerifier.create(service.create(req, "actor").contextWrite(...))
     *     .expectNextCount(1).verifyComplete();
     * JsonNode evt = consumeAuditEvent("medfund.audit.events", Duration.ofSeconds(5));
     * assertThat(evt.get("action").asText()).isEqualTo("CREATE");
     * }</pre>
     */
    protected JsonNode consumeAuditEvent(String topic, Duration timeout) {
        return consumeAuditEvent(topic, null, timeout);
    }

    protected JsonNode consumeAuditEvent(String topic, String entityTypeFilter, Duration timeout) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        // Unique group + earliest offset → every call sees the full topic, no
        // leakage between tests, no rebalance delays.
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "audit-assert-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");

        long deadline = System.currentTimeMillis() + timeout.toMillis();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of(topic));
            while (System.currentTimeMillis() < deadline) {
                ConsumerRecords<String, String> records =
                    consumer.poll(Duration.ofMillis(500));
                for (ConsumerRecord<String, String> rec : records) {
                    try {
                        JsonNode node = MAPPER.readTree(rec.value());
                        if (entityTypeFilter == null
                            || entityTypeFilter.equals(node.path("entityType").asText())) {
                            return node;
                        }
                    } catch (Exception ignored) {
                        // skip unparsable records — never blocks the consumer
                    }
                }
            }
        }
        return null;
    }
}
