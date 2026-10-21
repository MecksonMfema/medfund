package com.medfund.contributions.premium.service;

import com.medfund.contributions.client.UserServiceClient;
import com.medfund.contributions.premium.entity.EarningSchedule;
import com.medfund.contributions.premium.entity.EarningScheduleRun;
import com.medfund.contributions.premium.repository.EarningScheduleRepository;
import com.medfund.contributions.premium.repository.EarningScheduleRunRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Nightly period-close pass for {@link PremiumEarningExecutor} plus the
 * shared plumbing used by backfill and (in Phase 9) endorsement recompute
 * paths. Closes {@code earning_schedule} rows whose {@code period_end} is
 * strictly before {@code today} by copying {@code written_amount} into
 * {@code earned_at_period_end} (the "linear-earn-within-period" contract
 * for both HEALTH and annual-bind strips per Phase 12 §A U10). Chunked so
 * a tenant with 1M+ unclosed rows still fits inside the job's cooperative
 * cancel window.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EarningScheduleClosureService {

    /** Grill note 5 — 5k policies per commit chunk. */
    static final int CHUNK_SIZE = 5000;

    private final EarningScheduleRepository earningScheduleRepository;
    private final EarningScheduleRunRepository earningScheduleRunRepository;
    private final DatabaseClient db;
    private final UserServiceClient userServiceClient;

    /** Entry point called by {@link PremiumEarningExecutor}. */
    public Mono<Void> closeExpiredPeriodsForTenant(String tenantId) {
        UUID tenantUuid = parseUuid(tenantId);
        LocalDate asOf = LocalDate.now();
        return startRun(tenantUuid, "SCHEDULED", null)
                .flatMap(run -> earningScheduleRepository
                        .findByPeriodEndBeforeAndEarnedAtPeriodEndIsNullAndClosureFalse(asOf)
                        .window(CHUNK_SIZE)
                        .concatMap(chunk -> chunk
                                .flatMap(this::closeOne, 4)
                                .count()
                                .flatMap(closed -> heartbeat(run.getId(), closed.intValue())))
                        .then(finish(run.getId(), "COMPLETED", null))
                        .onErrorResume(err -> {
                            log.error("PremiumEarningExecutor tenant={} failed: {}", tenantId, err.getMessage(), err);
                            return finish(run.getId(), "FAILED", err.getMessage());
                        }))
                .then(refreshMemberFirstContribution())
                .then(refreshMemberContributionPresence())
                .then();
    }

    Mono<EarningSchedule> closeOne(EarningSchedule row) {
        row.setEarnedAtPeriodEnd(row.getWrittenAmount());
        return earningScheduleRepository.save(row);
    }

    Mono<EarningScheduleRun> startRun(UUID tenantId, String kind, UUID triggerReference) {
        EarningScheduleRun run = new EarningScheduleRun();
        run.setTenantId(tenantId);
        run.setRunKind(kind);
        run.setTriggerReference(triggerReference);
        run.setStatus("RUNNING");
        run.setStartedAt(Instant.now());
        run.setLastHeartbeatAt(Instant.now());
        return earningScheduleRunRepository.save(run);
    }

    Mono<EarningScheduleRun> heartbeat(UUID runId, int deltaPeriodsWritten) {
        return earningScheduleRunRepository.findById(runId)
                .flatMap(run -> {
                    run.setLastHeartbeatAt(Instant.now());
                    run.setPeriodsWritten(run.getPeriodsWritten() + deltaPeriodsWritten);
                    return earningScheduleRunRepository.save(run);
                });
    }

    Mono<EarningScheduleRun> finish(UUID runId, String status, String errorMessage) {
        return earningScheduleRunRepository.findById(runId)
                .flatMap(run -> {
                    run.setStatus(status);
                    run.setFinishedAt(Instant.now());
                    if (errorMessage != null) run.setErrorMessage(errorMessage);
                    return earningScheduleRunRepository.save(run);
                });
    }

    /**
     * REFRESH the {@code member_first_contribution} materialised view so
     * the {@code NEW_BUSINESS_REGISTER} report (Phase 6) sees today's new
     * HEALTH members (grill note 7). Best-effort — a failure here is
     * logged but does not fail the executor pass.
     */
    Mono<Void> refreshMemberFirstContribution() {
        return db.sql("REFRESH MATERIALIZED VIEW member_first_contribution")
                .fetch()
                .rowsUpdated()
                .then()
                .onErrorResume(err -> {
                    log.warn("REFRESH MATERIALIZED VIEW member_first_contribution failed: {}", err.getMessage());
                    return Mono.empty();
                });
    }

    /**
     * Phase 13 §C Phase 7 per L16 + grill note 4. REFRESH the
     * {@code member_contribution_presence} matview so
     * {@code PersistencyCohortReportService}'s HEALTH branch sees the
     * previous nightly cycle's contributions on its next report run, and
     * stamp the freshness timestamp on the newest run row so the report
     * can raise a >24h-stale warning.
     *
     * <p>Chained after {@link #refreshMemberFirstContribution()} inside
     * {@link #closeExpiredPeriodsForTenant(String)} — same best-effort
     * shape (a REFRESH failure is logged but does not fail the executor,
     * matching the sibling refresh).
     */
    public Mono<Void> refreshMemberContributionPresence() {
        return db.sql("REFRESH MATERIALIZED VIEW member_contribution_presence")
                .fetch()
                .rowsUpdated()
                .then(db.sql("""
                        UPDATE earning_schedule_run
                           SET contrib_presence_refresh_at = NOW()
                         WHERE id = (SELECT id FROM earning_schedule_run
                                      ORDER BY started_at DESC LIMIT 1)
                        """)
                        .fetch()
                        .rowsUpdated()
                        .then())
                .doOnSuccess(v -> log.debug("member_contribution_presence refreshed"))
                .onErrorResume(err -> {
                    log.warn("REFRESH MATERIALIZED VIEW member_contribution_presence failed: {}",
                            err.getMessage());
                    return Mono.empty();
                });
    }

    /**
     * Endorsement retro-recompute path (Phase 12 §C Phase 9). Deletes prior
     * endorsement rows for the same {@code endorsementId} (idempotent
     * replay) and mints one new {@code is_endorsement=TRUE} row per period
     * on or after {@code effectiveFrom} carrying the pro-rated
     * {@code premiumDelta}. Closed base periods (already-earned) get their
     * matching endorsement row closed at write-time so
     * {@code UPR_MOVEMENT} adds up.
     *
     * <p>Reads run under a fresh {@code earning_schedule_run} row with
     * {@code run_kind=ENDORSEMENT_RECOMPUTE} and the endorsement id in
     * {@code trigger_reference}. On successful completion the user-service
     * endorsement is flipped {@code COMMITTED → COMPUTED} via
     * {@link UserServiceClient#markEndorsementComputed}; a failure at that
     * hop is logged (the recompute has already succeeded and is idempotent
     * so an operator can flip the status manually).
     */
    public Mono<EarningScheduleRun> recomputeForEndorsement(String tenantId,
                                                            UUID endorsementId,
                                                            UUID policyId,
                                                            String policySource,
                                                            LocalDate effectiveFrom,
                                                            BigDecimal premiumDelta,
                                                            String currencyCode) {
        if (endorsementId == null || policyId == null || policySource == null || effectiveFrom == null) {
            return Mono.error(new IllegalArgumentException(
                    "recomputeForEndorsement requires endorsementId, policyId, policySource, effectiveFrom"));
        }
        UUID tenantUuid = parseUuid(tenantId);
        return startRun(tenantUuid, "ENDORSEMENT_RECOMPUTE", endorsementId)
                .flatMap(run -> earningScheduleRepository.deleteByEndorsementId(endorsementId)
                        .thenMany(earningScheduleRepository
                                .findByPolicyIdAndPolicySourceAndPeriodStartGreaterThanEqual(
                                        policyId, policySource, effectiveFrom)
                                .filter(row -> !row.isEndorsement()))
                        .collectList()
                        .flatMap(basePeriods -> writeEndorsementRows(basePeriods, endorsementId,
                                premiumDelta, currencyCode)
                                .flatMap(written -> heartbeat(run.getId(), written)
                                        .thenReturn(written)))
                        .flatMap(written -> finish(run.getId(), "COMPLETED", null))
                        .flatMap(finished -> userServiceClient.markEndorsementComputed(endorsementId)
                                .thenReturn(finished))
                        .onErrorResume(err -> {
                            log.error("recomputeForEndorsement endorsementId={} tenant={} failed: {}",
                                    endorsementId, tenantId, err.getMessage(), err);
                            return finish(run.getId(), "FAILED", err.getMessage());
                        }));
    }

    Mono<Integer> writeEndorsementRows(List<EarningSchedule> basePeriods, UUID endorsementId,
                                       BigDecimal premiumDelta, String currencyCode) {
        if (basePeriods.isEmpty() || premiumDelta == null || premiumDelta.signum() == 0) {
            return Mono.just(0);
        }
        List<EarningSchedule> endorsementRows = apportionDelta(basePeriods, endorsementId,
                premiumDelta, currencyCode);
        return Flux.fromIterable(endorsementRows)
                .flatMap(earningScheduleRepository::save, 4)
                .count()
                .map(Long::intValue);
    }

    /**
     * Split {@code premiumDelta} across {@code basePeriods} in the same
     * days-in-period proportion the base strip used, so the endorsement
     * strip lines up with the base periods one-for-one. Final row absorbs
     * rounding drift so the endorsement sum equals {@code premiumDelta}
     * exactly (matches {@code PremiumEarningStripCalculator}'s last-row
     * absorption policy).
     */
    private static List<EarningSchedule> apportionDelta(List<EarningSchedule> basePeriods,
                                                        UUID endorsementId,
                                                        BigDecimal premiumDelta,
                                                        String currencyCode) {
        long totalDays = 0;
        long[] periodDays = new long[basePeriods.size()];
        for (int i = 0; i < basePeriods.size(); i++) {
            EarningSchedule base = basePeriods.get(i);
            long days = java.time.temporal.ChronoUnit.DAYS.between(
                    base.getPeriodStart(), base.getPeriodEnd()) + 1;
            periodDays[i] = days;
            totalDays += days;
        }
        BigDecimal totalDaysBd = BigDecimal.valueOf(totalDays);
        BigDecimal running = BigDecimal.ZERO;
        java.util.ArrayList<EarningSchedule> out = new java.util.ArrayList<>(basePeriods.size());
        for (int i = 0; i < basePeriods.size(); i++) {
            EarningSchedule base = basePeriods.get(i);
            BigDecimal share;
            if (i == basePeriods.size() - 1) {
                share = premiumDelta.subtract(running).setScale(4, RoundingMode.HALF_EVEN);
            } else {
                share = premiumDelta.multiply(BigDecimal.valueOf(periodDays[i]))
                        .divide(totalDaysBd, 4, RoundingMode.HALF_EVEN);
                running = running.add(share);
            }

            EarningSchedule row = new EarningSchedule();
            row.setPolicyId(base.getPolicyId());
            row.setPolicySource(base.getPolicySource());
            row.setInsuranceLine(base.getInsuranceLine());
            row.setPeriodStart(base.getPeriodStart());
            row.setPeriodEnd(base.getPeriodEnd());
            row.setWrittenAmount(share);
            row.setEarnedAtPeriodEnd(base.getEarnedAtPeriodEnd() != null ? share : null);
            row.setCurrencyCode(currencyCode != null ? currencyCode : base.getCurrencyCode());
            row.setEndorsement(true);
            row.setEndorsementId(endorsementId);
            row.setPortfolioId(base.getPortfolioId());
            row.setCohortId(base.getCohortId());
            row.setEarningMethod(base.getEarningMethod());
            out.add(row);
        }
        return out;
    }

    // ── Phase 13 §B per L6 + grill note 5 ────────────────────────────────
    // Policy-status lifecycle hooks: close / freeze / resume / reinstate the
    // earning strip when PolicyStatusChangedConsumer receives a status event.
    // Idempotency across Kafka redeliveries is guarded by the closure_ref
    // column (V115) — every close / freeze write stamps the row with the
    // event's ref, and the resume / reinstate paths simply reverse rows that
    // still carry the corresponding is_closure flag.

    /**
     * LAPSED / TERMINATED path. Marks every future period (starting on or
     * after {@code effectiveDate}) whose earned value is still open as a
     * closure row: {@code is_closure=TRUE}, {@code earned_at_period_end=0},
     * {@code closure_ref=<ref>}. The nightly {@code PremiumEarningExecutor}
     * skips rows with {@code is_closure=TRUE} so a closed period does not
     * subsequently linear-earn.
     *
     * <p>Idempotency: if any row already carries {@code closure_ref=ref},
     * the update is skipped and 0 is returned. A redelivered Kafka event
     * therefore fans out to exactly one write.
     */
    public Mono<Long> closeOutForPolicyClosure(String tenantId, UUID policyId, String policySource,
                                               LocalDate effectiveDate, UUID closureRef) {
        if (policyId == null || policySource == null || effectiveDate == null || closureRef == null) {
            return Mono.error(new IllegalArgumentException(
                    "closeOutForPolicyClosure requires policyId, policySource, effectiveDate, closureRef"));
        }
        return db.sql("SELECT COUNT(*) FROM earning_schedule WHERE closure_ref = :ref")
                .bind("ref", closureRef)
                .map((row, meta) -> row.get(0, Long.class))
                .one()
                .defaultIfEmpty(0L)
                .flatMap(existing -> {
                    if (existing > 0) {
                        log.debug("closeOutForPolicyClosure: closure_ref {} already applied — idempotent skip",
                                closureRef);
                        return Mono.just(0L);
                    }
                    return db.sql("""
                            UPDATE earning_schedule
                               SET earned_at_period_end = 0,
                                   is_closure           = TRUE,
                                   closure_ref          = :ref,
                                   updated_at           = NOW()
                             WHERE policy_id            = :policyId
                               AND policy_source        = :source
                               AND period_start        >= :effectiveDate
                               AND earned_at_period_end IS NULL
                            """)
                            .bind("ref", closureRef)
                            .bind("policyId", policyId)
                            .bind("source", policySource)
                            .bind("effectiveDate", effectiveDate)
                            .fetch().rowsUpdated()
                            .doOnSuccess(n -> log.info(
                                    "closeOutForPolicyClosure policy={} source={} effective={} closed {} periods (ref={})",
                                    policyId, policySource, effectiveDate, n, closureRef));
                });
    }

    /**
     * SUSPENDED path. Marks every future open period as frozen —
     * {@code is_closure=TRUE}, {@code closure_ref=<ref>},
     * {@code earned_at_period_end} untouched (stays NULL). The nightly
     * executor skips closure rows so a frozen period does not accrue.
     * Reversal is via {@link #resumePolicyEarning}.
     */
    public Mono<Long> freezePolicyEarning(String tenantId, UUID policyId, String policySource,
                                          LocalDate effectiveDate, UUID closureRef) {
        if (policyId == null || policySource == null || effectiveDate == null || closureRef == null) {
            return Mono.error(new IllegalArgumentException(
                    "freezePolicyEarning requires policyId, policySource, effectiveDate, closureRef"));
        }
        return db.sql("SELECT COUNT(*) FROM earning_schedule WHERE closure_ref = :ref")
                .bind("ref", closureRef)
                .map((row, meta) -> row.get(0, Long.class))
                .one()
                .defaultIfEmpty(0L)
                .flatMap(existing -> {
                    if (existing > 0) {
                        log.debug("freezePolicyEarning: closure_ref {} already applied — idempotent skip",
                                closureRef);
                        return Mono.just(0L);
                    }
                    return db.sql("""
                            UPDATE earning_schedule
                               SET is_closure  = TRUE,
                                   closure_ref = :ref,
                                   updated_at  = NOW()
                             WHERE policy_id            = :policyId
                               AND policy_source        = :source
                               AND period_start        >= :effectiveDate
                               AND earned_at_period_end IS NULL
                               AND is_closure           = FALSE
                            """)
                            .bind("ref", closureRef)
                            .bind("policyId", policyId)
                            .bind("source", policySource)
                            .bind("effectiveDate", effectiveDate)
                            .fetch().rowsUpdated()
                            .doOnSuccess(n -> log.info(
                                    "freezePolicyEarning policy={} source={} effective={} froze {} periods (ref={})",
                                    policyId, policySource, effectiveDate, n, closureRef));
                });
    }

    /**
     * ACTIVE (from SUSPENDED) path. Clears the freeze on every future
     * period on or after {@code effectiveDate} — the nightly executor
     * picks the rows up again on the next pass and linear-earns them.
     * Only rows that are frozen (still {@code earned_at_period_end IS NULL})
     * are touched; already-closed lapse/terminate rows (earned=0) are
     * left alone so an operator can't accidentally reinstate a terminated
     * policy via a suspend-unwind path.
     */
    public Mono<Long> resumePolicyEarning(String tenantId, UUID policyId, String policySource,
                                          LocalDate effectiveDate) {
        if (policyId == null || policySource == null || effectiveDate == null) {
            return Mono.error(new IllegalArgumentException(
                    "resumePolicyEarning requires policyId, policySource, effectiveDate"));
        }
        return db.sql("""
                UPDATE earning_schedule
                   SET is_closure  = FALSE,
                       closure_ref = NULL,
                       updated_at  = NOW()
                 WHERE policy_id            = :policyId
                   AND policy_source        = :source
                   AND period_start        >= :effectiveDate
                   AND is_closure           = TRUE
                   AND earned_at_period_end IS NULL
                """)
                .bind("policyId", policyId)
                .bind("source", policySource)
                .bind("effectiveDate", effectiveDate)
                .fetch().rowsUpdated()
                .doOnSuccess(n -> log.info(
                        "resumePolicyEarning policy={} source={} effective={} resumed {} periods",
                        policyId, policySource, effectiveDate, n));
    }

    /**
     * ACTIVE (from LAPSED / TERMINATED) — REINSTATE path. Reverses the
     * closure on every future period on or after {@code effectiveDate}:
     * clears {@code earned_at_period_end} back to NULL, drops
     * {@code is_closure}, drops {@code closure_ref}. The nightly executor
     * subsequently closes the periods at their original
     * {@code written_amount} — this is the "pro-rata = 1.0 of original"
     * reinstate path (grill note 5); a tenant-configurable partial
     * reinstate is deferred to a follow-up phase.
     *
     * <p>Only closure rows (is_closure=TRUE with earned=0) are reversed;
     * frozen rows (earned still NULL) are left alone.
     */
    public Mono<Long> reinstatePolicyEarning(String tenantId, UUID policyId, String policySource,
                                             LocalDate effectiveDate) {
        if (policyId == null || policySource == null || effectiveDate == null) {
            return Mono.error(new IllegalArgumentException(
                    "reinstatePolicyEarning requires policyId, policySource, effectiveDate"));
        }
        return db.sql("""
                UPDATE earning_schedule
                   SET earned_at_period_end = NULL,
                       is_closure           = FALSE,
                       closure_ref          = NULL,
                       updated_at           = NOW()
                 WHERE policy_id            = :policyId
                   AND policy_source        = :source
                   AND period_start        >= :effectiveDate
                   AND is_closure           = TRUE
                   AND earned_at_period_end = 0
                """)
                .bind("policyId", policyId)
                .bind("source", policySource)
                .bind("effectiveDate", effectiveDate)
                .fetch().rowsUpdated()
                .doOnSuccess(n -> log.info(
                        "reinstatePolicyEarning policy={} source={} effective={} reinstated {} periods",
                        policyId, policySource, effectiveDate, n));
    }

    /** Ad-hoc backfill trigger for a specific policy — used by the admin controller. */
    public Mono<EarningScheduleRun> backfillPolicy(String tenantId, UUID policyId, String policySource) {
        UUID tenantUuid = parseUuid(tenantId);
        return startRun(tenantUuid, "BACKFILL", policyId)
                .flatMap(run -> earningScheduleRepository
                        .findByPolicyIdAndPolicySource(policyId, policySource)
                        .filter(r -> r.getEarnedAtPeriodEnd() == null && r.getPeriodEnd().isBefore(LocalDate.now()))
                        .flatMap(this::closeOne, 4)
                        .count()
                        .flatMap(closed -> finish(run.getId(), "COMPLETED", null)
                                .doOnNext(saved -> saved.setPeriodsWritten(closed.intValue())))
                        .onErrorResume(err -> finish(run.getId(), "FAILED", err.getMessage())));
    }

    private static UUID parseUuid(String s) {
        if (s == null || s.isBlank()) return null;
        try { return UUID.fromString(s); } catch (IllegalArgumentException e) { return null; }
    }
}
