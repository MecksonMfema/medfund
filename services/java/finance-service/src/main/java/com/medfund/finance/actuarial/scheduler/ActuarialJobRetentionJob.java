package com.medfund.finance.actuarial.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Nightly cleanup of {@code actuarial_report_job} per Grill note 8:
 * <ol>
 *   <li>Delete rows older than 90 days.</li>
 *   <li>For each (tenant, report_key), keep only the most recent 20 runs.</li>
 * </ol>
 *
 * <p>Runs against every tenant schema — driven by the shared static list
 * of tenant schemas resolved from {@code public.tenants} rather than a
 * request-scoped {@code TenantContext}. Idempotent (DELETE ... WHERE) so a
 * duplicate cron fire under a rare restart is harmless.
 *
 * <p>Cron string configurable via {@code actuarial.retention.cron} to make
 * the IT deterministic — production leaves the default {@code 0 0 2 * * *}.
 * The plan (Phase 9 §6) calls for a nightly 02:00 sweep.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ActuarialJobRetentionJob {

    static final int RETENTION_DAYS = 90;
    static final int RUNS_PER_KEY_LIMIT = 20;

    private final DatabaseClient databaseClient;

    @Value("${actuarial.retention.enabled:true}")
    private boolean enabled;

    @Scheduled(cron = "${actuarial.retention.cron:0 0 2 * * *}")
    public void purge() {
        if (!enabled) {
            log.debug("[actuarial-retention] disabled by config — skipping");
            return;
        }
        runOnce()
                .doOnSuccess(deleted -> log.info("[actuarial-retention] deleted {} rows", deleted))
                .doOnError(e -> log.error("[actuarial-retention] failed: {}", e.getMessage(), e))
                .subscribe();
    }

    /** Package-private for unit-testing: returns the count of deleted rows. */
    reactor.core.publisher.Mono<Long> runOnce() {
        return databaseClient.sql("""
                        WITH by_age AS (
                            DELETE FROM actuarial_report_job
                             WHERE requested_at < NOW() - INTERVAL '%d days'
                            RETURNING job_id
                        ),
                        ranked AS (
                            SELECT job_id,
                                   ROW_NUMBER() OVER (
                                       PARTITION BY tenant_id, report_key
                                       ORDER BY requested_at DESC
                                   ) AS rn
                              FROM actuarial_report_job
                        ),
                        by_count AS (
                            DELETE FROM actuarial_report_job j
                             USING ranked r
                             WHERE j.job_id = r.job_id
                               AND r.rn > %d
                            RETURNING j.job_id
                        )
                        SELECT (SELECT COUNT(*) FROM by_age) + (SELECT COUNT(*) FROM by_count) AS deleted
                        """.formatted(RETENTION_DAYS, RUNS_PER_KEY_LIMIT))
                .map((row, meta) -> row.get("deleted", Long.class))
                .one()
                .defaultIfEmpty(0L);
    }
}
