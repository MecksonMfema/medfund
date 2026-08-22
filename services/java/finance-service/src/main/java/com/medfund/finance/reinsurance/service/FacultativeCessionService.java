package com.medfund.finance.reinsurance.service;

import com.medfund.finance.reinsurance.dto.CessionResponse;
import com.medfund.finance.reinsurance.dto.CreateFacultativeCessionRequest;
import com.medfund.finance.reinsurance.entity.Cession;
import com.medfund.finance.reinsurance.entity.Recovery;
import com.medfund.finance.reinsurance.entity.Treaty;
import com.medfund.finance.reinsurance.repository.CessionRepository;
import com.medfund.finance.reinsurance.repository.RecoveryRepository;
import com.medfund.finance.reinsurance.repository.TreatyApplicableLineRepository;
import com.medfund.finance.reinsurance.repository.TreatyRepository;
import com.medfund.finance.util.DbErrors;
import com.medfund.shared.audit.AuditEvent;
import com.medfund.shared.audit.AuditPublisher;
import com.medfund.shared.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Facultative-cession lifecycle (Phase 7). Underwriter creates a DRAFT
 * against an ACTIVE treaty; supervisor approves DRAFT → APPROVED; supervisor
 * commits APPROVED → CEDED, which is when the Recovery(EXPECTED) row is
 * written (mirroring the auto-cession path in {@link CessionService}, minus
 * the rules-engine dispatch). A void at any pre-terminal state moves the
 * cession to VOIDED with a mandatory reason and cascade-voids any Recovery.
 *
 * <p>Every transition emits an AuditEvent with the tenant slug in
 * {@code entityName} per {@code feedback_audit_entity_name}. Actor id +
 * email come from the JWT via {@code AuditActor} — never inline
 * extraction per {@code feedback_audit_actor_email}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FacultativeCessionService {

    private static final String CESSION_ENTITY_TYPE  = "Cession";
    private static final String RECOVERY_ENTITY_TYPE = "Recovery";
    private static final String SOURCE_EVENT_TYPE    = "CLAIM_FACULTATIVE";

    private final CessionRepository cessionRepository;
    private final RecoveryRepository recoveryRepository;
    private final TreatyRepository treatyRepository;
    private final TreatyApplicableLineRepository applicableLineRepository;
    private final AuditPublisher auditPublisher;

    /**
     * Underwriter creates a DRAFT facultative cession against an ACTIVE
     * treaty. Validates: treaty ACTIVE, insurance line matches at least one
     * of the treaty's applicable_lines, no live LOSS cession on this
     * (claim, treaty) pair. Fails with a named message on any violation —
     * the controller maps the exception to 400/409 via the standard
     * @{@link com.medfund.finance.exception.GlobalExceptionHandler}.
     */
    @Transactional
    public Mono<CessionResponse> createDraft(CreateFacultativeCessionRequest req,
                                             String insuranceLine,
                                             String actorId, String actorEmail) {
        if (insuranceLine == null || insuranceLine.isBlank()) {
            return Mono.error(new IllegalArgumentException(
                    "insuranceLine is required for facultative cessions"));
        }
        return treatyRepository.findById(req.treatyId())
                .switchIfEmpty(Mono.error(new IllegalArgumentException(
                        "Treaty not found: " + req.treatyId())))
                .flatMap(treaty -> {
                    if (!"ACTIVE".equals(treaty.getStatus())) {
                        return Mono.error(new IllegalStateException(
                                "Treaty is " + treaty.getStatus()
                                        + " — facultative cessions require an ACTIVE treaty"));
                    }
                    return assertLineCovered(treaty, insuranceLine)
                            .then(Mono.defer(() -> assertNoLiveLoss(req.claimId(), req.treatyId())))
                            .then(Mono.defer(() -> insertDraft(treaty, req, actorId, actorEmail)));
                });
    }

    /**
     * Supervisor moves DRAFT → APPROVED. No amount changes — the amount
     * committed to the reinsurer is the one authored on the DRAFT.
     */
    @Transactional
    public Mono<CessionResponse> approve(UUID cessionId, String actorId, String actorEmail) {
        return findFacultativeInStatus(cessionId, "DRAFT",
                    "Only DRAFT facultative cessions can be approved")
                .flatMap(existing -> {
                    Map<String, Object> before = snapshot(existing);
                    existing.setStatus("APPROVED");
                    existing.setUpdatedAt(OffsetDateTime.now());
                    existing.setActorId(parseUuid(actorId));
                    existing.setActorEmail(actorEmail);
                    return cessionRepository.save(existing)
                            .flatMap(saved -> publishAudit("APPROVE", saved,
                                            before, snapshot(saved), actorId, actorEmail)
                                    .thenReturn(CessionResponse.from(saved)));
                });
    }

    /**
     * Supervisor moves APPROVED → CEDED. This is the point the fund is on
     * the hook to the reinsurer — write the EXPECTED Recovery row so the
     * recoveries bordereau (Phase 4/5) picks it up on the next export.
     */
    @Transactional
    public Mono<CessionResponse> commit(UUID cessionId, String actorId, String actorEmail) {
        return findFacultativeInStatus(cessionId, "APPROVED",
                    "Only APPROVED facultative cessions can be committed")
                .flatMap(existing -> {
                    Map<String, Object> before = snapshot(existing);
                    existing.setStatus("CEDED");
                    existing.setUpdatedAt(OffsetDateTime.now());
                    existing.setActorId(parseUuid(actorId));
                    existing.setActorEmail(actorEmail);
                    return cessionRepository.save(existing)
                            .flatMap(saved -> publishAudit("COMMIT", saved,
                                            before, snapshot(saved), actorId, actorEmail)
                                    .then(ensureRecovery(saved, actorId, actorEmail))
                                    .thenReturn(CessionResponse.from(saved)));
                });
    }

    /**
     * Supervisor voids a pre-terminal facultative cession (DRAFT or
     * APPROVED). Cascade-voids the linked Recovery if one exists. CEDED
     * facultative cessions are commuted, not voided — that's a separate
     * surface not in Phase 7 scope.
     */
    @Transactional
    public Mono<CessionResponse> voidCession(UUID cessionId, String reason,
                                             String actorId, String actorEmail) {
        if (reason == null || reason.isBlank()) {
            return Mono.error(new IllegalArgumentException("void reason is required"));
        }
        return cessionRepository.findById(cessionId)
                .switchIfEmpty(Mono.error(new IllegalArgumentException(
                        "Cession not found: " + cessionId)))
                .flatMap(existing -> {
                    if (!"FACULTATIVE".equals(existing.getSource())) {
                        return Mono.error(new IllegalStateException(
                                "Cession " + cessionId + " is not facultative"));
                    }
                    if (!"DRAFT".equals(existing.getStatus())
                            && !"APPROVED".equals(existing.getStatus())) {
                        return Mono.error(new IllegalStateException(
                                "Cannot void a " + existing.getStatus()
                                        + " facultative cession — only DRAFT/APPROVED are voidable"));
                    }
                    Map<String, Object> before = snapshot(existing);
                    existing.setStatus("VOIDED");
                    existing.setVoidedReason(reason);
                    existing.setUpdatedAt(OffsetDateTime.now());
                    existing.setActorId(parseUuid(actorId));
                    existing.setActorEmail(actorEmail);
                    return cessionRepository.save(existing)
                            .flatMap(saved -> publishAudit("VOID", saved,
                                            before, snapshot(saved), actorId, actorEmail)
                                    .then(cascadeVoidRecovery(saved.getId(), reason, actorId, actorEmail))
                                    .thenReturn(CessionResponse.from(saved)));
                });
    }

    // ── Internals ──────────────────────────────────────────────────────────

    private Mono<Void> assertLineCovered(Treaty treaty, String insuranceLine) {
        return applicableLineRepository.findByTreatyId(treaty.getId())
                .any(line -> insuranceLine.equalsIgnoreCase(line.getInsuranceLine()))
                .flatMap(match -> {
                    if (Boolean.TRUE.equals(match)) return Mono.empty();
                    return Mono.error(new IllegalStateException(
                            "Treaty " + treaty.getTreatyRef()
                                    + " does not cover insurance line " + insuranceLine));
                });
    }

    private Mono<Void> assertNoLiveLoss(UUID claimId, UUID treatyId) {
        return cessionRepository.findLiveLossByClaimAndTreaty(claimId, treatyId)
                .flatMap(existing -> Mono.<Void>error(new IllegalStateException(
                        "Claim " + claimId + " already has a live cession against treaty " + treatyId
                                + " (cession " + existing.getId() + ", status " + existing.getStatus() + ")")))
                .switchIfEmpty(Mono.empty());
    }

    private Mono<CessionResponse> insertDraft(Treaty treaty, CreateFacultativeCessionRequest req,
                                              String actorId, String actorEmail) {
        Cession c = new Cession();
        c.setTreatyId(treaty.getId());
        c.setTreatyLayerId(req.layerId());
        c.setCessionType("LOSS");
        c.setSource("FACULTATIVE");
        c.setStatus("DRAFT");
        c.setSourceEventId(req.claimId());
        c.setSourceEventType(SOURCE_EVENT_TYPE);
        c.setBasisAmount(req.basisAmount());
        c.setCededAmount(req.cededAmount());
        c.setCurrencyCode(req.currencyCode() != null ? req.currencyCode() : treaty.getDeclaredCurrency());
        c.setOccurredAt(OffsetDateTime.now());
        c.setVoidedReason(req.reason());
        c.setActorId(parseUuid(actorId));
        c.setActorEmail(actorEmail);
        return cessionRepository.save(c)
                .onErrorResume(err -> {
                    if (DbErrors.isUniqueViolation(err)) {
                        log.info("Facultative DRAFT UNIQUE race — treaty={} claim={} — rejecting",
                                treaty.getId(), req.claimId());
                        return Mono.error(new IllegalStateException(
                                "Claim " + req.claimId()
                                        + " already has a cession against treaty " + treaty.getId()));
                    }
                    return Mono.error(err);
                })
                .flatMap(saved -> publishAudit("CREATE", saved, null, snapshot(saved), actorId, actorEmail)
                        .thenReturn(CessionResponse.from(saved)));
    }

    private Mono<Cession> findFacultativeInStatus(UUID cessionId, String expected, String messagePrefix) {
        return cessionRepository.findById(cessionId)
                .switchIfEmpty(Mono.error(new IllegalArgumentException(
                        "Cession not found: " + cessionId)))
                .flatMap(existing -> {
                    if (!"FACULTATIVE".equals(existing.getSource())) {
                        return Mono.error(new IllegalStateException(
                                "Cession " + cessionId + " is not facultative"));
                    }
                    if (!expected.equals(existing.getStatus())) {
                        return Mono.error(new IllegalStateException(
                                messagePrefix + " — was " + existing.getStatus()));
                    }
                    return Mono.just(existing);
                });
    }

    private Mono<Void> ensureRecovery(Cession cession, String actorId, String actorEmail) {
        // hasElement() short-circuits the flatMap→Mono.empty()→switchIfEmpty
        // trap where a "row exists" result would still trigger the insert
        // branch (Reactor sees empty from the flatMap and re-fires
        // switchIfEmpty). Same idiom as {@link CessionService#writeCession}.
        return recoveryRepository.findByCessionId(cession.getId())
                .hasElement()
                .flatMap(exists -> {
                    if (Boolean.TRUE.equals(exists)) {
                        log.debug("Recovery already exists for cession {} — idempotent skip",
                                cession.getId());
                        return Mono.empty();
                    }
                    return insertRecovery(cession, actorId, actorEmail).then();
                });
    }

    private Mono<Recovery> insertRecovery(Cession cession, String actorId, String actorEmail) {
        Recovery r = new Recovery();
        r.setCessionId(cession.getId());
        r.setStatus("EXPECTED");
        r.setExpectedAmount(cession.getCededAmount());
        r.setCurrencyCode(cession.getCurrencyCode());
        r.setActorId(parseUuid(actorId));
        r.setActorEmail(actorEmail);
        return recoveryRepository.save(r)
                .onErrorResume(err -> {
                    if (DbErrors.isUniqueViolation(err)) {
                        log.info("Recovery UNIQUE race for cession {} on facultative commit — idempotent skip",
                                cession.getId());
                        return Mono.empty();
                    }
                    return Mono.error(err);
                })
                .flatMap(saved -> publishRecoveryAudit("CREATE", saved, cession, actorId, actorEmail)
                        .thenReturn(saved));
    }

    private Mono<Void> cascadeVoidRecovery(UUID cessionId, String reason,
                                           String actorId, String actorEmail) {
        return recoveryRepository.findByCessionId(cessionId)
                .flatMap(existing -> {
                    if ("WRITTEN_OFF".equals(existing.getStatus())
                            || "RECEIVED".equals(existing.getStatus())) {
                        log.info("Recovery {} already terminal ({}) — leaving on cession void",
                                existing.getId(), existing.getStatus());
                        return Mono.<Recovery>empty();
                    }
                    existing.setStatus("WRITTEN_OFF");
                    existing.setWriteOffReason("Cession voided: " + reason);
                    existing.setUpdatedAt(OffsetDateTime.now());
                    existing.setActorId(parseUuid(actorId));
                    existing.setActorEmail(actorEmail);
                    return recoveryRepository.save(existing)
                            .flatMap(saved -> publishRecoveryAudit("VOID", saved, null,
                                            actorId, actorEmail)
                                    .thenReturn(saved));
                })
                .then();
    }

    // ── Audit helpers ──────────────────────────────────────────────────────

    private Map<String, Object> snapshot(Cession c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("treatyId",         c.getTreatyId() != null ? c.getTreatyId().toString() : null);
        m.put("treatyLayerId",    c.getTreatyLayerId() != null ? c.getTreatyLayerId().toString() : null);
        m.put("cessionType",      c.getCessionType());
        m.put("source",           c.getSource());
        m.put("status",           c.getStatus());
        m.put("sourceEventId",    c.getSourceEventId() != null ? c.getSourceEventId().toString() : null);
        m.put("sourceEventType",  c.getSourceEventType());
        m.put("basisAmount",      c.getBasisAmount() != null ? c.getBasisAmount().toPlainString() : null);
        m.put("cededAmount",      c.getCededAmount() != null ? c.getCededAmount().toPlainString() : null);
        m.put("currencyCode",     c.getCurrencyCode());
        m.put("voidedReason",     c.getVoidedReason());
        return m;
    }

    private Mono<Void> publishAudit(String action, Cession cession,
                                    Map<String, Object> before, Map<String, Object> after,
                                    String actorId, String actorEmail) {
        return Mono.deferContextual(ctx -> {
            String tenantId = TenantContext.get(ctx);
            String entityName = "Facultative cession on claim " + cession.getSourceEventId()
                    + " — " + (cession.getCededAmount() != null
                                ? cession.getCededAmount().toPlainString() : "0")
                    + " " + cession.getCurrencyCode()
                    + " (" + cession.getStatus() + ")";
            var event = AuditEvent.create(
                    tenantId != null ? tenantId : "unknown",
                    CESSION_ENTITY_TYPE,
                    cession.getId().toString(),
                    entityName,
                    action,
                    actorId != null ? actorId : "system",
                    actorEmail,
                    before, after,
                    diff(before, after),
                    UUID.randomUUID().toString());
            return auditPublisher.publish(event);
        });
    }

    private Mono<Void> publishRecoveryAudit(String action, Recovery recovery, Cession cession,
                                            String actorId, String actorEmail) {
        return Mono.deferContextual(ctx -> {
            String tenantId = TenantContext.get(ctx);
            String basis = cession != null
                    ? " on cession " + cession.getId()
                    : " on cession " + recovery.getCessionId();
            String entityName = "Recovery" + basis
                    + " — " + (recovery.getExpectedAmount() != null
                                ? recovery.getExpectedAmount().toPlainString() : "0")
                    + " " + recovery.getCurrencyCode()
                    + " " + recovery.getStatus();
            Map<String, Object> newValue = new LinkedHashMap<>();
            newValue.put("cessionId",       recovery.getCessionId().toString());
            newValue.put("status",          recovery.getStatus());
            newValue.put("expectedAmount",  recovery.getExpectedAmount() != null
                    ? recovery.getExpectedAmount().toPlainString() : null);
            newValue.put("currencyCode",    recovery.getCurrencyCode());
            newValue.put("writeOffReason",  recovery.getWriteOffReason());
            var event = AuditEvent.create(
                    tenantId != null ? tenantId : "unknown",
                    RECOVERY_ENTITY_TYPE,
                    recovery.getId().toString(),
                    entityName,
                    action,
                    actorId != null ? actorId : "system",
                    actorEmail,
                    null, newValue,
                    new String[]{"status", "expectedAmount", "writeOffReason"},
                    UUID.randomUUID().toString());
            return auditPublisher.publish(event);
        });
    }

    private String[] diff(Map<String, Object> before, Map<String, Object> after) {
        if (before == null || after == null) return new String[0];
        return before.keySet().stream()
                .filter(k -> !java.util.Objects.equals(before.get(k), after.get(k)))
                .toArray(String[]::new);
    }

    private UUID parseUuid(String s) {
        if (s == null || s.isBlank()) return null;
        try { return UUID.fromString(s); } catch (IllegalArgumentException e) { return null; }
    }
}
