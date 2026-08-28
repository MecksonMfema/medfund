package com.medfund.finance.actuarial.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medfund.finance.actuarial.entity.ActuarialReportJob;
import io.r2dbc.postgresql.codec.Json;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.io.ByteArrayInputStream;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Focused regression for the Phase-16 XLSX header lines. Every actuarial
 * export now carries the {@code LDF method} + {@code Rule applied} audit
 * pair so a downstream reviewer can trace back which tenant rule (if any)
 * picked the method that was ultimately used.
 */
class ActuarialXlsxServiceTest {

    private final ActuarialXlsxService service = new ActuarialXlsxService(new ObjectMapper());

    @Test
    void renders_ldfRuleApplied_headerOnTriangleAndSummarySheets() throws Exception {
        ActuarialReportJob job = new ActuarialReportJob();
        job.setJobId(UUID.randomUUID());
        job.setStatus("completed");
        job.setReportKey("IBNR_TRIANGLE");
        job.setCompletedAt(OffsetDateTime.now());
        job.setParamsJson(Json.of("""
                {"ldfMethod":"5yr","ldfRuleApplied":"HEALTH 5-year weighted",
                 "periodStart":"2026-01-01","periodEnd":"2026-06-30",
                 "insuranceLine":"HEALTH","reportingCurrency":"USD"}
                """));
        job.setResultJson(Json.of("""
                {"ldfs":[1.5],"cdf":[1.5],"ibnr_total":1234.56,"ultimate_total":5678.90}
                """));

        StepVerifier.create(service.render(job))
                .assertNext(bytes -> {
                    try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
                        assertThat(cellText(wb.getSheet("Triangle"), "Rule applied"))
                                .isEqualTo("HEALTH 5-year weighted");
                        assertThat(cellText(wb.getSheet("Summary"), "Rule applied"))
                                .isEqualTo("HEALTH 5-year weighted");
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                })
                .verifyComplete();
    }

    @Test
    void renders_ldfRuleApplied_dashWhenNoRuleFired() throws Exception {
        ActuarialReportJob job = new ActuarialReportJob();
        job.setJobId(UUID.randomUUID());
        job.setStatus("completed");
        job.setReportKey("IBNR_TRIANGLE");
        job.setCompletedAt(OffsetDateTime.now());
        job.setParamsJson(Json.of("""
                {"ldfMethod":"volume",
                 "periodStart":"2026-01-01","periodEnd":"2026-06-30",
                 "insuranceLine":"HEALTH","reportingCurrency":"USD"}
                """));
        job.setResultJson(Json.of("""
                {"ldfs":[1.5],"cdf":[1.5],"ibnr_total":1234.56,"ultimate_total":5678.90}
                """));

        StepVerifier.create(service.render(job))
                .assertNext(bytes -> {
                    try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
                        // Sentinel dash when no rule mutated the fact — matches the
                        // "no rule fired" outcome from ActuarialRulesEvaluator.
                        assertThat(cellText(wb.getSheet("Triangle"), "Rule applied")).isEqualTo("—");
                        assertThat(cellText(wb.getSheet("Summary"), "Rule applied")).isEqualTo("—");
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                })
                .verifyComplete();
    }

    /**
     * Find the value cell adjacent to the label. Rendered layout is
     * col 0 = label, col 1 = value.
     */
    private String cellText(Sheet sheet, String label) {
        for (var row : sheet) {
            var labelCell = row.getCell(0);
            if (labelCell != null && label.equals(labelCell.getStringCellValue())) {
                var valueCell = row.getCell(1);
                return valueCell == null ? null : valueCell.getStringCellValue();
            }
        }
        throw new AssertionError("Label '" + label + "' not found on sheet " + sheet.getSheetName());
    }
}
