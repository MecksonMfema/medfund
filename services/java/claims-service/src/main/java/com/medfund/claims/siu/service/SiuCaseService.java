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
import com.medfund.shared.audit.AuditActor;
import com.medfund.shared.audit.AuditEvent;
import com.medfund.shared.audit.AuditPublisher;
import com.medfund.shared.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Owns the SIU case lifecycle. §B Phase 8 widens the state machine to
 * the full 5-state model:
 * <pre>
 *   OPEN ──(assign)──▶ ASSIGNED ──(startReviewFromAssigned)──▶ UNDER_REVIEW
 *     │                                                            │
 *     └──(startReview MVP direct)──────────────────────────────────┘
 *                                                                  │
 *   UNDER_REVIEW ──(closeDismissed)──▶ CLOSED_DISMISSED_FALSE_POSITIVE
 *   UNDER_REVIEW ──(proposeClosure)──▶ PENDING_APPROVAL
 *
 *   PENDING_APPROVAL ──(approveClosure)──▶ CLOSED_CONFIRMED_FRAUD
 *                                        │ CLOSED_REFERRED_LAW_ENFORCEMENT
 *                                        └ CLOSED_ACTION_TAKEN
 *   PENDING_APPROVAL ──(rejectClosure)───▶ UNDER_REVIEW
 *
 *   CLOSED_* ──(reopen)──▶ REOPENED (transient) ──▶ UNDER_REVIEW
 * </pre>
 *
 * <p>{@link #approveClosure} enforces the four-eyes gate per FR6: the
 * supervisor performing the approval must be a different actor from the
 * investigator who proposed the closure. Dismissals skip the gate
 * ({@link #closeDismissed} stays single-step).
 *
 * <p>{@link #evaluateTriage(FraudFlag)} decides whether a freshly-persisted
 * {@link FraudFlag} should open a new case. Phase 19 §A Phase 5 adds a
 * per-tenant {@link TenantRuleEngine} dispatch on the {@code FRAUD_TRIAGE}
 * agenda group: if the tenant has FRAUD_TRIAGE rules configured, the fired
 * fact's {@link FraudFlagFact#isEmitCase} decides; otherwise (or when the
 * tenant hasn't loaded any rules for this category) the default policy
 * fires (auto-open when {@code risk_level = HIGH} and
 * {@code risk_score > fraud.triage.default-min-score}, default 0.85).
 *
 * <p>Every mutation emits an {@link AuditEvent} per Rule 8 with the actor's
 * email resolved via {@link AuditActor}. Auto-opened cases carry the
 * platform system-actor identity — never a null email — per
 * {@code feedback_audit_actor_email}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SiuCaseService {

    static final String ENTITY_TYPE = "SIU_CASE";
    static final String SYSTEM_EMAIL = "system@medfund.local";
    static final UUID SYSTEM_ACTOR = UUID.fromString("00000000-0000-0000-0000-000000000000");
    static final String AGENDA_GROUP = "FRAUD_TRIAGE";
    /**
     * Rolling lookback used by the §B Phase 9 repeat-offender +
     * provider-high-flag templates. 90 days matches the historical
     * fraud-detection industry norm and is coupled to the templates'
     * {@code minCount} default; tune both together if changing.
     */
    static final long HISTORICAL_LOOKBACK_DAYS = 90;

    private final SiuCaseRepository caseRepo;
    private final SiuCaseNoteRepository noteRepo;
    private final SiuEvidenceRepository evidenceRepo;
    private final SiuReferralRepository referralRepo;
    private final FraudFlagService fraudFlagService;
    private final AuditPublisher auditPublisher;
    private final TenantRuleEngine ruleEngine;
    private final ClaimRepository claimRepository;
    private final FraudFlagRepository fraudFlagRepository;

    @Value("${fraud.triage.default-min-score:0.85}")
    private BigDecimal defaultMinScore;

    // ── Triage entry point (called by FraudFlaggedConsumer) ─────────────

    /**
     * Decide whether to open a case for a freshly-persisted flag.
     *
     * <p>Reads the tenant id from the reactor context (set by
     * {@code FraudFlaggedConsumer.processEvent}). If the tenant has
     * {@code FRAUD_TRIAGE} rules loaded, evaluate them and honour
     * {@link FraudFlagFact#isEmitCase}. Otherwise fall through to the
     * default policy (HIGH + risk_score > defaultMinScore).
     *
     * <p>Rules-engine dispatch is blocking; wrapped in
     * {@code Mono.fromCallable(...).subscribeOn(Schedulers.boundedElastic())}
     * so it doesn't stall the Kafka receive thread.
     */
    public Mono<Void> evaluateTriage(FraudFlag flag) {
        return Mono.deferContextual(ctx -> {
            String tenantId = TenantContext.get(ctx);
            return decideTriage(flag, tenantId)
                    .flatMap(shouldOpen -> shouldOpen
                            ? openCaseForFlag(flag).then()
                            : Mono.empty());
        });
    }

    private Mono<Boolean> decideTriage(FraudFlag flag, String tenantId) {
        if (tenantId == null || !ruleEngine.hasRulesLoaded(tenantId)) {
            // No tenant rules → default policy path (skips enrichment;
            // default policy only reads riskLevel + riskScore off the flag).
            return Mono.just(shouldOpenByDefault(flag));
        }
        // Enrich the fact with claim + historical counts, then dispatch to
        // the tenant's FRAUD_TRIAGE agenda group. If no rule fires (empty
        // rule set for this category), fall back to the default policy so
        // tenants authoring rules for other categories aren't silently
        // deprived of the default fraud triage.
        return buildEnrichedFact(flag)
                .flatMap(fact -> Mono.fromCallable(() -> {
                            ruleEngine.evaluateInGroup(tenantId, AGENDA_GROUP, fact);
                            return fact.isEmitCase() || shouldOpenByDefault(flag);
                        })
                        .subscribeOn(Schedulers.boundedElastic()));
    }

    /**
     * Hydrate a {@link FraudFlagFact} with the claim-side context
     * (member/provider/insuranceLine/claimAmount/currency) and the two
     * historical counts feeding the §B Phase 9 pattern-recognition
     * templates. All lookups short-circuit safely on null keys: an
     * asset-line claim without a memberId leaves
     * {@code historicalMemberFlagCount = 0} rather than probing an
     * unindexed range scan.
     */
    private Mono<FraudFlagFact> buildEnrichedFact(FraudFlag flag) {
        Mono<Claim> claimMono = flag.getClaimId() != null
                ? claimRepository.findById(flag.getClaimId())
                        .switchIfEmpty(Mono.fromRunnable(() -> log.warn(
                                "fraud_flag {} references missing claim {} - pattern templates degrade",
                                flag.getId(), flag.getClaimId())))
                : Mono.empty();
        return claimMono
                .defaultIfEmpty(new Claim())
                .flatMap(claim -> countsFor(claim)
                        .map(counts -> FraudFlagFact.builder()
                                .riskScore(flag.getRiskScore())
                                .riskLevel(flag.getRiskLevel())
                                .memberId(claim.getMemberId())
                                .providerId(claim.getProviderId())
                                .insuranceLine(claim.getInsuranceLine())
                                .claimAmount(claim.getClaimedAmount() != null
                                        ? claim.getClaimedAmount() : BigDecimal.ZERO)
                                .currencyCode(claim.getCurrencyCode())
                                .flaggedAt(flag.getFlaggedAt())
                                .historicalMemberFlagCount(counts.getT1())
                                .historicalProviderHighFlagCount(counts.getT2())
                                .build()));
    }

    private Mono<reactor.util.function.Tuple2<Long, Long>> countsFor(Claim claim) {
        OffsetDateTime since = OffsetDateTime.now().minus(HISTORICAL_LOOKBACK_DAYS, ChronoUnit.DAYS);
        Mono<Long> memberCount = claim.getMemberId() != null
                ? fraudFlagRepository.countHighRiskForMemberSince(claim.getMemberId(), since)
                        .defaultIfEmpty(0L)
                : Mono.just(0L);
        Mono<Long> providerCount = claim.getProviderId() != null
                ? fraudFlagRepository.countHighRiskForProviderSince(claim.getProviderId(), since)
                        .defaultIfEmpty(0L)
                : Mono.just(0L);
        return Mono.zip(memberCount, providerCount);
    }

    private boolean shouldOpenByDefault(FraudFlag flag) {
        return "HIGH".equals(flag.getRiskLevel())
                && flag.getRiskScore() != null
                && flag.getRiskScore().compareTo(defaultMinScore) > 0;
    }

    private Mono<SiuCase> openCaseForFlag(FraudFlag flag) {
        OffsetDateTime now = OffsetDateTime.now();
        // NOTE — case_number generation via count() is race-prone under
        // concurrent auto-open. MVP-acceptable because auto-opens are rare
        // enough that a UNIQUE-index collision on the (very rare) conflict
        // path retries at the consumer via ack-on-error. Phase 7/8 replaces
        // this with a Postgres sequence per tenant.
        return caseRepo.count()
                .map(existing -> String.format("SIU-%d-%06d",
                        now.getYear(), existing + 1))
                .flatMap(caseNumber -> {
                    SiuCase kase = new SiuCase();
                    kase.setCaseNumber(caseNumber);
                    kase.setStatus("OPEN");
                    kase.setPriority("MEDIUM");
                    kase.setOpenedBy(SYSTEM_ACTOR);
                    kase.setOpenedByEmail(SYSTEM_EMAIL);
                    kase.setOpenedAt(now);
                    kase.setCreatedAt(now);
                    kase.setUpdatedAt(now);
                    return caseRepo.save(kase)
                            .flatMap(saved -> auditCreate(saved, SYSTEM_ACTOR, SYSTEM_EMAIL)
                                    .thenReturn(saved))
                            .flatMap(saved -> fraudFlagService.linkToCase(flag, saved.getId())
                                    .thenReturn(saved))
                            .flatMap(saved -> addNote(saved.getId(), SYSTEM_ACTOR, SYSTEM_EMAIL,
                                    "STATUS_CHANGE",
                                    "Case auto-opened from fraud_flag " + flag.getId())
                                    .thenReturn(saved));
                });
    }

    // ── State-machine transitions (MVP 3-state) ─────────────────────────

    public Mono<SiuCase> startReview(UUID caseId, Jwt jwt) {
        String actorEmail = AuditActor.email(jwt);
        UUID actorId = UUID.fromString(AuditActor.id(jwt));
        return caseRepo.findById(caseId)
                .switchIfEmpty(Mono.error(new IllegalArgumentException(
                        "case not found: " + caseId)))
                .flatMap(kase -> {
                    requireStatus(kase, "OPEN");
                    kase.setStatus("UNDER_REVIEW");
                    kase.setUpdatedAt(OffsetDateTime.now());
                    return caseRepo.save(kase)
                            .flatMap(saved -> auditTransition(saved, "OPEN", "UNDER_REVIEW",
                                    actorId, actorEmail).thenReturn(saved))
                            .flatMap(saved -> addNote(caseId, actorId, actorEmail,
                                    "STATUS_CHANGE", "OPEN → UNDER_REVIEW")
                                    .thenReturn(saved));
                });
    }

    /**
     * OPEN → ASSIGNED. Requires {@code claims:siu:assign} at the controller.
     * Sets {@code assigned_to} to the picked officer's id; the assignee is
     * captured for audit but the acting user (from JWT) is who owns the
     * transition on the audit trail.
     */
    public Mono<SiuCase> assign(UUID caseId, UUID assigneeId, Jwt jwt) {
        String actorEmail = AuditActor.email(jwt);
        UUID actorId = UUID.fromString(AuditActor.id(jwt));
        return caseRepo.findById(caseId)
                .switchIfEmpty(Mono.error(new IllegalArgumentException(
                        "case not found: " + caseId)))
                .flatMap(kase -> {
                    requireStatus(kase, "OPEN");
                    kase.setStatus("ASSIGNED");
                    kase.setAssignedTo(assigneeId);
                    kase.setUpdatedAt(OffsetDateTime.now());
                    return caseRepo.save(kase)
                            .flatMap(saved -> auditTransition(saved, "OPEN", "ASSIGNED",
                                    actorId, actorEmail).thenReturn(saved))
                            .flatMap(saved -> addNote(caseId, actorId, actorEmail,
                                    "ASSIGNED",
                                    "OPEN → ASSIGNED (assignee=" + assigneeId + ")")
                                    .thenReturn(saved));
                });
    }

    /** ASSIGNED → UNDER_REVIEW. Complements the MVP {@link #startReview}. */
    public Mono<SiuCase> startReviewFromAssigned(UUID caseId, Jwt jwt) {
        String actorEmail = AuditActor.email(jwt);
        UUID actorId = UUID.fromString(AuditActor.id(jwt));
        return caseRepo.findById(caseId)
                .switchIfEmpty(Mono.error(new IllegalArgumentException(
                        "case not found: " + caseId)))
                .flatMap(kase -> {
                    requireStatus(kase, "ASSIGNED");
                    kase.setStatus("UNDER_REVIEW");
                    kase.setUpdatedAt(OffsetDateTime.now());
                    return caseRepo.save(kase)
                            .flatMap(saved -> auditTransition(saved, "ASSIGNED", "UNDER_REVIEW",
                                    actorId, actorEmail).thenReturn(saved))
                            .flatMap(saved -> addNote(caseId, actorId, actorEmail,
                                    "STATUS_CHANGE", "ASSIGNED → UNDER_REVIEW")
                                    .thenReturn(saved));
                });
    }

    /**
     * UNDER_REVIEW → PENDING_APPROVAL. Stages the proposed outcome on the
     * four-eyes columns; the terminal {@code closed_*} fields stay null
     * until {@link #approveClosure} copies them over.
     *
     * <p>{@code outcome} must be one of
     * {@code CONFIRMED_FRAUD | REFERRED_LAW_ENFORCEMENT | ACTION_TAKEN}.
     * Dismissals do NOT go through this lane — call
     * {@link #closeDismissed} directly per FR6.
     */
    public Mono<SiuCase> proposeClosure(UUID caseId, String outcome,
                                         BigDecimal savedAmount, String savedCurrency,
                                         String closureReason, Jwt jwt) {
        if (outcome == null || outcome.isBlank()) {
            return Mono.error(new IllegalArgumentException(
                    "outcome is required (CONFIRMED_FRAUD|REFERRED_LAW_ENFORCEMENT|ACTION_TAKEN)"));
        }
        if (!isNonDismissalOutcome(outcome)) {
            return Mono.error(new IllegalArgumentException(
                    "unexpected outcome for proposeClosure: " + outcome));
        }
        if (savedAmount == null || savedCurrency == null || savedCurrency.isBlank()) {
            return Mono.error(new IllegalArgumentException(
                    "proposeClosure requires savedAmount + savedCurrency"));
        }
        String actorEmail = AuditActor.email(jwt);
        UUID actorId = UUID.fromString(AuditActor.id(jwt));
        return caseRepo.findById(caseId)
                .switchIfEmpty(Mono.error(new IllegalArgumentException(
                        "case not found: " + caseId)))
                .flatMap(kase -> {
                    requireStatus(kase, "UNDER_REVIEW");
                    OffsetDateTime now = OffsetDateTime.now();
                    kase.setStatus("PENDING_APPROVAL");
                    kase.setProposedBy(actorId);
                    kase.setProposedByEmail(actorEmail);
                    kase.setProposedAt(now);
                    kase.setProposedOutcome(outcome);
                    kase.setProposedSavedAmount(savedAmount);
                    kase.setProposedSavedCurrency(savedCurrency);
                    kase.setProposedClosureReason(closureReason);
                    kase.setUpdatedAt(now);
                    return caseRepo.save(kase)
                            .flatMap(saved -> auditTransition(saved, "UNDER_REVIEW", "PENDING_APPROVAL",
                                    actorId, actorEmail).thenReturn(saved))
                            .flatMap(saved -> addNote(caseId, actorId, actorEmail,
                                    "STATUS_CHANGE",
                                    "UNDER_REVIEW → PENDING_APPROVAL (proposed: " + outcome + ")")
                                    .thenReturn(saved));
                });
    }

    /**
     * PENDING_APPROVAL → CLOSED_*. Enforces the four-eyes gate: the
     * approver must be a different actor from the proposer per FR6.
     * Copies the {@code proposed_*} staging fields onto the terminal
     * {@code closed_*} columns and clears them.
     */
    public Mono<SiuCase> approveClosure(UUID caseId, Jwt jwt) {
        String approverEmail = AuditActor.email(jwt);
        UUID approverId = UUID.fromString(AuditActor.id(jwt));
        return caseRepo.findById(caseId)
                .switchIfEmpty(Mono.error(new IllegalArgumentException(
                        "case not found: " + caseId)))
                .flatMap(kase -> {
                    requireStatus(kase, "PENDING_APPROVAL");
                    if (approverId.equals(kase.getProposedBy())) {
                        return Mono.error(new IllegalStateException(
                                "four-eyes violation: approver " + approverEmail
                                        + " cannot approve their own proposal " + kase.getProposedBy()));
                    }
                    String targetStatus = mapOutcomeToStatus(kase.getProposedOutcome());
                    OffsetDateTime now = OffsetDateTime.now();
                    kase.setStatus(targetStatus);
                    kase.setOutcome(kase.getProposedOutcome());
                    kase.setSavedAmount(kase.getProposedSavedAmount());
                    kase.setSavedCurrency(kase.getProposedSavedCurrency());
                    kase.setClosureReason(kase.getProposedClosureReason());
                    kase.setClosedBy(approverId);
                    kase.setClosedByEmail(approverEmail);
                    kase.setClosedAt(now);
                    kase.setUpdatedAt(now);
                    return caseRepo.save(kase)
                            .flatMap(saved -> auditTransition(saved, "PENDING_APPROVAL", targetStatus,
                                    approverId, approverEmail).thenReturn(saved))
                            .flatMap(saved -> addNote(caseId, approverId, approverEmail,
                                    "STATUS_CHANGE",
                                    "PENDING_APPROVAL → " + targetStatus + " (approved by supervisor)")
                                    .thenReturn(saved));
                });
    }

    /** PENDING_APPROVAL → UNDER_REVIEW. Supervisor rejects the proposal. */
    public Mono<SiuCase> rejectClosure(UUID caseId, String rejectionNote, Jwt jwt) {
        String actorEmail = AuditActor.email(jwt);
        UUID actorId = UUID.fromString(AuditActor.id(jwt));
        return caseRepo.findById(caseId)
                .switchIfEmpty(Mono.error(new IllegalArgumentException(
                        "case not found: " + caseId)))
                .flatMap(kase -> {
                    requireStatus(kase, "PENDING_APPROVAL");
                    OffsetDateTime now = OffsetDateTime.now();
                    kase.setStatus("UNDER_REVIEW");
                    // Clear the four-eyes staging fields — a fresh proposal
                    // starts from a clean slate.
                    kase.setProposedBy(null);
                    kase.setProposedByEmail(null);
                    kase.setProposedAt(null);
                    kase.setProposedOutcome(null);
                    kase.setProposedSavedAmount(null);
                    kase.setProposedSavedCurrency(null);
                    kase.setProposedClosureReason(null);
                    kase.setUpdatedAt(now);
                    return caseRepo.save(kase)
                            .flatMap(saved -> auditTransition(saved, "PENDING_APPROVAL", "UNDER_REVIEW",
                                    actorId, actorEmail).thenReturn(saved))
                            .flatMap(saved -> addNote(caseId, actorId, actorEmail,
                                    "STATUS_CHANGE",
                                    "PENDING_APPROVAL → UNDER_REVIEW (rejected by supervisor): "
                                            + (rejectionNote == null ? "" : rejectionNote))
                                    .thenReturn(saved));
                });
    }

    /**
     * CLOSED_* → UNDER_REVIEW via a transient REOPENED emission.
     * Persists REOPENED first (so the timeline records the state), then
     * immediately flips to UNDER_REVIEW so investigators can act on it.
     * Clears {@code closed_*} + {@code outcome} + {@code saved_*} so the
     * report doesn't double-count savings from the prior closure.
     */
    public Mono<SiuCase> reopen(UUID caseId, String reopenReason, Jwt jwt) {
        String actorEmail = AuditActor.email(jwt);
        UUID actorId = UUID.fromString(AuditActor.id(jwt));
        return caseRepo.findById(caseId)
                .switchIfEmpty(Mono.error(new IllegalArgumentException(
                        "case not found: " + caseId)))
                .flatMap(kase -> {
                    if (!kase.getStatus().startsWith("CLOSED_")) {
                        return Mono.error(new IllegalStateException(
                                "case " + kase.getId() + " is " + kase.getStatus()
                                        + "; expected CLOSED_*"));
                    }
                    String priorStatus = kase.getStatus();
                    OffsetDateTime now = OffsetDateTime.now();
                    // Transient REOPENED — persisted only long enough to
                    // emit the audit + timeline note. The flip to
                    // UNDER_REVIEW below overwrites it in the same call.
                    kase.setStatus("REOPENED");
                    kase.setOutcome(null);
                    kase.setSavedAmount(null);
                    kase.setSavedCurrency(null);
                    kase.setClosureReason(null);
                    kase.setClosedBy(null);
                    kase.setClosedByEmail(null);
                    kase.setClosedAt(null);
                    kase.setUpdatedAt(now);
                    return caseRepo.save(kase)
                            .flatMap(saved -> auditTransition(saved, priorStatus, "REOPENED",
                                    actorId, actorEmail).thenReturn(saved))
                            .flatMap(saved -> addNote(caseId, actorId, actorEmail,
                                    "STATUS_CHANGE",
                                    priorStatus + " → REOPENED: "
                                            + (reopenReason == null ? "" : reopenReason))
                                    .thenReturn(saved))
                            .flatMap(saved -> {
                                saved.setStatus("UNDER_REVIEW");
                                saved.setUpdatedAt(OffsetDateTime.now());
                                return caseRepo.save(saved);
                            })
                            .flatMap(saved -> auditTransition(saved, "REOPENED", "UNDER_REVIEW",
                                    actorId, actorEmail).thenReturn(saved))
                            .flatMap(saved -> addNote(caseId, actorId, actorEmail,
                                    "STATUS_CHANGE", "REOPENED → UNDER_REVIEW")
                                    .thenReturn(saved));
                });
    }

    public Mono<SiuCase> closeDismissed(UUID caseId, String closureReason, Jwt jwt) {
        String actorEmail = AuditActor.email(jwt);
        UUID actorId = UUID.fromString(AuditActor.id(jwt));
        return caseRepo.findById(caseId)
                .switchIfEmpty(Mono.error(new IllegalArgumentException(
                        "case not found: " + caseId)))
                .flatMap(kase -> {
                    requireStatus(kase, "UNDER_REVIEW");
                    String oldStatus = kase.getStatus();
                    OffsetDateTime now = OffsetDateTime.now();
                    kase.setStatus("CLOSED_DISMISSED_FALSE_POSITIVE");
                    kase.setOutcome("DISMISSED_FALSE_POSITIVE");
                    kase.setSavedAmount(null);
                    kase.setSavedCurrency(null);
                    kase.setClosureReason(closureReason);
                    kase.setClosedBy(actorId);
                    kase.setClosedByEmail(actorEmail);
                    kase.setClosedAt(now);
                    kase.setUpdatedAt(now);
                    return caseRepo.save(kase)
                            .flatMap(saved -> auditTransition(saved, oldStatus,
                                    "CLOSED_DISMISSED_FALSE_POSITIVE",
                                    actorId, actorEmail).thenReturn(saved))
                            .flatMap(saved -> addNote(caseId, actorId, actorEmail,
                                    "STATUS_CHANGE",
                                    oldStatus + " → CLOSED_DISMISSED_FALSE_POSITIVE: " + closureReason)
                                    .thenReturn(saved));
                });
    }

    private void requireStatus(SiuCase kase, String expected) {
        if (!expected.equals(kase.getStatus())) {
            throw new IllegalStateException(
                    "case " + kase.getId() + " is " + kase.getStatus()
                            + "; expected " + expected);
        }
    }

    private static boolean isNonDismissalOutcome(String outcome) {
        return "CONFIRMED_FRAUD".equals(outcome)
                || "REFERRED_LAW_ENFORCEMENT".equals(outcome)
                || "ACTION_TAKEN".equals(outcome);
    }

    private static String mapOutcomeToStatus(String outcome) {
        return switch (outcome) {
            case "CONFIRMED_FRAUD"          -> "CLOSED_CONFIRMED_FRAUD";
            case "REFERRED_LAW_ENFORCEMENT" -> "CLOSED_REFERRED_LAW_ENFORCEMENT";
            case "ACTION_TAKEN"             -> "CLOSED_ACTION_TAKEN";
            case null, default -> throw new IllegalStateException(
                    "unexpected proposed outcome: " + outcome);
        };
    }

    // ── Evidence + referrals (§B Phase 7) ───────────────────────────────

    /**
     * Attach an uploaded evidence artifact (file bytes live in file-service;
     * this row carries the handle). Emits an {@code EVIDENCE_ADDED} note +
     * an {@code AuditEvent} per Rule 8.
     */
    public Mono<SiuEvidence> addEvidence(UUID caseId, String fileServiceRef,
                                         String description, String evidenceType,
                                         Jwt jwt) {
        String actorEmail = AuditActor.email(jwt);
        UUID actorId = UUID.fromString(AuditActor.id(jwt));
        return caseRepo.findById(caseId)
                .switchIfEmpty(Mono.error(new IllegalArgumentException(
                        "case not found: " + caseId)))
                .flatMap(kase -> {
                    SiuEvidence ev = new SiuEvidence();
                    ev.setCaseId(caseId);
                    ev.setFileServiceRef(fileServiceRef);
                    ev.setDescription(description);
                    ev.setEvidenceType(evidenceType);
                    ev.setUploadedBy(actorId);
                    ev.setUploadedByEmail(actorEmail);
                    ev.setUploadedAt(OffsetDateTime.now());
                    return evidenceRepo.save(ev)
                            .flatMap(saved -> auditEvidence(kase, saved, actorId, actorEmail)
                                    .thenReturn(saved))
                            .flatMap(saved -> addNote(caseId, actorId, actorEmail,
                                    "EVIDENCE_ADDED",
                                    evidenceType + " · " + description).thenReturn(saved));
                });
    }

    /**
     * Record an external referral (law enforcement / regulator / HR). No
     * automated push to the external body — Phase 19.5 wires that.
     * Emits a {@code REFERRAL_ADDED} note + {@code AuditEvent}.
     */
    public Mono<SiuReferral> addReferral(UUID caseId, String referralTo,
                                          String referralReference, Jwt jwt) {
        String actorEmail = AuditActor.email(jwt);
        UUID actorId = UUID.fromString(AuditActor.id(jwt));
        return caseRepo.findById(caseId)
                .switchIfEmpty(Mono.error(new IllegalArgumentException(
                        "case not found: " + caseId)))
                .flatMap(kase -> {
                    SiuReferral ref = new SiuReferral();
                    ref.setCaseId(caseId);
                    ref.setReferralTo(referralTo);
                    ref.setReferralReference(referralReference);
                    ref.setReferredBy(actorId);
                    ref.setReferredByEmail(actorEmail);
                    ref.setReferredAt(OffsetDateTime.now());
                    return referralRepo.save(ref)
                            .flatMap(saved -> auditReferral(kase, saved, actorId, actorEmail)
                                    .thenReturn(saved))
                            .flatMap(saved -> addNote(caseId, actorId, actorEmail,
                                    "REFERRAL_ADDED",
                                    "Referred to " + referralTo
                                            + (referralReference != null && !referralReference.isBlank()
                                                    ? " (ref: " + referralReference + ")" : ""))
                                    .thenReturn(saved));
                });
    }

    // ── Notes + audit helpers ───────────────────────────────────────────

    public Mono<SiuCaseNote> addNote(UUID caseId, UUID authorId, String authorEmail,
                                      String noteType, String body) {
        SiuCaseNote note = new SiuCaseNote();
        note.setCaseId(caseId);
        note.setAuthorId(authorId);
        note.setAuthorEmail(authorEmail);
        note.setNoteType(noteType);
        note.setBody(body);
        note.setCreatedAt(OffsetDateTime.now());
        return noteRepo.save(note);
    }

    private Mono<Void> auditCreate(SiuCase kase, UUID actorId, String actorEmail) {
        Map<String, Object> newValue = new LinkedHashMap<>();
        newValue.put("status", kase.getStatus());
        newValue.put("caseNumber", kase.getCaseNumber());
        newValue.put("priority", kase.getPriority());
        return Mono.deferContextual(ctx -> {
            String tenantId = TenantContext.get(ctx);
            AuditEvent event = AuditEvent.create(
                    tenantId != null ? tenantId : "unknown",
                    ENTITY_TYPE,
                    kase.getId().toString(),
                    kase.getCaseNumber(),
                    "CREATE",
                    actorId.toString(),
                    actorEmail,
                    null,
                    newValue,
                    new String[]{"status", "caseNumber"},
                    UUID.randomUUID().toString()
            );
            return auditPublisher.publish(event);
        });
    }

    private Mono<Void> auditTransition(SiuCase kase, String oldStatus, String newStatus,
                                        UUID actorId, String actorEmail) {
        Map<String, Object> oldValue = Map.of("status", oldStatus);
        Map<String, Object> newValue = Map.of("status", newStatus);
        return Mono.deferContextual(ctx -> {
            String tenantId = TenantContext.get(ctx);
            AuditEvent event = AuditEvent.create(
                    tenantId != null ? tenantId : "unknown",
                    ENTITY_TYPE,
                    kase.getId().toString(),
                    kase.getCaseNumber(),
                    "UPDATE",
                    actorId.toString(),
                    actorEmail,
                    oldValue,
                    newValue,
                    new String[]{"status"},
                    UUID.randomUUID().toString()
            );
            return auditPublisher.publish(event);
        });
    }

    private Mono<Void> auditEvidence(SiuCase kase, SiuEvidence ev,
                                      UUID actorId, String actorEmail) {
        Map<String, Object> newValue = new LinkedHashMap<>();
        newValue.put("evidenceType", ev.getEvidenceType());
        newValue.put("description", ev.getDescription());
        newValue.put("fileServiceRef", ev.getFileServiceRef());
        return Mono.deferContextual(ctx -> {
            String tenantId = TenantContext.get(ctx);
            AuditEvent event = AuditEvent.create(
                    tenantId != null ? tenantId : "unknown",
                    "SIU_EVIDENCE",
                    ev.getId().toString(),
                    kase.getCaseNumber() + " · " + ev.getEvidenceType(),
                    "CREATE",
                    actorId.toString(),
                    actorEmail,
                    null,
                    newValue,
                    new String[]{"evidenceType", "description", "fileServiceRef"},
                    UUID.randomUUID().toString()
            );
            return auditPublisher.publish(event);
        });
    }

    private Mono<Void> auditReferral(SiuCase kase, SiuReferral ref,
                                      UUID actorId, String actorEmail) {
        Map<String, Object> newValue = new LinkedHashMap<>();
        newValue.put("referralTo", ref.getReferralTo());
        newValue.put("referralReference", ref.getReferralReference());
        return Mono.deferContextual(ctx -> {
            String tenantId = TenantContext.get(ctx);
            AuditEvent event = AuditEvent.create(
                    tenantId != null ? tenantId : "unknown",
                    "SIU_REFERRAL",
                    ref.getId().toString(),
                    kase.getCaseNumber() + " · " + ref.getReferralTo(),
                    "CREATE",
                    actorId.toString(),
                    actorEmail,
                    null,
                    newValue,
                    new String[]{"referralTo", "referralReference"},
                    UUID.randomUUID().toString()
            );
            return auditPublisher.publish(event);
        });
    }
}
