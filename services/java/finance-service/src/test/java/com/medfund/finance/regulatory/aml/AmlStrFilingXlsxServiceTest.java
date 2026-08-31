package com.medfund.finance.regulatory.aml;

import com.medfund.finance.regulatory.aml.entity.SuspiciousTransactionAlert;
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
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AmlStrFilingXlsxServiceTest {

    private static final UUID TENANT = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

    private final RegulatoryTemplateService templateService =
            new RegulatoryTemplateService(Optional.empty());
    private final AmlStrFilingXlsxService xlsxService = new AmlStrFilingXlsxService(
            templateService, new AmlStrFilingCellMap(), new AmlStrFilingShaper());

    @Test
    void render_zaTemplate_writesEveryNamedRange_fromFullyPopulatedAlert() {
        SuspiciousTransactionAlert alert = AmlStrFilingShaperTest.filedAlert();

        StepVerifier.create(xlsxService.render(TENANT, alert,
                        "Acme Insurance ZA (Pty) Ltd", "FIC-REG-2026-0000042", "ZA"))
                .assertNext(rendered -> {
                    assertThat(rendered.source()).isEqualTo(TemplateSource.BUNDLED_SYNTHETIC);
                    assertThat(rendered.versionLabel()).contains("SYNTHETIC_2024-01-01");
                    try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(rendered.bytes()))) {
                        assertThat(readString(wb, "AMLSTR_META_COUNTRY")).isEqualTo("ZA");
                        assertThat(readString(wb, "AMLSTR_META_TEMPLATE_KEY"))
                                .isEqualTo(AmlStrFilingShaper.TEMPLATE_KEY_ZA);
                        assertThat(readString(wb, "AMLSTR_META_ENTITY_NAME"))
                                .contains("Acme Insurance ZA");
                        assertThat(readString(wb, "AMLSTR_ALERT_TXN_REF")).isEqualTo("TXN-2026-000042");
                        assertThat(readNumeric(wb, "AMLSTR_ALERT_AMOUNT"))
                                .isEqualByComparingTo("125000.00");
                        assertThat(readString(wb, "AMLSTR_ALERT_CURRENCY")).isEqualTo("ZAR");
                        assertThat(readString(wb, "AMLSTR_ALERT_FILED_REF"))
                                .isEqualTo("FIC-STR-2026-0009999");
                    } catch (Exception e) {
                        throw new AssertionError("re-open of rendered XLSX failed", e);
                    }
                })
                .verifyComplete();
    }

    @Test
    void render_zwTemplate_pickedForZwCountry() {
        SuspiciousTransactionAlert alert = AmlStrFilingShaperTest.filedAlert();
        // Force ZW-specific meta so the visual sheet is the FIU template.
        alert.setCurrency("ZWL");

        StepVerifier.create(xlsxService.render(TENANT, alert,
                        "Acme ZW", "FIU-REG-2026-1", "ZW"))
                .assertNext(rendered -> {
                    assertThat(rendered.source()).isEqualTo(TemplateSource.BUNDLED_SYNTHETIC);
                    try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(rendered.bytes()))) {
                        // The ZW template's sheet name pinpoints the picker
                        assertThat(wb.getSheetName(0)).isEqualTo("STR");
                        assertThat(readString(wb, "AMLSTR_META_TEMPLATE_KEY"))
                                .isEqualTo(AmlStrFilingShaper.TEMPLATE_KEY_ZW);
                        assertThat(readString(wb, "AMLSTR_ALERT_CURRENCY")).isEqualTo("ZWL");
                    } catch (Exception e) {
                        throw new AssertionError(e);
                    }
                })
                .verifyComplete();
    }

    @Test
    void render_usTemplate_pickedForUsCountry() {
        SuspiciousTransactionAlert alert = AmlStrFilingShaperTest.filedAlert();
        alert.setCurrency("USD");

        StepVerifier.create(xlsxService.render(TENANT, alert,
                        "Acme US LLC", "FINCEN-REG-1", "US"))
                .assertNext(rendered -> {
                    try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(rendered.bytes()))) {
                        assertThat(wb.getSheetName(0)).isEqualTo("FinCEN-SAR");
                        assertThat(readString(wb, "AMLSTR_META_TEMPLATE_KEY"))
                                .isEqualTo(AmlStrFilingShaper.TEMPLATE_KEY_US);
                    } catch (Exception e) {
                        throw new AssertionError(e);
                    }
                })
                .verifyComplete();
    }

    @Test
    void pickReportKeyFile_mapsThreeCountries_andDefaultsToZa() {
        assertThat(AmlStrFilingXlsxService.pickReportKeyFile("ZW")).isEqualTo("zw-str-filing");
        assertThat(AmlStrFilingXlsxService.pickReportKeyFile("ZA")).isEqualTo("za-str-filing");
        assertThat(AmlStrFilingXlsxService.pickReportKeyFile("US")).isEqualTo("us-str-filing");
        assertThat(AmlStrFilingXlsxService.pickReportKeyFile("KE")).isEqualTo("za-str-filing");
        assertThat(AmlStrFilingXlsxService.pickReportKeyFile(null)).isEqualTo("za-str-filing");
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
