package com.medfund.contributions.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medfund.contributions.dto.RecordTransactionRequest;
import com.medfund.contributions.entity.Transaction;
import com.medfund.contributions.service.TransactionService;
import com.medfund.shared.audit.AuditActor;
import com.medfund.shared.tenant.TenantContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.kafka.receiver.ReceiverOptions;
import reactor.test.StepVerifier;
import reactor.util.context.Context;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CtcCommittedConsumerTest {

    @Mock private ReceiverOptions<String, String> receiverOptions;
    @Mock private TransactionService transactionService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private CtcCommittedConsumer consumer() {
        return new CtcCommittedConsumer(receiverOptions, objectMapper, transactionService);
    }

    @Test
    void processEvent_happyPath_postsCtcOffsetWithSystemActor() {
        String memberId = UUID.randomUUID().toString();
        String ctcId    = UUID.randomUUID().toString();
        String payable  = UUID.randomUUID().toString();
        String tenantId = UUID.randomUUID().toString();
        String json = """
                {
                  "event": "CTC_COMMITTED",
                  "ctcId": "%s",
                  "tenantId": "%s",
                  "memberId": "%s",
                  "memberPayableId": "%s",
                  "amount": "150.00",
                  "currencyCode": "USD",
                  "committedBy": "%s"
                }
                """.formatted(ctcId, tenantId, memberId, payable, UUID.randomUUID());

        when(transactionService.recordFromCtcOffset(any(), eq(AuditActor.SYSTEM_ID),
                eq(AuditActor.SYSTEM_EMAIL))).thenReturn(Mono.just(new Transaction()));

        StepVerifier.create(consumer().processEvent(json))
                .verifyComplete();

        ArgumentCaptor<RecordTransactionRequest> req = ArgumentCaptor.forClass(RecordTransactionRequest.class);
        verify(transactionService).recordFromCtcOffset(req.capture(),
                eq(AuditActor.SYSTEM_ID), eq(AuditActor.SYSTEM_EMAIL));
        assertThat(req.getValue().transactionType()).isEqualTo("CTC_OFFSET");
        assertThat(req.getValue().memberId()).isEqualTo(UUID.fromString(memberId));
        assertThat(req.getValue().amount()).isEqualByComparingTo("150.00");
        assertThat(req.getValue().currencyCode()).isEqualTo("USD");
        assertThat(req.getValue().reference()).isEqualTo("CTC:" + ctcId);
        assertThat(req.getValue().paymentMethod()).isEqualTo("CTC");
    }

    @Test
    void processEvent_withTenantId_propagatesToReactorContext() {
        String memberId = UUID.randomUUID().toString();
        String ctcId    = UUID.randomUUID().toString();
        String tenantId = UUID.randomUUID().toString();
        String json = """
                {
                  "event": "CTC_COMMITTED",
                  "ctcId": "%s",
                  "tenantId": "%s",
                  "memberId": "%s",
                  "amount": "10.00",
                  "currencyCode": "USD"
                }
                """.formatted(ctcId, tenantId, memberId);

        // Capture the tenant seen on the deferred subscription — proves
        // contextWrite(Context.of(TenantContext.KEY, ...)) landed.
        String[] tenantSeen = {null};
        when(transactionService.recordFromCtcOffset(any(), any(), any()))
                .thenReturn(Mono.deferContextual(ctx -> {
                    tenantSeen[0] = TenantContext.get(ctx);
                    return Mono.just(new Transaction());
                }));

        StepVerifier.create(consumer().processEvent(json)).verifyComplete();

        assertThat(tenantSeen[0]).isEqualTo(tenantId);
    }

    @Test
    void processEvent_missingMemberId_noOp() {
        String json = """
                {
                  "event": "CTC_COMMITTED",
                  "ctcId": "%s",
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
                  "event": "CTC_COMMITTED",
                  "ctcId": "%s",
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
