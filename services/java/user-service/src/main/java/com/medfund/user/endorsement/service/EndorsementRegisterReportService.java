package com.medfund.user.endorsement.service;

import com.medfund.shared.report.ReportEnvelopeBuilder;
import com.medfund.shared.report.ReportKey;
import com.medfund.shared.report.ReportPeriod;
import com.medfund.shared.report.ReportResponse;
import com.medfund.user.endorsement.dto.EndorsementRegisterRow;
import com.medfund.user.endorsement.repository.EndorsementReportQueryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.util.List;

/**
 * Phase 12 §C Phase 9 Endorsement Register — per-endorsement report for a
 * reporting window. Rows come from
 * {@link EndorsementReportQueryRepository}; the envelope carries
 * per-currency native totals (magnitude, not signed sum, so lifts don't
 * net against cuts) and a best-effort FX lookup to the reporting
 * currency.
 *
 * <p>Native currency per parent-plan invariant #1. Report gating is
 * enforced at the controller layer via {@code @RequiresReport}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EndorsementRegisterReportService {

    private final EndorsementReportQueryRepository queryRepository;
    private final ReportEnvelopeBuilder envelopeBuilder;

    public Mono<ReportResponse<List<EndorsementRegisterRow>>> register(
            LocalDate periodStart, LocalDate periodEnd,
            String insuranceLine, String status, String overrideCurrency) {

        ReportPeriod period = new ReportPeriod(
                periodStart, periodEnd, ReportPeriod.PeriodGrain.CUSTOM);

        Mono<List<EndorsementRegisterRow>> rowsMono =
                queryRepository.rows(periodStart, periodEnd, insuranceLine, status).collectList();

        return envelopeBuilder.build(
                ReportKey.ENDORSEMENT_REGISTER,
                period,
                overrideCurrency,
                rowsMono,
                queryRepository.perCurrencyTotals(periodStart, periodEnd, insuranceLine, status));
    }
}
