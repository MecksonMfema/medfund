package com.medfund.finance.regulatory.aml.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.medfund.shared.report.SuspiciousTransactionEvent;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.kafka.sender.KafkaSender;
import reactor.kafka.sender.SenderRecord;
import reactor.kafka.sender.SenderResult;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link SuspiciousTransactionEventPublisher}. Covers the
 * canonical-topic publish + the record key (alertId → partition affinity
 * so transitions land in order for the same alert) + JSON serialisation
 * shape + broker-error propagation to the caller (the caller in
 * {@code AmlAlertService} is what decides whether to swallow it).
 */
class SuspiciousTransactionEventPublisherTest {

    @Test
    void publishesToCanonicalTopic_withAlertIdKey() {
        SuspiciousTransactionEvent event = sampleEvent();
        @SuppressWarnings("unchecked")
        KafkaSender<String, String> sender = mock(KafkaSender.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Mono<SenderRecord<String, String, UUID>>> monoCaptor =
                ArgumentCaptor.forClass(Mono.class);
        when(sender.send(monoCaptor.capture())).thenReturn(Flux.just(canned(event.alertId())));

        SuspiciousTransactionEventPublisher publisher =
                new SuspiciousTransactionEventPublisher(sender, jsonMapper());
        StepVerifier.create(publisher.publish(event)).verifyComplete();

        verify(sender, times(1)).send(any());

        List<Mono<SenderRecord<String, String, UUID>>> monos = monoCaptor.getAllValues();
        SenderRecord<String, String, UUID> record = monos.get(0).block();
        assertThat(record.topic()).isEqualTo(SuspiciousTransactionEvent.TOPIC);
        assertThat(record.key()).isEqualTo(event.alertId().toString());
        assertThat(record.correlationMetadata()).isEqualTo(event.alertId());
    }

    @Test
    void serializesEventFieldsIntoRecordValue() {
        SuspiciousTransactionEvent event = sampleEvent();
        @SuppressWarnings("unchecked")
        KafkaSender<String, String> sender = mock(KafkaSender.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Mono<SenderRecord<String, String, UUID>>> monoCaptor =
                ArgumentCaptor.forClass(Mono.class);
        when(sender.send(monoCaptor.capture())).thenReturn(Flux.just(canned(event.alertId())));

        new SuspiciousTransactionEventPublisher(sender, jsonMapper())
                .publish(event).block();

        SenderRecord<String, String, UUID> record = monoCaptor.getValue().block();
        String value = record.value();
        assertThat(value).contains("\"schemaVersion\":1");
        assertThat(value).contains("\"transactionRef\":\"TXN-KAFKA-001\"");
        assertThat(value).contains("\"transition\":\"REVIEW\"");
        assertThat(value).contains("\"priorStatus\":\"RAISED\"");
        assertThat(value).contains("\"newStatus\":\"REVIEWED\"");
        assertThat(value).contains("\"actorEmail\":\"reviewer@medfund\"");
    }

    @Test
    void brokerFailurePropagatesToCaller() {
        // The service layer decides whether to swallow it — the publisher
        // itself must surface the error so the caller can log + retry.
        @SuppressWarnings("unchecked")
        KafkaSender<String, String> sender = mock(KafkaSender.class);
        when(sender.send(any()))
                .thenReturn(Flux.error(new IllegalStateException("broker unreachable")));

        StepVerifier.create(new SuspiciousTransactionEventPublisher(sender, jsonMapper())
                        .publish(sampleEvent()))
                .expectErrorSatisfies(e -> assertThat(e.getMessage()).contains("broker unreachable"))
                .verify();
    }

    // ── Fixtures ──────────────────────────────────────────────────────────

    /** Mirrors the Spring-auto-configured mapper: JavaTimeModule for Instant. */
    private static ObjectMapper jsonMapper() {
        return new ObjectMapper().registerModule(new JavaTimeModule());
    }

    private static SuspiciousTransactionEvent sampleEvent() {
        return new SuspiciousTransactionEvent(
                SuspiciousTransactionEvent.CURRENT_SCHEMA_VERSION,
                UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"),
                UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"),
                "TXN-KAFKA-001",
                "PREMIUM",
                new BigDecimal("15000.00"),
                "USD",
                UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc"),
                null,
                "RAISED",
                "REVIEWED",
                SuspiciousTransactionEvent.TRANSITION_REVIEW,
                "reviewer-actor-id",
                "reviewer@medfund",
                Instant.parse("2026-08-30T12:00:00Z"));
    }

    private static SenderResult<UUID> canned(UUID id) {
        RecordMetadata md = new RecordMetadata(
                new TopicPartition(SuspiciousTransactionEvent.TOPIC, 0),
                0L, 0, 0L, 0, 0);
        return new SenderResult<>() {
            @Override public RecordMetadata recordMetadata() { return md; }
            @Override public Exception exception() { return null; }
            @Override public UUID correlationMetadata() { return id; }
        };
    }
}
