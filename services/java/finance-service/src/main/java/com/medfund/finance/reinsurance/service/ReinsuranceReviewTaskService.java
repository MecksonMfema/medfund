package com.medfund.finance.reinsurance.service;

import com.medfund.finance.reinsurance.dto.ReinsuranceReviewTaskResponse;
import com.medfund.finance.reinsurance.entity.Cession;
import com.medfund.finance.reinsurance.entity.Recovery;
import com.medfund.finance.reinsurance.entity.ReinsuranceReviewTask;
import com.medfund.finance.reinsurance.repository.CessionRepository;
import com.medfund.finance.reinsurance.repository.RecoveryRepository;
import com.medfund.finance.reinsurance.repository.ReinsuranceReviewTaskRepository;
import com.medfund.shared.audit.AuditEvent;
import com.medfund.shared.audit.AuditPublisher;
import com.medfund.shared.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Reinsurance manual-review queue (V090). The regression-detection path
 * in {@code ReinsuranceLossCessionConsumer} opens tasks here when a
 * claim's re-adjudication drops below a prior cession's basis; operators
 * work through the queue, assign tasks to themselves, then resolve.
 *
 * <p>{@link #resolve(UUID, String, String, String, String)} cascades on
 * {@code RESOLVED_VOID}: linked cession → VOIDED, any linked non-terminal
 * recovery → WRITTEN_OFF with a "Cession voided: …" prefix (same shape
 * as the facultative void cascade). {@code RESOLVED_KEEP} and
 * {@code DISMISSED} close the task without touching the cession/recovery.
 *
 * <p>Every write emits an AuditEvent with a friendly {@code entityName}
 * (task type + cession id short prefix + status) per
 * {@code feedback_audit_entity_name}. Actor id + email flow through from
 * the JWT via {@link com.medfund.shared.audit.AuditActor} per
 * {@code feedback_audit_actor_email}; the regression-detection consumer
 * passes {@code AuditActor.systemActor()}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReinsuranceReviewTaskService {

    private static final String ENTITY_TYPE = "ReinsuranceReviewTask";
    private static final Set<String> RESOLVED_STATUSES = Set.of(
            "RESOLVED_VOID", "RESOLVED_KEEP", "DISMISSED");

    private final ReinsuranceReviewTaskRepository repository;
    private final CessionRepository cessionRepository;
    private final RecoveryRepository recoveryRepository;
    private final AuditPublisher auditPublisher;

    /**
     * Called by the loss-cession consumer when a re-adjudicated claim
     * lands with {@code approvedAmount} lower than at least one existing
     * cession's basis. Opens one CLAIM_REGRESSION task per affected
     * cession — the operator resolves each independently since each
     * targets a different treaty. Idempotent: an already-open task on
     * the same (cession, task_type) is a no-op.
     */
    @Transactional
    public Flux<ReinsuranceReviewTask> createRegressionTasks(UUID claimId,
                                                             List<Cession> priorCessions,
                                                             BigDecimal newBasis,
                                                             String actorId,
                                                             String actorEmail) {
        if (priorCessions == null || priorCessions.isEmpty()) {
            return Flux.empty();
        }
        return Flux.fromIterable(priorCessions)
                .filter(c -> newBasis.compareTo(c.getBasisAmount()) < 0)
                .flatMap(c -> insertRegressionIfMissing(claimId, c, newBasis, actorId, actorEmail));
    }

    private Mono<ReinsuranceReviewTask> insertRegressionIfMissing(UUID claimId,
                                                                  Cession cession,
                                                                  BigDecimal newBasis,
                                                                  String actorId,
                                                                  String actorEmail) {
        return repository.findOpenByCessionAndType(cession.getId(), "CLAIM_REGRESSION")
                .hasElement()
                .flatMap(exists -> {
                    if (Boolean.TRUE.equals(exists)) {
                        log.debug("Regression task already open for cession {} — skipping", cession.getId());
                        return Mono.<ReinsuranceReviewTask>empty();
                    }
                    ReinsuranceReviewTask t = new ReinsuranceReviewTask();
                    t.setTaskType("CLAIM_REGRESSION");
                    t.setCessionId(cession.getId());
                    t.setClaimId(claimId);
                    t.setTreatyId(cession.getTreatyId());
                    t.setStatus("OPEN");
                    t.setCreateReason("Claim " + claimId
                            + " re-adjudicated: previous basis "
                            + cession.getBasisAmount().toPlainString()
                            + " " + cession.getCurrencyCode()
                            + " → new basis " + newBasis.toPlainString()
                            + " " + cession.getCurrencyCode()
                            + ". Review whether to void or keep cession "
                            + cession.getId() + " (ceded "
                            + cession.getCededAmount().toPlainString() + ").");
                    OffsetDateTime now = OffsetDateTime.now();
                    t.setCreatedAt(now);
                    t.setUpdatedAt(now);
                    t.setActorId(parseUuid(actorId));
                    t.setActorEmail(actorEmail);
                    return repository.save(t)
                            .flatMap(saved -> publishAudit("CREATE", saved, null,
                                            snapshot(saved), actorId, actorEmail)
                                    .thenReturn(saved));
                });
    }

    public Mono<ReinsuranceReviewTaskResponse> get(UUID id) {
        return repository.findById(id)
                .switchIfEmpty(Mono.error(new IllegalArgumentException(
                        "Reinsurance review task not found: " + id)))
                .map(ReinsuranceReviewTaskResponse::from);
    }

    public Flux<ReinsuranceReviewTaskResponse> listOpenQueue(int page, int size) {
        int offset = page * size;
        return repository.findOpenQueue(offset, size).map(ReinsuranceReviewTaskResponse::from);
    }

    public Flux<ReinsuranceReviewTaskResponse> listByStatus(String status, int page, int size) {
        int offset = page * size;
        return repository.findByStatus(status, offset, size).map(ReinsuranceReviewTaskResponse::from);
    }

    public Mono<Long> countOpen() { return repository.countOpen(); }
    public Mono<Long> countByStatus(String status) { return repository.countByStatus(status); }

    @Transactional
    public Mono<ReinsuranceReviewTaskResponse> assign(UUID taskId, UUID assigneeUserId,
                                                      String actorId, String actorEmail) {
        return repository.findById(taskId)
                .switchIfEmpty(Mono.error(new IllegalArgumentException(
                        "Reinsurance review task not found: " + taskId)))
                .flatMap(existing -> {
                    if (RESOLVED_STATUSES.contains(existing.getStatus())) {
                        return Mono.error(new IllegalStateException(
                                "Cannot assign resolved task " + taskId
                                        + " (status " + existing.getStatus() + ")"));
                    }
                    Map<String, Object> before = snapshot(existing);
                    existing.setAssigneeUserId(assigneeUserId);
                    existing.setStatus("IN_PROGRESS");
                    existing.setUpdatedAt(OffsetDateTime.now());
                    existing.setActorId(parseUuid(actorId));
                    existing.setActorEmail(actorEmail);
                    return repository.save(existing)
                            .flatMap(saved -> publishAudit("ASSIGN", saved,
                                            before, snapshot(saved), actorId, actorEmail)
                                    .thenReturn(ReinsuranceReviewTaskResponse.from(saved)));
                });
    }

    /**
     * Resolves an OPEN/IN_PROGRESS task. Valid resolutions:
     * <ul>
     *   <li>{@code RESOLVED_VOID} — cascade-voids the linked cession (if
     *       still non-VOIDED) + WRITTEN_OFFs any non-terminal linked recovery
     *       with prefix "Cession voided: " + notes.</li>
     *   <li>{@code RESOLVED_KEEP} — closes the task; cession + recovery
     *       untouched.</li>
     *   <li>{@code DISMISSED} — closes the task (false-positive path).</li>
     * </ul>
     */
    @Transactional
    public Mono<ReinsuranceReviewTaskResponse> resolve(UUID taskId, String resolution,
                                                       String notes,
                                                       String actorId, String actorEmail) {
        if (resolution == null || !RESOLVED_STATUSES.contains(resolution)) {
            return Mono.error(new IllegalArgumentException(
                    "resolution must be one of RESOLVED_VOID / RESOLVED_KEEP / DISMISSED"));
        }
        return repository.findById(taskId)
                .switchIfEmpty(Mono.error(new IllegalArgumentException(
                        "Reinsurance review task not found: " + taskId)))
                .flatMap(existing -> {
                    if (RESOLVED_STATUSES.contains(existing.getStatus())) {
                        return Mono.error(new IllegalStateException(
                                "Task " + taskId + " already " + existing.getStatus()));
                    }
                    Map<String, Object> before = snapshot(existing);
                    existing.setStatus(resolution);
                    existing.setResolutionNotes(notes);
                    existing.setUpdatedAt(OffsetDateTime.now());
                    existing.setActorId(parseUuid(actorId));
                    existing.setActorEmail(actorEmail);
                    return repository.save(existing)
                            .flatMap(saved -> publishAudit("RESOLVE", saved,
                                            before, snapshot(saved), actorId, actorEmail)
                                    .then("RESOLVED_VOID".equals(resolution)
                                            ? cascadeVoid(saved, notes, actorId, actorEmail)
                                            : Mono.empty())
                                    .thenReturn(ReinsuranceReviewTaskResponse.from(saved)));
                });
    }

    // ── Cascade helpers ────────────────────────────────────────────────────

    private Mono<Void> cascadeVoid(ReinsuranceReviewTask task, String notes,
                                   String actorId, String actorEmail) {
        if (task.getCessionId() == null) return Mono.empty();
        String reason = notes != null && !notes.isBlank()
                ? notes
                : "Reinsurance review task " + task.getId() + " resolved as void";
        return cessionRepository.findById(task.getCessionId())
                .flatMap(cession -> voidCession(cession, reason, actorId, actorEmail)
                        .then(cascadeVoidRecovery(cession.getId(), reason, actorId, actorEmail)));
    }

    private Mono<Void> voidCession(Cession cession, String reason,
                                   String actorId, String actorEmail) {
        if ("VOIDED".equals(cession.getStatus())) {
            log.debug("Cession {} already VOIDED — skipping cascade", cession.getId());
            return Mono.empty();
        }
        Map<String, Object> before = cessionSnapshot(cession);
        cession.setStatus("VOIDED");
        cession.setVoidedReason("Reinsurance review: " + reason);
        cession.setUpdatedAt(OffsetDateTime.now());
        cession.setActorId(parseUuid(actorId));
        cession.setActorEmail(actorEmail);
        return cessionRepository.save(cession)
                .flatMap(saved -> publishCessionAudit(saved, before, actorId, actorEmail))
                .then();
    }

    private Mono<Void> cascadeVoidRecovery(UUID cessionId, String reason,
                                           String actorId, String actorEmail) {
        return recoveryRepository.findByCessionId(cessionId)
                .flatMap(recovery -> {
                    if ("RECEIVED".equals(recovery.getStatus())
                            || "WRITTEN_OFF".equals(recovery.getStatus())) {
                        log.info("Recovery {} already terminal ({}) — leaving on regression void",
                                recovery.getId(), recovery.getStatus());
                        return Mono.<Recovery>empty();
                    }
                    recovery.setStatus("WRITTEN_OFF");
                    recovery.setWriteOffReason("Cession voided: " + reason);
                    recovery.setUpdatedAt(OffsetDateTime.now());
                    recovery.setActorId(parseUuid(actorId));
                    recovery.setActorEmail(actorEmail);
                    return recoveryRepository.save(recovery)
                            .flatMap(saved -> publishRecoveryAudit("VOID", saved, actorId, actorEmail)
                                    .thenReturn(saved));
                })
                .then();
    }

    // ── Audit ──────────────────────────────────────────────────────────────

    private Map<String, Object> snapshot(ReinsuranceReviewTask t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("taskType",        t.getTaskType());
        m.put("cessionId",       t.getCessionId() != null ? t.getCessionId().toString() : null);
        m.put("recoveryId",      t.getRecoveryId() != null ? t.getRecoveryId().toString() : null);
        m.put("claimId",         t.getClaimId() != null ? t.getClaimId().toString() : null);
        m.put("treatyId",        t.getTreatyId() != null ? t.getTreatyId().toString() : null);
        m.put("status",          t.getStatus());
        m.put("assigneeUserId",  t.getAssigneeUserId() != null ? t.getAssigneeUserId().toString() : null);
        m.put("createReason",    t.getCreateReason());
        m.put("resolutionNotes", t.getResolutionNotes());
        return m;
    }

    private Map<String, Object> cessionSnapshot(Cession c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status",       c.getStatus());
        m.put("voidedReason", c.getVoidedReason());
        return m;
    }

    private Mono<Void> publishAudit(String action, ReinsuranceReviewTask task,
                                    Map<String, Object> before, Map<String, Object> after,
                                    String actorId, String actorEmail) {
        return Mono.deferContextual(ctx -> {
            String tenantId = TenantContext.get(ctx);
            String cessionRef = task.getCessionId() != null
                    ? task.getCessionId().toString().substring(0, 8) : "n/a";
            String entityName = task.getTaskType()
                    + " task on cession " + cessionRef
                    + " (" + task.getStatus() + ")";
            var event = AuditEvent.create(
                    tenantId != null ? tenantId : "unknown",
                    ENTITY_TYPE,
                    task.getId().toString(),
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

    private Mono<Cession> publishCessionAudit(Cession cession, Map<String, Object> before,
                                              String actorId, String actorEmail) {
        return Mono.deferContextual(ctx -> {
            String tenantId = TenantContext.get(ctx);
            String entityName = "Cession " + cession.getId().toString().substring(0, 8)
                    + " voided via reinsurance review";
            Map<String, Object> after = cessionSnapshot(cession);
            var event = AuditEvent.create(
                    tenantId != null ? tenantId : "unknown",
                    "Cession",
                    cession.getId().toString(),
                    entityName,
                    "VOID",
                    actorId != null ? actorId : "system",
                    actorEmail,
                    before, after,
                    diff(before, after),
                    UUID.randomUUID().toString());
            return auditPublisher.publish(event).thenReturn(cession);
        });
    }

    private Mono<Void> publishRecoveryAudit(String action, Recovery recovery,
                                            String actorId, String actorEmail) {
        return Mono.deferContextual(ctx -> {
            String tenantId = TenantContext.get(ctx);
            String entityName = "Recovery on cession " + recovery.getCessionId()
                    + " " + recovery.getStatus();
            Map<String, Object> newValue = new LinkedHashMap<>();
            newValue.put("status",         recovery.getStatus());
            newValue.put("writeOffReason", recovery.getWriteOffReason());
            var event = AuditEvent.create(
                    tenantId != null ? tenantId : "unknown",
                    "Recovery",
                    recovery.getId().toString(),
                    entityName,
                    action,
                    actorId != null ? actorId : "system",
                    actorEmail,
                    null, newValue,
                    new String[]{"status", "writeOffReason"},
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
