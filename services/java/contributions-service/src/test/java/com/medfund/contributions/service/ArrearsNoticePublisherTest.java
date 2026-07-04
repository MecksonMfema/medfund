package com.medfund.contributions.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medfund.contributions.service.ArrearsNoticePublisher.ArrearsNoticePayload;
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
class ArrearsNoticePublisherTest {

    @Mock
    private KafkaSender<String, String> kafkaSender;

    @Captor
    private ArgumentCaptor<Mono<SenderRecord<String, String, String>>> senderRecordCaptor;

    private ArrearsNoticePublisher publisher;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        publisher = new ArrearsNoticePublisher(kafkaSender, objectMapper);
    }

    @Test
    @SuppressWarnings("unchecked")
    void publish_preSuspensionReminder_emitsCorrectTopicAndEnvelope() {
        when(kafkaSender.send(any(Mono.class))).thenReturn(Flux.empty());
        ArrearsNoticePayload p = new ArrearsNoticePayload(
                "tnt-1",
                ArrearsNoticePublisher.KIND_REMINDER_PRE_SUSPENSION,
                "MEMBER", "mbr-123",
                "USD", new BigDecimal("125.5"),
                23L, 7L, "SUSPENSION");

        StepVerifier.create(publisher.publish(p)).verifyComplete();
        verify(kafkaSender).send(senderRecordCaptor.capture());
        StepVerifier.create(senderRecordCaptor.getValue())
                .assertNext(record -> {
                    assertThat(record.topic()).isEqualTo(ArrearsNoticePublisher.TOPIC);
                    assertThat(record.key()).isEqualTo("mbr-123:REMINDER_PRE_SUSPENSION");
                    assertThat(record.value())
                            .contains("\"event\":\"ARREARS_NOTICE\"")
                            .contains("\"kind\":\"REMINDER_PRE_SUSPENSION\"")
                            .contains("\"tenantId\":\"tnt-1\"")
                            .contains("\"subjectType\":\"MEMBER\"")
                            .contains("\"subjectId\":\"mbr-123\"")
                            .contains("\"currencyCode\":\"USD\"")
                            // Money always 2dp on the wire so downstream email
                            // templates don't have to re-format it.
                            .contains("\"balance\":\"125.50\"")
                            .contains("\"daysOverdue\":\"23\"")
                            .contains("\"daysUntilNextStep\":\"7\"")
                            .contains("\"nextStep\":\"SUSPENSION\"");
                })
                .verifyComplete();
    }

    @Test
    @SuppressWarnings("unchecked")
    void publish_preDeactivationReminder_carriesGroupSubject() {
        when(kafkaSender.send(any(Mono.class))).thenReturn(Flux.empty());
        ArrearsNoticePayload p = new ArrearsNoticePayload(
                "tnt-1",
                ArrearsNoticePublisher.KIND_REMINDER_PRE_DEACTIVATION,
                "GROUP", "grp-9",
                "USD", new BigDecimal("500"),
                60L, 30L, "DEACTIVATION");

        StepVerifier.create(publisher.publish(p)).verifyComplete();
        verify(kafkaSender).send(senderRecordCaptor.capture());
        StepVerifier.create(senderRecordCaptor.getValue())
                .assertNext(record -> {
                    assertThat(record.key()).isEqualTo("grp-9:REMINDER_PRE_DEACTIVATION");
                    assertThat(record.value())
                            .contains("\"subjectType\":\"GROUP\"")
                            .contains("\"nextStep\":\"DEACTIVATION\"");
                })
                .verifyComplete();
    }

    @Test
    @SuppressWarnings("unchecked")
    void publish_nullOptionalFields_serializeAsEmpty() {
        when(kafkaSender.send(any(Mono.class))).thenReturn(Flux.empty());
        ArrearsNoticePayload p = new ArrearsNoticePayload(
                null, // tenantId null → empty string on the wire
                ArrearsNoticePublisher.KIND_REMINDER_PRE_SUSPENSION,
                "MEMBER", "mbr-1",
                "USD", new BigDecimal("10.00"),
                10L, null, null);

        StepVerifier.create(publisher.publish(p)).verifyComplete();
        verify(kafkaSender).send(senderRecordCaptor.capture());
        StepVerifier.create(senderRecordCaptor.getValue())
                .assertNext(record -> {
                    assertThat(record.value())
                            .contains("\"tenantId\":\"\"")
                            .contains("\"daysUntilNextStep\":\"\"")
                            .contains("\"nextStep\":\"\"");
                })
                .verifyComplete();
    }
}
