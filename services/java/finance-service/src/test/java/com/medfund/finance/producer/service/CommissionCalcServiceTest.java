package com.medfund.finance.producer.service;

import com.medfund.finance.producer.dto.ContributionPaidEvent;
import com.medfund.finance.producer.entity.CommissionRateCard;
import com.medfund.finance.producer.entity.CommissionTransaction;
import com.medfund.finance.producer.entity.MemberProducerAssignment;
import com.medfund.finance.producer.entity.Producer;
import com.medfund.finance.producer.repository.CommissionRateCardRepository;
import com.medfund.finance.producer.repository.CommissionTransactionRepository;
import com.medfund.finance.producer.repository.MemberProducerAssignmentRepository;
import com.medfund.finance.producer.repository.ProducerRepository;
import com.medfund.finance.producer.util.ReferenceGenerator;
import com.medfund.rules.fact.ContributionFact;
import com.medfund.rules.fact.RuleResult;
import com.medfund.rules.service.RuleEvaluationService;
import com.medfund.rules.service.TenantRuleLoader;
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
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
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

/**
 * Pure-Mockito unit tests for {@link CommissionCalcService}. Mirrors the
 * shape of {@code PremiumCessionServiceTest} — happy path + every guard
 * short-circuit + the idempotency swallow.
 */
@ExtendWith(MockitoExtension.class)
class CommissionCalcServiceTest {

    private static final String TENANT_ID = "11111111-1111-4111-8111-111111111111";
    private static final String SYSTEM_ID = "system";
    private static final String SYSTEM_EMAIL = "system@medfund";
    private static final UUID PRODUCER_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID RATE_CARD_ID = UUID.fromString("33333333-3333-4333-8333-333333333333");

    @Mock MemberProducerAssignmentRepository assignmentRepository;
    @Mock CommissionRateCardRepository rateCardRepository;
    @Mock CommissionTransactionRepository commissionTxnRepository;
    @Mock ProducerRepository producerRepository;
    @Mock TenantRuleLoader tenantRuleLoader;
    @Mock RuleEvaluationService ruleEvaluationService;
    @Mock ReferenceGenerator referenceGenerator;
    @Mock AuditPublisher auditPublisher;

    @InjectMocks CommissionCalcService service;

    @Test
    void processPaidContribution_noActiveProducer_warnsAndSkips() {
        ContributionPaidEvent event = paidEvent("500.00");
        when(assignmentRepository.findActiveFor(eq(event.memberId()), any()))
                .thenReturn(Mono.empty());

        StepVerifier.create(service.processPaidContribution(event, SYSTEM_ID, SYSTEM_EMAIL))
                .verifyComplete();

        verify(rateCardRepository, never()).findApplicable(anyString(), anyString(), any());
        verify(commissionTxnRepository, never()).save(any());
    }

    @Test
    void processPaidContribution_noActiveRateCard_surfacesError() {
        ContributionPaidEvent event = paidEvent("500.00");
        when(assignmentRepository.findActiveFor(eq(event.memberId()), any()))
                .thenReturn(Mono.just(assignment()));
        when(producerRepository.findById(PRODUCER_ID)).thenReturn(Mono.just(producer(null)));
        when(rateCardRepository.findApplicable(eq("HEALTH"), eq("DIRECT"), any()))
                .thenReturn(Mono.empty());

        StepVerifier.create(service.processPaidContribution(event, SYSTEM_ID, SYSTEM_EMAIL))
                .expectErrorMatches(err -> err instanceof IllegalStateException
                        && err.getMessage().contains("No active commission rate card"))
                .verify();
    }

    @Test
    void processPaidContribution_rateCardOnly_persistsBaseAmount() {
        ContributionPaidEvent event = paidEvent("500.00");
        CommissionRateCard card = card(new BigDecimal("10.0000"), null);
        wireHappyChain(event, card, List.of());

        StepVerifier.create(service.processPaidContribution(event, SYSTEM_ID, SYSTEM_EMAIL))
                .assertNext(txn -> {
                    // Base = 500 * 10.0000 / 100 = 50.0000
                    assertThat(txn.getNativeAmount()).isEqualByComparingTo("50.0000");
                    assertThat(txn.getAppliedRatePct()).isEqualByComparingTo("10.0000");
                    assertThat(txn.getStatus()).isEqualTo("ACCRUED");
                    assertThat(txn.getReference()).isEqualTo("COMM-2026-000001");
                })
                .verifyComplete();
        verify(commissionTxnRepository, times(1)).save(any());
    }

    @Test
    void processPaidContribution_singleKicker_addsOnTopOfBase() {
        ContributionPaidEvent event = paidEvent("500.00");
        CommissionRateCard card = card(new BigDecimal("10.0000"), 60);
        // 25 bp kicker on 500 = 500 * 25 / 10000 = 1.25
        wireHappyChain(event, card,
                List.of(new RuleResult("PAY_COMMISSION", PRODUCER_ID.toString(),
                        "kicker", new BigDecimal("1.2500"), RATE_CARD_ID.toString())));

        StepVerifier.create(service.processPaidContribution(event, SYSTEM_ID, SYSTEM_EMAIL))
                .assertNext(txn ->
                        // 50 (base) + 1.25 (kicker) = 51.25
                        assertThat(txn.getNativeAmount()).isEqualByComparingTo("51.2500"))
                .verifyComplete();
    }

    @Test
    void processPaidContribution_multipleKickers_sumsAll() {
        ContributionPaidEvent event = paidEvent("500.00");
        CommissionRateCard card = card(new BigDecimal("10.0000"), 60);
        wireHappyChain(event, card,
                List.of(new RuleResult("PAY_COMMISSION", PRODUCER_ID.toString(),
                                "kicker1", new BigDecimal("1.2500"), RATE_CARD_ID.toString()),
                        new RuleResult("PAY_COMMISSION", PRODUCER_ID.toString(),
                                "kicker2", new BigDecimal("2.5000"), RATE_CARD_ID.toString())));

        StepVerifier.create(service.processPaidContribution(event, SYSTEM_ID, SYSTEM_EMAIL))
                .assertNext(txn ->
                        // 50 + 1.25 + 2.5 = 53.75
                        assertThat(txn.getNativeAmount()).isEqualByComparingTo("53.7500"))
                .verifyComplete();
    }

    @Test
    void processPaidContribution_zeroKickerFromRateCardMarker_skippedFromSum() {
        ContributionPaidEvent event = paidEvent("500.00");
        CommissionRateCard card = card(new BigDecimal("10.0000"), 60);
        // Zero-amount marker (rate-card lookup) — should not contribute to the sum.
        wireHappyChain(event, card,
                List.of(new RuleResult("PAY_COMMISSION", PRODUCER_ID.toString(),
                        "rate-card marker", BigDecimal.ZERO, RATE_CARD_ID.toString())));

        StepVerifier.create(service.processPaidContribution(event, SYSTEM_ID, SYSTEM_EMAIL))
                .assertNext(txn -> assertThat(txn.getNativeAmount()).isEqualByComparingTo("50.0000"))
                .verifyComplete();
    }

    @Test
    void processPaidContribution_duplicateKeyOnSave_swallowsAsIdempotent() {
        ContributionPaidEvent event = paidEvent("500.00");
        CommissionRateCard card = card(new BigDecimal("10.0000"), null);
        // Wire chain up to save, then have save error with DuplicateKeyException.
        when(assignmentRepository.findActiveFor(eq(event.memberId()), any()))
                .thenReturn(Mono.just(assignment()));
        when(producerRepository.findById(PRODUCER_ID)).thenReturn(Mono.just(producer(null)));
        when(rateCardRepository.findApplicable(anyString(), anyString(), any())).thenReturn(Mono.just(card));
        when(tenantRuleLoader.ensureLoaded(any())).thenReturn(Mono.empty());
        when(ruleEvaluationService.evaluateInGroup(eq(TENANT_ID), eq("COMMISSION"), any()))
                .thenReturn(Mono.just(List.of()));
        when(referenceGenerator.nextCommissionReference()).thenReturn(Mono.just("COMM-2026-000001"));
        when(commissionTxnRepository.save(any()))
                .thenReturn(Mono.error(new DuplicateKeyException("ux_commission_txn_source")));

        StepVerifier.create(service.processPaidContribution(event, SYSTEM_ID, SYSTEM_EMAIL))
                .verifyComplete();

        verify(auditPublisher, never()).publish(any());
    }

    @Test
    void processPaidContribution_missingTenantId_shortCircuits() {
        ContributionPaidEvent event = new ContributionPaidEvent(
                UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("500.00"),
                "USD", "HEALTH", OffsetDateTime.now(), null);

        StepVerifier.create(service.processPaidContribution(event, SYSTEM_ID, SYSTEM_EMAIL))
                .verifyComplete();

        verify(assignmentRepository, never()).findActiveFor(any(), any());
    }

    @Test
    void processPaidContribution_zeroAmount_shortCircuits() {
        ContributionPaidEvent event = paidEvent("0.00");

        StepVerifier.create(service.processPaidContribution(event, SYSTEM_ID, SYSTEM_EMAIL))
                .verifyComplete();

        verify(assignmentRepository, never()).findActiveFor(any(), any());
    }

    @Test
    void processPaidContribution_missingInsuranceLine_shortCircuits() {
        ContributionPaidEvent event = new ContributionPaidEvent(
                UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("500.00"),
                "USD", null, OffsetDateTime.now(), TENANT_ID);

        StepVerifier.create(service.processPaidContribution(event, SYSTEM_ID, SYSTEM_EMAIL))
                .verifyComplete();

        verify(assignmentRepository, never()).findActiveFor(any(), any());
    }

    @Test
    void processPaidContribution_producerLookupMissing_warnsAndSkips() {
        ContributionPaidEvent event = paidEvent("500.00");
        when(assignmentRepository.findActiveFor(eq(event.memberId()), any()))
                .thenReturn(Mono.just(assignment()));
        when(producerRepository.findById(PRODUCER_ID)).thenReturn(Mono.empty());

        StepVerifier.create(service.processPaidContribution(event, SYSTEM_ID, SYSTEM_EMAIL))
                .verifyComplete();

        verify(rateCardRepository, never()).findApplicable(anyString(), anyString(), any());
    }

    @Test
    void processPaidContribution_subProducer_looksUpSubTier() {
        ContributionPaidEvent event = paidEvent("500.00");
        Producer sub = producer(UUID.randomUUID());
        CommissionRateCard card = card(new BigDecimal("8.0000"), null);
        when(assignmentRepository.findActiveFor(eq(event.memberId()), any()))
                .thenReturn(Mono.just(assignment()));
        when(producerRepository.findById(PRODUCER_ID)).thenReturn(Mono.just(sub));
        when(rateCardRepository.findApplicable(eq("HEALTH"), eq("SUB"), any())).thenReturn(Mono.just(card));
        when(tenantRuleLoader.ensureLoaded(any())).thenReturn(Mono.empty());
        when(ruleEvaluationService.evaluateInGroup(eq(TENANT_ID), eq("COMMISSION"), any()))
                .thenReturn(Mono.just(List.of()));
        when(referenceGenerator.nextCommissionReference()).thenReturn(Mono.just("COMM-2026-000001"));
        when(commissionTxnRepository.save(any())).thenAnswer(inv -> {
            CommissionTransaction c = inv.getArgument(0);
            c.setId(UUID.randomUUID());
            return Mono.just(c);
        });
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());

        StepVerifier.create(service.processPaidContribution(event, SYSTEM_ID, SYSTEM_EMAIL))
                .assertNext(txn -> assertThat(txn.getNativeAmount()).isEqualByComparingTo("40.0000"))
                .verifyComplete();

        verify(rateCardRepository).findApplicable(eq("HEALTH"), eq("SUB"), any());
    }

    @Test
    void processPaidContribution_totalIsZero_skipsPersistWithoutAudit() {
        ContributionPaidEvent event = paidEvent("500.00");
        CommissionRateCard zeroCard = card(BigDecimal.ZERO, null);
        when(assignmentRepository.findActiveFor(eq(event.memberId()), any()))
                .thenReturn(Mono.just(assignment()));
        when(producerRepository.findById(PRODUCER_ID)).thenReturn(Mono.just(producer(null)));
        when(rateCardRepository.findApplicable(anyString(), anyString(), any())).thenReturn(Mono.just(zeroCard));
        when(tenantRuleLoader.ensureLoaded(any())).thenReturn(Mono.empty());
        when(ruleEvaluationService.evaluateInGroup(eq(TENANT_ID), eq("COMMISSION"), any()))
                .thenReturn(Mono.just(List.of()));

        StepVerifier.create(service.processPaidContribution(event, SYSTEM_ID, SYSTEM_EMAIL))
                .verifyComplete();

        verify(commissionTxnRepository, never()).save(any());
        verify(auditPublisher, never()).publish(any());
    }

    @Test
    void processPaidContribution_auditEvent_carriesReferenceAsEntityName() {
        ContributionPaidEvent event = paidEvent("500.00");
        CommissionRateCard card = card(new BigDecimal("10.0000"), null);
        wireHappyChain(event, card, List.of());

        StepVerifier.create(service.processPaidContribution(event, SYSTEM_ID, SYSTEM_EMAIL))
                .expectNextCount(1)
                .verifyComplete();

        ArgumentCaptor<AuditEvent> auditCap = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditPublisher, times(1)).publish(auditCap.capture());
        AuditEvent published = auditCap.getValue();
        assertThat(published.entityType()).isEqualTo("CommissionTransaction");
        // entityName must be the friendly reference (feedback_audit_entity_name).
        assertThat(published.entityName()).isEqualTo("COMM-2026-000001");
        assertThat(published.actorId()).isEqualTo(SYSTEM_ID);
        assertThat(published.actorEmail()).isEqualTo(SYSTEM_EMAIL);
    }

    @Test
    void processPaidContribution_ruleFactCarriesInsuranceLineAttribute_forRuleAuthors() {
        ContributionPaidEvent event = paidEvent("500.00");
        CommissionRateCard card = card(new BigDecimal("10.0000"), null);
        List<Object> capturedFacts = new ArrayList<>();
        when(assignmentRepository.findActiveFor(eq(event.memberId()), any()))
                .thenReturn(Mono.just(assignment()));
        when(producerRepository.findById(PRODUCER_ID)).thenReturn(Mono.just(producer(null)));
        when(rateCardRepository.findApplicable(anyString(), anyString(), any())).thenReturn(Mono.just(card));
        when(tenantRuleLoader.ensureLoaded(any())).thenReturn(Mono.empty());
        when(ruleEvaluationService.evaluateInGroup(eq(TENANT_ID), eq("COMMISSION"), any()))
                .thenAnswer(inv -> {
                    Object[] args = inv.getArguments();
                    for (int i = 2; i < args.length; i++) {
                        capturedFacts.add(args[i]);
                    }
                    return Mono.just(List.of());
                });
        when(referenceGenerator.nextCommissionReference()).thenReturn(Mono.just("COMM-2026-000001"));
        when(commissionTxnRepository.save(any())).thenAnswer(inv -> {
            CommissionTransaction c = inv.getArgument(0);
            c.setId(UUID.randomUUID());
            return Mono.just(c);
        });
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());

        StepVerifier.create(service.processPaidContribution(event, SYSTEM_ID, SYSTEM_EMAIL))
                .expectNextCount(1)
                .verifyComplete();

        assertThat(capturedFacts).hasSize(1);
        ContributionFact fact = (ContributionFact) capturedFacts.get(0);
        assertThat(fact.getAttributes()).containsEntry("insuranceLine", "HEALTH");
        assertThat(fact.getAttributes()).containsEntry("producerTier", "DIRECT");
        assertThat(fact.getAttributes()).containsEntry("producerId", PRODUCER_ID.toString());
        assertThat(fact.getPremiumAmount()).isEqualByComparingTo("500.00");
    }

    // ---- helpers ----

    private void wireHappyChain(ContributionPaidEvent event, CommissionRateCard card,
                                 List<RuleResult> factResults) {
        when(assignmentRepository.findActiveFor(eq(event.memberId()), any()))
                .thenReturn(Mono.just(assignment()));
        when(producerRepository.findById(PRODUCER_ID)).thenReturn(Mono.just(producer(null)));
        when(rateCardRepository.findApplicable(anyString(), anyString(), any())).thenReturn(Mono.just(card));
        when(tenantRuleLoader.ensureLoaded(any())).thenReturn(Mono.empty());
        when(ruleEvaluationService.evaluateInGroup(eq(TENANT_ID), eq("COMMISSION"), any()))
                .thenAnswer(inv -> {
                    // Mockito exposes varargs as separate arguments after the
                    // fixed params — pull them from getArguments() offset 2+.
                    Object[] args = inv.getArguments();
                    for (int i = 2; i < args.length; i++) {
                        if (args[i] instanceof ContributionFact cf) {
                            for (RuleResult r : factResults) {
                                cf.getResults().add(r);
                            }
                        }
                    }
                    return Mono.just(List.of());
                });
        when(referenceGenerator.nextCommissionReference()).thenReturn(Mono.just("COMM-2026-000001"));
        when(commissionTxnRepository.save(any())).thenAnswer(inv -> {
            CommissionTransaction c = inv.getArgument(0);
            c.setId(UUID.randomUUID());
            return Mono.just(c);
        });
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());
    }

    private static ContributionPaidEvent paidEvent(String amount) {
        return new ContributionPaidEvent(
                UUID.randomUUID(), UUID.randomUUID(),
                new BigDecimal(amount), "USD", "HEALTH",
                OffsetDateTime.now(), TENANT_ID);
    }

    private static MemberProducerAssignment assignment() {
        MemberProducerAssignment mpa = new MemberProducerAssignment();
        mpa.setId(UUID.randomUUID());
        mpa.setMemberId(UUID.randomUUID());
        mpa.setProducerId(PRODUCER_ID);
        mpa.setEffectiveFrom(LocalDate.now().withDayOfMonth(1));
        return mpa;
    }

    private static Producer producer(UUID parentId) {
        Producer p = new Producer();
        p.setId(PRODUCER_ID);
        p.setProducerCode("BRK-A");
        p.setName("Broker A");
        p.setHomeCurrency("USD");
        p.setParentProducerId(parentId);
        p.setActive(true);
        return p;
    }

    private static CommissionRateCard card(BigDecimal ratePct, Integer clawbackWindowDays) {
        CommissionRateCard c = new CommissionRateCard();
        c.setId(RATE_CARD_ID);
        c.setName("Health direct");
        c.setInsuranceLine("HEALTH");
        c.setProducerTier(null);
        c.setBaseRatePct(ratePct);
        c.setClawbackWindowDays(clawbackWindowDays);
        c.setEffectiveFrom(LocalDate.now().minusMonths(3));
        c.setActive(true);
        return c;
    }
}
