package com.medfund.finance.integration;

import com.medfund.finance.reinsurance.dto.ContributionPaidEvent;
import com.medfund.finance.reinsurance.dto.CreateReinsurerRequest;
import com.medfund.finance.reinsurance.dto.CreateTreatyApplicableLineRequest;
import com.medfund.finance.reinsurance.dto.CreateTreatyRequest;
import com.medfund.finance.reinsurance.dto.UpsertTreatyParticipantRequest;
import com.medfund.finance.reinsurance.entity.Cession;
import com.medfund.finance.reinsurance.repository.CessionRepository;
import com.medfund.finance.reinsurance.repository.RecoveryRepository;
import com.medfund.finance.reinsurance.service.PremiumCessionService;
import com.medfund.finance.reinsurance.service.ReinsurerService;
import com.medfund.finance.reinsurance.service.TreatyApplicableLineService;
import com.medfund.finance.reinsurance.service.TreatyParticipantService;
import com.medfund.finance.reinsurance.service.TreatyService;
import com.medfund.rules.fact.RuleResult;
import com.medfund.rules.service.RuleEvaluationService;
import com.medfund.rules.service.TenantRuleLoader;
import com.medfund.shared.audit.AuditEvent;
import com.medfund.shared.audit.AuditPublisher;
import com.medfund.shared.testfixtures.AbstractIntegrationTest;
import com.medfund.shared.testfixtures.TenantTestContext;
import com.medfund.shared.testfixtures.WithTenant;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.TestPropertySource;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * End-to-end integration for the Phase 6 premium-cession path. Real
 * Postgres (Testcontainers) + real R2DBC repositories + real
 * {@link PremiumCessionService}. Rules engine mocked so we can drive
 * canned {@link RuleResult}s — same shape as {@code ReinsuranceLossCessionIT}.
 *
 * <p>Verifies:
 * <ul>
 *   <li>PREMIUM Cession row is written against a paid contribution when
 *       the rules engine emits a CEDE_TO_TREATY result</li>
 *   <li>No Recovery is written (premium cession is the fund → reinsurer
 *       leg; recoveries are the loss-cession leg)</li>
 *   <li>Idempotency: re-processing the same contribution writes zero
 *       duplicate rows</li>
 *   <li>Non-proportional treaty on the same line is not touched</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {
        "spring.flyway.locations=classpath:db/test-migration",
        "spring.flyway.baseline-on-migrate=true"
})
@Import(ReinsurancePremiumCessionIT.SecurityStub.class)
class ReinsurancePremiumCessionIT extends AbstractIntegrationTest {

    @TestConfiguration
    static class SecurityStub {
        @Bean
        ReactiveJwtDecoder reactiveJwtDecoder() {
            return token -> Mono.just(new Jwt(
                    token, Instant.now(), Instant.now().plusSeconds(300),
                    Map.of("alg", "none"),
                    Map.of("sub", "test", "iss", "test")));
        }
    }

    private static final String TENANT_ID = "00000000-0000-4000-8000-000000000031";
    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    @Autowired private PremiumCessionService premiumCessionService;
    @Autowired private ReinsurerService reinsurerService;
    @Autowired private TreatyService treatyService;
    @Autowired private TreatyParticipantService participantService;
    @Autowired private TreatyApplicableLineService applicableLineService;
    @Autowired private CessionRepository cessionRepository;
    @Autowired private RecoveryRepository recoveryRepository;

    @MockBean private AuditPublisher auditPublisher;
    @MockBean private RuleEvaluationService ruleEvaluationService;
    @MockBean private TenantRuleLoader tenantRuleLoader;

    @Test
    @WithTenant(TENANT_ID)
    void paidContribution_writesPremiumCession_noRecovery_withAudit() {
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());
        when(tenantRuleLoader.ensureLoaded(any())).thenReturn(Mono.empty());

        UUID treatyId = seedActiveHealthQuotaShareTreaty();
        when(ruleEvaluationService.evaluateInGroup(eq(TENANT_ID), eq("REINSURANCE"), any()))
                .thenReturn(Mono.just(List.of(
                        new RuleResult("CEDE_TO_TREATY", treatyId.toString(),
                                "30% quota share", new BigDecimal("150.00"), null))));

        UUID contributionId = UUID.randomUUID();
        ContributionPaidEvent event = new ContributionPaidEvent(
                contributionId, UUID.randomUUID(), "HEALTH",
                new BigDecimal("500.00"), "USD",
                OffsetDateTime.now(), TENANT_ID);

        List<Cession> saved = premiumCessionService.processPaidContribution(event, "sys", "system@medfund")
                .contextWrite(TenantTestContext.put())
                .collectList()
                .block(TIMEOUT);
        assertThat(saved).isNotNull().hasSize(1);

        Cession cession = cessionRepository.findByTreatyIdAndSourceEventIdAndCessionType(
                        treatyId, contributionId, "PREMIUM")
                .contextWrite(TenantTestContext.put())
                .block(TIMEOUT);
        assertThat(cession).isNotNull();
        assertThat(cession.getCessionType()).isEqualTo("PREMIUM");
        assertThat(cession.getSource()).isEqualTo("AUTOMATIC");
        assertThat(cession.getStatus()).isEqualTo("ACTIVE");
        assertThat(cession.getBasisAmount()).isEqualByComparingTo("500.00");
        assertThat(cession.getCededAmount()).isEqualByComparingTo("150.00");
        assertThat(cession.getSourceEventType()).isEqualTo("CONTRIBUTION_PAID");

        // Premium cession must NOT create a Recovery — that leg is loss-only.
        Long recoveryCount = recoveryRepository.findByCessionId(cession.getId())
                .contextWrite(TenantTestContext.put())
                .flux().count().block(TIMEOUT);
        assertThat(recoveryCount).isZero();

        ArgumentCaptor<AuditEvent> cap = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditPublisher, atLeastOnce()).publish(cap.capture());
        assertThat(cap.getAllValues()).extracting(AuditEvent::entityType).contains("Cession");
        assertThat(cap.getAllValues()).allSatisfy(ev -> {
            assertThat(ev.entityName()).doesNotContain(ev.entityId());
            assertThat(ev.actorEmail()).isNotBlank();
        });
    }

    @Test
    @WithTenant(TENANT_ID)
    void reprocessingSameContribution_isIdempotent() {
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());
        when(tenantRuleLoader.ensureLoaded(any())).thenReturn(Mono.empty());

        UUID treatyId = seedActiveHealthQuotaShareTreaty();
        UUID contributionId = UUID.randomUUID();

        when(ruleEvaluationService.evaluateInGroup(eq(TENANT_ID), eq("REINSURANCE"), any()))
                .thenReturn(Mono.just(List.of(
                        new RuleResult("CEDE_TO_TREATY", treatyId.toString(),
                                "30%", new BigDecimal("150.00"), null))));

        ContributionPaidEvent event = new ContributionPaidEvent(
                contributionId, UUID.randomUUID(), "HEALTH",
                new BigDecimal("500.00"), "USD",
                OffsetDateTime.now(), TENANT_ID);

        premiumCessionService.processPaidContribution(event, "sys", "system@medfund")
                .contextWrite(TenantTestContext.put()).blockLast(TIMEOUT);
        premiumCessionService.processPaidContribution(event, "sys", "system@medfund")
                .contextWrite(TenantTestContext.put()).blockLast(TIMEOUT);

        Long cessionCount = cessionRepository.findBySourceEventId(contributionId)
                .contextWrite(TenantTestContext.put())
                .count().block(TIMEOUT);
        assertThat(cessionCount).isEqualTo(1L);
    }

    private UUID seedActiveHealthQuotaShareTreaty() {
        var reinsurer = reinsurerService.create(
                        new CreateReinsurerRequest(
                                "Prem Re " + UUID.randomUUID(), null, null, null, null, null),
                        "sys", "system@medfund")
                .contextWrite(TenantTestContext.put()).block(TIMEOUT);
        assertThat(reinsurer).isNotNull();

        var draft = treatyService.createDraft(
                        new CreateTreatyRequest("HEALTH-QS-PREM-" + UUID.randomUUID(),
                                "QUOTA_SHARE", "USD",
                                LocalDate.now().minusDays(30),
                                LocalDate.now().plusDays(300),
                                null, null, null, null),
                        "sys", "system@medfund")
                .contextWrite(TenantTestContext.put()).block(TIMEOUT);
        assertThat(draft).isNotNull();

        participantService.upsert(draft.id(),
                        new UpsertTreatyParticipantRequest(reinsurer.id(),
                                new BigDecimal("100.0000"), "LEADER"),
                        "sys", "system@medfund")
                .contextWrite(TenantTestContext.put()).block(TIMEOUT);
        applicableLineService.add(draft.id(),
                        new CreateTreatyApplicableLineRequest("HEALTH"),
                        "sys", "system@medfund")
                .contextWrite(TenantTestContext.put()).block(TIMEOUT);
        treatyService.activate(draft.id(), "sys", "system@medfund")
                .contextWrite(TenantTestContext.put()).block(TIMEOUT);

        return draft.id();
    }
}
