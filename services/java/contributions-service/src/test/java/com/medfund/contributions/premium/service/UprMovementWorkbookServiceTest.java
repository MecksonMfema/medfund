package com.medfund.contributions.premium.service;

import com.medfund.contributions.premium.dto.UprMovementRow;
import com.medfund.contributions.premium.repository.PremiumReportQueryRepository;
import com.medfund.shared.report.FxRateReader;
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
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Verifies the UPR movement XLSX shape: one sheet per insurance line + a
 * Summary sheet with per-currency closing UPR and a best-effort
 * converted grand total (or FX-unavailable warning). Workbook bytes are
 * parsed back through POI so cell values are asserted.
 */
@ExtendWith(MockitoExtension.class)
class UprMovementWorkbookServiceTest {

    @Mock PremiumReportQueryRepository queryRepository;
    @Mock ReportingCurrencyResolver currencyResolver;
    @Mock FxRateReader fxRateReader;

    @InjectMocks UprMovementWorkbookService service;

    private static final UUID TENANT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final LocalDate PERIOD_START = LocalDate.of(2026, 1, 1);
    private static final LocalDate PERIOD_END   = LocalDate.of(2026, 3, 31);

    private Workbook parse(byte[] bytes) throws Exception {
        return new XSSFWorkbook(new ByteArrayInputStream(bytes));
    }

    @Test
    void workbook_perLine_producesOneSheetPerInsuranceLinePlusSummary() throws Exception {
        when(currencyResolver.resolve(eq(TENANT_ID), any())).thenReturn(Mono.just("USD"));
        when(fxRateReader.findRate(eq("USD"), eq("USD"), any(), eq(TENANT_ID)))
                .thenReturn(Mono.just(BigDecimal.ONE));
        when(queryRepository.uprMovementRows(any(), any(), any()))
                .thenReturn(Flux.fromIterable(List.of(
                        row("LIFE", "USD", "100", "500", "200", "0", "400"),
                        row("VEHICLE", "USD", "50", "300", "150", "0", "200"))));

        StepVerifier.create(service.workbook(PERIOD_START, PERIOD_END, null, null, TENANT_ID))
                .assertNext(bytes -> {
                    try (Workbook wb = parse(bytes)) {
                        assertThat(wb.getSheet("LIFE")).isNotNull();
                        assertThat(wb.getSheet("VEHICLE")).isNotNull();
                        assertThat(wb.getSheet("Summary")).isNotNull();
                    } catch (Exception e) { throw new AssertionError(e); }
                })
                .verifyComplete();
    }

    @Test
    void workbook_summaryCarriesConvertedGrandTotal_whenFxResolves() throws Exception {
        when(currencyResolver.resolve(eq(TENANT_ID), any())).thenReturn(Mono.just("USD"));
        when(fxRateReader.findRate(eq("USD"), eq("USD"), any(), eq(TENANT_ID)))
                .thenReturn(Mono.just(BigDecimal.ONE));
        when(queryRepository.uprMovementRows(any(), any(), any()))
                .thenReturn(Flux.fromIterable(List.of(
                        row("LIFE", "USD", "0", "1000", "300", "0", "700"))));

        StepVerifier.create(service.workbook(PERIOD_START, PERIOD_END, null, null, TENANT_ID))
                .assertNext(bytes -> {
                    try (Workbook wb = parse(bytes)) {
                        Sheet summary = wb.getSheet("Summary");
                        boolean convertedRow = false;
                        for (var row : summary) {
                            if (row.getCell(0) == null) continue;
                            String c0 = row.getCell(0).toString();
                            if (c0.contains("Converted grand total")) convertedRow = true;
                        }
                        assertThat(convertedRow).isTrue();
                    } catch (Exception e) { throw new AssertionError(e); }
                })
                .verifyComplete();
    }

    @Test
    void workbook_missingFx_omitsConvertedTotalAndRendersWarning() throws Exception {
        when(currencyResolver.resolve(eq(TENANT_ID), any())).thenReturn(Mono.just("EUR"));
        when(fxRateReader.findRate(eq("USD"), eq("EUR"), any(), eq(TENANT_ID)))
                .thenReturn(Mono.empty());
        when(queryRepository.uprMovementRows(any(), any(), any()))
                .thenReturn(Flux.fromIterable(List.of(
                        row("LIFE", "USD", "0", "1000", "300", "0", "700"))));

        StepVerifier.create(service.workbook(PERIOD_START, PERIOD_END, null, "EUR", TENANT_ID))
                .assertNext(bytes -> {
                    try (Workbook wb = parse(bytes)) {
                        Sheet summary = wb.getSheet("Summary");
                        boolean unavailableNoted = false;
                        boolean warningRow = false;
                        for (var row : summary) {
                            if (row.getCell(0) == null) continue;
                            String c0 = row.getCell(0).toString();
                            String c1 = row.getCell(1) != null ? row.getCell(1).toString() : "";
                            if (c0.contains("Converted grand total")
                                    && c1.toLowerCase().contains("fx unavailable")) {
                                unavailableNoted = true;
                            }
                            if (c1.contains("FX not available")) warningRow = true;
                        }
                        assertThat(unavailableNoted).isTrue();
                        assertThat(warningRow).isTrue();
                    } catch (Exception e) { throw new AssertionError(e); }
                })
                .verifyComplete();
    }

    private static UprMovementRow row(String line, String ccy, String opening, String written,
                                      String earned, String delta, String closing) {
        return new UprMovementRow(line, ccy, new BigDecimal(opening), new BigDecimal(written),
                new BigDecimal(earned), new BigDecimal(delta), new BigDecimal(closing));
    }
}
