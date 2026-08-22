package com.medfund.finance.reinsurance.service;

import com.medfund.finance.reinsurance.dto.CessionBordereauRow;
import com.medfund.finance.reinsurance.dto.RecoveriesBordereauRow;
import com.medfund.finance.reinsurance.dto.TreatyUtilizationRow;
import com.medfund.finance.reinsurance.repository.BordereauQueryRepository;
import com.medfund.shared.report.ReportEnvelopeBuilder;
import com.medfund.shared.report.ReportKey;
import com.medfund.shared.report.ReportPeriod;
import com.medfund.shared.report.ReportResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Wraps the three bordereau queries in the standard {@link ReportResponse}
 * envelope. Applies per-currency subtotals via
 * {@link ReportEnvelopeBuilder}, best-effort FX rate lookup to the tenant
 * reporting currency (or override), and the {@code isPriorPeriodAdjustment}
 * flag by cross-referencing the {@code bordereau_period_export} registry.
 *
 * <p>Rows stay native (R7). The envelope carries the FX rates the client
 * uses to convert on-the-fly if it wants a converted total.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BordereauReportService {

    private final BordereauQueryRepository queryRepository;
    private final BordereauPeriodExportService periodExportService;
    private final ReportEnvelopeBuilder envelopeBuilder;

    // ── Cession bordereau ───────────────────────────────────────────────────

    // No @Transactional — each per-currency lookup + envelope FX call needs
    // an independent connection so a swallowed error (e.g. absent
    // tenant_currency_config in test schemas) doesn't poison a shared tx.
    public Mono<ReportResponse<List<CessionBordereauRow>>> cessionBordereau(
            UUID reinsurerId, UUID treatyId, int year, int quarter, String overrideCurrency) {

        ReportPeriod period = quarterToPeriod(year, quarter);
        LocalDate exclusiveEnd = period.periodEnd().plusDays(1);

        Mono<List<CessionBordereauRow>> rowsMono = periodExportService
                .firstExportedAt(reinsurerId, treatyId,
                        ReportKey.REINSURANCE_CESSION_BORDEREAU.name(), year, quarter)
                .defaultIfEmpty(OffsetDateTime.MAX)
                .flatMap(firstExportedAt -> queryRepository
                        .cessionRows(reinsurerId, treatyId, period.periodStart(), exclusiveEnd)
                        .map(row -> row.withPriorPeriodAdjustment(
                                row.createdAt() != null && firstExportedAt != OffsetDateTime.MAX
                                        && row.createdAt().isAfter(firstExportedAt)))
                        .collectList());

        return envelopeBuilder.build(
                ReportKey.REINSURANCE_CESSION_BORDEREAU,
                period,
                overrideCurrency,
                rowsMono,
                queryRepository.cessionPerCurrencyTotals(reinsurerId, treatyId,
                        period.periodStart(), exclusiveEnd));
    }

    // ── Recoveries bordereau ────────────────────────────────────────────────

    // No @Transactional — each per-currency lookup + envelope FX call needs
    // an independent connection so a swallowed error (e.g. absent
    // tenant_currency_config in test schemas) doesn't poison a shared tx.
    public Mono<ReportResponse<List<RecoveriesBordereauRow>>> recoveriesBordereau(
            UUID reinsurerId, UUID treatyId, int year, int quarter, String overrideCurrency) {

        ReportPeriod period = quarterToPeriod(year, quarter);
        LocalDate exclusiveEnd = period.periodEnd().plusDays(1);

        Mono<List<RecoveriesBordereauRow>> rowsMono = periodExportService
                .firstExportedAt(reinsurerId, treatyId,
                        ReportKey.REINSURANCE_RECOVERIES.name(), year, quarter)
                .defaultIfEmpty(OffsetDateTime.MAX)
                .flatMap(firstExportedAt -> queryRepository
                        .recoveriesRows(reinsurerId, treatyId, period.periodStart(), exclusiveEnd)
                        .map(row -> row.withPriorPeriodAdjustment(
                                row.createdAt() != null && firstExportedAt != OffsetDateTime.MAX
                                        && row.createdAt().isAfter(firstExportedAt)))
                        .collectList());

        return envelopeBuilder.build(
                ReportKey.REINSURANCE_RECOVERIES,
                period,
                overrideCurrency,
                rowsMono,
                queryRepository.recoveriesPerCurrencyTotals(reinsurerId, treatyId,
                        period.periodStart(), exclusiveEnd));
    }

    // ── Treaty utilization ──────────────────────────────────────────────────

    // No @Transactional — each per-currency lookup + envelope FX call needs
    // an independent connection so a swallowed error (e.g. absent
    // tenant_currency_config in test schemas) doesn't poison a shared tx.
    public Mono<ReportResponse<List<TreatyUtilizationRow>>> treatyUtilization(
            UUID treatyId, LocalDate asOfDate, String overrideCurrency) {

        LocalDate resolvedAsOf = asOfDate != null ? asOfDate : LocalDate.now();
        Mono<List<TreatyUtilizationRow>> rowsMono = queryRepository
                .utilizationRows(treatyId, resolvedAsOf)
                .collectList();

        return envelopeBuilder.build(
                ReportKey.REINSURANCE_TREATY_UTILIZATION,
                null,  // treaty utilization is a since-inception snapshot; no natural quarter window
                overrideCurrency,
                rowsMono,
                queryRepository.utilizationPerCurrencyTotals(treatyId, resolvedAsOf));
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    /** Q1 = Jan–Mar, Q2 = Apr–Jun, Q3 = Jul–Sep, Q4 = Oct–Dec (calendar year). */
    static ReportPeriod quarterToPeriod(int year, int quarter) {
        if (quarter < 1 || quarter > 4) {
            throw new IllegalArgumentException("quarter must be 1..4, was " + quarter);
        }
        int startMonth = (quarter - 1) * 3 + 1;
        LocalDate start = LocalDate.of(year, startMonth, 1);
        LocalDate endInclusive = start.plusMonths(3).minusDays(1);
        return new ReportPeriod(start, endInclusive, ReportPeriod.PeriodGrain.QUARTERLY);
    }
}
