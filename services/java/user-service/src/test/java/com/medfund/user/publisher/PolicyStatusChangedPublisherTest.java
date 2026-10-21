package com.medfund.user.publisher;

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

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 13 §B: unit coverage for the policy-status-changed publisher — topic,
 * partition key, populated payload, and Kafka failure surface. Wire-shape
 * mirrors {@link com.medfund.user.service.UserEventPublisherTest}.
 */
@ExtendWith(MockitoExtension.class)
class PolicyStatusChangedPublisherTest {

    @Mock private KafkaSender<String, String> kafkaSender;

    @Captor private ArgumentCaptor<Mono<SenderRecord<String, String, String>>> senderRecordCaptor;

    private PolicyStatusChangedPublisher publisher;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        publisher = new PolicyStatusChangedPublisher(kafkaSender, objectMapper);
    }

    @Test
    @SuppressWarnings("unchecked")
    void publish_populatesFullPayload_partitionsByPolicyId() {
        when(kafkaSender.send(any(Mono.class))).thenReturn(Flux.empty());

        UUID policyId = UUID.fromString("aaaaaaaa-aaaa-4000-8000-000000000001");
        OffsetDateTime effectiveAt =
                OffsetDateTime.of(2026, 8, 24, 12, 34, 56, 0, ZoneOffset.UTC);

        StepVerifier.create(publisher.publish(
                        "tnt-42", policyId, "LIFE_POLICY", "LIFE",
                        "active", "lapsed", effectiveAt,
                        "NON_PAYMENT",
                        "22222222-2222-4000-8000-000000000002",
                        "underwriter@insureflow.test"))
                .verifyComplete();

        verify(kafkaSender).send(senderRecordCaptor.capture());

        StepVerifier.create(senderRecordCaptor.getValue())
                .assertNext(record -> {
                    assertThat(record.topic()).isEqualTo("medfund.user.policy-status-changed");
                    assertThat(record.key()).isEqualTo(policyId.toString());
                    assertThat(record.value())
                            .contains("\"event\":\"POLICY_STATUS_CHANGED\"")
                            .contains("\"tenantId\":\"tnt-42\"")
                            .contains("\"policyId\":\"" + policyId + "\"")
                            .contains("\"policySource\":\"LIFE_POLICY\"")
                            .contains("\"insuranceLine\":\"LIFE\"")
                            .contains("\"fromStatus\":\"active\"")
                            .contains("\"toStatus\":\"lapsed\"")
                            .contains("\"effectiveAt\":\"2026-08-24T12:34:56Z\"")
                            .contains("\"reasonCode\":\"NON_PAYMENT\"")
                            .contains("\"actorId\":\"22222222-2222-4000-8000-000000000002\"")
                            .contains("\"actorEmail\":\"underwriter@insureflow.test\"");
                })
                .verifyComplete();
    }

    @Test
    @SuppressWarnings("unchecked")
    void publish_nullableFields_serialiseAsEmptyStrings() {
        when(kafkaSender.send(any(Mono.class))).thenReturn(Flux.empty());

        UUID policyId = UUID.randomUUID();
        OffsetDateTime effectiveAt = OffsetDateTime.now();

        // Nullable: tenantId, fromStatus (on a first-time transition), reasonCode,
        // actorId (system-initiated). actorEmail + toStatus are required.
        StepVerifier.create(publisher.publish(
                        null, policyId, "VEHICLE_POLICY", "VEHICLE",
                        null, "terminated", effectiveAt,
                        null, null, "system@insureflow"))
                .verifyComplete();

        verify(kafkaSender).send(senderRecordCaptor.capture());
        StepVerifier.create(senderRecordCaptor.getValue())
                .assertNext(record -> assertThat(record.value())
                        .contains("\"tenantId\":\"\"")
                        .contains("\"fromStatus\":\"\"")
                        .contains("\"reasonCode\":\"\"")
                        .contains("\"actorId\":\"\"")
                        .contains("\"toStatus\":\"terminated\"")
                        .contains("\"actorEmail\":\"system@insureflow\""))
                .verifyComplete();
    }

    @Test
    @SuppressWarnings("unchecked")
    void publish_kafkaError_isLogged_andErrorBubbles() {
        // Downstream consumers (contributions-service earning-schedule closure)
        // are critical enough that we don't want a silent Kafka failure to
        // hide a missed close-out. Errors bubble to the caller; the transition
        // service will roll back the transaction, matching the AuditPublisher
        // failure semantic.
        when(kafkaSender.send(any(Mono.class)))
                .thenReturn(Flux.error(new RuntimeException("broker down")));

        StepVerifier.create(publisher.publish(
                        "tnt-1", UUID.randomUUID(), "LIFE_POLICY", "LIFE",
                        "active", "suspended", OffsetDateTime.now(),
                        "ADMIN_CORRECTION",
                        "11111111-1111-4000-8000-000000000001",
                        "admin@insureflow"))
                .expectErrorMessage("broker down")
                .verify();
    }
}
