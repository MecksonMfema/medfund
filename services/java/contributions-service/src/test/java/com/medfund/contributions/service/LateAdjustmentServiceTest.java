package com.medfund.contributions.service;

import com.medfund.contributions.dto.RecordTransactionRequest;
import com.medfund.contributions.entity.Transaction;
import com.medfund.contributions.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Locks in the Kafka-redelivery idempotency guarantee.
 *
 * <p>The three auto-adjustment consumers ({@code MemberEnrolled},
 * {@code MemberLifecycle}, {@code SchemeChanged}) all fire into
 * {@link LateAdjustmentService}. If a rebalance replays their events,
 * the ONLY thing preventing a double-charge of the ledger is the
 * {@code findFirstByReferenceAndTransactionType} short-circuit
 * asserted below. Do not remove.
 */
@ExtendWith(MockitoExtension.class)
class LateAdjustmentServiceTest {

    @Mock BillingService billingService;
    @Mock TransactionRepository transactionRepository;
    @Mock TransactionService transactionService;

    private LateAdjustmentService service;

    @BeforeEach
    void setUp() {
        service = new LateAdjustmentService(billingService, transactionRepository, transactionService);
    }

    @Test
    void postAggregate_firstDelivery_pricesAndRecordsTransaction() {
        // Fresh event → no existing transaction with this reference →
        // priceOneMember runs, aggregate computed, record fires.
        UUID memberId = UUID.randomUUID();
        UUID groupId = UUID.randomUUID();
        UUID schemeId = UUID.randomUUID();
        LocalDate period = LocalDate.of(2026, 7, 1);
        when(transactionRepository.findFirstByReferenceAndTransactionType(
                anyString(), eq("LATE_TERMINATION_CREDIT")))
                .thenReturn(Mono.empty());
        when(billingService.priceOneMember(eq(memberId), eq(schemeId), eq(period),
                eq(LocalDate.of(2026, 7, 31))))
                .thenReturn(Mono.just(new BigDecimal("60.00")));
        when(transactionService.record(any(RecordTransactionRequest.class), any(), any()))
                .thenReturn(Mono.just(new Transaction()));

        StepVerifier.create(service.postAggregate(memberId, groupId, schemeId, period,
                        2, "USD", "LATE_TERMINATION_CREDIT", memberId.toString()))
                .verifyComplete();

        ArgumentCaptor<RecordTransactionRequest> cap =
                ArgumentCaptor.forClass(RecordTransactionRequest.class);
        verify(transactionService).record(cap.capture(), any(), any());
        // 60 × 2 months = 120. Reference must carry the source key +
        // months + period so a redelivery of the same event collapses.
        assertThat(cap.getValue().amount()).isEqualByComparingTo("120.00");
        assertThat(cap.getValue().transactionType()).isEqualTo("LATE_TERMINATION_CREDIT");
        assertThat(cap.getValue().reference()).contains(memberId.toString());
    }

    @Test
    void postAggregate_redelivery_skipsRecord() {
        // Kafka replay: the same event has already produced a
        // transaction row (findFirstByReferenceAndTransactionType returns
        // one) → postAggregate must NOT re-price, must NOT re-record.
        // This is the entire idempotency guarantee for lifecycle-driven
        // adjustments; a regression here doubles the ledger on every
        // rebalance.
        UUID memberId = UUID.randomUUID();
        UUID schemeId = UUID.randomUUID();
        LocalDate period = LocalDate.of(2026, 7, 1);
        Transaction alreadyPosted = new Transaction();
        alreadyPosted.setTransactionNumber("TXN-000123");
        when(transactionRepository.findFirstByReferenceAndTransactionType(
                anyString(), eq("LATE_TERMINATION_CREDIT")))
                .thenReturn(Mono.just(alreadyPosted));

        StepVerifier.create(service.postAggregate(memberId, null, schemeId, period,
                        1, "USD", "LATE_TERMINATION_CREDIT", memberId.toString()))
                .verifyComplete();

        verify(billingService, never()).priceOneMember(any(), any(), any(), any());
        verify(transactionService, never()).record(any(), any(), any());
    }

    @Test
    void postAggregate_zeroMonths_isNoOpBeforeIdempotencyCheck() {
        // Guard: 0 months → skip WITHOUT hitting the DB. Repro of the
        // pre-hardening bug where a "0 months" call would still query
        // for a prior transaction — cheap and correct, but load a lot
        // if a scheduled sweep called us with 0 in bulk.
        UUID memberId = UUID.randomUUID();
        UUID schemeId = UUID.randomUUID();

        StepVerifier.create(service.postAggregate(memberId, null, schemeId,
                        LocalDate.of(2026, 7, 1), 0, "USD",
                        "LATE_TERMINATION_CREDIT", "src-key"))
                .verifyComplete();

        verify(transactionRepository, never())
                .findFirstByReferenceAndTransactionType(any(), any());
    }

    @Test
    void postAggregate_missingRequiredField_isNoOp() {
        // Null memberId / schemeId / type / sourceKey → skip. Never
        // fabricate defaults on money-moving paths.
        StepVerifier.create(service.postAggregate(null, null, UUID.randomUUID(),
                        LocalDate.of(2026, 7, 1), 1, "USD",
                        "LATE_TERMINATION_CREDIT", "src-key"))
                .verifyComplete();

        verify(transactionRepository, never())
                .findFirstByReferenceAndTransactionType(any(), any());
    }

    @Test
    void postFixedAggregate_redelivery_skipsRecord() {
        // Same idempotency contract on the fixed-aggregate variant used
        // by SchemeChangedConsumer. Sign of the amount is the caller's
        // responsibility; the guard here is purely reference-based.
        UUID memberId = UUID.randomUUID();
        UUID schemeId = UUID.randomUUID();
        Transaction alreadyPosted = new Transaction();
        alreadyPosted.setTransactionNumber("TXN-000456");
        when(transactionRepository.findFirstByReferenceAndTransactionType(
                anyString(), eq("SCHEME_UPGRADE_ARREARS")))
                .thenReturn(Mono.just(alreadyPosted));

        StepVerifier.create(service.postFixedAggregate(memberId, null, schemeId,
                        LocalDate.of(2026, 7, 1), 1, new BigDecimal("30.00"),
                        "USD", "SCHEME_UPGRADE_ARREARS", "scheme-change-1"))
                .verifyComplete();

        verify(transactionService, never()).record(any(), any(), any());
    }

    @Test
    void postFixedAggregate_negativeOrZeroAmount_isNoOp() {
        // The consumer computes delta and passes absolute value; a
        // negative amount here means the consumer got the sign wrong.
        // Skip rather than record a negative-amount row.
        StepVerifier.create(service.postFixedAggregate(UUID.randomUUID(), null, UUID.randomUUID(),
                        LocalDate.of(2026, 7, 1), 1, new BigDecimal("-1.00"),
                        "USD", "SCHEME_UPGRADE_ARREARS", "src"))
                .verifyComplete();

        verify(transactionRepository, never())
                .findFirstByReferenceAndTransactionType(any(), any());
    }
}
