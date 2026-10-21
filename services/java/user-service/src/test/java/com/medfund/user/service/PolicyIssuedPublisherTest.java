package com.medfund.user.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.kafka.sender.KafkaSender;
import reactor.kafka.sender.SenderRecord;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PolicyIssuedPublisherTest {

    @Mock
    private KafkaSender<String, String> kafkaSender;

    @Captor
    private ArgumentCaptor<Mono<SenderRecord<String, String, String>>> senderRecordCaptor;

    private PolicyIssuedPublisher publisher;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        publisher = new PolicyIssuedPublisher(kafkaSender, objectMapper);
    }

    @Test
    @SuppressWarnings("unchecked")
    void publish_writesToCorrectTopicWithFullPayload() {
        when(kafkaSender.send(any(Mono.class))).thenReturn(Flux.empty());

        UUID policyId = UUID.randomUUID();
        UUID memberId = UUID.randomUUID();
        UUID portfolioId = UUID.randomUUID();
        UUID cohortId = UUID.randomUUID();

        var payload = new PolicyIssuedPublisher.PolicyIssuedPayload(
                "tenant-1", policyId, "POL-2026-001",
                "LIFE_POLICY", "LIFE",
                new BigDecimal("1200.00"), "USD",
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31),
                Instant.parse("2026-01-01T00:00:00Z"),
                memberId, portfolioId, cohortId, null
        );

        StepVerifier.create(publisher.publish(payload)).verifyComplete();

        verify(kafkaSender).send(senderRecordCaptor.capture());
        StepVerifier.create(senderRecordCaptor.getValue())
                .assertNext(record -> {
                    assertThat(record.topic()).isEqualTo("medfund.user.policy-issued");
                    assertThat(record.key()).isEqualTo(policyId.toString());
                    assertThat(record.value()).contains("POLICY_ISSUED");
                    assertThat(record.value()).contains("\"tenantId\":\"tenant-1\"");
                    assertThat(record.value()).contains("\"policySource\":\"LIFE_POLICY\"");
                    assertThat(record.value()).contains("\"insuranceLine\":\"LIFE\"");
                    assertThat(record.value()).contains("\"writtenPremium\":\"1200.00\"");
                    assertThat(record.value()).contains("\"currencyCode\":\"USD\"");
                    assertThat(record.value()).contains("\"policyNumber\":\"POL-2026-001\"");
                    assertThat(record.value()).contains("\"memberId\":\"" + memberId + "\"");
                    assertThat(record.value()).contains("\"portfolioId\":\"" + portfolioId + "\"");
                    assertThat(record.value()).contains("\"cohortId\":\"" + cohortId + "\"");
                    // Null renewedFrom serialises as empty string per wire convention.
                    assertThat(record.value()).contains("\"renewedFromPolicyId\":\"\"");
                })
                .verifyComplete();
    }

    @Test
    void publish_nullWrittenPremium_skipsEmitEntirely() {
        UUID policyId = UUID.randomUUID();
        var payload = new PolicyIssuedPublisher.PolicyIssuedPayload(
                "tenant-1", policyId, "POL-LEGACY",
                "VEHICLE_POLICY", "VEHICLE",
                null, "USD",
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31),
                Instant.parse("2026-01-01T00:00:00Z"),
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), null
        );

        StepVerifier.create(publisher.publish(payload)).verifyComplete();

        verify(kafkaSender, never()).send(any(Mono.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void publish_travelPolicy_usesTripDatesAsCoverageWindow() {
        when(kafkaSender.send(any(Mono.class))).thenReturn(Flux.empty());

        UUID policyId = UUID.randomUUID();
        var payload = new PolicyIssuedPublisher.PolicyIssuedPayload(
                "tenant-2", policyId, "TRV-99",
                "TRAVEL_POLICY", "TRAVEL",
                new BigDecimal("120.50"), "EUR",
                LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30),
                Instant.parse("2026-05-15T09:00:00Z"),
                UUID.randomUUID(), null, null, null
        );

        StepVerifier.create(publisher.publish(payload)).verifyComplete();

        verify(kafkaSender).send(senderRecordCaptor.capture());
        StepVerifier.create(senderRecordCaptor.getValue())
                .assertNext(record -> {
                    assertThat(record.topic()).isEqualTo("medfund.user.policy-issued");
                    assertThat(record.value()).contains("\"insuranceLine\":\"TRAVEL\"");
                    assertThat(record.value()).contains("\"coverageStart\":\"2026-06-01\"");
                    assertThat(record.value()).contains("\"coverageEnd\":\"2026-06-30\"");
                    // Nullable portfolio + cohort collapse to empty-string on the wire.
                    assertThat(record.value()).contains("\"portfolioId\":\"\"");
                    assertThat(record.value()).contains("\"cohortId\":\"\"");
                })
                .verifyComplete();
    }
}
