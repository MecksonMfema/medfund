package com.medfund.user.publisher;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medfund.shared.testfixtures.AbstractKafkaIntegrationTest;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.kafka.sender.KafkaSender;
import reactor.kafka.sender.SenderOptions;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 13 §B end-to-end guard for {@link PolicyStatusChangedPublisher}: a
 * real reactor-kafka producer against a real broker, one message published,
 * consumed off the topic, and every field of the flat envelope asserted.
 *
 * <p>Chose {@link AbstractKafkaIntegrationTest} over the combined base — the
 * publisher itself has no Postgres surface, so avoiding the second container
 * keeps the class start-up under 6 s.
 */
class PolicyStatusChangedPublisherIT extends AbstractKafkaIntegrationTest {

    private KafkaSender<String, String> kafkaSender;
    private PolicyStatusChangedPublisher publisher;

    @BeforeEach
    void setUpProducer() {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        kafkaSender = KafkaSender.create(SenderOptions.create(props));
        publisher = new PolicyStatusChangedPublisher(kafkaSender, new ObjectMapper());
    }

    @AfterEach
    void tearDownProducer() {
        if (kafkaSender != null) {
            kafkaSender.close();
        }
    }

    @Test
    void publish_reachesBroker_carriesFullEnvelope() {
        UUID policyId = UUID.randomUUID();
        OffsetDateTime effectiveAt =
                OffsetDateTime.of(2026, 8, 24, 12, 0, 0, 0, ZoneOffset.UTC);
        UUID actorId = UUID.fromString("11111111-1111-4000-8000-000000000001");
        String actorEmail = "underwriter+" + UUID.randomUUID() + "@insureflow.test";

        StepVerifier.create(publisher.publish(
                        "tnt-" + UUID.randomUUID(), policyId, "VEHICLE_POLICY", "VEHICLE",
                        "active", "suspended", effectiveAt,
                        "STORAGE_SUSPEND", actorId.toString(), actorEmail))
                .verifyComplete();

        JsonNode msg = consumeMatching(PolicyStatusChangedPublisher.TOPIC,
                node -> actorEmail.equals(node.path("actorEmail").asText()),
                Duration.ofSeconds(10));

        assertThat(msg).as("event landed on the broker").isNotNull();
        assertThat(msg.path("event").asText()).isEqualTo("POLICY_STATUS_CHANGED");
        assertThat(msg.path("policyId").asText()).isEqualTo(policyId.toString());
        assertThat(msg.path("policySource").asText()).isEqualTo("VEHICLE_POLICY");
        assertThat(msg.path("insuranceLine").asText()).isEqualTo("VEHICLE");
        assertThat(msg.path("fromStatus").asText()).isEqualTo("active");
        assertThat(msg.path("toStatus").asText()).isEqualTo("suspended");
        assertThat(msg.path("reasonCode").asText()).isEqualTo("STORAGE_SUSPEND");
        assertThat(msg.path("effectiveAt").asText()).isEqualTo("2026-08-24T12:00Z");
        assertThat(msg.path("actorId").asText()).isEqualTo(actorId.toString());
    }

    /**
     * The base's {@code consumeAuditEvent} helpers filter on
     * {@code entityType}/{@code details} — this envelope doesn't carry those
     * (it's a domain event, not an audit envelope), so poll with a custom
     * matcher on {@code actorEmail} which the test freshens per run.
     */
    private JsonNode consumeMatching(String topic,
                                     java.util.function.Predicate<JsonNode> matcher,
                                     Duration timeout) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "policy-status-changed-it-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");

        ObjectMapper mapper = new ObjectMapper();
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of(topic));
            while (System.currentTimeMillis() < deadline) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
                for (ConsumerRecord<String, String> rec : records) {
                    try {
                        JsonNode node = mapper.readTree(rec.value());
                        if (matcher.test(node)) {
                            return node;
                        }
                    } catch (Exception ignored) {
                    }
                }
            }
        }
        return null;
    }
}
