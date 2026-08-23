package com.medfund.finance.producer.service;

import com.medfund.finance.producer.entity.ClawbackEvent;
import com.medfund.finance.producer.entity.CommissionRateCard;
import com.medfund.finance.producer.entity.CommissionTransaction;
import com.medfund.finance.producer.repository.ClawbackEventRepository;
import com.medfund.finance.producer.repository.CommissionRateCardRepository;
import com.medfund.finance.producer.repository.CommissionTransactionRepository;
import com.medfund.finance.producer.util.ReferenceGenerator;
import com.medfund.shared.audit.AuditEvent;
import com.medfund.shared.audit.AuditPublisher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CommissionClawbackServiceTest {

    private static final UUID PRODUCER_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID RATE_CARD_ID = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID MEMBER_ID = UUID.fromString("44444444-4444-4444-8444-444444444444");
    private static final UUID CONTRIBUTION_ID = UUID.fromString("55555555-5555-4555-8555-555555555555");
    private static final UUID ORIGINAL_TXN_ID = UUID.fromString("66666666-6666-4666-8666-666666666666");
    private static final String SYSTEM_ID = "system";
    private static final String SYSTEM_EMAIL = "system@medfund";

    @Mock CommissionTransactionRepository commissionTxnRepository;
    @Mock ClawbackEventRepository clawbackEventRepository;
    @Mock CommissionRateCardRepository rateCardRepository;
    @Mock ReferenceGenerator referenceGenerator;
    @Mock AuditPublisher auditPublisher;

    @InjectMocks CommissionClawbackService service;

    // ---- MEMBER_LAPSE ----

    @Test
    void processMemberLapse_withinWindow_writesReversalAndClawback() {
        Instant now = Instant.now();
        CommissionTransaction original = accruedTxn(now.minus(30, ChronoUnit.DAYS));
        CommissionRateCard card = card(90);
        wireLapseHappyChain(original, card);

        StepVerifier.create(service.processMemberLapse(MEMBER_ID, now, "lapsed", SYSTEM_ID, SYSTEM_EMAIL))
                .assertNext(cb -> {
                    assertThat(cb.getSource()).isEqualTo("MEMBER_LAPSE");
                    assertThat(cb.getCommissionTransactionId()).isEqualTo(ORIGINAL_TXN_ID);
                    assertThat(cb.getNativeAmount()).isEqualByComparingTo("50.0000");
                })
                .verifyComplete();

        // Two commission saves — one insert for reversed, one update on original.
        verify(commissionTxnRepository, times(2)).save(any());
        verify(clawbackEventRepository, times(1)).save(any());
        verify(auditPublisher, times(2)).publish(any());
    }

    @Test
    void processMemberLapse_outsideWindow_isNoOp() {
        Instant now = Instant.now();
        // Accrued 100 days ago; window 90 → outside.
        CommissionTransaction original = accruedTxn(now.minus(100, ChronoUnit.DAYS));
        CommissionRateCard card = card(90);
        when(commissionTxnRepository.findByMemberIdAndStatusIn(eq(MEMBER_ID), any()))
                .thenReturn(Flux.just(original));
        when(rateCardRepository.findById(RATE_CARD_ID)).thenReturn(Mono.just(card));

        StepVerifier.create(service.processMemberLapse(MEMBER_ID, now, "lapsed", SYSTEM_ID, SYSTEM_EMAIL))
                .verifyComplete();

        verify(commissionTxnRepository, never()).save(any());
        verify(clawbackEventRepository, never()).save(any());
    }

    @Test
    void processMemberLapse_nullClawbackWindow_isNoOp() {
        Instant now = Instant.now();
        CommissionTransaction original = accruedTxn(now.minus(30, ChronoUnit.DAYS));
        CommissionRateCard card = card(null);  // no window configured
        when(commissionTxnRepository.findByMemberIdAndStatusIn(eq(MEMBER_ID), any()))
                .thenReturn(Flux.just(original));
        when(rateCardRepository.findById(RATE_CARD_ID)).thenReturn(Mono.just(card));

        StepVerifier.create(service.processMemberLapse(MEMBER_ID, now, "lapsed", SYSTEM_ID, SYSTEM_EMAIL))
                .verifyComplete();

        verify(commissionTxnRepository, never()).save(any());
    }

    @Test
    void processMemberLapse_missingMemberId_shortCircuits() {
        StepVerifier.create(service.processMemberLapse(null, Instant.now(), "lapsed", SYSTEM_ID, SYSTEM_EMAIL))
                .verifyComplete();

        verify(commissionTxnRepository, never()).findByMemberIdAndStatusIn(any(), any());
    }

    @Test
    void processMemberLapse_duplicateClawback_swallowedAsIdempotent() {
        Instant now = Instant.now();
        CommissionTransaction original = accruedTxn(now.minus(30, ChronoUnit.DAYS));
        CommissionRateCard card = card(90);
        when(commissionTxnRepository.findByMemberIdAndStatusIn(eq(MEMBER_ID), any()))
                .thenReturn(Flux.just(original));
        when(rateCardRepository.findById(RATE_CARD_ID)).thenReturn(Mono.just(card));
        when(referenceGenerator.nextCommissionReference()).thenReturn(Mono.just("COMM-2026-000002"));
        when(commissionTxnRepository.save(any())).thenAnswer(inv -> {
            CommissionTransaction c = inv.getArgument(0);
            if (c.getId() == null) c.setId(UUID.randomUUID());
            return Mono.just(c);
        });
        // Second save (clawback_event) throws duplicate — swallowed.
        when(clawbackEventRepository.save(any()))
                .thenReturn(Mono.error(new DuplicateKeyException("ux_clawback_by_source")));

        StepVerifier.create(service.processMemberLapse(MEMBER_ID, now, "lapsed", SYSTEM_ID, SYSTEM_EMAIL))
                .verifyComplete();

        verify(auditPublisher, never()).publish(any());
    }

    // ---- CONTRIBUTION_REVOKE ----

    @Test
    void processContributionRevoke_originalExists_reversesUnconditionally() {
        Instant now = Instant.now();
        CommissionTransaction original = accruedTxn(now.minus(200, ChronoUnit.DAYS));  // outside any window
        when(commissionTxnRepository.findByContributionIdAndReversalOfTxnIdIsNull(CONTRIBUTION_ID))
                .thenReturn(Mono.just(original));
        when(referenceGenerator.nextCommissionReference()).thenReturn(Mono.just("COMM-2026-000003"));
        when(commissionTxnRepository.save(any())).thenAnswer(inv -> {
            CommissionTransaction c = inv.getArgument(0);
            if (c.getId() == null) c.setId(UUID.randomUUID());
            return Mono.just(c);
        });
        when(clawbackEventRepository.save(any())).thenAnswer(inv -> {
            ClawbackEvent evt = inv.getArgument(0);
            evt.setId(UUID.randomUUID());
            return Mono.just(evt);
        });
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());

        StepVerifier.create(service.processContributionRevoke(CONTRIBUTION_ID, MEMBER_ID, now,
                        "revoked", SYSTEM_ID, SYSTEM_EMAIL))
                .assertNext(cb -> {
                    assertThat(cb.getSource()).isEqualTo("CONTRIBUTION_REVOKE");
                    assertThat(cb.getTriggeringEventRef()).isEqualTo(CONTRIBUTION_ID.toString());
                    // The original terminal status is REVERSED (not CLAWED_BACK).
                    assertThat(original.getStatus()).isEqualTo("REVERSED");
                })
                .verifyComplete();

        // The rate-card lookup is not needed for the revoke path.
        verify(rateCardRepository, never()).findById(any(UUID.class));
        verify(commissionTxnRepository, times(2)).save(any());
    }

    @Test
    void processContributionRevoke_noMatchingTxn_isNoOp() {
        when(commissionTxnRepository.findByContributionIdAndReversalOfTxnIdIsNull(CONTRIBUTION_ID))
                .thenReturn(Mono.empty());

        StepVerifier.create(service.processContributionRevoke(CONTRIBUTION_ID, MEMBER_ID, Instant.now(),
                        "revoked", SYSTEM_ID, SYSTEM_EMAIL))
                .verifyComplete();

        verify(commissionTxnRepository, never()).save(any());
    }

    @Test
    void processContributionRevoke_alreadyReversedTxn_isNoOp() {
        CommissionTransaction original = accruedTxn(Instant.now().minus(30, ChronoUnit.DAYS));
        original.setStatus("REVERSED");
        when(commissionTxnRepository.findByContributionIdAndReversalOfTxnIdIsNull(CONTRIBUTION_ID))
                .thenReturn(Mono.just(original));

        StepVerifier.create(service.processContributionRevoke(CONTRIBUTION_ID, MEMBER_ID, Instant.now(),
                        "revoked", SYSTEM_ID, SYSTEM_EMAIL))
                .verifyComplete();

        verify(commissionTxnRepository, never()).save(any());
    }

    @Test
    void processContributionRevoke_alreadyClawedBack_isNoOp() {
        CommissionTransaction original = accruedTxn(Instant.now().minus(30, ChronoUnit.DAYS));
        original.setStatus("CLAWED_BACK");
        when(commissionTxnRepository.findByContributionIdAndReversalOfTxnIdIsNull(CONTRIBUTION_ID))
                .thenReturn(Mono.just(original));

        StepVerifier.create(service.processContributionRevoke(CONTRIBUTION_ID, MEMBER_ID, Instant.now(),
                        "revoked", SYSTEM_ID, SYSTEM_EMAIL))
                .verifyComplete();

        verify(commissionTxnRepository, never()).save(any());
    }

    @Test
    void processContributionRevoke_missingContributionId_shortCircuits() {
        StepVerifier.create(service.processContributionRevoke(null, MEMBER_ID, Instant.now(),
                        "revoked", SYSTEM_ID, SYSTEM_EMAIL))
                .verifyComplete();

        verify(commissionTxnRepository, never())
                .findByContributionIdAndReversalOfTxnIdIsNull(any());
    }

    @Test
    void clawback_auditsCarryOriginalReferenceAsEntityName() {
        Instant now = Instant.now();
        CommissionTransaction original = accruedTxn(now.minus(200, ChronoUnit.DAYS));
        when(commissionTxnRepository.findByContributionIdAndReversalOfTxnIdIsNull(CONTRIBUTION_ID))
                .thenReturn(Mono.just(original));
        when(referenceGenerator.nextCommissionReference()).thenReturn(Mono.just("COMM-2026-000004"));
        when(commissionTxnRepository.save(any())).thenAnswer(inv -> {
            CommissionTransaction c = inv.getArgument(0);
            if (c.getId() == null) c.setId(UUID.randomUUID());
            return Mono.just(c);
        });
        when(clawbackEventRepository.save(any())).thenAnswer(inv -> {
            ClawbackEvent evt = inv.getArgument(0);
            evt.setId(UUID.randomUUID());
            return Mono.just(evt);
        });
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());

        StepVerifier.create(service.processContributionRevoke(CONTRIBUTION_ID, MEMBER_ID, now,
                        "revoked", SYSTEM_ID, SYSTEM_EMAIL))
                .expectNextCount(1)
                .verifyComplete();

        ArgumentCaptor<AuditEvent> auditCap = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditPublisher, times(2)).publish(auditCap.capture());
        // Both audit events carry the ORIGINAL commission reference as entityName.
        assertThat(auditCap.getAllValues()).allSatisfy(evt ->
                assertThat(evt.entityName()).isEqualTo(original.getReference()));
    }

    // ---- helpers ----

    private void wireLapseHappyChain(CommissionTransaction original, CommissionRateCard card) {
        when(commissionTxnRepository.findByMemberIdAndStatusIn(eq(MEMBER_ID), any()))
                .thenReturn(Flux.just(original));
        when(rateCardRepository.findById(RATE_CARD_ID)).thenReturn(Mono.just(card));
        when(referenceGenerator.nextCommissionReference()).thenReturn(Mono.just("COMM-2026-000002"));
        when(commissionTxnRepository.save(any())).thenAnswer(inv -> {
            CommissionTransaction c = inv.getArgument(0);
            if (c.getId() == null) c.setId(UUID.randomUUID());
            return Mono.just(c);
        });
        when(clawbackEventRepository.save(any())).thenAnswer(inv -> {
            ClawbackEvent evt = inv.getArgument(0);
            evt.setId(UUID.randomUUID());
            return Mono.just(evt);
        });
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());
    }

    private static CommissionTransaction accruedTxn(Instant occurredAt) {
        CommissionTransaction c = new CommissionTransaction();
        c.setId(ORIGINAL_TXN_ID);
        c.setReference("COMM-2026-000001");
        c.setProducerId(PRODUCER_ID);
        c.setContributionId(CONTRIBUTION_ID);
        c.setMemberId(MEMBER_ID);
        c.setInsuranceLine("HEALTH");
        c.setRateCardId(RATE_CARD_ID);
        c.setNativeAmount(new BigDecimal("50.0000"));
        c.setNativeCurrency("USD");
        c.setContributionAmount(new BigDecimal("500.0000"));
        c.setAppliedRatePct(new BigDecimal("10.0000"));
        c.setStatus("ACCRUED");
        c.setOccurredAt(OffsetDateTime.ofInstant(occurredAt, ZoneOffset.UTC));
        return c;
    }

    private static CommissionRateCard card(Integer windowDays) {
        CommissionRateCard c = new CommissionRateCard();
        c.setId(RATE_CARD_ID);
        c.setName("Health direct");
        c.setInsuranceLine("HEALTH");
        c.setBaseRatePct(new BigDecimal("10.0000"));
        c.setClawbackWindowDays(windowDays);
        c.setActive(true);
        return c;
    }
}
