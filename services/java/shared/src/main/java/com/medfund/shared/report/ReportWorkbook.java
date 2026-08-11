package com.medfund.shared.report;

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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.Date;
import java.util.function.BiConsumer;

/**
 * Fluent Apache POI wrapper that centralises the styling and layout every
 * InsureFlow report XLSX shares — merged title, grey label / bold value
 * meta rows, sea-green table header, right-aligned money cells with
 * {@code #,##0.00} format, freeze-at-header, auto-sized columns.
 *
 * <p>Absorbs the duplicated logic previously spread across
 * {@code CreditorsExcelService}, {@code StatementExcelService},
 * {@code DebtorsExcelService}, and {@code BadDebtsExcelService}. New
 * reports should not re-hand-roll POI code — always route through this.
 *
 * <p>Typical use:
 * <pre>{@code
 * byte[] bytes = ReportWorkbook.newBook()
 *         .sheet("Creditors")
 *             .title("Creditors")
 *             .meta("Currency", currency)
 *             .meta("Rows", String.valueOf(rows.size()))
 *             .blankRow()
 *             .header("Type", "Code", "Amount")
 *             .forEach(rows, (sw, row) -> sw
 *                     .text(row.type())
 *                     .text(row.code())
 *                     .money(row.amount()))
 *             .freezeAtHeader()
 *             .autoSize()
 *         .toBytes();
 * }</pre>
 *
 * <p>Not thread-safe — build one per request, {@code toBytes()} closes it.
 */
public final class ReportWorkbook implements AutoCloseable {

    private final XSSFWorkbook wb;
    private final Styles styles;

    private ReportWorkbook() {
        this.wb = new XSSFWorkbook();
        this.styles = new Styles(wb);
    }

    public static ReportWorkbook newBook() {
        return new ReportWorkbook();
    }

    public SheetWriter sheet(String name) {
        Sheet sheet = wb.createSheet(name);
        return new SheetWriter(this, sheet, styles);
    }

    /** Serialises to bytes and closes the workbook. Call at most once. */
    public byte[] toBytes() {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream();
             ReportWorkbook self = this) {
            self.wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("Failed to serialise report workbook", e);
        }
    }

    @Override
    public void close() {
        try { wb.close(); } catch (IOException e) { /* best effort */ }
    }

    // ── SheetWriter ─────────────────────────────────────────────────────────

    /**
     * Fluent per-sheet writer. Tracks the current row/column cursor so
     * callers never have to hand-manage indices — every {@code text/money/date}
     * call places the value at the next column, and row-boundary calls
     * ({@code forEach}, {@code nextRow}) advance the cursor.
     */
    public static final class SheetWriter {
        private final ReportWorkbook parent;
        private final Sheet sheet;
        private final Styles styles;

        private int currentRow = 0;
        private int currentCol = 0;
        private int headerRow  = -1;
        private int maxCol     = 0;

        private SheetWriter(ReportWorkbook parent, Sheet sheet, Styles styles) {
            this.parent = parent;
            this.sheet  = sheet;
            this.styles = styles;
        }

        // ── Header block ────────────────────────────────────────────────────

        /** Merged title row across the last {@code maxCol} columns seen. */
        public SheetWriter title(String text) {
            Row row = sheet.createRow(currentRow);
            cell(row, 0, text, styles.title);
            // Merge conservatively — we don't yet know maxCol; caller
            // can extend via titleMerged(text, spanCols) when needed.
            currentRow++;
            currentCol = 0;
            return blankRow();
        }

        /** Merged title with an explicit column span. */
        public SheetWriter titleMerged(String text, int spanCols) {
            Row row = sheet.createRow(currentRow);
            cell(row, 0, text, styles.title);
            if (spanCols > 1) {
                sheet.addMergedRegion(new CellRangeAddress(
                        currentRow, currentRow, 0, spanCols - 1));
            }
            currentRow++;
            currentCol = 0;
            return blankRow();
        }

        /**
         * Two-column meta row — grey label, bold value. Use for the filter
         * echo block above the ledger table.
         */
        public SheetWriter meta(String label, String value) {
            Row row = sheet.createRow(currentRow);
            cell(row, 0, label, styles.label);
            cell(row, 1, value != null ? value : "—", styles.bold);
            maxCol = Math.max(maxCol, 2);
            currentRow++;
            currentCol = 0;
            return this;
        }

        /** Two-column meta row with a formatted money value. */
        public SheetWriter metaMoney(String label, BigDecimal amount) {
            Row row = sheet.createRow(currentRow);
            cell(row, 0, label, styles.label);
            money(row, 1, amount, styles.moneyBold);
            maxCol = Math.max(maxCol, 2);
            currentRow++;
            currentCol = 0;
            return this;
        }

        /** Advance the cursor by one empty row — visual separator. */
        public SheetWriter blankRow() {
            currentRow++;
            currentCol = 0;
            return this;
        }

        // ── Table header ────────────────────────────────────────────────────

        /**
         * Sea-green bold header row. Remembers the position so
         * {@link #freezeAtHeader()} can lock the columns underneath it.
         */
        public SheetWriter header(String... columns) {
            headerRow = currentRow;
            Row row = sheet.createRow(currentRow);
            for (int i = 0; i < columns.length; i++) {
                cell(row, i, columns[i], styles.tableHeader);
            }
            maxCol = Math.max(maxCol, columns.length);
            currentRow++;
            currentCol = 0;
            return this;
        }

        // ── Row-cursor writes ───────────────────────────────────────────────

        /** Write a text cell at the current column, advance. */
        public SheetWriter text(String value) {
            Row row = getOrCreateCurrentRow();
            cell(row, currentCol, value != null ? value : "", null);
            currentCol++;
            return this;
        }

        /** Right-aligned money cell — {@code #,##0.00} format. */
        public SheetWriter money(BigDecimal amount) {
            Row row = getOrCreateCurrentRow();
            money(row, currentCol, amount, styles.money);
            currentCol++;
            return this;
        }

        /** Right-aligned bold money cell — for totals / balances. */
        public SheetWriter moneyBold(BigDecimal amount) {
            Row row = getOrCreateCurrentRow();
            money(row, currentCol, amount, styles.moneyBold);
            currentCol++;
            return this;
        }

        /** ISO date cell from an {@link Instant} — {@code yyyy-mm-dd}. */
        public SheetWriter date(Instant when) {
            Row row = getOrCreateCurrentRow();
            if (when == null) {
                cell(row, currentCol, "", null);
            } else {
                Cell c = row.createCell(currentCol);
                c.setCellValue(Date.from(when));
                c.setCellStyle(styles.date);
            }
            currentCol++;
            return this;
        }

        /** ISO date cell from a {@link LocalDate} — {@code yyyy-mm-dd}. */
        public SheetWriter date(LocalDate when) {
            Row row = getOrCreateCurrentRow();
            if (when == null) {
                cell(row, currentCol, "", null);
            } else {
                Cell c = row.createCell(currentCol);
                c.setCellValue(java.sql.Date.valueOf(when));
                c.setCellStyle(styles.date);
            }
            currentCol++;
            return this;
        }

        /** Right-aligned integer cell. */
        public SheetWriter number(Long value) {
            Row row = getOrCreateCurrentRow();
            if (value == null) {
                cell(row, currentCol, "", null);
            } else {
                Cell c = row.createCell(currentCol);
                c.setCellValue(value);
                c.setCellStyle(styles.number);
            }
            currentCol++;
            return this;
        }

        /** Manually advance to the next row (rare — {@link #forEach} is preferred). */
        public SheetWriter nextRow() {
            maxCol = Math.max(maxCol, currentCol);
            currentRow++;
            currentCol = 0;
            return this;
        }

        /**
         * Append one row per element via the given writer. Ends each row
         * automatically — the callback only writes cells.
         */
        public <T> SheetWriter forEach(Collection<T> items, BiConsumer<SheetWriter, T> writer) {
            for (T item : items) {
                writer.accept(this, item);
                nextRow();
            }
            return this;
        }

        // ── Finalisers ──────────────────────────────────────────────────────

        /** Freeze all rows above the table header so the columns stay visible on scroll. */
        public SheetWriter freezeAtHeader() {
            if (headerRow >= 0) sheet.createFreezePane(0, headerRow + 1);
            return this;
        }

        /** Auto-size every column that had at least one cell written. */
        public SheetWriter autoSize() {
            int cols = Math.max(maxCol, currentCol);
            for (int i = 0; i < cols; i++) sheet.autoSizeColumn(i);
            return this;
        }

        // ── Escape hatches ──────────────────────────────────────────────────

        /** Back to the parent workbook — add another sheet or {@link #toBytes()}. */
        public ReportWorkbook end() {
            return parent;
        }

        /** Terminal shortcut that skips {@link #end()}. */
        public byte[] toBytes() {
            return parent.toBytes();
        }

        // ── Internals ───────────────────────────────────────────────────────

        private Row getOrCreateCurrentRow() {
            Row row = sheet.getRow(currentRow);
            return row != null ? row : sheet.createRow(currentRow);
        }

        private static void cell(Row row, int col, String value, CellStyle style) {
            Cell c = row.createCell(col);
            c.setCellValue(value);
            if (style != null) c.setCellStyle(style);
        }

        private static void money(Row row, int col, BigDecimal amount, CellStyle style) {
            if (amount == null) {
                cell(row, col, "", style);
                return;
            }
            Cell c = row.createCell(col);
            c.setCellValue(amount.doubleValue());
            c.setCellStyle(style);
        }
    }

    // ── Style bundle ────────────────────────────────────────────────────────

    private static final class Styles {
        final CellStyle title;
        final CellStyle label;
        final CellStyle bold;
        final CellStyle money;
        final CellStyle moneyBold;
        final CellStyle date;
        final CellStyle number;
        final CellStyle tableHeader;

        Styles(Workbook wb) {
            this.title       = titleStyle(wb);
            this.label       = labelStyle(wb);
            this.bold        = boldStyle(wb);
            this.money       = moneyStyle(wb, false);
            this.moneyBold   = moneyStyle(wb, true);
            this.date        = dateStyle(wb);
            this.number      = numberStyle(wb);
            this.tableHeader = tableHeaderStyle(wb);
        }

        private static CellStyle titleStyle(Workbook wb) {
            CellStyle s = wb.createCellStyle();
            Font f = wb.createFont();
            f.setBold(true);
            f.setFontHeightInPoints((short) 16);
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

        private static CellStyle moneyStyle(Workbook wb, boolean bold) {
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

        private static CellStyle dateStyle(Workbook wb) {
            CellStyle s = wb.createCellStyle();
            s.setDataFormat(wb.createDataFormat().getFormat("yyyy-mm-dd"));
            return s;
        }

        private static CellStyle numberStyle(Workbook wb) {
            CellStyle s = wb.createCellStyle();
            s.setAlignment(HorizontalAlignment.RIGHT);
            return s;
        }

        private static CellStyle tableHeaderStyle(Workbook wb) {
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
}
