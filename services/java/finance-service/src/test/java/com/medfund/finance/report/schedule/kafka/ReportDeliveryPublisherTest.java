package com.medfund.finance.report.schedule.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.medfund.shared.report.ReportDeliveryEvent;
import com.medfund.shared.report.ReportDeliveryFailedEvent;
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

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReportDeliveryPublisherTest {

    @SuppressWarnings("unchecked")
    @Test
    void publish_serialisesEventAndKeysByTenantId() {
        KafkaSender<String, String> sender = mock(KafkaSender.class);
        SenderResult<UUID> result = mock(SenderResult.class);
        RecordMetadata meta = new RecordMetadata(
                new TopicPartition(ReportDeliveryEvent.TOPIC, 0),
                0L, 0, System.currentTimeMillis(), 0, 0);
        when(result.recordMetadata()).thenReturn(meta);
        when(sender.send(any(Mono.class))).thenReturn(Flux.just(result));

        var publisher = new ReportDeliveryPublisher(sender, new ObjectMapper().registerModule(new JavaTimeModule()));
        UUID tenantId = UUID.randomUUID();
        ReportDeliveryEvent event = new ReportDeliveryEvent(
                UUID.randomUUID(), UUID.randomUUID(), tenantId,
                "COMMISSION_STATEMENT",
                "acme/2026/09/x.xlsx", "sha", 12345L,
                "Monthly", "2026-08-01", "2026-08-31",
                "USD", Instant.now(), ReportDeliveryEvent.SCHEMA_VERSION);

        StepVerifier.create(publisher.publish(event)).verifyComplete();

        ArgumentCaptor<Mono<SenderRecord<String, String, UUID>>> captor = ArgumentCaptor.forClass(Mono.class);
        verify(sender).send(captor.capture());
        SenderRecord<String, String, UUID> record = captor.getValue().block();
        assertThat(record).isNotNull();
        assertThat(record.topic()).isEqualTo(ReportDeliveryEvent.TOPIC);
        assertThat(record.key()).isEqualTo(tenantId.toString());
        assertThat(record.value()).contains("COMMISSION_STATEMENT").contains(tenantId.toString());
    }

    @SuppressWarnings("unchecked")
    @Test
    void failedPublisher_serialisesEnvelopeAndKeysByTenantId() {
        KafkaSender<String, String> sender = mock(KafkaSender.class);
        SenderResult<UUID> result = mock(SenderResult.class);
        RecordMetadata meta = new RecordMetadata(
                new TopicPartition(ReportDeliveryFailedEvent.TOPIC, 0),
                0L, 0, System.currentTimeMillis(), 0, 0);
        when(result.recordMetadata()).thenReturn(meta);
        when(sender.send(any(Mono.class))).thenReturn(Flux.just(result));

        var publisher = new ReportDeliveryFailedPublisher(sender, new ObjectMapper().registerModule(new JavaTimeModule()));
        UUID tenantId = UUID.randomUUID();
        ReportDeliveryFailedEvent event = new ReportDeliveryFailedEvent(
                UUID.randomUUID(), UUID.randomUUID(), tenantId,
                "COMMISSION_STATEMENT", "2026-08-01", "2026-08-31",
                "SHAPE", "boom", Instant.now(),
                ReportDeliveryFailedEvent.SCHEMA_VERSION);

        StepVerifier.create(publisher.publish(event)).verifyComplete();

        ArgumentCaptor<Mono<SenderRecord<String, String, UUID>>> captor = ArgumentCaptor.forClass(Mono.class);
        verify(sender).send(captor.capture());
        SenderRecord<String, String, UUID> record = captor.getValue().block();
        assertThat(record).isNotNull();
        assertThat(record.topic()).isEqualTo(ReportDeliveryFailedEvent.TOPIC);
        assertThat(record.key()).isEqualTo(tenantId.toString());
        assertThat(record.value()).contains("SHAPE").contains("boom");
    }
}
