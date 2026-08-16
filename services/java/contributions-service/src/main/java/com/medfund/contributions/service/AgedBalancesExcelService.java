package com.medfund.contributions.service;

import com.medfund.contributions.dto.BadDebtRow;
import com.medfund.contributions.dto.PageResponse;
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
import java.time.Instant;
import java.time.LocalDate;
import java.util.Date;

/**
 * Excel export for the aged-balances view (Phase 8, D8-8 — the aged
 * page's route key is {@code AGED_BALANCES}). Same layout as
 * {@link BadDebtsExcelService} with the aging classification added, so
 * an operator comparing the debtors / bad-debts / aged tabs sees a
 * consistent row shape with the collections-lifecycle answer added.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgedBalancesExcelService {

    /** Hard ceiling on the number of rows exported in one call. */
    private static final int MAX_ROWS = 10_000;

    private final BalanceService balanceService;

    public Mono<byte[]> generate(String currency, Integer minAgeDays, String q) {
        return balanceService.listAged(currency, minAgeDays, q, 0, MAX_ROWS)
                .map(page -> render(page, currency, minAgeDays, q));
    }

    private byte[] render(PageResponse<BadDebtRow> page, String currency,
                           Integer minAgeDays, String q) {
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("Aged balances");

            CellStyle title      = titleStyle(wb);
            CellStyle label      = labelStyle(wb);
            CellStyle bold       = boldStyle(wb);
            CellStyle money      = moneyStyle(wb, false);
            CellStyle moneyBold  = moneyStyle(wb, true);
            CellStyle date       = dateStyle(wb);
            CellStyle number     = numberStyle(wb);
            CellStyle thHeader   = tableHeaderStyle(wb);

            int r = 0;
            Row titleRow = sheet.createRow(r++);
            cell(titleRow, 0, "Aged balances", title);
            sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, 7));
            r++;

            r = writeLabelValue(sheet, r, label, bold, "Currency", currency);
            r = writeLabelValue(sheet, r, label, bold, "Min age (days)",
                    minAgeDays != null ? String.valueOf(minAgeDays) : "Dunning suspension threshold");
            r = writeLabelValue(sheet, r, label, bold, "Search",
                    q != null && !q.isBlank() ? q : "—");
            r = writeLabelValue(sheet, r, label, bold, "Exported at",
                    LocalDate.now().toString());
            r = writeLabelValue(sheet, r, label, bold, "Rows",
                    String.valueOf(page.total()));
            r++;

            int tableHeaderRow = r;
            Row head = sheet.createRow(r++);
            cell(head, 0, "Type",        thHeader);
            cell(head, 1, "Name",        thHeader);
            cell(head, 2, "Code",        thHeader);
            cell(head, 3, "Email",       thHeader);
            cell(head, 4, "Aging",       thHeader);
            cell(head, 5, "Outstanding", thHeader);
            cell(head, 6, "Last payment", thHeader);
            cell(head, 7, "Days since",  thHeader);

            for (BadDebtRow row : page.content()) {
                Row xr = sheet.createRow(r++);
                cell(xr, 0, humanizeSubjectType(row.subjectType()), null);
                cell(xr, 1, row.subjectName() != null ? row.subjectName() : "", null);
                cell(xr, 2, row.subjectCode() != null ? row.subjectCode() : "", null);
                cell(xr, 3, row.subjectEmail() != null ? row.subjectEmail() : "", null);
                cell(xr, 4, row.agingStatus() != null ? row.agingStatus() : "", null);
                writeMoney(xr, 5, row.balance(), moneyBold);
                writeDate(xr, 6, row.lastPaymentAt(), date);
                writeNumber(xr, 7, row.daysSinceLastActivity(), number);
            }

            sheet.createFreezePane(0, tableHeaderRow + 1);
            for (int i = 0; i <= 7; i++) sheet.autoSizeColumn(i);

            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("Failed to build aged-balances workbook", e);
        }
    }

    private static String humanizeSubjectType(String v) {
        if (v == null) return "—";
        return switch (v.toUpperCase()) {
            case "MEMBER" -> "Individual";
            case "GROUP"  -> "Group";
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

    private void writeDate(Row row, int col, Instant when, CellStyle style) {
        if (when == null) { cell(row, col, "", null); return; }
        Cell c = row.createCell(col);
        c.setCellValue(Date.from(when));
        if (style != null) c.setCellStyle(style);
    }

    private void writeNumber(Row row, int col, Long value, CellStyle style) {
        if (value == null) { cell(row, col, "", null); return; }
        Cell c = row.createCell(col);
        c.setCellValue(value);
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

    private CellStyle numberStyle(Workbook wb) {
        CellStyle s = wb.createCellStyle();
        s.setAlignment(HorizontalAlignment.RIGHT);
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
