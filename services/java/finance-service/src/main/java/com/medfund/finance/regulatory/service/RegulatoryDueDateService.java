package com.medfund.finance.regulatory.service;

import com.medfund.finance.regulatory.dto.DueDateBannerResponse;
import com.medfund.finance.regulatory.repository.RegulatorySubmissionRepository;
import com.medfund.shared.report.ReportCadence;
import com.medfund.shared.report.ReportCadenceCatalog;
import com.medfund.shared.report.ReportKey;
import com.medfund.shared.tenant.TenantMetadataReader;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.UUID;

/**
 * Composes the reports-hub due-date banner (Phase 6): for every Phase-16
 * regulator report applicable to the tenant, resolve the most recent
 * completed filing period and check whether it has been submitted.
 *
 * <p>Reference date defaults to {@code Clock.systemDefaultZone()}; tests
 * inject a fixed clock. All lookups are tenant-scoped via
 * {@link RegulatorySubmissionRepository}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RegulatoryDueDateService {

    private final RegulatorySubmissionRepository submissions;
    private final TenantMetadataReader tenantMetadata;
    private final Clock clock;

    /** Banner rows for the calling tenant, one per applicable Phase-16 report. */
    public Flux<DueDateBannerResponse> bannerRowsFor(UUID tenantId) {
        LocalDate today = LocalDate.now(clock);
        return tenantMetadata.load(tenantId)
                .flatMapMany(meta -> {
                    var keys = RegulatoryReportApplicability.applicableFor(
                            meta.jurisdictionCode(), meta.countryCode());
                    return Flux.fromIterable(keys)
                            .concatMap(key -> bannerRowFor(tenantId, key, today));
                });
    }

    private reactor.core.publisher.Mono<DueDateBannerResponse> bannerRowFor(
            UUID tenantId, ReportKey key, LocalDate today) {
        return ReportCadenceCatalog.lookup(key)
                .map(info -> {
                    Period period = currentPeriodFor(info.cadence(), today);
                    LocalDate dueDate = period.end().plusDays(info.daysPostPeriodEnd());
                    long daysUntilDue = ChronoUnit.DAYS.between(today, dueDate);
                    return submissions
                            .findByTenantIdAndReportKeyAndPeriodStartOrderBySubmissionNumberDesc(
                                    tenantId, key.name(), period.start())
                            .next()
                            .map(sub -> toRow(key, info.cadence(), period, dueDate,
                                    daysUntilDue, sub.getStatus()))
                            .defaultIfEmpty(toRow(key, info.cadence(), period, dueDate,
                                    daysUntilDue, "PENDING"));
                })
                .orElseGet(reactor.core.publisher.Mono::empty);
    }

    private static DueDateBannerResponse toRow(ReportKey key,
                                               ReportCadence cadence,
                                               Period period,
                                               LocalDate dueDate,
                                               long daysUntilDue,
                                               String submissionStatus) {
        return new DueDateBannerResponse(
                key.name(),
                key.getLabel(),
                cadence.name(),
                period.start(),
                period.end(),
                dueDate,
                daysUntilDue,
                submissionStatus,
                severityOf(daysUntilDue, submissionStatus));
    }

    /**
     * Severity ladder per the plan: {@code RED} for overdue, {@code AMBER}
     * for ≤7 days, otherwise {@code INFO}. A row already {@code SUBMITTED}
     * or {@code AMENDED} short-circuits to {@code INFO} regardless of
     * distance to due-date — the compliance officer doesn't need to see red
     * on something that's already filed.
     */
    static String severityOf(long daysUntilDue, String submissionStatus) {
        if ("SUBMITTED".equals(submissionStatus) || "AMENDED".equals(submissionStatus)) {
            return "INFO";
        }
        if (daysUntilDue <= 0) return "RED";
        if (daysUntilDue <= 7) return "AMBER";
        return "INFO";
    }

    /** The most recent completed filing period for the cadence, relative to {@code today}. */
    public static Period currentPeriodFor(ReportCadence cadence, LocalDate today) {
        return switch (cadence) {
            case MONTHLY -> {
                LocalDate priorMonth = today.minusMonths(1);
                yield new Period(
                        priorMonth.with(TemporalAdjusters.firstDayOfMonth()),
                        priorMonth.with(TemporalAdjusters.lastDayOfMonth()));
            }
            case QUARTERLY -> {
                LocalDate priorQuarter = today.minusMonths(3);
                int monthOfPrior = priorQuarter.getMonthValue();
                int quarterStartMonth = ((monthOfPrior - 1) / 3) * 3 + 1;
                LocalDate start = LocalDate.of(priorQuarter.getYear(), quarterStartMonth, 1);
                LocalDate end = start.plusMonths(3).minusDays(1);
                yield new Period(start, end);
            }
            case ANNUAL -> {
                int priorYear = today.getYear() - 1;
                yield new Period(LocalDate.of(priorYear, 1, 1), LocalDate.of(priorYear, 12, 31));
            }
            case EVENT_DRIVEN -> new Period(today, today);
            case WEEKLY -> throw new IllegalArgumentException(
                    "WEEKLY cadence is a Phase 17 scheduling-only value; regulator due-date "
                            + "catalog does not map any key to it");
        };
    }

    public record Period(LocalDate start, LocalDate end) {}
}
