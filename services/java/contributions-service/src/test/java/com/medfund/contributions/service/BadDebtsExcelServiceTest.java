package com.medfund.contributions.service;

import com.medfund.contributions.dto.CreditorRow;
import com.medfund.contributions.dto.PageResponse;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Guards the shape of the bad-debts XLSX export. The workbook feeds an
 * operator triaging deactivated / terminated subjects that still owe
 * money — anything wrong in the header block (currency, subject-type
 * filter, status-filter description, search term) or the table (subject
 * humanisation, money column, dates) is directly visible to the user, so
 * lock the columns in explicitly rather than trusting the mirror-of-
 * CreditorsExcelService pattern to stay aligned.
 */
@ExtendWith(MockitoExtension.class)
class BadDebtsExcelServiceTest {

    @Mock private BalanceService balanceService;
    @InjectMocks private BadDebtsExcelService excel;

    @Test
    void generate_withGroupFilterAndSearch_writesHeaderBlockAndBodyRow() throws Exception {
        // A single group row so we can assert both:
        //  – the header block echoes the filters the operator applied
        //  – the body row carries the correct humanized subject type +
        //    money + dates without the columns silently drifting.
        UUID groupId = UUID.randomUUID();
        CreditorRow row = new CreditorRow(
                "GROUP", groupId, "GRP-42", "Acme Ltd", "billing@acme.example",
                "USD", new BigDecimal("1250.75"),
                Instant.parse("2026-01-15T00:00:00Z"),
                Instant.parse("2026-02-20T00:00:00Z"),
                135L);
        PageResponse<CreditorRow> page = PageResponse.of(List.of(row), 1L, 0, 10_000);
        when(balanceService.listBadDebts(eq("USD"), eq("GROUP"), eq("Acme"), eq(0), anyInt()))
                .thenReturn(Mono.just(page));

        byte[] bytes = generateBlocking("USD", "GROUP", "Acme");

        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            Sheet sheet = wb.getSheet("Bad debts");
            assertThat(sheet).isNotNull();

            // Row 0: title.
            assertThat(cellString(sheet, 0, 0)).isEqualTo("Bad debts");

            // Header block starts on row 2 (blank row after title). The
            // exact row numbers here mirror the order in
            // BadDebtsExcelService.render() and are the visible-to-user
            // contract, so we pin them explicitly.
            assertThat(cellString(sheet, 2, 0)).isEqualTo("Currency");
            assertThat(cellString(sheet, 2, 1)).isEqualTo("USD");
            assertThat(cellString(sheet, 3, 0)).isEqualTo("Subject type");
            assertThat(cellString(sheet, 3, 1)).isEqualTo("Group");
            assertThat(cellString(sheet, 4, 0)).isEqualTo("Status filter");
            assertThat(cellString(sheet, 4, 1))
                    .isEqualTo("Deactivated or terminated with an outstanding balance");
            assertThat(cellString(sheet, 5, 0)).isEqualTo("Search");
            assertThat(cellString(sheet, 5, 1)).isEqualTo("Acme");
            assertThat(cellString(sheet, 7, 0)).isEqualTo("Rows");
            assertThat(cellString(sheet, 7, 1)).isEqualTo("1");

            // Table header row (row 9 — six label rows + blank row + one
            // blank line before header). Guard the exact column order:
            // Type, Name, Code, Email, Outstanding, Last payment, Days since.
            int headerRow = 9;
            assertThat(cellString(sheet, headerRow, 0)).isEqualTo("Type");
            assertThat(cellString(sheet, headerRow, 1)).isEqualTo("Name");
            assertThat(cellString(sheet, headerRow, 2)).isEqualTo("Code");
            assertThat(cellString(sheet, headerRow, 3)).isEqualTo("Email");
            assertThat(cellString(sheet, headerRow, 4)).isEqualTo("Outstanding");
            assertThat(cellString(sheet, headerRow, 5)).isEqualTo("Last payment");
            assertThat(cellString(sheet, headerRow, 6)).isEqualTo("Days since");

            // Body row (row 10). Type must be humanised from GROUP →
            // "Group"; balance as a numeric cell; days-since as a number.
            int bodyRow = headerRow + 1;
            assertThat(cellString(sheet, bodyRow, 0)).isEqualTo("Group");
            assertThat(cellString(sheet, bodyRow, 1)).isEqualTo("Acme Ltd");
            assertThat(cellString(sheet, bodyRow, 2)).isEqualTo("GRP-42");
            assertThat(cellString(sheet, bodyRow, 3)).isEqualTo("billing@acme.example");
            assertThat(sheet.getRow(bodyRow).getCell(4).getNumericCellValue())
                    .isEqualTo(1250.75);
            assertThat(sheet.getRow(bodyRow).getCell(6).getNumericCellValue())
                    .isEqualTo(135.0);
        }

        verify(balanceService).listBadDebts("USD", "GROUP", "Acme", 0, 10_000);
    }

    @Test
    void generate_nullSubjectTypeAndNoSearch_writesAllPlaceholderAndDash() throws Exception {
        // The "both individuals + groups" default flavour of the export.
        // Filter block must render "All (individuals + groups)" + "—" so
        // the operator opening the sheet later can tell it wasn't
        // filtered.
        PageResponse<CreditorRow> page = PageResponse.of(List.of(), 0L, 0, 10_000);
        when(balanceService.listBadDebts(eq("USD"), eq((String) null), eq((String) null),
                eq(0), anyInt()))
                .thenReturn(Mono.just(page));

        byte[] bytes = generateBlocking("USD", null, null);

        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            Sheet sheet = wb.getSheet("Bad debts");
            assertThat(cellString(sheet, 3, 1)).isEqualTo("All (individuals + groups)");
            assertThat(cellString(sheet, 5, 1)).isEqualTo("—");
        }
    }

    @Test
    void generate_memberRow_humanisesToIndividual() throws Exception {
        // The Type column is the operator's primary at-a-glance filter
        // within the sheet — "MEMBER" would be jargon leaking through.
        UUID memberId = UUID.randomUUID();
        CreditorRow member = new CreditorRow(
                "MEMBER", memberId, "M-01", "Jane Doe", "jane@example.com",
                "USD", new BigDecimal("50.00"), null, null, null);
        PageResponse<CreditorRow> page = PageResponse.of(List.of(member), 1L, 0, 10_000);
        when(balanceService.listBadDebts(eq("USD"), eq("MEMBER"), eq((String) null),
                eq(0), anyInt()))
                .thenReturn(Mono.just(page));

        byte[] bytes = generateBlocking("USD", "MEMBER", null);

        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            Sheet sheet = wb.getSheet("Bad debts");
            // Header block subject-type line reflects the requested filter.
            assertThat(cellString(sheet, 3, 1)).isEqualTo("Individual");
            // Body row Type cell reflects the row-level humanisation.
            assertThat(cellString(sheet, 10, 0)).isEqualTo("Individual");
        }
    }

    private byte[] generateBlocking(String currency, String subjectType, String q) {
        return excel.generate(currency, subjectType, q).block();
    }

    private static String cellString(Sheet sheet, int rowIdx, int colIdx) {
        Row row = sheet.getRow(rowIdx);
        if (row == null) return "";
        var cell = row.getCell(colIdx);
        return cell == null ? "" : cell.getStringCellValue();
    }

    // Silence "unused" warnings for the StepVerifier import if refactored
    // later — kept in scope in case the test file grows an async path.
    @SuppressWarnings("unused")
    private static void unusedSilencer() {
        StepVerifier.create(Mono.empty()).verifyComplete();
    }
}
