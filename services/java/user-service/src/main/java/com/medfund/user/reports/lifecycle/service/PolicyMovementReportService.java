package com.medfund.user.reports.lifecycle.service;

import com.medfund.shared.report.ReportEnvelopeBuilder;
import com.medfund.shared.report.ReportKey;
import com.medfund.shared.report.ReportPeriod;
import com.medfund.shared.report.ReportResponse;
import com.medfund.user.reports.lifecycle.dto.PolicyMovementResult;
import com.medfund.user.reports.lifecycle.repository.PolicyLifecycleReportQueryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.LocalDate;

/**
 * Phase 13 §C Phase 8 per L10 — POLICY_MOVEMENT for a window.
 * Native rows per parent-plan invariant #1; envelope's perCurrency roll
 * carries the |written_added + written_removed| magnitude per currency so
 * lifts and cuts don't net on the summary line.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PolicyMovementReportService {

    private final PolicyLifecycleReportQueryRepository queryRepository;
    private final ReportEnvelopeBuilder envelopeBuilder;

    public Mono<ReportResponse<PolicyMovementResult>> generate(LocalDate periodStart, LocalDate periodEnd,
                                                               String overrideCurrency) {
        ReportPeriod period = new ReportPeriod(periodStart, periodEnd, ReportPeriod.PeriodGrain.CUSTOM);
        Mono<PolicyMovementResult> dataMono = queryRepository.movementRows(periodStart, periodEnd)
                .collectList()
                .map(PolicyMovementResult::new);
        return envelopeBuilder.build(
                ReportKey.POLICY_MOVEMENT,
                period,
                overrideCurrency,
                dataMono,
                queryRepository.movementPerCurrencyTotals(periodStart, periodEnd));
    }
}
