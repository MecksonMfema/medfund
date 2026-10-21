package com.medfund.finance.regulatory.pmb;

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

class PmbSpendXlsxServiceTest {

    private static final UUID TENANT = UUID.fromString("55555555-5555-5555-5555-555555555555");

    private final PmbSpendCalculator calculator = new PmbSpendCalculator();
    private final PmbSpendReportShaper shaper = new PmbSpendReportShaper(
            (tenantId, ps, pe) -> reactor.core.publisher.Mono.just(PmbSpendReportShaperTest.goldenRaw()),
            calculator);
    // No override reader in unit tests — falls back to the bundled synthetic template.
    private final RegulatoryTemplateService templateService =
            new RegulatoryTemplateService(Optional.empty());
    private final PmbSpendXlsxService xlsxService = new PmbSpendXlsxService(templateService, new PmbCellMap());

    @Test
    void render_writesEveryNamedRange_intoBundledSyntheticTemplate() {
        RegulatoryReportData data = shaper.compose(PmbSpendReportShaperTest.goldenRaw(), TENANT,
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), "ZAR");

        StepVerifier.create(xlsxService.render(TENANT, data))
                .assertNext(rendered -> {
                    assertThat(rendered.source()).isEqualTo(TemplateSource.BUNDLED_SYNTHETIC);
                    assertThat(rendered.versionLabel()).contains("SYNTHETIC_2026-08-30");
                    assertThat(rendered.bytes()).isNotEmpty();

                    // Re-open the produced workbook and verify the named-range values landed.
                    try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(rendered.bytes()))) {
                        assertThat(readNumeric(wb, "PMB_TOTAL_PMB_PAID"))
                                .isEqualByComparingTo("115000000.00");
                        assertThat(readNumeric(wb, "PMB_TOTAL_NON_PMB_PAID"))
                                .isEqualByComparingTo("285000000.00");
                        assertThat(readNumeric(wb, "PMB_TOTAL_ALL_CLAIMS_PAID"))
                                .isEqualByComparingTo("400000000.00");
                        assertThat(readNumeric(wb, "PMB_TOTAL_PMB_RATIO"))
                                .isEqualByComparingTo("0.2875");
                        assertThat(readNumeric(wb, "PMB_TOTAL_PMB_PAID_PER_BENEFICIARY"))
                                .isEqualByComparingTo("884.62");
                        assertThat(readNumeric(wb, "PMB_TOTAL_PMB_COUNT"))
                                .isEqualByComparingTo("7500");
                        assertThat(readNumeric(wb, "PMB_MEM_TOTAL_BENEFICIARIES"))
                                .isEqualByComparingTo("130000");
                        assertThat(readNumeric(wb, "PMB_CAT_ONCOLOGY_PAID"))
                                .isEqualByComparingTo("40000000.00");
                        assertThat(readNumeric(wb, "PMB_CAT_CARDIAC_COUNT"))
                                .isEqualByComparingTo("2500");
                        // Currency is a string cell — check separately.
                        Name currencyName = wb.getName("PMB_META_CURRENCY");
                        CellReference ref = new CellReference(stripSheet(currencyName.getRefersToFormula()));
                        assertThat(wb.getSheet(currencyName.getSheetName())
                                .getRow(ref.getRow()).getCell(ref.getCol())
                                .getStringCellValue()).isEqualTo("ZAR");
                    } catch (Exception e) {
                        throw new AssertionError("re-open of rendered XLSX failed", e);
                    }
                })
                .verifyComplete();
    }

    @Test
    void render_reportsSyntheticTemplateSource_forAngularWarningBanner() {
        RegulatoryReportData data = RegulatoryReportData.builder(
                ReportKey.PMB_SPEND, TENANT,
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

    private static String stripSheet(String formula) {
        int bang = formula.indexOf('!');
        return bang < 0 ? formula : formula.substring(bang + 1);
    }
}
