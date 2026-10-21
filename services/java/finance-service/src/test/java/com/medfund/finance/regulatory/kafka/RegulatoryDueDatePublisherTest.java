package com.medfund.finance.regulatory.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.medfund.shared.report.RegulatoryDueDateApproachingEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.kafka.sender.KafkaSender;
import reactor.kafka.sender.SenderRecord;
import reactor.kafka.sender.SenderResult;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RegulatoryDueDatePublisherTest {

    @Mock
    private KafkaSender<String, String> kafkaSender;

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    void publish_sendsJsonEncodedRecordWithTenantAsPartitionKey() throws Exception {
        UUID tenantId = UUID.randomUUID();
        var event = new RegulatoryDueDateApproachingEvent(
                1, tenantId, "IPEC_QUARTERLY_RETURN",
                LocalDate.of(2026, 4, 1), LocalDate.of(2026, 6, 30),
                LocalDate.of(2026, 7, 30), 7, "AMBER", "DUE_DATE_7D",
                Instant.parse("2026-07-23T03:00:00Z"));

        @SuppressWarnings("unchecked")
        SenderResult<String> result = org.mockito.Mockito.mock(SenderResult.class);
        when(kafkaSender.send(any(Mono.class))).thenReturn(Flux.just(result));

        var publisher = new RegulatoryDueDatePublisher(kafkaSender, mapper);
        StepVerifier.create(publisher.publish(event)).verifyComplete();

        @SuppressWarnings({"unchecked", "rawtypes"})
        ArgumentCaptor<Mono<SenderRecord<String, String, String>>> monoCaptor =
                ArgumentCaptor.forClass((Class) Mono.class);
        verify(kafkaSender).send(monoCaptor.capture());
        SenderRecord<String, String, String> sent = monoCaptor.getValue().block();
        assertThat(sent).isNotNull();
        assertThat(sent.topic()).isEqualTo(RegulatoryDueDateApproachingEvent.TOPIC);
        assertThat(sent.key()).isEqualTo(tenantId.toString());
        JsonNode body = mapper.readTree(sent.value());
        assertThat(body.get("tenantId").asText()).isEqualTo(tenantId.toString());
        assertThat(body.get("reportKey").asText()).isEqualTo("IPEC_QUARTERLY_RETURN");
        assertThat(body.get("eventTier").asText()).isEqualTo("DUE_DATE_7D");
        assertThat(body.get("severity").asText()).isEqualTo("AMBER");
        assertThat(body.get("daysUntilDue").asLong()).isEqualTo(7);
        assertThat(body.get("schemaVersion").asInt()).isEqualTo(1);
    }

    @Test
    void publish_nullEvent_dropsSilently() {
        var publisher = new RegulatoryDueDatePublisher(kafkaSender, mapper);
        StepVerifier.create(publisher.publish(null)).verifyComplete();
        verify(kafkaSender, never()).send(any(Mono.class));
    }

    @Test
    void publish_nullTenantId_dropsSilently() {
        var event = new RegulatoryDueDateApproachingEvent(
                1, null, "IPEC_QUARTERLY_RETURN",
                LocalDate.now(), LocalDate.now(), LocalDate.now(), 7, "AMBER", "DUE_DATE_7D",
                Instant.now());
        var publisher = new RegulatoryDueDatePublisher(kafkaSender, mapper);
        StepVerifier.create(publisher.publish(event)).verifyComplete();
        verify(kafkaSender, never()).send(any(Mono.class));
    }

    @Test
    void publish_kafkaError_completesSilently() {
        UUID tenantId = UUID.randomUUID();
        var event = new RegulatoryDueDateApproachingEvent(
                1, tenantId, "VAT_RETURN",
                LocalDate.now(), LocalDate.now(), LocalDate.now(), -1, "RED", "DUE_DATE_OVERDUE",
                Instant.now());
        when(kafkaSender.send(any(Mono.class))).thenReturn(Flux.error(new RuntimeException("broker down")));

        var publisher = new RegulatoryDueDatePublisher(kafkaSender, mapper);
        StepVerifier.create(publisher.publish(event)).verifyComplete();
    }
}
