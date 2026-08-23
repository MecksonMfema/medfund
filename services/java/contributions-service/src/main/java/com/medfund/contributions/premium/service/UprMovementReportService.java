package com.medfund.contributions.premium.service;

import com.medfund.contributions.premium.dto.UprMovementRow;
import com.medfund.contributions.premium.repository.PremiumReportQueryRepository;
import com.medfund.shared.report.ReportEnvelopeBuilder;
import com.medfund.shared.report.ReportKey;
import com.medfund.shared.report.ReportPeriod;
import com.medfund.shared.report.ReportResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.util.List;

/**
 * Phase 12 §B UPR Movement report. Wraps the four-CTE aggregate SQL in
 * the standard {@link ReportResponse} envelope — native-currency rows
 * per line, best-effort FX rates to the tenant reporting currency,
 * warnings for missing rates (parent-plan invariants #1 + #6 / G28).
 *
 * <p>Same "no {@code @Transactional}" rationale as
 * {@code CommissionStatementService}: the per-currency FX lookup + row
 * fetch run on independent connections so a swallowed error (missing
 * FX row, missing tenant_currency_config) doesn't poison a shared tx.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UprMovementReportService {

    private final PremiumReportQueryRepository queryRepository;
    private final ReportEnvelopeBuilder envelopeBuilder;

    public Mono<ReportResponse<List<UprMovementRow>>> movement(
            LocalDate periodStart, LocalDate periodEnd, String insuranceLine, String overrideCurrency) {

        ReportPeriod period = new ReportPeriod(
                periodStart, periodEnd, ReportPeriod.PeriodGrain.CUSTOM);

        Mono<List<UprMovementRow>> rowsMono =
                queryRepository.uprMovementRows(periodStart, periodEnd, insuranceLine).collectList();

        return envelopeBuilder.build(
                ReportKey.UPR_MOVEMENT,
                period,
                overrideCurrency,
                rowsMono,
                queryRepository.uprMovementPerCurrencyTotals(periodStart, periodEnd, insuranceLine));
    }
}
