package com.medfund.shared.security;

import com.fasterxml.jackson.databind.JsonNode;
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

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SecurityEventPublisherTest {

    @Mock
    private KafkaSender<String, String> kafkaSender;

    @Captor
    private ArgumentCaptor<Mono<SenderRecord<String, String, String>>> captor;

    private SecurityEventPublisher publisher;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        publisher = new SecurityEventPublisher(kafkaSender, objectMapper);
    }

    @Test
    @SuppressWarnings("unchecked")
    void publishDataAccess_sendsMergedDetailsToSecurityTopic() throws Exception {
        when(kafkaSender.send(any(Mono.class))).thenReturn(Flux.empty());

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("period", "2026-08");
        details.put("rows", 42);

        StepVerifier.create(publisher.publishDataAccess(
                        "tenant-1", "actor-1", "actor@example.com",
                        "AGED_DEBTORS", details))
                .verifyComplete();

        verify(kafkaSender).send(captor.capture());
        StepVerifier.create(captor.getValue())
                .assertNext(record -> {
                    assertThat(record.topic()).isEqualTo("medfund.security.events");
                    assertThat(record.key()).isEqualTo("actor-1");
                    try {
                        JsonNode outer = objectMapper.readTree(record.value());
                        assertThat(outer.get("eventType").asText()).isEqualTo("DATA_ACCESS");
                        assertThat(outer.get("tenantId").asText()).isEqualTo("tenant-1");
                        assertThat(outer.get("userId").asText()).isEqualTo("actor-1");
                        assertThat(outer.get("actorEmail").asText()).isEqualTo("actor@example.com");
                        // details is a JSON-encoded string inside the outer envelope
                        JsonNode inner = objectMapper.readTree(outer.get("details").asText());
                        assertThat(inner.get("reportKey").asText()).isEqualTo("AGED_DEBTORS");
                        assertThat(inner.get("period").asText()).isEqualTo("2026-08");
                        assertThat(inner.get("rows").asInt()).isEqualTo(42);
                    } catch (Exception e) {
                        throw new AssertionError("Failed to parse payload", e);
                    }
                })
                .verifyComplete();
    }

    @Test
    @SuppressWarnings("unchecked")
    void publishDataAccess_tolerantOfNullDetailsAndBlankIds() throws Exception {
        when(kafkaSender.send(any(Mono.class))).thenReturn(Flux.empty());

        StepVerifier.create(publisher.publishDataAccess(
                        null, null, null, "CREDITORS", null))
                .verifyComplete();

        verify(kafkaSender).send(captor.capture());
        StepVerifier.create(captor.getValue())
                .assertNext(record -> {
                    assertThat(record.key()).isEmpty();
                    try {
                        JsonNode outer = objectMapper.readTree(record.value());
                        assertThat(outer.get("tenantId").asText()).isEmpty();
                        assertThat(outer.get("userId").asText()).isEmpty();
                        assertThat(outer.get("actorEmail").asText()).isEmpty();
                        JsonNode inner = objectMapper.readTree(outer.get("details").asText());
                        assertThat(inner.get("reportKey").asText()).isEqualTo("CREDITORS");
                    } catch (Exception e) {
                        throw new AssertionError("Failed to parse payload", e);
                    }
                })
                .verifyComplete();
    }

    @Test
    @SuppressWarnings("unchecked")
    void publish_swallowsSendErrors() {
        when(kafkaSender.send(any(Mono.class))).thenReturn(Flux.error(new RuntimeException("broker down")));

        SecurityEventMessage msg = new SecurityEventMessage(
                "id", "tenant", "DATA_ACCESS", "user", "user@example.com",
                "1.2.3.4", "curl", "{}", "2026-08-11T00:00:00Z");

        // Errors are swallowed — a failed audit publish must never abort a
        // user-facing operation. Anything else would let a Kafka hiccup
        // break invoice-PDF downloads or report exports.
        StepVerifier.create(publisher.publish(msg)).verifyComplete();
    }

    @Test
    @SuppressWarnings("unchecked")
    void publishDataAccess_serialiseFailureFallsBackToEmptyDetails() {
        var self = new java.util.HashMap<String, Object>();
        self.put("cycle", self);

        when(kafkaSender.send(any(Mono.class))).thenReturn(Flux.empty());

        StepVerifier.create(publisher.publishDataAccess(
                        "tenant", "actor", "actor@x", "KEY", self))
                .verifyComplete();

        verify(kafkaSender).send(captor.capture());
        StepVerifier.create(captor.getValue()).assertNext(r ->
                assertThat(r.value()).contains("DATA_ACCESS")).verifyComplete();
    }
}
