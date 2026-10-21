package com.medfund.claims.siu.scheduler;

import com.medfund.claims.siu.repository.FraudFlagRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;

/**
 * Nightly purge of unlinked {@code fraud_flag} rows (age &gt; 1y). Rows linked
 * to an siu_case (siu_case_id IS NOT NULL) are retained under
 * {@code ReportJob.RETENTION_SIU_CASE_7Y} per Phase 19 §A retention split.
 *
 * <p>Runs at 02:30 daily — 30 min after {@code ReportJobRetentionJob} at 02:00
 * to avoid I/O contention on shared Postgres.
 *
 * <p>Enable/disable via {@code fraud.retention.enabled}; override the cron
 * via {@code fraud.retention.cron} for IT determinism.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FraudFlagRetentionJob {

    private final FraudFlagRepository repo;

    @Value("${fraud.retention.enabled:true}")
    private boolean enabled;

    @Scheduled(cron = "${fraud.retention.cron:0 30 2 * * *}")
    public void purge() {
        if (!enabled) {
            log.debug("[fraud-flag-retention] disabled by config — skipping");
            return;
        }
        runOnce()
                .doOnSuccess(count -> log.info("[fraud-flag-retention] purged {} rows", count))
                .doOnError(err -> log.error("[fraud-flag-retention] failed", err))
                .subscribe();
    }

    /** Package-private for unit-testing: returns rows deleted. */
    Mono<Long> runOnce() {
        OffsetDateTime cutoff = OffsetDateTime.now().minus(1, ChronoUnit.YEARS);
        return repo.purgeUnlinkedOlderThan(cutoff);
    }
}
