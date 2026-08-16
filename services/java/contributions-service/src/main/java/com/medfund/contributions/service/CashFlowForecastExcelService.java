package com.medfund.contributions.service;

import com.medfund.contributions.dto.CashFlowForecastResponse;
import com.medfund.contributions.dto.CashFlowForecastResponse.CurrencySeries;
import com.medfund.contributions.dto.CashFlowForecastResponse.WeekBucket;
import com.medfund.shared.report.ReportWorkbook;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.util.List;

/**
 * XLSX generator for the 13-week cash-flow forecast. Summary sheet first
 * (per currency: total inflow / outflow / net), then one sheet per
 * currency with the full weekly strip — so a multi-currency tenant gets
 * a workbook that matches the API's per-currency series shape.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CashFlowForecastExcelService {

    private final CashFlowForecastService forecastService;

    public Mono<byte[]> workbook(LocalDate asOf, int rollingWeeks, List<String> warnings) {
        return forecastService.compute(asOf, rollingWeeks, warnings)
                .map(forecast -> render(forecast, warnings));
    }

    private byte[] render(CashFlowForecastResponse forecast, List<String> warnings) {
        ReportWorkbook book = ReportWorkbook.newBook();

        ReportWorkbook.SheetWriter summary = book.sheet("Summary")
                .titleMerged("Cash-flow forecast", 4)
                .meta("As of", forecast.asOf() != null ? forecast.asOf().toString() : "—")
                .meta("Rolling weeks", String.valueOf(forecast.rollingWeeks()))
                .meta("Window", forecast.windowStart() + " → " + forecast.windowEnd())
                .meta("Series", String.valueOf(forecast.series().size()));

        if (warnings != null && !warnings.isEmpty()) {
            summary.meta("Warnings", String.valueOf(warnings.size()));
            for (String w : warnings) {
                summary.meta("", w);
            }
        }
        summary.blankRow();
        summary.header("Currency", "Total inflow", "Total outflow", "Net");
        summary.forEach(forecast.series(), (sw, s) -> sw.text(s.currencyCode())
                .moneyBold(s.totalInflow())
                .moneyBold(s.totalOutflow())
                .moneyBold(s.totalNet()));
        summary.freezeAtHeader().autoSize();

        if (forecast.series().isEmpty()) {
            book.sheet("Weekly").titleMerged("No invoice or payment-run activity in this window", 4);
        } else {
            for (CurrencySeries s : forecast.series()) {
                renderCurrencySheet(book, s);
            }
        }
        return book.toBytes();
    }

    private void renderCurrencySheet(ReportWorkbook book, CurrencySeries s) {
        ReportWorkbook.SheetWriter sheet = book.sheet(s.currencyCode())
                .titleMerged("Cash-flow forecast — " + s.currencyCode(), 4)
                .meta("Total inflow",  s.totalInflow().toString())
                .meta("Total outflow", s.totalOutflow().toString())
                .meta("Net",           s.totalNet().toString())
                .blankRow()
                .header("Week starting", "Inflow", "Outflow", "Net");

        for (WeekBucket b : s.buckets()) {
            sheet.date(b.weekStart())
                    .money(b.inflow())
                    .money(b.outflow())
                    .money(b.net());
        }
        sheet.freezeAtHeader().autoSize();
    }
}
