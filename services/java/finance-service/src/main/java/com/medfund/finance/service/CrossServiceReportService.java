package com.medfund.finance.service;

import com.medfund.finance.client.ClaimsClient;
import com.medfund.finance.client.ContributionsClient;
import com.medfund.finance.dto.BillingAggregateRow;
import com.medfund.finance.dto.ClaimsAggregateRow;
import com.medfund.finance.dto.LossRatioReportResponse;
import com.medfund.finance.dto.LossRatioReportResponse.LossRatioRow;
import com.medfund.finance.dto.MemberPaymentsReportResponse;
import com.medfund.finance.dto.MemberPaymentsReportResponse.MemberPaymentRow;
import com.medfund.shared.report.CrossServiceCallHelper;
import com.medfund.shared.report.MonthlyAggregateRow;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Composes the Phase 5 cross-service reports from the contributions-service
 * billing + receipts aggregates and the claims-service claims aggregate.
 * Same resilience story as {@link CollectionRateReportService}: guarded
 * fan-out via {@link CrossServiceCallHelper}, so a peer down populates
 * {@code warnings} and the report still renders with partial data (G37).
 * Rows stay native per-currency — never a cross-currency conversion (G34).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CrossServiceReportService {

    private final ContributionsClient contributionsClient;
    private final ClaimsClient claimsClient;

    /**
     * Loss-ratio (billing vs claims) over {@code [periodStart, periodEnd]}.
     * SCHEME-grained: one row per (scheme, currency) with the full funnel.
     */
    public Mono<LossRatioReportResponse> lossRatio(LocalDate periodStart, LocalDate periodEnd,
                                                   List<String> warnings) {
        Mono<List<BillingAggregateRow>> billing = CrossServiceCallHelper.guarded(
                "billing-aggregate[SCHEME]",
                contributionsClient.aggregateBilling(periodStart, periodEnd),
                List.of(), warnings);
        Mono<List<ClaimsAggregateRow>> claims = CrossServiceCallHelper.guarded(
                "claims-aggregate[SCHEME]",
                claimsClient.aggregateClaims(periodStart, periodEnd),
                List.of(), warnings);
        return Mono.zip(objects -> new LossRatioReportResponse(
                        periodStart, periodEnd,
                        composeLossRatio((List<BillingAggregateRow>) objects[0],
                                (List<ClaimsAggregateRow>) objects[1])),
                billing, claims);
    }

    /**
     * Member-payments unified over {@code [periodStart, periodEnd]}.
     * MEMBER-grained: one row per (member, currency) summing the monthly
     * buckets from all three sources across the reporting window.
     */
    public Mono<MemberPaymentsReportResponse> memberPayments(LocalDate periodStart, LocalDate periodEnd,
                                                             List<String> warnings) {
        Mono<List<MonthlyAggregateRow>> billing = CrossServiceCallHelper.guarded(
                "billing-aggregate-monthly[MEMBER]",
                contributionsClient.aggregateBillingMonthly(periodStart, periodEnd, "MEMBER"),
                List.of(), warnings);
        Mono<List<MonthlyAggregateRow>> receipts = CrossServiceCallHelper.guarded(
                "receipts-aggregate-monthly[MEMBER]",
                contributionsClient.aggregateReceiptsMonthly(periodStart, periodEnd, "MEMBER"),
                List.of(), warnings);
        Mono<List<MonthlyAggregateRow>> claims = CrossServiceCallHelper.guarded(
                "claims-aggregate-monthly[MEMBER]",
                claimsClient.aggregateClaimsMonthly(periodStart, periodEnd, "MEMBER"),
                List.of(), warnings);
        return Mono.zip(objects -> new MemberPaymentsReportResponse(
                        periodStart, periodEnd,
                        composeMemberPayments((List<MonthlyAggregateRow>) objects[0],
                                (List<MonthlyAggregateRow>) objects[1],
                                (List<MonthlyAggregateRow>) objects[2])),
                billing, receipts, claims);
    }

    /** Union billing + claims into per-(scheme, currency) rows with the funnel. */
    static List<LossRatioRow> composeLossRatio(List<BillingAggregateRow> billing,
                                               List<ClaimsAggregateRow> claims) {
        Map<SchemeKey, LossRatioBucket> buckets = new LinkedHashMap<>();
        for (BillingAggregateRow row : billing) {
            SchemeKey key = new SchemeKey(row.schemeId(), row.currencyCode());
            LossRatioBucket bucket = buckets.computeIfAbsent(key, k -> new LossRatioBucket(key));
            bucket.schemeName = firstNonNull(bucket.schemeName, row.schemeName());
            bucket.totalBilled = add(bucket.totalBilled, row.totalBilled());
        }
        for (ClaimsAggregateRow row : claims) {
            SchemeKey key = new SchemeKey(row.dimensionId(), row.currencyCode());
            LossRatioBucket bucket = buckets.computeIfAbsent(key, k -> new LossRatioBucket(key));
            bucket.schemeName = firstNonNull(bucket.schemeName, row.dimensionName());
            bucket.totalClaimed = add(bucket.totalClaimed, row.totalClaimed());
            bucket.totalApproved = add(bucket.totalApproved, row.totalApproved());
            bucket.totalPaid = add(bucket.totalPaid, row.totalPaid());
        }
        return buckets.values().stream()
                .map(b -> new LossRatioRow(
                        b.key.schemeId(), b.schemeName, b.key.currencyCode(),
                        b.totalBilled, b.totalClaimed, b.totalApproved, b.totalPaid,
                        CollectionRateReportService.ratePercent(b.totalPaid, b.totalBilled),
                        subtract(b.totalBilled, b.totalPaid)))
                .sorted(Comparator.comparing(LossRatioRow::schemeName,
                                Comparator.nullsLast(String::compareToIgnoreCase))
                        .thenComparing(LossRatioRow::currencyCode,
                                Comparator.nullsLast(String::compareToIgnoreCase)))
                .toList();
    }

    /** Sum the monthly buckets per (member, currency) across all three sources. */
    static List<MemberPaymentRow> composeMemberPayments(List<MonthlyAggregateRow> billing,
                                                        List<MonthlyAggregateRow> receipts,
                                                        List<MonthlyAggregateRow> claims) {
        Map<MemberKey, MemberBucket> buckets = new LinkedHashMap<>();
        for (MonthlyAggregateRow row : billing) {
            MemberKey key = new MemberKey(row.dimensionId(), row.currencyCode());
            MemberBucket bucket = buckets.computeIfAbsent(key, k -> new MemberBucket(key));
            bucket.memberName = firstNonNull(bucket.memberName, row.dimensionName());
            bucket.totalBilled = add(bucket.totalBilled, row.totalAmount());
        }
        for (MonthlyAggregateRow row : receipts) {
            MemberKey key = new MemberKey(row.dimensionId(), row.currencyCode());
            MemberBucket bucket = buckets.computeIfAbsent(key, k -> new MemberBucket(key));
            bucket.memberName = firstNonNull(bucket.memberName, row.dimensionName());
            bucket.totalReceived = add(bucket.totalReceived, row.totalAmount());
        }
        for (MonthlyAggregateRow row : claims) {
            MemberKey key = new MemberKey(row.dimensionId(), row.currencyCode());
            MemberBucket bucket = buckets.computeIfAbsent(key, k -> new MemberBucket(key));
            bucket.memberName = firstNonNull(bucket.memberName, row.dimensionName());
            bucket.totalClaimsPaid = add(bucket.totalClaimsPaid, row.totalAmount());
        }
        return buckets.values().stream()
                .map(b -> new MemberPaymentRow(
                        b.key.memberId(), b.memberName, b.key.currencyCode(),
                        b.totalBilled, b.totalReceived, b.totalClaimsPaid,
                        subtract(b.totalReceived, b.totalClaimsPaid)))
                .sorted(Comparator.comparing(MemberPaymentRow::memberName,
                                Comparator.nullsLast(String::compareToIgnoreCase))
                        .thenComparing(MemberPaymentRow::currencyCode,
                                Comparator.nullsLast(String::compareToIgnoreCase)))
                .toList();
    }

    private static BigDecimal add(BigDecimal acc, BigDecimal amount) {
        BigDecimal safe = amount != null ? amount : BigDecimal.ZERO;
        return acc != null ? acc.add(safe) : safe;
    }

    private static BigDecimal subtract(BigDecimal a, BigDecimal b) {
        BigDecimal safeA = a != null ? a : BigDecimal.ZERO;
        BigDecimal safeB = b != null ? b : BigDecimal.ZERO;
        return safeA.subtract(safeB);
    }

    private static String firstNonNull(String existing, String candidate) {
        return existing != null ? existing : candidate;
    }

    /** (schemeId, currency) — never cross-currency (G34). */
    private record SchemeKey(UUID schemeId, String currencyCode) {}

    /** (memberId, currency) — never cross-currency (G34). */
    private record MemberKey(UUID memberId, String currencyCode) {}

    private static final class LossRatioBucket {
        private final SchemeKey key;
        private String schemeName;
        private BigDecimal totalBilled = BigDecimal.ZERO;
        private BigDecimal totalClaimed = BigDecimal.ZERO;
        private BigDecimal totalApproved = BigDecimal.ZERO;
        private BigDecimal totalPaid = BigDecimal.ZERO;

        LossRatioBucket(SchemeKey key) {
            this.key = key;
        }
    }

    private static final class MemberBucket {
        private final MemberKey key;
        private String memberName;
        private BigDecimal totalBilled = BigDecimal.ZERO;
        private BigDecimal totalReceived = BigDecimal.ZERO;
        private BigDecimal totalClaimsPaid = BigDecimal.ZERO;

        MemberBucket(MemberKey key) {
            this.key = key;
        }
    }
}
