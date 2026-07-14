package com.medfund.finance.repository;

import com.medfund.finance.dto.NoteFilterParams;
import com.medfund.finance.dto.NoteDtos.NoteResponse;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Dynamic-SQL search powering the debit/credit-notes lists. Two
 * separate tables share this repository — the {@code table} parameter
 * picks which one to hit; the row shape is identical.
 */
@Repository
public class NoteQueryRepository {

    public enum NoteTable {
        DEBIT("debit_notes"),
        CREDIT("credit_notes");
        final String table;
        NoteTable(String table) { this.table = table; }
    }

    private static final Map<String, String> SORT_COLUMNS = Map.of(
            "amount",       "amount",
            "currencyCode", "currency_code",
            "reference",    "reference",
            "createdAt",    "created_at"
    );

    private final DatabaseClient db;

    public NoteQueryRepository(DatabaseClient db) {
        this.db = db;
    }

    public Flux<NoteResponse> search(NoteTable table, NoteFilterParams f, int limit, int offset) {
        boolean hasQ = f.q() != null && !f.q().isBlank();
        String search = hasQ ? "%" + f.q().toLowerCase() + "%" : null;

        String sql = "SELECT id, amount, currency_code, reference, task_id, notes, created_at, created_by "
                + "  FROM " + table.table
                + whereClause(f, hasQ)
                + " ORDER BY " + sortClause(f.sortKey(), f.sortDirection())
                + " LIMIT :limit OFFSET :offset";
        var spec = bindFilters(db.sql(sql), f, hasQ, search)
                .bind("limit", limit)
                .bind("offset", offset);
        return spec.map(this::toRow).all();
    }

    public Mono<Long> count(NoteTable table, NoteFilterParams f) {
        boolean hasQ = f.q() != null && !f.q().isBlank();
        String search = hasQ ? "%" + f.q().toLowerCase() + "%" : null;

        String sql = "SELECT COUNT(*) AS total FROM " + table.table + whereClause(f, hasQ);
        var spec = bindFilters(db.sql(sql), f, hasQ, search);
        return spec.map(row -> ((Number) row.get("total")).longValue()).one();
    }

    private String whereClause(NoteFilterParams f, boolean hasQ) {
        StringBuilder sb = new StringBuilder(" WHERE 1 = 1 ");
        if (f.currencyCode() != null && !f.currencyCode().isBlank()) {
            sb.append(" AND UPPER(currency_code) = UPPER(:currencyCode) ");
        }
        if (hasQ) {
            sb.append(" AND (LOWER(COALESCE(reference, '')) LIKE :search "
                   + "     OR LOWER(COALESCE(notes, '')) LIKE :search) ");
        }
        return sb.toString();
    }

    private DatabaseClient.GenericExecuteSpec bindFilters(DatabaseClient.GenericExecuteSpec spec,
                                                          NoteFilterParams f,
                                                          boolean hasQ,
                                                          String search) {
        if (f.currencyCode() != null && !f.currencyCode().isBlank()) {
            spec = spec.bind("currencyCode", f.currencyCode());
        }
        if (hasQ) spec = spec.bind("search", search);
        return spec;
    }

    private String sortClause(String sortKey, String sortDirection) {
        String col = SORT_COLUMNS.getOrDefault(sortKey, "created_at");
        String dir = "asc".equalsIgnoreCase(sortDirection) ? "ASC" : "DESC";
        return col + " " + dir + " NULLS LAST, id ASC";
    }

    private NoteResponse toRow(io.r2dbc.spi.Readable row) {
        return new NoteResponse(
                row.get("id", UUID.class),
                row.get("amount", BigDecimal.class),
                row.get("currency_code", String.class),
                row.get("reference", String.class),
                row.get("task_id", UUID.class),
                row.get("notes", String.class),
                row.get("created_at", Instant.class),
                row.get("created_by", UUID.class)
        );
    }
}
