package com.medfund.contributions.service;

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ArrearsThresholdPublisherTest {

    @Mock
    private KafkaSender<String, String> kafkaSender;

    @Captor
    private ArgumentCaptor<Mono<SenderRecord<String, String, String>>> senderRecordCaptor;

    private ArrearsThresholdPublisher publisher;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        publisher = new ArrearsThresholdPublisher(kafkaSender, objectMapper);
    }

    @Test
    @SuppressWarnings("unchecked")
    void publishBreached_emitsCorrectTopicAndPayload() {
        when(kafkaSender.send(any(Mono.class))).thenReturn(Flux.empty());

        StepVerifier.create(publisher.publishBreached(
                        "tnt-1", "MEMBER", "mbr-42", 3,
                        new BigDecimal("125"), "USD"))
                .verifyComplete();

        verify(kafkaSender).send(senderRecordCaptor.capture());
        StepVerifier.create(senderRecordCaptor.getValue())
                .assertNext(record -> {
                    assertThat(record.topic()).isEqualTo(ArrearsThresholdPublisher.TOPIC_BREACHED);
                    assertThat(record.key()).isEqualTo("mbr-42");
                    assertThat(record.value())
                            .contains("\"event\":\"ARREARS_THRESHOLD_BREACHED\"")
                            .contains("\"tenantId\":\"tnt-1\"")
                            .contains("\"subjectType\":\"MEMBER\"")
                            .contains("\"subjectId\":\"mbr-42\"")
                            .contains("\"arrearsMonths\":\"3\"")
                            // Money forced to 2dp on the wire.
                            .contains("\"balance\":\"125.00\"")
                            .contains("\"currencyCode\":\"USD\"")
                            .contains("\"breachedAt\":");
                })
                .verifyComplete();
    }

    @Test
    @SuppressWarnings("unchecked")
    void publishCleared_emitsCorrectTopicAndPayload() {
        when(kafkaSender.send(any(Mono.class))).thenReturn(Flux.empty());

        StepVerifier.create(publisher.publishCleared(
                        "tnt-1", "MEMBER", "mbr-42",
                        BigDecimal.ZERO, "USD"))
                .verifyComplete();

        verify(kafkaSender).send(senderRecordCaptor.capture());
        StepVerifier.create(senderRecordCaptor.getValue())
                .assertNext(record -> {
                    assertThat(record.topic()).isEqualTo(ArrearsThresholdPublisher.TOPIC_CLEARED);
                    assertThat(record.key()).isEqualTo("mbr-42");
                    assertThat(record.value())
                            .contains("\"event\":\"ARREARS_CLEARED\"")
                            .contains("\"tenantId\":\"tnt-1\"")
                            .contains("\"subjectId\":\"mbr-42\"")
                            .contains("\"balance\":\"0.00\"")
                            .contains("\"currencyCode\":\"USD\"")
                            .contains("\"clearedAt\":");
                })
                .verifyComplete();
    }

    @Test
    @SuppressWarnings("unchecked")
    void publishBreached_nullTenantAndCurrency_serialiseAsEmpty() {
        when(kafkaSender.send(any(Mono.class))).thenReturn(Flux.empty());

        StepVerifier.create(publisher.publishBreached(
                        null, "MEMBER", "mbr-1", 1, null, null))
                .verifyComplete();

        verify(kafkaSender).send(senderRecordCaptor.capture());
        StepVerifier.create(senderRecordCaptor.getValue())
                .assertNext(record -> assertThat(record.value())
                        .contains("\"tenantId\":\"\"")
                        .contains("\"currencyCode\":\"\"")
                        // Null balance defaults to 0.00 for wire stability.
                        .contains("\"balance\":\"0.00\""))
                .verifyComplete();
    }
}
