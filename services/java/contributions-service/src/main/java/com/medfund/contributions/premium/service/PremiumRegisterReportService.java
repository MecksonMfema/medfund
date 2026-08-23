package com.medfund.contributions.premium.service;

import com.medfund.contributions.premium.dto.PremiumRegisterRow;
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
 * Phase 12 §B Premium Register — per-policy-per-period listing over the
 * reporting window. Rows are native-currency; envelope carries the
 * per-currency subtotal + FX conversion metadata.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PremiumRegisterReportService {

    private final PremiumReportQueryRepository queryRepository;
    private final ReportEnvelopeBuilder envelopeBuilder;

    public Mono<ReportResponse<List<PremiumRegisterRow>>> register(
            LocalDate periodStart, LocalDate periodEnd, String insuranceLine, String overrideCurrency) {

        ReportPeriod period = new ReportPeriod(
                periodStart, periodEnd, ReportPeriod.PeriodGrain.CUSTOM);

        Mono<List<PremiumRegisterRow>> rowsMono =
                queryRepository.premiumRegisterRows(periodStart, periodEnd, insuranceLine).collectList();

        return envelopeBuilder.build(
                ReportKey.PREMIUM_REGISTER,
                period,
                overrideCurrency,
                rowsMono,
                queryRepository.premiumRegisterPerCurrencyTotals(periodStart, periodEnd, insuranceLine));
    }
}
