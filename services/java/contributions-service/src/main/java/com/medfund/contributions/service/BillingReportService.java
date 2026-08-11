package com.medfund.contributions.service;

import com.medfund.contributions.dto.BillingAggregateRow;
import com.medfund.contributions.dto.BillingMonthlyBucket;
import com.medfund.contributions.dto.GroupBillingDetailResponse;
import com.medfund.contributions.dto.GroupBillingSummaryRow;
import com.medfund.contributions.dto.MemberBillingDetailResponse;
import com.medfund.contributions.dto.MemberBillingSummaryRow;
import com.medfund.contributions.dto.PageResponse;
import com.medfund.contributions.dto.SchemeBillingDetailResponse;
import com.medfund.contributions.dto.SchemeBillingSummaryRow;
import com.medfund.contributions.repository.BillingReportQueryRepository;
import com.medfund.shared.report.MonthlyAggregateRow;
import com.medfund.shared.report.PerCurrencyTotal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Thin façade over {@link BillingReportQueryRepository} — every method
 * returns a report payload (list or detail) sized for the envelope layer to
 * wrap. Zero in-memory aggregation; the repo does the SQL, the controller
 * does the envelope.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BillingReportService {

    private final BillingReportQueryRepository repository;

    public Mono<List<SchemeBillingSummaryRow>> perSchemeSummary(LocalDate periodStart, LocalDate periodEnd) {
        return repository.perSchemeSummary(periodStart, periodEnd).collectList();
    }

    public Mono<Map<String, PerCurrencyTotal>> perSchemePerCurrencyTotals(LocalDate periodStart, LocalDate periodEnd) {
        return repository.perSchemePerCurrencyTotals(periodStart, periodEnd);
    }

    public Mono<List<GroupBillingSummaryRow>> perGroupSummary(LocalDate periodStart, LocalDate periodEnd) {
        return repository.perGroupSummary(periodStart, periodEnd).collectList();
    }

    public Mono<Map<String, PerCurrencyTotal>> perGroupPerCurrencyTotals(LocalDate periodStart, LocalDate periodEnd) {
        return repository.perGroupPerCurrencyTotals(periodStart, periodEnd);
    }

    public Mono<SchemeBillingDetailResponse> schemeDetail(UUID schemeId, LocalDate periodStart, LocalDate periodEnd) {
        Mono<List<SchemeBillingSummaryRow>> summary  = repository.schemeSummary(schemeId, periodStart, periodEnd).collectList();
        Mono<List<BillingMonthlyBucket>> monthly     = repository.schemeMonthly(schemeId, periodStart, periodEnd).collectList();
        return Mono.zip(summary, monthly)
                .map(tuple -> {
                    List<SchemeBillingSummaryRow> rows = tuple.getT1();
                    String schemeName    = rows.isEmpty() ? ""        : rows.get(0).schemeName();
                    String insuranceLine = rows.isEmpty() ? "HEALTH"  : rows.get(0).insuranceLine();
                    return new SchemeBillingDetailResponse(schemeId, schemeName, insuranceLine,
                            rows, tuple.getT2());
                });
    }

    public Mono<GroupBillingDetailResponse> groupDetail(UUID groupId, LocalDate periodStart, LocalDate periodEnd) {
        Mono<List<GroupBillingSummaryRow>> summary = repository.groupSummary(groupId, periodStart, periodEnd).collectList();
        Mono<List<BillingMonthlyBucket>> monthly   = repository.groupMonthly(groupId, periodStart, periodEnd).collectList();
        return Mono.zip(summary, monthly)
                .map(tuple -> {
                    List<GroupBillingSummaryRow> rows = tuple.getT1();
                    String groupName = rows.isEmpty() ? "" : rows.get(0).groupName();
                    return new GroupBillingDetailResponse(groupId, groupName, rows, tuple.getT2());
                });
    }

    public Mono<List<BillingAggregateRow>> aggregatePerScheme(LocalDate periodStart, LocalDate periodEnd) {
        return repository.aggregatePerScheme(periodStart, periodEnd).collectList();
    }

    // ── Per-member (paginated + search) — Phase 3 §8 owed-back to Phase 2 ──

    public Mono<PageResponse<MemberBillingSummaryRow>> perMemberSummary(LocalDate periodStart, LocalDate periodEnd,
                                                                        String search, String insuranceLine, UUID schemeId,
                                                                        int page, int size) {
        int offset = Math.max(page, 0) * Math.max(size, 1);
        return repository.perMemberSummary(periodStart, periodEnd, search, insuranceLine, schemeId, offset, size)
                .collectList()
                .zipWith(repository.perMemberSummaryCount(periodStart, periodEnd, search, insuranceLine, schemeId))
                .map(t -> PageResponse.of(t.getT1(), t.getT2(), page, size));
    }

    public Mono<Map<String, PerCurrencyTotal>> perMemberPerCurrencyTotals(LocalDate periodStart, LocalDate periodEnd,
                                                                          String search, String insuranceLine, UUID schemeId) {
        return repository.perMemberPerCurrencyTotals(periodStart, periodEnd, search, insuranceLine, schemeId);
    }

    public Mono<MemberBillingDetailResponse> memberDetail(UUID memberId, LocalDate periodStart, LocalDate periodEnd) {
        Mono<List<MemberBillingSummaryRow>> summary = repository.memberSummary(memberId, periodStart, periodEnd).collectList();
        Mono<List<BillingMonthlyBucket>> monthly    = repository.memberMonthly(memberId, periodStart, periodEnd).collectList();
        return Mono.zip(summary, monthly)
                .map(tuple -> {
                    List<MemberBillingSummaryRow> rows = tuple.getT1();
                    String memberNumber  = rows.isEmpty() ? ""       : rows.get(0).memberNumber();
                    String memberName    = rows.isEmpty() ? ""       : rows.get(0).memberName();
                    String insuranceLine = rows.isEmpty() ? "HEALTH" : rows.get(0).insuranceLine();
                    return new MemberBillingDetailResponse(memberId, memberNumber, memberName,
                            insuranceLine, rows, tuple.getT2());
                });
    }

    public Mono<List<MonthlyAggregateRow>> aggregateMonthly(String dimension, LocalDate periodStart, LocalDate periodEnd) {
        return repository.aggregateMonthly(dimension, periodStart, periodEnd).collectList();
    }
}
