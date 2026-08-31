package com.medfund.finance.regulatory.ipec;

import com.medfund.finance.regulatory.service.RegulatoryReportData;
import com.medfund.shared.report.ReportKey;
import com.medfund.shared.report.regulatory.RegulatoryTemplateService;
import com.medfund.shared.report.regulatory.TemplateSource;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Name;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class IpecXlsxServiceTest {

    private static final UUID TENANT = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private final IpecSolvencyCalculator calculator = new IpecSolvencyCalculator();
    private final IpecReportShaper shaper = new IpecReportShaper(
            (tenantId, ps, pe) -> reactor.core.publisher.Mono.just(goldenRaw()),
            calculator);
    // No override reader in unit tests — falls back to the bundled synthetic template.
    private final RegulatoryTemplateService templateService =
            new RegulatoryTemplateService(Optional.empty());
    private final IpecXlsxService xlsxService = new IpecXlsxService(templateService, new IpecCellMap());

    @Test
    void render_writesEveryNamedRange_intoBundledSyntheticTemplate() {
        RegulatoryReportData data = shaper.compose(goldenRaw(), TENANT,
                LocalDate.of(2026, 4, 1), LocalDate.of(2026, 6, 30), "ZWL");

        StepVerifier.create(xlsxService.render(TENANT, data))
                .assertNext(rendered -> {
                    assertThat(rendered.source()).isEqualTo(TemplateSource.BUNDLED_SYNTHETIC);
                    assertThat(rendered.versionLabel()).contains("SYNTHETIC_2024-06-01");
                    assertThat(rendered.bytes()).isNotEmpty();

                    // Re-open the produced workbook and verify the named-range values landed.
                    try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(rendered.bytes()))) {
                        assertThat(readNumeric(wb, "IPEC_Q_BS_TOTAL_ASSETS"))
                                .isEqualByComparingTo("12500000.00");
                        assertThat(readNumeric(wb, "IPEC_Q_BS_TOTAL_LIABILITIES"))
                                .isEqualByComparingTo("8300000.00");
                        assertThat(readNumeric(wb, "IPEC_Q_BS_TOTAL_EQUITY"))
                                .isEqualByComparingTo("4200000.00");
                        assertThat(readNumeric(wb, "IPEC_Q_SOL_CAPITAL"))
                                .isEqualByComparingTo("4200000.00");
                        assertThat(readNumeric(wb, "IPEC_Q_SOL_MIN_REQUIRED"))
                                .isEqualByComparingTo("1590900.00");
                        assertThat(readNumeric(wb, "IPEC_Q_SOL_MARGIN"))
                                .isEqualByComparingTo("2609100.00");
                        assertThat(readNumeric(wb, "IPEC_Q_SOL_RATIO"))
                                .isEqualByComparingTo("2.6400");
                        assertThat(wb.getSheet("Quarterly Return")
                                .getRow(cellReferenceRow(wb, "IPEC_Q_META_CURRENCY"))
                                .getCell(cellReferenceCol(wb, "IPEC_Q_META_CURRENCY"))
                                .getStringCellValue())
                                .isEqualTo("ZWL");
                    } catch (Exception e) {
                        throw new AssertionError("re-open of rendered XLSX failed", e);
                    }
                })
                .verifyComplete();
    }

    @Test
    void render_reportsSyntheticTemplateSource_forAngularWarningBanner() {
        RegulatoryReportData data = RegulatoryReportData.builder(
                ReportKey.IPEC_QUARTERLY_RETURN, TENANT,
                LocalDate.of(2026, 4, 1), LocalDate.of(2026, 6, 30), "ZWL")
                .build();

        StepVerifier.create(xlsxService.render(TENANT, data))
                .assertNext(rendered -> assertThat(rendered.source()).isEqualTo(TemplateSource.BUNDLED_SYNTHETIC))
                .verifyComplete();
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static BigDecimal readNumeric(Workbook wb, String rangeName) {
        Name name = wb.getName(rangeName);
        assertThat(name).as("named range %s", rangeName).isNotNull();
        CellReference ref = new CellReference(stripSheet(name.getRefersToFormula()));
        var row = wb.getSheet(name.getSheetName()).getRow(ref.getRow());
        var cell = row.getCell(ref.getCol());
        assertThat(cell.getCellType()).isEqualTo(CellType.NUMERIC);
        return BigDecimal.valueOf(cell.getNumericCellValue());
    }

    private static int cellReferenceRow(Workbook wb, String rangeName) {
        Name name = wb.getName(rangeName);
        return new CellReference(stripSheet(name.getRefersToFormula())).getRow();
    }

    private static int cellReferenceCol(Workbook wb, String rangeName) {
        Name name = wb.getName(rangeName);
        return new CellReference(stripSheet(name.getRefersToFormula())).getCol();
    }

    private static String stripSheet(String formula) {
        int bang = formula.indexOf('!');
        return bang < 0 ? formula : formula.substring(bang + 1);
    }

    private static IpecRawData goldenRaw() {
        return new IpecRawData(
                "Acme Insurance ZW",
                "IPEC-STI-000123",
                new BigDecimal("12500000.00"),
                new BigDecimal("8300000.00"),
                new BigDecimal("3000000.00"),
                new BigDecimal("1500000.00"),
                new BigDecimal("500000.00"),
                new BigDecimal("1000000.00"),
                new BigDecimal("3800000.00"),
                new BigDecimal("2400000.00"),
                new BigDecimal("600000.00"),
                new BigDecimal("800000.00"),
                new BigDecimal("400000.00"),
                new BigDecimal("150000.00"),
                new BigDecimal("900000.00"),
                new BigDecimal("300000.00"),
                new BigDecimal("100000.00"),
                new BigDecimal("250000.00"),
                new BigDecimal("80000.00"),
                new BigDecimal("40000.00"),
                new BigDecimal("200000.00"),
                new BigDecimal("90000.00"));
    }
}
