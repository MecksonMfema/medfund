package com.medfund.contributions.service;

import com.medfund.contributions.dto.StatementLine;
import com.medfund.contributions.dto.StatementResponse;
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
import java.util.UUID;

/**
 * Renders a {@link StatementResponse} as a formatted XLSX workbook. Single
 * sheet, target/period header in merged cells, ledger table with debit /
 * credit / running-balance columns, totals row at the bottom.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StatementExcelService {

    private final StatementService statementService;

    public Mono<byte[]> generate(String targetType, UUID targetId,
                                  LocalDate periodStart, LocalDate periodEnd, String currency) {
        return statementService.generate(targetType, targetId, periodStart, periodEnd, currency)
                .map(this::render);
    }

    private byte[] render(StatementResponse statement) {
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("Statement");

            CellStyle title = headerStyle(wb, true);
            CellStyle label = labelStyle(wb);
            CellStyle bold = boldStyle(wb);
            CellStyle money = moneyStyle(wb, false);
            CellStyle moneyBold = moneyStyle(wb, true);
            CellStyle thHeader = tableHeaderStyle(wb);

            int r = 0;
            StatementResponse.Header h = statement.header();

            Row titleRow = sheet.createRow(r++);
            cell(titleRow, 0, "Contribution Statement", title);
            sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, 5));
            r++;

            r = writeLabelValue(sheet, r, label, bold,
                    h.targetType().charAt(0) + h.targetType().substring(1).toLowerCase(),
                    h.targetName() != null ? h.targetName() : h.targetId().toString());
            if (h.targetCode() != null) {
                r = writeLabelValue(sheet, r, label, bold, "Code", h.targetCode());
            }
            r = writeLabelValue(sheet, r, label, bold, "Period",
                    h.periodStart() + " → " + h.periodEnd());
            r = writeLabelValue(sheet, r, label, bold, "Currency", h.currencyCode());
            r++;

            r = writeLabelMoney(sheet, r, label, moneyBold, "Opening balance", h.openingBalance());
            r = writeLabelMoney(sheet, r, label, money, "Total charges", h.totalCharges());
            r = writeLabelMoney(sheet, r, label, money, "Total payments", h.totalPayments());
            r = writeLabelMoney(sheet, r, label, moneyBold, "Closing balance", h.closingBalance());
            r++;

            // Table header
            Row head = sheet.createRow(r++);
            cell(head, 0, "Date", thHeader);
            cell(head, 1, "Description", thHeader);
            cell(head, 2, "Reference", thHeader);
            cell(head, 3, "Debit", thHeader);
            cell(head, 4, "Credit", thHeader);
            cell(head, 5, "Balance", thHeader);

            for (StatementLine line : statement.lines()) {
                Row row = sheet.createRow(r++);
                cell(row, 0, line.date() != null ? line.date().toString().substring(0, 10) : "", null);
                cell(row, 1, line.description() != null ? line.description() : "", null);
                cell(row, 2, line.reference() != null ? line.reference() : "", null);
                writeMoney(row, 3, line.debit(), money);
                writeMoney(row, 4, line.credit(), money);
                writeMoney(row, 5, line.runningBalance(), moneyBold);
            }

            // Freeze the table header row.
            sheet.createFreezePane(0, r - statement.lines().size());

            for (int i = 0; i <= 5; i++) sheet.autoSizeColumn(i);

            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("Failed to build statement workbook", e);
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private int writeLabelValue(Sheet sheet, int r, CellStyle labelStyle, CellStyle valueStyle,
                                 String label, String value) {
        Row row = sheet.createRow(r);
        cell(row, 0, label, labelStyle);
        cell(row, 1, value, valueStyle);
        return r + 1;
    }

    private int writeLabelMoney(Sheet sheet, int r, CellStyle labelStyle, CellStyle moneyStyle,
                                 String label, BigDecimal amount) {
        Row row = sheet.createRow(r);
        cell(row, 0, label, labelStyle);
        writeMoney(row, 1, amount, moneyStyle);
        return r + 1;
    }

    private void writeMoney(Row row, int col, BigDecimal amount, CellStyle style) {
        if (amount == null) {
            cell(row, col, "", style);
            return;
        }
        Cell c = row.createCell(col);
        c.setCellValue(amount.doubleValue());
        if (style != null) c.setCellStyle(style);
    }

    private void cell(Row row, int col, String value, CellStyle style) {
        Cell c = row.createCell(col);
        c.setCellValue(value);
        if (style != null) c.setCellStyle(style);
    }

    private CellStyle headerStyle(Workbook wb, boolean large) {
        CellStyle s = wb.createCellStyle();
        Font f = wb.createFont();
        f.setBold(true);
        f.setFontHeightInPoints((short) (large ? 16 : 11));
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
