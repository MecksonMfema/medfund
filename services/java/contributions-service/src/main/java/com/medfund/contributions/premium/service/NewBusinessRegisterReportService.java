package com.medfund.contributions.premium.service;

import com.medfund.contributions.premium.dto.NewBusinessRegisterRow;
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
 * Phase 12 §B New Business Register — policies (or first-Contributions
 * for HEALTH) that first bound within the window per U6. Annual-bind
 * lines use the {@code renewed_from_policy_id IS NULL AND bound_at
 * BETWEEN ...} filter; HEALTH uses the {@code
 * member_first_contribution} materialised view.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NewBusinessRegisterReportService {

    private final PremiumReportQueryRepository queryRepository;
    private final ReportEnvelopeBuilder envelopeBuilder;

    public Mono<ReportResponse<List<NewBusinessRegisterRow>>> register(
            LocalDate periodStart, LocalDate periodEnd, String insuranceLine, String overrideCurrency) {

        ReportPeriod period = new ReportPeriod(
                periodStart, periodEnd, ReportPeriod.PeriodGrain.CUSTOM);

        Mono<List<NewBusinessRegisterRow>> rowsMono =
                queryRepository.newBusinessRows(periodStart, periodEnd, insuranceLine).collectList();

        return envelopeBuilder.build(
                ReportKey.NEW_BUSINESS_REGISTER,
                period,
                overrideCurrency,
                rowsMono,
                queryRepository.newBusinessPerCurrencyTotals(periodStart, periodEnd, insuranceLine));
    }
}
