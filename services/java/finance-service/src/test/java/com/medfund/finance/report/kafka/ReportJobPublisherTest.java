package com.medfund.finance.report.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medfund.shared.report.ReportJobRequestedEvent;
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

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReportJobPublisherTest {

    @Test
    void publishesEventOnlyToCanonicalTopic() {
        // Phase 15 §22 cutover: legacy medfund.actuarial.job-requested dual-write
        // dropped; publisher writes to canonical topic only.
        ReportJobRequestedEvent event = event(UUID.randomUUID(), UUID.randomUUID(), tinyParams());
        @SuppressWarnings("unchecked")
        KafkaSender<String, String> sender = mock(KafkaSender.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Mono<SenderRecord<String, String, UUID>>> monoCaptor =
                ArgumentCaptor.forClass(Mono.class);
        when(sender.send(monoCaptor.capture())).thenReturn(Flux.just(canned(event.jobId())));

        ReportJobPublisher publisher = new ReportJobPublisher(sender, new ObjectMapper());
        StepVerifier.create(publisher.publish(event)).verifyComplete();

        verify(sender, times(1)).send(any());

        List<Mono<SenderRecord<String, String, UUID>>> monos = monoCaptor.getAllValues();
        List<String> topics = monos.stream()
                .map(mono -> mono.block())
                .map(SenderRecord::topic)
                .toList();
        assertThat(topics).containsExactly(ReportJobPublisher.TOPIC);

        SenderRecord<String, String, UUID> record = monos.get(0).block();
        assertThat(record.correlationMetadata()).isEqualTo(event.jobId());
        assertThat(record.value()).contains("\"jobId\":\"" + event.jobId() + "\"");
        assertThat(record.value()).contains("\"tenantId\":\"" + event.tenantId() + "\"");
    }

    @Test
    void canonicalPublishFailurePropagates() {
        // Phase 15 §22: canonical is authoritative; a Kafka failure must
        // bubble out so the caller can flip the job row to 'failed'.
        ReportJobRequestedEvent event = event(UUID.randomUUID(), UUID.randomUUID(), tinyParams());
        @SuppressWarnings("unchecked")
        KafkaSender<String, String> sender = mock(KafkaSender.class);
        when(sender.send(any()))
                .thenReturn(Flux.error(new IllegalStateException("broker unreachable")));

        ReportJobPublisher publisher = new ReportJobPublisher(sender, new ObjectMapper());
        StepVerifier.create(publisher.publish(event))
                .expectErrorSatisfies(e -> assertThat(e.getMessage()).contains("broker unreachable"))
                .verify();
    }

    @Test
    void rejectsPayloadOver900Kb() {
        // Fabricate a params map that serialises well beyond 900KB.
        Map<String, Object> huge = new HashMap<>();
        String junk = "x".repeat(1024);
        for (int i = 0; i < 1000; i++) {
            huge.put("key-" + i, junk);
        }
        ReportJobRequestedEvent event = event(UUID.randomUUID(), UUID.randomUUID(), huge);
        @SuppressWarnings("unchecked")
        KafkaSender<String, String> sender = mock(KafkaSender.class);

        ReportJobPublisher publisher = new ReportJobPublisher(sender, new ObjectMapper());
        StepVerifier.create(publisher.publish(event))
                .expectErrorSatisfies(e -> {
                    assertThat(e).isInstanceOf(IllegalStateException.class);
                    assertThat(e.getMessage()).contains("too large");
                })
                .verify();
    }

    @Test
    void serialisationFailurePropagatesAsIllegalState() {
        // Craft a value that Jackson cannot serialise (self-referencing map).
        Map<String, Object> selfRef = new LinkedHashMap<>();
        selfRef.put("self", selfRef);
        ReportJobRequestedEvent event = event(UUID.randomUUID(), UUID.randomUUID(), selfRef);
        @SuppressWarnings("unchecked")
        KafkaSender<String, String> sender = mock(KafkaSender.class);
        ReportJobPublisher publisher = new ReportJobPublisher(sender, new ObjectMapper());

        StepVerifier.create(publisher.publish(event))
                .expectError(IllegalStateException.class)
                .verify();
    }

    private static ReportJobRequestedEvent event(UUID jobId, UUID tenantId, Map<String, Object> params) {
        return new ReportJobRequestedEvent(
                ReportJobRequestedEvent.CURRENT_SCHEMA_VERSION,
                jobId, tenantId, null,
                "IBNR_TRIANGLE",
                params, null, null, null, null, null,
                UUID.randomUUID(), "actor@example.test");
    }

    private static Map<String, Object> tinyParams() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("shape", "paid");
        params.put("grain", "quarter");
        return params;
    }

    private static SenderResult<UUID> canned(UUID correlation) {
        return new SenderResult<>() {
            @Override public RecordMetadata recordMetadata() {
                return new RecordMetadata(new TopicPartition(ReportJobPublisher.TOPIC, 0),
                        0L, 0, 0L, 0, 0);
            }
            @Override public Exception exception() { return null; }
            @Override public UUID correlationMetadata() { return correlation; }
        };
    }

    @SuppressWarnings("unchecked")
    private static <T> T any() {
        return org.mockito.ArgumentMatchers.any();
    }
}
