package com.medfund.finance.regulatory.tax.wht;

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
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TaxWithheldXlsxServiceTest {

    private static final UUID TENANT = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff");

    private final TaxWithheldCalculator calc = new TaxWithheldCalculator();
    private final TaxWithheldReturnReportShaper shaper = new TaxWithheldReturnReportShaper(
            (tenantId, ps, pe) -> Mono.just(TaxWithheldReturnReportShaperTest.goldenRaw()),
            (tenantId, country, currency, asOf) -> Mono.just(TaxWithheldReturnReportShaperTest.goldenRates()),
            calc);
    private final RegulatoryTemplateService templateService =
            new RegulatoryTemplateService(Optional.empty());
    private final TaxWithheldXlsxService xlsxService = new TaxWithheldXlsxService(templateService, new TaxWithheldCellMap());

    @Test
    void render_writesEveryNamedRange_intoZaSyntheticTemplate() {
        RegulatoryReportData data = shaper.compose(
                TaxWithheldReturnReportShaperTest.goldenRaw(),
                TaxWithheldReturnReportShaperTest.goldenRates(),
                TENANT, "ZA",
                LocalDate.of(2026, 4, 1), LocalDate.of(2026, 6, 30), "ZAR");

        StepVerifier.create(xlsxService.render(TENANT, data))
                .assertNext(rendered -> {
                    assertThat(rendered.source()).isEqualTo(TemplateSource.BUNDLED_SYNTHETIC);
                    assertThat(rendered.versionLabel()).contains("SYNTHETIC_2024-01-01");
                    assertThat(rendered.bytes()).isNotEmpty();
                    try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(rendered.bytes()))) {
                        assertThat(readNumeric(wb, "WHT_TOTAL_WITHHOLDING_PAYABLE"))
                                .isEqualByComparingTo("870000.00");
                        assertThat(readNumeric(wb, "WHT_TOTAL_PAYMENTS_BASE"))
                                .isEqualByComparingTo("5800000.00");
                        assertThat(readNumeric(wb, "WHT_CAT_COMMISSION_WHT"))
                                .isEqualByComparingTo("600000.00");
                        assertThat(readString(wb, "WHT_META_COUNTRY")).isEqualTo("ZA");
                        assertThat(readString(wb, "WHT_META_CURRENCY")).isEqualTo("ZAR");
                    } catch (Exception e) {
                        throw new AssertionError("re-open of rendered XLSX failed", e);
                    }
                })
                .verifyComplete();
    }

    @Test
    void render_zwCountry_picksZwTemplate() {
        RegulatoryReportData data = shaper.compose(
                TaxWithheldReturnReportShaperTest.goldenRaw(),
                TaxWithheldReturnReportShaperTest.goldenRates(),
                TENANT, "ZW",
                LocalDate.of(2026, 4, 1), LocalDate.of(2026, 6, 30), "ZWL");

        StepVerifier.create(xlsxService.render(TENANT, data))
                .assertNext(rendered -> {
                    try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(rendered.bytes()))) {
                        assertThat(readString(wb, "WHT_META_COUNTRY")).isEqualTo("ZW");
                        assertThat(readString(wb, "WHT_META_CURRENCY")).isEqualTo("ZWL");
                    } catch (Exception e) {
                        throw new AssertionError("re-open of rendered XLSX failed", e);
                    }
                })
                .verifyComplete();
    }

    @Test
    void pickReportKeyFile_maps_countryToStem_defaultsToZaOnMissing() {
        RegulatoryReportData zw = shaper.compose(
                TaxWithheldReturnReportShaperTest.goldenRaw(),
                TaxWithheldReturnReportShaperTest.goldenRates(),
                TENANT, "ZW",
                LocalDate.of(2026, 4, 1), LocalDate.of(2026, 6, 30), "ZWL");
        RegulatoryReportData za = shaper.compose(
                TaxWithheldReturnReportShaperTest.goldenRaw(),
                TaxWithheldReturnReportShaperTest.goldenRates(),
                TENANT, "ZA",
                LocalDate.of(2026, 4, 1), LocalDate.of(2026, 6, 30), "ZAR");
        RegulatoryReportData empty = RegulatoryReportData.builder(
                ReportKey.TAX_WITHHELD_RETURN, TENANT,
                LocalDate.of(2026, 4, 1), LocalDate.of(2026, 6, 30), "ZAR").build();

        assertThat(TaxWithheldXlsxService.pickReportKeyFile(zw)).isEqualTo("zw-tax-withheld");
        assertThat(TaxWithheldXlsxService.pickReportKeyFile(za)).isEqualTo("za-tax-withheld");
        assertThat(TaxWithheldXlsxService.pickReportKeyFile(empty)).isEqualTo("za-tax-withheld");
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

    private static String readString(Workbook wb, String rangeName) {
        Name name = wb.getName(rangeName);
        assertThat(name).as("named range %s", rangeName).isNotNull();
        CellReference ref = new CellReference(stripSheet(name.getRefersToFormula()));
        var row = wb.getSheet(name.getSheetName()).getRow(ref.getRow());
        var cell = row.getCell(ref.getCol());
        return cell.getStringCellValue();
    }

    private static String stripSheet(String formula) {
        int bang = formula.indexOf('!');
        return bang < 0 ? formula : formula.substring(bang + 1);
    }
}
