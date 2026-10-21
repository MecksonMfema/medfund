package com.medfund.user.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medfund.shared.tenant.TenantContext;
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
import reactor.util.context.Context;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 15 §19: unit coverage for the Kafka-backed IFRS 17 material event
 * publisher. Verifies wire shape, tenant partition key, tenant-missing
 * drop-and-log guard, and Kafka failure semantics.
 */
@ExtendWith(MockitoExtension.class)
class KafkaIfrs17MaterialEventPublisherTest {

    @Mock private KafkaSender<String, String> kafkaSender;

    @Captor private ArgumentCaptor<Mono<SenderRecord<String, String, String>>> senderRecordCaptor;

    private KafkaIfrs17MaterialEventPublisher publisher;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String TENANT_ID = "00000000-0000-4000-8000-000000000001";

    @BeforeEach
    void setUp() {
        publisher = new KafkaIfrs17MaterialEventPublisher(kafkaSender, objectMapper);
    }

    @Test
    @SuppressWarnings("unchecked")
    void publish_populatesFullPayload_partitionsByTenantId() {
        when(kafkaSender.send(any(Mono.class))).thenReturn(Flux.empty());

        UUID cohortId = UUID.fromString("aaaaaaaa-aaaa-4000-8000-000000000001");
        UUID sourceRunId = UUID.fromString("bbbbbbbb-bbbb-4000-8000-000000000002");

        StepVerifier.create(publisher.publish(
                        cohortId, "ONEROUS_TRANSITION", "WARN",
                        "Cohort flipped ONEROUS on run xyz", sourceRunId)
                        .contextWrite(TenantContext.put(Context.empty(), TENANT_ID)))
                .verifyComplete();

        verify(kafkaSender).send(senderRecordCaptor.capture());
        StepVerifier.create(senderRecordCaptor.getValue())
                .assertNext(record -> {
                    assertThat(record.topic()).isEqualTo("medfund.ifrs17.material-event");
                    assertThat(record.key()).isEqualTo(TENANT_ID);
                    assertThat(record.value())
                            .contains("\"event\":\"IFRS17_MATERIAL_EVENT\"")
                            .contains("\"schemaVersion\":\"1\"")
                            .contains("\"tenantId\":\"" + TENANT_ID + "\"")
                            .contains("\"cohortId\":\"" + cohortId + "\"")
                            .contains("\"eventType\":\"ONEROUS_TRANSITION\"")
                            .contains("\"severity\":\"WARN\"")
                            .contains("\"message\":\"Cohort flipped ONEROUS on run xyz\"")
                            .contains("\"sourceRunId\":\"" + sourceRunId + "\"")
                            .contains("\"occurredAt\":");
                })
                .verifyComplete();
    }

    @Test
    @SuppressWarnings("unchecked")
    void publish_nullableFieldsSerialiseAsEmptyStrings() {
        when(kafkaSender.send(any(Mono.class))).thenReturn(Flux.empty());

        UUID cohortId = UUID.randomUUID();

        StepVerifier.create(publisher.publish(cohortId, "CSM_NEGATIVE", null, null, null)
                        .contextWrite(TenantContext.put(Context.empty(), TENANT_ID)))
                .verifyComplete();

        verify(kafkaSender).send(senderRecordCaptor.capture());
        StepVerifier.create(senderRecordCaptor.getValue())
                .assertNext(record -> assertThat(record.value())
                        .contains("\"severity\":\"INFO\"")  // default when null
                        .contains("\"message\":\"\"")
                        .contains("\"sourceRunId\":\"\""))
                .verifyComplete();
    }

    @Test
    @SuppressWarnings("unchecked")
    void publish_missingTenantOnContext_dropsAndDoesNotHitKafka() {
        StepVerifier.create(publisher.publish(
                        UUID.randomUUID(), "ONEROUS_TRANSITION", "WARN", "msg", null)
                        .contextWrite(Context.empty()))
                .verifyComplete();

        verify(kafkaSender, never()).send(any(Mono.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void publish_kafkaError_isLogged_andErrorBubbles() {
        when(kafkaSender.send(any(Mono.class)))
                .thenReturn(Flux.error(new RuntimeException("broker down")));

        StepVerifier.create(publisher.publish(
                        UUID.randomUUID(), "ONEROUS_TRANSITION", "WARN", "msg", null)
                        .contextWrite(TenantContext.put(Context.empty(), TENANT_ID)))
                .expectErrorMessage("broker down")
                .verify();
    }
}
