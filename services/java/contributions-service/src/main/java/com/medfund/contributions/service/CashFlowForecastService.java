package com.medfund.contributions.service;

import com.medfund.contributions.client.FinanceClient;
import com.medfund.contributions.dto.CashFlowForecastResponse;
import com.medfund.contributions.dto.CashFlowForecastResponse.CurrencySeries;
import com.medfund.contributions.dto.CashFlowForecastResponse.WeekBucket;
import com.medfund.contributions.dto.InvoiceReceiptRow;
import com.medfund.contributions.dto.PlannedOutflowRow;
import com.medfund.contributions.repository.CashFlowForecastQueryRepository;
import com.medfund.shared.report.CrossServiceCallHelper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Phase 8 13-week cash-flow forecast (D8-1..D8-7). Inflow is computed
 * locally from unpaid invoices bucketed by {@code due_date}; outflow is
 * composed from the finance-service planned-outflow feed. Both sides
 * bucket by the SAME ISO weeks so the net strip is one calendar —
 * weekStart() is the single source of truth for the week boundary.
 *
 * <p>Peer failure (finance-service down) never fails the forecast:
 * guarded() records the miss on the warnings list and the outflow side
 * renders as all-zero, which is a legitimate "no payouts planned" strip.
 */
@Service
@RequiredArgsConstructor
public class CashFlowForecastService {

    private static final int MAX_ROLLING_WEEKS = 52;

    private final CashFlowForecastQueryRepository queryRepository;
    private final FinanceClient financeClient;

    /** The forecast's window: {@code [asOf, asOf + rollingWeeks*7)}. */
    public record ForecastWindow(LocalDate start, LocalDate end, List<LocalDate> weekStarts) {}

    /**
     * Validate + clamp the rolling-window request into a concrete window.
     * Rejects {@code rollingWeeks < 1} (controller maps to 400); clamps
     * above 52 so a typo can't produce a pathological query.
     */
    public ForecastWindow buildWindow(LocalDate asOf, int rollingWeeks) {
        if (rollingWeeks < 1) {
            throw new IllegalArgumentException("rollingWeeks must be between 1 and 52");
        }
        int weeks = Math.min(rollingWeeks, MAX_ROLLING_WEEKS);
        LocalDate end = asOf.plusDays(weeks * 7L);
        List<LocalDate> weekStarts = new ArrayList<>();
        LocalDate first = weekStart(asOf);
        for (int i = 0; i < weeks; i++) {
            weekStarts.add(first.plusWeeks(i));
        }
        return new ForecastWindow(asOf, end, List.copyOf(weekStarts));
    }

    /** ISO Monday-start week containing {@code d}. */
    static LocalDate weekStart(LocalDate d) {
        return d.minusDays(d.getDayOfWeek().getValue() - DayOfWeek.MONDAY.getValue());
    }

    public Mono<CashFlowForecastResponse> compute(LocalDate asOf, int rollingWeeks,
                                                  List<String> warnings) {
        ForecastWindow window = buildWindow(asOf, rollingWeeks);
        Mono<List<InvoiceReceiptRow>> inflow = queryRepository
                .expectedReceipts(window.start(), window.end())
                .collectList();
        Mono<List<PlannedOutflowRow>> outflow = CrossServiceCallHelper.guarded(
                "payment-run-outflows",
                financeClient.plannedOutflows(window.start(), window.end()),
                List.of(), warnings);
        return Mono.zip(inflow, outflow)
                .map(t -> compose(window, t.getT1(), t.getT2(), asOf, rollingWeeks));
    }

    private CashFlowForecastResponse compose(ForecastWindow window,
                                             List<InvoiceReceiptRow> inflow,
                                             List<PlannedOutflowRow> outflow,
                                             LocalDate asOf, int rollingWeeks) {
        Set<String> currencies = new TreeSet<>();
        for (InvoiceReceiptRow row : inflow) {
            if (row != null && row.currencyCode() != null) currencies.add(row.currencyCode());
        }
        for (PlannedOutflowRow row : outflow) {
            if (row != null && row.currencyCode() != null) currencies.add(row.currencyCode());
        }

        Map<String, SeriesAcc> byCurrency = new TreeMap<>();
        for (String currency : currencies) {
            byCurrency.put(currency, new SeriesAcc());
        }
        for (InvoiceReceiptRow row : inflow) {
            if (row == null || row.currencyCode() == null || row.dueDate() == null) continue;
            byCurrency.get(row.currencyCode())
                    .weeks.computeIfAbsent(weekStart(row.dueDate()), w -> new WeekAcc())
                    .addIn(safe(row.amount()));
        }
        for (PlannedOutflowRow row : outflow) {
            if (row == null || row.currencyCode() == null || row.createdAt() == null) continue;
            LocalDate day = row.createdAt().atZone(java.time.ZoneOffset.UTC).toLocalDate();
            byCurrency.get(row.currencyCode())
                    .weeks.computeIfAbsent(weekStart(day), w -> new WeekAcc())
                    .addOut(safe(row.amount()));
        }

        List<CurrencySeries> series = byCurrency.entrySet().stream()
                .map(e -> toSeries(window, e.getKey(), e.getValue()))
                .sorted(Comparator.comparing(CurrencySeries::currencyCode,
                        Comparator.nullsLast(String::compareToIgnoreCase)))
                .toList();
        return new CashFlowForecastResponse(asOf, rollingWeeks,
                window.start(), window.end(), series);
    }

    private CurrencySeries toSeries(ForecastWindow window, String currency, SeriesAcc acc) {
        List<WeekBucket> buckets = new ArrayList<>();
        BigDecimal totalInflow = BigDecimal.ZERO;
        BigDecimal totalOutflow = BigDecimal.ZERO;
        for (LocalDate ws : window.weekStarts()) {
            WeekAcc w = acc.weeks.get(ws);
            BigDecimal in = w != null ? w.inflow : BigDecimal.ZERO;
            BigDecimal out = w != null ? w.outflow : BigDecimal.ZERO;
            totalInflow = totalInflow.add(in);
            totalOutflow = totalOutflow.add(out);
            buckets.add(new WeekBucket(ws, in, out, in.subtract(out)));
        }
        return new CurrencySeries(currency, totalInflow, totalOutflow,
                totalInflow.subtract(totalOutflow), List.copyOf(buckets));
    }

    private static BigDecimal safe(BigDecimal v) {
        return v != null ? v : BigDecimal.ZERO;
    }

    private static final class SeriesAcc {
        final Map<LocalDate, WeekAcc> weeks = new TreeMap<>();
    }

    private static final class WeekAcc {
        BigDecimal inflow = BigDecimal.ZERO;
        BigDecimal outflow = BigDecimal.ZERO;

        void addIn(BigDecimal v) { inflow = inflow.add(v); }
        void addOut(BigDecimal v) { outflow = outflow.add(v); }
    }
}
