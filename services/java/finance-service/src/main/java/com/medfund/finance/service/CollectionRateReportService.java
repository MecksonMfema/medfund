package com.medfund.finance.service;

import com.medfund.finance.client.ContributionsClient;
import com.medfund.finance.dto.CollectionRateReportResponse;
import com.medfund.finance.dto.CollectionRateReportResponse.DimensionRow;
import com.medfund.finance.dto.CollectionRateReportResponse.MonthlyBucket;
import com.medfund.shared.report.CrossServiceCallHelper;
import com.medfund.shared.report.MonthlyAggregateRow;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Composes the Phase 3 collection-rate report from
 * contributions-service's cross-service billing + receipts monthly
 * aggregates. Cross-service resilience via
 * {@link CrossServiceCallHelper} — a peer down populates
 * {@code warnings} and the report still renders with partial data (G37 /
 * invariant #7). Per-currency rates only, no cross-currency conversion
 * of the ratio itself (G34).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CollectionRateReportService {

    private final ContributionsClient contributionsClient;

    /**
     * Compose the collection-rate report for {@code [periodStart, periodEnd]}.
     * {@code warnings} is a mutable list the caller passes through to the
     * envelope so peer-failure notes bubble up to Angular as banner text.
     */
    public Mono<CollectionRateReportResponse> compute(LocalDate periodStart, LocalDate periodEnd,
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
            List<MonthlyAggregateRow> sB = cast(objects[0]);
            List<MonthlyAggregateRow> gB = cast(objects[1]);
            List<MonthlyAggregateRow> mB = cast(objects[2]);
            List<MonthlyAggregateRow> sR = cast(objects[3]);
            List<MonthlyAggregateRow> gR = cast(objects[4]);
            List<MonthlyAggregateRow> mR = cast(objects[5]);
            return new CollectionRateReportResponse(
                    periodStart, periodEnd,
                    compose(sB, sR),
                    compose(gB, gR),
                    compose(mB, mR));
        }, schemeBilling, groupBilling, memberBilling, schemeReceipts, groupReceipts, memberReceipts);
    }

    /** Compose one dimension's rows from its billing + receipts aggregates. */
    static List<DimensionRow> compose(List<MonthlyAggregateRow> billing,
                                       List<MonthlyAggregateRow> receipts) {
        Map<DimensionKey, DimensionBucket> buckets = new LinkedHashMap<>();
        for (MonthlyAggregateRow row : billing) {
            DimensionKey key = DimensionKey.of(row);
            buckets.computeIfAbsent(key, k -> new DimensionBucket(k, row.dimensionName()))
                    .addBilled(row.month(), row.totalAmount());
        }
        for (MonthlyAggregateRow row : receipts) {
            DimensionKey key = DimensionKey.of(row);
            buckets.computeIfAbsent(key, k -> new DimensionBucket(k, row.dimensionName()))
                    .addReceived(row.month(), row.totalAmount());
        }
        return buckets.values().stream()
                .map(DimensionBucket::toRow)
                .sorted(Comparator.comparing(DimensionRow::dimensionName,
                                Comparator.nullsLast(String::compareToIgnoreCase))
                        .thenComparing(DimensionRow::currencyCode,
                                Comparator.nullsLast(String::compareToIgnoreCase)))
                .toList();
    }

    /**
     * Compute a percentage ratio, rounded to two decimals. Returns
     * {@code null} on a zero denominator — treasurer sees "—" rather
     * than a fake 0% or a division-by-zero.
     */
    static BigDecimal ratePercent(BigDecimal received, BigDecimal billed) {
        if (billed == null || billed.signum() == 0) return null;
        if (received == null) received = BigDecimal.ZERO;
        return received.multiply(BigDecimal.valueOf(100))
                .divide(billed, 2, RoundingMode.HALF_UP);
    }

    @SuppressWarnings("unchecked")
    private static List<MonthlyAggregateRow> cast(Object o) {
        return (List<MonthlyAggregateRow>) o;
    }

    /** (dimensionId, currency) — never cross-currency (G34). */
    private record DimensionKey(UUID dimensionId, String currencyCode) {
        static DimensionKey of(MonthlyAggregateRow row) {
            return new DimensionKey(row.dimensionId(), row.currencyCode());
        }
    }

    private static final class DimensionBucket {
        private final DimensionKey key;
        private final String dimensionName;
        private final TreeMap<LocalDate, MonthAcc> months = new TreeMap<>();

        DimensionBucket(DimensionKey key, String dimensionName) {
            this.key = key;
            this.dimensionName = dimensionName;
        }

        void addBilled(LocalDate month, BigDecimal amount) {
            months.computeIfAbsent(month, m -> new MonthAcc()).billed = safe(amount);
        }

        void addReceived(LocalDate month, BigDecimal amount) {
            months.computeIfAbsent(month, m -> new MonthAcc()).received = safe(amount);
        }

        DimensionRow toRow() {
            List<MonthlyBucket> buckets = new ArrayList<>(months.size());
            BigDecimal totalBilled = BigDecimal.ZERO;
            BigDecimal totalReceived = BigDecimal.ZERO;
            for (Map.Entry<LocalDate, MonthAcc> entry : months.entrySet()) {
                MonthAcc acc = entry.getValue();
                buckets.add(new MonthlyBucket(entry.getKey(),
                        acc.billed, acc.received, ratePercent(acc.received, acc.billed)));
                totalBilled = totalBilled.add(acc.billed);
                totalReceived = totalReceived.add(acc.received);
            }
            return new DimensionRow(key.dimensionId(), dimensionName, key.currencyCode(),
                    buckets, totalBilled, totalReceived, ratePercent(totalReceived, totalBilled));
        }

        private static BigDecimal safe(BigDecimal v) {
            return v != null ? v : BigDecimal.ZERO;
        }
    }

    private static final class MonthAcc {
        BigDecimal billed = BigDecimal.ZERO;
        BigDecimal received = BigDecimal.ZERO;
    }
}
