package com.medfund.finance.report.scheduler;

import com.medfund.finance.report.entity.ReportJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Nightly cleanup of {@code report_job} — replaces Phase 14's
 * {@code ActuarialJobRetentionJob} straight per the Phase 1 rename
 * deviation (I28 retention-class split).
 *
 * <p>Three-class purge:
 * <ul>
 *   <li>{@link ReportJob#RETENTION_OPERATIONAL_90D OPERATIONAL_90D} — the
 *       Phase 14 default: delete rows older than 90 days; then for each
 *       {@code (tenant_id, report_key)} keep only the 20 most recent
 *       runs.</li>
 *   <li>{@link ReportJob#RETENTION_STATUTORY_7Y STATUTORY_7Y} — the
 *       IFRS 17 / regulatory default: delete rows older than 7 years,
 *       no top-N trim.</li>
 *   <li>{@link ReportJob#RETENTION_SIU_CASE_7Y SIU_CASE_7Y} — Phase 19 §A:
 *       {@code FRAUD_SIU_REPORT} runs kept for 7 years to align with insurance-
 *       fraud statute (ZW Insurance Act 2019 §137; POPIA §5(e) equivalent).
 *       No top-N trim.</li>
 * </ul>
 *
 * <p>Chunks + MinIO payload references are removed by the
 * {@code report_job_chunk.parent_job_id} FK cascade — the {@code result_ref}
 * MinIO objects themselves are best-effort cleaned by the aggregator (§18)
 * at chunk-completion time.
 *
 * <p>Runs against every tenant schema via {@link DatabaseClient}; the SQL
 * itself is schema-neutral (unqualified {@code report_job}) so a per-tenant
 * dispatch loop is not needed for the shared static schema-per-tenant
 * routing already in place. Idempotent (DELETE … WHERE) so a duplicate
 * cron fire under a rare restart is harmless.
 *
 * <p>Cron string configurable via {@code report.retention.cron} to make
 * the IT deterministic — production leaves the default {@code 0 0 2 * * *}
 * (nightly 02:00 sweep).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReportJobRetentionJob {

    static final int OPERATIONAL_RETENTION_DAYS = 90;
    static final int OPERATIONAL_RUNS_PER_KEY_LIMIT = 20;
    static final int STATUTORY_RETENTION_DAYS = 365 * 7;
    static final int SIU_CASE_RETENTION_DAYS = 365 * 7;

    private final DatabaseClient databaseClient;

    @Value("${report.retention.enabled:true}")
    private boolean enabled;

    @Scheduled(cron = "${report.retention.cron:0 0 2 * * *}")
    public void purge() {
        if (!enabled) {
            log.debug("[report-retention] disabled by config — skipping");
            return;
        }
        runOnce()
                .doOnSuccess(deleted -> log.info("[report-retention] deleted {} rows", deleted))
                .doOnError(e -> log.error("[report-retention] failed: {}", e.getMessage(), e))
                .subscribe();
    }

    /** Package-private for unit-testing: returns the count of deleted rows. */
    Mono<Long> runOnce() {
        return databaseClient.sql("""
                        WITH by_age_op AS (
                            DELETE FROM report_job
                             WHERE retention_class = 'OPERATIONAL_90D'
                               AND requested_at < NOW() - INTERVAL '%d days'
                            RETURNING job_id
                        ),
                        ranked_op AS (
                            SELECT job_id,
                                   ROW_NUMBER() OVER (
                                       PARTITION BY tenant_id, report_key
                                       ORDER BY requested_at DESC
                                   ) AS rn
                              FROM report_job
                             WHERE retention_class = 'OPERATIONAL_90D'
                        ),
                        by_count_op AS (
                            DELETE FROM report_job j
                             USING ranked_op r
                             WHERE j.job_id = r.job_id
                               AND r.rn > %d
                            RETURNING j.job_id
                        ),
                        by_age_stat AS (
                            DELETE FROM report_job
                             WHERE retention_class = 'STATUTORY_7Y'
                               AND requested_at < NOW() - INTERVAL '%d days'
                            RETURNING job_id
                        ),
                        by_age_siu AS (
                            DELETE FROM report_job
                             WHERE retention_class = 'SIU_CASE_7Y'
                               AND requested_at < NOW() - INTERVAL '%d days'
                            RETURNING job_id
                        )
                        SELECT (SELECT COUNT(*) FROM by_age_op)
                             + (SELECT COUNT(*) FROM by_count_op)
                             + (SELECT COUNT(*) FROM by_age_stat)
                             + (SELECT COUNT(*) FROM by_age_siu) AS deleted
                        """.formatted(
                                OPERATIONAL_RETENTION_DAYS,
                                OPERATIONAL_RUNS_PER_KEY_LIMIT,
                                STATUTORY_RETENTION_DAYS,
                                SIU_CASE_RETENTION_DAYS))
                .map((row, meta) -> row.get("deleted", Long.class))
                .one()
                .defaultIfEmpty(0L);
    }
}
