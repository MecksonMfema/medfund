package com.medfund.contributions.repository;

import com.medfund.contributions.dto.InvoiceReceiptRow;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Reads the invoice ledger for the Phase 8 cash-flow forecast. Only the
 * forecast's needs are exposed: unpaid invoices (status neither
 * {@code paid} nor {@code void}) whose {@code due_date} falls inside the
 * window — the inflow side of the weekly strip. Tenancy is enforced by
 * the TenantContext search-path interceptor, same as every other query
 * here.
 */
@Repository
public class CashFlowForecastQueryRepository {

    private final DatabaseClient db;

    public CashFlowForecastQueryRepository(DatabaseClient db) {
        this.db = db;
    }

    public Flux<InvoiceReceiptRow> expectedReceipts(LocalDate windowStart, LocalDate windowEnd) {
        return db.sql("SELECT currency_code, due_date, total_amount "
                    + "  FROM invoices "
                    + " WHERE status NOT IN ('paid', 'void') "
                    + "   AND due_date >= :start AND due_date < :end")
                .bind("start", windowStart)
                .bind("end", windowEnd)
                .map((row, meta) -> new InvoiceReceiptRow(
                        row.get("currency_code", String.class),
                        row.get("due_date", LocalDate.class),
                        row.get("total_amount", BigDecimal.class)))
                .all();
    }
}
