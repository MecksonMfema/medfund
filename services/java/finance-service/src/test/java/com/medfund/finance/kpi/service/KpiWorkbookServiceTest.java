package com.medfund.finance.kpi.service;

import com.medfund.finance.kpi.dto.KpiReportData;
import com.medfund.finance.kpi.dto.KpiRequest;
import com.medfund.finance.kpi.dto.KpiTrendPoint;
import com.medfund.finance.kpi.dto.KpiValue;
import com.medfund.shared.report.ReportKey;
import com.medfund.shared.report.ReportPeriod;
import com.medfund.shared.report.ReportResponse;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Phase 18 §Phase 8 — POI byte inspection of the KPI workbook. Verifies the
 * sheet count, header row wording, meta rows, and per-currency rows for
 * every KPI shape. Uses the real {@link com.medfund.shared.report.ReportWorkbook}
 * builder so any drift in styling / row ordering surfaces here.
 */
@ExtendWith(MockitoExtension.class)
class KpiWorkbookServiceTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final LocalDate PS = LocalDate.of(2026, 8, 1);
    private static final LocalDate PE = LocalDate.of(2026, 9, 1);

    @Mock private KpiComposerService composer;
    @InjectMocks private KpiWorkbookService service;

    private KpiRequest req;

    @BeforeEach
    void setup() {
        req = new KpiRequest(TENANT, PS, PE, "USD", null, null, null);
    }

    private static ReportResponse<KpiReportData> envelope(ReportKey key, KpiReportData data, List<String> warnings) {
        return new ReportResponse<>(
                key.name(),
                new ReportPeriod(PS, PE, ReportPeriod.PeriodGrain.MONTHLY),
                "USD",
                data,
                Map.of(),
                Map.of(),
                warnings,
                OffsetDateTime.now());
    }

    private static KpiReportData data(BigDecimal composite, BigDecimal num, BigDecimal den,
                                      String basisNote, Map<String, KpiValue> perCurrency) {
        return new KpiReportData(composite, num, den, basisNote, perCurrency);
    }

    private static KpiValue value(BigDecimal ratio, BigDecimal num, BigDecimal den, String currency) {
        return new KpiValue(ratio, num, den, currency);
    }

    private static List<KpiTrendPoint> trend12(BigDecimal[] ratios) {
        java.util.List<KpiTrendPoint> pts = new java.util.ArrayList<>();
        LocalDate anchor = LocalDate.of(2026, 9, 1);
        for (int i = ratios.length; i >= 1; i--) {
            LocalDate start = anchor.minusMonths(i);
            LocalDate end = anchor.minusMonths(i - 1);
            KpiReportData d = data(ratios[ratios.length - i], BigDecimal.ONE, BigDecimal.ONE, null, Map.of());
            pts.add(new KpiTrendPoint(start, end, d, Map.of(), List.of()));
        }
        return pts;
    }

    private static Sheet firstSheet(byte[] bytes, String name) throws Exception {
        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            return wb.getSheet(name);
        }
    }

    private static Workbook openBook(byte[] bytes) throws Exception {
        return new XSSFWorkbook(new ByteArrayInputStream(bytes));
    }

    @Test
    void workbook_lossRatio_summarySheetContainsTitleMetaAndPerCurrencyRows() throws Exception {
        Map<String, KpiValue> per = new LinkedHashMap<>();
        per.put("USD", value(new BigDecimal("0.60"), new BigDecimal("60"), new BigDecimal("100"), "USD"));
        per.put("EUR", value(new BigDecimal("0.55"), new BigDecimal("55"), new BigDecimal("100"), "EUR"));
        when(composer.lossRatio(eq(req))).thenReturn(Mono.just(envelope(ReportKey.LOSS_RATIO_KPI,
                data(new BigDecimal("0.60"), new BigDecimal("60"), new BigDecimal("100"), null, per),
                List.of())));
        when(composer.trend(eq(ReportKey.LOSS_RATIO_KPI), eq(req), eq(12)))
                .thenReturn(Mono.just(trend12(new BigDecimal[]{new BigDecimal("0.50"), new BigDecimal("0.60")})));

        byte[] bytes = service.workbook(ReportKey.LOSS_RATIO_KPI, req).block();
        assertThat(bytes).isNotEmpty();

        try (Workbook wb = openBook(bytes)) {
            assertThat(wb.getNumberOfSheets()).isEqualTo(2);
            Sheet summary = wb.getSheet("Summary");
            assertThat(summary).isNotNull();
            assertThat(summary.getRow(0).getCell(0).getStringCellValue()).isEqualTo("Loss ratio (KPI)");
            // Meta rows land in order — period start, period end, reporting currency, composite ratio,
            // numerator (money), denominator (money), basis. Composite ratio uses formatted "60.0%".
            String compositeRatioCell = valueOfMetaWithLabel(summary, "Composite ratio");
            assertThat(compositeRatioCell).isEqualTo("60.0%");
            String basisCell = valueOfMetaWithLabel(summary, "Basis");
            assertThat(basisCell).isEqualTo("Single basis");
            // Header + 2 per-currency rows present.
            int headerRow = findRowContaining(summary, "Currency");
            assertThat(headerRow).isNotEqualTo(-1);
            assertThat(summary.getRow(headerRow + 1).getCell(0).getStringCellValue()).isEqualTo("USD");
            assertThat(summary.getRow(headerRow + 2).getCell(0).getStringCellValue()).isEqualTo("EUR");
        }
    }

    @Test
    void workbook_combinedRatio_summarySheetCarriesMixedBasisNote() throws Exception {
        Map<String, KpiValue> per = Map.of();
        when(composer.combinedRatio(eq(req))).thenReturn(Mono.just(envelope(ReportKey.COMBINED_RATIO,
                data(new BigDecimal("0.85"), new BigDecimal("85"), new BigDecimal("100"),
                        "MIXED_LOSS_EARNED_EXPENSE_WRITTEN", per),
                List.of())));
        when(composer.trend(eq(ReportKey.COMBINED_RATIO), any(), eq(12))).thenReturn(Mono.just(List.of()));

        byte[] bytes = service.workbook(ReportKey.COMBINED_RATIO, req).block();
        try (Workbook wb = openBook(bytes)) {
            Sheet summary = wb.getSheet("Summary");
            assertThat(valueOfMetaWithLabel(summary, "Basis")).isEqualTo("MIXED_LOSS_EARNED_EXPENSE_WRITTEN");
        }
    }

    @Test
    void workbook_includesWarningsAsMetaRows() throws Exception {
        Map<String, KpiValue> per = Map.of();
        when(composer.lossRatio(eq(req))).thenReturn(Mono.just(envelope(ReportKey.LOSS_RATIO_KPI,
                data(new BigDecimal("0.60"), new BigDecimal("60"), new BigDecimal("100"), null, per),
                List.of("claims-service call failed", "IBNR pending"))));
        when(composer.trend(eq(ReportKey.LOSS_RATIO_KPI), any(), eq(12))).thenReturn(Mono.just(List.of()));

        byte[] bytes = service.workbook(ReportKey.LOSS_RATIO_KPI, req).block();
        try (Workbook wb = openBook(bytes)) {
            Sheet summary = wb.getSheet("Summary");
            assertThat(valueOfMetaWithLabel(summary, "Warnings")).isEqualTo("2");
            // The two warnings land under empty labels — locate them by their text.
            assertThat(anyCellContains(summary, "claims-service call failed")).isTrue();
            assertThat(anyCellContains(summary, "IBNR pending")).isTrue();
        }
    }

    @Test
    void workbook_trendSheetPopulates12Rows() throws Exception {
        Map<String, KpiValue> per = Map.of();
        when(composer.expenseRatio(eq(req))).thenReturn(Mono.just(envelope(ReportKey.EXPENSE_RATIO,
                data(new BigDecimal("0.20"), new BigDecimal("20"), new BigDecimal("100"), null, per),
                List.of())));
        BigDecimal[] ratios = new BigDecimal[12];
        for (int i = 0; i < 12; i++) ratios[i] = new BigDecimal("0." + (i + 1));
        when(composer.trend(eq(ReportKey.EXPENSE_RATIO), eq(req), eq(12)))
                .thenReturn(Mono.just(trend12(ratios)));

        byte[] bytes = service.workbook(ReportKey.EXPENSE_RATIO, req).block();
        try (Workbook wb = openBook(bytes)) {
            Sheet trend = wb.getSheet("Trend (12 months)");
            assertThat(trend).isNotNull();
            int headerRow = findRowContaining(trend, "Period start");
            assertThat(headerRow).isNotEqualTo(-1);
            // 12 data rows below the header.
            for (int i = 1; i <= 12; i++) {
                assertThat(trend.getRow(headerRow + i)).isNotNull();
                assertThat(trend.getRow(headerRow + i).getCell(0).getStringCellValue()).isNotBlank();
            }
        }
    }

    @Test
    void formatRatio_averageSeverity_rendersMoneyNotPercent() {
        String cell = KpiWorkbookService.formatRatio(ReportKey.AVERAGE_SEVERITY, new BigDecimal("1234.567"));
        assertThat(cell).isEqualTo("1234.57");
    }

    @Test
    void formatRatio_percentBasedKpis_useOneDecimalPercent() {
        for (ReportKey k : List.of(ReportKey.LOSS_RATIO_KPI, ReportKey.EXPENSE_RATIO,
                ReportKey.COMBINED_RATIO, ReportKey.CLAIMS_FREQUENCY)) {
            assertThat(KpiWorkbookService.formatRatio(k, new BigDecimal("0.6321"))).isEqualTo("63.2%");
        }
    }

    @Test
    void formatRatio_null_rendersDashPlaceholder() {
        assertThat(KpiWorkbookService.formatRatio(ReportKey.LOSS_RATIO_KPI, null)).isEqualTo("-");
    }

    @Test
    void workbook_composerFailure_propagates() {
        when(composer.lossRatio(eq(req))).thenReturn(Mono.error(new RuntimeException("peer down")));
        when(composer.trend(eq(ReportKey.LOSS_RATIO_KPI), eq(req), eq(12))).thenReturn(Mono.just(List.of()));

        StepVerifier.create(service.workbook(ReportKey.LOSS_RATIO_KPI, req))
                .expectErrorMatches(err -> err.getMessage().contains("peer down"))
                .verify();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static String valueOfMetaWithLabel(Sheet sheet, String label) {
        for (int r = 0; r <= sheet.getLastRowNum(); r++) {
            var row = sheet.getRow(r);
            if (row == null) continue;
            var c0 = row.getCell(0);
            if (c0 != null && label.equals(c0.getStringCellValue())) {
                var c1 = row.getCell(1);
                return c1 != null ? c1.toString() : "";
            }
        }
        return null;
    }

    private static int findRowContaining(Sheet sheet, String text) {
        for (int r = 0; r <= sheet.getLastRowNum(); r++) {
            var row = sheet.getRow(r);
            if (row == null) continue;
            for (int c = 0; c < row.getLastCellNum(); c++) {
                var cell = row.getCell(c);
                if (cell != null && cell.getCellType() == org.apache.poi.ss.usermodel.CellType.STRING
                        && text.equals(cell.getStringCellValue())) {
                    return r;
                }
            }
        }
        return -1;
    }

    private static boolean anyCellContains(Sheet sheet, String needle) {
        for (int r = 0; r <= sheet.getLastRowNum(); r++) {
            var row = sheet.getRow(r);
            if (row == null) continue;
            for (int c = 0; c < row.getLastCellNum(); c++) {
                var cell = row.getCell(c);
                if (cell != null && cell.getCellType() == org.apache.poi.ss.usermodel.CellType.STRING
                        && cell.getStringCellValue().contains(needle)) {
                    return true;
                }
            }
        }
        return false;
    }
}
