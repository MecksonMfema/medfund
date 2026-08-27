package com.medfund.finance.actuarial.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medfund.shared.actuarial.ActuarialJobRequestedEvent;
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
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ActuarialJobPublisherTest {

    @Test
    void publishesEventToTopicAndCompletes() {
        ActuarialJobRequestedEvent event = event(UUID.randomUUID(), UUID.randomUUID(), tinyParams());
        @SuppressWarnings("unchecked")
        KafkaSender<String, String> sender = mock(KafkaSender.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Mono<SenderRecord<String, String, UUID>>> monoCaptor = ArgumentCaptor.forClass(Mono.class);
        when(sender.send(monoCaptor.capture())).thenReturn(Flux.just(canned(event.jobId())));

        ActuarialJobPublisher publisher = new ActuarialJobPublisher(sender, new ObjectMapper());
        StepVerifier.create(publisher.publish(event)).verifyComplete();

        SenderRecord<String, String, UUID> sent = monoCaptor.getValue().block();
        assertThat(sent.correlationMetadata()).isEqualTo(event.jobId());
        assertThat(sent.value()).contains("\"jobId\":\"" + event.jobId() + "\"");
        assertThat(sent.value()).contains("\"tenantId\":\"" + event.tenantId() + "\"");
    }

    @Test
    void rejectsPayloadOver900Kb() {
        // Fabricate a params map that serialises well beyond 900KB.
        Map<String, Object> huge = new HashMap<>();
        String junk = "x".repeat(1024);
        for (int i = 0; i < 1000; i++) {
            huge.put("key-" + i, junk);
        }
        ActuarialJobRequestedEvent event = event(UUID.randomUUID(), UUID.randomUUID(), huge);
        @SuppressWarnings("unchecked")
        KafkaSender<String, String> sender = mock(KafkaSender.class);

        ActuarialJobPublisher publisher = new ActuarialJobPublisher(sender, new ObjectMapper());
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
        ActuarialJobRequestedEvent event = event(UUID.randomUUID(), UUID.randomUUID(), selfRef);
        @SuppressWarnings("unchecked")
        KafkaSender<String, String> sender = mock(KafkaSender.class);
        ActuarialJobPublisher publisher = new ActuarialJobPublisher(sender, new ObjectMapper());

        StepVerifier.create(publisher.publish(event))
                .expectError(IllegalStateException.class)
                .verify();
    }

    private static ActuarialJobRequestedEvent event(UUID jobId, UUID tenantId, Map<String, Object> params) {
        return new ActuarialJobRequestedEvent(
                ActuarialJobRequestedEvent.CURRENT_SCHEMA_VERSION,
                jobId, tenantId,
                "IBNR_TRIANGLE",
                params, null, null, null,
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
                return new RecordMetadata(new TopicPartition("medfund.actuarial.job-requested", 0),
                        0L, 0, 0L, 0, 0);
            }
            @Override public Exception exception() { return null; }
            @Override public UUID correlationMetadata() { return correlation; }
        };
    }
}
