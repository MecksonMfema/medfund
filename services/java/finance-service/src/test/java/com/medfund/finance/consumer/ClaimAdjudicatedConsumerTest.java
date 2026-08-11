package com.medfund.finance.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medfund.finance.client.FxConverter;
import com.medfund.finance.client.TenantConfigClient;
import com.medfund.finance.client.TenantConfigClient.CtcAutoConfig;
import com.medfund.finance.entity.CtcPayment;
import com.medfund.finance.entity.MemberPayable;
import com.medfund.finance.entity.ProviderBalance;
import com.medfund.finance.repository.CtcPaymentRepository;
import com.medfund.finance.entity.MemberBalance;
import com.medfund.finance.entity.MemberCostShareLiability;
import com.medfund.finance.entity.MemberCostShareSettlement;
import com.medfund.finance.repository.MemberContributionBalanceReader;
import com.medfund.finance.repository.MemberCostShareLiabilityRepository;
import com.medfund.finance.repository.MemberCostShareSettlementRepository;
import com.medfund.finance.repository.MemberPayableRepository;
import com.medfund.finance.service.MemberBalanceService;
import com.medfund.finance.service.ProviderBalanceService;
import com.medfund.shared.audit.AuditPublisher;
import com.medfund.shared.tenant.TenantContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import reactor.util.context.Context;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ClaimAdjudicatedConsumerTest {

    @Mock
    private ProviderBalanceService providerBalanceService;

    @Mock
    private MemberBalanceService memberBalanceService;

    @Mock
    private MemberPayableRepository memberPayableRepository;

    @Mock
    private AuditPublisher auditPublisher;

    @Mock
    private TenantConfigClient tenantConfigClient;

    @Mock
    private MemberContributionBalanceReader memberContributionBalanceReader;

    @Mock
    private CtcPaymentRepository ctcPaymentRepository;

    @Mock
    private FxConverter fxConverter;

    @Mock
    private MemberCostShareLiabilityRepository liabilityRepository;

    @Mock
    private MemberCostShareSettlementRepository settlementRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private ClaimAdjudicatedConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new ClaimAdjudicatedConsumer(null, providerBalanceService,
                memberBalanceService,
                memberPayableRepository, liabilityRepository, settlementRepository,
                auditPublisher, objectMapper,
                tenantConfigClient, memberContributionBalanceReader,
                ctcPaymentRepository, fxConverter);
        // Balance bumps are lenient — many tests don't care but the new
        // consumer path always tries to write member_balances first on any
        // MEMBER event with a claimed or approved delta.
        lenient().when(memberBalanceService.updateBalance(any(), any(), any(), any(), any(), any(), any()))
                 .thenReturn(Mono.just(new MemberBalance()));
        // V078: lenient stubs so the new liability write branch doesn't NPE
        // on tests that were written before Phase 4. Individual liability tests
        // can .when(...).thenReturn(...) over these.
        lenient().when(liabilityRepository.save(any()))
                 .thenAnswer(inv -> {
                     MemberCostShareLiability l = inv.getArgument(0);
                     if (l.getId() == null) l.setId(UUID.randomUUID());
                     return Mono.just(l);
                 });
        lenient().when(settlementRepository.save(any()))
                 .thenAnswer(inv -> {
                     MemberCostShareSettlement s = inv.getArgument(0);
                     if (s.getId() == null) s.setId(UUID.randomUUID());
                     return Mono.just(s);
                 });
        lenient().when(auditPublisher.publish(any())).thenReturn(Mono.empty());
    }

    private static CtcAutoConfig disabled() {
        return new CtcAutoConfig(false, BigDecimal.ZERO, null, "USD");
    }

    private static CtcAutoConfig enabled(BigDecimal threshold, BigDecimal cap, String currency) {
        return new CtcAutoConfig(true, threshold, cap, currency);
    }

    // ── PROVIDER-payee branch (unchanged pre-V069 behaviour) ────────────

    @Test
    void processEvent_approvedProviderClaim_updatesProviderBalance() {
        String providerId = UUID.randomUUID().toString();
        String json = """
            {"event":"CLAIM_ADJUDICATED","decision":"APPROVED","providerId":"%s","approvedAmount":"1500.00","currencyCode":"USD","payeeType":"PROVIDER"}
            """.formatted(providerId);
        when(providerBalanceService.updateBalance(any(), any(), any(), any(), any(), any(), any()))
            .thenReturn(Mono.just(new ProviderBalance()));

        StepVerifier.create(consumer.processEvent(json))
            .verifyComplete();

        verify(providerBalanceService).updateBalance(
            UUID.fromString(providerId),
            "USD",
            null,
            new BigDecimal("1500.00"),
            null,
            "system",
            "system@medfund"
        );
        verifyNoInteractions(memberPayableRepository);
    }

    @Test
    void processEvent_partialApprovedProviderClaim_updatesProviderBalance() {
        String providerId = UUID.randomUUID().toString();
        String json = """
            {"event":"CLAIM_ADJUDICATED","decision":"PARTIAL_APPROVED","providerId":"%s","approvedAmount":"950.00","currencyCode":"USD"}
            """.formatted(providerId);
        when(providerBalanceService.updateBalance(any(), any(), any(), any(), any(), any(), any()))
            .thenReturn(Mono.just(new ProviderBalance()));

        StepVerifier.create(consumer.processEvent(json))
            .verifyComplete();

        verify(providerBalanceService).updateBalance(
            UUID.fromString(providerId),
            "USD",
            null,
            new BigDecimal("950.00"),
            null,
            "system",
            "system@medfund"
        );
    }

    @Test
    void processEvent_rejectedProviderClaim_callsUpdateWithNullDeltas() {
        String providerId = UUID.randomUUID().toString();
        String json = """
            {"event":"CLAIM_ADJUDICATED","decision":"REJECTED","providerId":"%s","claimedAmount":"500.00","approvedAmount":"0","currencyCode":"USD"}
            """.formatted(providerId);
        when(providerBalanceService.updateBalance(any(), any(), any(), any(), any(), any(), any()))
            .thenReturn(Mono.just(new ProviderBalance()));

        StepVerifier.create(consumer.processEvent(json))
            .verifyComplete();

        verify(providerBalanceService).updateBalance(
            UUID.fromString(providerId),
            "USD",
            new BigDecimal("500.00"),
            null,
            null,
            "system",
            "system@medfund"
        );
    }

    @Test
    void processEvent_paidProviderClaim_updatesPaidDelta() {
        String providerId = UUID.randomUUID().toString();
        String json = """
            {"event":"CLAIM_ADJUDICATED","decision":"PAID","providerId":"%s","approvedAmount":"750.00","currencyCode":"USD"}
            """.formatted(providerId);
        when(providerBalanceService.updateBalance(any(), any(), any(), any(), any(), any(), any()))
            .thenReturn(Mono.just(new ProviderBalance()));

        StepVerifier.create(consumer.processEvent(json))
            .verifyComplete();

        verify(providerBalanceService).updateBalance(
            UUID.fromString(providerId),
            "USD",
            null,
            null,
            new BigDecimal("750.00"),
            "system",
            "system@medfund"
        );
    }

    @Test
    void processEvent_missingProviderContext_skips() {
        String json = """
            {"event":"CLAIM_ADJUDICATED","decision":"APPROVED","approvedAmount":"100","currencyCode":"USD"}
            """;
        StepVerifier.create(consumer.processEvent(json))
            .verifyComplete();

        verifyNoInteractions(providerBalanceService);
        verifyNoInteractions(memberPayableRepository);
    }

    // ── MEMBER-payee branch (V069) ─────────────────────────────────────

    @Test
    void processEvent_approvedMemberClaim_writesMemberPayable() {
        String memberId = UUID.randomUUID().toString();
        String claimId = UUID.randomUUID().toString();
        String json = """
            {"event":"CLAIM_ADJUDICATED","decision":"APPROVED","claimId":"%s","claimNumber":"CLM-123","memberId":"%s","approvedAmount":"150.00","currencyCode":"USD","payeeType":"MEMBER"}
            """.formatted(claimId, memberId);
        when(memberPayableRepository.save(any(MemberPayable.class)))
            .thenAnswer(inv -> {
                MemberPayable saved = inv.getArgument(0);
                saved.setId(UUID.randomUUID());
                return Mono.just(saved);
            });
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());

        StepVerifier.create(consumer.processEvent(json))
            .verifyComplete();

        ArgumentCaptor<MemberPayable> captor = ArgumentCaptor.forClass(MemberPayable.class);
        verify(memberPayableRepository).save(captor.capture());
        MemberPayable saved = captor.getValue();
        assertThat(saved.getMemberId()).isEqualTo(UUID.fromString(memberId));
        assertThat(saved.getClaimId()).isEqualTo(UUID.fromString(claimId));
        assertThat(saved.getClaimNumber()).isEqualTo("CLM-123");
        assertThat(saved.getAmount()).isEqualByComparingTo(new BigDecimal("150.00"));
        assertThat(saved.getCurrencyCode()).isEqualTo("USD");
        assertThat(saved.getStatus()).isEqualTo("open");
        verify(auditPublisher).publish(any());
        verifyNoInteractions(providerBalanceService);
    }

    @Test
    void processEvent_rejectedMemberClaim_isNoop() {
        String memberId = UUID.randomUUID().toString();
        String claimId = UUID.randomUUID().toString();
        String json = """
            {"event":"CLAIM_ADJUDICATED","decision":"REJECTED","claimId":"%s","memberId":"%s","approvedAmount":"0","currencyCode":"USD","payeeType":"MEMBER"}
            """.formatted(claimId, memberId);

        StepVerifier.create(consumer.processEvent(json))
            .verifyComplete();

        verifyNoInteractions(memberPayableRepository);
        verifyNoInteractions(auditPublisher);
        verifyNoInteractions(providerBalanceService);
    }

    @Test
    void processEvent_zeroApprovedMemberClaim_isNoop() {
        String memberId = UUID.randomUUID().toString();
        String claimId = UUID.randomUUID().toString();
        String json = """
            {"event":"CLAIM_ADJUDICATED","decision":"APPROVED","claimId":"%s","memberId":"%s","approvedAmount":"0.00","currencyCode":"USD","payeeType":"MEMBER"}
            """.formatted(claimId, memberId);

        StepVerifier.create(consumer.processEvent(json))
            .verifyComplete();

        verifyNoInteractions(memberPayableRepository);
    }

    @Test
    void processEvent_duplicateMemberPayable_idempotentSkip() {
        String memberId = UUID.randomUUID().toString();
        String claimId = UUID.randomUUID().toString();
        String json = """
            {"event":"CLAIM_ADJUDICATED","decision":"APPROVED","claimId":"%s","memberId":"%s","approvedAmount":"150.00","currencyCode":"USD","payeeType":"MEMBER"}
            """.formatted(claimId, memberId);
        // Simulate the unique-violation Spring wraps into DuplicateKeyException.
        when(memberPayableRepository.save(any(MemberPayable.class)))
            .thenReturn(Mono.error(new DuplicateKeyException(
                "duplicate key value violates unique constraint \"member_payables_claim_unique\"")));

        StepVerifier.create(consumer.processEvent(json))
            .verifyComplete();

        verify(memberPayableRepository).save(any(MemberPayable.class));
        verifyNoInteractions(auditPublisher);
    }

    // ── Auto-CTC branch (Phase 4) ──────────────────────────────────────

    @Test
    void processEvent_memberPayee_autoCtcDisabled_noDraft() {
        UUID tenantId = UUID.randomUUID();
        String json = memberEventJson(UUID.randomUUID(), UUID.randomUUID(),
                new BigDecimal("150.00"), "USD");
        stubMemberPayableSave();
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());
        when(tenantConfigClient.getCtcAutoConfig(tenantId)).thenReturn(Mono.just(disabled()));

        StepVerifier.create(consumer.processEvent(json)
                        .contextWrite(Context.of(TenantContext.KEY, tenantId.toString())))
                .verifyComplete();

        verify(memberPayableRepository).save(any(MemberPayable.class));
        verifyNoInteractions(ctcPaymentRepository);
    }

    @Test
    void processEvent_memberPayee_belowThreshold_noDraft() {
        UUID tenantId = UUID.randomUUID();
        UUID memberId = UUID.randomUUID();
        String json = memberEventJson(memberId, UUID.randomUUID(),
                new BigDecimal("150.00"), "USD");
        stubMemberPayableSave();
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());
        when(tenantConfigClient.getCtcAutoConfig(tenantId))
                .thenReturn(Mono.just(enabled(new BigDecimal("500"), null, "USD")));
        when(memberContributionBalanceReader.getBalance(memberId, "USD"))
                .thenReturn(Mono.just(new BigDecimal("100.00")));

        StepVerifier.create(consumer.processEvent(json)
                        .contextWrite(Context.of(TenantContext.KEY, tenantId.toString())))
                .verifyComplete();

        verifyNoInteractions(ctcPaymentRepository);
    }

    @Test
    void processEvent_memberPayee_aboveThreshold_createsDraft() {
        UUID tenantId = UUID.randomUUID();
        UUID memberId = UUID.randomUUID();
        BigDecimal payableAmount = new BigDecimal("150.00");
        String json = memberEventJson(memberId, UUID.randomUUID(), payableAmount, "USD");
        stubMemberPayableSave();
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());
        when(tenantConfigClient.getCtcAutoConfig(tenantId))
                .thenReturn(Mono.just(enabled(new BigDecimal("100"), null, "USD")));
        when(memberContributionBalanceReader.getBalance(memberId, "USD"))
                .thenReturn(Mono.just(new BigDecimal("500.00")));
        when(ctcPaymentRepository.save(any(CtcPayment.class)))
                .thenAnswer(inv -> {
                    CtcPayment saved = inv.getArgument(0);
                    saved.setId(UUID.randomUUID());
                    return Mono.just(saved);
                });

        StepVerifier.create(consumer.processEvent(json)
                        .contextWrite(Context.of(TenantContext.KEY, tenantId.toString())))
                .verifyComplete();

        ArgumentCaptor<CtcPayment> captor = ArgumentCaptor.forClass(CtcPayment.class);
        verify(ctcPaymentRepository).save(captor.capture());
        CtcPayment draft = captor.getValue();
        assertThat(draft.getMemberId()).isEqualTo(memberId);
        assertThat(draft.getAmount()).isEqualByComparingTo(payableAmount);
        assertThat(draft.getCurrencyCode()).isEqualTo("USD");
        assertThat(draft.getType()).isEqualTo("CTC");
        assertThat(draft.getStatus()).isEqualTo("draft");
        assertThat(draft.getCommitted()).isFalse();
        assertThat(draft.getCreatedBy()).isNull(); // system-initiated marker
    }

    @Test
    void processEvent_memberPayee_capBelowPayable_draftAtCap() {
        UUID tenantId = UUID.randomUUID();
        UUID memberId = UUID.randomUUID();
        String json = memberEventJson(memberId, UUID.randomUUID(),
                new BigDecimal("200.00"), "USD");
        stubMemberPayableSave();
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());
        when(tenantConfigClient.getCtcAutoConfig(tenantId))
                .thenReturn(Mono.just(enabled(new BigDecimal("0"), new BigDecimal("50"), "USD")));
        when(memberContributionBalanceReader.getBalance(memberId, "USD"))
                .thenReturn(Mono.just(new BigDecimal("500.00")));
        when(ctcPaymentRepository.save(any(CtcPayment.class)))
                .thenAnswer(inv -> {
                    CtcPayment saved = inv.getArgument(0);
                    saved.setId(UUID.randomUUID());
                    return Mono.just(saved);
                });

        StepVerifier.create(consumer.processEvent(json)
                        .contextWrite(Context.of(TenantContext.KEY, tenantId.toString())))
                .verifyComplete();

        ArgumentCaptor<CtcPayment> captor = ArgumentCaptor.forClass(CtcPayment.class);
        verify(ctcPaymentRepository).save(captor.capture());
        assertThat(captor.getValue().getAmount()).isEqualByComparingTo(new BigDecimal("50"));
    }

    @Test
    void processEvent_memberPayee_thresholdInDifferentCurrency_convertsBeforeCompare() {
        UUID tenantId = UUID.randomUUID();
        UUID memberId = UUID.randomUUID();
        // Member's payable is in ZAR; threshold configured in USD. Balance
        // in ZAR is 5000; threshold is 100 USD; FX rate ZAR->USD is 0.05
        // ⇒ balance in USD = 250, comfortably above threshold.
        String json = memberEventJson(memberId, UUID.randomUUID(),
                new BigDecimal("1000.00"), "ZAR");
        stubMemberPayableSave();
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());
        when(tenantConfigClient.getCtcAutoConfig(tenantId))
                .thenReturn(Mono.just(enabled(new BigDecimal("100"), null, "USD")));
        when(memberContributionBalanceReader.getBalance(memberId, "ZAR"))
                .thenReturn(Mono.just(new BigDecimal("5000.00")));
        when(fxConverter.convert(eq(new BigDecimal("5000.00")), eq("ZAR"), eq("USD"), any(), eq(tenantId)))
                .thenReturn(Mono.just(new BigDecimal("250.00")));
        when(ctcPaymentRepository.save(any(CtcPayment.class)))
                .thenAnswer(inv -> {
                    CtcPayment saved = inv.getArgument(0);
                    saved.setId(UUID.randomUUID());
                    return Mono.just(saved);
                });

        StepVerifier.create(consumer.processEvent(json)
                        .contextWrite(Context.of(TenantContext.KEY, tenantId.toString())))
                .verifyComplete();

        verify(ctcPaymentRepository).save(any(CtcPayment.class));
    }

    private void stubMemberPayableSave() {
        when(memberPayableRepository.save(any(MemberPayable.class)))
                .thenAnswer(inv -> {
                    MemberPayable saved = inv.getArgument(0);
                    saved.setId(UUID.randomUUID());
                    return Mono.just(saved);
                });
    }

    private String memberEventJson(UUID memberId, UUID claimId, BigDecimal amount, String currency) {
        return """
            {"event":"CLAIM_ADJUDICATED","decision":"APPROVED","claimId":"%s","memberId":"%s","approvedAmount":"%s","currencyCode":"%s","payeeType":"MEMBER"}
            """.formatted(claimId, memberId, amount.toPlainString(), currency);
    }

    // ── V078 member cost-share liability writes (Phase 4 copayments) ──────

    @Test
    void processEvent_providerPayeeWithMemberResponsibility_writesLiabilityOpen() {
        UUID memberId = UUID.randomUUID();
        UUID claimId  = UUID.randomUUID();
        UUID providerId = UUID.randomUUID();
        String json = """
            {"event":"CLAIM_ADJUDICATED","decision":"APPROVED","claimId":"%s","memberId":"%s",
             "providerId":"%s","approvedAmount":"375.00","currencyCode":"USD","payeeType":"PROVIDER",
             "allowedAmount":"500","deductibleApplied":"100","copayAmount":"25",
             "coinsuranceAmount":"0","notCoveredAmount":"0","shortfallAmount":"0",
             "memberResponsibility":"125"}
            """.formatted(claimId, memberId, providerId);
        when(providerBalanceService.updateBalance(any(), any(), any(), any(), any(), any(), any()))
            .thenReturn(Mono.just(new ProviderBalance()));

        StepVerifier.create(consumer.processEvent(json))
            .verifyComplete();

        ArgumentCaptor<MemberCostShareLiability> captor = ArgumentCaptor.forClass(MemberCostShareLiability.class);
        verify(liabilityRepository).save(captor.capture());
        MemberCostShareLiability l = captor.getValue();
        assertThat(l.getMemberId()).isEqualTo(memberId);
        assertThat(l.getClaimId()).isEqualTo(claimId);
        assertThat(l.getTotalOwed()).isEqualByComparingTo("125");
        assertThat(l.getDeductible()).isEqualByComparingTo("100");
        assertThat(l.getCopay()).isEqualByComparingTo("25");
        assertThat(l.getCurrencyCode()).isEqualTo("USD");
        assertThat(l.getStatus()).isEqualTo("OPEN");
        assertThat(l.getTotalSettled()).isEqualByComparingTo("0");
        // Provider path: no synthetic settlement row.
        verify(settlementRepository, never()).save(any());
    }

    @Test
    void processEvent_memberPayee_writesLiabilitySettledPlusSyntheticSettlement() {
        UUID memberId = UUID.randomUUID();
        UUID claimId  = UUID.randomUUID();
        String json = """
            {"event":"CLAIM_ADJUDICATED","decision":"APPROVED","claimId":"%s","memberId":"%s",
             "approvedAmount":"375.00","currencyCode":"USD","payeeType":"MEMBER",
             "allowedAmount":"500","deductibleApplied":"100","copayAmount":"25",
             "coinsuranceAmount":"0","notCoveredAmount":"0","shortfallAmount":"0",
             "memberResponsibility":"125"}
            """.formatted(claimId, memberId);
        stubMemberPayableSave();
        // Auto-CTC branch never runs without tenantId in the payload —
        // handleMemberPayee wraps its own contextWrite only when tenantId is
        // present, so maybeAutoDraftCtc sees null context and short-circuits.

        StepVerifier.create(consumer.processEvent(json))
            .verifyComplete();

        ArgumentCaptor<MemberCostShareLiability> lCaptor = ArgumentCaptor.forClass(MemberCostShareLiability.class);
        verify(liabilityRepository).save(lCaptor.capture());
        MemberCostShareLiability l = lCaptor.getValue();
        assertThat(l.getStatus()).isEqualTo("SETTLED");
        assertThat(l.getTotalSettled()).isEqualByComparingTo("125");

        ArgumentCaptor<MemberCostShareSettlement> sCaptor = ArgumentCaptor.forClass(MemberCostShareSettlement.class);
        verify(settlementRepository).save(sCaptor.capture());
        MemberCostShareSettlement s = sCaptor.getValue();
        assertThat(s.getSource()).isEqualTo("MEMBER_PAID_PROVIDER");
        assertThat(s.getAmount()).isEqualByComparingTo("125");
        assertThat(s.getReceiptTransactionId()).isNull();
    }

    @Test
    void processEvent_rejectedClaim_skipsLiabilityWrite() {
        UUID memberId = UUID.randomUUID();
        UUID claimId  = UUID.randomUUID();
        UUID providerId = UUID.randomUUID();
        String json = """
            {"event":"CLAIM_ADJUDICATED","decision":"REJECTED","claimId":"%s","memberId":"%s",
             "providerId":"%s","approvedAmount":"0","currencyCode":"USD","payeeType":"PROVIDER",
             "memberResponsibility":"125"}
            """.formatted(claimId, memberId, providerId);
        when(providerBalanceService.updateBalance(any(), any(), any(), any(), any(), any(), any()))
            .thenReturn(Mono.just(new ProviderBalance()));

        StepVerifier.create(consumer.processEvent(json))
            .verifyComplete();

        // Rejected claims never write a liability, even if the (nonsensical)
        // memberResponsibility field slipped through — a REJECTED claim has
        // no plan-paid portion for the member to owe against.
        verify(liabilityRepository, never()).save(any());
    }

    @Test
    void processEvent_missingMemberResponsibility_skipsLiabilityWrite() {
        UUID providerId = UUID.randomUUID();
        String json = """
            {"event":"CLAIM_ADJUDICATED","decision":"APPROVED","providerId":"%s",
             "approvedAmount":"1500.00","currencyCode":"USD","payeeType":"PROVIDER"}
            """.formatted(providerId);
        when(providerBalanceService.updateBalance(any(), any(), any(), any(), any(), any(), any()))
            .thenReturn(Mono.just(new ProviderBalance()));

        StepVerifier.create(consumer.processEvent(json))
            .verifyComplete();

        // Pre-V077 payload shape → no liability write, backwards compat preserved.
        verify(liabilityRepository, never()).save(any());
    }
}
