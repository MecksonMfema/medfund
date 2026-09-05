package com.medfund.claims.siu.service;

import com.medfund.claims.entity.Claim;
import com.medfund.claims.repository.ClaimRepository;
import com.medfund.claims.siu.entity.FraudFlag;
import com.medfund.claims.siu.entity.SiuCase;
import com.medfund.claims.siu.entity.SiuCaseNote;
import com.medfund.claims.siu.entity.SiuEvidence;
import com.medfund.claims.siu.entity.SiuReferral;
import com.medfund.claims.siu.repository.FraudFlagRepository;
import com.medfund.claims.siu.repository.SiuCaseNoteRepository;
import com.medfund.claims.siu.repository.SiuCaseRepository;
import com.medfund.claims.siu.repository.SiuEvidenceRepository;
import com.medfund.claims.siu.repository.SiuReferralRepository;
import com.medfund.rules.engine.TenantRuleEngine;
import com.medfund.rules.fact.FraudFlagFact;
import com.medfund.shared.audit.AuditEvent;
import com.medfund.shared.audit.AuditPublisher;
import com.medfund.shared.tenant.TenantContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SiuCaseServiceTest {

    private SiuCaseRepository caseRepo;
    private SiuCaseNoteRepository noteRepo;
    private SiuEvidenceRepository evidenceRepo;
    private SiuReferralRepository referralRepo;
    private FraudFlagService fraudFlagService;
    private AuditPublisher auditPublisher;
    private TenantRuleEngine ruleEngine;
    private ClaimRepository claimRepository;
    private FraudFlagRepository fraudFlagRepository;
    private SiuCaseService service;

    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-4000-8000-000000000001");

    @BeforeEach
    void setUp() {
        caseRepo = mock(SiuCaseRepository.class);
        noteRepo = mock(SiuCaseNoteRepository.class);
        evidenceRepo = mock(SiuEvidenceRepository.class);
        referralRepo = mock(SiuReferralRepository.class);
        fraudFlagService = mock(FraudFlagService.class);
        auditPublisher = mock(AuditPublisher.class);
        ruleEngine = mock(TenantRuleEngine.class);
        claimRepository = mock(ClaimRepository.class);
        fraudFlagRepository = mock(FraudFlagRepository.class);

        service = new SiuCaseService(caseRepo, noteRepo, evidenceRepo, referralRepo,
                fraudFlagService, auditPublisher, ruleEngine,
                claimRepository, fraudFlagRepository);
        ReflectionTestUtils.setField(service, "defaultMinScore", new BigDecimal("0.85"));

        // Enrichment defaults — most tests exercise the default-policy path
        // (no tenant rules loaded), so the claim + count lookups aren't
        // hit. Rules-loaded tests override these per-test.
        when(claimRepository.findById(any(UUID.class))).thenReturn(Mono.empty());
        when(fraudFlagRepository.countHighRiskForMemberSince(any(UUID.class), any()))
                .thenReturn(Mono.just(0L));
        when(fraudFlagRepository.countHighRiskForProviderSince(any(UUID.class), any()))
                .thenReturn(Mono.just(0L));

        when(caseRepo.save(any(SiuCase.class))).thenAnswer(inv -> {
            SiuCase arg = inv.getArgument(0);
            if (arg.getId() == null) arg.setId(UUID.randomUUID());
            return Mono.just(arg);
        });
        when(noteRepo.save(any(SiuCaseNote.class))).thenAnswer(inv -> {
            SiuCaseNote arg = inv.getArgument(0);
            if (arg.getId() == null) arg.setId(UUID.randomUUID());
            return Mono.just(arg);
        });
        when(auditPublisher.publish(any(AuditEvent.class))).thenReturn(Mono.empty());
    }

    // ── evaluateTriage / default-policy path ────────────────────────────

    @Test
    void evaluateTriage_highRiskAboveThreshold_opensCaseAndLinksFlag() {
        FraudFlag flag = highRiskFlag(new BigDecimal("0.90"));
        when(caseRepo.count()).thenReturn(Mono.just(0L));
        when(fraudFlagService.linkToCase(any(FraudFlag.class), any(UUID.class)))
                .thenAnswer(inv -> Mono.just(inv.<FraudFlag>getArgument(0)));

        StepVerifier.create(service.evaluateTriage(flag))
                .verifyComplete();

        ArgumentCaptor<SiuCase> caseCaptor = ArgumentCaptor.forClass(SiuCase.class);
        verify(caseRepo).save(caseCaptor.capture());
        assertThat(caseCaptor.getValue().getStatus()).isEqualTo("OPEN");
        assertThat(caseCaptor.getValue().getOpenedByEmail()).isEqualTo(SiuCaseService.SYSTEM_EMAIL);
        assertThat(caseCaptor.getValue().getCaseNumber()).startsWith("SIU-");

        // linkToCase called + auto-open note added
        verify(fraudFlagService).linkToCase(any(FraudFlag.class), any(UUID.class));
        ArgumentCaptor<SiuCaseNote> noteCaptor = ArgumentCaptor.forClass(SiuCaseNote.class);
        verify(noteRepo).save(noteCaptor.capture());
        assertThat(noteCaptor.getValue().getNoteType()).isEqualTo("STATUS_CHANGE");
        assertThat(noteCaptor.getValue().getBody()).contains("auto-opened");
    }

    @Test
    void evaluateTriage_mediumRisk_doesNotOpenCase() {
        FraudFlag flag = new FraudFlag();
        flag.setId(UUID.randomUUID());
        flag.setRiskLevel("MEDIUM");
        flag.setRiskScore(new BigDecimal("0.50"));

        StepVerifier.create(service.evaluateTriage(flag)).verifyComplete();
        verify(caseRepo, never()).save(any(SiuCase.class));
        verify(auditPublisher, never()).publish(any(AuditEvent.class));
    }

    @Test
    void evaluateTriage_highRiskAtOrBelowThreshold_doesNotOpenCase() {
        // Score exactly at threshold — compareTo returns 0, > check fails.
        FraudFlag flag = highRiskFlag(new BigDecimal("0.85"));
        StepVerifier.create(service.evaluateTriage(flag)).verifyComplete();
        verify(caseRepo, never()).save(any(SiuCase.class));
    }

    // ── evaluateTriage / rules-engine dispatch path (Phase 5) ───────────

    @Test
    void evaluateTriage_tenantRulesLoaded_ruleFires_opensCase() {
        FraudFlag flag = highRiskFlag(new BigDecimal("0.50"));   // below default threshold
        when(caseRepo.count()).thenReturn(Mono.just(0L));
        when(fraudFlagService.linkToCase(any(FraudFlag.class), any(UUID.class)))
                .thenAnswer(inv -> Mono.just(inv.<FraudFlag>getArgument(0)));
        when(ruleEngine.hasRulesLoaded(TENANT_ID.toString())).thenReturn(true);
        // Simulate the rule flipping emitCase on the passed fact
        when(ruleEngine.evaluateInGroup(eq(TENANT_ID.toString()), eq("FRAUD_TRIAGE"),
                any(FraudFlagFact.class)))
                .thenAnswer(inv -> {
                    FraudFlagFact fact = inv.getArgument(2);
                    fact.setEmitCase(true);
                    return List.of();
                });

        StepVerifier.create(service.evaluateTriage(flag)
                        .contextWrite(ctx -> TenantContext.put(ctx, TENANT_ID.toString())))
                .verifyComplete();

        verify(caseRepo).save(any(SiuCase.class));
    }

    @Test
    void evaluateTriage_tenantRulesLoaded_noRuleFires_fallsBackToDefaultPolicy() {
        FraudFlag flag = highRiskFlag(new BigDecimal("0.50"));   // below default threshold
        when(ruleEngine.hasRulesLoaded(TENANT_ID.toString())).thenReturn(true);
        // Rules don't set emitCase → fall through to default policy, which
        // sees 0.50 < 0.85 and skips.
        when(ruleEngine.evaluateInGroup(any(), any(), any(FraudFlagFact.class)))
                .thenReturn(List.of());

        StepVerifier.create(service.evaluateTriage(flag)
                        .contextWrite(ctx -> TenantContext.put(ctx, TENANT_ID.toString())))
                .verifyComplete();
        verify(caseRepo, never()).save(any(SiuCase.class));
    }

    @Test
    void evaluateTriage_tenantRulesLoaded_ruleSkips_defaultPolicyStillFiresForHighRisk() {
        FraudFlag flag = highRiskFlag(new BigDecimal("0.90"));   // above default threshold
        when(caseRepo.count()).thenReturn(Mono.just(0L));
        when(fraudFlagService.linkToCase(any(FraudFlag.class), any(UUID.class)))
                .thenAnswer(inv -> Mono.just(inv.<FraudFlag>getArgument(0)));
        when(ruleEngine.hasRulesLoaded(TENANT_ID.toString())).thenReturn(true);
        // Rules fire but don't set emitCase → default policy sees 0.90 > 0.85 → open.
        when(ruleEngine.evaluateInGroup(any(), any(), any(FraudFlagFact.class)))
                .thenReturn(List.of());

        StepVerifier.create(service.evaluateTriage(flag)
                        .contextWrite(ctx -> TenantContext.put(ctx, TENANT_ID.toString())))
                .verifyComplete();
        verify(caseRepo).save(any(SiuCase.class));
    }

    @Test
    void evaluateTriage_noTenantContext_usesDefaultPolicy() {
        // No .contextWrite → tenantId resolves to null → default policy path.
        FraudFlag flag = highRiskFlag(new BigDecimal("0.90"));
        when(caseRepo.count()).thenReturn(Mono.just(0L));
        when(fraudFlagService.linkToCase(any(FraudFlag.class), any(UUID.class)))
                .thenAnswer(inv -> Mono.just(inv.<FraudFlag>getArgument(0)));

        StepVerifier.create(service.evaluateTriage(flag)).verifyComplete();
        verify(caseRepo).save(any(SiuCase.class));
        verify(ruleEngine, never()).evaluateInGroup(any(), any(), any(FraudFlagFact.class));
    }

    // ── §B Phase 9 — fact enrichment (claim lookup + historical counts) ─

    @Test
    void evaluateTriage_tenantRulesLoaded_enrichesFactFromClaimAndCounts() {
        UUID memberId = UUID.randomUUID();
        UUID providerId = UUID.randomUUID();
        UUID claimId = UUID.randomUUID();

        FraudFlag flag = highRiskFlag(new BigDecimal("0.50"));
        flag.setClaimId(claimId);

        Claim claim = new Claim();
        claim.setId(claimId);
        claim.setMemberId(memberId);
        claim.setProviderId(providerId);
        claim.setInsuranceLine("HEALTH");
        claim.setClaimedAmount(new BigDecimal("2500.00"));
        claim.setCurrencyCode("USD");

        when(ruleEngine.hasRulesLoaded(TENANT_ID.toString())).thenReturn(true);
        when(claimRepository.findById(claimId)).thenReturn(Mono.just(claim));
        when(fraudFlagRepository.countHighRiskForMemberSince(eq(memberId), any()))
                .thenReturn(Mono.just(3L));
        when(fraudFlagRepository.countHighRiskForProviderSince(eq(providerId), any()))
                .thenReturn(Mono.just(7L));
        when(ruleEngine.evaluateInGroup(eq(TENANT_ID.toString()), eq("FRAUD_TRIAGE"),
                any(FraudFlagFact.class))).thenReturn(List.of());

        StepVerifier.create(service.evaluateTriage(flag)
                        .contextWrite(ctx -> TenantContext.put(ctx, TENANT_ID.toString())))
                .verifyComplete();

        ArgumentCaptor<FraudFlagFact> factCap = ArgumentCaptor.forClass(FraudFlagFact.class);
        verify(ruleEngine).evaluateInGroup(eq(TENANT_ID.toString()), eq("FRAUD_TRIAGE"),
                factCap.capture());
        FraudFlagFact fact = factCap.getValue();
        assertThat(fact.getMemberId()).isEqualTo(memberId);
        assertThat(fact.getProviderId()).isEqualTo(providerId);
        assertThat(fact.getInsuranceLine()).isEqualTo("HEALTH");
        assertThat(fact.getClaimAmount()).isEqualByComparingTo("2500.00");
        assertThat(fact.getCurrencyCode()).isEqualTo("USD");
        assertThat(fact.getHistoricalMemberFlagCount()).isEqualTo(3L);
        assertThat(fact.getHistoricalProviderHighFlagCount()).isEqualTo(7L);
    }

    @Test
    void evaluateTriage_tenantRulesLoaded_claimNotFound_stillDispatches_zeroCounts() {
        FraudFlag flag = highRiskFlag(new BigDecimal("0.50"));
        flag.setClaimId(UUID.randomUUID());
        when(ruleEngine.hasRulesLoaded(TENANT_ID.toString())).thenReturn(true);
        when(claimRepository.findById(any(UUID.class))).thenReturn(Mono.empty());
        when(ruleEngine.evaluateInGroup(any(), any(), any(FraudFlagFact.class)))
                .thenReturn(List.of());

        StepVerifier.create(service.evaluateTriage(flag)
                        .contextWrite(ctx -> TenantContext.put(ctx, TENANT_ID.toString())))
                .verifyComplete();

        ArgumentCaptor<FraudFlagFact> factCap = ArgumentCaptor.forClass(FraudFlagFact.class);
        verify(ruleEngine).evaluateInGroup(any(), any(), factCap.capture());
        FraudFlagFact fact = factCap.getValue();
        assertThat(fact.getMemberId()).isNull();
        assertThat(fact.getProviderId()).isNull();
        assertThat(fact.getHistoricalMemberFlagCount()).isZero();
        assertThat(fact.getHistoricalProviderHighFlagCount()).isZero();
        // Neither historical query fired — the null-member/provider guard
        // short-circuited to Mono.just(0L).
        verify(fraudFlagRepository, never()).countHighRiskForMemberSince(any(), any());
        verify(fraudFlagRepository, never()).countHighRiskForProviderSince(any(), any());
    }

    @Test
    void evaluateTriage_defaultPolicyPath_skipsClaimAndCountLookups() {
        // No tenant rules → decideTriage short-circuits before enrichment.
        FraudFlag flag = highRiskFlag(new BigDecimal("0.90"));
        flag.setClaimId(UUID.randomUUID());
        when(caseRepo.count()).thenReturn(Mono.just(0L));
        when(fraudFlagService.linkToCase(any(FraudFlag.class), any(UUID.class)))
                .thenAnswer(inv -> Mono.just(inv.<FraudFlag>getArgument(0)));

        StepVerifier.create(service.evaluateTriage(flag)
                        .contextWrite(ctx -> TenantContext.put(ctx, TENANT_ID.toString())))
                .verifyComplete();

        verify(claimRepository, never()).findById(any(UUID.class));
        verify(fraudFlagRepository, never()).countHighRiskForMemberSince(any(), any());
    }

    // ── State machine transitions ───────────────────────────────────────

    @Test
    void startReview_openCase_transitionsToUnderReview() {
        UUID caseId = UUID.randomUUID();
        SiuCase existing = openCase(caseId);
        when(caseRepo.findById(caseId)).thenReturn(Mono.just(existing));

        StepVerifier.create(service.startReview(caseId, jwt("assessor@medfund.local")))
                .assertNext(saved -> assertThat(saved.getStatus()).isEqualTo("UNDER_REVIEW"))
                .verifyComplete();

        // Audit event captured with old→new
        ArgumentCaptor<AuditEvent> ev = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditPublisher).publish(ev.capture());
        assertThat(ev.getValue().action()).isEqualTo("UPDATE");
        assertThat(ev.getValue().oldValue()).containsEntry("status", "OPEN");
        assertThat(ev.getValue().newValue()).containsEntry("status", "UNDER_REVIEW");
        assertThat(ev.getValue().actorEmail()).isEqualTo("assessor@medfund.local");
        assertThat(ev.getValue().entityName()).isEqualTo(existing.getCaseNumber());
    }

    @Test
    void startReview_alreadyUnderReview_errorsIllegalState() {
        UUID caseId = UUID.randomUUID();
        SiuCase existing = openCase(caseId);
        existing.setStatus("UNDER_REVIEW");
        when(caseRepo.findById(caseId)).thenReturn(Mono.just(existing));

        StepVerifier.create(service.startReview(caseId, jwt("a@medfund.local")))
                .expectError(IllegalStateException.class)
                .verify();
        verify(caseRepo, never()).save(any(SiuCase.class));
    }

    @Test
    void startReview_missingCase_errorsIllegalArgument() {
        UUID caseId = UUID.randomUUID();
        when(caseRepo.findById(caseId)).thenReturn(Mono.empty());

        StepVerifier.create(service.startReview(caseId, jwt("a@medfund.local")))
                .expectError(IllegalArgumentException.class)
                .verify();
    }

    // ── §B Phase 8 — assign + startReviewFromAssigned ───────────────────

    @Test
    void assign_openCase_transitionsAndPersistsAssignee() {
        UUID caseId = UUID.randomUUID();
        UUID assignee = UUID.randomUUID();
        SiuCase existing = openCase(caseId);
        when(caseRepo.findById(caseId)).thenReturn(Mono.just(existing));

        StepVerifier.create(service.assign(caseId, assignee, jwt("supervisor@medfund.local")))
                .assertNext(saved -> {
                    assertThat(saved.getStatus()).isEqualTo("ASSIGNED");
                    assertThat(saved.getAssignedTo()).isEqualTo(assignee);
                })
                .verifyComplete();

        ArgumentCaptor<AuditEvent> ev = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditPublisher).publish(ev.capture());
        assertThat(ev.getValue().oldValue()).containsEntry("status", "OPEN");
        assertThat(ev.getValue().newValue()).containsEntry("status", "ASSIGNED");
    }

    @Test
    void assign_alreadyAssigned_errorsIllegalState() {
        UUID caseId = UUID.randomUUID();
        SiuCase existing = openCase(caseId);
        existing.setStatus("ASSIGNED");
        when(caseRepo.findById(caseId)).thenReturn(Mono.just(existing));

        StepVerifier.create(service.assign(caseId, UUID.randomUUID(), jwt("a@medfund.local")))
                .expectError(IllegalStateException.class)
                .verify();
    }

    @Test
    void startReviewFromAssigned_transitionsCleanly() {
        UUID caseId = UUID.randomUUID();
        SiuCase existing = openCase(caseId);
        existing.setStatus("ASSIGNED");
        when(caseRepo.findById(caseId)).thenReturn(Mono.just(existing));

        StepVerifier.create(service.startReviewFromAssigned(caseId, jwt("investigator@medfund.local")))
                .assertNext(saved -> assertThat(saved.getStatus()).isEqualTo("UNDER_REVIEW"))
                .verifyComplete();
    }

    @Test
    void startReviewFromAssigned_notAssigned_errorsIllegalState() {
        UUID caseId = UUID.randomUUID();
        SiuCase existing = openCase(caseId); // OPEN, not ASSIGNED
        when(caseRepo.findById(caseId)).thenReturn(Mono.just(existing));

        StepVerifier.create(service.startReviewFromAssigned(caseId, jwt("a@medfund.local")))
                .expectError(IllegalStateException.class)
                .verify();
    }

    // ── §B Phase 8 — proposeClosure ─────────────────────────────────────

    @Test
    void proposeClosure_underReview_stagesProposedFieldsAndFlipsToPending() {
        UUID caseId = UUID.randomUUID();
        SiuCase existing = openCase(caseId);
        existing.setStatus("UNDER_REVIEW");
        when(caseRepo.findById(caseId)).thenReturn(Mono.just(existing));

        StepVerifier.create(service.proposeClosure(caseId, "CONFIRMED_FRAUD",
                        new BigDecimal("1500.0000"), "USD", "Duplicate submission",
                        jwt("investigator@medfund.local")))
                .assertNext(saved -> {
                    assertThat(saved.getStatus()).isEqualTo("PENDING_APPROVAL");
                    assertThat(saved.getProposedOutcome()).isEqualTo("CONFIRMED_FRAUD");
                    assertThat(saved.getProposedSavedAmount()).isEqualByComparingTo("1500.0000");
                    assertThat(saved.getProposedSavedCurrency()).isEqualTo("USD");
                    assertThat(saved.getProposedByEmail()).isEqualTo("investigator@medfund.local");
                    // Terminal fields stay null until approveClosure.
                    assertThat(saved.getClosedBy()).isNull();
                    assertThat(saved.getSavedAmount()).isNull();
                    assertThat(saved.getOutcome()).isNull();
                })
                .verifyComplete();
    }

    @Test
    void proposeClosure_missingSavings_errors() {
        StepVerifier.create(service.proposeClosure(UUID.randomUUID(),
                        "CONFIRMED_FRAUD", null, "USD", "reason", jwt("a@medfund.local")))
                .expectError(IllegalArgumentException.class)
                .verify();
        StepVerifier.create(service.proposeClosure(UUID.randomUUID(),
                        "CONFIRMED_FRAUD", new BigDecimal("1"), null, "reason", jwt("a@medfund.local")))
                .expectError(IllegalArgumentException.class)
                .verify();
        verify(caseRepo, never()).findById(any(UUID.class));
    }

    @Test
    void proposeClosure_dismissalOutcome_rejectedAsIllegalArgument() {
        // Dismissals must not go through the four-eyes lane per FR6.
        StepVerifier.create(service.proposeClosure(UUID.randomUUID(),
                        "DISMISSED_FALSE_POSITIVE",
                        new BigDecimal("1"), "USD", "reason", jwt("a@medfund.local")))
                .expectError(IllegalArgumentException.class)
                .verify();
    }

    @Test
    void proposeClosure_notUnderReview_errorsIllegalState() {
        UUID caseId = UUID.randomUUID();
        SiuCase existing = openCase(caseId); // OPEN
        when(caseRepo.findById(caseId)).thenReturn(Mono.just(existing));

        StepVerifier.create(service.proposeClosure(caseId,
                        "CONFIRMED_FRAUD", new BigDecimal("1"), "USD", "reason",
                        jwt("a@medfund.local")))
                .expectError(IllegalStateException.class)
                .verify();
    }

    // ── §B Phase 8 — approveClosure (four-eyes gate) ────────────────────

    @Test
    void approveClosure_differentActor_confirmedFraud_transitionsCorrectly() {
        UUID caseId = UUID.randomUUID();
        SiuCase existing = pendingApprovalCase(caseId, "CONFIRMED_FRAUD",
                new BigDecimal("1500.0000"), "USD", "Duplicate submission",
                UUID.randomUUID(), "investigator@medfund.local");
        when(caseRepo.findById(caseId)).thenReturn(Mono.just(existing));

        StepVerifier.create(service.approveClosure(caseId, jwt("supervisor@medfund.local")))
                .assertNext(saved -> {
                    assertThat(saved.getStatus()).isEqualTo("CLOSED_CONFIRMED_FRAUD");
                    assertThat(saved.getOutcome()).isEqualTo("CONFIRMED_FRAUD");
                    assertThat(saved.getSavedAmount()).isEqualByComparingTo("1500.0000");
                    assertThat(saved.getSavedCurrency()).isEqualTo("USD");
                    assertThat(saved.getClosedByEmail()).isEqualTo("supervisor@medfund.local");
                    assertThat(saved.getClosureReason()).isEqualTo("Duplicate submission");
                })
                .verifyComplete();
    }

    @Test
    void approveClosure_referredLawEnforcement_mapsToClosedReferredStatus() {
        UUID caseId = UUID.randomUUID();
        SiuCase existing = pendingApprovalCase(caseId, "REFERRED_LAW_ENFORCEMENT",
                new BigDecimal("500.0000"), "ZWL", "Referred to ZRP",
                UUID.randomUUID(), "investigator@medfund.local");
        when(caseRepo.findById(caseId)).thenReturn(Mono.just(existing));

        StepVerifier.create(service.approveClosure(caseId, jwt("supervisor@medfund.local")))
                .assertNext(saved -> assertThat(saved.getStatus())
                        .isEqualTo("CLOSED_REFERRED_LAW_ENFORCEMENT"))
                .verifyComplete();
    }

    @Test
    void approveClosure_actionTaken_mapsToClosedActionTaken() {
        UUID caseId = UUID.randomUUID();
        SiuCase existing = pendingApprovalCase(caseId, "ACTION_TAKEN",
                new BigDecimal("250.0000"), "USD", "Provider warning issued",
                UUID.randomUUID(), "investigator@medfund.local");
        when(caseRepo.findById(caseId)).thenReturn(Mono.just(existing));

        StepVerifier.create(service.approveClosure(caseId, jwt("supervisor@medfund.local")))
                .assertNext(saved -> assertThat(saved.getStatus()).isEqualTo("CLOSED_ACTION_TAKEN"))
                .verifyComplete();
    }

    @Test
    void approveClosure_rejectsSelfApproval() {
        // Four-eyes gate — same actor cannot approve their own proposal.
        UUID caseId = UUID.randomUUID();
        UUID proposerId = UUID.randomUUID();
        SiuCase existing = pendingApprovalCase(caseId, "CONFIRMED_FRAUD",
                new BigDecimal("1"), "USD", "reason",
                proposerId, "investigator@medfund.local");
        when(caseRepo.findById(caseId)).thenReturn(Mono.just(existing));

        // Reuse the SAME proposer UUID as the JWT sub via a bespoke Jwt.
        Jwt sameActorJwt = new Jwt(
                "token", Instant.now(), Instant.now().plusSeconds(300),
                Map.of("alg", "none"),
                Map.of("sub", proposerId.toString(), "email", "investigator@medfund.local"));

        StepVerifier.create(service.approveClosure(caseId, sameActorJwt))
                .expectErrorSatisfies(err -> {
                    assertThat(err).isInstanceOf(IllegalStateException.class);
                    assertThat(err.getMessage()).contains("four-eyes violation");
                })
                .verify();
        verify(caseRepo, never()).save(any(SiuCase.class));
    }

    @Test
    void approveClosure_notPending_errorsIllegalState() {
        UUID caseId = UUID.randomUUID();
        SiuCase existing = openCase(caseId); // OPEN, not PENDING_APPROVAL
        when(caseRepo.findById(caseId)).thenReturn(Mono.just(existing));

        StepVerifier.create(service.approveClosure(caseId, jwt("supervisor@medfund.local")))
                .expectError(IllegalStateException.class)
                .verify();
    }

    // ── §B Phase 8 — rejectClosure ──────────────────────────────────────

    @Test
    void rejectClosure_pending_clearsProposedAndBacksToUnderReview() {
        UUID caseId = UUID.randomUUID();
        SiuCase existing = pendingApprovalCase(caseId, "CONFIRMED_FRAUD",
                new BigDecimal("1"), "USD", "reason",
                UUID.randomUUID(), "investigator@medfund.local");
        when(caseRepo.findById(caseId)).thenReturn(Mono.just(existing));

        StepVerifier.create(service.rejectClosure(caseId, "Needs more evidence",
                        jwt("supervisor@medfund.local")))
                .assertNext(saved -> {
                    assertThat(saved.getStatus()).isEqualTo("UNDER_REVIEW");
                    assertThat(saved.getProposedBy()).isNull();
                    assertThat(saved.getProposedOutcome()).isNull();
                    assertThat(saved.getProposedSavedAmount()).isNull();
                })
                .verifyComplete();
    }

    @Test
    void rejectClosure_notPending_errorsIllegalState() {
        UUID caseId = UUID.randomUUID();
        SiuCase existing = openCase(caseId);
        existing.setStatus("UNDER_REVIEW");
        when(caseRepo.findById(caseId)).thenReturn(Mono.just(existing));

        StepVerifier.create(service.rejectClosure(caseId, "note", jwt("a@medfund.local")))
                .expectError(IllegalStateException.class)
                .verify();
    }

    // ── §B Phase 8 — reopen (transient REOPENED → UNDER_REVIEW) ─────────

    @Test
    void reopen_closedCase_flipsThroughReopenedToUnderReview_clearsClosureFields() {
        UUID caseId = UUID.randomUUID();
        SiuCase existing = openCase(caseId);
        existing.setStatus("CLOSED_CONFIRMED_FRAUD");
        existing.setOutcome("CONFIRMED_FRAUD");
        existing.setSavedAmount(new BigDecimal("1500.0000"));
        existing.setSavedCurrency("USD");
        existing.setClosureReason("Duplicate");
        existing.setClosedBy(UUID.randomUUID());
        existing.setClosedByEmail("supervisor@medfund.local");
        existing.setClosedAt(OffsetDateTime.now().minusDays(1));
        when(caseRepo.findById(caseId)).thenReturn(Mono.just(existing));

        StepVerifier.create(service.reopen(caseId, "New evidence surfaced",
                        jwt("investigator@medfund.local")))
                .assertNext(saved -> {
                    assertThat(saved.getStatus()).isEqualTo("UNDER_REVIEW");
                    // Closure fields cleared so the report doesn't double-count.
                    assertThat(saved.getOutcome()).isNull();
                    assertThat(saved.getSavedAmount()).isNull();
                    assertThat(saved.getSavedCurrency()).isNull();
                    assertThat(saved.getClosedBy()).isNull();
                })
                .verifyComplete();
    }

    @Test
    void reopen_notClosed_errorsIllegalState() {
        UUID caseId = UUID.randomUUID();
        SiuCase existing = openCase(caseId);
        existing.setStatus("UNDER_REVIEW");
        when(caseRepo.findById(caseId)).thenReturn(Mono.just(existing));

        StepVerifier.create(service.reopen(caseId, "why", jwt("a@medfund.local")))
                .expectError(IllegalStateException.class)
                .verify();
    }

    @Test
    void closeDismissed_underReview_transitionsWithoutSavings() {
        UUID caseId = UUID.randomUUID();
        SiuCase existing = openCase(caseId);
        existing.setStatus("UNDER_REVIEW");
        when(caseRepo.findById(caseId)).thenReturn(Mono.just(existing));

        StepVerifier.create(service.closeDismissed(caseId, "False alarm",
                        jwt("assessor@medfund.local")))
                .assertNext(saved -> {
                    assertThat(saved.getStatus()).isEqualTo("CLOSED_DISMISSED_FALSE_POSITIVE");
                    assertThat(saved.getOutcome()).isEqualTo("DISMISSED_FALSE_POSITIVE");
                    assertThat(saved.getSavedAmount()).isNull();
                    assertThat(saved.getSavedCurrency()).isNull();
                })
                .verifyComplete();
    }

    // ── Evidence + referrals (Phase 7) ─────────────────────────────────

    @Test
    void addEvidence_persistsRow_emitsAuditAndNote() {
        UUID caseId = UUID.randomUUID();
        SiuCase existing = openCase(caseId);
        when(caseRepo.findById(caseId)).thenReturn(Mono.just(existing));
        when(evidenceRepo.save(any(SiuEvidence.class))).thenAnswer(inv -> {
            SiuEvidence arg = inv.getArgument(0);
            if (arg.getId() == null) arg.setId(UUID.randomUUID());
            return Mono.just(arg);
        });

        StepVerifier.create(service.addEvidence(caseId,
                        "s3://bucket/evidence-1.pdf",
                        "Provider claim history dump",
                        "PROVIDER_RECORD",
                        jwt("officer@medfund.local")))
                .assertNext(ev -> {
                    assertThat(ev.getCaseId()).isEqualTo(caseId);
                    assertThat(ev.getFileServiceRef()).isEqualTo("s3://bucket/evidence-1.pdf");
                    assertThat(ev.getEvidenceType()).isEqualTo("PROVIDER_RECORD");
                    assertThat(ev.getUploadedByEmail()).isEqualTo("officer@medfund.local");
                    assertThat(ev.getUploadedAt()).isNotNull();
                })
                .verifyComplete();

        ArgumentCaptor<AuditEvent> auditCap = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditPublisher).publish(auditCap.capture());
        assertThat(auditCap.getValue().entityType()).isEqualTo("SIU_EVIDENCE");
        assertThat(auditCap.getValue().action()).isEqualTo("CREATE");
        assertThat(auditCap.getValue().newValue()).containsEntry("evidenceType", "PROVIDER_RECORD");
        assertThat(auditCap.getValue().entityName())
                .contains("PROVIDER_RECORD")
                .contains(existing.getCaseNumber());

        ArgumentCaptor<SiuCaseNote> noteCap = ArgumentCaptor.forClass(SiuCaseNote.class);
        verify(noteRepo).save(noteCap.capture());
        assertThat(noteCap.getValue().getNoteType()).isEqualTo("EVIDENCE_ADDED");
        assertThat(noteCap.getValue().getBody()).contains("PROVIDER_RECORD");
    }

    @Test
    void addEvidence_missingCase_errorsIllegalArgument() {
        UUID caseId = UUID.randomUUID();
        when(caseRepo.findById(caseId)).thenReturn(Mono.empty());

        StepVerifier.create(service.addEvidence(caseId, "ref", "d", "DOCUMENT",
                        jwt("a@medfund.local")))
                .expectError(IllegalArgumentException.class)
                .verify();
        verify(evidenceRepo, never()).save(any(SiuEvidence.class));
    }

    @Test
    void addReferral_persistsRow_emitsAuditAndNote() {
        UUID caseId = UUID.randomUUID();
        SiuCase existing = openCase(caseId);
        when(caseRepo.findById(caseId)).thenReturn(Mono.just(existing));
        when(referralRepo.save(any(SiuReferral.class))).thenAnswer(inv -> {
            SiuReferral arg = inv.getArgument(0);
            if (arg.getId() == null) arg.setId(UUID.randomUUID());
            return Mono.just(arg);
        });

        StepVerifier.create(service.addReferral(caseId,
                        "LAW_ENFORCEMENT",
                        "ZRP-2026-0042",
                        jwt("supervisor@medfund.local")))
                .assertNext(ref -> {
                    assertThat(ref.getCaseId()).isEqualTo(caseId);
                    assertThat(ref.getReferralTo()).isEqualTo("LAW_ENFORCEMENT");
                    assertThat(ref.getReferralReference()).isEqualTo("ZRP-2026-0042");
                    assertThat(ref.getReferredByEmail()).isEqualTo("supervisor@medfund.local");
                    assertThat(ref.getReferredAt()).isNotNull();
                })
                .verifyComplete();

        ArgumentCaptor<AuditEvent> auditCap = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditPublisher).publish(auditCap.capture());
        assertThat(auditCap.getValue().entityType()).isEqualTo("SIU_REFERRAL");
        assertThat(auditCap.getValue().action()).isEqualTo("CREATE");
        assertThat(auditCap.getValue().newValue())
                .containsEntry("referralTo", "LAW_ENFORCEMENT")
                .containsEntry("referralReference", "ZRP-2026-0042");

        ArgumentCaptor<SiuCaseNote> noteCap = ArgumentCaptor.forClass(SiuCaseNote.class);
        verify(noteRepo).save(noteCap.capture());
        assertThat(noteCap.getValue().getNoteType()).isEqualTo("REFERRAL_ADDED");
        assertThat(noteCap.getValue().getBody())
                .contains("LAW_ENFORCEMENT")
                .contains("ZRP-2026-0042");
    }

    @Test
    void addReferral_nullReference_omitsReferenceFromNoteBody() {
        UUID caseId = UUID.randomUUID();
        SiuCase existing = openCase(caseId);
        when(caseRepo.findById(caseId)).thenReturn(Mono.just(existing));
        when(referralRepo.save(any(SiuReferral.class))).thenAnswer(inv -> {
            SiuReferral arg = inv.getArgument(0);
            if (arg.getId() == null) arg.setId(UUID.randomUUID());
            return Mono.just(arg);
        });

        StepVerifier.create(service.addReferral(caseId, "REGULATOR", null,
                        jwt("a@medfund.local")))
                .expectNextCount(1)
                .verifyComplete();

        ArgumentCaptor<SiuCaseNote> noteCap = ArgumentCaptor.forClass(SiuCaseNote.class);
        verify(noteRepo).save(noteCap.capture());
        assertThat(noteCap.getValue().getBody())
                .contains("REGULATOR")
                .doesNotContain("(ref:");
    }

    // ── addNote helper ──────────────────────────────────────────────────

    @Test
    void addNote_persistsWithAllFields() {
        UUID caseId = UUID.randomUUID();
        UUID authorId = UUID.randomUUID();

        StepVerifier.create(service.addNote(caseId, authorId, "officer@medfund.local",
                        "COMMENT", "Provider records requested"))
                .assertNext(note -> {
                    assertThat(note.getCaseId()).isEqualTo(caseId);
                    assertThat(note.getAuthorId()).isEqualTo(authorId);
                    assertThat(note.getAuthorEmail()).isEqualTo("officer@medfund.local");
                    assertThat(note.getNoteType()).isEqualTo("COMMENT");
                    assertThat(note.getBody()).isEqualTo("Provider records requested");
                    assertThat(note.getCreatedAt()).isNotNull();
                })
                .verifyComplete();
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private FraudFlag highRiskFlag(BigDecimal score) {
        FraudFlag f = new FraudFlag();
        f.setId(UUID.randomUUID());
        f.setClaimId(UUID.randomUUID());
        f.setRiskLevel("HIGH");
        f.setRiskScore(score);
        f.setFlaggedAt(OffsetDateTime.now());
        return f;
    }

    private SiuCase openCase(UUID caseId) {
        OffsetDateTime now = OffsetDateTime.now();
        SiuCase k = new SiuCase();
        k.setId(caseId);
        k.setCaseNumber("SIU-2026-000001");
        k.setStatus("OPEN");
        k.setOpenedBy(SiuCaseService.SYSTEM_ACTOR);
        k.setOpenedByEmail(SiuCaseService.SYSTEM_EMAIL);
        k.setOpenedAt(now);
        k.setCreatedAt(now);
        k.setUpdatedAt(now);
        return k;
    }

    private SiuCase pendingApprovalCase(UUID caseId, String proposedOutcome,
                                         BigDecimal proposedAmount, String proposedCurrency,
                                         String proposedReason,
                                         UUID proposerId, String proposerEmail) {
        SiuCase k = openCase(caseId);
        k.setStatus("PENDING_APPROVAL");
        k.setProposedBy(proposerId);
        k.setProposedByEmail(proposerEmail);
        k.setProposedAt(OffsetDateTime.now().minusMinutes(5));
        k.setProposedOutcome(proposedOutcome);
        k.setProposedSavedAmount(proposedAmount);
        k.setProposedSavedCurrency(proposedCurrency);
        k.setProposedClosureReason(proposedReason);
        return k;
    }

    /**
     * Builds a Jwt with a random-UUID subject and the given email. The
     * subject must be a valid UUID because SiuCaseService parses
     * AuditActor.id(jwt) via UUID.fromString.
     */
    private Jwt jwt(String email) {
        return new Jwt(
                "token", Instant.now(), Instant.now().plusSeconds(300),
                Map.of("alg", "none"),
                Map.of("sub", UUID.randomUUID().toString(), "email", email));
    }
}
