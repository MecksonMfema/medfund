package com.medfund.finance.regulatory.tax.vat;

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

class VatXlsxServiceTest {

    private static final UUID TENANT = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

    private final VatCalculator calculator = new VatCalculator();
    private final VatReturnReportShaper shaper = new VatReturnReportShaper(
            (tenantId, ps, pe) -> Mono.just(VatReturnReportShaperTest.goldenRaw()),
            (tenantId, country, currency, asOf) -> Mono.just(VatReturnReportShaperTest.goldenRates()),
            calculator);
    // No override reader in unit tests — falls back to the bundled synthetic template.
    private final RegulatoryTemplateService templateService =
            new RegulatoryTemplateService(Optional.empty());
    private final VatXlsxService xlsxService = new VatXlsxService(templateService, new VatCellMap());

    @Test
    void render_writesEveryNamedRange_intoZaSyntheticTemplate() {
        RegulatoryReportData data = shaper.compose(
                VatReturnReportShaperTest.goldenRaw(),
                VatReturnReportShaperTest.goldenRates(),
                TENANT, "ZA",
                LocalDate.of(2026, 4, 1), LocalDate.of(2026, 6, 30), "ZAR");

        StepVerifier.create(xlsxService.render(TENANT, data))
                .assertNext(rendered -> {
                    assertThat(rendered.source()).isEqualTo(TemplateSource.BUNDLED_SYNTHETIC);
                    assertThat(rendered.versionLabel()).contains("SYNTHETIC_2024-01-01");
                    assertThat(rendered.bytes()).isNotEmpty();

                    try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(rendered.bytes()))) {
                        assertThat(readNumeric(wb, "VAT_OUTPUT_STANDARD_TOTAL_VAT"))
                                .isEqualByComparingTo("1875000.00");
                        assertThat(readNumeric(wb, "VAT_OUTPUT_ZERO_RATED_TOTAL_BASE"))
                                .isEqualByComparingTo("100000000.00");
                        assertThat(readNumeric(wb, "VAT_INPUT_TOTAL_VAT"))
                                .isEqualByComparingTo("495000.00");
                        assertThat(readNumeric(wb, "VAT_SUMMARY_NET_PAYABLE"))
                                .isEqualByComparingTo("1380000.00");
                        // Currency + country are string cells.
                        assertThat(readString(wb, "VAT_META_COUNTRY")).isEqualTo("ZA");
                        assertThat(readString(wb, "VAT_META_CURRENCY")).isEqualTo("ZAR");
                    } catch (Exception e) {
                        throw new AssertionError("re-open of rendered XLSX failed", e);
                    }
                })
                .verifyComplete();
    }

    @Test
    void render_zwCountry_picksZwTemplate_reportsBundledSynthetic() {
        RegulatoryReportData data = shaper.compose(
                VatReturnReportShaperTest.goldenRaw(),
                VatReturnReportShaperTest.goldenRates(),
                TENANT, "ZW",
                LocalDate.of(2026, 4, 1), LocalDate.of(2026, 6, 30), "ZWL");

        StepVerifier.create(xlsxService.render(TENANT, data))
                .assertNext(rendered -> {
                    assertThat(rendered.source()).isEqualTo(TemplateSource.BUNDLED_SYNTHETIC);
                    try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(rendered.bytes()))) {
                        assertThat(readString(wb, "VAT_META_COUNTRY")).isEqualTo("ZW");
                        assertThat(readString(wb, "VAT_META_CURRENCY")).isEqualTo("ZWL");
                    } catch (Exception e) {
                        throw new AssertionError("re-open of rendered XLSX failed", e);
                    }
                })
                .verifyComplete();
    }

    @Test
    void pickReportKeyFile_maps_countryToTemplateStem_defaultsToZaOnMissing() {
        RegulatoryReportData zw = shaper.compose(
                VatReturnReportShaperTest.goldenRaw(),
                VatReturnReportShaperTest.goldenRates(),
                TENANT, "ZW",
                LocalDate.of(2026, 4, 1), LocalDate.of(2026, 6, 30), "ZWL");
        RegulatoryReportData za = shaper.compose(
                VatReturnReportShaperTest.goldenRaw(),
                VatReturnReportShaperTest.goldenRates(),
                TENANT, "ZA",
                LocalDate.of(2026, 4, 1), LocalDate.of(2026, 6, 30), "ZAR");
        RegulatoryReportData empty = RegulatoryReportData.builder(
                ReportKey.VAT_RETURN, TENANT,
                LocalDate.of(2026, 4, 1), LocalDate.of(2026, 6, 30), "ZAR").build();

        assertThat(VatXlsxService.pickReportKeyFile(zw)).isEqualTo("zw-vat-return");
        assertThat(VatXlsxService.pickReportKeyFile(za)).isEqualTo("za-vat-return");
        assertThat(VatXlsxService.pickReportKeyFile(empty)).isEqualTo("za-vat-return");
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
