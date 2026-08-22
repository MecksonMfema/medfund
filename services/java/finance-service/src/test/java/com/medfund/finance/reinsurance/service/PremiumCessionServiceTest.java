package com.medfund.finance.reinsurance.service;

import com.medfund.finance.reinsurance.dto.ContributionPaidEvent;
import com.medfund.finance.reinsurance.entity.Cession;
import com.medfund.finance.reinsurance.entity.Treaty;
import com.medfund.finance.reinsurance.repository.CessionRepository;
import com.medfund.finance.reinsurance.repository.TreatyRepository;
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
 * Unit tests for {@link PremiumCessionService} — pure Mockito. Mirrors the
 * shape of {@link CessionServiceTest} but with contribution-paid inputs
 * and PREMIUM cession outputs; also asserts the proportional-only filter
 * (XoL / StopLoss treaties are excluded here — those take a flat
 * inception premium via the {@code ReinsuranceTreatyPremiumExecutor}).
 */
@ExtendWith(MockitoExtension.class)
class PremiumCessionServiceTest {

    private static final String TENANT_ID = "11111111-1111-4111-8111-111111111111";
    private static final String SYSTEM_ID = "system";
    private static final String SYSTEM_EMAIL = "system@medfund";

    @Mock CessionRepository cessionRepository;
    @Mock TreatyRepository treatyRepository;
    @Mock TenantRuleLoader tenantRuleLoader;
    @Mock RuleEvaluationService ruleEvaluationService;
    @Mock AuditPublisher auditPublisher;

    @InjectMocks PremiumCessionService service;

    @Test
    void processPaidContribution_proportionalTreaty_writesPremiumCession_noRecovery() {
        UUID treatyId = UUID.randomUUID();
        Treaty treaty = treaty(treatyId, "QUOTA_SHARE");
        ContributionPaidEvent event = paidEvent("500.00");

        when(treatyRepository.findActiveByInsuranceLine("HEALTH"))
                .thenReturn(Flux.just(treaty));
        when(tenantRuleLoader.ensureLoaded(any())).thenReturn(Mono.empty());
        when(ruleEvaluationService.evaluateInGroup(eq(TENANT_ID), eq("REINSURANCE"), any()))
                .thenReturn(Mono.just(List.of(
                        cedeResult(treatyId, new BigDecimal("150.00")))));
        when(cessionRepository.findByTreatyIdAndSourceEventIdAndCessionType(
                eq(treatyId), eq(event.contributionId()), eq("PREMIUM")))
                .thenReturn(Mono.empty());
        when(cessionRepository.save(any())).thenAnswer(inv -> {
            Cession c = inv.getArgument(0);
            c.setId(UUID.randomUUID());
            return Mono.just(c);
        });
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());

        StepVerifier.create(service.processPaidContribution(event, SYSTEM_ID, SYSTEM_EMAIL))
                .assertNext(cession -> {
                    assertThat(cession.getTreatyId()).isEqualTo(treatyId);
                    assertThat(cession.getCessionType()).isEqualTo("PREMIUM");
                    assertThat(cession.getSource()).isEqualTo("AUTOMATIC");
                    assertThat(cession.getStatus()).isEqualTo("ACTIVE");
                    assertThat(cession.getBasisAmount()).isEqualByComparingTo("500.00");
                    assertThat(cession.getCededAmount()).isEqualByComparingTo("150.00");
                    assertThat(cession.getTreatyLayerId()).isNull();
                    assertThat(cession.getSourceEventType()).isEqualTo("CONTRIBUTION_PAID");
                    assertThat(cession.getSourceEventId()).isEqualTo(event.contributionId());
                })
                .verifyComplete();

        verify(cessionRepository, times(1)).save(any());
        ArgumentCaptor<AuditEvent> auditCap = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditPublisher, times(1)).publish(auditCap.capture());
        assertThat(auditCap.getValue().entityType()).isEqualTo("Cession");
        assertThat(auditCap.getValue().entityName()).startsWith("Premium cession on treaty");
    }

    @Test
    void processPaidContribution_nonProportionalTreatyExcluded_writesNothing() {
        UUID xolId = UUID.randomUUID();
        Treaty xol = treaty(xolId, "EXCESS_OF_LOSS");
        ContributionPaidEvent event = paidEvent("500.00");

        // Repo returns the treaty; service must filter it out on treatyType.
        when(treatyRepository.findActiveByInsuranceLine("HEALTH"))
                .thenReturn(Flux.just(xol));

        StepVerifier.create(service.processPaidContribution(event, SYSTEM_ID, SYSTEM_EMAIL))
                .verifyComplete();

        verify(tenantRuleLoader, never()).ensureLoaded(any());
        verify(cessionRepository, never()).save(any());
        verify(auditPublisher, never()).publish(any());
    }

    @Test
    void processPaidContribution_alreadyCeded_isIdempotentNoOp() {
        UUID treatyId = UUID.randomUUID();
        Treaty treaty = treaty(treatyId, "QUOTA_SHARE");
        ContributionPaidEvent event = paidEvent("500.00");
        Cession existing = new Cession();
        existing.setId(UUID.randomUUID());

        when(treatyRepository.findActiveByInsuranceLine("HEALTH"))
                .thenReturn(Flux.just(treaty));
        when(tenantRuleLoader.ensureLoaded(any())).thenReturn(Mono.empty());
        when(ruleEvaluationService.evaluateInGroup(eq(TENANT_ID), eq("REINSURANCE"), any()))
                .thenReturn(Mono.just(List.of(
                        cedeResult(treatyId, new BigDecimal("150.00")))));
        when(cessionRepository.findByTreatyIdAndSourceEventIdAndCessionType(
                eq(treatyId), eq(event.contributionId()), eq("PREMIUM")))
                .thenReturn(Mono.just(existing));

        StepVerifier.create(service.processPaidContribution(event, SYSTEM_ID, SYSTEM_EMAIL))
                .verifyComplete();

        verify(cessionRepository, never()).save(any());
        verify(auditPublisher, never()).publish(any());
    }

    @Test
    void processPaidContribution_uniqueViolationOnRace_swallowsSilently() {
        UUID treatyId = UUID.randomUUID();
        Treaty treaty = treaty(treatyId, "QUOTA_SHARE");
        ContributionPaidEvent event = paidEvent("500.00");

        when(treatyRepository.findActiveByInsuranceLine("HEALTH"))
                .thenReturn(Flux.just(treaty));
        when(tenantRuleLoader.ensureLoaded(any())).thenReturn(Mono.empty());
        when(ruleEvaluationService.evaluateInGroup(eq(TENANT_ID), eq("REINSURANCE"), any()))
                .thenReturn(Mono.just(List.of(
                        cedeResult(treatyId, new BigDecimal("150.00")))));
        when(cessionRepository.findByTreatyIdAndSourceEventIdAndCessionType(any(), any(), anyString()))
                .thenReturn(Mono.empty());
        when(cessionRepository.save(any()))
                .thenReturn(Mono.error(new DuplicateKeyException("ux_cession_source_event")));

        StepVerifier.create(service.processPaidContribution(event, SYSTEM_ID, SYSTEM_EMAIL))
                .verifyComplete();

        verify(auditPublisher, never()).publish(any());
    }

    @Test
    void processPaidContribution_noMatchingTreaty_writesNothing() {
        ContributionPaidEvent event = paidEvent("500.00");
        when(treatyRepository.findActiveByInsuranceLine("HEALTH")).thenReturn(Flux.empty());

        StepVerifier.create(service.processPaidContribution(event, SYSTEM_ID, SYSTEM_EMAIL))
                .verifyComplete();

        verify(tenantRuleLoader, never()).ensureLoaded(any());
        verify(cessionRepository, never()).save(any());
    }

    @Test
    void processPaidContribution_zeroAmount_shortCircuits() {
        ContributionPaidEvent event = paidEvent("0.00");

        StepVerifier.create(service.processPaidContribution(event, SYSTEM_ID, SYSTEM_EMAIL))
                .verifyComplete();

        verify(treatyRepository, never()).findActiveByInsuranceLine(anyString());
    }

    @Test
    void processPaidContribution_missingTenantId_shortCircuits() {
        ContributionPaidEvent event = new ContributionPaidEvent(
                UUID.randomUUID(), UUID.randomUUID(), "HEALTH",
                new BigDecimal("500.00"), "USD", OffsetDateTime.now(), null);

        StepVerifier.create(service.processPaidContribution(event, SYSTEM_ID, SYSTEM_EMAIL))
                .verifyComplete();

        verify(treatyRepository, never()).findActiveByInsuranceLine(anyString());
    }

    @Test
    void processPaidContribution_multipleProportionalTreaties_cedesToEach() {
        UUID t1 = UUID.randomUUID();
        UUID t2 = UUID.randomUUID();
        UUID xolId = UUID.randomUUID();
        Treaty qs = treaty(t1, "QUOTA_SHARE");
        Treaty ss = treaty(t2, "SURPLUS_SHARE");
        Treaty xol = treaty(xolId, "EXCESS_OF_LOSS");
        ContributionPaidEvent event = paidEvent("500.00");

        // Repo returns three treaties; service must filter XoL out and cede
        // only against the two proportional treaties.
        when(treatyRepository.findActiveByInsuranceLine("HEALTH"))
                .thenReturn(Flux.just(qs, ss, xol));
        when(tenantRuleLoader.ensureLoaded(any())).thenReturn(Mono.empty());
        when(ruleEvaluationService.evaluateInGroup(eq(TENANT_ID), eq("REINSURANCE"), any()))
                .thenReturn(Mono.just(List.of(
                        cedeResult(t1, new BigDecimal("150.00")),
                        cedeResult(t2, new BigDecimal("100.00")),
                        cedeResult(xolId, new BigDecimal("50.00")))));
        when(cessionRepository.findByTreatyIdAndSourceEventIdAndCessionType(any(), any(), anyString()))
                .thenReturn(Mono.empty());
        when(cessionRepository.save(any())).thenAnswer(inv -> {
            Cession c = inv.getArgument(0);
            c.setId(UUID.randomUUID());
            return Mono.just(c);
        });
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());

        StepVerifier.create(service.processPaidContribution(event, SYSTEM_ID, SYSTEM_EMAIL))
                .expectNextCount(2)
                .verifyComplete();

        // XoL rule targeting the excluded treaty should not save.
        verify(cessionRepository, times(2)).save(any());
    }

    // ---- helpers ----

    private static Treaty treaty(UUID id, String type) {
        Treaty t = new Treaty();
        t.setId(id);
        t.setTreatyRef("T-" + id.toString().substring(0, 8));
        t.setTreatyType(type);
        t.setDeclaredCurrency("USD");
        t.setInceptionDate(LocalDate.now().minusDays(30));
        t.setExpiryDate(LocalDate.now().plusDays(300));
        t.setStatus("ACTIVE");
        return t;
    }

    private static ContributionPaidEvent paidEvent(String amount) {
        return new ContributionPaidEvent(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "HEALTH",
                new BigDecimal(amount),
                "USD",
                OffsetDateTime.now(),
                TENANT_ID);
    }

    private static RuleResult cedeResult(UUID treatyId, BigDecimal ceded) {
        return new RuleResult("CEDE_TO_TREATY", treatyId.toString(),
                "Ceded to treaty " + treatyId, ceded, null);
    }
}
