package com.medfund.finance.regulatory.naic;

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

class NaicSchedulePXlsxServiceTest {

    private static final UUID TENANT = UUID.fromString("88888888-8888-8888-8888-888888888888");

    private final NaicSchedulePCalculator calculator = new NaicSchedulePCalculator();
    private final NaicSchedulePReportShaper shaper = new NaicSchedulePReportShaper(
            (tenantId, ps, pe) -> reactor.core.publisher.Mono.just(NaicSchedulePReportShaperTest.goldenRaw()),
            calculator);
    private final RegulatoryTemplateService templateService =
            new RegulatoryTemplateService(Optional.empty());
    private final NaicSchedulePXlsxService xlsxService =
            new NaicSchedulePXlsxService(templateService, new NaicPCellMap());

    @Test
    void render_writesEveryNamedRange_intoBundledSyntheticTemplate() {
        RegulatoryReportData data = shaper.compose(NaicSchedulePReportShaperTest.goldenRaw(), TENANT,
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), "USD");

        StepVerifier.create(xlsxService.render(TENANT, data))
                .assertNext(rendered -> {
                    assertThat(rendered.source()).isEqualTo(TemplateSource.BUNDLED_SYNTHETIC);
                    assertThat(rendered.versionLabel()).contains("SYNTHETIC_2026-08-30");
                    assertThat(rendered.bytes()).isNotEmpty();

                    try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(rendered.bytes()))) {
                        assertThat(readNumeric(wb, "NAIC_P_P1_INCURRED_AY_MINUS_2"))
                                .isEqualByComparingTo("125000000.00");
                        assertThat(readNumeric(wb, "NAIC_P_P1_INCURRED_AY_CURRENT"))
                                .isEqualByComparingTo("100000000.00");
                        assertThat(readNumeric(wb, "NAIC_P_P6_LOSS_RATIO_AY_MINUS_2"))
                                .isEqualByComparingTo("0.6944");
                        assertThat(readNumeric(wb, "NAIC_P_P6_LOSS_RATIO_AY_MINUS_1"))
                                .isEqualByComparingTo("0.5263");
                        assertThat(readNumeric(wb, "NAIC_P_P6_LOSS_RATIO_AY_CURRENT"))
                                .isEqualByComparingTo("0.5000");
                        assertThat(readNumeric(wb, "NAIC_P_TOTAL_INCURRED"))
                                .isEqualByComparingTo("325000000.00");
                        assertThat(readNumeric(wb, "NAIC_P_TOTAL_EARNED_PREMIUM"))
                                .isEqualByComparingTo("570000000.00");
                        assertThat(readNumeric(wb, "NAIC_P_OVERALL_LOSS_RATIO"))
                                .isEqualByComparingTo("0.5702");
                        assertThat(wb.getSheet("Schedule P")
                                .getRow(cellReferenceRow(wb, "NAIC_P_META_CURRENCY"))
                                .getCell(cellReferenceCol(wb, "NAIC_P_META_CURRENCY"))
                                .getStringCellValue())
                                .isEqualTo("USD");
                    } catch (Exception e) {
                        throw new AssertionError("re-open of rendered XLSX failed", e);
                    }
                })
                .verifyComplete();
    }

    @Test
    void render_reportsSyntheticTemplateSource_forAngularWarningBanner() {
        RegulatoryReportData data = RegulatoryReportData.builder(
                ReportKey.NAIC_SCHEDULE_P, TENANT,
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), "USD")
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
}
