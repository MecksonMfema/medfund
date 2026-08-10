package com.medfund.contributions.service;

import com.medfund.contributions.dto.StatementLine;
import com.medfund.contributions.dto.StatementResponse;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Reads the generated XLSX bytes back with Apache POI and asserts on
 * the workbook shape. Guards the double-entry cleanup:
 *
 * <ul>
 *   <li>Header block carries the standard-financial fields (holder,
 *       code, statement period, currency, opening, closing) — no
 *       total-charges / total-payments here.</li>
 *   <li>Ledger table iterates every line including OPENING_BALANCE
 *       and CLOSING_BALANCE — bookend rows get the description
 *       suffixes {@code (B/F)} and {@code (C/F)} so an
 *       operator reading the sheet sees the double-entry structure at
 *       a glance.</li>
 *   <li>Ledger footer carries the period activity totals
 *       (contributions, payments, amount due).</li>
 * </ul>
 *
 * A regression that flattened the header or dropped the bookend
 * suffixes would produce a subtly-wrong workbook that unit tests would
 * miss without reading the bytes back — this test does exactly that.
 */
@ExtendWith(MockitoExtension.class)
class StatementExcelServiceTest {

    @Mock StatementService statementService;

    private StatementExcelService service;

    @BeforeEach
    void setUp() {
        service = new StatementExcelService(statementService);
    }

    @Test
    void generate_writesStandardFinancialHeader_ledgerWithBookends_andFooter() throws Exception {
        var statement = sampleStatementWithBookends();
        when(statementService.generate(any(), any(), any(), any(), any()))
                .thenReturn(Mono.just(statement));

        byte[] xlsx = service.generate("GROUP", UUID.randomUUID(),
                LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31), "USD").block();

        assertThat(xlsx).isNotNull().isNotEmpty();

        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(xlsx))) {
            Sheet sheet = wb.getSheetAt(0);
            assertThat(sheet.getSheetName()).isEqualTo("Statement");

            List<String> firstColLabels = collectFirstColumn(sheet, 40);

            // ── Standard-financial header block ─────────────────────
            //
            // Order: Title → (blank) → Group holder → Code → Statement
            // period → Currency → Opening balance → Closing balance →
            // (blank) → ledger.
            assertThat(firstColLabels).contains(
                    "Contribution Statement",
                    "Statement period",
                    "Currency",
                    "Opening balance",
                    "Closing balance");
            // Total charges / payments MUST NOT be in the header —
            // they belong in the ledger footer per the standard
            // financial layout.
            assertThat(headerRowsOnly(sheet)).doesNotContain(
                    "Total charges", "Total payments");

            // ── Ledger bookend rows ──────────────────────────────────
            //
            // OPENING_BALANCE row description suffixed with (B/F);
            // CLOSING_BALANCE row description suffixed with
            // (C/F · amount due). Locates them by scanning the
            // description column.
            List<String> descriptions = collectColumn(sheet, 1, 60);
            assertThat(descriptions).anySatisfy(d ->
                    assertThat(d).contains("Balance brought forward").contains("(B/F)"));
            assertThat(descriptions).anySatisfy(d ->
                    assertThat(d).contains("Amount Due").contains("(C/F)"));

            // ── Ledger table header row present ──────────────────────
            assertThat(descriptions).contains("Description");

            // ── Ledger footer ────────────────────────────────────────
            //
            // Contributions this period + Payments received + Amount
            // Due land BELOW the ledger table. Amount Due is the last
            // label (row furthest down the sheet).
            assertThat(firstColLabels).contains(
                    "Contributions this period",
                    "Payments received",
                    "Amount Due");
        }
    }

    @Test
    void generate_emptyStatement_stillWritesHeaderAndBookendRows() throws Exception {
        // Even for a period with zero activity the sheet must still
        // render the two bookend rows — the ledger is the point, not
        // the transactions in it.
        var statement = emptyStatementWithBookendsOnly();
        when(statementService.generate(any(), any(), any(), any(), any()))
                .thenReturn(Mono.just(statement));

        byte[] xlsx = service.generate("GROUP", UUID.randomUUID(),
                LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31), "USD").block();

        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(xlsx))) {
            Sheet sheet = wb.getSheetAt(0);
            List<String> descriptions = collectColumn(sheet, 1, 60);
            assertThat(descriptions).anySatisfy(d -> assertThat(d).contains("(B/F)"));
            assertThat(descriptions).anySatisfy(d -> assertThat(d).contains("(C/F"));
        }
    }

    @Test
    void generate_ledgerRow_forNonBookendLine_writesDebitAndCredit() throws Exception {
        // Sanity: a regular transaction row still writes debit / credit
        // numeric cells so a POI regression on the bookend styling
        // doesn't accidentally break the plain-row column math.
        var statement = statementWith(
                bookend(StatementLine.TYPE_OPENING_BALANCE, "Balance brought forward",
                        new BigDecimal("50.00")),
                new StatementLine(Instant.parse("2026-08-15T00:00:00Z"),
                        StatementLine.TYPE_TRANSACTION, "PAYMENT — MOMO", "REF-1",
                        null, new BigDecimal("100.00"), new BigDecimal("-50.00"), UUID.randomUUID()),
                bookend(StatementLine.TYPE_CLOSING_BALANCE, "Amount Due",
                        new BigDecimal("-50.00"))
        );
        when(statementService.generate(any(), any(), any(), any(), any()))
                .thenReturn(Mono.just(statement));

        byte[] xlsx = service.generate("GROUP", UUID.randomUUID(),
                LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31), "USD").block();

        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(xlsx))) {
            Sheet sheet = wb.getSheetAt(0);
            Row paymentRow = findRowByDescription(sheet, "PAYMENT — MOMO");
            assertThat(paymentRow).as("payment row must exist in the ledger").isNotNull();
            // Column 4 (credit) should carry 100.00 as a numeric cell.
            assertThat(paymentRow.getCell(4).getNumericCellValue()).isEqualTo(100.00);
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────

    private StatementResponse sampleStatementWithBookends() {
        return statementWith(
                bookend(StatementLine.TYPE_OPENING_BALANCE, "Balance brought forward",
                        new BigDecimal("100.00")),
                new StatementLine(Instant.parse("2026-08-05T00:00:00Z"),
                        StatementLine.TYPE_CONTRIBUTION, "Contribution 2026-08-01 → 2026-08-31",
                        null, new BigDecimal("150.00"), null, new BigDecimal("250.00"), UUID.randomUUID()),
                new StatementLine(Instant.parse("2026-08-20T00:00:00Z"),
                        StatementLine.TYPE_TRANSACTION, "Payment — bank transfer", "REF-A",
                        null, new BigDecimal("0.00"), new BigDecimal("250.00"), UUID.randomUUID()),
                bookend(StatementLine.TYPE_CLOSING_BALANCE, "Amount Due",
                        new BigDecimal("250.00"))
        );
    }

    private StatementResponse emptyStatementWithBookendsOnly() {
        return statementWith(
                bookend(StatementLine.TYPE_OPENING_BALANCE, "Balance brought forward",
                        new BigDecimal("0.00")),
                bookend(StatementLine.TYPE_CLOSING_BALANCE, "Amount Due",
                        new BigDecimal("0.00"))
        );
    }

    private StatementLine bookend(String type, String description, BigDecimal running) {
        return new StatementLine(Instant.now(), type, description,
                null, null, null, running, null);
    }

    private StatementResponse statementWith(StatementLine... lines) {
        List<StatementLine> list = new ArrayList<>(List.of(lines));
        BigDecimal opening = list.stream()
                .filter(l -> StatementLine.TYPE_OPENING_BALANCE.equals(l.type()))
                .map(StatementLine::runningBalance).findFirst().orElse(BigDecimal.ZERO);
        BigDecimal closing = list.stream()
                .filter(l -> StatementLine.TYPE_CLOSING_BALANCE.equals(l.type()))
                .map(StatementLine::runningBalance).reduce((a, b) -> b).orElse(BigDecimal.ZERO);

        var header = new StatementResponse.Header(
                "GROUP", UUID.randomUUID(),
                "Acme Corp", "REG-001",
                LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31),
                "USD",
                opening, closing,
                new BigDecimal("150.00"), new BigDecimal("0.00"));
        return new StatementResponse(header, list);
    }

    /** Read column 0 across the first {@code maxRows} rows as strings. */
    private List<String> collectFirstColumn(Sheet sheet, int maxRows) {
        return collectColumn(sheet, 0, maxRows);
    }

    private List<String> collectColumn(Sheet sheet, int col, int maxRows) {
        List<String> out = new ArrayList<>();
        int last = Math.min(sheet.getLastRowNum(), maxRows);
        for (int i = 0; i <= last; i++) {
            Row r = sheet.getRow(i);
            if (r == null) continue;
            Cell c = r.getCell(col);
            if (c == null) continue;
            switch (c.getCellType()) {
                case STRING -> out.add(c.getStringCellValue());
                case NUMERIC -> out.add(String.valueOf(c.getNumericCellValue()));
                default -> { /* skip blanks / formulas */ }
            }
        }
        return out;
    }

    /** Header rows only — the block above the ledger table header.
     *  Used to assert that Total charges / Total payments do NOT appear
     *  in the header (they belong in the footer). */
    private List<String> headerRowsOnly(Sheet sheet) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i <= sheet.getLastRowNum(); i++) {
            Row r = sheet.getRow(i);
            if (r == null) continue;
            Cell c = r.getCell(0);
            if (c == null || c.getCellType() != org.apache.poi.ss.usermodel.CellType.STRING) continue;
            String v = c.getStringCellValue();
            if ("Date".equals(v)) break; // reached the ledger table header
            out.add(v);
        }
        return out;
    }

    private Row findRowByDescription(Sheet sheet, String description) {
        for (int i = 0; i <= sheet.getLastRowNum(); i++) {
            Row r = sheet.getRow(i);
            if (r == null) continue;
            Cell c = r.getCell(1);
            if (c != null && c.getCellType() == org.apache.poi.ss.usermodel.CellType.STRING
                    && description.equals(c.getStringCellValue())) {
                return r;
            }
        }
        return null;
    }
}
