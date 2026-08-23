package com.medfund.finance.producer.service;

import com.medfund.finance.producer.dto.ClawbackRegisterRow;
import com.medfund.finance.producer.repository.CommissionReportQueryRepository;
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
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * Wraps {@link CommissionReportQueryRepository#clawbackRows} in the standard
 * {@link ReportResponse} envelope. Optional {@code source} filter narrows to
 * MEMBER_LAPSE or CONTRIBUTION_REVOKE without duplicating the endpoint.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CommissionClawbackReportService {

    private final CommissionReportQueryRepository queryRepository;
    private final ReportEnvelopeBuilder envelopeBuilder;

    // No @Transactional — per-currency + FX fetch on independent connections
    // (see CommissionStatementService for rationale).
    public Mono<ReportResponse<List<ClawbackRegisterRow>>> register(
            LocalDate periodStart, LocalDate periodEnd,
            UUID producerId, String source, String overrideCurrency) {

        ReportPeriod period = new ReportPeriod(
                periodStart, periodEnd, ReportPeriod.PeriodGrain.CUSTOM);
        OffsetDateTime from = periodStart.atStartOfDay().atOffset(ZoneOffset.UTC);
        OffsetDateTime to   = periodEnd.plusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC);

        Mono<List<ClawbackRegisterRow>> rowsMono =
                queryRepository.clawbackRows(from, to, producerId, source).collectList();

        return envelopeBuilder.build(
                ReportKey.COMMISSION_CLAWBACK,
                period,
                overrideCurrency,
                rowsMono,
                queryRepository.clawbackPerCurrencyTotals(from, to, producerId, source));
    }
}
