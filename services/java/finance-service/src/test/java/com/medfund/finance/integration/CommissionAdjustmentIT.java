package com.medfund.finance.integration;

import com.medfund.finance.producer.dto.AdjustmentResponse;
import com.medfund.finance.producer.dto.AssignMemberRequest;
import com.medfund.finance.producer.dto.ContributionPaidEvent;
import com.medfund.finance.producer.dto.CreateAdjustmentRequest;
import com.medfund.finance.producer.dto.CreateProducerRequest;
import com.medfund.finance.producer.dto.CreateRateCardRequest;
import com.medfund.finance.producer.entity.CommissionTransaction;
import com.medfund.finance.producer.repository.CommissionAdjustmentRepository;
import com.medfund.finance.producer.repository.CommissionTransactionRepository;
import com.medfund.finance.producer.service.CommissionAdjustmentService;
import com.medfund.finance.producer.service.CommissionCalcService;
import com.medfund.finance.producer.service.CommissionRateCardService;
import com.medfund.finance.producer.service.MemberProducerAssignmentService;
import com.medfund.finance.producer.service.ProducerService;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * End-to-end integration for the Phase 8 commission-adjustment four-eyes
 * lifecycle against a real Postgres. Verifies:
 *
 * <ul>
 *   <li>Full DRAFT → APPROVED → COMMITTED round-trip with two distinct
 *       actors writes a reference-carrying row, a compensating
 *       {@code commission_transaction}, and three {@link AuditEvent}s
 *       (create/approve/commit) with friendly {@code entityName}.</li>
 *   <li>Void from DRAFT with reason succeeds.</li>
 *   <li>Void from COMMITTED is rejected with 409.</li>
 *   <li>Four-eyes: same actor cannot approve their own DRAFT.</li>
 *   <li>Every {@code AuditEvent.entityName} equals the {@code reference},
 *       never the UUID (feedback_audit_entity_name).</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {
        "spring.flyway.locations=classpath:db/test-migration",
        "spring.flyway.baseline-on-migrate=true"
})
@Import(CommissionAdjustmentIT.SecurityStub.class)
class CommissionAdjustmentIT extends AbstractIntegrationTest {

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

    private static final String TENANT_ID = "00000000-0000-4000-8000-000000000081";
    private static final Duration TIMEOUT = Duration.ofSeconds(15);
    private static final String DRAFTER_ID    = "aaaaaaaa-0000-4000-8000-000000000010";
    private static final String DRAFTER_EMAIL = "drafter@medfund";
    private static final String APPROVER_ID   = "bbbbbbbb-0000-4000-8000-000000000020";
    private static final String APPROVER_EMAIL = "approver@medfund";
    private static final String JUSTIFICATION =
            "Broker requested ex-gratia adjustment on this contribution after review";

    @Autowired private ProducerService producerService;
    @Autowired private CommissionRateCardService rateCardService;
    @Autowired private MemberProducerAssignmentService assignmentService;
    @Autowired private CommissionCalcService commissionCalcService;
    @Autowired private CommissionAdjustmentService adjustmentService;
    @Autowired private CommissionTransactionRepository commissionTxnRepository;
    @Autowired private CommissionAdjustmentRepository adjustmentRepository;

    @MockBean private AuditPublisher auditPublisher;
    @MockBean private RuleEvaluationService ruleEvaluationService;
    @MockBean private TenantRuleLoader tenantRuleLoader;

    @Test
    @WithTenant(TENANT_ID)
    void fullLifecycle_draftApproveCommit_writesCompensatingAndAudits() {
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());
        UUID targetId = seedCommissionTransaction();

        // DRAFT
        AdjustmentResponse draft = adjustmentService.createDraft(
                        new CreateAdjustmentRequest(targetId, "EX_GRATIA",
                                new BigDecimal("25.0000"), JUSTIFICATION),
                        DRAFTER_ID, DRAFTER_EMAIL)
                .contextWrite(TenantTestContext.put()).block(TIMEOUT);
        assertThat(draft).isNotNull();
        assertThat(draft.status()).isEqualTo("DRAFT");
        assertThat(draft.reference()).startsWith("COMM-ADJ-");
        assertThat(draft.nativeCurrency()).isEqualTo("USD");

        // APPROVE (different actor)
        AdjustmentResponse approved = adjustmentService.approve(draft.id(),
                        APPROVER_ID, APPROVER_EMAIL)
                .contextWrite(TenantTestContext.put()).block(TIMEOUT);
        assertThat(approved).isNotNull();
        assertThat(approved.status()).isEqualTo("APPROVED");
        assertThat(approved.approverActorEmail()).isEqualTo(APPROVER_EMAIL);

        // COMMIT — writes compensating commission_transaction
        AdjustmentResponse committed = adjustmentService.commit(draft.id(),
                        APPROVER_ID, APPROVER_EMAIL)
                .contextWrite(TenantTestContext.put()).block(TIMEOUT);
        assertThat(committed).isNotNull();
        assertThat(committed.status()).isEqualTo("COMMITTED");
        assertThat(committed.committedTxnId()).isNotNull();

        CommissionTransaction compensating = commissionTxnRepository.findById(committed.committedTxnId())
                .contextWrite(TenantTestContext.put()).block(TIMEOUT);
        assertThat(compensating).isNotNull();
        assertThat(compensating.getReversalOfTxnId()).isEqualTo(targetId);
        assertThat(compensating.getNativeAmount()).isEqualByComparingTo("25.0000");
        assertThat(compensating.getReference()).startsWith("COMM-");
        assertThat(compensating.getReference()).doesNotStartWith("COMM-ADJ-");

        // Audit invariants: CREATE + APPROVE + COMMIT + compensating-CREATE
        ArgumentCaptor<AuditEvent> cap = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditPublisher, atLeastOnce()).publish(cap.capture());
        List<AuditEvent> adjEvents = cap.getAllValues().stream()
                .filter(e -> "CommissionAdjustment".equals(e.entityType()))
                .toList();
        assertThat(adjEvents).extracting(AuditEvent::action)
                .contains("CREATE", "APPROVE", "COMMIT");
        assertThat(adjEvents).allSatisfy(ev -> {
            assertThat(ev.entityName()).startsWith("COMM-ADJ-");
            assertThat(ev.entityName()).doesNotContain(ev.entityId());
            assertThat(ev.actorEmail()).isNotBlank();
        });
    }

    @Test
    @WithTenant(TENANT_ID)
    void voidFromDraft_withReason_flipsAndPersists() {
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());
        UUID targetId = seedCommissionTransaction();

        AdjustmentResponse draft = adjustmentService.createDraft(
                        new CreateAdjustmentRequest(targetId, "MANUAL_REVERSAL",
                                new BigDecimal("-10.0000"), JUSTIFICATION),
                        DRAFTER_ID, DRAFTER_EMAIL)
                .contextWrite(TenantTestContext.put()).block(TIMEOUT);
        assertThat(draft).isNotNull();

        AdjustmentResponse voided = adjustmentService.voidAdjustment(draft.id(),
                        "operator withdrew request",
                        APPROVER_ID, APPROVER_EMAIL)
                .contextWrite(TenantTestContext.put()).block(TIMEOUT);
        assertThat(voided).isNotNull();
        assertThat(voided.status()).isEqualTo("VOIDED");
        assertThat(voided.voidedReason()).isEqualTo("operator withdrew request");

        var reloaded = adjustmentRepository.findById(draft.id())
                .contextWrite(TenantTestContext.put()).block(TIMEOUT);
        assertThat(reloaded).isNotNull();
        assertThat(reloaded.getStatus()).isEqualTo("VOIDED");
    }

    @Test
    @WithTenant(TENANT_ID)
    void voidFromCommitted_isRejected() {
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());
        UUID targetId = seedCommissionTransaction();

        AdjustmentResponse draft = adjustmentService.createDraft(
                        new CreateAdjustmentRequest(targetId, "EX_GRATIA",
                                new BigDecimal("15.0000"), JUSTIFICATION),
                        DRAFTER_ID, DRAFTER_EMAIL)
                .contextWrite(TenantTestContext.put()).block(TIMEOUT);
        adjustmentService.approve(draft.id(), APPROVER_ID, APPROVER_EMAIL)
                .contextWrite(TenantTestContext.put()).block(TIMEOUT);
        adjustmentService.commit(draft.id(), APPROVER_ID, APPROVER_EMAIL)
                .contextWrite(TenantTestContext.put()).block(TIMEOUT);

        assertThatThrownBy(() -> adjustmentService.voidAdjustment(draft.id(),
                        "too late", APPROVER_ID, APPROVER_EMAIL)
                .contextWrite(TenantTestContext.put()).block(TIMEOUT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cannot void a COMMITTED");
    }

    @Test
    @WithTenant(TENANT_ID)
    void fourEyes_sameActorCannotApproveOwnDraft() {
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());
        UUID targetId = seedCommissionTransaction();

        AdjustmentResponse draft = adjustmentService.createDraft(
                        new CreateAdjustmentRequest(targetId, "EX_GRATIA",
                                new BigDecimal("30.0000"), JUSTIFICATION),
                        DRAFTER_ID, DRAFTER_EMAIL)
                .contextWrite(TenantTestContext.put()).block(TIMEOUT);

        assertThatThrownBy(() -> adjustmentService.approve(draft.id(),
                        DRAFTER_ID, DRAFTER_EMAIL)
                .contextWrite(TenantTestContext.put()).block(TIMEOUT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("four-eyes");
    }

    @Test
    @WithTenant(TENANT_ID)
    void audit_entityName_isReferenceNotUuid() {
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());
        UUID targetId = seedCommissionTransaction();

        AdjustmentResponse draft = adjustmentService.createDraft(
                        new CreateAdjustmentRequest(targetId, "EX_GRATIA",
                                new BigDecimal("40.0000"), JUSTIFICATION),
                        DRAFTER_ID, DRAFTER_EMAIL)
                .contextWrite(TenantTestContext.put()).block(TIMEOUT);

        ArgumentCaptor<AuditEvent> cap = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditPublisher, atLeastOnce()).publish(cap.capture());
        assertThat(cap.getAllValues()).filteredOn(e -> "CommissionAdjustment".equals(e.entityType()))
                .allSatisfy(ev -> {
                    assertThat(ev.entityName()).isEqualTo(draft.reference());
                    assertThat(ev.entityName()).isNotEqualTo(draft.id().toString());
                });
    }

    // ── seed helpers ────────────────────────────────────────────────────────

    private UUID seedCommissionTransaction() {
        when(tenantRuleLoader.ensureLoaded(any())).thenReturn(Mono.empty());
        when(ruleEvaluationService.evaluateInGroup(eq(TENANT_ID), eq("COMMISSION"), any()))
                .thenReturn(Mono.just(List.of()));

        UUID memberId = UUID.randomUUID();
        seedProducerAssignedTo(memberId);
        seedRateCard("HEALTH", new BigDecimal("10.0000"));

        UUID contributionId = UUID.randomUUID();
        ContributionPaidEvent event = new ContributionPaidEvent(
                contributionId, memberId,
                new BigDecimal("500.00"), "USD", "HEALTH",
                OffsetDateTime.now(), TENANT_ID);

        CommissionTransaction saved = commissionCalcService
                .processPaidContribution(event, "sys", "system@medfund")
                .contextWrite(TenantTestContext.put()).block(TIMEOUT);
        assertThat(saved).isNotNull();
        return saved.getId();
    }

    private void seedProducerAssignedTo(UUID memberId) {
        var producer = producerService.create(
                        new CreateProducerRequest(
                                "BRK-" + UUID.randomUUID().toString().substring(0, 8),
                                "Adj-Test Broker", null, null, "ZW", "USD",
                                null, null, null),
                        UUID.randomUUID().toString(), "admin@test.example")
                .contextWrite(TenantTestContext.put()).block(TIMEOUT);
        assertThat(producer).isNotNull();
        assignmentService.assign(memberId,
                        new AssignMemberRequest(producer.id(), LocalDate.now().withDayOfMonth(1), "test"),
                        UUID.randomUUID().toString(), "admin@test.example")
                .contextWrite(TenantTestContext.put()).block(TIMEOUT);
    }

    private void seedRateCard(String insuranceLine, BigDecimal ratePct) {
        rateCardService.create(
                        new CreateRateCardRequest(
                                "Adj Test " + insuranceLine + " " + UUID.randomUUID(),
                                insuranceLine, null, ratePct, null,
                                LocalDate.now().minusMonths(3), null),
                        UUID.randomUUID().toString(), "admin@test.example")
                .contextWrite(TenantTestContext.put()).block(TIMEOUT);
    }
}
