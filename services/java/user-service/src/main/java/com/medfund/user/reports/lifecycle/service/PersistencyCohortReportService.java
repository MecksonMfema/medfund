package com.medfund.user.reports.lifecycle.service;

import com.medfund.shared.report.ReportEnvelopeBuilder;
import com.medfund.shared.report.ReportKey;
import com.medfund.shared.report.ReportPeriod;
import com.medfund.shared.report.ReportResponse;
import com.medfund.user.reports.lifecycle.dto.PersistencyCohortResult;
import com.medfund.user.reports.lifecycle.dto.PersistencyCohortRow;
import com.medfund.user.reports.lifecycle.repository.PolicyLifecycleReportQueryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Phase 13 §C Phase 8 per L9 + L16 + grill note 4. Emits PERSISTENCY_COHORT
 * rows for a window with a checkpoint set, and copies a freshness warning
 * into the result when the {@code member_contribution_presence} matview is
 * more than 24h stale (so a HEALTH persistency row read from a stale
 * matview surfaces a warning to the tenant admin).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PersistencyCohortReportService {

    private static final Duration STALENESS_LIMIT = Duration.ofHours(24);
    public static final List<Integer> DEFAULT_CHECKPOINTS = List.of(6, 12, 24);

    private final PolicyLifecycleReportQueryRepository queryRepository;
    private final ReportEnvelopeBuilder envelopeBuilder;

    public Mono<ReportResponse<PersistencyCohortResult>> generate(LocalDate periodStart, LocalDate periodEnd,
                                                                  List<Integer> checkpointsIn,
                                                                  String insuranceLine,
                                                                  String overrideCurrency) {
        List<Integer> checkpoints = (checkpointsIn == null || checkpointsIn.isEmpty())
                ? DEFAULT_CHECKPOINTS
                : checkpointsIn;

        ReportPeriod period = new ReportPeriod(periodStart, periodEnd, ReportPeriod.PeriodGrain.CUSTOM);
        Mono<List<PersistencyCohortRow>> rowsMono = queryRepository
                .persistencyCohortRows(periodStart, periodEnd, checkpoints, insuranceLine)
                .collectList();

        Mono<PersistencyCohortResult> resultMono = Mono.zip(rowsMono, freshnessWarning())
                .map(t -> new PersistencyCohortResult(t.getT1(),
                        t.getT2().isBlank() ? null : t.getT2()));

        return envelopeBuilder.buildNoAggregate(
                ReportKey.PERSISTENCY_COHORT, period, overrideCurrency, resultMono);
    }

    private Mono<String> freshnessWarning() {
        return queryRepository.latestContribPresenceRefreshAt()
                .map(this::warningFor)
                .defaultIfEmpty("HEALTH persistency data may be stale - "
                        + "member_contribution_presence has not yet been refreshed");
    }

    private String warningFor(Instant refreshedAt) {
        Duration age = Duration.between(refreshedAt, Instant.now());
        if (age.compareTo(STALENESS_LIMIT) > 0) {
            return "HEALTH persistency data may be up to " + age.toHours() + " hours stale";
        }
        return "";
    }
}
