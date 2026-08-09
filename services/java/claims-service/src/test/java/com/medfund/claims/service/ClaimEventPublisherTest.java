package com.medfund.claims.service;

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ClaimEventPublisherTest {

    @Mock
    private KafkaSender<String, String> kafkaSender;

    @Captor
    private ArgumentCaptor<Mono<SenderRecord<String, String, String>>> senderRecordCaptor;

    private ClaimEventPublisher claimEventPublisher;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        claimEventPublisher = new ClaimEventPublisher(kafkaSender, objectMapper);
    }

    @Test
    @SuppressWarnings("unchecked")
    void publishClaimSubmitted_sendsToCorrectTopic() {
        when(kafkaSender.send(any(Mono.class))).thenReturn(Flux.empty());

        StepVerifier.create(claimEventPublisher.publishClaimSubmitted("clm-1", "CLM-123", "mbr-1", "HEALTH"))
                .verifyComplete();

        verify(kafkaSender).send(senderRecordCaptor.capture());

        StepVerifier.create(senderRecordCaptor.getValue())
                .assertNext(record -> {
                    assertThat(record.topic()).isEqualTo("medfund.claims.submitted");
                    assertThat(record.key()).isEqualTo("clm-1");
                    assertThat(record.value()).contains("CLAIM_SUBMITTED");
                    assertThat(record.value()).contains("CLM-123");
                    // insuranceLine must ride on every claim event so
                    // downstream consumers can route without a scheme lookup.
                    assertThat(record.value()).contains("HEALTH");
                })
                .verifyComplete();
    }

    @Test
    @SuppressWarnings("unchecked")
    void publishClaimCaptured_sendsToCorrectTopic() {
        // Emitted only for the preVerified=true capture path — pins the
        // topic name so downstream "ready-for-adjudication" consumers
        // stay wired.
        when(kafkaSender.send(any(Mono.class))).thenReturn(Flux.empty());

        StepVerifier.create(claimEventPublisher.publishClaimCaptured("clm-2", "CLM-777", "mbr-9", "VEHICLE"))
                .verifyComplete();

        verify(kafkaSender).send(senderRecordCaptor.capture());

        StepVerifier.create(senderRecordCaptor.getValue())
                .assertNext(record -> {
                    assertThat(record.topic()).isEqualTo("medfund.claims.captured");
                    assertThat(record.key()).isEqualTo("clm-2");
                    assertThat(record.value()).contains("CLAIM_CAPTURED");
                    assertThat(record.value()).contains("VEHICLE");
                })
                .verifyComplete();
    }

    @Test
    @SuppressWarnings("unchecked")
    void publishClaimAdjudicated_sendsToCorrectTopic() {
        when(kafkaSender.send(any(Mono.class))).thenReturn(Flux.empty());

        StepVerifier.create(claimEventPublisher.publishClaimAdjudicated("clm-1", "CLM-123", "APPROVED",
                        "prov-1", "500.00", "USD", "HEALTH",
                        "mbr-1", null, "bnf-1", "2026", "MEMBER",
                        "b3c1e7b4-0000-0000-0000-000000000001"))
                .verifyComplete();

        verify(kafkaSender).send(senderRecordCaptor.capture());

        StepVerifier.create(senderRecordCaptor.getValue())
                .assertNext(record -> {
                    assertThat(record.topic()).isEqualTo("medfund.claims.adjudicated");
                    assertThat(record.key()).isEqualTo("clm-1");
                    assertThat(record.value()).contains("CLAIM_ADJUDICATED");
                    assertThat(record.value()).contains("APPROVED");
                    assertThat(record.value()).contains("\"payeeType\":\"MEMBER\"");
                })
                .verifyComplete();
    }

    @Test
    @SuppressWarnings("unchecked")
    void publishClaimStatusChanged_sendsToCorrectTopic() {
        when(kafkaSender.send(any(Mono.class))).thenReturn(Flux.empty());

        StepVerifier.create(claimEventPublisher.publishClaimStatusChanged("clm-1", "PAID", "HEALTH"))
                .verifyComplete();

        verify(kafkaSender).send(senderRecordCaptor.capture());

        StepVerifier.create(senderRecordCaptor.getValue())
                .assertNext(record -> {
                    assertThat(record.topic()).isEqualTo("medfund.claims.lifecycle");
                    assertThat(record.key()).isEqualTo("clm-1");
                    assertThat(record.value()).contains("CLAIM_STATUS_CHANGED");
                })
                .verifyComplete();
    }

    @Test
    @SuppressWarnings("unchecked")
    void publishPreAuthDecision_sendsToCorrectTopic() {
        when(kafkaSender.send(any(Mono.class))).thenReturn(Flux.empty());

        StepVerifier.create(claimEventPublisher.publishPreAuthDecision("pa-1", "PA-123", "APPROVED"))
                .verifyComplete();

        verify(kafkaSender).send(senderRecordCaptor.capture());

        StepVerifier.create(senderRecordCaptor.getValue())
                .assertNext(record -> {
                    assertThat(record.topic()).isEqualTo("medfund.claims.pre-auth-decision");
                    assertThat(record.key()).isEqualTo("pa-1");
                    assertThat(record.value()).contains("PRE_AUTH_DECISION");
                })
                .verifyComplete();
    }
}
