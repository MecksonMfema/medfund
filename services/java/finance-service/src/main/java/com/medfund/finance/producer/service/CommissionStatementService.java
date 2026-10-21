package com.medfund.finance.producer.service;

import com.medfund.finance.producer.dto.CommissionStatementRow;
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
 * Wraps {@link CommissionReportQueryRepository#statementRows} in the standard
 * {@link ReportResponse} envelope (per-currency native subtotals, best-effort
 * FX rates to the tenant reporting currency, warnings for missing rates).
 *
 * <p>Rows stay native (parent-plan cross-phase invariant #1) — the envelope
 * carries the conversion metadata the client uses to render a converted
 * grand total.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CommissionStatementService {

    private final CommissionReportQueryRepository queryRepository;
    private final ReportEnvelopeBuilder envelopeBuilder;

    // No @Transactional — each envelope's per-currency lookup + FX fetch runs
    // on an independent connection so a swallowed error (missing
    // tenant_currency_config, missing FX row) doesn't poison a shared tx.
    // Same rationale as BordereauReportService.
    public Mono<ReportResponse<List<CommissionStatementRow>>> statement(
            LocalDate periodStart, LocalDate periodEnd,
            UUID producerId, String overrideCurrency) {

        ReportPeriod period = new ReportPeriod(
                periodStart, periodEnd, ReportPeriod.PeriodGrain.CUSTOM);
        OffsetDateTime from = periodStart.atStartOfDay().atOffset(ZoneOffset.UTC);
        OffsetDateTime to   = periodEnd.plusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC);

        Mono<List<CommissionStatementRow>> rowsMono =
                queryRepository.statementRows(from, to, producerId).collectList();

        return envelopeBuilder.build(
                ReportKey.COMMISSION_STATEMENT,
                period,
                overrideCurrency,
                rowsMono,
                queryRepository.statementPerCurrencyTotals(from, to, producerId));
    }
}
