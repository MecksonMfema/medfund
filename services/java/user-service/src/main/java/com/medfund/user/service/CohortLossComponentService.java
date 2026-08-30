package com.medfund.user.service;

import com.medfund.shared.audit.AuditEvent;
import com.medfund.shared.audit.AuditPublisher;
import com.medfund.shared.tenant.TenantContext;
import com.medfund.user.entity.CohortLossComponentHistory;
import com.medfund.user.entity.Ifrs17Cohort;
import com.medfund.user.exception.Ifrs17CohortNotFoundException;
import com.medfund.user.repository.CohortLossComponentHistoryRepository;
import com.medfund.user.repository.Ifrs17CohortRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Phase 15 §5 (I19) — append-only movement journal for IFRS 17 loss
 * component balances + matview-backed opening-balance lookup.
 *
 * <p>Two entry points for writes:
 * <ul>
 *   <li>{@link #recordMovement} — the raw call; used by both MANUAL (admin
 *       adjustment) and AUTO (Phase 15 §15 onerous test) callers.</li>
 *   <li>{@link #findByCohortId} — lists movements for the admin UI tab.</li>
 * </ul>
 *
 * <p>The {@code cohort_loss_component_current} matview is refreshed via
 * {@link #refreshMatview()} at the end of each report run (§17 controller).
 * {@link #openingBalance(UUID, String)} reads from the matview for
 * opening-balance lookups used by §17 shaping.
 *
 * <p>Every insert emits an {@link AuditEvent} per Rule 8. Composes with
 * §4 {@link CohortStatusHistoryService} — onerous transitions land both a
 * status-history row and an INITIAL_RECOGNITION loss-component row from
 * the same §15 code path.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CohortLossComponentService {

    static final String AUDIT_ENTITY_TYPE = "CohortLossComponentHistory";
    static final String AUDIT_ACTION = "CREATE";

    /**
     * Movements that add to the balance. Rest subtract. The application-level
     * guard here matches the SQL CHECK constraint + the matview SUM CASE.
     */
    static final Set<String> ADD_MOVEMENTS = Set.of("INITIAL_RECOGNITION");
    static final Set<String> SUBTRACT_MOVEMENTS = Set.of(
            "RELEASE", "REVERSAL", "RECLASSIFICATION_TO_NON_ONEROUS");

    private final CohortLossComponentHistoryRepository repository;
    private final Ifrs17CohortRepository cohortRepository;
    private final R2dbcEntityTemplate r2dbcTemplate;
    private final DatabaseClient databaseClient;
    private final AuditPublisher auditPublisher;

    public Flux<CohortLossComponentHistory> findByCohortId(UUID cohortId) {
        return cohortRepository.findById(cohortId)
                .switchIfEmpty(Mono.error(new Ifrs17CohortNotFoundException(cohortId)))
                .flatMapMany(existing -> repository.findByCohortIdOrderByEffectiveAtDesc(cohortId));
    }

    /**
     * Appends a movement row. Callers hand in the movement metadata; nothing
     * here mutates the {@code ifrs17_cohort} row itself — that's the caller's
     * job (typically §15 onerous-test compute writes an INITIAL_RECOGNITION
     * row alongside flipping the cohort's status).
     *
     * @param movementType one of INITIAL_RECOGNITION / RELEASE / REVERSAL /
     *                     RECLASSIFICATION_TO_NON_ONEROUS
     * @param amount       absolute amount; the matview applies the sign per movement type
     * @param sourceRunId  links to the report_job that triggered an AUTO row;
     *                     null for MANUAL admin adjustments
     */
    public Mono<CohortLossComponentHistory> recordMovement(UUID cohortId, String movementType,
                                                           BigDecimal amount, String currency,
                                                           UUID sourceRunId, UUID actorId,
                                                           String actorEmail, String reasonNote) {
        if (amount == null || amount.signum() <= 0) {
            return Mono.error(new IllegalArgumentException(
                    "amount must be positive; movement direction is derived from movement_type"));
        }
        if (!ADD_MOVEMENTS.contains(movementType) && !SUBTRACT_MOVEMENTS.contains(movementType)) {
            return Mono.error(new IllegalArgumentException(
                    "unknown movement_type: " + movementType));
        }
        return cohortRepository.findById(cohortId)
                .switchIfEmpty(Mono.error(new Ifrs17CohortNotFoundException(cohortId)))
                .flatMap(cohort -> insert(cohort, movementType, amount, currency,
                        sourceRunId, actorId, actorEmail, reasonNote))
                .flatMap(saved -> publishAudit(saved).thenReturn(saved));
    }

    /**
     * Refreshes {@code cohort_loss_component_current} concurrently — required
     * before {@link #openingBalance(UUID, String)} reflects freshly inserted
     * movements. Called from §17 {@code Ifrs17ReportController} at the end of
     * each report run.
     */
    public Mono<Void> refreshMatview() {
        return databaseClient
                .sql("REFRESH MATERIALIZED VIEW CONCURRENTLY cohort_loss_component_current")
                .then();
    }

    /**
     * Reads the current balance for (cohort, currency). Returns {@link BigDecimal#ZERO}
     * when the matview has no row for the pair — either the cohort has no
     * loss component yet, or the matview hasn't been refreshed after the
     * first movement was inserted.
     */
    public Mono<BigDecimal> openingBalance(UUID cohortId, String currency) {
        return databaseClient
                .sql("""
                        SELECT balance FROM cohort_loss_component_current
                        WHERE cohort_id = :cohortId AND currency = :currency
                        """)
                .bind("cohortId", cohortId)
                .bind("currency", currency)
                .map((row, meta) -> row.get("balance", BigDecimal.class))
                .one()
                .defaultIfEmpty(BigDecimal.ZERO);
    }

    private Mono<CohortLossComponentHistory> insert(Ifrs17Cohort cohort, String movementType,
                                                    BigDecimal amount, String currency,
                                                    UUID sourceRunId, UUID actorId,
                                                    String actorEmail, String reasonNote) {
        var row = new CohortLossComponentHistory();
        // Leave id null per bug_r2dbc_pre_populated_id_update_mode — DB default gen_random_uuid().
        row.setCohortId(cohort.getId());
        row.setMovementType(movementType);
        row.setAmount(amount);
        row.setCurrency(currency);
        row.setSourceRunId(sourceRunId);
        row.setEffectiveAt(Instant.now());
        row.setReasonNote(reasonNote);
        row.setActorId(actorId);
        row.setActorEmail(actorEmail);
        return r2dbcTemplate.insert(row);
    }

    private Mono<Void> publishAudit(CohortLossComponentHistory row) {
        return Mono.deferContextual(ctx -> {
            String tenantId = TenantContext.get(ctx);
            Map<String, Object> newValue = new HashMap<>();
            newValue.put("cohortId", row.getCohortId());
            newValue.put("movementType", row.getMovementType());
            newValue.put("amount", row.getAmount());
            newValue.put("currency", row.getCurrency());
            newValue.put("sourceRunId", row.getSourceRunId());
            newValue.put("reasonNote", row.getReasonNote());
            var event = AuditEvent.create(
                    tenantId != null ? tenantId : "unknown",
                    AUDIT_ENTITY_TYPE,
                    row.getId().toString(),
                    row.getMovementType() + " " + row.getAmount() + " " + row.getCurrency(),
                    AUDIT_ACTION,
                    row.getActorId() != null ? row.getActorId().toString() : null,
                    row.getActorEmail(),
                    null,
                    newValue,
                    new String[]{"movementType", "amount", "currency"},
                    UUID.randomUUID().toString()
            );
            return auditPublisher.publish(event);
        });
    }
}
