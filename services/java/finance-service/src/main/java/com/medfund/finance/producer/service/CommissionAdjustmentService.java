package com.medfund.finance.producer.service;

import com.medfund.finance.producer.dto.AdjustmentResponse;
import com.medfund.finance.producer.dto.CreateAdjustmentRequest;
import com.medfund.finance.producer.entity.CommissionAdjustment;
import com.medfund.finance.producer.entity.CommissionTransaction;
import com.medfund.finance.producer.repository.CommissionAdjustmentRepository;
import com.medfund.finance.producer.repository.CommissionTransactionRepository;
import com.medfund.finance.producer.util.ReferenceGenerator;
import com.medfund.finance.util.DbErrors;
import com.medfund.shared.audit.AuditEvent;
import com.medfund.shared.audit.AuditPublisher;
import com.medfund.shared.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Four-eyes commission-adjustment lifecycle (Phase 8, §B). Mirrors
 * {@link com.medfund.finance.reinsurance.service.FacultativeCessionService}
 * one-for-one:
 * <pre>
 *   DRAFT → APPROVED → COMMITTED   (terminal)
 *   DRAFT | APPROVED → VOIDED       (terminal)
 *   COMMITTED cannot be voided     (matches facultative precedent)
 * </pre>
 *
 * <p>On COMMIT the service writes a compensating
 * {@link CommissionTransaction} row linked back via
 * {@link CommissionAdjustment#getCommittedTxnId()}, with
 * {@code reversalOfTxnId} pointing at the target row. The compensating row
 * carries the adjustment amount as-is — the drafter is responsible for
 * signing correctly (positive credits producer, negative reverses).
 *
 * <p>Every transition emits an {@link AuditEvent} carrying the friendly
 * {@code reference} as {@code entityName} per
 * {@code feedback_audit_entity_name}, with actor identity from
 * {@link com.medfund.shared.audit.AuditActor} per
 * {@code feedback_audit_actor_email}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CommissionAdjustmentService {

    private static final String ADJUSTMENT_ENTITY_TYPE = "CommissionAdjustment";
    private static final String COMMISSION_TXN_ENTITY_TYPE = "CommissionTransaction";

    private final CommissionAdjustmentRepository adjustmentRepository;
    private final CommissionTransactionRepository commissionTxnRepository;
    private final ReferenceGenerator referenceGenerator;
    private final AuditPublisher auditPublisher;

    /**
     * Drafter creates a DRAFT adjustment against an existing
     * {@link CommissionTransaction}. Validates the target exists, then mints
     * a reference and inserts. Currency is inferred from the target row so
     * the drafter cannot mismatch currencies.
     */
    @Transactional
    public Mono<AdjustmentResponse> createDraft(CreateAdjustmentRequest req,
                                                String actorId, String actorEmail) {
        return validateDraft(req)
                .then(Mono.defer(() -> commissionTxnRepository.findById(req.targetCommissionTransactionId())
                        .switchIfEmpty(Mono.error(new IllegalArgumentException(
                                "CommissionTransaction not found: " + req.targetCommissionTransactionId())))))
                .flatMap(target -> referenceGenerator.nextAdjustmentReference()
                        .flatMap(ref -> insertDraft(ref, target, req, actorId, actorEmail)));
    }

    /**
     * Supervisor moves DRAFT → APPROVED. Same actor cannot approve their own
     * draft (four-eyes invariant enforced against {@link CommissionAdjustment#getActorId()}).
     */
    @Transactional
    public Mono<AdjustmentResponse> approve(UUID id, String actorId, String actorEmail) {
        return findInStatus(id, "DRAFT",
                    "Only DRAFT adjustments can be approved")
                .flatMap(existing -> {
                    if (existing.getActorId() != null && actorId != null
                            && existing.getActorId().toString().equals(actorId)) {
                        return Mono.error(new IllegalStateException(
                                "Approver must differ from drafter (four-eyes)"));
                    }
                    Map<String, Object> before = snapshot(existing);
                    existing.setStatus("APPROVED");
                    existing.setApproverActorId(parseUuid(actorId));
                    existing.setApproverActorEmail(actorEmail);
                    existing.setApprovedAt(OffsetDateTime.now());
                    existing.setUpdatedAt(OffsetDateTime.now());
                    return adjustmentRepository.save(existing)
                            .flatMap(saved -> publishAudit("APPROVE", saved,
                                            before, snapshot(saved), actorId, actorEmail)
                                    .thenReturn(AdjustmentResponse.from(saved)));
                });
    }

    /**
     * Supervisor moves APPROVED → COMMITTED. Writes the compensating
     * {@link CommissionTransaction} row and links back via
     * {@link CommissionAdjustment#setCommittedTxnId(UUID)}. This is the
     * point the fund actually records the ledger movement — the statement
     * report picks it up on the next export.
     */
    @Transactional
    public Mono<AdjustmentResponse> commit(UUID id, String actorId, String actorEmail) {
        return findInStatus(id, "APPROVED",
                    "Only APPROVED adjustments can be committed")
                .flatMap(adj -> writeCompensatingTransaction(adj, actorId, actorEmail)
                        .flatMap(compensating -> {
                            Map<String, Object> before = snapshot(adj);
                            adj.setStatus("COMMITTED");
                            adj.setCommittedAt(OffsetDateTime.now());
                            adj.setCommittedTxnId(compensating.getId());
                            adj.setUpdatedAt(OffsetDateTime.now());
                            return adjustmentRepository.save(adj)
                                    .flatMap(saved -> publishAudit("COMMIT", saved,
                                                    before, snapshot(saved), actorId, actorEmail)
                                            .thenReturn(AdjustmentResponse.from(saved)));
                        }));
    }

    /**
     * DRAFT or APPROVED → VOIDED. COMMITTED cannot be voided (terminal, same
     * as facultative precedent). Reason is required.
     */
    @Transactional
    public Mono<AdjustmentResponse> voidAdjustment(UUID id, String reason,
                                                   String actorId, String actorEmail) {
        if (reason == null || reason.isBlank()) {
            return Mono.error(new IllegalArgumentException("void reason is required"));
        }
        return adjustmentRepository.findById(id)
                .switchIfEmpty(Mono.error(new IllegalArgumentException(
                        "CommissionAdjustment not found: " + id)))
                .flatMap(existing -> {
                    if (!"DRAFT".equals(existing.getStatus())
                            && !"APPROVED".equals(existing.getStatus())) {
                        return Mono.error(new IllegalStateException(
                                "Cannot void a " + existing.getStatus()
                                        + " adjustment - only DRAFT/APPROVED are voidable"));
                    }
                    Map<String, Object> before = snapshot(existing);
                    existing.setStatus("VOIDED");
                    existing.setVoidedAt(OffsetDateTime.now());
                    existing.setVoidedReason(reason);
                    existing.setUpdatedAt(OffsetDateTime.now());
                    return adjustmentRepository.save(existing)
                            .flatMap(saved -> publishAudit("VOID", saved,
                                            before, snapshot(saved), actorId, actorEmail)
                                    .thenReturn(AdjustmentResponse.from(saved)));
                });
    }

    /** Read-side queue lookup. {@code statuses} defaults to DRAFT + APPROVED
     *  when {@code null} or empty. */
    public Flux<AdjustmentResponse> queue(List<String> statuses, int page, int size) {
        List<String> effective = (statuses == null || statuses.isEmpty())
                ? List.of("DRAFT", "APPROVED")
                : statuses;
        Pageable pageable = PageRequest.of(page, size);
        return adjustmentRepository
                .findByStatusInOrderByCreatedAtAsc(effective, pageable)
                .map(AdjustmentResponse::from);
    }

    public Mono<Long> queueCount(List<String> statuses) {
        List<String> effective = (statuses == null || statuses.isEmpty())
                ? List.of("DRAFT", "APPROVED")
                : statuses;
        return adjustmentRepository.countByStatusIn(effective);
    }

    public Mono<AdjustmentResponse> findById(UUID id) {
        return adjustmentRepository.findById(id)
                .switchIfEmpty(Mono.error(new IllegalArgumentException(
                        "CommissionAdjustment not found: " + id)))
                .map(AdjustmentResponse::from);
    }

    // ── Internals ──────────────────────────────────────────────────────────

    private Mono<Void> validateDraft(CreateAdjustmentRequest req) {
        if (req.justification() == null || req.justification().length() < 20) {
            return Mono.error(new IllegalArgumentException(
                    "justification must be at least 20 characters"));
        }
        if (req.adjustmentAmount() == null || req.adjustmentAmount().signum() == 0) {
            return Mono.error(new IllegalArgumentException(
                    "adjustmentAmount must be non-zero"));
        }
        if (req.targetCommissionTransactionId() == null || req.adjustmentType() == null) {
            return Mono.error(new IllegalArgumentException(
                    "targetCommissionTransactionId + adjustmentType required"));
        }
        return Mono.empty();
    }

    private Mono<AdjustmentResponse> insertDraft(String reference, CommissionTransaction target,
                                                 CreateAdjustmentRequest req,
                                                 String actorId, String actorEmail) {
        CommissionAdjustment adj = new CommissionAdjustment();
        adj.setReference(reference);
        adj.setTargetCommissionTransactionId(target.getId());
        adj.setAdjustmentType(req.adjustmentType());
        adj.setAdjustmentAmount(req.adjustmentAmount());
        adj.setNativeCurrency(target.getNativeCurrency());
        adj.setJustification(req.justification());
        adj.setStatus("DRAFT");
        adj.setActorId(parseUuid(actorId));
        adj.setActorEmail(actorEmail);
        return adjustmentRepository.save(adj)
                .onErrorResume(err -> {
                    if (DbErrors.isUniqueViolation(err)) {
                        log.info("Adjustment reference UNIQUE race on {} — surfacing as 409", reference);
                        return Mono.error(new IllegalStateException(
                                "Adjustment reference collision - retry"));
                    }
                    return Mono.error(err);
                })
                .flatMap(saved -> publishAudit("CREATE", saved, null, snapshot(saved),
                                actorId, actorEmail)
                        .thenReturn(AdjustmentResponse.from(saved)));
    }

    private Mono<CommissionAdjustment> findInStatus(UUID id, String expected, String messagePrefix) {
        return adjustmentRepository.findById(id)
                .switchIfEmpty(Mono.error(new IllegalArgumentException(
                        "CommissionAdjustment not found: " + id)))
                .flatMap(existing -> {
                    if (!expected.equals(existing.getStatus())) {
                        return Mono.error(new IllegalStateException(
                                messagePrefix + " - was " + existing.getStatus()));
                    }
                    return Mono.just(existing);
                });
    }

    private Mono<CommissionTransaction> writeCompensatingTransaction(CommissionAdjustment adj,
                                                                     String actorId, String actorEmail) {
        return commissionTxnRepository.findById(adj.getTargetCommissionTransactionId())
                .switchIfEmpty(Mono.error(new IllegalStateException(
                        "Target CommissionTransaction disappeared: "
                                + adj.getTargetCommissionTransactionId())))
                .flatMap(target -> referenceGenerator.nextCommissionReference()
                        .flatMap(ref -> {
                            CommissionTransaction compensating = new CommissionTransaction();
                            compensating.setReference(ref);
                            compensating.setProducerId(target.getProducerId());
                            compensating.setContributionId(target.getContributionId());
                            compensating.setMemberId(target.getMemberId());
                            compensating.setInsuranceLine(target.getInsuranceLine());
                            compensating.setRateCardId(target.getRateCardId());
                            compensating.setNativeAmount(adj.getAdjustmentAmount());
                            compensating.setNativeCurrency(adj.getNativeCurrency());
                            compensating.setContributionAmount(target.getContributionAmount());
                            compensating.setAppliedRatePct(BigDecimal.ZERO);
                            compensating.setStatus("ACCRUED");
                            compensating.setReversalOfTxnId(target.getId());
                            compensating.setOccurredAt(OffsetDateTime.now());
                            compensating.setActorId(parseUuid(actorId));
                            compensating.setActorEmail(actorEmail);
                            return commissionTxnRepository.save(compensating)
                                    .flatMap(saved -> publishCompensatingAudit(saved, adj,
                                                    actorId, actorEmail)
                                            .thenReturn(saved));
                        }));
    }

    // ── Audit helpers ──────────────────────────────────────────────────────

    private Map<String, Object> snapshot(CommissionAdjustment a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("reference",                     a.getReference());
        m.put("targetCommissionTransactionId", a.getTargetCommissionTransactionId() != null
                ? a.getTargetCommissionTransactionId().toString() : null);
        m.put("adjustmentType",                a.getAdjustmentType());
        m.put("adjustmentAmount",              a.getAdjustmentAmount() != null
                ? a.getAdjustmentAmount().toPlainString() : null);
        m.put("nativeCurrency",                a.getNativeCurrency());
        m.put("status",                        a.getStatus());
        m.put("committedTxnId",                a.getCommittedTxnId() != null
                ? a.getCommittedTxnId().toString() : null);
        m.put("voidedReason",                  a.getVoidedReason());
        return m;
    }

    private Mono<Void> publishAudit(String action, CommissionAdjustment adj,
                                    Map<String, Object> before, Map<String, Object> after,
                                    String actorId, String actorEmail) {
        return Mono.deferContextual(ctx -> {
            String tenantId = TenantContext.get(ctx);
            AuditEvent event = AuditEvent.create(
                    tenantId != null ? tenantId : "unknown",
                    ADJUSTMENT_ENTITY_TYPE,
                    adj.getId().toString(),
                    adj.getReference(),
                    action,
                    actorId != null ? actorId : "system",
                    actorEmail,
                    before, after,
                    diff(before, after),
                    UUID.randomUUID().toString());
            return auditPublisher.publish(event);
        });
    }

    private Mono<Void> publishCompensatingAudit(CommissionTransaction compensating,
                                                CommissionAdjustment adj,
                                                String actorId, String actorEmail) {
        return Mono.deferContextual(ctx -> {
            String tenantId = TenantContext.get(ctx);
            Map<String, Object> newValue = new LinkedHashMap<>();
            newValue.put("reference",           compensating.getReference());
            newValue.put("producerId",          compensating.getProducerId().toString());
            newValue.put("contributionId",      compensating.getContributionId().toString());
            newValue.put("reversalOfTxnId",     compensating.getReversalOfTxnId().toString());
            newValue.put("nativeAmount",        compensating.getNativeAmount() != null
                    ? compensating.getNativeAmount().toPlainString() : null);
            newValue.put("nativeCurrency",      compensating.getNativeCurrency());
            newValue.put("status",              compensating.getStatus());
            newValue.put("triggeringAdjustment", adj.getReference());
            AuditEvent event = AuditEvent.create(
                    tenantId != null ? tenantId : "unknown",
                    COMMISSION_TXN_ENTITY_TYPE,
                    compensating.getId().toString(),
                    compensating.getReference(),
                    "CREATE",
                    actorId != null ? actorId : "system",
                    actorEmail,
                    null, newValue,
                    new String[]{"reference", "nativeAmount", "reversalOfTxnId", "status"},
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
