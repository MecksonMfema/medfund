package com.medfund.shared.report.regulatory;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Name;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RegulatoryTemplateServiceTest {

    private final RegulatoryTemplateService service = new RegulatoryTemplateService();

    // ── Version resolution (pure function) ──────────────────────────────────

    @Test
    void pickHighestVersion_picksHighestDateLeEffective() {
        List<String> files = List.of(
                "ipec-quarterly-return-v_2024-01-01.xlsx",
                "ipec-quarterly-return-v_2024-06-01.xlsx",
                "ipec-quarterly-return-v_2025-03-01.xlsx"
        );
        Optional<String> pick = RegulatoryTemplateService.pickHighestVersion(
                "ipec", files, LocalDate.of(2024, 12, 31));
        assertThat(pick).contains("report-templates/ipec/ipec-quarterly-return-v_2024-06-01.xlsx");
    }

    @Test
    void pickHighestVersion_ignoresFutureVersions() {
        List<String> files = List.of(
                "cms-asr-v_2024-01-01.xlsx",
                "cms-asr-v_SYNTHETIC_2027-08-30.xlsx"
        );
        Optional<String> pick = RegulatoryTemplateService.pickHighestVersion(
                "cms", files, LocalDate.of(2026, 6, 30));
        assertThat(pick).contains("report-templates/cms/cms-asr-v_2024-01-01.xlsx");
    }

    @Test
    void pickHighestVersion_treatsSyntheticAlongsideReal_bySelectionOrder() {
        // Synthetic vs real is a marker only for Angular banner — selection uses
        // the date, not the marker (per REG17 comment).
        List<String> files = List.of(
                "cms-asr-v_2024-01-01.xlsx",
                "cms-asr-v_SYNTHETIC_2026-08-30.xlsx"
        );
        Optional<String> pick = RegulatoryTemplateService.pickHighestVersion(
                "cms", files, LocalDate.of(2027, 1, 1));
        assertThat(pick).contains("report-templates/cms/cms-asr-v_SYNTHETIC_2026-08-30.xlsx");
    }

    @Test
    void pickHighestVersion_emptyWhenNothingMatchesEffectiveDate() {
        List<String> files = List.of("ipec-quarterly-return-v_2030-01-01.xlsx");
        Optional<String> pick = RegulatoryTemplateService.pickHighestVersion(
                "ipec", files, LocalDate.of(2026, 1, 1));
        assertThat(pick).isEmpty();
    }

    @Test
    void pickHighestVersion_skipsMalformedFilenames() {
        List<String> files = List.of(
                "ipec-quarterly-return-v_bad-date.xlsx",
                "ipec-quarterly-return.xlsx",
                "ipec-quarterly-return-v_2024-06-01.xlsx"
        );
        Optional<String> pick = RegulatoryTemplateService.pickHighestVersion(
                "ipec", files, LocalDate.of(2026, 1, 1));
        assertThat(pick).contains("report-templates/ipec/ipec-quarterly-return-v_2024-06-01.xlsx");
    }

    @Test
    void isSynthetic_detectsSyntheticMarker() {
        assertThat(RegulatoryTemplateService.isSynthetic(
                "report-templates/cms/cms-asr-v_SYNTHETIC_2026-08-30.xlsx")).isTrue();
        assertThat(RegulatoryTemplateService.isSynthetic(
                "report-templates/ipec/ipec-quarterly-return-v_2024-06-01.xlsx")).isFalse();
        assertThat(RegulatoryTemplateService.isSynthetic(null)).isFalse();
        assertThat(RegulatoryTemplateService.isSynthetic("nonsense.xlsx")).isFalse();
    }

    // ── loadBundled ────────────────────────────────────────────────────────

    @Test
    void loadBundled_missingResource_throws() {
        // Nothing under report-templates/nonexistent-regulator/ in test classpath.
        assertThatThrownBy(() -> service.loadBundled("nonexistent-regulator", "nothing", LocalDate.of(2026, 1, 1)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No bundled regulator template found");
    }

    @Test
    void resolveBundledResource_nullArgs_returnsEmpty() {
        assertThat(service.resolveBundledResource(null, "x", LocalDate.now())).isEmpty();
        assertThat(service.resolveBundledResource("x", null, LocalDate.now())).isEmpty();
        assertThat(service.resolveBundledResource("x", "y", null)).isEmpty();
        assertThat(service.resolveBundledResource("", "y", LocalDate.now())).isEmpty();
    }

    // ── writeNamed ──────────────────────────────────────────────────────────

    @Test
    void writeNamed_writesSingleCellValueByRange() {
        try (Workbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("Sheet1");
            sheet.createRow(0).createCell(0);
            addNamedRange(wb, "SAMPLE_CELL", "Sheet1!$A$1");

            service.writeNamed(wb, "SAMPLE_CELL", new BigDecimal("1234.56"));

            Cell cell = wb.getSheet("Sheet1").getRow(0).getCell(0);
            assertThat(cell.getCellType()).isEqualTo(CellType.NUMERIC);
            assertThat(cell.getNumericCellValue()).isEqualTo(1234.56);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void writeNamed_writesText() {
        try (Workbook wb = new XSSFWorkbook()) {
            wb.createSheet("Sheet1").createRow(0).createCell(0);
            addNamedRange(wb, "TEXT_CELL", "Sheet1!$A$1");

            service.writeNamed(wb, "TEXT_CELL", "hello");

            assertThat(wb.getSheet("Sheet1").getRow(0).getCell(0).getStringCellValue()).isEqualTo("hello");
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void writeNamed_missingRange_throws() {
        try (Workbook wb = new XSSFWorkbook()) {
            assertThatThrownBy(() -> service.writeNamed(wb, "MISSING", "x"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Named range not found");
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void writeNamed_multiCellRange_throws() {
        try (Workbook wb = new XSSFWorkbook()) {
            wb.createSheet("Sheet1");
            addNamedRange(wb, "MULTI", "Sheet1!$A$1:$B$3");

            assertThatThrownBy(() -> service.writeNamed(wb, "MULTI", "x"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("multiple cells");
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void writeNamed_handlesSheetNameWithSpaces() {
        try (Workbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("Balance Sheet");
            sheet.createRow(2).createCell(3);
            addNamedRange(wb, "TAGGED", "'Balance Sheet'!$D$3");

            service.writeNamed(wb, "TAGGED", "42");

            assertThat(wb.getSheet("Balance Sheet").getRow(2).getCell(3).getStringCellValue()).isEqualTo("42");
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    // ── writeAnchored ──────────────────────────────────────────────────────

    @Test
    void writeAnchored_findsRowByLabel_caseInsensitive() {
        try (Workbook wb = new XSSFWorkbook()) {
            buildAnchoredSample(wb);

            service.writeAnchored(wb, new LabelAnchor("Data", "total assets", "Amount"),
                    new BigDecimal("999.99"));

            Cell target = wb.getSheet("Data").getRow(1).getCell(1);
            assertThat(target.getNumericCellValue()).isEqualTo(999.99);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void writeAnchored_missingSheet_throws() {
        try (Workbook wb = new XSSFWorkbook()) {
            wb.createSheet("Other");
            assertThatThrownBy(() -> service.writeAnchored(wb,
                    new LabelAnchor("Missing", "row", "col"), "x"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Sheet not found");
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void writeAnchored_missingRowLabel_throws() {
        try (Workbook wb = new XSSFWorkbook()) {
            buildAnchoredSample(wb);
            assertThatThrownBy(() -> service.writeAnchored(wb,
                    new LabelAnchor("Data", "Unknown", "Amount"), "x"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Row label not found");
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void writeAnchored_missingColumnHeader_throws() {
        try (Workbook wb = new XSSFWorkbook()) {
            buildAnchoredSample(wb);
            assertThatThrownBy(() -> service.writeAnchored(wb,
                    new LabelAnchor("Data", "Total Assets", "Nope"), "x"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Column header not found");
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    // ── fill ────────────────────────────────────────────────────────────────

    enum SampleField { REVENUE, EXPENSE, NOTES }

    @Test
    void fill_appliesEveryMappingWithMatchingValue() {
        try (Workbook wb = new XSSFWorkbook()) {
            wb.createSheet("Sheet1").createRow(0).createCell(0);
            wb.getSheet("Sheet1").getRow(0).createCell(1);
            addNamedRange(wb, "REV", "Sheet1!$A$1");
            addNamedRange(wb, "EXP", "Sheet1!$B$1");

            RegulatoryCellMap<SampleField> cellMap = new SampleCellMap(List.of(
                    new RegulatoryCellMap.NamedRange<>(SampleField.REVENUE, "REV"),
                    new RegulatoryCellMap.NamedRange<>(SampleField.EXPENSE, "EXP")
            ));

            service.fill(wb, cellMap, Map.of(
                    SampleField.REVENUE, new BigDecimal("100"),
                    SampleField.EXPENSE, new BigDecimal("40")
                    // NOTES intentionally absent — no mapping for it, no write
            ));

            assertThat(wb.getSheet("Sheet1").getRow(0).getCell(0).getNumericCellValue()).isEqualTo(100.0);
            assertThat(wb.getSheet("Sheet1").getRow(0).getCell(1).getNumericCellValue()).isEqualTo(40.0);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void fill_skipsMissingDataEntries() {
        try (Workbook wb = new XSSFWorkbook()) {
            wb.createSheet("Sheet1").createRow(0).createCell(0);
            addNamedRange(wb, "REV", "Sheet1!$A$1");

            RegulatoryCellMap<SampleField> cellMap = new SampleCellMap(List.of(
                    new RegulatoryCellMap.NamedRange<>(SampleField.REVENUE, "REV")
            ));

            // Empty data map — nothing written, no crash.
            service.fill(wb, cellMap, Map.of());

            Cell cell = wb.getSheet("Sheet1").getRow(0).getCell(0);
            // Untouched cell has default blank state.
            assertThat(cell.getCellType()).isEqualTo(CellType.BLANK);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    // ── toBytes ─────────────────────────────────────────────────────────────

    @Test
    void toBytes_producesReloadableWorkbook() {
        try (Workbook wb = new XSSFWorkbook()) {
            wb.createSheet("Data").createRow(0).createCell(0).setCellValue("hello");
            byte[] bytes = service.toBytes(wb);
            assertThat(bytes).isNotEmpty();

            try (Workbook reloaded = new XSSFWorkbook(new java.io.ByteArrayInputStream(bytes))) {
                assertThat(reloaded.getSheet("Data").getRow(0).getCell(0).getStringCellValue())
                        .isEqualTo("hello");
            }
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    /** Build a small sheet with a header row + one anchored data row. */
    private static void buildAnchoredSample(Workbook wb) {
        Sheet sheet = wb.createSheet("Data");
        Row header = sheet.createRow(0);
        header.createCell(0).setCellValue("Label");
        header.createCell(1).setCellValue("Amount");
        Row dataRow = sheet.createRow(1);
        dataRow.createCell(0).setCellValue("Total Assets");
        dataRow.createCell(1); // empty target cell
    }

    private static void addNamedRange(Workbook wb, String name, String refersTo) {
        Name n = wb.createName();
        n.setNameName(name);
        n.setRefersToFormula(refersTo);
    }

    private record SampleCellMap(List<Mapping<SampleField>> mappings) implements RegulatoryCellMap<SampleField> {
        @Override public Class<SampleField> keyClass() { return SampleField.class; }
    }
}
