package com.medfund.shared.audit;

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

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuditPublisherTest {

    @Mock
    private KafkaSender<String, String> kafkaSender;

    @Captor
    private ArgumentCaptor<Mono<SenderRecord<String, String, String>>> senderRecordCaptor;

    private AuditPublisher auditPublisher;

    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
            .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @BeforeEach
    void setUp() {
        auditPublisher = new AuditPublisher(kafkaSender, objectMapper);
    }

    @Test
    @SuppressWarnings("unchecked")
    void publish_sendsToAuditTopic() {
        var event = AuditEvent.create(
                "test-tenant", "Member", "mbr-123", "CREATE", "actor-1",
                "actor@test.example", null, Map.of("name", "John"),
                new String[]{"name"}, "corr-1"
        );

        when(kafkaSender.send(any(Mono.class))).thenReturn(Flux.empty());

        StepVerifier.create(auditPublisher.publish(event))
                .verifyComplete();

        verify(kafkaSender).send(senderRecordCaptor.capture());

        StepVerifier.create(senderRecordCaptor.getValue())
                .assertNext(record -> {
                    assertThat(record.topic()).isEqualTo("medfund.audit.events");
                    assertThat(record.key()).isEqualTo("mbr-123");
                })
                .verifyComplete();
    }

    @Test
    @SuppressWarnings("unchecked")
    void publish_includesAllEventFields() {
        var event = AuditEvent.create(
                "test-tenant", "Member", "mbr-123", "CREATE", "actor-1",
                "actor@test.example", null, Map.of("name", "John"),
                new String[]{"name"}, "corr-1"
        );

        when(kafkaSender.send(any(Mono.class))).thenReturn(Flux.empty());

        StepVerifier.create(auditPublisher.publish(event))
                .verifyComplete();

        verify(kafkaSender).send(senderRecordCaptor.capture());

        StepVerifier.create(senderRecordCaptor.getValue())
                .assertNext(record -> {
                    String value = record.value();
                    assertThat(value).contains("Member");
                    assertThat(value).contains("CREATE");
                    assertThat(value).contains("actor-1");
                    assertThat(value).contains("test-tenant");
                    assertThat(value).contains("mbr-123");
                    assertThat(value).contains("corr-1");
                })
                .verifyComplete();
    }
}
