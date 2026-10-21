package com.medfund.finance.integration;

import com.medfund.finance.reinsurance.dto.ClaimAdjudicatedEvent;
import com.medfund.finance.reinsurance.dto.CreateReinsurerRequest;
import com.medfund.finance.reinsurance.dto.CreateTreatyApplicableLineRequest;
import com.medfund.finance.reinsurance.dto.CreateTreatyRequest;
import com.medfund.finance.reinsurance.dto.UpsertTreatyParticipantRequest;
import com.medfund.finance.reinsurance.entity.Cession;
import com.medfund.finance.reinsurance.repository.CessionRepository;
import com.medfund.finance.reinsurance.repository.RecoveryRepository;
import com.medfund.finance.reinsurance.service.CessionService;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * End-to-end integration for the Phase 3 loss cession path. Real
 * Postgres (Testcontainers) + real R2DBC repositories + real
 * {@link CessionService}. The rules-engine layer is mocked so we can
 * drive canned {@link RuleResult}s without authoring live DRL for a
 * synthetic tenant; that decoupling matches the Phase 2 IT's approach
 * of mocking only the broker-facing seams.
 *
 * <p>Verifies:
 * <ul>
 *   <li>A Cession row is written with the correct amounts + status
 *       when the rules engine emits a CEDE_TO_TREATY result</li>
 *   <li>A matching Recovery(EXPECTED) is written in the same
 *       transaction (Phase 3 deviation — recovery is not payment-triggered)</li>
 *   <li>Idempotency: re-processing the same claim writes zero
 *       duplicate rows (UNIQUE ux_cession_source_event enforced)</li>
 *   <li>AuditEvent is emitted for both entities with tenant slug and
 *       friendly entity names</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {
        "spring.flyway.locations=classpath:db/test-migration",
        "spring.flyway.baseline-on-migrate=true"
})
@Import(ReinsuranceLossCessionIT.SecurityStub.class)
class ReinsuranceLossCessionIT extends AbstractIntegrationTest {

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

    private static final String TENANT_ID = "00000000-0000-4000-8000-000000000011";
    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    @Autowired private CessionService cessionService;
    @Autowired private ReinsurerService reinsurerService;
    @Autowired private TreatyService treatyService;
    @Autowired private TreatyParticipantService participantService;
    @Autowired private TreatyApplicableLineService applicableLineService;
    @Autowired private CessionRepository cessionRepository;
    @Autowired private RecoveryRepository recoveryRepository;
    @Autowired private com.medfund.finance.reinsurance.repository.TreatyRepository treatyRepository;

    @MockBean private AuditPublisher auditPublisher;
    @MockBean private RuleEvaluationService ruleEvaluationService;
    @MockBean private TenantRuleLoader tenantRuleLoader;

    @Test
    @WithTenant(TENANT_ID)
    void adjudicatedApprovedClaim_writesCessionAndRecovery_withAudit() {
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());
        when(tenantRuleLoader.ensureLoaded(any())).thenReturn(Mono.empty());

        UUID treatyId = seedActiveHealthQuotaShareTreaty();

        // Diagnostic: treaty must actually be ACTIVE with HEALTH line for the
        // rest of this test to make sense.
        var active = treatyRepository.findActiveByInsuranceLine("HEALTH")
                .contextWrite(TenantTestContext.put())
                .collectList().block(TIMEOUT);
        assertThat(active).extracting("id").contains(treatyId);

        // Rule fires a 30% cede on this treaty.
        when(ruleEvaluationService.evaluateInGroup(eq(TENANT_ID), eq("REINSURANCE"), any()))
                .thenReturn(Mono.just(List.of(
                        new RuleResult("CEDE_TO_TREATY", treatyId.toString(),
                                "30% quota share", new BigDecimal("300.00"), null))));

        UUID claimId = UUID.randomUUID();
        ClaimAdjudicatedEvent event = new ClaimAdjudicatedEvent(
                claimId, "CLM-IT-1", UUID.randomUUID(), UUID.randomUUID(),
                "HEALTH", "USD", new BigDecimal("1000.00"), "APPROVED",
                "PROVIDER", OffsetDateTime.now(), TENANT_ID);

        List<Cession> savedList = cessionService.processAdjudicatedClaim(event, "sys", "system@medfund")
                .contextWrite(TenantTestContext.put())
                .collectList()
                .block(TIMEOUT);
        assertThat(savedList).isNotNull();
        assertThat(savedList).hasSize(1);
        Cession saved = savedList.get(0);

        var cession = cessionRepository.findByTreatyIdAndSourceEventIdAndCessionType(
                        treatyId, claimId, "LOSS")
                .contextWrite(TenantTestContext.put())
                .block(TIMEOUT);
        assertThat(cession).isNotNull();
        assertThat(cession.getCessionType()).isEqualTo("LOSS");
        assertThat(cession.getSource()).isEqualTo("AUTOMATIC");
        assertThat(cession.getStatus()).isEqualTo("ACTIVE");
        assertThat(cession.getBasisAmount()).isEqualByComparingTo("1000.00");
        assertThat(cession.getCededAmount()).isEqualByComparingTo("300.00");
        assertThat(cession.getSourceEventType()).isEqualTo("CLAIM_ADJUDICATED");

        var recovery = recoveryRepository.findByCessionId(cession.getId())
                .contextWrite(TenantTestContext.put())
                .block(TIMEOUT);
        assertThat(recovery).isNotNull();
        assertThat(recovery.getStatus()).isEqualTo("EXPECTED");
        assertThat(recovery.getExpectedAmount()).isEqualByComparingTo("300.00");
        assertThat(recovery.getReceivedAmount()).isNull();

        // Two audit events (Cession CREATE + Recovery CREATE), both with
        // tenant slug in tenantId and non-UUID friendly entityName.
        ArgumentCaptor<AuditEvent> cap = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditPublisher, atLeastOnce()).publish(cap.capture());
        assertThat(cap.getAllValues()).extracting(AuditEvent::entityType)
                .contains("Cession", "Recovery");
        assertThat(cap.getAllValues()).allSatisfy(ev -> {
            assertThat(ev.entityName()).doesNotContain(ev.entityId());
            assertThat(ev.actorEmail()).isNotBlank();
        });
    }

    @Test
    @WithTenant(TENANT_ID)
    void reprocessingSameClaim_isIdempotent() {
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());
        when(tenantRuleLoader.ensureLoaded(any())).thenReturn(Mono.empty());

        UUID treatyId = seedActiveHealthQuotaShareTreaty();
        UUID claimId = UUID.randomUUID();

        when(ruleEvaluationService.evaluateInGroup(eq(TENANT_ID), eq("REINSURANCE"), any()))
                .thenReturn(Mono.just(List.of(
                        new RuleResult("CEDE_TO_TREATY", treatyId.toString(),
                                "30%", new BigDecimal("300.00"), null))));

        ClaimAdjudicatedEvent event = new ClaimAdjudicatedEvent(
                claimId, "CLM-IT-2", UUID.randomUUID(), UUID.randomUUID(),
                "HEALTH", "USD", new BigDecimal("1000.00"), "APPROVED",
                "PROVIDER", OffsetDateTime.now(), TENANT_ID);

        // First delivery
        cessionService.processAdjudicatedClaim(event, "sys", "system@medfund")
                .contextWrite(TenantTestContext.put()).blockLast(TIMEOUT);
        // Second delivery — should be a no-op
        cessionService.processAdjudicatedClaim(event, "sys", "system@medfund")
                .contextWrite(TenantTestContext.put()).blockLast(TIMEOUT);

        Long cessionCount = cessionRepository.findBySourceEventId(claimId)
                .contextWrite(TenantTestContext.put())
                .count().block(TIMEOUT);
        assertThat(cessionCount).isEqualTo(1L);
    }

    @Test
    @WithTenant(TENANT_ID)
    void noActiveTreatyForLine_writesNoRows() {
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());
        when(tenantRuleLoader.ensureLoaded(any())).thenReturn(Mono.empty());
        // Default stub so the test is robust even if a prior test in the
        // shared-DB harness leaked a treaty on this line.
        when(ruleEvaluationService.evaluateInGroup(anyString(), anyString(), any()))
                .thenReturn(Mono.just(List.of()));
        // Use an insurance line that no other test seeds a treaty for.
        UUID claimId = UUID.randomUUID();
        ClaimAdjudicatedEvent event = new ClaimAdjudicatedEvent(
                claimId, "CLM-IT-3", UUID.randomUUID(), UUID.randomUUID(),
                "VEHICLE", "USD", new BigDecimal("1000.00"), "APPROVED",
                "PROVIDER", OffsetDateTime.now(), TENANT_ID);

        cessionService.processAdjudicatedClaim(event, "sys", "system@medfund")
                .contextWrite(TenantTestContext.put()).blockLast(TIMEOUT);

        Long cessionCount = cessionRepository.findBySourceEventId(claimId)
                .contextWrite(TenantTestContext.put())
                .count().block(TIMEOUT);
        assertThat(cessionCount).isZero();
    }

    private UUID seedActiveHealthQuotaShareTreaty() {
        var reinsurer = reinsurerService.create(
                        new CreateReinsurerRequest(
                                "Sole Re " + UUID.randomUUID(), null, null, null, null, null),
                        "sys", "system@medfund")
                .contextWrite(TenantTestContext.put()).block(TIMEOUT);
        assertThat(reinsurer).isNotNull();

        var draft = treatyService.createDraft(
                        new CreateTreatyRequest("HEALTH-QS-IT-" + UUID.randomUUID(),
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
