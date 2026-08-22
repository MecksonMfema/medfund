package com.medfund.finance.integration;

import com.medfund.finance.reinsurance.dto.ClaimAdjudicatedEvent;
import com.medfund.finance.reinsurance.dto.CreateReinsurerRequest;
import com.medfund.finance.reinsurance.dto.CreateTreatyApplicableLineRequest;
import com.medfund.finance.reinsurance.dto.CreateTreatyRequest;
import com.medfund.finance.reinsurance.dto.UpsertTreatyParticipantRequest;
import com.medfund.finance.reinsurance.entity.Cession;
import com.medfund.finance.reinsurance.entity.Recovery;
import com.medfund.finance.reinsurance.repository.CessionRepository;
import com.medfund.finance.reinsurance.repository.RecoveryRepository;
import com.medfund.finance.reinsurance.repository.ReinsuranceReviewTaskRepository;
import com.medfund.finance.reinsurance.service.CessionService;
import com.medfund.finance.reinsurance.service.RecoveryService;
import com.medfund.finance.reinsurance.service.ReinsuranceReviewTaskService;
import com.medfund.finance.reinsurance.service.ReinsurerService;
import com.medfund.finance.reinsurance.service.TreatyApplicableLineService;
import com.medfund.finance.reinsurance.service.TreatyParticipantService;
import com.medfund.finance.reinsurance.service.TreatyService;
import com.medfund.rules.fact.RuleResult;
import com.medfund.rules.service.RuleEvaluationService;
import com.medfund.rules.service.TenantRuleLoader;
import com.medfund.shared.audit.AuditPublisher;
import com.medfund.shared.testfixtures.AbstractIntegrationTest;
import com.medfund.shared.testfixtures.TenantTestContext;
import com.medfund.shared.testfixtures.WithTenant;
import org.junit.jupiter.api.Test;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Phase 8 end-to-end integration: review-queue + recovery lifecycle.
 * Real Postgres via Testcontainers + real repositories + real service
 * layer. Rules-engine is mocked so we can seed a canned cede result
 * without authoring live DRL for a synthetic tenant.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {
        "spring.flyway.locations=classpath:db/test-migration",
        "spring.flyway.baseline-on-migrate=true"
})
@Import(ReinsuranceReviewAndRecoveryIT.SecurityStub.class)
class ReinsuranceReviewAndRecoveryIT extends AbstractIntegrationTest {

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

    private static final String TENANT_ID = "00000000-0000-4000-8000-000000000021";
    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    @Autowired private CessionService cessionService;
    @Autowired private ReinsurerService reinsurerService;
    @Autowired private TreatyService treatyService;
    @Autowired private TreatyParticipantService participantService;
    @Autowired private TreatyApplicableLineService applicableLineService;
    @Autowired private CessionRepository cessionRepository;
    @Autowired private RecoveryRepository recoveryRepository;
    @Autowired private ReinsuranceReviewTaskService reviewTaskService;
    @Autowired private ReinsuranceReviewTaskRepository reviewTaskRepository;
    @Autowired private RecoveryService recoveryService;

    @MockBean private AuditPublisher auditPublisher;
    @MockBean private RuleEvaluationService ruleEvaluationService;
    @MockBean private TenantRuleLoader tenantRuleLoader;

    @Test
    @WithTenant(TENANT_ID)
    void regressionCreatesTask_resolveVoid_cascadesToCessionAndRecovery() {
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());
        when(tenantRuleLoader.ensureLoaded(any())).thenReturn(Mono.empty());

        UUID treatyId = seedActiveHealthQuotaShareTreaty();
        UUID claimId = UUID.randomUUID();

        // Seed one prior cession via a first-time adjudication run.
        when(ruleEvaluationService.evaluateInGroup(eq(TENANT_ID), eq("REINSURANCE"), any()))
                .thenReturn(Mono.just(List.of(
                        new RuleResult("CEDE_TO_TREATY", treatyId.toString(),
                                "30%", new BigDecimal("300.00"), null))));
        cessionService.processAdjudicatedClaim(
                        new ClaimAdjudicatedEvent(
                                claimId, "CLM-IT-8A", UUID.randomUUID(), UUID.randomUUID(),
                                "HEALTH", "USD", new BigDecimal("1000.00"), "APPROVED",
                                "PROVIDER", OffsetDateTime.now(), TENANT_ID),
                        "sys", "system@medfund")
                .contextWrite(TenantTestContext.put())
                .blockLast(TIMEOUT);

        Cession prior = cessionRepository.findByTreatyIdAndSourceEventIdAndCessionType(
                        treatyId, claimId, "LOSS")
                .contextWrite(TenantTestContext.put())
                .block(TIMEOUT);
        assertThat(prior).isNotNull();

        // Open a regression task pointing at this cession (basis drops).
        var tasks = reviewTaskService.createRegressionTasks(
                        claimId, List.of(prior), new BigDecimal("500.00"),
                        "sys", "system@medfund")
                .contextWrite(TenantTestContext.put())
                .collectList()
                .block(TIMEOUT);
        assertThat(tasks).hasSize(1);
        UUID taskId = tasks.get(0).getId();

        // Resolve the task RESOLVED_VOID → cession + recovery cascade.
        reviewTaskService.resolve(taskId, "RESOLVED_VOID", "wrong loss magnitude",
                        "supervisor", "supervisor@medfund")
                .contextWrite(TenantTestContext.put())
                .block(TIMEOUT);

        Cession voided = cessionRepository.findById(prior.getId())
                .contextWrite(TenantTestContext.put())
                .block(TIMEOUT);
        assertThat(voided.getStatus()).isEqualTo("VOIDED");
        assertThat(voided.getVoidedReason()).contains("wrong loss magnitude");

        Recovery cascadedRecovery = recoveryRepository.findByCessionId(prior.getId())
                .contextWrite(TenantTestContext.put())
                .block(TIMEOUT);
        assertThat(cascadedRecovery.getStatus()).isEqualTo("WRITTEN_OFF");
        assertThat(cascadedRecovery.getWriteOffReason())
                .startsWith("Cession voided: wrong loss magnitude");
    }

    @Test
    @WithTenant(TENANT_ID)
    void recoveryLifecycle_markReceivedThenWriteOffAttempt_rejects() {
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());
        when(tenantRuleLoader.ensureLoaded(any())).thenReturn(Mono.empty());

        UUID treatyId = seedActiveHealthQuotaShareTreaty();
        UUID claimId = UUID.randomUUID();
        when(ruleEvaluationService.evaluateInGroup(eq(TENANT_ID), eq("REINSURANCE"), any()))
                .thenReturn(Mono.just(List.of(
                        new RuleResult("CEDE_TO_TREATY", treatyId.toString(),
                                "30%", new BigDecimal("300.00"), null))));
        cessionService.processAdjudicatedClaim(
                        new ClaimAdjudicatedEvent(
                                claimId, "CLM-IT-8B", UUID.randomUUID(), UUID.randomUUID(),
                                "HEALTH", "USD", new BigDecimal("1000.00"), "APPROVED",
                                "PROVIDER", OffsetDateTime.now(), TENANT_ID),
                        "sys", "system@medfund")
                .contextWrite(TenantTestContext.put())
                .blockLast(TIMEOUT);

        Cession cession = cessionRepository.findByTreatyIdAndSourceEventIdAndCessionType(
                        treatyId, claimId, "LOSS")
                .contextWrite(TenantTestContext.put())
                .block(TIMEOUT);
        Recovery seeded = recoveryRepository.findByCessionId(cession.getId())
                .contextWrite(TenantTestContext.put())
                .block(TIMEOUT);
        assertThat(seeded.getStatus()).isEqualTo("EXPECTED");

        // Mark received with a partial payment.
        var received = recoveryService.markReceived(seeded.getId(),
                        new BigDecimal("290.00"),
                        OffsetDateTime.parse("2026-08-20T09:00:00Z"),
                        "finance", "finance@medfund")
                .contextWrite(TenantTestContext.put())
                .block(TIMEOUT);
        assertThat(received.status()).isEqualTo("RECEIVED");
        assertThat(received.receivedAmount()).isEqualByComparingTo("290.00");

        // Write-off on the already-RECEIVED recovery must fail — terminal state.
        try {
            recoveryService.writeOff(seeded.getId(), "test", "finance", "finance@medfund")
                    .contextWrite(TenantTestContext.put())
                    .block(TIMEOUT);
            throw new AssertionError("expected IllegalStateException on write-off of RECEIVED");
        } catch (IllegalStateException expected) {
            assertThat(expected).hasMessageContaining("RECEIVED");
        }
        Recovery reloaded = recoveryRepository.findById(seeded.getId())
                .contextWrite(TenantTestContext.put())
                .block(TIMEOUT);
        assertThat(reloaded.getStatus()).isEqualTo("RECEIVED");
    }

    @Test
    @WithTenant(TENANT_ID)
    void createRegressionTasks_rerun_isIdempotent() {
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());
        when(tenantRuleLoader.ensureLoaded(any())).thenReturn(Mono.empty());

        UUID treatyId = seedActiveHealthQuotaShareTreaty();
        UUID claimId = UUID.randomUUID();
        when(ruleEvaluationService.evaluateInGroup(eq(TENANT_ID), eq("REINSURANCE"), any()))
                .thenReturn(Mono.just(List.of(
                        new RuleResult("CEDE_TO_TREATY", treatyId.toString(),
                                "30%", new BigDecimal("300.00"), null))));
        cessionService.processAdjudicatedClaim(
                        new ClaimAdjudicatedEvent(
                                claimId, "CLM-IT-8C", UUID.randomUUID(), UUID.randomUUID(),
                                "HEALTH", "USD", new BigDecimal("1000.00"), "APPROVED",
                                "PROVIDER", OffsetDateTime.now(), TENANT_ID),
                        "sys", "system@medfund")
                .contextWrite(TenantTestContext.put())
                .blockLast(TIMEOUT);
        Cession prior = cessionRepository.findByTreatyIdAndSourceEventIdAndCessionType(
                        treatyId, claimId, "LOSS")
                .contextWrite(TenantTestContext.put())
                .block(TIMEOUT);

        // First and second regression attempts on the same cession.
        reviewTaskService.createRegressionTasks(claimId, List.of(prior),
                        new BigDecimal("500.00"), "sys", "system@medfund")
                .contextWrite(TenantTestContext.put())
                .blockLast(TIMEOUT);
        reviewTaskService.createRegressionTasks(claimId, List.of(prior),
                        new BigDecimal("400.00"), "sys", "system@medfund")
                .contextWrite(TenantTestContext.put())
                .blockLast(TIMEOUT);

        Long taskCount = reviewTaskRepository.findByClaimId(claimId)
                .contextWrite(TenantTestContext.put())
                .count()
                .block(TIMEOUT);
        assertThat(taskCount).isEqualTo(1L);
    }

    private UUID seedActiveHealthQuotaShareTreaty() {
        var reinsurer = reinsurerService.create(
                        new CreateReinsurerRequest(
                                "Sole Re " + UUID.randomUUID(), null, null, null, null, null),
                        "sys", "system@medfund")
                .contextWrite(TenantTestContext.put()).block(TIMEOUT);
        assertThat(reinsurer).isNotNull();

        var draft = treatyService.createDraft(
                        new CreateTreatyRequest("HEALTH-QS-8-" + UUID.randomUUID(),
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
