package com.medfund.finance.regulatory.cms;

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

class CmsAsrXlsxServiceTest {

    private static final UUID TENANT = UUID.fromString("44444444-4444-4444-4444-444444444444");

    private final CmsAsrCalculator calculator = new CmsAsrCalculator();
    private final CmsAsrReportShaper shaper = new CmsAsrReportShaper(
            (tenantId, ps, pe) -> reactor.core.publisher.Mono.just(CmsAsrReportShaperTest.goldenRaw()),
            calculator);
    // No override reader in unit tests — falls back to the bundled synthetic template.
    private final RegulatoryTemplateService templateService =
            new RegulatoryTemplateService(Optional.empty());
    private final CmsAsrXlsxService xlsxService = new CmsAsrXlsxService(templateService, new CmsCellMap());

    @Test
    void render_writesEveryNamedRange_intoBundledSyntheticTemplate() {
        RegulatoryReportData data = shaper.compose(CmsAsrReportShaperTest.goldenRaw(), TENANT,
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), "ZAR");

        StepVerifier.create(xlsxService.render(TENANT, data))
                .assertNext(rendered -> {
                    assertThat(rendered.source()).isEqualTo(TemplateSource.BUNDLED_SYNTHETIC);
                    assertThat(rendered.versionLabel()).contains("SYNTHETIC_2026-08-30");
                    assertThat(rendered.bytes()).isNotEmpty();

                    // Re-open the produced workbook and verify the named-range values landed.
                    try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(rendered.bytes()))) {
                        assertThat(readNumeric(wb, "CMS_ASR_BS_TOTAL_ASSETS"))
                                .isEqualByComparingTo("250000000.00");
                        assertThat(readNumeric(wb, "CMS_ASR_BS_TOTAL_LIABILITIES"))
                                .isEqualByComparingTo("100000000.00");
                        assertThat(readNumeric(wb, "CMS_ASR_BS_ACCUMULATED_FUNDS"))
                                .isEqualByComparingTo("150000000.00");
                        assertThat(readNumeric(wb, "CMS_ASR_INC_NET_SURPLUS"))
                                .isEqualByComparingTo("25000000.00");
                        assertThat(readNumeric(wb, "CMS_ASR_RATIO_CLAIMS"))
                                .isEqualByComparingTo("0.8000");
                        assertThat(readNumeric(wb, "CMS_ASR_SOL_ACCUMULATED_FUNDS"))
                                .isEqualByComparingTo("150000000.00");
                        assertThat(readNumeric(wb, "CMS_ASR_SOL_MIN_REQUIRED_RESERVES"))
                                .isEqualByComparingTo("125000000.00");
                        assertThat(readNumeric(wb, "CMS_ASR_SOL_ACTUAL_RATIO"))
                                .isEqualByComparingTo("0.3000");
                        assertThat(readNumeric(wb, "CMS_ASR_SOL_SURPLUS_DEFICIT"))
                                .isEqualByComparingTo("25000000.00");
                        assertThat(readNumeric(wb, "CMS_ASR_MEM_TOTAL_BENEFICIARIES"))
                                .isEqualByComparingTo("130000");
                        assertThat(wb.getSheet("Annual Statutory Return")
                                .getRow(cellReferenceRow(wb, "CMS_ASR_META_CURRENCY"))
                                .getCell(cellReferenceCol(wb, "CMS_ASR_META_CURRENCY"))
                                .getStringCellValue())
                                .isEqualTo("ZAR");
                    } catch (Exception e) {
                        throw new AssertionError("re-open of rendered XLSX failed", e);
                    }
                })
                .verifyComplete();
    }

    @Test
    void render_reportsSyntheticTemplateSource_forAngularWarningBanner() {
        RegulatoryReportData data = RegulatoryReportData.builder(
                ReportKey.CMS_ASR, TENANT,
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), "ZAR")
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
