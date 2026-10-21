package com.medfund.finance.integration;

import com.medfund.finance.reinsurance.dto.CessionResponse;
import com.medfund.finance.reinsurance.dto.CreateFacultativeCessionRequest;
import com.medfund.finance.reinsurance.dto.CreateReinsurerRequest;
import com.medfund.finance.reinsurance.dto.CreateTreatyApplicableLineRequest;
import com.medfund.finance.reinsurance.dto.CreateTreatyRequest;
import com.medfund.finance.reinsurance.dto.UpsertTreatyParticipantRequest;
import com.medfund.finance.reinsurance.repository.CessionRepository;
import com.medfund.finance.reinsurance.repository.RecoveryRepository;
import com.medfund.finance.reinsurance.service.FacultativeCessionService;
import com.medfund.finance.reinsurance.service.ReinsurerService;
import com.medfund.finance.reinsurance.service.TreatyApplicableLineService;
import com.medfund.finance.reinsurance.service.TreatyParticipantService;
import com.medfund.finance.reinsurance.service.TreatyService;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * End-to-end integration for the Phase 7 facultative cession lifecycle
 * against a real Postgres via Testcontainers. Verifies:
 *
 * <ul>
 *   <li>Full DRAFT → APPROVED → CEDED round-trip persists correctly and
 *       writes an EXPECTED Recovery on commit</li>
 *   <li>Void on a DRAFT flips both the cession and any pending Recovery</li>
 *   <li>Creation against a DRAFT treaty is rejected (409)</li>
 *   <li>Creation against a line the treaty does not cover is rejected (409)</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {
        "spring.flyway.locations=classpath:db/test-migration",
        "spring.flyway.baseline-on-migrate=true"
})
@Import(FacultativeCessionIT.SecurityStub.class)
class FacultativeCessionIT extends AbstractIntegrationTest {

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

    @Autowired private FacultativeCessionService facultativeService;
    @Autowired private ReinsurerService reinsurerService;
    @Autowired private TreatyService treatyService;
    @Autowired private TreatyParticipantService participantService;
    @Autowired private TreatyApplicableLineService applicableLineService;
    @Autowired private CessionRepository cessionRepository;
    @Autowired private RecoveryRepository recoveryRepository;

    @MockBean private AuditPublisher auditPublisher;

    @Test
    @WithTenant(TENANT_ID)
    void fullLifecycle_draftApproveCommit_writesCessionAndRecoveryWithAudit() {
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());

        UUID treatyId = seedActiveHealthTreaty();
        UUID claimId  = UUID.randomUUID();

        CreateFacultativeCessionRequest req = new CreateFacultativeCessionRequest(
                claimId, treatyId, null,
                new BigDecimal("5000.00"), new BigDecimal("12000.00"),
                "USD", "high-severity provider claim");

        CessionResponse draft = facultativeService.createDraft(req, "HEALTH",
                        "under-1", "underwriter@medfund")
                .contextWrite(TenantTestContext.put()).block(TIMEOUT);
        assertThat(draft).isNotNull();
        assertThat(draft.status()).isEqualTo("DRAFT");
        assertThat(draft.source()).isEqualTo("FACULTATIVE");
        assertThat(draft.sourceEventType()).isEqualTo("CLAIM_FACULTATIVE");

        // No Recovery until commit
        var pending = recoveryRepository.findByCessionId(draft.id())
                .contextWrite(TenantTestContext.put()).block(TIMEOUT);
        assertThat(pending).isNull();

        // Approve
        CessionResponse approved = facultativeService.approve(draft.id(),
                        "super-1", "supervisor@medfund")
                .contextWrite(TenantTestContext.put()).block(TIMEOUT);
        assertThat(approved).isNotNull();
        assertThat(approved.status()).isEqualTo("APPROVED");

        // Commit — writes Recovery(EXPECTED)
        CessionResponse ceded = facultativeService.commit(draft.id(),
                        "super-1", "supervisor@medfund")
                .contextWrite(TenantTestContext.put()).block(TIMEOUT);
        assertThat(ceded).isNotNull();
        assertThat(ceded.status()).isEqualTo("CEDED");

        var recovery = recoveryRepository.findByCessionId(draft.id())
                .contextWrite(TenantTestContext.put()).block(TIMEOUT);
        assertThat(recovery).isNotNull();
        assertThat(recovery.getStatus()).isEqualTo("EXPECTED");
        assertThat(recovery.getExpectedAmount()).isEqualByComparingTo("5000.00");
        assertThat(recovery.getCurrencyCode()).isEqualTo("USD");

        // Audit invariants: friendly entityName, non-null actorEmail
        ArgumentCaptor<AuditEvent> cap = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditPublisher, atLeastOnce()).publish(cap.capture());
        List<AuditEvent> reinsuranceEvents = cap.getAllValues().stream()
                .filter(e -> "Cession".equals(e.entityType()) || "Recovery".equals(e.entityType()))
                .toList();
        assertThat(reinsuranceEvents).extracting(AuditEvent::action)
                .contains("CREATE", "APPROVE", "COMMIT");
        assertThat(reinsuranceEvents).allSatisfy(ev -> {
            assertThat(ev.entityName()).doesNotContain(ev.entityId());
            assertThat(ev.actorEmail()).isNotBlank();
        });
    }

    @Test
    @WithTenant(TENANT_ID)
    void voidDraft_cascadeVoidsPendingRecoveryIfPresent() {
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());
        UUID treatyId = seedActiveHealthTreaty();

        CreateFacultativeCessionRequest req = new CreateFacultativeCessionRequest(
                UUID.randomUUID(), treatyId, null,
                new BigDecimal("2500.00"), new BigDecimal("5000.00"),
                "USD", "void-me-later");

        CessionResponse draft = facultativeService.createDraft(req, "HEALTH",
                        "under-1", "underwriter@medfund")
                .contextWrite(TenantTestContext.put()).block(TIMEOUT);
        assertThat(draft).isNotNull();

        CessionResponse voided = facultativeService.voidCession(draft.id(),
                        "reinsurer withdrew capacity",
                        "super-1", "supervisor@medfund")
                .contextWrite(TenantTestContext.put()).block(TIMEOUT);
        assertThat(voided).isNotNull();
        assertThat(voided.status()).isEqualTo("VOIDED");
        assertThat(voided.voidedReason()).isEqualTo("reinsurer withdrew capacity");

        // No Recovery was written pre-commit — nothing to cascade
        var recovery = recoveryRepository.findByCessionId(draft.id())
                .contextWrite(TenantTestContext.put()).block(TIMEOUT);
        assertThat(recovery).isNull();
    }

    @Test
    @WithTenant(TENANT_ID)
    void createDraft_againstDraftTreaty_rejects() {
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());

        // Seed a DRAFT treaty (skip activate())
        var reinsurer = reinsurerService.create(
                        new CreateReinsurerRequest(
                                "DRAFT Re " + UUID.randomUUID(), null, null, null, null, null),
                        "sys", "system@medfund")
                .contextWrite(TenantTestContext.put()).block(TIMEOUT);
        assertThat(reinsurer).isNotNull();

        var draftTreaty = treatyService.createDraft(
                        new CreateTreatyRequest("HEALTH-DRAFT-" + UUID.randomUUID(),
                                "QUOTA_SHARE", "USD",
                                LocalDate.now().minusDays(30),
                                LocalDate.now().plusDays(300),
                                null, null, null, null),
                        "sys", "system@medfund")
                .contextWrite(TenantTestContext.put()).block(TIMEOUT);
        assertThat(draftTreaty).isNotNull();

        CreateFacultativeCessionRequest req = new CreateFacultativeCessionRequest(
                UUID.randomUUID(), draftTreaty.id(), null,
                new BigDecimal("1000"), new BigDecimal("2000"),
                "USD", null);

        assertThatThrownBy(() -> facultativeService.createDraft(req, "HEALTH",
                        "under-1", "underwriter@medfund")
                .contextWrite(TenantTestContext.put()).block(TIMEOUT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ACTIVE");
    }

    @Test
    @WithTenant(TENANT_ID)
    void createDraft_lineNotCovered_rejects() {
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());
        UUID treatyId = seedActiveHealthTreaty();

        CreateFacultativeCessionRequest req = new CreateFacultativeCessionRequest(
                UUID.randomUUID(), treatyId, null,
                new BigDecimal("1000"), new BigDecimal("2000"),
                "USD", null);

        assertThatThrownBy(() -> facultativeService.createDraft(req, "LIFE",
                        "under-1", "underwriter@medfund")
                .contextWrite(TenantTestContext.put()).block(TIMEOUT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does not cover");
    }

    // ── fixture ────────────────────────────────────────────────────────────

    private UUID seedActiveHealthTreaty() {
        var reinsurer = reinsurerService.create(
                        new CreateReinsurerRequest(
                                "Fac Re " + UUID.randomUUID(), null, null, null, null, null),
                        "sys", "system@medfund")
                .contextWrite(TenantTestContext.put()).block(TIMEOUT);
        assertThat(reinsurer).isNotNull();

        var draft = treatyService.createDraft(
                        new CreateTreatyRequest("HEALTH-FAC-IT-" + UUID.randomUUID(),
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
