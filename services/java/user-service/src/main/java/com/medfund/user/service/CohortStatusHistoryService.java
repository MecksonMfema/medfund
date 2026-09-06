package com.medfund.user.service;

import com.medfund.shared.audit.AuditActor;
import com.medfund.shared.audit.AuditEvent;
import com.medfund.shared.audit.AuditPublisher;
import com.medfund.shared.tenant.TenantContext;
import com.medfund.user.dto.AutoTransitionRequest;
import com.medfund.user.entity.CohortStatusHistory;
import com.medfund.user.entity.Ifrs17Cohort;
import com.medfund.user.exception.Ifrs17CohortNotFoundException;
import com.medfund.user.repository.CohortStatusHistoryRepository;
import com.medfund.user.repository.Ifrs17CohortRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Phase 15 §4 (I11) — append-only journal of IFRS 17 cohort_type transitions.
 *
 * <p>Two entry points:
 * <ul>
 *   <li>{@link #recordTransition} — the raw call, used by both MANUAL (from
 *       {@link Ifrs17CohortService#update}) and AUTO (Phase 15 §15 onerous
 *       test) callers.</li>
 *   <li>{@link #findByCohortId} — lists history for the admin UI tab.</li>
 * </ul>
 *
 * <p>Every insert emits an {@link AuditEvent}; AUTO inserts additionally
 * publish an {@code medfund.ifrs17.material-event} via
 * {@link Ifrs17MaterialEventPublisher} (real Kafka producer lands in §19).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CohortStatusHistoryService {

    static final String AUDIT_ENTITY_TYPE = "CohortStatusHistory";
    static final String AUDIT_ACTION = "CREATE";
    static final String MATERIAL_EVENT_ONEROUS_TRANSITION = "ONEROUS_TRANSITION";

    private final CohortStatusHistoryRepository repository;
    private final Ifrs17CohortRepository cohortRepository;
    private final R2dbcEntityTemplate r2dbcTemplate;
    private final DatabaseClient databaseClient;
    private final AuditPublisher auditPublisher;
    private final Ifrs17MaterialEventPublisher materialEventPublisher;
    private final CohortLossComponentService lossComponentService;

    public Flux<CohortStatusHistory> findByCohortId(UUID cohortId) {
        return cohortRepository.findById(cohortId)
                .switchIfEmpty(Mono.error(new Ifrs17CohortNotFoundException(cohortId)))
                .flatMapMany(existing -> repository.findByCohortIdOrderByEffectiveAtDesc(cohortId));
    }

    /**
     * Phase 15 §15 (I11) — service-to-service callback from ai-service
     * onerous-test compute. Flips {@code ifrs17_cohort.cohort_type},
     * appends a {@code cohort_status_history} row (source=AUTO), and
     * — on AUTO_TEST_FAILED — writes an INITIAL_RECOGNITION loss
     * component movement for the positive gap. All three DB writes
     * land inside one transaction; the AuditEvent + material event
     * publishes fire best-effort after commit.
     *
     * <p>Idempotency is scoped to (cohortId, sourceRunId) — if a row
     * already exists the caller receives that row back and no new
     * mutation or event fires. That matches the retry semantics of
     * the outbox publisher driving the ai-service callback.
     *
     * <p>Defensive fromStatus check: if the cohort's current
     * cohort_type doesn't match the caller's fromStatus, the request
     * is rejected with 409. The onerous test ran against a snapshot
     * that no longer holds — the caller must re-read + re-decide.
     */
    @Transactional
    public Mono<CohortStatusHistory> recordAutoTransition(UUID cohortId, AutoTransitionRequest request) {
        // Cross-field guard hoisted BEFORE any DB write so a bad request rolls
        // back cleanly (nothing to roll back). Validating inside the reactive
        // chain would let cohort update + status-history insert land before the
        // loss-component insert fires the error — the @Transactional wrap would
        // still roll the row inserts back, but the AuditEvent / material event
        // publishes for those rows fire outside the DB transaction.
        if (hasLossComponent(request)
                && (request.currency() == null || request.currency().isBlank())) {
            return Mono.error(new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "currency is required when lossComponentAmount is supplied"));
        }
        return repository.findByCohortIdAndSourceRunId(cohortId, request.sourceRunId())
                .doOnNext(existing -> log.debug(
                        "auto-transition already recorded for cohortId={} sourceRunId={} - returning existing row {}",
                        cohortId, request.sourceRunId(), existing.getId()))
                .switchIfEmpty(Mono.defer(() -> performAutoTransition(cohortId, request)));
    }

    private Mono<CohortStatusHistory> performAutoTransition(UUID cohortId, AutoTransitionRequest request) {
        return cohortRepository.findById(cohortId)
                .switchIfEmpty(Mono.error(new Ifrs17CohortNotFoundException(cohortId)))
                .flatMap(cohort -> {
                    if (!java.util.Objects.equals(cohort.getCohortType(), request.fromStatus())) {
                        return Mono.<Ifrs17Cohort>error(new ResponseStatusException(
                                HttpStatus.CONFLICT,
                                "cohort.cohort_type (" + cohort.getCohortType() + ") does not match "
                                        + "request.fromStatus (" + request.fromStatus() + ") - "
                                        + "the ai-service onerous-test ran against a stale snapshot; "
                                        + "re-read and re-decide"));
                    }
                    // Targeted UPDATE via DatabaseClient rather than repository.save() —
                    // save() writes every column and would try to bind the entity's String
                    // ``lockedInYieldCurveSnapshot`` into the JSONB column, which Postgres
                    // rejects (42804). Same trick as ``Ifrs17CohortLockInService.writeLockIn``.
                    return databaseClient.sql("""
                                    UPDATE ifrs17_cohort
                                       SET cohort_type = :toStatus,
                                           updated_at = :now
                                     WHERE id = :id
                                    """)
                            .bind("toStatus", request.toStatus())
                            .bind("now", Instant.now())
                            .bind("id", cohort.getId())
                            .fetch()
                            .rowsUpdated()
                            .thenReturn(cohort);
                })
                .flatMap(cohort -> recordTransition(
                                cohort.getId(),
                                request.fromStatus(),
                                request.toStatus(),
                                request.transitionReason(),
                                "AUTO",
                                request.sourceRunId(),
                                null,
                                AuditActor.SYSTEM_EMAIL,
                                request.reasonNote())
                        .flatMap(statusRow -> maybeRecordLossComponent(cohortId, request)
                                .thenReturn(statusRow)));
    }

    private boolean hasLossComponent(AutoTransitionRequest request) {
        return request.lossComponentAmount() != null && request.lossComponentAmount().signum() > 0;
    }

    private Mono<Void> maybeRecordLossComponent(UUID cohortId, AutoTransitionRequest request) {
        if (!hasLossComponent(request)) {
            return Mono.empty();
        }
        return lossComponentService.recordMovement(
                        cohortId,
                        "INITIAL_RECOGNITION",
                        request.lossComponentAmount(),
                        request.currency(),
                        request.sourceRunId(),
                        null,
                        AuditActor.SYSTEM_EMAIL,
                        "AUTO onerous test flagged loss component (" + request.transitionReason() + ")")
                .then();
    }

    /**
     * Appends a transition row. Callers hand in the observed from/to statuses
     * and the transition metadata; nothing here mutates {@code ifrs17_cohort}
     * itself — that's the caller's job.
     *
     * @param source {@code AUTO} or {@code MANUAL}
     * @param sourceRunId links to the report_job that triggered an AUTO row; may be null for MANUAL
     */
    public Mono<CohortStatusHistory> recordTransition(UUID cohortId, String fromStatus, String toStatus,
                                                      String transitionReason, String source,
                                                      UUID sourceRunId, UUID actorId, String actorEmail,
                                                      String reasonNote) {
        return cohortRepository.findById(cohortId)
                .switchIfEmpty(Mono.error(new Ifrs17CohortNotFoundException(cohortId)))
                .flatMap(cohort -> insert(cohort, fromStatus, toStatus, transitionReason,
                        source, sourceRunId, actorId, actorEmail, reasonNote))
                .flatMap(saved -> publishAudit(saved).thenReturn(saved))
                .flatMap(saved -> maybePublishMaterialEvent(saved).thenReturn(saved));
    }

    private Mono<CohortStatusHistory> insert(Ifrs17Cohort cohort, String fromStatus, String toStatus,
                                             String transitionReason, String source,
                                             UUID sourceRunId, UUID actorId, String actorEmail,
                                             String reasonNote) {
        var row = new CohortStatusHistory();
        // Leave id null per bug_r2dbc_pre_populated_id_update_mode — DB default gen_random_uuid().
        row.setCohortId(cohort.getId());
        row.setFromStatus(fromStatus);
        row.setToStatus(toStatus);
        row.setTransitionReason(transitionReason);
        row.setTransitionSource(source);
        row.setSourceRunId(sourceRunId);
        row.setEffectiveAt(Instant.now());
        row.setReasonNote(reasonNote);
        row.setActorId(actorId);
        row.setActorEmail(actorEmail);
        return r2dbcTemplate.insert(row);
    }

    private Mono<Void> publishAudit(CohortStatusHistory row) {
        return Mono.deferContextual(ctx -> {
            String tenantId = TenantContext.get(ctx);
            Map<String, Object> newValue = new HashMap<>();
            newValue.put("cohortId", row.getCohortId());
            newValue.put("fromStatus", row.getFromStatus());
            newValue.put("toStatus", row.getToStatus());
            newValue.put("transitionReason", row.getTransitionReason());
            newValue.put("transitionSource", row.getTransitionSource());
            newValue.put("sourceRunId", row.getSourceRunId());
            newValue.put("reasonNote", row.getReasonNote());
            var event = AuditEvent.create(
                    tenantId != null ? tenantId : "unknown",
                    AUDIT_ENTITY_TYPE,
                    row.getId().toString(),
                    row.getFromStatus() + " → " + row.getToStatus(),
                    AUDIT_ACTION,
                    row.getActorId() != null ? row.getActorId().toString() : null,
                    row.getActorEmail(),
                    null,
                    newValue,
                    new String[]{"fromStatus", "toStatus", "transitionReason", "transitionSource"},
                    UUID.randomUUID().toString()
            );
            return auditPublisher.publish(event);
        });
    }

    private Mono<Void> maybePublishMaterialEvent(CohortStatusHistory row) {
        if (!"AUTO".equals(row.getTransitionSource())) {
            return Mono.empty();
        }
        String message = "Cohort " + row.getCohortId() + " transitioned "
                + row.getFromStatus() + " → " + row.getToStatus()
                + " (reason=" + row.getTransitionReason() + ")";
        String severity = "ONEROUS".equals(row.getToStatus()) ? "WARN" : "INFO";
        return materialEventPublisher.publish(
                row.getCohortId(),
                MATERIAL_EVENT_ONEROUS_TRANSITION,
                severity,
                message,
                row.getSourceRunId());
    }
}
