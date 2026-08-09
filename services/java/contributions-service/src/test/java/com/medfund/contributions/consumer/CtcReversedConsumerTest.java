package com.medfund.contributions.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medfund.contributions.dto.RecordTransactionRequest;
import com.medfund.contributions.entity.Transaction;
import com.medfund.contributions.service.TransactionService;
import com.medfund.shared.audit.AuditActor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.kafka.receiver.ReceiverOptions;
import reactor.test.StepVerifier;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CtcReversedConsumerTest {

    @Mock private ReceiverOptions<String, String> receiverOptions;
    @Mock private TransactionService transactionService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private CtcReversedConsumer consumer() {
        return new CtcReversedConsumer(receiverOptions, objectMapper, transactionService);
    }

    @Test
    void processEvent_happyPath_postsCtcOffsetReversal_withReasonInReason() {
        String originalId = UUID.randomUUID().toString();
        String compensatingId = UUID.randomUUID().toString();
        String memberId = UUID.randomUUID().toString();
        String json = """
                {
                  "event": "CTC_REVERSED",
                  "originalCtcId": "%s",
                  "compensatingCtcId": "%s",
                  "memberId": "%s",
                  "memberPayableId": "%s",
                  "amount": "42.00",
                  "currencyCode": "USD",
                  "reason": "operator error"
                }
                """.formatted(originalId, compensatingId, memberId, UUID.randomUUID());

        when(transactionService.recordFromCtcOffset(any(), eq(AuditActor.SYSTEM_ID),
                eq(AuditActor.SYSTEM_EMAIL))).thenReturn(Mono.just(new Transaction()));

        StepVerifier.create(consumer().processEvent(json)).verifyComplete();

        ArgumentCaptor<RecordTransactionRequest> req = ArgumentCaptor.forClass(RecordTransactionRequest.class);
        verify(transactionService).recordFromCtcOffset(req.capture(),
                eq(AuditActor.SYSTEM_ID), eq(AuditActor.SYSTEM_EMAIL));
        assertThat(req.getValue().transactionType()).isEqualTo("CTC_OFFSET_REVERSAL");
        assertThat(req.getValue().memberId()).isEqualTo(UUID.fromString(memberId));
        assertThat(req.getValue().amount()).isEqualByComparingTo("42.00");
        assertThat(req.getValue().reference()).contains(compensatingId).contains(originalId);
        assertThat(req.getValue().reason()).contains("operator error");
    }

    @Test
    void processEvent_missingOriginalId_noOp() {
        String json = """
                {
                  "event": "CTC_REVERSED",
                  "memberId": "%s",
                  "amount": "50.00",
                  "currencyCode": "USD"
                }
                """.formatted(UUID.randomUUID());

        StepVerifier.create(consumer().processEvent(json)).verifyComplete();
        verify(transactionService, never()).recordFromCtcOffset(any(), any(), any());
    }

    @Test
    void processEvent_zeroAmount_noOp() {
        String json = """
                {
                  "event": "CTC_REVERSED",
                  "originalCtcId": "%s",
                  "memberId": "%s",
                  "amount": "0",
                  "currencyCode": "USD"
                }
                """.formatted(UUID.randomUUID(), UUID.randomUUID());

        StepVerifier.create(consumer().processEvent(json)).verifyComplete();
        verify(transactionService, never()).recordFromCtcOffset(any(), any(), any());
    }

    @Test
    void processEvent_malformedJson_errorsSoOffsetIsNotAcked() {
        StepVerifier.create(consumer().processEvent("{ not json"))
                .expectError()
                .verify();
    }
}
