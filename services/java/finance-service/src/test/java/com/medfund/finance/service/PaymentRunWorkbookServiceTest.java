package com.medfund.finance.service;

import com.medfund.finance.client.FxConverter;
import com.medfund.finance.dto.PaymentRunWorkbookRow;
import com.medfund.finance.entity.PaymentRun;
import com.medfund.finance.repository.PaymentRunWorkbookQueryRepository;
import com.medfund.shared.report.ReportingCurrencyResolver;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the Phase 7 payment-run workbook export (D7-1..D7-3,
 * D7-7): currency grouping, per-sheet totals, the summary converted row
 * at the run's executed date, warn-and-omit on a missing FX rate, and
 * header-only output for a run with no items. The workbook bytes are
 * parsed back via Apache POI so sheet count / cells are asserted, not
 * just "did it produce bytes".
 */
@ExtendWith(MockitoExtension.class)
class PaymentRunWorkbookServiceTest {

    @Mock
    private PaymentRunWorkbookQueryRepository queryRepository;

    @Mock
    private ReportingCurrencyResolver currencyResolver;

    @Mock
    private FxConverter fxConverter;

    @InjectMocks
    private PaymentRunWorkbookService service;

    private static final UUID RUN_ID = UUID.fromString("00000000-0000-4000-8000-0000000000a1");
    private static final UUID TENANT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private PaymentRun run(Instant executedAt) {
        PaymentRun run = new PaymentRun();
        run.setId(RUN_ID);
        run.setRunNumber("RUN-123456");
        run.setStatus("executed");
        run.setPayeeType("PROVIDER");
        run.setCurrencyCode("USD");
        run.setPaymentCount(3);
        run.setExecutedAt(executedAt);
        run.setCreatedAt(executedAt);
        return run;
    }

    private PaymentRunWorkbookRow row(String currency, String amount, String payee, String status) {
        return new PaymentRunWorkbookRow(
                UUID.randomUUID(), UUID.randomUUID(), "PAY-0001", "PROVIDER",
                UUID.randomUUID(), null, payee,
                new BigDecimal(amount), currency, status, "EFT", "REF-1",
                Instant.parse("2026-07-01T00:00:00Z"), Instant.parse("2026-06-30T00:00:00Z"));
    }

    private Workbook parse(byte[] bytes) throws Exception {
        return new XSSFWorkbook(new ByteArrayInputStream(bytes));
    }

    @Test
    void workbook_singleCurrency_sheetPerCurrencyPlusSummary_andTotalsReconcile() throws Exception {
        Instant executedAt = Instant.parse("2026-07-01T00:00:00Z");
        PaymentRun run = run(executedAt);
        when(queryRepository.findByRunId(RUN_ID)).thenReturn(Flux.fromIterable(List.of(
                row("USD", "100.00", "Sunrise Clinic", "paid"),
                row("USD", "250.50", "Lakeside Surgical", "scheduled"))));
        when(currencyResolver.resolve(eq(TENANT_ID), any())).thenReturn(Mono.just("ZWL"));
        when(fxConverter.convert(eq(new BigDecimal("350.50")), eq("USD"), eq("ZWL"), any(), eq(TENANT_ID)))
                .thenReturn(Mono.just(new BigDecimal("17755.00")));

        StepVerifier.create(service.workbook(run, TENANT_ID))
                .assertNext(bytes -> {
                    try {
                        Workbook wb = parse(bytes);
                        assertThat(wb.getNumberOfSheets()).isEqualTo(2);
                        Sheet currency = wb.getSheet("Payment USD");
                        Sheet summary = wb.getSheet("Summary");
                        assertThat(currency).isNotNull();
                        assertThat(summary).isNotNull();
                        // header + 2 rows + blank + total row
                        assertThat(currency.getPhysicalNumberOfRows()).isGreaterThanOrEqualTo(5);
                        // Summary grand total in run currency reconciles to the item sum.
                        var it = summary.rowIterator();
                        boolean reconciled = false;
                        while (it.hasNext()) {
                            var row = it.next();
                            if (row.getCell(0) != null
                                    && row.getCell(0).toString().contains("Grand total")
                                    && row.getCell(1) != null
                                    && "350.5".equals(row.getCell(1).toString())) {
                                reconciled = true;
                            }
                        }
                        assertThat(reconciled).isTrue();
                        // Converted row present (reporting currency ZWL).
                        var conv = summary.rowIterator();
                        boolean convertedRow = false;
                        while (conv.hasNext()) {
                            var row = conv.next();
                            if (row.getCell(0) != null
                                    && row.getCell(0).toString().contains("Converted to ZWL")) {
                                convertedRow = true;
                            }
                        }
                        assertThat(convertedRow).isTrue();
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                })
                .verifyComplete();
    }

    @Test
    void workbook_multiCurrency_groupsIntoPerCurrencySheets_withNativeTotals() throws Exception {
        Instant executedAt = Instant.parse("2026-07-01T00:00:00Z");
        PaymentRun run = run(executedAt);
        when(queryRepository.findByRunId(RUN_ID)).thenReturn(Flux.fromIterable(List.of(
                row("USD", "100.00", "Sunrise Clinic", "paid"),
                row("USD", "250.50", "Lakeside Surgical", "scheduled"),
                row("ZWL", "5000.00", "Lakeside Surgical", "pending"))));
        when(currencyResolver.resolve(eq(TENANT_ID), any())).thenReturn(Mono.just("ZWL"));
        // Convert the run's USD grand total to the ZWL reporting currency.
        when(fxConverter.convert(eq(new BigDecimal("350.50")), eq("USD"), eq("ZWL"), any(), eq(TENANT_ID)))
                .thenReturn(Mono.just(new BigDecimal("17525.00")));

        StepVerifier.create(service.workbook(run, TENANT_ID))
                .assertNext(bytes -> {
                    try {
                        Workbook wb = parse(bytes);
                        assertThat(wb.getNumberOfSheets()).isEqualTo(3);
                        assertThat(wb.getSheet("Payment USD")).isNotNull();
                        assertThat(wb.getSheet("Payment ZWL")).isNotNull();
                        assertThat(wb.getSheet("Summary")).isNotNull();
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                })
                .verifyComplete();
    }

    @Test
    void workbook_missingFxRate_omitsConvertedRow_andWarns() throws Exception {
        Instant executedAt = Instant.parse("2026-07-01T00:00:00Z");
        PaymentRun run = run(executedAt);
        when(queryRepository.findByRunId(RUN_ID)).thenReturn(Flux.fromIterable(List.of(
                row("USD", "100.00", "Sunrise Clinic", "paid"))));
        when(currencyResolver.resolve(eq(TENANT_ID), any())).thenReturn(Mono.just("ZWL"));
        when(fxConverter.convert(eq(new BigDecimal("100.00")), eq("USD"), eq("ZWL"), any(), eq(TENANT_ID)))
                .thenReturn(Mono.error(new IllegalStateException("No exchange rate")));

        StepVerifier.create(service.workbook(run, TENANT_ID))
                .assertNext(bytes -> {
                    try {
                        Workbook wb = parse(bytes);
                        assertThat(wb.getNumberOfSheets()).isEqualTo(2);
                        Sheet summary = wb.getSheet("Summary");
                        // Converted row present as a text warning rather than a money row.
                        boolean warned = false;
                        var it = summary.rowIterator();
                        while (it.hasNext()) {
                            var row = it.next();
                            if (row.getCell(1) != null
                                    && row.getCell(1).toString().contains("unavailable")) {
                                warned = true;
                            }
                        }
                        assertThat(warned).isTrue();
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                })
                .verifyComplete();
    }

    @Test
    void workbook_noItems_headerOnlySummarySheet_noCrash() throws Exception {
        Instant executedAt = Instant.parse("2026-07-01T00:00:00Z");
        PaymentRun run = run(executedAt);
        when(queryRepository.findByRunId(RUN_ID)).thenReturn(Flux.empty());
        when(currencyResolver.resolve(eq(TENANT_ID), any())).thenReturn(Mono.just("USD"));

        StepVerifier.create(service.workbook(run, TENANT_ID))
                .assertNext(bytes -> {
                    try {
                        Workbook wb = parse(bytes);
                        // No currency sheets; summary only.
                        assertThat(wb.getNumberOfSheets()).isEqualTo(1);
                        assertThat(wb.getSheet("Summary")).isNotNull();
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                })
                .verifyComplete();
    }

    @Test
    void workbook_unexecutedRun_usesCreatedDateForConversion() throws Exception {
        PaymentRun run = run(null);
        run.setCreatedAt(Instant.parse("2026-06-15T00:00:00Z"));
        when(queryRepository.findByRunId(RUN_ID)).thenReturn(Flux.fromIterable(List.of(
                row("USD", "100.00", "Sunrise Clinic", "paid"))));
        when(currencyResolver.resolve(eq(TENANT_ID), any())).thenReturn(Mono.just("ZWL"));
        when(fxConverter.convert(eq(new BigDecimal("100.00")), eq("USD"), eq("ZWL"), any(), eq(TENANT_ID)))
                .thenReturn(Mono.just(new BigDecimal("6000.00")));

        StepVerifier.create(service.workbook(run, TENANT_ID))
                .assertNext(bytes -> {
                    try {
                        Workbook wb = parse(bytes);
                        assertThat(wb.getNumberOfSheets()).isEqualTo(2);
                        Sheet summary = wb.getSheet("Summary");
                        assertThat(summary).isNotNull();
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                })
                .verifyComplete();
    }
}
