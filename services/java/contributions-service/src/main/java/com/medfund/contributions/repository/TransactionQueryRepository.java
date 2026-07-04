package com.medfund.contributions.repository;

import com.medfund.contributions.dto.TransactionFilterParams;
import com.medfund.contributions.entity.Transaction;
import lombok.RequiredArgsConstructor;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Dynamic-SQL search for the operational transactions list. Mirrors the
 * pattern in {@link BalanceQueryRepository} — boolean flags decide which
 * WHERE clauses appear in the SQL string, and binds happen only for the
 * fields that are present, so we never need to bind null values.
 */
@Repository
@RequiredArgsConstructor
public class TransactionQueryRepository {

    private final DatabaseClient db;

    public Flux<Transaction> search(TransactionFilterParams f, int limit, int offset) {
        boolean hasQ = f.q() != null && !f.q().isBlank();
        String search = hasQ ? "%" + f.q().toLowerCase() + "%" : null;

        String sql = baseQuery(f, hasQ)
                + " ORDER BY transaction_date DESC NULLS LAST, created_at DESC LIMIT :limit OFFSET :offset";
        var spec = bindFilters(db.sql(sql), f, hasQ, search)
                .bind("limit", limit)
                .bind("offset", offset);
        return spec.map(this::toEntity).all();
    }

    public Mono<Long> count(TransactionFilterParams f) {
        boolean hasQ = f.q() != null && !f.q().isBlank();
        String search = hasQ ? "%" + f.q().toLowerCase() + "%" : null;

        String sql = "SELECT COUNT(*) AS total FROM (" + baseQuery(f, hasQ) + ") sub";
        var spec = bindFilters(db.sql(sql), f, hasQ, search);
        return spec.map(row -> ((Number) row.get("total")).longValue()).one();
    }

    // ── SQL builder ──────────────────────────────────────────────────────────

    private String baseQuery(TransactionFilterParams f, boolean hasQ) {
        StringBuilder sb = new StringBuilder("""
                SELECT id, transaction_number, group_id, member_id,
                       contribution_id, invoice_id,
                       amount, currency_code, transaction_type, payment_method,
                       reference, reason, status, transaction_date, created_at, created_by
                  FROM transactions
                 WHERE 1 = 1
                """);
        if (f.currencyCode()    != null) sb.append(" AND currency_code = :currency ");
        if (f.transactionType() != null) sb.append(" AND LOWER(transaction_type) = LOWER(:type) ");
        if (f.paymentMethod()   != null) sb.append(" AND LOWER(COALESCE(payment_method, '')) = LOWER(:method) ");
        if (f.periodStart()     != null) sb.append(" AND transaction_date >= :periodStart ");
        if (f.periodEnd()       != null) sb.append(" AND transaction_date <  :periodEndExclusive ");
        if (f.contributionId()  != null) sb.append(" AND contribution_id = :contributionId ");
        if (f.invoiceId()       != null) sb.append(" AND invoice_id = :invoiceId ");
        if (hasQ) sb.append(" AND (LOWER(transaction_number) LIKE :search OR LOWER(COALESCE(reference, '')) LIKE :search) ");
        return sb.toString();
    }

    private DatabaseClient.GenericExecuteSpec bindFilters(DatabaseClient.GenericExecuteSpec spec,
                                                          TransactionFilterParams f,
                                                          boolean hasQ,
                                                          String search) {
        if (f.currencyCode()    != null) spec = spec.bind("currency", f.currencyCode());
        if (f.transactionType() != null) spec = spec.bind("type", f.transactionType());
        if (f.paymentMethod()   != null) spec = spec.bind("method", f.paymentMethod());
        if (f.periodStart()     != null) {
            spec = spec.bind("periodStart", f.periodStart().atStartOfDay().toInstant(java.time.ZoneOffset.UTC));
        }
        if (f.periodEnd()       != null) {
            // Exclusive upper bound = day-after-periodEnd at midnight UTC.
            spec = spec.bind("periodEndExclusive",
                    f.periodEnd().plusDays(1).atStartOfDay().toInstant(java.time.ZoneOffset.UTC));
        }
        if (f.contributionId()  != null) spec = spec.bind("contributionId", f.contributionId());
        if (f.invoiceId()       != null) spec = spec.bind("invoiceId", f.invoiceId());
        if (hasQ)                        spec = spec.bind("search", search);
        return spec;
    }

    private Transaction toEntity(io.r2dbc.spi.Readable row) {
        Transaction t = new Transaction();
        t.setId((UUID) row.get("id"));
        t.setTransactionNumber((String) row.get("transaction_number"));
        t.setGroupId((UUID) row.get("group_id"));
        t.setMemberId((UUID) row.get("member_id"));
        t.setContributionId((UUID) row.get("contribution_id"));
        t.setInvoiceId((UUID) row.get("invoice_id"));
        t.setAmount((BigDecimal) row.get("amount"));
        t.setCurrencyCode((String) row.get("currency_code"));
        t.setTransactionType((String) row.get("transaction_type"));
        t.setPaymentMethod((String) row.get("payment_method"));
        t.setReference((String) row.get("reference"));
        t.setReason((String) row.get("reason"));
        t.setStatus((String) row.get("status"));
        // TIMESTAMPTZ columns come back as OffsetDateTime; ask R2DBC to
        // hand us Instant directly so we don't ClassCastException on the
        // legacy (Instant) row.get(...) shape. See snapshot statement
        // queries for the same pattern.
        t.setTransactionDate(row.get("transaction_date", Instant.class));
        t.setCreatedAt(row.get("created_at", Instant.class));
        t.setCreatedBy((UUID) row.get("created_by"));
        return t;
    }
}
