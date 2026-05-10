package com.medfund.finance.service;

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
class FinanceEventPublisherTest {

    @Mock
    private KafkaSender<String, String> kafkaSender;

    @Captor
    private ArgumentCaptor<Mono<SenderRecord<String, String, String>>> senderRecordCaptor;

    private FinanceEventPublisher financeEventPublisher;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        financeEventPublisher = new FinanceEventPublisher(kafkaSender, objectMapper);
    }

    @Test
    @SuppressWarnings("unchecked")
    void publishPaymentCreated_sendsToCorrectTopic() {
        when(kafkaSender.send(any(Mono.class))).thenReturn(Flux.empty());

        StepVerifier.create(financeEventPublisher.publishPaymentCreated("pay-1", "PAY-123", "prov-1"))
                .verifyComplete();

        verify(kafkaSender).send(senderRecordCaptor.capture());

        StepVerifier.create(senderRecordCaptor.getValue())
                .assertNext(record -> {
                    assertThat(record.topic()).isEqualTo("medfund.finance.payment-created");
                    assertThat(record.key()).isEqualTo("pay-1");
                    assertThat(record.value()).contains("PAYMENT_CREATED");
                })
                .verifyComplete();
    }

    @Test
    @SuppressWarnings("unchecked")
    void publishPaymentRunExecuted_sendsToCorrectTopic() {
        when(kafkaSender.send(any(Mono.class))).thenReturn(Flux.empty());

        StepVerifier.create(financeEventPublisher.publishPaymentRunExecuted("run-1", "RUN-123", 25))
                .verifyComplete();

        verify(kafkaSender).send(senderRecordCaptor.capture());

        StepVerifier.create(senderRecordCaptor.getValue())
                .assertNext(record -> {
                    // Topic was renamed to medfund.payments.run.executed for the
                    // claims-plan ↔ finance-plan event-namespace alignment.
                    assertThat(record.topic()).isEqualTo("medfund.payments.run.executed");
                    assertThat(record.key()).isEqualTo("run-1");
                    assertThat(record.value()).contains("PAYMENT_RUN_EXECUTED");
                })
                .verifyComplete();
    }

    @Test
    @SuppressWarnings("unchecked")
    void publishAdjustmentApplied_sendsToCorrectTopic() {
        when(kafkaSender.send(any(Mono.class))).thenReturn(Flux.empty());

        StepVerifier.create(financeEventPublisher.publishAdjustmentApplied("adj-1", "ADJ-123", "prov-1"))
                .verifyComplete();

        verify(kafkaSender).send(senderRecordCaptor.capture());

        StepVerifier.create(senderRecordCaptor.getValue())
                .assertNext(record -> {
                    assertThat(record.topic()).isEqualTo("medfund.finance.adjustment-applied");
                    assertThat(record.key()).isEqualTo("adj-1");
                    assertThat(record.value()).contains("ADJUSTMENT_APPLIED");
                })
                .verifyComplete();
    }
}
