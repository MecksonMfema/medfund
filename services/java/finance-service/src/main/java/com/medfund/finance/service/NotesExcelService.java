package com.medfund.finance.service;

import com.medfund.finance.dto.NoteFilterParams;
import com.medfund.finance.dto.NoteRow;
import com.medfund.finance.repository.NoteQueryRepository;
import com.medfund.shared.report.FxRateReader;
import com.medfund.shared.report.ReportWorkbook;
import com.medfund.shared.report.ReportingCurrencyResolver;
import com.medfund.shared.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * XLSX export for the finance-side Notes list. First of the seven Phase-1
 * §B XLSX exports built on top of the shared {@link ReportWorkbook} —
 * later families (payment advice, payment run, advance, ctc, reconciliation,
 * beneficiary annual totals) follow the same template.
 *
 * <p>Row-level amounts stay native per G25. When
 * {@code reportingCurrency} is supplied, a rightmost "Amount in
 * {reportingCurrency}" column is populated from the same best-effort
 * {@link FxRateReader#findRate FX lookup} the envelope uses — missing FX
 * rates leave the cell blank rather than failing the workbook.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotesExcelService {

    /** Hard ceiling on the number of rows exported in one call. */
    private static final int MAX_ROWS = 10_000;

    private final NoteQueryRepository queryRepository;
    private final ReportingCurrencyResolver currencyResolver;
    private final FxRateReader fxRateReader;

    public Mono<byte[]> generate(NoteFilterParams params, String overrideCurrency) {
        return Mono.deferContextual(ctx -> {
            String tenantIdStr = TenantContext.get(ctx);
            UUID tenantId = parseTenantId(tenantIdStr);
            Mono<String> reportingCurrencyMono = currencyResolver.resolve(tenantId, overrideCurrency);
            Mono<List<NoteRow>> rowsMono = queryRepository.search(params, MAX_ROWS, 0).collectList();
            return Mono.zip(reportingCurrencyMono, rowsMono)
                    .flatMap(tuple -> {
                        String reportingCurrency = tuple.getT1();
                        List<NoteRow> rows = tuple.getT2();
                        boolean includeReportingCurrency =
                                overrideCurrency != null && !overrideCurrency.isBlank();
                        return loadRates(rows, reportingCurrency, tenantId, includeReportingCurrency)
                                .map(rates -> render(rows, params, reportingCurrency,
                                        includeReportingCurrency, rates));
                    });
        });
    }

    private Mono<Map<String, BigDecimal>> loadRates(List<NoteRow> rows, String reportingCurrency,
                                                    UUID tenantId, boolean include) {
        if (!include || rows.isEmpty()) return Mono.just(Map.of());
        return Flux.fromIterable(rows)
                .map(NoteRow::currencyCode)
                .filter(ccy -> ccy != null && !ccy.isBlank())
                .distinct()
                .flatMap(nativeCcy -> fxRateReader.findRate(nativeCcy, reportingCurrency,
                                LocalDate.now(), tenantId)
                        .map(rate -> Map.entry(nativeCcy, rate)))
                .collectMap(Map.Entry::getKey, Map.Entry::getValue)
                .defaultIfEmpty(new HashMap<>());
    }

    private byte[] render(List<NoteRow> rows, NoteFilterParams params, String reportingCurrency,
                          boolean includeReportingCurrency, Map<String, BigDecimal> rates) {
        ReportWorkbook wb = ReportWorkbook.newBook();
        ReportWorkbook.SheetWriter sheet = wb.sheet("Notes")
                .titleMerged("Notes", includeReportingCurrency ? 11 : 10)
                .meta("Direction",     params.direction()    != null ? params.direction()    : "All")
                .meta("Note type",     params.noteType()     != null ? params.noteType()     : "All")
                .meta("Status",        params.status()       != null ? params.status()       : "All")
                .meta("Currency",      params.currencyCode() != null ? params.currencyCode() : "All")
                .meta("Search",        params.q()            != null && !params.q().isBlank() ? params.q() : "—")
                .meta("Exported at",   LocalDate.now().toString())
                .meta("Rows",          String.valueOf(rows.size()));
        if (includeReportingCurrency) sheet.meta("Reporting currency", reportingCurrency);
        sheet.blankRow();

        if (includeReportingCurrency) {
            sheet.header("Note #", "Direction", "Type", "Status", "Payee",
                    "Reason", "Amount", "Currency", "Posted at", "Created at",
                    "Amount in " + reportingCurrency);
        } else {
            sheet.header("Note #", "Direction", "Type", "Status", "Payee",
                    "Reason", "Amount", "Currency", "Posted at", "Created at");
        }

        for (NoteRow row : rows) {
            sheet.text(row.noteNumber())
                    .text(row.direction())
                    .text(row.noteType())
                    .text(row.status())
                    .text(payeeLabel(row))
                    .text(row.reason())
                    .money(row.amount())
                    .text(row.currencyCode())
                    .date(row.postedAt())
                    .date(row.createdAt());
            if (includeReportingCurrency) {
                BigDecimal rate = row.currencyCode() != null ? rates.get(row.currencyCode()) : null;
                BigDecimal converted = row.amount() != null && rate != null
                        ? row.amount().multiply(rate)
                        : null;
                sheet.money(converted);
            }
            sheet.nextRow();
        }

        return sheet.freezeAtHeader().autoSize().toBytes();
    }

    private static String payeeLabel(NoteRow row) {
        if (row.providerName() != null && !row.providerName().isBlank()) {
            return "Provider: " + row.providerName();
        }
        if (row.memberName() != null && !row.memberName().isBlank()) {
            return "Member: " + row.memberName();
        }
        return "—";
    }

    private static UUID parseTenantId(String tenantIdStr) {
        if (tenantIdStr == null || tenantIdStr.isBlank()) return null;
        try {
            return UUID.fromString(tenantIdStr);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
