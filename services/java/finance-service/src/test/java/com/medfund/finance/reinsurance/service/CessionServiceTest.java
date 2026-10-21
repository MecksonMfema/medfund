package com.medfund.finance.reinsurance.service;

import com.medfund.finance.reinsurance.dto.ClaimAdjudicatedEvent;
import com.medfund.finance.reinsurance.entity.Cession;
import com.medfund.finance.reinsurance.entity.Recovery;
import com.medfund.finance.reinsurance.entity.Treaty;
import com.medfund.finance.reinsurance.repository.CessionRepository;
import com.medfund.finance.reinsurance.repository.RecoveryRepository;
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
 * Unit tests for {@link CessionService} — pure Mockito, no Postgres. Fixed
 * inputs at the boundary (canned {@link RuleResult}s coming out of the
 * mocked rules-engine) drive every branch of the dispatch.
 */
@ExtendWith(MockitoExtension.class)
class CessionServiceTest {

    private static final String TENANT_ID = "11111111-1111-4111-8111-111111111111";
    private static final String SYSTEM_ID = "system";
    private static final String SYSTEM_EMAIL = "system@medfund";

    @Mock CessionRepository cessionRepository;
    @Mock RecoveryRepository recoveryRepository;
    @Mock TreatyRepository treatyRepository;
    @Mock TenantRuleLoader tenantRuleLoader;
    @Mock RuleEvaluationService ruleEvaluationService;
    @Mock AuditPublisher auditPublisher;

    @InjectMocks CessionService service;

    @Test
    void processAdjudicatedClaim_proportionalCession_writesCessionAndRecovery() {
        UUID treatyId = UUID.randomUUID();
        Treaty treaty = treaty(treatyId, "QUOTA_SHARE");
        ClaimAdjudicatedEvent event = adjudicatedEvent("1000.00");

        when(treatyRepository.findActiveByInsuranceLine("HEALTH"))
                .thenReturn(Flux.just(treaty));
        when(tenantRuleLoader.ensureLoaded(any())).thenReturn(Mono.empty());
        when(ruleEvaluationService.evaluateInGroup(eq(TENANT_ID), eq("REINSURANCE"), any()))
                .thenReturn(Mono.just(List.of(
                        cedeResult(treatyId, new BigDecimal("300.00"), null))));
        when(cessionRepository.findByTreatyIdAndSourceEventIdAndCessionType(
                eq(treatyId), eq(event.claimId()), eq("LOSS")))
                .thenReturn(Mono.empty());
        when(cessionRepository.save(any())).thenAnswer(inv -> {
            Cession c = inv.getArgument(0);
            c.setId(UUID.randomUUID());
            return Mono.just(c);
        });
        when(recoveryRepository.save(any())).thenAnswer(inv -> {
            Recovery r = inv.getArgument(0);
            r.setId(UUID.randomUUID());
            return Mono.just(r);
        });
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());

        StepVerifier.create(service.processAdjudicatedClaim(event, SYSTEM_ID, SYSTEM_EMAIL))
                .assertNext(cession -> {
                    assertThat(cession.getTreatyId()).isEqualTo(treatyId);
                    assertThat(cession.getCessionType()).isEqualTo("LOSS");
                    assertThat(cession.getSource()).isEqualTo("AUTOMATIC");
                    assertThat(cession.getStatus()).isEqualTo("ACTIVE");
                    assertThat(cession.getBasisAmount()).isEqualByComparingTo("1000.00");
                    assertThat(cession.getCededAmount()).isEqualByComparingTo("300.00");
                    assertThat(cession.getTreatyLayerId()).isNull();
                })
                .verifyComplete();

        // One cession + one recovery + two audit events (cession + recovery)
        verify(cessionRepository, times(1)).save(any());
        verify(recoveryRepository, times(1)).save(any());
        ArgumentCaptor<AuditEvent> auditCap = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditPublisher, times(2)).publish(auditCap.capture());
        assertThat(auditCap.getAllValues())
                .extracting(AuditEvent::entityType)
                .containsExactly("Cession", "Recovery");

        // Recovery expected = cession ceded
        ArgumentCaptor<Recovery> recoveryCap = ArgumentCaptor.forClass(Recovery.class);
        verify(recoveryRepository).save(recoveryCap.capture());
        assertThat(recoveryCap.getValue().getStatus()).isEqualTo("EXPECTED");
        assertThat(recoveryCap.getValue().getExpectedAmount()).isEqualByComparingTo("300.00");
    }

    @Test
    void processAdjudicatedClaim_xolWithLayer_carriesLayerId() {
        UUID treatyId = UUID.randomUUID();
        UUID layerId  = UUID.randomUUID();
        Treaty treaty = treaty(treatyId, "EXCESS_OF_LOSS");
        ClaimAdjudicatedEvent event = adjudicatedEvent("100000.00");

        when(treatyRepository.findActiveByInsuranceLine("HEALTH"))
                .thenReturn(Flux.just(treaty));
        when(tenantRuleLoader.ensureLoaded(any())).thenReturn(Mono.empty());
        when(ruleEvaluationService.evaluateInGroup(eq(TENANT_ID), eq("REINSURANCE"), any()))
                .thenReturn(Mono.just(List.of(
                        cedeResult(treatyId, new BigDecimal("60000.00"), layerId.toString()))));
        when(cessionRepository.findByTreatyIdAndSourceEventIdAndCessionType(any(), any(), anyString()))
                .thenReturn(Mono.empty());
        when(cessionRepository.save(any())).thenAnswer(inv -> {
            Cession c = inv.getArgument(0);
            c.setId(UUID.randomUUID());
            return Mono.just(c);
        });
        when(recoveryRepository.save(any())).thenAnswer(inv -> {
            Recovery r = inv.getArgument(0);
            r.setId(UUID.randomUUID());
            return Mono.just(r);
        });
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());

        StepVerifier.create(service.processAdjudicatedClaim(event, SYSTEM_ID, SYSTEM_EMAIL))
                .assertNext(cession -> {
                    assertThat(cession.getTreatyLayerId()).isEqualTo(layerId);
                    assertThat(cession.getCededAmount()).isEqualByComparingTo("60000.00");
                })
                .verifyComplete();
    }

    @Test
    void processAdjudicatedClaim_alreadyCeded_isIdempotentNoOp() {
        UUID treatyId = UUID.randomUUID();
        Treaty treaty = treaty(treatyId, "QUOTA_SHARE");
        ClaimAdjudicatedEvent event = adjudicatedEvent("1000.00");
        Cession existing = new Cession();
        existing.setId(UUID.randomUUID());

        when(treatyRepository.findActiveByInsuranceLine("HEALTH"))
                .thenReturn(Flux.just(treaty));
        when(tenantRuleLoader.ensureLoaded(any())).thenReturn(Mono.empty());
        when(ruleEvaluationService.evaluateInGroup(eq(TENANT_ID), eq("REINSURANCE"), any()))
                .thenReturn(Mono.just(List.of(
                        cedeResult(treatyId, new BigDecimal("300.00"), null))));
        when(cessionRepository.findByTreatyIdAndSourceEventIdAndCessionType(
                eq(treatyId), eq(event.claimId()), eq("LOSS")))
                .thenReturn(Mono.just(existing));

        StepVerifier.create(service.processAdjudicatedClaim(event, SYSTEM_ID, SYSTEM_EMAIL))
                .verifyComplete();

        verify(cessionRepository, never()).save(any());
        verify(recoveryRepository, never()).save(any());
        verify(auditPublisher, never()).publish(any());
    }

    @Test
    void processAdjudicatedClaim_uniqueViolationOnRace_swallowsSilently() {
        UUID treatyId = UUID.randomUUID();
        Treaty treaty = treaty(treatyId, "QUOTA_SHARE");
        ClaimAdjudicatedEvent event = adjudicatedEvent("1000.00");

        when(treatyRepository.findActiveByInsuranceLine("HEALTH"))
                .thenReturn(Flux.just(treaty));
        when(tenantRuleLoader.ensureLoaded(any())).thenReturn(Mono.empty());
        when(ruleEvaluationService.evaluateInGroup(eq(TENANT_ID), eq("REINSURANCE"), any()))
                .thenReturn(Mono.just(List.of(
                        cedeResult(treatyId, new BigDecimal("300.00"), null))));
        when(cessionRepository.findByTreatyIdAndSourceEventIdAndCessionType(any(), any(), anyString()))
                .thenReturn(Mono.empty());
        when(cessionRepository.save(any()))
                .thenReturn(Mono.error(new DuplicateKeyException("ux_cession_source_event")));

        StepVerifier.create(service.processAdjudicatedClaim(event, SYSTEM_ID, SYSTEM_EMAIL))
                .verifyComplete();

        verify(recoveryRepository, never()).save(any());
        verify(auditPublisher, never()).publish(any());
    }

    @Test
    void processAdjudicatedClaim_noMatchingTreaty_writesNothing() {
        ClaimAdjudicatedEvent event = adjudicatedEvent("1000.00");
        when(treatyRepository.findActiveByInsuranceLine("HEALTH")).thenReturn(Flux.empty());

        StepVerifier.create(service.processAdjudicatedClaim(event, SYSTEM_ID, SYSTEM_EMAIL))
                .verifyComplete();

        verify(cessionRepository, never()).save(any());
        verify(recoveryRepository, never()).save(any());
        verify(tenantRuleLoader, never()).ensureLoaded(any());
    }

    @Test
    void processAdjudicatedClaim_ruleTargetingUnknownTreaty_isSkipped() {
        UUID activeTreatyId = UUID.randomUUID();
        UUID unrelatedTreatyId = UUID.randomUUID();
        Treaty treaty = treaty(activeTreatyId, "QUOTA_SHARE");
        ClaimAdjudicatedEvent event = adjudicatedEvent("1000.00");

        when(treatyRepository.findActiveByInsuranceLine("HEALTH"))
                .thenReturn(Flux.just(treaty));
        when(tenantRuleLoader.ensureLoaded(any())).thenReturn(Mono.empty());
        // Rule fires for a treaty that isn't in the active-for-line set
        when(ruleEvaluationService.evaluateInGroup(eq(TENANT_ID), eq("REINSURANCE"), any()))
                .thenReturn(Mono.just(List.of(
                        cedeResult(unrelatedTreatyId, new BigDecimal("300.00"), null))));

        StepVerifier.create(service.processAdjudicatedClaim(event, SYSTEM_ID, SYSTEM_EMAIL))
                .verifyComplete();

        verify(cessionRepository, never()).save(any());
    }

    @Test
    void processAdjudicatedClaim_zeroApproved_shortCircuits() {
        ClaimAdjudicatedEvent event = adjudicatedEvent("0.00");

        StepVerifier.create(service.processAdjudicatedClaim(event, SYSTEM_ID, SYSTEM_EMAIL))
                .verifyComplete();

        verify(treatyRepository, never()).findActiveByInsuranceLine(anyString());
    }

    @Test
    void processAdjudicatedClaim_multipleTreaties_cedesToEach() {
        UUID t1 = UUID.randomUUID();
        UUID t2 = UUID.randomUUID();
        Treaty treaty1 = treaty(t1, "QUOTA_SHARE");
        Treaty treaty2 = treaty(t2, "SURPLUS_SHARE");
        ClaimAdjudicatedEvent event = adjudicatedEvent("1000.00");

        when(treatyRepository.findActiveByInsuranceLine("HEALTH"))
                .thenReturn(Flux.just(treaty1, treaty2));
        when(tenantRuleLoader.ensureLoaded(any())).thenReturn(Mono.empty());
        when(ruleEvaluationService.evaluateInGroup(eq(TENANT_ID), eq("REINSURANCE"), any()))
                .thenReturn(Mono.just(List.of(
                        cedeResult(t1, new BigDecimal("300.00"), null),
                        cedeResult(t2, new BigDecimal("200.00"), null))));
        when(cessionRepository.findByTreatyIdAndSourceEventIdAndCessionType(any(), any(), anyString()))
                .thenReturn(Mono.empty());
        when(cessionRepository.save(any())).thenAnswer(inv -> {
            Cession c = inv.getArgument(0);
            c.setId(UUID.randomUUID());
            return Mono.just(c);
        });
        when(recoveryRepository.save(any())).thenAnswer(inv -> {
            Recovery r = inv.getArgument(0);
            r.setId(UUID.randomUUID());
            return Mono.just(r);
        });
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());

        StepVerifier.create(service.processAdjudicatedClaim(event, SYSTEM_ID, SYSTEM_EMAIL))
                .expectNextCount(2)
                .verifyComplete();

        verify(cessionRepository, times(2)).save(any());
        verify(recoveryRepository, times(2)).save(any());
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

    private static ClaimAdjudicatedEvent adjudicatedEvent(String approvedAmount) {
        return new ClaimAdjudicatedEvent(
                UUID.randomUUID(),
                "CLM-0001",
                UUID.randomUUID(),
                UUID.randomUUID(),
                "HEALTH",
                "USD",
                new BigDecimal(approvedAmount),
                "APPROVED",
                "PROVIDER",
                OffsetDateTime.now(),
                TENANT_ID);
    }

    private static RuleResult cedeResult(UUID treatyId, BigDecimal ceded, String layerId) {
        return new RuleResult("CEDE_TO_TREATY", treatyId.toString(),
                "Ceded to treaty " + treatyId, ceded, layerId);
    }
}
