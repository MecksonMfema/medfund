package com.medfund.finance.regulatory.aml.service;

import com.medfund.finance.regulatory.aml.dto.AmlAlertResponse;
import com.medfund.finance.regulatory.aml.dto.CloseAmlAlertRequest;
import com.medfund.finance.regulatory.aml.dto.FileAmlAlertRequest;
import com.medfund.finance.regulatory.aml.dto.RaiseAmlAlertRequest;
import com.medfund.finance.regulatory.aml.dto.ReviewAmlAlertRequest;
import com.medfund.finance.regulatory.aml.entity.SuspiciousTransactionAlert;
import com.medfund.finance.regulatory.aml.kafka.SuspiciousTransactionEventPublisher;
import com.medfund.finance.regulatory.aml.repository.SuspiciousTransactionAlertRepository;
import com.medfund.shared.audit.AuditActor;
import com.medfund.shared.audit.AuditEvent;
import com.medfund.shared.audit.AuditPublisher;
import com.medfund.shared.report.SuspiciousTransactionEvent;
import com.medfund.shared.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * AML Suspicious Transaction Alert workflow (Phase 22, REG8).
 *
 * <pre>
 *   RAISED → REVIEWED → FILED     (terminal — regulator submission complete)
 *   RAISED | REVIEWED → CLOSED    (terminal — deemed not reportable)
 *   FILED / CLOSED are terminal — no further transitions
 * </pre>
 *
 * <p>Every transition emits an {@link AuditEvent} with
 * {@code entityName = "AML alert #{txnRef}"} per
 * {@code feedback_audit_entity_name}, and actor identity via
 * {@link AuditActor} per {@code feedback_audit_actor_email}.
 *
 * <p>Kafka emission on transitions is wired in Phase 24; this phase only
 * covers the DB workflow + audit trail.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AmlAlertService {

    private static final String ENTITY_TYPE = "SuspiciousTransactionAlert";
    public static final String STATUS_RAISED = "RAISED";
    public static final String STATUS_REVIEWED = "REVIEWED";
    public static final String STATUS_FILED = "FILED";
    public static final String STATUS_CLOSED = "CLOSED";

    private final SuspiciousTransactionAlertRepository repository;
    private final AuditPublisher auditPublisher;
    private final SuspiciousTransactionEventPublisher eventPublisher;
    private final AmlStrFilingService strFilingService;

    /**
     * Raise a new alert in status {@code RAISED}. member and provider ids are
     * optional (some alert types have neither — e.g. an internal adjustment).
     */
    @Transactional
    public Mono<AmlAlertResponse> raise(RaiseAmlAlertRequest req,
                                        String actorId, String actorEmail) {
        SuspiciousTransactionAlert row = new SuspiciousTransactionAlert();
        row.setStatus(STATUS_RAISED);
        row.setTransactionRef(req.transactionRef());
        row.setTransactionType(req.transactionType());
        row.setAmountNative(req.amountNative());
        row.setCurrency(req.currency());
        row.setMemberId(req.memberId());
        row.setProviderId(req.providerId());
        row.setDescription(req.description());
        row.setRaisedByActorId(parseUuid(actorId));
        row.setRaisedByActorEmail(actorEmail);
        row.setRaisedAt(OffsetDateTime.now());
        return repository.save(row)
                .flatMap(saved -> publishAudit("RAISE", saved, null, snapshot(saved),
                                actorId, actorEmail)
                        .then(publishEvent(saved, null, SuspiciousTransactionEvent.TRANSITION_RAISE,
                                actorId, actorEmail))
                        .thenReturn(AmlAlertResponse.from(saved)));
    }

    /** RAISED → REVIEWED. Reviewer must supply a non-blank note. */
    @Transactional
    public Mono<AmlAlertResponse> review(UUID id, ReviewAmlAlertRequest req,
                                         String actorId, String actorEmail) {
        return findInStatus(id, STATUS_RAISED,
                "Only RAISED alerts can be reviewed")
                .flatMap(existing -> {
                    Map<String, Object> before = snapshot(existing);
                    existing.setStatus(STATUS_REVIEWED);
                    existing.setReviewerActorId(parseUuid(actorId));
                    existing.setReviewerActorEmail(actorEmail);
                    existing.setReviewedAt(OffsetDateTime.now());
                    existing.setReviewNote(req.reviewNote());
                    existing.setUpdatedAt(OffsetDateTime.now());
                    return repository.save(existing)
                            .flatMap(saved -> publishAudit("REVIEW", saved, before,
                                            snapshot(saved), actorId, actorEmail)
                                    .then(publishEvent(saved, STATUS_RAISED,
                                            SuspiciousTransactionEvent.TRANSITION_REVIEW,
                                            actorId, actorEmail))
                                    .thenReturn(AmlAlertResponse.from(saved)));
                });
    }

    /**
     * REVIEWED → FILED. Regulator filing reference is required. Phase 26
     * additionally auto-generates a per-STR XLSX and (if MinIO is wired)
     * uploads it, capturing the {@code s3://} reference on
     * {@link SuspiciousTransactionAlert#setFiledXlsxRef(String)}. A
     * caller-supplied {@code filedXlsxRef} on the request is honoured only
     * when the auto-store step yields no ref — pre-uploaded evidence packs
     * (rare) still round-trip.
     *
     * <p>The auto-generation is best-effort: any failure logs + swallows
     * so the FILED transition still commits, matching the Kafka fan-out
     * posture ({@link #publishEvent}). Compliance ops can re-run the
     * export on-demand via the {@code /per-str/{alertId}/xlsx} endpoint.
     */
    @Transactional
    public Mono<AmlAlertResponse> file(UUID id, FileAmlAlertRequest req,
                                       String actorId, String actorEmail) {
        return findInStatus(id, STATUS_REVIEWED,
                "Only REVIEWED alerts can be filed")
                .flatMap(existing -> {
                    Map<String, Object> before = snapshot(existing);
                    existing.setStatus(STATUS_FILED);
                    existing.setFilerActorId(parseUuid(actorId));
                    existing.setFilerActorEmail(actorEmail);
                    existing.setFiledAt(OffsetDateTime.now());
                    existing.setFiledRef(req.filedRef());
                    existing.setUpdatedAt(OffsetDateTime.now());
                    return autoStoreFiledXlsx(existing, req.filedXlsxRef())
                            .flatMap(autoRef -> {
                                existing.setFiledXlsxRef(autoRef.orElse(null));
                                return repository.save(existing);
                            })
                            .flatMap(saved -> publishAudit("FILE", saved, before,
                                            snapshot(saved), actorId, actorEmail)
                                    .then(publishEvent(saved, STATUS_REVIEWED,
                                            SuspiciousTransactionEvent.TRANSITION_FILE,
                                            actorId, actorEmail))
                                    .thenReturn(AmlAlertResponse.from(saved)));
                });
    }

    /**
     * Best-effort auto-generation of the per-STR XLSX + MinIO upload for
     * the FILED transition. Resolves the tenant from {@link TenantContext}
     * and hands off to {@link AmlStrFilingService#renderAndStore}. Any
     * failure logs + returns the caller-supplied ref (may still be null)
     * so the transition itself never breaks on a broker/blob hiccup.
     *
     * <p>Returns {@code Optional<String>} rather than {@code String} so a
     * "no ref" outcome can be signalled through the reactive pipeline
     * (Reactor forbids {@code Mono.just(null)}).
     */
    private Mono<java.util.Optional<String>> autoStoreFiledXlsx(SuspiciousTransactionAlert alert,
                                                                String callerSuppliedRef) {
        return Mono.deferContextual(ctx -> {
            String tenantStr = TenantContext.get(ctx);
            UUID tenantId = parseUuid(tenantStr);
            if (tenantId == null) {
                log.warn("[aml-alert] auto-store XLSX skipped for alert {} — no tenant in context",
                        alert.getId());
                return Mono.just(java.util.Optional.ofNullable(callerSuppliedRef));
            }
            return strFilingService.renderAndStore(tenantId, alert)
                    .map(result -> result.filedXlsxRef()
                            .or(() -> java.util.Optional.ofNullable(callerSuppliedRef)))
                    .onErrorResume(err -> {
                        log.warn("[aml-alert] auto-store XLSX failed for alert {}: {} — "
                                + "keeping caller-supplied ref (may be null)",
                                alert.getId(), err.getMessage());
                        return Mono.just(java.util.Optional.ofNullable(callerSuppliedRef));
                    });
        });
    }

    /** RAISED|REVIEWED → CLOSED. Closes with a mandatory reason (not reportable). */
    @Transactional
    public Mono<AmlAlertResponse> close(UUID id, CloseAmlAlertRequest req,
                                        String actorId, String actorEmail) {
        return repository.findById(id)
                .switchIfEmpty(Mono.error(new IllegalArgumentException(
                        "SuspiciousTransactionAlert not found: " + id)))
                .flatMap(existing -> {
                    if (!STATUS_RAISED.equals(existing.getStatus())
                            && !STATUS_REVIEWED.equals(existing.getStatus())) {
                        return Mono.error(new IllegalStateException(
                                "Cannot close a " + existing.getStatus()
                                        + " alert - only RAISED/REVIEWED are closeable"));
                    }
                    String priorStatus = existing.getStatus();
                    Map<String, Object> before = snapshot(existing);
                    existing.setStatus(STATUS_CLOSED);
                    existing.setCloserActorId(parseUuid(actorId));
                    existing.setCloserActorEmail(actorEmail);
                    existing.setClosedAt(OffsetDateTime.now());
                    existing.setClosedReason(req.closedReason());
                    existing.setUpdatedAt(OffsetDateTime.now());
                    return repository.save(existing)
                            .flatMap(saved -> publishAudit("CLOSE", saved, before,
                                            snapshot(saved), actorId, actorEmail)
                                    .then(publishEvent(saved, priorStatus,
                                            SuspiciousTransactionEvent.TRANSITION_CLOSE,
                                            actorId, actorEmail))
                                    .thenReturn(AmlAlertResponse.from(saved)));
                });
    }

    /**
     * Paginated queue. When {@code statuses} is empty defaults to RAISED +
     * REVIEWED (the active-work slice) so the review UI opens on a useful
     * list without a query param.
     */
    public Flux<AmlAlertResponse> queue(List<String> statuses, int page, int size) {
        List<String> effective = (statuses == null || statuses.isEmpty())
                ? List.of(STATUS_RAISED, STATUS_REVIEWED)
                : statuses;
        Pageable pageable = PageRequest.of(page, size);
        return repository.findByStatusInOrderByRaisedAtDesc(effective, pageable)
                .map(AmlAlertResponse::from);
    }

    public Mono<Long> queueCount(List<String> statuses) {
        List<String> effective = (statuses == null || statuses.isEmpty())
                ? List.of(STATUS_RAISED, STATUS_REVIEWED)
                : statuses;
        return repository.countByStatusIn(effective);
    }

    public Mono<AmlAlertResponse> findById(UUID id) {
        return repository.findById(id)
                .switchIfEmpty(Mono.error(new IllegalArgumentException(
                        "SuspiciousTransactionAlert not found: " + id)))
                .map(AmlAlertResponse::from);
    }

    /** Fetch entity by id, or {@link IllegalArgumentException} — for internal callers (Phase 26 XLSX writer). */
    public Mono<SuspiciousTransactionAlert> findEntity(UUID id) {
        return repository.findById(id)
                .switchIfEmpty(Mono.error(new IllegalArgumentException(
                        "SuspiciousTransactionAlert not found: " + id)));
    }

    // ── Internals ──────────────────────────────────────────────────────────

    private Mono<SuspiciousTransactionAlert> findInStatus(UUID id, String expected,
                                                          String messagePrefix) {
        return repository.findById(id)
                .switchIfEmpty(Mono.error(new IllegalArgumentException(
                        "SuspiciousTransactionAlert not found: " + id)))
                .flatMap(existing -> {
                    if (!expected.equals(existing.getStatus())) {
                        return Mono.error(new IllegalStateException(
                                messagePrefix + " - was " + existing.getStatus()));
                    }
                    return Mono.just(existing);
                });
    }

    private static Map<String, Object> snapshot(SuspiciousTransactionAlert a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("transactionRef",       a.getTransactionRef());
        m.put("transactionType",      a.getTransactionType());
        m.put("amountNative",         a.getAmountNative() != null ? a.getAmountNative().toPlainString() : null);
        m.put("currency",             a.getCurrency());
        m.put("memberId",             a.getMemberId() != null ? a.getMemberId().toString() : null);
        m.put("providerId",           a.getProviderId() != null ? a.getProviderId().toString() : null);
        m.put("status",               a.getStatus());
        m.put("reviewNote",           a.getReviewNote());
        m.put("filedRef",             a.getFiledRef());
        m.put("filedXlsxRef",         a.getFiledXlsxRef());
        m.put("closedReason",         a.getClosedReason());
        return m;
    }

    private Mono<Void> publishAudit(String action, SuspiciousTransactionAlert alert,
                                    Map<String, Object> before, Map<String, Object> after,
                                    String actorId, String actorEmail) {
        return Mono.deferContextual(ctx -> {
            String tenantId = TenantContext.get(ctx);
            String friendly = "AML alert #" + alert.getTransactionRef();
            AuditEvent event = AuditEvent.create(
                    tenantId != null ? tenantId : "unknown",
                    ENTITY_TYPE,
                    alert.getId().toString(),
                    friendly,
                    action,
                    actorId != null ? actorId : AuditActor.SYSTEM_ID,
                    actorEmail != null ? actorEmail : AuditActor.SYSTEM_EMAIL,
                    before,
                    "DELETE".equals(action) ? null : after,
                    diff(before, after),
                    UUID.randomUUID().toString());
            return auditPublisher.publish(event);
        });
    }

    /**
     * Best-effort Kafka fan-out on every workflow transition. Failure is
     * logged but swallowed — the transition itself has already committed
     * and been audit-logged, so a downstream fan-out drop is recoverable
     * from the audit log + the {@code suspicious_transaction_alert} row.
     * Never propagate the error to the caller (never fail the transition
     * because of a broker hiccup).
     */
    private Mono<Void> publishEvent(SuspiciousTransactionAlert alert,
                                    String priorStatus,
                                    String transition,
                                    String actorId, String actorEmail) {
        return Mono.deferContextual(ctx -> {
            String tenantIdStr = TenantContext.get(ctx);
            UUID tenantId = parseUuid(tenantIdStr);
            SuspiciousTransactionEvent event = new SuspiciousTransactionEvent(
                    SuspiciousTransactionEvent.CURRENT_SCHEMA_VERSION,
                    tenantId,
                    alert.getId(),
                    alert.getTransactionRef(),
                    alert.getTransactionType(),
                    alert.getAmountNative(),
                    alert.getCurrency(),
                    alert.getMemberId(),
                    alert.getProviderId(),
                    priorStatus,
                    alert.getStatus(),
                    transition,
                    actorId != null ? actorId : AuditActor.SYSTEM_ID,
                    actorEmail != null ? actorEmail : AuditActor.SYSTEM_EMAIL,
                    Instant.now());
            return eventPublisher.publish(event)
                    .onErrorResume(err -> {
                        log.warn("[aml-alert] Kafka fan-out failed for alert {} transition {}: {}",
                                alert.getId(), transition, err.getMessage());
                        return Mono.empty();
                    });
        });
    }

    private static String[] diff(Map<String, Object> before, Map<String, Object> after) {
        if (before == null || after == null) return null;
        return before.keySet().stream()
                .filter(k -> !Objects.equals(before.get(k), after.get(k)))
                .toArray(String[]::new);
    }

    private static UUID parseUuid(String s) {
        if (s == null || s.isBlank()) return null;
        try { return UUID.fromString(s); } catch (IllegalArgumentException e) { return null; }
    }
}
