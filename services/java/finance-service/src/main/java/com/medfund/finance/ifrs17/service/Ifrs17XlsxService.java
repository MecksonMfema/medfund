package com.medfund.finance.ifrs17.service;

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
import org.apache.poi.ss.util.WorkbookUtil;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Renders a completed IFRS 17 report job to an XLSX workbook per the I21
 * layout: a Summary sheet at position 0 + one sheet per portfolio with
 * LRC totals on top and LIC totals below (for LRC/LIC reconciliation),
 * or revenue / expenses / service-result blocks (for insurance revenue
 * & service result). Uses the aggregated envelope written by
 * {@link Ifrs17JobAggregator#buildEnvelope} — portfolios → cohorts →
 * currency → chunk-result.
 *
 * <p>Failed jobs collapse to a single-sheet "Report failed" workbook so
 * the export still succeeds with a downloadable error trail (matches the
 * {@code ActuarialXlsxService} convention).
 *
 * <p>Portfolio sheet names are truncated + de-duplicated per POI's 31-char
 * limit via {@link WorkbookUtil#createSafeSheetName}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class Ifrs17XlsxService {

    /** Envelope key emitted by {@link Ifrs17JobAggregator}. */
    static final String KEY_SUMMARY = "summary";
    static final String KEY_PORTFOLIOS = "portfolios";
    static final String KEY_COHORTS = "cohorts";
    static final String KEY_RESULT = "result";

    private final ObjectMapper objectMapper;

    public Mono<byte[]> render(ReportJob job) {
        return Mono.fromCallable(() -> renderSync(job));
    }

    private byte[] renderSync(ReportJob job) throws IOException {
        JsonNode params = readJsonOrNull(job.getParamsJson() != null
                ? job.getParamsJson().asString() : null);
        JsonNode envelope = readJsonOrNull(job.getResultJson() != null
                ? job.getResultJson().asString() : null);

        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            CellStyle title = titleStyle(wb);
            CellStyle sectionTitle = sectionTitleStyle(wb);
            CellStyle label = labelStyle(wb);
            CellStyle bold = boldStyle(wb);
            CellStyle money = moneyStyle(wb, false);
            CellStyle moneyBold = moneyStyle(wb, true);
            CellStyle th = tableHeaderStyle(wb);

            if ("failed".equals(job.getStatus()) || envelope == null) {
                writeFailedSheet(wb, job, title, label, bold);
            } else {
                writeSummarySheet(wb, job, params, envelope, title, label, bold, moneyBold);
                writePortfolioSheets(wb, job, envelope, sectionTitle, th, label, bold, money, moneyBold);
            }
            wb.write(out);
            return out.toByteArray();
        }
    }

    // ── Summary sheet ───────────────────────────────────────────────────

    private void writeSummarySheet(Workbook wb, ReportJob job, JsonNode params, JsonNode envelope,
                                    CellStyle title, CellStyle label, CellStyle bold,
                                    CellStyle moneyBold) {
        Sheet sheet = wb.createSheet("Summary");
        int r = 0;
        Row t = sheet.createRow(r++);
        cell(t, 0, humanReportName(job.getReportKey()) + " - summary", title);
        sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, 4));
        r++;

        String periodStart = textField(params, "periodStart", "-");
        String periodEnd = textField(params, "periodEnd", "-");
        String reportingCurrency = textField(params, "reportingCurrency", "-");

        r = writeLabelValue(sheet, r, label, bold, "Report", humanReportName(job.getReportKey()));
        r = writeLabelValue(sheet, r, label, bold, "Job ID", job.getJobId().toString());
        r = writeLabelValue(sheet, r, label, bold, "Status", job.getStatus());
        r = writeLabelValue(sheet, r, label, bold, "Period", periodStart + " - " + periodEnd);
        r = writeLabelValue(sheet, r, label, bold, "Reporting currency", reportingCurrency);
        r = writeLabelValue(sheet, r, label, bold, "Completed at",
                job.getCompletedAt() != null ? job.getCompletedAt().toString() : "-");
        r++;

        JsonNode summary = envelope.path(KEY_SUMMARY);
        if (summary != null && !summary.isMissingNode()) {
            Row header = sheet.createRow(r++);
            cell(header, 0, "Chunk roll-up", bold);
            r = writeLabelValue(sheet, r, label, bold, "Total chunks",
                    String.valueOf(summary.path("totalChunks").asInt()));
            r = writeLabelValue(sheet, r, label, bold, "Completed chunks",
                    String.valueOf(summary.path("completedChunks").asInt()));
            r = writeLabelValue(sheet, r, label, bold, "Failed chunks",
                    String.valueOf(summary.path("failedChunks").asInt()));
            r = writeLabelValue(sheet, r, label, bold, "Measurement models",
                    joinArrayNode(summary.path("measurementModelsSeen")));
            r = writeLabelValue(sheet, r, label, bold, "Currencies",
                    joinArrayNode(summary.path("currenciesSeen")));
            r++;
        }

        // Portfolio-count roll-up so the summary sheet doesn't require
        // opening every portfolio tab just to see "how many did we get".
        JsonNode portfolios = envelope.path(KEY_PORTFOLIOS);
        int portfolioCount = portfolios != null && portfolios.isObject()
                ? portfolios.size() : 0;
        r = writeLabelValue(sheet, r, label, bold, "Portfolios", String.valueOf(portfolioCount));

        sheet.setColumnWidth(0, 6000);
        sheet.setColumnWidth(1, 8000);
    }

    // ── Portfolio sheets ────────────────────────────────────────────────

    private void writePortfolioSheets(Workbook wb, ReportJob job, JsonNode envelope,
                                       CellStyle sectionTitle, CellStyle th,
                                       CellStyle label, CellStyle bold,
                                       CellStyle money, CellStyle moneyBold) {
        JsonNode portfolios = envelope.path(KEY_PORTFOLIOS);
        if (portfolios == null || !portfolios.isObject() || portfolios.isEmpty()) {
            Sheet empty = wb.createSheet("Portfolios");
            Row row = empty.createRow(0);
            cell(row, 0, "No portfolio results in aggregated envelope.", label);
            return;
        }

        boolean isRevenueReport = isInsuranceRevenueKey(job.getReportKey());
        Iterator<Map.Entry<String, JsonNode>> it = portfolios.fields();
        int idx = 1;
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> entry = it.next();
            String portfolioId = entry.getKey();
            JsonNode portfolioNode = entry.getValue();
            String sheetName = WorkbookUtil.createSafeSheetName(
                    "Portfolio " + idx + " - " + shortId(portfolioId));
            Sheet sheet = wb.createSheet(sheetName);
            writePortfolioSheet(sheet, portfolioId, portfolioNode, isRevenueReport,
                    sectionTitle, th, label, bold, money, moneyBold);
            idx++;
        }
    }

    private void writePortfolioSheet(Sheet sheet, String portfolioId, JsonNode portfolioNode,
                                      boolean isRevenueReport,
                                      CellStyle sectionTitle, CellStyle th,
                                      CellStyle label, CellStyle bold,
                                      CellStyle money, CellStyle moneyBold) {
        int r = 0;
        Row header = sheet.createRow(r++);
        cell(header, 0, "Portfolio " + portfolioId, sectionTitle);
        sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, 5));
        r++;

        List<CohortCurrencyRow> rows = flattenCohortRows(portfolioNode);
        if (rows.isEmpty()) {
            Row empty = sheet.createRow(r);
            cell(empty, 0, "No cohorts in this portfolio.", label);
            return;
        }

        if (isRevenueReport) {
            r = writeRevenueBlock(sheet, r, rows, th, bold, money, moneyBold);
        } else {
            r = writeLrcBlock(sheet, r, rows, th, bold, money, moneyBold);
            r++;
            r = writeLicBlock(sheet, r, rows, th, bold, money, moneyBold);
        }

        // Column widths — cohort/currency identifiers deserve room; numeric
        // columns stay narrower.
        sheet.setColumnWidth(0, 8000);
        sheet.setColumnWidth(1, 3000);
        for (int c = 2; c < 8; c++) {
            sheet.setColumnWidth(c, 4500);
        }
    }

    private int writeLrcBlock(Sheet sheet, int rowIdx, List<CohortCurrencyRow> rows,
                               CellStyle th, CellStyle bold, CellStyle money, CellStyle moneyBold) {
        Row title = sheet.createRow(rowIdx++);
        cell(title, 0, "Liability for Remaining Coverage (LRC)", bold);
        rowIdx = writeMovementTable(sheet, rowIdx, rows, "lrc", th, money, moneyBold);
        return rowIdx;
    }

    private int writeLicBlock(Sheet sheet, int rowIdx, List<CohortCurrencyRow> rows,
                               CellStyle th, CellStyle bold, CellStyle money, CellStyle moneyBold) {
        Row title = sheet.createRow(rowIdx++);
        cell(title, 0, "Liability for Incurred Claims (LIC)", bold);
        rowIdx = writeMovementTable(sheet, rowIdx, rows, "lic", th, money, moneyBold);
        return rowIdx;
    }

    private int writeRevenueBlock(Sheet sheet, int rowIdx, List<CohortCurrencyRow> rows,
                                    CellStyle th, CellStyle bold, CellStyle money, CellStyle moneyBold) {
        Row title = sheet.createRow(rowIdx++);
        cell(title, 0, "Insurance revenue & service result", bold);
        Row header = sheet.createRow(rowIdx++);
        cell(header, 0, "Cohort", th);
        cell(header, 1, "Currency", th);
        cell(header, 2, "Insurance revenue", th);
        cell(header, 3, "Insurance service expenses", th);
        cell(header, 4, "Insurance service result", th);
        cell(header, 5, "Insurance finance expense", th);

        double totalRevenue = 0;
        double totalExpenses = 0;
        double totalServiceResult = 0;
        double totalFinanceExpense = 0;
        for (CohortCurrencyRow row : rows) {
            JsonNode result = row.result();
            double revenue = numericField(result, "insuranceRevenue");
            double expenses = numericField(result, "insuranceServiceExpenses");
            double serviceResult = result.hasNonNull("insuranceServiceResult")
                    ? result.path("insuranceServiceResult").asDouble()
                    : revenue - expenses;
            double financeExpense = numericField(result, "insuranceFinanceExpense");

            Row r = sheet.createRow(rowIdx++);
            cell(r, 0, shortId(row.cohortId()), null);
            cell(r, 1, row.currency(), null);
            moneyCell(r, 2, revenue, money);
            moneyCell(r, 3, expenses, money);
            moneyCell(r, 4, serviceResult, money);
            moneyCell(r, 5, financeExpense, money);

            totalRevenue += revenue;
            totalExpenses += expenses;
            totalServiceResult += serviceResult;
            totalFinanceExpense += financeExpense;
        }

        Row totals = sheet.createRow(rowIdx++);
        cell(totals, 0, "Total", bold);
        moneyCell(totals, 2, totalRevenue, moneyBold);
        moneyCell(totals, 3, totalExpenses, moneyBold);
        moneyCell(totals, 4, totalServiceResult, moneyBold);
        moneyCell(totals, 5, totalFinanceExpense, moneyBold);
        return rowIdx;
    }

    private int writeMovementTable(Sheet sheet, int rowIdx, List<CohortCurrencyRow> rows,
                                     String movementKey, CellStyle th,
                                     CellStyle money, CellStyle moneyBold) {
        Row header = sheet.createRow(rowIdx++);
        cell(header, 0, "Cohort", th);
        cell(header, 1, "Currency", th);
        cell(header, 2, "Opening", th);
        cell(header, 3, "New business", th);
        cell(header, 4, "Cash flows", th);
        cell(header, 5, "Revenue / claims", th);
        cell(header, 6, "Finance expense", th);
        cell(header, 7, "Closing", th);

        double totalOpening = 0;
        double totalClosing = 0;
        for (CohortCurrencyRow row : rows) {
            JsonNode movement = row.result().path(movementKey);
            if (movement.isMissingNode() || movement.isNull()) {
                movement = row.result();
            }
            double opening = numericField(movement, "opening");
            double newBusiness = numericField(movement, "newBusiness");
            double cashFlows = numericField(movement, movementKey.equals("lrc") ? "cashInflows" : "cashOutflows");
            double revenueOrClaims = numericField(movement, movementKey.equals("lrc") ? "insuranceRevenue" : "claimsIncurred");
            double financeExpense = numericField(movement, "financeExpense");
            double closing = movement.hasNonNull("closing")
                    ? movement.path("closing").asDouble()
                    : opening + newBusiness + cashFlows - revenueOrClaims + financeExpense;

            Row r = sheet.createRow(rowIdx++);
            cell(r, 0, shortId(row.cohortId()), null);
            cell(r, 1, row.currency(), null);
            moneyCell(r, 2, opening, money);
            moneyCell(r, 3, newBusiness, money);
            moneyCell(r, 4, cashFlows, money);
            moneyCell(r, 5, revenueOrClaims, money);
            moneyCell(r, 6, financeExpense, money);
            moneyCell(r, 7, closing, money);

            totalOpening += opening;
            totalClosing += closing;
        }

        Row totals = sheet.createRow(rowIdx++);
        cell(totals, 0, "Total", moneyBold);
        moneyCell(totals, 2, totalOpening, moneyBold);
        moneyCell(totals, 7, totalClosing, moneyBold);
        return rowIdx;
    }

    // ── Envelope helpers ────────────────────────────────────────────────

    /** Flatten portfolio → cohorts → currency → leaf into a plain list of
     *  (cohortId, currency, resultNode) rows so the writer can iterate in
     *  reading order without repeatedly navigating the tree. */
    private List<CohortCurrencyRow> flattenCohortRows(JsonNode portfolioNode) {
        List<CohortCurrencyRow> out = new ArrayList<>();
        JsonNode cohorts = portfolioNode.path(KEY_COHORTS);
        if (cohorts == null || !cohorts.isObject()) return out;
        Iterator<Map.Entry<String, JsonNode>> cohortIt = cohorts.fields();
        while (cohortIt.hasNext()) {
            Map.Entry<String, JsonNode> ce = cohortIt.next();
            String cohortId = ce.getKey();
            JsonNode cohortNode = ce.getValue();
            if (!cohortNode.isObject()) continue;
            Iterator<Map.Entry<String, JsonNode>> curIt = cohortNode.fields();
            while (curIt.hasNext()) {
                Map.Entry<String, JsonNode> cur = curIt.next();
                String currency = cur.getKey();
                JsonNode leaf = cur.getValue();
                if (!leaf.isObject()) continue;
                JsonNode result = leaf.path(KEY_RESULT);
                if (result.isMissingNode() || result.isNull()) result = leaf;
                out.add(new CohortCurrencyRow(cohortId, currency, result));
            }
        }
        return out;
    }

    private record CohortCurrencyRow(String cohortId, String currency, JsonNode result) {}

    // ── Failure path ────────────────────────────────────────────────────

    private void writeFailedSheet(Workbook wb, ReportJob job,
                                    CellStyle title, CellStyle label, CellStyle bold) {
        Sheet sheet = wb.createSheet("Report failed");
        int r = 0;
        Row t = sheet.createRow(r++);
        cell(t, 0, "IFRS 17 report failed", title);
        sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, 3));
        r++;
        r = writeLabelValue(sheet, r, label, bold, "Job ID", job.getJobId().toString());
        r = writeLabelValue(sheet, r, label, bold, "Report key", job.getReportKey());
        r = writeLabelValue(sheet, r, label, bold, "Status", job.getStatus());
        writeLabelValue(sheet, r, label, bold, "Error",
                job.getErrorMessage() != null ? job.getErrorMessage() : "-");
    }

    // ── JSON + string helpers ───────────────────────────────────────────

    private JsonNode readJsonOrNull(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return objectMapper.readTree(value);
        } catch (Exception e) {
            log.warn("[ifrs17-xlsx] failed to parse json: {}", e.getMessage());
            return null;
        }
    }

    private static String textField(JsonNode node, String key, String fallback) {
        if (node == null) return fallback;
        JsonNode v = node.get(key);
        return v != null && !v.isNull() ? v.asText(fallback) : fallback;
    }

    private static double numericField(JsonNode node, String key) {
        if (node == null) return 0d;
        JsonNode v = node.get(key);
        return v != null && v.isNumber() ? v.asDouble() : 0d;
    }

    private static String joinArrayNode(JsonNode node) {
        if (node == null || !node.isArray() || node.isEmpty()) return "-";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < node.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(node.get(i).asText());
        }
        return sb.toString();
    }

    private static String humanReportName(String reportKey) {
        if (reportKey == null) return "IFRS 17";
        return switch (reportKey) {
            case "IFRS17_LRC_LIC_RECONCILIATION" -> "IFRS 17 - LRC / LIC reconciliation";
            case "IFRS17_INSURANCE_REVENUE_SERVICE_RESULT" ->
                    "IFRS 17 - insurance revenue & service result";
            default -> reportKey;
        };
    }

    private static boolean isInsuranceRevenueKey(String reportKey) {
        return "IFRS17_INSURANCE_REVENUE_SERVICE_RESULT".equals(reportKey);
    }

    /** UUIDs are 36 chars — we show only the first 8 in cells to keep sheets
     *  readable, mirroring the actuarial XLSX convention. */
    private static String shortId(String uuid) {
        if (uuid == null) return "-";
        return uuid.length() > 8 ? uuid.substring(0, 8) : uuid;
    }

    // ── Cell + style helpers (mirror ActuarialXlsxService) ──────────────

    private static CellStyle titleStyle(Workbook wb) {
        CellStyle s = wb.createCellStyle();
        Font f = wb.createFont();
        f.setBold(true);
        f.setFontHeightInPoints((short) 14);
        s.setFont(f);
        return s;
    }

    private static CellStyle sectionTitleStyle(Workbook wb) {
        CellStyle s = wb.createCellStyle();
        Font f = wb.createFont();
        f.setBold(true);
        f.setFontHeightInPoints((short) 12);
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

    private static void cell(Row row, int col, String value, CellStyle style) {
        var c = row.createCell(col);
        c.setCellValue(value);
        if (style != null) c.setCellStyle(style);
    }

    private static void moneyCell(Row row, int col, double value, CellStyle style) {
        var c = row.createCell(col);
        c.setCellValue(value);
        if (style != null) c.setCellStyle(style);
    }

    private static int writeLabelValue(Sheet sheet, int rowIdx, CellStyle labelStyle,
                                         CellStyle valueStyle, String label, String value) {
        Row row = sheet.createRow(rowIdx);
        cell(row, 0, label, labelStyle);
        cell(row, 1, value, valueStyle);
        return rowIdx + 1;
    }
}
