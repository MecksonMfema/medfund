package com.medfund.finance.repository;

import com.medfund.finance.dto.BankReconciliationFilterParams;
import com.medfund.finance.entity.BankReconciliation;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

/**
 * Dynamic-SQL search powering the bank-reconciliations list. No joins —
 * reconciliation rows are self-contained.
 */
@Repository
public class BankReconciliationQueryRepository {

    private static final Map<String, String> SORT_COLUMNS = Map.of(
            "referenceNumber", "reference_number",
            "statementAmount", "statement_amount",
            "systemAmount",    "system_amount",
            "difference",      "difference",
            "currencyCode",    "currency_code",
            "status",          "status",
            "statementDate",   "statement_date",
            "reconciledAt",    "reconciled_at"
    );

    private final DatabaseClient db;

    public BankReconciliationQueryRepository(DatabaseClient db) {
        this.db = db;
    }

    public Flux<BankReconciliation> search(BankReconciliationFilterParams f, int limit, int offset) {
        boolean hasQ = f.q() != null && !f.q().isBlank();
        String search = hasQ ? "%" + f.q().toLowerCase() + "%" : null;

        String sql = "SELECT id, reference_number, statement_amount, system_amount, difference, "
                + "       currency_code, status, notes, statement_date, reconciled_at, reconciled_by "
                + "  FROM bank_reconciliations "
                + whereClause(f, hasQ)
                + " ORDER BY " + sortClause(f.sortKey(), f.sortDirection())
                + " LIMIT :limit OFFSET :offset";
        var spec = bindFilters(db.sql(sql), f, hasQ, search)
                .bind("limit", limit)
                .bind("offset", offset);
        return spec.map(this::toEntity).all();
    }

    public Mono<Long> count(BankReconciliationFilterParams f) {
        boolean hasQ = f.q() != null && !f.q().isBlank();
        String search = hasQ ? "%" + f.q().toLowerCase() + "%" : null;

        String sql = "SELECT COUNT(*) AS total FROM bank_reconciliations " + whereClause(f, hasQ);
        var spec = bindFilters(db.sql(sql), f, hasQ, search);
        return spec.map(row -> ((Number) row.get("total")).longValue()).one();
    }

    private String whereClause(BankReconciliationFilterParams f, boolean hasQ) {
        StringBuilder sb = new StringBuilder(" WHERE 1 = 1 ");
        if (f.status() != null && !f.status().isBlank()) {
            sb.append(" AND LOWER(status) = LOWER(:status) ");
        }
        if (f.currencyCode() != null && !f.currencyCode().isBlank()) {
            sb.append(" AND UPPER(currency_code) = UPPER(:currencyCode) ");
        }
        if (hasQ) {
            sb.append(" AND (LOWER(reference_number) LIKE :search "
                   + "     OR LOWER(COALESCE(notes, '')) LIKE :search) ");
        }
        return sb.toString();
    }

    private DatabaseClient.GenericExecuteSpec bindFilters(DatabaseClient.GenericExecuteSpec spec,
                                                          BankReconciliationFilterParams f,
                                                          boolean hasQ,
                                                          String search) {
        if (f.status() != null && !f.status().isBlank())              spec = spec.bind("status", f.status());
        if (f.currencyCode() != null && !f.currencyCode().isBlank())  spec = spec.bind("currencyCode", f.currencyCode());
        if (hasQ) spec = spec.bind("search", search);
        return spec;
    }

    private String sortClause(String sortKey, String sortDirection) {
        String col = SORT_COLUMNS.getOrDefault(sortKey, "statement_date");
        String dir = "asc".equalsIgnoreCase(sortDirection) ? "ASC" : "DESC";
        return col + " " + dir + " NULLS LAST, id ASC";
    }

    private BankReconciliation toEntity(io.r2dbc.spi.Readable row) {
        BankReconciliation r = new BankReconciliation();
        r.setId(row.get("id", UUID.class));
        r.setReferenceNumber(row.get("reference_number", String.class));
        r.setStatementAmount(row.get("statement_amount", BigDecimal.class));
        r.setSystemAmount(row.get("system_amount", BigDecimal.class));
        r.setDifference(row.get("difference", BigDecimal.class));
        r.setCurrencyCode(row.get("currency_code", String.class));
        r.setStatus(row.get("status", String.class));
        r.setNotes(row.get("notes", String.class));
        r.setStatementDate(row.get("statement_date", LocalDate.class));
        r.setReconciledAt(row.get("reconciled_at", Instant.class));
        r.setReconciledBy(row.get("reconciled_by", UUID.class));
        return r;
    }
}
