package com.medfund.finance.regulatory.aml;

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

class AmlXlsxServiceTest {

    private static final UUID TENANT = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

    private final AmlSummaryCalculator calculator = new AmlSummaryCalculator();
    private final AmlSummaryReportShaper shaper = new AmlSummaryReportShaper(
            (tenantId, ps, pe) -> Mono.just(AmlSummaryReportShaperTest.goldenRaw()),
            (tenantId, country, currency, asOf) -> Mono.just(AmlSummaryReportShaperTest.goldenThresholds()),
            calculator);
    // No override reader in unit tests — falls back to the bundled synthetic template.
    private final RegulatoryTemplateService templateService =
            new RegulatoryTemplateService(Optional.empty());
    private final AmlXlsxService xlsxService = new AmlXlsxService(templateService, new AmlCellMap());

    @Test
    void render_writesEveryNamedRange_intoZaSyntheticTemplate() {
        RegulatoryReportData data = shaper.compose(
                AmlSummaryReportShaperTest.goldenRaw(),
                AmlSummaryReportShaperTest.goldenThresholds(),
                TENANT, "ZA",
                LocalDate.of(2026, 4, 1), LocalDate.of(2026, 6, 30), "ZAR");

        StepVerifier.create(xlsxService.render(TENANT, data))
                .assertNext(rendered -> {
                    assertThat(rendered.source()).isEqualTo(TemplateSource.BUNDLED_SYNTHETIC);
                    assertThat(rendered.versionLabel()).contains("SYNTHETIC_2024-01-01");
                    assertThat(rendered.bytes()).isNotEmpty();
                    try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(rendered.bytes()))) {
                        // Activity totals
                        assertThat(readNumeric(wb, "AML_ACTIVITY_TOTAL_COUNT"))
                                .isEqualByComparingTo("30");
                        assertThat(readNumeric(wb, "AML_ACTIVITY_TOTAL_AMOUNT"))
                                .isEqualByComparingTo("5230000.00");
                        // Filed rate rounded to 4dp
                        assertThat(readNumeric(wb, "AML_SUMMARY_FILED_RATE"))
                                .isEqualByComparingTo("0.2");
                        // Meta cells are string
                        assertThat(readString(wb, "AML_META_COUNTRY")).isEqualTo("ZA");
                        assertThat(readString(wb, "AML_META_CURRENCY")).isEqualTo("ZAR");
                        assertThat(readString(wb, "AML_META_ENTITY_NAME"))
                                .contains("Acme Insurance ZA");
                    } catch (Exception e) {
                        throw new AssertionError("re-open of rendered XLSX failed", e);
                    }
                })
                .verifyComplete();
    }

    @Test
    void render_zwCountry_fallsBackToZaTemplate_reportsBundledSynthetic() {
        RegulatoryReportData data = shaper.compose(
                AmlSummaryReportShaperTest.goldenRaw(),
                AmlSummaryReportShaperTest.goldenThresholds(),
                TENANT, "ZW",
                LocalDate.of(2026, 4, 1), LocalDate.of(2026, 6, 30), "ZWL");

        StepVerifier.create(xlsxService.render(TENANT, data))
                .assertNext(rendered -> {
                    assertThat(rendered.source()).isEqualTo(TemplateSource.BUNDLED_SYNTHETIC);
                    try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(rendered.bytes()))) {
                        // ZW template is deferred to Phase 26 — we render into the ZA
                        // synthetic template but the meta cells still show ZW / ZWL.
                        assertThat(readString(wb, "AML_META_COUNTRY")).isEqualTo("ZW");
                        assertThat(readString(wb, "AML_META_CURRENCY")).isEqualTo("ZWL");
                    } catch (Exception e) {
                        throw new AssertionError("re-open of rendered XLSX failed", e);
                    }
                })
                .verifyComplete();
    }

    @Test
    void pickReportKeyFile_maps_countryToTemplateStem_defaultsToZaOnMissing() {
        RegulatoryReportData zw = shaper.compose(
                AmlSummaryReportShaperTest.goldenRaw(),
                AmlSummaryReportShaperTest.goldenThresholds(),
                TENANT, "ZW",
                LocalDate.of(2026, 4, 1), LocalDate.of(2026, 6, 30), "ZWL");
        RegulatoryReportData za = shaper.compose(
                AmlSummaryReportShaperTest.goldenRaw(),
                AmlSummaryReportShaperTest.goldenThresholds(),
                TENANT, "ZA",
                LocalDate.of(2026, 4, 1), LocalDate.of(2026, 6, 30), "ZAR");
        RegulatoryReportData us = shaper.compose(
                AmlSummaryReportShaperTest.goldenRaw(),
                AmlSummaryReportShaperTest.goldenThresholds(),
                TENANT, "US",
                LocalDate.of(2026, 4, 1), LocalDate.of(2026, 6, 30), "USD");
        RegulatoryReportData empty = RegulatoryReportData.builder(
                ReportKey.AML_STR, TENANT,
                LocalDate.of(2026, 4, 1), LocalDate.of(2026, 6, 30), "ZAR").build();

        // Phase 25 only ships the ZA template — ZW + US fall back with a WARN log.
        assertThat(AmlXlsxService.pickReportKeyFile(zw)).isEqualTo("za-aml-summary");
        assertThat(AmlXlsxService.pickReportKeyFile(za)).isEqualTo("za-aml-summary");
        assertThat(AmlXlsxService.pickReportKeyFile(us)).isEqualTo("za-aml-summary");
        assertThat(AmlXlsxService.pickReportKeyFile(empty)).isEqualTo("za-aml-summary");
    }

    // ── Helpers ──────────────────────────────────────────────────────

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
