package com.medfund.finance.report.schedule;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.OffsetDateTime;

/**
 * Phase 17 §A.2 — hourly probe that fires every enabled schedule whose
 * (cadence, day, hour) matches the current tick in the tenant's TZ.
 * Deploys behind {@code scheduled.report.probe.enabled} so the flag can
 * gate rollout independently of code deploys — per F-S9 the Go
 * consumer must be running first.
 *
 * <p>Concurrency of 4 protects the shape services from a large-tenant burst
 * (13 keys × N tenants × 24 hours = plenty of parallelism); tune via config
 * if a real spike appears in production.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ScheduledReportProbe {

    private final ScheduledReportOrchestrator orchestrator;
    private final ScheduledReportFireResolver resolver;
    private final Clock clock;

    @Value("${scheduled.report.probe.enabled:true}")
    private boolean enabled;

    @Value("${scheduled.report.probe.concurrency:4}")
    private int concurrency;

    @Scheduled(cron = "${scheduled.report.probe.cron:0 5 * * * *}")
    public void probeAndFire() {
        if (!enabled) {
            log.debug("[scheduled-report-probe] disabled by config — skipping");
            return;
        }
        runOnce()
                .doOnSuccess(count -> log.info("[scheduled-report-probe] tick complete: fired={}", count))
                .doOnError(err -> log.error("[scheduled-report-probe] tick failed: {}", err.getMessage(), err))
                .subscribe();
    }

    /** Package-private for tests: returns the number of fires attempted. */
    Mono<Long> runOnce() {
        OffsetDateTime firedAt = OffsetDateTime.now(clock);
        return resolver.findCandidates(firedAt)
                .flatMap(cand -> orchestrator.fireOnce(cand, firedAt)
                                .then(Mono.just(1L))
                                .onErrorResume(err -> {
                                    log.error("[scheduled-report-probe] fireOnce failed for schedule={}: {}",
                                            cand.scheduleId(), err.getMessage(), err);
                                    return Mono.just(0L);
                                }),
                        Math.max(1, concurrency))
                .reduce(0L, Long::sum);
    }

    /**
     * Test / force-fire helper — enumerate candidates for the given fire
     * time so the controller can dispatch them under the caller's identity.
     * Not intended for production traffic; the force-fire endpoint that
     * uses it is gated by {@code scheduled.report.probe.force-fire-enabled}.
     */
    public Flux<TenantScheduleFireCandidate> candidatesFor(OffsetDateTime firedAt) {
        return resolver.findCandidates(firedAt);
    }
}
