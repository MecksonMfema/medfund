package com.medfund.finance.service;

import com.medfund.finance.dto.CreditorFilterParams;
import com.medfund.finance.dto.CreditorRow;
import com.medfund.finance.repository.CreditorQueryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
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
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * XLSX export for the unified finance-side Creditors list. Same layout
 * shape as the contributions-side {@code DebtorsExcelService} so an
 * operator working across both surfaces sees workbooks they can visually
 * compare row-for-row.
 *
 * <p>Two extra columns vs the contributions-side sheet — providers and
 * members both carry the full four-column money breakdown
 * (claimed / approved / paid / outstanding), so the sheet is wider.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CreditorsExcelService {

    /** Hard ceiling on the number of rows exported in one call. */
    private static final int MAX_ROWS = 10_000;

    private final CreditorQueryRepository queryRepository;

    public Mono<byte[]> generate(String subjectType, String currencyCode, String q) {
        var params = new CreditorFilterParams(
                subjectType, currencyCode, q,
                "outstandingBalance", "desc",
                0, MAX_ROWS);
        return queryRepository.search(params, MAX_ROWS, 0)
                .collectList()
                .map(rows -> render(rows, subjectType, currencyCode, q));
    }

    private byte[] render(List<CreditorRow> rows, String subjectType, String currencyCode, String q) {
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("Creditors");

            CellStyle title    = titleStyle(wb);
            CellStyle label    = labelStyle(wb);
            CellStyle bold     = boldStyle(wb);
            CellStyle money    = moneyStyle(wb, false);
            CellStyle moneyBold = moneyStyle(wb, true);
            CellStyle date     = dateStyle(wb);
            CellStyle thHeader = tableHeaderStyle(wb);

            int r = 0;
            Row titleRow = sheet.createRow(r++);
            cell(titleRow, 0, "Creditors", title);
            sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, 9));
            r++;

            r = writeLabelValue(sheet, r, label, bold, "Subject type",
                    subjectType != null && !subjectType.isBlank()
                            ? humanizeSubjectType(subjectType)
                            : "All (providers + members)");
            r = writeLabelValue(sheet, r, label, bold, "Currency",
                    currencyCode != null && !currencyCode.isBlank() ? currencyCode : "All");
            r = writeLabelValue(sheet, r, label, bold, "Search",
                    q != null && !q.isBlank() ? q : "—");
            r = writeLabelValue(sheet, r, label, bold, "Exported at",
                    LocalDate.now().toString());
            r = writeLabelValue(sheet, r, label, bold, "Rows",
                    String.valueOf(rows.size()));
            r++;

            int tableHeaderRow = r;
            Row head = sheet.createRow(r++);
            cell(head, 0, "Type",         thHeader);
            cell(head, 1, "Code",         thHeader);
            cell(head, 2, "Name",         thHeader);
            cell(head, 3, "Email",        thHeader);
            cell(head, 4, "Currency",     thHeader);
            cell(head, 5, "Claimed",      thHeader);
            cell(head, 6, "Approved",     thHeader);
            cell(head, 7, "Paid",         thHeader);
            cell(head, 8, "Outstanding",  thHeader);
            cell(head, 9, "Last activity", thHeader);

            for (CreditorRow row : rows) {
                Row xr = sheet.createRow(r++);
                cell(xr, 0, humanizeSubjectType(row.subjectType()), null);
                cell(xr, 1, row.subjectCode()  != null ? row.subjectCode()  : "", null);
                cell(xr, 2, row.subjectName()  != null ? row.subjectName()  : "", null);
                cell(xr, 3, row.subjectEmail() != null ? row.subjectEmail() : "", null);
                cell(xr, 4, row.currencyCode() != null ? row.currencyCode() : "", null);
                writeMoney(xr, 5, row.totalClaimed(),       money);
                writeMoney(xr, 6, row.totalApproved(),      money);
                writeMoney(xr, 7, row.totalPaid(),          money);
                writeMoney(xr, 8, row.outstandingBalance(), moneyBold);
                writeDate(xr, 9, row.lastActivityAt(),      date);
            }

            sheet.createFreezePane(0, tableHeaderRow + 1);
            for (int i = 0; i <= 9; i++) sheet.autoSizeColumn(i);

            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("Failed to build creditors workbook", e);
        }
    }

    // ── Helpers (mirror DebtorsExcelService's) ──────────────────────────

    private static String humanizeSubjectType(String v) {
        if (v == null) return "—";
        return switch (v.toUpperCase()) {
            case "PROVIDER" -> "Provider";
            case "MEMBER"   -> "Member";
            case "BOTH"     -> "All (providers + members)";
            default -> v;
        };
    }

    private int writeLabelValue(Sheet sheet, int r, CellStyle labelStyle, CellStyle valueStyle,
                                  String label, String value) {
        Row row = sheet.createRow(r);
        cell(row, 0, label, labelStyle);
        cell(row, 1, value, valueStyle);
        return r + 1;
    }

    private void writeMoney(Row row, int col, BigDecimal amount, CellStyle style) {
        if (amount == null) { cell(row, col, "", style); return; }
        Cell c = row.createCell(col);
        c.setCellValue(amount.doubleValue());
        if (style != null) c.setCellStyle(style);
    }

    private void writeDate(Row row, int col, java.time.Instant when, CellStyle style) {
        if (when == null) { cell(row, col, "", null); return; }
        Cell c = row.createCell(col);
        c.setCellValue(java.util.Date.from(when));
        if (style != null) c.setCellStyle(style);
    }

    private void cell(Row row, int col, String value, CellStyle style) {
        Cell c = row.createCell(col);
        c.setCellValue(value);
        if (style != null) c.setCellStyle(style);
    }

    private CellStyle titleStyle(Workbook wb) {
        CellStyle s = wb.createCellStyle();
        Font f = wb.createFont();
        f.setBold(true);
        f.setFontHeightInPoints((short) 16);
        s.setFont(f);
        return s;
    }

    private CellStyle labelStyle(Workbook wb) {
        CellStyle s = wb.createCellStyle();
        Font f = wb.createFont();
        f.setColor(IndexedColors.GREY_50_PERCENT.getIndex());
        s.setFont(f);
        return s;
    }

    private CellStyle boldStyle(Workbook wb) {
        CellStyle s = wb.createCellStyle();
        Font f = wb.createFont();
        f.setBold(true);
        s.setFont(f);
        return s;
    }

    private CellStyle moneyStyle(Workbook wb, boolean bold) {
        CellStyle s = wb.createCellStyle();
        s.setDataFormat(wb.createDataFormat().getFormat("#,##0.00"));
        s.setAlignment(HorizontalAlignment.RIGHT);
        if (bold) {
            Font f = wb.createFont();
            f.setBold(true);
            s.setFont(f);
        }
        return s;
    }

    private CellStyle dateStyle(Workbook wb) {
        CellStyle s = wb.createCellStyle();
        s.setDataFormat(wb.createDataFormat().getFormat("yyyy-mm-dd"));
        return s;
    }

    private CellStyle tableHeaderStyle(Workbook wb) {
        CellStyle s = wb.createCellStyle();
        Font f = wb.createFont();
        f.setBold(true);
        f.setColor(IndexedColors.WHITE.getIndex());
        s.setFont(f);
        s.setFillForegroundColor(IndexedColors.SEA_GREEN.getIndex());
        s.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        s.setBorderBottom(BorderStyle.THIN);
        return s;
    }
}
