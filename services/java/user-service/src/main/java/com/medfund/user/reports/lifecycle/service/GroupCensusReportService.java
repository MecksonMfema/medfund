package com.medfund.user.reports.lifecycle.service;

import com.medfund.shared.report.ReportEnvelopeBuilder;
import com.medfund.shared.report.ReportKey;
import com.medfund.shared.report.ReportPeriod;
import com.medfund.shared.report.ReportResponse;
import com.medfund.user.reports.lifecycle.dto.GroupCensusResult;
import com.medfund.user.reports.lifecycle.repository.PolicyLifecycleReportQueryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Phase 13 §C Phase 8 per L11 — group-census snapshot at {@code asOf}.
 * Read from members + groups joined by group_id; status counts derived
 * from the current status column (asOf &gt; today rejected upstream by
 * the controller).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GroupCensusReportService {

    private final PolicyLifecycleReportQueryRepository queryRepository;
    private final ReportEnvelopeBuilder envelopeBuilder;

    public Mono<ReportResponse<GroupCensusResult>> generate(LocalDate asOf, UUID groupId, String statusFilter,
                                                            String overrideCurrency) {
        ReportPeriod period = new ReportPeriod(asOf, asOf, ReportPeriod.PeriodGrain.DAILY);
        Mono<GroupCensusResult> dataMono = queryRepository
                .groupCensusRows(asOf, groupId, statusFilter)
                .collectList()
                .map(list -> new GroupCensusResult(asOf, list));
        return envelopeBuilder.buildNoAggregate(
                ReportKey.GROUP_CENSUS, period, overrideCurrency, dataMono);
    }
}
