package com.medfund.contributions.premium.service;

import com.medfund.contributions.premium.dto.PremiumRegisterRow;
import com.medfund.contributions.premium.repository.PremiumReportQueryRepository;
import com.medfund.shared.report.FxRateReader;
import com.medfund.shared.report.ReportingCurrencyResolver;
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
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Verifies the Premium Register XLSX shape: single Register sheet with
 * per-policy rows + Summary sheet with per-currency native totals and a
 * best-effort converted grand total.
 */
@ExtendWith(MockitoExtension.class)
class PremiumRegisterWorkbookServiceTest {

    @Mock PremiumReportQueryRepository queryRepository;
    @Mock ReportingCurrencyResolver currencyResolver;
    @Mock FxRateReader fxRateReader;

    @InjectMocks PremiumRegisterWorkbookService service;

    private static final UUID TENANT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final LocalDate PERIOD_START = LocalDate.of(2026, 4, 1);
    private static final LocalDate PERIOD_END   = LocalDate.of(2026, 6, 30);

    private Workbook parse(byte[] bytes) throws Exception {
        return new XSSFWorkbook(new ByteArrayInputStream(bytes));
    }

    @Test
    void workbook_singleCurrency_producesRegisterAndSummarySheets() throws Exception {
        when(currencyResolver.resolve(eq(TENANT_ID), any())).thenReturn(Mono.just("USD"));
        when(fxRateReader.findRate(eq("USD"), eq("USD"), any(), eq(TENANT_ID)))
                .thenReturn(Mono.just(BigDecimal.ONE));
        when(queryRepository.premiumRegisterRows(any(), any(), any()))
                .thenReturn(Flux.fromIterable(List.of(row("USD", "100"), row("USD", "200"))));

        StepVerifier.create(service.workbook(PERIOD_START, PERIOD_END, null, null, TENANT_ID))
                .assertNext(bytes -> {
                    try (Workbook wb = parse(bytes)) {
                        assertThat(wb.getSheet("Register")).isNotNull();
                        assertThat(wb.getSheet("Summary")).isNotNull();
                        boolean usdRow = false;
                        var it = wb.getSheet("Summary").rowIterator();
                        while (it.hasNext()) {
                            var row = it.next();
                            if (row.getCell(0) == null) continue;
                            String c0 = row.getCell(0).toString();
                            String c1 = row.getCell(1) != null ? row.getCell(1).toString() : "";
                            if ("USD".equals(c0) && c1.startsWith("300.")) usdRow = true;
                        }
                        assertThat(usdRow).isTrue();
                    } catch (Exception e) { throw new AssertionError(e); }
                })
                .verifyComplete();
    }

    @Test
    void workbook_missingFx_omitsConvertedTotal() throws Exception {
        when(currencyResolver.resolve(eq(TENANT_ID), any())).thenReturn(Mono.just("EUR"));
        when(fxRateReader.findRate(eq("USD"), eq("EUR"), any(), eq(TENANT_ID)))
                .thenReturn(Mono.empty());
        when(queryRepository.premiumRegisterRows(any(), any(), any()))
                .thenReturn(Flux.fromIterable(List.of(row("USD", "100"))));

        StepVerifier.create(service.workbook(PERIOD_START, PERIOD_END, null, "EUR", TENANT_ID))
                .assertNext(bytes -> {
                    try (Workbook wb = parse(bytes)) {
                        boolean unavailable = false;
                        var it = wb.getSheet("Summary").rowIterator();
                        while (it.hasNext()) {
                            var row = it.next();
                            if (row.getCell(0) == null) continue;
                            String c0 = row.getCell(0).toString();
                            String c1 = row.getCell(1) != null ? row.getCell(1).toString() : "";
                            if (c0.contains("Converted grand total")
                                    && c1.toLowerCase().contains("fx unavailable")) {
                                unavailable = true;
                            }
                        }
                        assertThat(unavailable).isTrue();
                    } catch (Exception e) { throw new AssertionError(e); }
                })
                .verifyComplete();
    }

    private static PremiumRegisterRow row(String ccy, String written) {
        return new PremiumRegisterRow(
                UUID.randomUUID(), "LIFE_POLICY", "Alice", "LIFE",
                "Gold scheme", ccy,
                new BigDecimal(written), BigDecimal.ZERO, new BigDecimal(written),
                OffsetDateTime.of(2026, 4, 1, 0, 0, 0, 0, ZoneOffset.UTC),
                LocalDate.of(2026, 4, 1), LocalDate.of(2027, 3, 31),
                false, "MISC", "MISC-2026-DEFAULT",
                PERIOD_START, PERIOD_END);
    }
}
