package com.medfund.finance.ifrs17.kafka;

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

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 15 §19 finance-service Kafka publisher unit test — mirrors the
 * user-service publisher's spec. Pins wire shape, tenant partition key,
 * missing-tenant drop guard, and swallow-on-Kafka-error semantics (the
 * publisher never blocks the aggregator's commit path).
 */
@ExtendWith(MockitoExtension.class)
class Ifrs17MaterialEventPublisherTest {

    @Mock private KafkaSender<String, String> kafkaSender;

    @Captor private ArgumentCaptor<Mono<SenderRecord<String, String, String>>> senderRecordCaptor;

    private Ifrs17MaterialEventPublisher publisher;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String TENANT_ID = "00000000-0000-4000-8000-000000000001";

    @BeforeEach
    void setUp() {
        publisher = new Ifrs17MaterialEventPublisher(kafkaSender, objectMapper);
    }

    @Test
    @SuppressWarnings("unchecked")
    void publish_populatesFullPayload_partitionsByTenantId() {
        when(kafkaSender.send(any(Mono.class))).thenReturn(Flux.empty());

        UUID cohortId = UUID.fromString("aaaaaaaa-aaaa-4000-8000-000000000001");
        UUID sourceRunId = UUID.fromString("bbbbbbbb-bbbb-4000-8000-000000000002");

        StepVerifier.create(publisher.publish(
                        TENANT_ID, cohortId, "CSM_NEGATIVE", "WARN",
                        "CSM went negative on cohort", sourceRunId))
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
                            .contains("\"eventType\":\"CSM_NEGATIVE\"")
                            .contains("\"severity\":\"WARN\"")
                            .contains("\"message\":\"CSM went negative on cohort\"")
                            .contains("\"sourceRunId\":\"" + sourceRunId + "\"");
                })
                .verifyComplete();
    }

    @Test
    @SuppressWarnings("unchecked")
    void publish_missingTenant_dropsWithoutSend() {
        StepVerifier.create(publisher.publish(
                        null, UUID.randomUUID(), "CSM_NEGATIVE", "WARN", "msg", null))
                .verifyComplete();
        verify(kafkaSender, never()).send(any(Mono.class));

        StepVerifier.create(publisher.publish(
                        "  ", UUID.randomUUID(), "CSM_NEGATIVE", "WARN", "msg", null))
                .verifyComplete();
        verify(kafkaSender, never()).send(any(Mono.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void publish_nullCohortAndSourceRun_emitEmptyStrings() {
        when(kafkaSender.send(any(Mono.class))).thenReturn(Flux.empty());

        StepVerifier.create(publisher.publish(
                        TENANT_ID, null, "IBNR_SUB_JOB_STALE", null, null, null))
                .verifyComplete();

        verify(kafkaSender).send(senderRecordCaptor.capture());
        StepVerifier.create(senderRecordCaptor.getValue())
                .assertNext(record -> assertThat(record.value())
                        .contains("\"cohortId\":\"\"")
                        .contains("\"severity\":\"INFO\"")  // default
                        .contains("\"message\":\"\"")
                        .contains("\"sourceRunId\":\"\""))
                .verifyComplete();
    }

    @Test
    @SuppressWarnings("unchecked")
    void publish_kafkaError_swallowsSoAggregatorCommitIsNotBlocked() {
        // The publisher is called from the aggregator AFTER the parent row is
        // saved — a Kafka broker outage should NOT bubble back and roll the
        // aggregator's commit. Verified by expecting the Mono to complete
        // normally even when the send Flux errors.
        when(kafkaSender.send(any(Mono.class)))
                .thenReturn(Flux.error(new RuntimeException("broker down")));

        StepVerifier.create(publisher.publish(
                        TENANT_ID, UUID.randomUUID(), "CSM_NEGATIVE", "WARN", "msg", null))
                .verifyComplete();
    }
}
