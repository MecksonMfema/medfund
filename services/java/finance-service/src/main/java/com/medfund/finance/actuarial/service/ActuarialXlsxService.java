package com.medfund.finance.actuarial.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medfund.finance.report.entity.ReportJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * Renders a completed actuarial job's result payload to a multi-sheet XLSX.
 * Sheets:
 * <ol>
 *   <li><b>Triangle</b> — the shaped input matrix (accident × development).</li>
 *   <li><b>Development factors</b> — LDFs + CDFs by development period.</li>
 *   <li><b>Summary</b> — IBNR + Ultimate totals, Mack standard error,
 *       method + rule applied per Phase 16 XLSX-header convention.</li>
 * </ol>
 *
 * <p>Failed jobs land as a single-sheet "Report failed" workbook so the
 * export still succeeds with the error trail (the frontend can surface it
 * as a downloaded file rather than a 500).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ActuarialXlsxService {

    private final ObjectMapper objectMapper;

    public Mono<byte[]> render(ReportJob job) {
        return Mono.fromCallable(() -> renderSync(job));
    }

    private byte[] renderSync(ReportJob job) throws IOException {
        JsonNode params = readJsonOrNull(job.getParamsJson() != null ? job.getParamsJson().asString() : null);
        JsonNode result = readJsonOrNull(job.getResultJson() != null ? job.getResultJson().asString() : null);

        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            CellStyle title = titleStyle(wb);
            CellStyle label = labelStyle(wb);
            CellStyle bold = boldStyle(wb);
            CellStyle money = moneyStyle(wb, false);
            CellStyle moneyBold = moneyStyle(wb, true);
            CellStyle th = tableHeaderStyle(wb);
            CellStyle decimal = decimalStyle(wb);

            if ("failed".equals(job.getStatus()) || result == null) {
                writeFailedSheet(wb, job, title, label, bold);
            } else {
                writeTriangleSheet(wb, job, params, title, label, bold, th, money);
                writeDevFactorsSheet(wb, result, title, th, decimal);
                writeSummarySheet(wb, job, params, result, title, label, bold, moneyBold);
            }
            wb.write(out);
            return out.toByteArray();
        }
    }

    private void writeTriangleSheet(Workbook wb, ReportJob job, JsonNode params,
                                    CellStyle title, CellStyle label, CellStyle bold,
                                    CellStyle th, CellStyle money) {
        Sheet sheet = wb.createSheet("Triangle");
        int r = 0;
        Row t = sheet.createRow(r++);
        cell(t, 0, job.getReportKey() + " — triangle", title);

        String ldfMethod = params != null && params.hasNonNull("ldfMethod")
                ? params.get("ldfMethod").asText() : "volume";
        String periodStart = params != null && params.hasNonNull("periodStart")
                ? params.get("periodStart").asText() : "—";
        String periodEnd = params != null && params.hasNonNull("periodEnd")
                ? params.get("periodEnd").asText() : "—";
        String line = params != null && params.hasNonNull("insuranceLine")
                ? params.get("insuranceLine").asText() : "ALL";
        String currency = params != null && params.hasNonNull("reportingCurrency")
                ? params.get("reportingCurrency").asText() : "USD";

        r = writeLabelValue(sheet, r, label, bold, "Period", periodStart + " — " + periodEnd);
        r = writeLabelValue(sheet, r, label, bold, "Insurance line", line);
        r = writeLabelValue(sheet, r, label, bold, "Reporting currency", currency);
        r = writeLabelValue(sheet, r, label, bold, "LDF method", ldfMethod);
        String ldfRuleApplied = params != null && params.hasNonNull("ldfRuleApplied")
                ? params.get("ldfRuleApplied").asText() : "—";
        r = writeLabelValue(sheet, r, label, bold, "Rule applied", ldfRuleApplied);
        r = writeLabelValue(sheet, r, label, bold, "Job ID", job.getJobId().toString());
        r++;

        JsonNode triangle = params != null ? params.path("triangle") : null;
        JsonNode accidentPeriods = triangle != null ? triangle.path("accident_periods") : null;
        JsonNode developmentPeriods = triangle != null ? triangle.path("development_periods") : null;
        JsonNode cells = triangle != null ? triangle.path("cells") : null;
        if (accidentPeriods == null || !accidentPeriods.isArray() || accidentPeriods.isEmpty()) {
            Row empty = sheet.createRow(r);
            cell(empty, 0, "Triangle input not preserved in params_json — see result sheet for LDFs.", label);
            return;
        }

        Row header = sheet.createRow(r++);
        cell(header, 0, "Accident period", th);
        for (int c = 0; c < developmentPeriods.size(); c++) {
            cell(header, c + 1, developmentPeriods.get(c).asText(), th);
        }
        for (int i = 0; i < accidentPeriods.size(); i++) {
            Row row = sheet.createRow(r++);
            cell(row, 0, accidentPeriods.get(i).asText(), bold);
            JsonNode rowCells = cells.get(i);
            if (rowCells == null) continue;
            for (int c = 0; c < rowCells.size(); c++) {
                JsonNode value = rowCells.get(c);
                if (value == null || value.isNull()) continue;
                var poiCell = row.createCell(c + 1);
                poiCell.setCellValue(value.asDouble());
                poiCell.setCellStyle(money);
            }
        }
        sheet.setColumnWidth(0, 4000);
    }

    private void writeDevFactorsSheet(Workbook wb, JsonNode result,
                                      CellStyle title, CellStyle th, CellStyle decimal) {
        Sheet sheet = wb.createSheet("Development factors");
        int r = 0;
        Row t = sheet.createRow(r++);
        cell(t, 0, "Development factors (LDF + CDF)", title);
        r++;
        Row header = sheet.createRow(r++);
        cell(header, 0, "Development period", th);
        cell(header, 1, "LDF", th);
        cell(header, 2, "CDF", th);
        JsonNode ldfs = result.path("ldfs");
        JsonNode cdfs = result.path("cdf");
        int rows = ldfs.isArray() ? ldfs.size() : 0;
        for (int i = 0; i < rows; i++) {
            Row row = sheet.createRow(r++);
            row.createCell(0).setCellValue(i + 1);
            if (i < ldfs.size()) {
                var c = row.createCell(1);
                c.setCellValue(ldfs.get(i).asDouble());
                c.setCellStyle(decimal);
            }
            if (cdfs.isArray() && i < cdfs.size()) {
                var c = row.createCell(2);
                c.setCellValue(cdfs.get(i).asDouble());
                c.setCellStyle(decimal);
            }
        }
    }

    private void writeSummarySheet(Workbook wb, ReportJob job, JsonNode params, JsonNode result,
                                   CellStyle title, CellStyle label, CellStyle bold, CellStyle moneyBold) {
        Sheet sheet = wb.createSheet("Summary");
        int r = 0;
        Row t = sheet.createRow(r++);
        cell(t, 0, "Summary", title);
        r++;
        r = writeLabelValue(sheet, r, label, bold, "Status", job.getStatus());
        r = writeLabelValue(sheet, r, label, bold, "Completed at",
                job.getCompletedAt() != null ? job.getCompletedAt().toString() : "—");
        String ldfMethod = params != null && params.hasNonNull("ldfMethod")
                ? params.get("ldfMethod").asText() : "volume";
        r = writeLabelValue(sheet, r, label, bold, "LDF method", ldfMethod);
        String ldfRuleApplied = params != null && params.hasNonNull("ldfRuleApplied")
                ? params.get("ldfRuleApplied").asText() : "—";
        r = writeLabelValue(sheet, r, label, bold, "Rule applied", ldfRuleApplied);
        if (params != null && params.hasNonNull("shape_warnings")) {
            r = writeLabelValue(sheet, r, label, bold, "Warnings", params.get("shape_warnings").toString());
        }
        r++;

        writeMoneyRow(sheet, r++, label, moneyBold, "IBNR total", result.path("ibnr_total").asDouble());
        writeMoneyRow(sheet, r++, label, moneyBold, "Ultimate total", result.path("ultimate_total").asDouble());
        if (result.hasNonNull("mack_standard_error")) {
            writeMoneyRow(sheet, r, label, moneyBold, "Mack standard error",
                    result.get("mack_standard_error").asDouble());
        }
        sheet.setColumnWidth(0, 6000);
        sheet.setColumnWidth(1, 6000);
    }

    private void writeFailedSheet(Workbook wb, ReportJob job,
                                  CellStyle title, CellStyle label, CellStyle bold) {
        Sheet sheet = wb.createSheet("Report failed");
        int r = 0;
        Row t = sheet.createRow(r++);
        cell(t, 0, "Report failed", title);
        sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, 3));
        r++;
        r = writeLabelValue(sheet, r, label, bold, "Job ID", job.getJobId().toString());
        r = writeLabelValue(sheet, r, label, bold, "Report key", job.getReportKey());
        r = writeLabelValue(sheet, r, label, bold, "Status", job.getStatus());
        writeLabelValue(sheet, r, label, bold, "Error",
                job.getErrorMessage() != null ? job.getErrorMessage() : "—");
    }

    private JsonNode readJsonOrNull(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return objectMapper.readTree(value);
        } catch (Exception e) {
            log.warn("[actuarial-xlsx] failed to parse json: {}", e.getMessage());
            return null;
        }
    }

    // ── Style + cell helpers (mirror CreditorsExcelService) ─────────────────

    private static CellStyle titleStyle(Workbook wb) {
        CellStyle s = wb.createCellStyle();
        Font f = wb.createFont();
        f.setBold(true);
        f.setFontHeightInPoints((short) 14);
        s.setFont(f);
        return s;
    }

    private static CellStyle labelStyle(Workbook wb) {
        CellStyle s = wb.createCellStyle();
        Font f = wb.createFont();
        f.setColor(IndexedColors.GREY_50_PERCENT.getIndex());
        s.setFont(f);
        return s;
    }

    private static CellStyle boldStyle(Workbook wb) {
        CellStyle s = wb.createCellStyle();
        Font f = wb.createFont();
        f.setBold(true);
        s.setFont(f);
        return s;
    }

    private static CellStyle tableHeaderStyle(Workbook wb) {
        CellStyle s = wb.createCellStyle();
        Font f = wb.createFont();
        f.setBold(true);
        s.setFont(f);
        s.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
        s.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        s.setBorderBottom(BorderStyle.THIN);
        s.setAlignment(HorizontalAlignment.CENTER);
        return s;
    }

    private static CellStyle moneyStyle(Workbook wb, boolean boldFont) {
        CellStyle s = wb.createCellStyle();
        s.setDataFormat(wb.createDataFormat().getFormat("#,##0.00"));
        if (boldFont) {
            Font f = wb.createFont();
            f.setBold(true);
            s.setFont(f);
        }
        return s;
    }

    private static CellStyle decimalStyle(Workbook wb) {
        CellStyle s = wb.createCellStyle();
        s.setDataFormat(wb.createDataFormat().getFormat("0.0000"));
        return s;
    }

    private static void cell(Row row, int col, String value, CellStyle style) {
        var c = row.createCell(col);
        c.setCellValue(value);
        if (style != null) c.setCellStyle(style);
    }

    private static int writeLabelValue(Sheet sheet, int rowIdx, CellStyle labelStyle, CellStyle valueStyle,
                                       String label, String value) {
        Row row = sheet.createRow(rowIdx);
        cell(row, 0, label, labelStyle);
        cell(row, 1, value, valueStyle);
        return rowIdx + 1;
    }

    private static void writeMoneyRow(Sheet sheet, int rowIdx, CellStyle labelStyle, CellStyle moneyStyle,
                                      String label, double value) {
        Row row = sheet.createRow(rowIdx);
        cell(row, 0, label, labelStyle);
        var c = row.createCell(1);
        c.setCellValue(value);
        c.setCellStyle(moneyStyle);
    }
}
