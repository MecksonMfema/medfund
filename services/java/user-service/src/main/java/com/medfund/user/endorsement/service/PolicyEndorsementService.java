package com.medfund.user.endorsement.service;

import com.medfund.shared.audit.AuditEvent;
import com.medfund.shared.audit.AuditPublisher;
import com.medfund.shared.tenant.TenantContext;
import com.medfund.user.client.TenantEndorsementConfigClient;
import com.medfund.user.endorsement.dto.CreateEndorsementRequest;
import com.medfund.user.endorsement.dto.EndorsementResponse;
import com.medfund.user.endorsement.entity.Endorsement;
import com.medfund.user.endorsement.repository.EndorsementRepository;
import com.medfund.user.endorsement.util.EndorsementReferenceGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Four-eyes policy-endorsement lifecycle (Phase 12 §C). Mirrors
 * {@code CommissionAdjustmentService} in finance-service one-for-one:
 * <pre>
 *   DRAFT → APPROVED → COMMITTED   (terminal)
 *   DRAFT | APPROVED → VOIDED       (terminal)
 *   COMMITTED → COMPUTED            (set by contributions-service after
 *                                    retro earning-schedule recompute)
 * </pre>
 *
 * <p>Threshold behaviour reads from {@code public.tenant_endorsement_config}
 * via {@link TenantEndorsementConfigClient}. When the config is disabled or
 * absent, {@link #createDraft} auto-commits (skips the DRAFT stage,
 * publishes {@code medfund.user.policy-endorsed} immediately). When the
 * config is enabled and the {@code premiumDelta} magnitude reaches
 * {@code fourEyesThresholdAmount}, the row stays at DRAFT and awaits a
 * second-actor approve+commit.
 *
 * <p>{@code effectiveFrom} snaps to 1st-of-month per
 * {@code feedback_effective_date_snap}; audit events use the friendly
 * {@code reference} as {@code entityName} per
 * {@code feedback_audit_entity_name}; actor identity flows through
 * {@link com.medfund.shared.audit.AuditActor} per
 * {@code feedback_audit_actor_email}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PolicyEndorsementService {

    private static final String ENTITY_TYPE = "PolicyEndorsement";

    private final EndorsementRepository endorsementRepository;
    private final EndorsementReferenceGenerator referenceGenerator;
    private final TenantEndorsementConfigClient tenantConfigClient;
    private final PolicyEndorsedPublisher endorsedPublisher;
    private final R2dbcEntityTemplate r2dbcTemplate;
    private final AuditPublisher auditPublisher;

    // ── Read side ──────────────────────────────────────────────────────────

    public Mono<EndorsementResponse> findById(UUID id) {
        return endorsementRepository.findById(id)
                .switchIfEmpty(Mono.error(new IllegalArgumentException(
                        "Endorsement not found: " + id)))
                .map(EndorsementResponse::from);
    }

    public Flux<EndorsementResponse> findByPolicy(UUID policyId, String policySource) {
        return endorsementRepository
                .findByPolicyIdAndPolicySourceOrderByCreatedAtDesc(policyId, policySource)
                .map(EndorsementResponse::from);
    }

    public Flux<EndorsementResponse> queue(List<String> statuses, int page, int size) {
        List<String> effective = (statuses == null || statuses.isEmpty())
                ? List.of("DRAFT", "APPROVED")
                : statuses;
        Pageable pageable = PageRequest.of(page, size);
        return endorsementRepository
                .findByStatusInOrderByCreatedAtAsc(effective, pageable)
                .map(EndorsementResponse::from);
    }

    public Mono<Long> queueCount(List<String> statuses) {
        List<String> effective = (statuses == null || statuses.isEmpty())
                ? List.of("DRAFT", "APPROVED")
                : statuses;
        return endorsementRepository.countByStatusIn(effective);
    }

    // ── Write side ─────────────────────────────────────────────────────────

    /**
     * Drafter creates the endorsement. Below-threshold (or disabled) config
     * auto-commits and fires the policy-endorsed event in the same call so
     * contributions-service picks up the recompute without a manual approve.
     * Above-threshold parks at DRAFT for supervisor approval.
     */
    @Transactional
    public Mono<EndorsementResponse> createDraft(CreateEndorsementRequest req,
                                                 String actorId, String actorEmail) {
        return validateDraft(req)
                .then(Mono.defer(referenceGenerator::nextEndorsementReference))
                .flatMap(ref -> insertDraft(ref, req, actorId, actorEmail))
                .flatMap(saved -> Mono.deferContextual(ctx -> {
                    String tenantId = TenantContext.get(ctx);
                    return maybeAutoCommit(saved, tenantId, actorId, actorEmail);
                }));
    }

    /** Supervisor moves DRAFT → APPROVED. Approver actorId must differ from drafter. */
    @Transactional
    public Mono<EndorsementResponse> approve(UUID id, String actorId, String actorEmail) {
        return findInStatus(id, "DRAFT", "Only DRAFT endorsements can be approved")
                .flatMap(existing -> {
                    if (existing.getDraftActorId() != null && actorId != null
                            && existing.getDraftActorId().toString().equals(actorId)) {
                        return Mono.error(new IllegalStateException(
                                "Approver must differ from drafter (four-eyes)"));
                    }
                    Map<String, Object> before = snapshot(existing);
                    existing.setStatus("APPROVED");
                    existing.setApproveActorId(parseUuid(actorId));
                    existing.setApproveActorEmail(actorEmail);
                    existing.setApproveAt(Instant.now());
                    existing.setUpdatedAt(Instant.now());
                    return endorsementRepository.save(existing)
                            .flatMap(saved -> publishAudit("APPROVE", saved,
                                    before, snapshot(saved), actorId, actorEmail)
                                    .thenReturn(EndorsementResponse.from(saved)));
                });
    }

    /**
     * Supervisor moves APPROVED → COMMITTED. Publishes
     * {@code medfund.user.policy-endorsed} so contributions-service picks up
     * the retro recompute; audit event records the commit actor.
     */
    @Transactional
    public Mono<EndorsementResponse> commit(UUID id, String actorId, String actorEmail) {
        return findInStatus(id, "APPROVED", "Only APPROVED endorsements can be committed")
                .flatMap(existing -> {
                    Map<String, Object> before = snapshot(existing);
                    existing.setStatus("COMMITTED");
                    existing.setCommitActorId(parseUuid(actorId));
                    existing.setCommitActorEmail(actorEmail);
                    existing.setCommitAt(Instant.now());
                    existing.setUpdatedAt(Instant.now());
                    return endorsementRepository.save(existing)
                            .flatMap(saved -> Mono.deferContextual(ctx -> {
                                String tenantId = TenantContext.get(ctx);
                                return publishAudit("COMMIT", saved, before, snapshot(saved),
                                                actorId, actorEmail)
                                        .then(endorsedPublisher.publish(tenantId, saved))
                                        .thenReturn(EndorsementResponse.from(saved));
                            }));
                });
    }

    /** DRAFT or APPROVED → VOIDED. COMMITTED / COMPUTED cannot be voided. Reason required. */
    @Transactional
    public Mono<EndorsementResponse> voidEndorsement(UUID id, String reason,
                                                     String actorId, String actorEmail) {
        if (reason == null || reason.isBlank()) {
            return Mono.error(new IllegalArgumentException("void reason is required"));
        }
        return endorsementRepository.findById(id)
                .switchIfEmpty(Mono.error(new IllegalArgumentException(
                        "Endorsement not found: " + id)))
                .flatMap(existing -> {
                    if (!"DRAFT".equals(existing.getStatus())
                            && !"APPROVED".equals(existing.getStatus())) {
                        return Mono.error(new IllegalStateException(
                                "Cannot void a " + existing.getStatus()
                                        + " endorsement — only DRAFT/APPROVED are voidable"));
                    }
                    Map<String, Object> before = snapshot(existing);
                    existing.setStatus("VOIDED");
                    existing.setVoidedAt(Instant.now());
                    existing.setVoidedReason(reason);
                    existing.setUpdatedAt(Instant.now());
                    return endorsementRepository.save(existing)
                            .flatMap(saved -> publishAudit("VOID", saved,
                                            before, snapshot(saved), actorId, actorEmail)
                                    .thenReturn(EndorsementResponse.from(saved)));
                });
    }

    /**
     * Contributions-service transitions {@code COMMITTED → COMPUTED} once the
     * retro earning-schedule recompute finishes. Exposed as its own method so
     * the controller can wire a system-actor endpoint at Phase 9.
     */
    @Transactional
    public Mono<EndorsementResponse> markComputed(UUID id, String actorId, String actorEmail) {
        return findInStatus(id, "COMMITTED", "Only COMMITTED endorsements can be marked COMPUTED")
                .flatMap(existing -> {
                    Map<String, Object> before = snapshot(existing);
                    existing.setStatus("COMPUTED");
                    existing.setUpdatedAt(Instant.now());
                    return endorsementRepository.save(existing)
                            .flatMap(saved -> publishAudit("COMPUTE", saved,
                                            before, snapshot(saved), actorId, actorEmail)
                                    .thenReturn(EndorsementResponse.from(saved)));
                });
    }

    // ── Internals ──────────────────────────────────────────────────────────

    private Mono<Void> validateDraft(CreateEndorsementRequest req) {
        if (req.reason() == null || req.reason().trim().length() < 10) {
            return Mono.error(new IllegalArgumentException(
                    "reason must be at least 10 characters"));
        }
        if ((req.premiumDelta() == null) != (req.currencyCode() == null)) {
            return Mono.error(new IllegalArgumentException(
                    "premiumDelta + currencyCode must be provided together (or both omitted)"));
        }
        if (req.premiumDelta() != null && req.premiumDelta().signum() == 0) {
            return Mono.error(new IllegalArgumentException(
                    "premiumDelta must be non-zero when provided"));
        }
        return Mono.empty();
    }

    private Mono<Endorsement> insertDraft(String reference, CreateEndorsementRequest req,
                                          String actorId, String actorEmail) {
        Endorsement e = new Endorsement();
        e.setReference(reference);
        e.setPolicyId(req.policyId());
        e.setPolicySource(req.policySource());
        e.setInsuranceLine(req.insuranceLine());
        e.setChangeType(req.changeType());
        e.setEffectiveFrom(snapToFirstOfMonth(req.effectiveFrom()));
        e.setPremiumDelta(req.premiumDelta());
        e.setCurrencyCode(req.currencyCode());
        e.setReason(req.reason());
        e.setStatus("DRAFT");
        e.setDraftActorId(parseUuid(actorId));
        e.setDraftActorEmail(actorEmail);
        e.setDraftAt(Instant.now());
        e.setCreatedAt(Instant.now());
        e.setUpdatedAt(Instant.now());
        return r2dbcTemplate.insert(e)
                .flatMap(saved -> publishAudit("CREATE", saved, null, snapshot(saved),
                                actorId, actorEmail)
                        .thenReturn(saved));
    }

    /**
     * If the tenant config is disabled or the endorsement's premium delta is
     * below the four-eyes threshold, auto-commit synchronously. Currency
     * mismatch between the endorsement and the threshold is treated as
     * over-threshold conservatively — a threshold in USD does not gate a ZWL
     * endorsement, so we park at DRAFT and let a supervisor decide.
     */
    private Mono<EndorsementResponse> maybeAutoCommit(Endorsement draft, String tenantId,
                                                     String actorId, String actorEmail) {
        UUID tenantUuid = parseUuid(tenantId);
        return tenantConfigClient.get(tenantUuid)
                .flatMap(cfg -> {
                    boolean requiresFourEyes = cfg.enabled()
                            && cfg.fourEyesThresholdAmount() != null
                            && draft.getPremiumDelta() != null
                            && sameCurrency(cfg.thresholdCurrency(), draft.getCurrencyCode())
                            && draft.getPremiumDelta().abs()
                                    .compareTo(cfg.fourEyesThresholdAmount()) >= 0;
                    if (requiresFourEyes) {
                        return Mono.just(EndorsementResponse.from(draft));
                    }
                    return autoCommit(draft, tenantId, actorId, actorEmail);
                });
    }

    private Mono<EndorsementResponse> autoCommit(Endorsement draft, String tenantId,
                                                 String actorId, String actorEmail) {
        Map<String, Object> before = snapshot(draft);
        Instant now = Instant.now();
        draft.setStatus("COMMITTED");
        draft.setCommitActorId(parseUuid(actorId));
        draft.setCommitActorEmail(actorEmail);
        draft.setCommitAt(now);
        draft.setUpdatedAt(now);
        return endorsementRepository.save(draft)
                .flatMap(saved -> publishAudit("AUTO_COMMIT", saved, before, snapshot(saved),
                                actorId, actorEmail)
                        .then(endorsedPublisher.publish(tenantId, saved))
                        .thenReturn(EndorsementResponse.from(saved)));
    }

    private Mono<Endorsement> findInStatus(UUID id, String expected, String messagePrefix) {
        return endorsementRepository.findById(id)
                .switchIfEmpty(Mono.error(new IllegalArgumentException(
                        "Endorsement not found: " + id)))
                .flatMap(existing -> {
                    if (!expected.equals(existing.getStatus())) {
                        return Mono.error(new IllegalStateException(
                                messagePrefix + " — was " + existing.getStatus()));
                    }
                    return Mono.just(existing);
                });
    }

    private static boolean sameCurrency(String a, String b) {
        return a != null && b != null && a.equalsIgnoreCase(b);
    }

    private static LocalDate snapToFirstOfMonth(LocalDate d) {
        return d == null ? null : d.withDayOfMonth(1);
    }

    // ── Audit helpers ──────────────────────────────────────────────────────

    private Map<String, Object> snapshot(Endorsement e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("reference",       e.getReference());
        m.put("policyId",        e.getPolicyId() != null ? e.getPolicyId().toString() : null);
        m.put("policySource",    e.getPolicySource());
        m.put("insuranceLine",   e.getInsuranceLine());
        m.put("changeType",      e.getChangeType());
        m.put("effectiveFrom",   e.getEffectiveFrom() != null ? e.getEffectiveFrom().toString() : null);
        m.put("premiumDelta",    e.getPremiumDelta() != null ? e.getPremiumDelta().toPlainString() : null);
        m.put("currencyCode",    e.getCurrencyCode());
        m.put("status",          e.getStatus());
        m.put("voidedReason",    e.getVoidedReason());
        return m;
    }

    private Mono<Void> publishAudit(String action, Endorsement e,
                                    Map<String, Object> before, Map<String, Object> after,
                                    String actorId, String actorEmail) {
        return Mono.deferContextual(ctx -> {
            String tenantId = TenantContext.get(ctx);
            AuditEvent event = AuditEvent.create(
                    tenantId != null ? tenantId : "unknown",
                    ENTITY_TYPE,
                    e.getId().toString(),
                    e.getReference(),
                    action,
                    actorId != null ? actorId : "system",
                    actorEmail,
                    before, after,
                    diff(before, after),
                    UUID.randomUUID().toString());
            return auditPublisher.publish(event);
        });
    }

    private String[] diff(Map<String, Object> before, Map<String, Object> after) {
        if (before == null || after == null) return new String[0];
        return before.keySet().stream()
                .filter(k -> !Objects.equals(before.get(k), after.get(k)))
                .toArray(String[]::new);
    }

    private UUID parseUuid(String s) {
        if (s == null || s.isBlank()) return null;
        try { return UUID.fromString(s); } catch (IllegalArgumentException e) { return null; }
    }
}
