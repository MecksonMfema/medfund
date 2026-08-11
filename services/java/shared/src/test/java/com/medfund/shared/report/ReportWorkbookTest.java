package com.medfund.shared.report;

import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReportWorkbookTest {

    private record CreditorRow(String subject, String currency, BigDecimal amount) {}

    @Test
    void buildsWorkbookWithTitleMetaHeaderAndDataRows() throws IOException {
        List<CreditorRow> rows = List.of(
                new CreditorRow("Acme Health", "USD", new BigDecimal("1250.00")),
                new CreditorRow("Beta Care",   "USD", new BigDecimal("980.50")));

        byte[] bytes = ReportWorkbook.newBook()
                .sheet("Creditors")
                    .titleMerged("Creditors", 3)
                    .meta("Currency", "USD")
                    .metaMoney("Total", new BigDecimal("2230.50"))
                    .blankRow()
                    .header("Subject", "Currency", "Amount")
                    .forEach(rows, (sw, row) -> sw
                            .text(row.subject())
                            .text(row.currency())
                            .money(row.amount()))
                    .freezeAtHeader()
                    .autoSize()
                .toBytes();

        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            Sheet sheet = wb.getSheet("Creditors");
            assertThat(sheet).isNotNull();
            assertThat(sheet.getRow(0).getCell(0).getStringCellValue()).isEqualTo("Creditors");
            assertThat(sheet.getRow(2).getCell(0).getStringCellValue()).isEqualTo("Currency");
            assertThat(sheet.getRow(2).getCell(1).getStringCellValue()).isEqualTo("USD");
            assertThat(sheet.getRow(3).getCell(0).getStringCellValue()).isEqualTo("Total");
            assertThat(sheet.getRow(3).getCell(1).getNumericCellValue()).isEqualTo(2230.50);
            // Blank row at index 4 → header at index 5
            Row header = sheet.getRow(5);
            assertThat(header.getCell(0).getStringCellValue()).isEqualTo("Subject");
            Row first = sheet.getRow(6);
            assertThat(first.getCell(0).getStringCellValue()).isEqualTo("Acme Health");
            assertThat(first.getCell(2).getNumericCellValue()).isEqualTo(1250.00);
            // freezeAtHeader() locks everything above the table header.
            assertThat(sheet.getPaneInformation()).isNotNull();
        }
    }

    @Test
    void supportsSingleColumnTitle() throws IOException {
        byte[] bytes = ReportWorkbook.newBook()
                .sheet("Simple")
                    .title("Just a heading")
                    .header("Col")
                    .text("val")
                    .nextRow()
                .toBytes();

        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            Sheet sheet = wb.getSheet("Simple");
            assertThat(sheet.getRow(0).getCell(0).getStringCellValue()).isEqualTo("Just a heading");
        }
    }

    @Test
    void handlesNullValuesGracefully() throws IOException {
        byte[] bytes = ReportWorkbook.newBook()
                .sheet("NullCheck")
                    .meta("Missing", null)
                    .header("A", "B", "C", "D", "E")
                    .text(null)
                    .money(null)
                    .date((Instant) null)
                    .date((LocalDate) null)
                    .number(null)
                    .nextRow()
                .toBytes();

        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            Sheet sheet = wb.getSheet("NullCheck");
            // meta with null value renders an em-dash
            assertThat(sheet.getRow(0).getCell(1).getStringCellValue()).isEqualTo("—");
            // Every null-typed cell rendered as blank string
            Row dataRow = sheet.getRow(2);
            for (int i = 0; i < 5; i++) {
                assertThat(dataRow.getCell(i).getStringCellValue()).isEmpty();
            }
        }
    }

    @Test
    void supportsDateCells() throws IOException {
        Instant when = Instant.parse("2026-08-11T00:00:00Z");
        LocalDate d = LocalDate.of(2026, 8, 11);
        byte[] bytes = ReportWorkbook.newBook()
                .sheet("Dates")
                    .header("Instant", "LocalDate")
                    .date(when)
                    .date(d)
                    .nextRow()
                .toBytes();

        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            Sheet sheet = wb.getSheet("Dates");
            Row row = sheet.getRow(1);
            assertThat(row.getCell(0).getCellType()).isEqualTo(CellType.NUMERIC);
            assertThat(row.getCell(1).getCellType()).isEqualTo(CellType.NUMERIC);
        }
    }

    @Test
    void supportsNumbersAndMoneyBold() throws IOException {
        byte[] bytes = ReportWorkbook.newBook()
                .sheet("Nums")
                    .header("N", "M")
                    .number(42L)
                    .moneyBold(new BigDecimal("99.99"))
                    .nextRow()
                    .end()
                .toBytes();

        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            Sheet sheet = wb.getSheet("Nums");
            Row row = sheet.getRow(1);
            assertThat(row.getCell(0).getNumericCellValue()).isEqualTo(42d);
            assertThat(row.getCell(1).getNumericCellValue()).isEqualTo(99.99d);
        }
    }

    @Test
    void endReturnsParentAndAllowsMultipleSheets() throws IOException {
        byte[] bytes = ReportWorkbook.newBook()
                .sheet("First").title("First").end()
                .sheet("Second").title("Second").end()
                .toBytes();

        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            assertThat(wb.getSheet("First")).isNotNull();
            assertThat(wb.getSheet("Second")).isNotNull();
        }
    }

    @Test
    void freezeAtHeaderIsNoOpWithoutHeader() throws IOException {
        // Should not throw when the sheet never called header()
        byte[] bytes = ReportWorkbook.newBook()
                .sheet("NoHeader")
                    .title("Nothing")
                    .freezeAtHeader()
                    .autoSize()
                .toBytes();
        assertThat(bytes).isNotEmpty();
    }
}
