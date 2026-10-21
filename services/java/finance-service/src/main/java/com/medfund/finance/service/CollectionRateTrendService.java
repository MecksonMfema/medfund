package com.medfund.finance.service;

import com.medfund.finance.client.ContributionsClient;
import com.medfund.finance.dto.CollectionRateReportResponse.DimensionRow;
import com.medfund.finance.dto.CollectionRateReportResponse.MonthlyBucket;
import com.medfund.finance.dto.CollectionRateTrendResponse;
import com.medfund.finance.dto.CollectionRateTrendResponse.MonthRow;
import com.medfund.shared.report.CrossServiceCallHelper;
import com.medfund.shared.report.MonthlyAggregateRow;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static com.medfund.finance.service.CollectionRateReportService.compose;
import static com.medfund.finance.service.CollectionRateReportService.ratePercent;

/**
 * Phase 8 portfolio-level collection-rate trend. Sums the six
 * billing/receipts dimension aggregates into one (month, currency)
 * strip via {@link CollectionRateReportService#compose} + a flatten that
 * drops the dimension — same fanout, same warnings discipline, same
 * rate arithmetic as the per-dimension report, so the two never drift.
 */
@Service
@RequiredArgsConstructor
public class CollectionRateTrendService {

    private final ContributionsClient contributionsClient;

    public Mono<CollectionRateTrendResponse> compute(LocalDate periodStart, LocalDate periodEnd,
                                                      List<String> warnings) {
        Mono<List<MonthlyAggregateRow>> schemeBilling = CrossServiceCallHelper.guarded(
                "billing-aggregate-monthly[SCHEME]",
                contributionsClient.aggregateBillingMonthly(periodStart, periodEnd, "SCHEME"),
                List.of(), warnings);
        Mono<List<MonthlyAggregateRow>> groupBilling = CrossServiceCallHelper.guarded(
                "billing-aggregate-monthly[GROUP]",
                contributionsClient.aggregateBillingMonthly(periodStart, periodEnd, "GROUP"),
                List.of(), warnings);
        Mono<List<MonthlyAggregateRow>> memberBilling = CrossServiceCallHelper.guarded(
                "billing-aggregate-monthly[MEMBER]",
                contributionsClient.aggregateBillingMonthly(periodStart, periodEnd, "MEMBER"),
                List.of(), warnings);
        Mono<List<MonthlyAggregateRow>> schemeReceipts = CrossServiceCallHelper.guarded(
                "receipts-aggregate-monthly[SCHEME]",
                contributionsClient.aggregateReceiptsMonthly(periodStart, periodEnd, "SCHEME"),
                List.of(), warnings);
        Mono<List<MonthlyAggregateRow>> groupReceipts = CrossServiceCallHelper.guarded(
                "receipts-aggregate-monthly[GROUP]",
                contributionsClient.aggregateReceiptsMonthly(periodStart, periodEnd, "GROUP"),
                List.of(), warnings);
        Mono<List<MonthlyAggregateRow>> memberReceipts = CrossServiceCallHelper.guarded(
                "receipts-aggregate-monthly[MEMBER]",
                contributionsClient.aggregateReceiptsMonthly(periodStart, periodEnd, "MEMBER"),
                List.of(), warnings);

        return Mono.zip(objects -> {
            List<DimensionRow> schemes = compose(cast(objects[0]), cast(objects[3]));
            List<DimensionRow> groups  = compose(cast(objects[1]), cast(objects[4]));
            List<DimensionRow> members = compose(cast(objects[2]), cast(objects[5]));
            return new CollectionRateTrendResponse(
                    periodStart, periodEnd,
                    flatten(schemes, groups, members));
        }, schemeBilling, groupBilling, memberBilling, schemeReceipts, groupReceipts, memberReceipts);
    }

    /**
     * Drop the dimension axis: sum every dimension's monthly buckets per
     * (month, currency), then recompute the rate. A dimension with a
     * month on only one side (billed but not yet received, or vice-versa)
     * still contributes — same rule as the dimension report.
     */
    private static List<MonthRow> flatten(List<DimensionRow>... dimensions) {
        Map<LocalDate, Map<String, MonthAcc>> accs = new TreeMap<>();
        for (List<DimensionRow> dim : dimensions) {
            if (dim == null) continue;
            for (DimensionRow row : dim) {
                if (row.monthlyBuckets() == null) continue;
                for (MonthlyBucket bucket : row.monthlyBuckets()) {
                    accs.computeIfAbsent(bucket.month(), m -> new TreeMap<>())
                            .computeIfAbsent(row.currencyCode(), c -> new MonthAcc())
                            .add(bucket.billed(), bucket.received());
                }
            }
        }
        List<MonthRow> out = new ArrayList<>();
        accs.forEach((month, byCurrency) -> byCurrency.forEach((currency, acc) ->
                out.add(new MonthRow(month, currency, acc.billed, acc.received,
                        ratePercent(acc.received, acc.billed)))));
        out.sort(Comparator.comparing(MonthRow::month).thenComparing(MonthRow::currencyCode,
                Comparator.nullsLast(String::compareToIgnoreCase)));
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<MonthlyAggregateRow> cast(Object o) {
        return (List<MonthlyAggregateRow>) o;
    }

    private static final class MonthAcc {
        BigDecimal billed = BigDecimal.ZERO;
        BigDecimal received = BigDecimal.ZERO;

        void add(BigDecimal b, BigDecimal r) {
            if (b != null) billed = billed.add(b);
            if (r != null) received = received.add(r);
        }
    }
}
