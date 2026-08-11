package com.medfund.finance.repository;

import com.medfund.finance.dto.NoteFilterParams;
import com.medfund.finance.dto.NoteRow;
import com.medfund.shared.report.PerCurrencyTotal;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Dynamic-SQL search powering the notes list (server-side sort +
 * pagination + join). Feeds all three route variants — /debit-notes
 * (direction=DEBIT), /credit-notes (direction=CREDIT), and /notes
 * (both). Member + provider names joined server-side so the client
 * never renders a raw UUID.
 */
@Repository
public class NoteQueryRepository {

    private static final Map<String, String> SORT_COLUMNS = Map.ofEntries(
            Map.entry("noteNumber",   "n.note_number"),
            Map.entry("memberName",   "COALESCE(m.first_name || ' ' || m.last_name, '')"),
            Map.entry("providerName", "COALESCE(p.name, '')"),
            Map.entry("direction",    "n.direction"),
            Map.entry("noteType",     "n.note_type"),
            Map.entry("amount",       "n.amount"),
            Map.entry("currencyCode", "n.currency_code"),
            Map.entry("status",       "n.status"),
            Map.entry("postedAt",     "n.posted_at"),
            Map.entry("createdAt",    "n.created_at")
    );

    private final DatabaseClient db;

    public NoteQueryRepository(DatabaseClient db) {
        this.db = db;
    }

    public Flux<NoteRow> search(NoteFilterParams f, int limit, int offset) {
        boolean hasQ = f.q() != null && !f.q().isBlank();
        String search = hasQ ? "%" + f.q().toLowerCase() + "%" : null;

        String sql = selectClause() + baseFrom() + whereClause(f, hasQ)
                + " ORDER BY " + sortClause(f.sortKey(), f.sortDirection())
                + " LIMIT :limit OFFSET :offset";
        var spec = bindFilters(db.sql(sql), f, hasQ, search)
                .bind("limit", limit)
                .bind("offset", offset);
        return spec.map(this::toRow).all();
    }

    public Mono<Long> count(NoteFilterParams f) {
        boolean hasQ = f.q() != null && !f.q().isBlank();
        String search = hasQ ? "%" + f.q().toLowerCase() + "%" : null;

        String sql = "SELECT COUNT(*) AS total FROM notes n "
                + " LEFT JOIN members   m ON m.id = n.member_id "
                + " LEFT JOIN providers p ON p.id = n.provider_id "
                + whereClause(f, hasQ);
        var spec = bindFilters(db.sql(sql), f, hasQ, search);
        return spec.map(row -> ((Number) row.get("total")).longValue()).one();
    }

    /**
     * Filtered-set per-currency totals (G18) — same WHERE clause as the
     * paged query, grouped by {@code n.currency_code}. Sums {@code n.amount}
     * across the filtered slice so the envelope's {@code perCurrency} map
     * matches what the caller sees on-page.
     */
    public Mono<Map<String, PerCurrencyTotal>> perCurrencyTotals(NoteFilterParams f) {
        boolean hasQ = f.q() != null && !f.q().isBlank();
        String search = hasQ ? "%" + f.q().toLowerCase() + "%" : null;
        String sql = "SELECT n.currency_code AS currency_code,"
                + "        COALESCE(SUM(n.amount), 0) AS total_amount,"
                + "        COUNT(*)                    AS row_count"
                + baseFrom()
                + whereClause(f, hasQ)
                + " AND n.currency_code IS NOT NULL"
                + " GROUP BY n.currency_code";
        var spec = bindFilters(db.sql(sql), f, hasQ, search);
        return spec.map((row, meta) -> Map.entry(
                        row.get("currency_code", String.class),
                        new PerCurrencyTotal(
                                nz(row.get("total_amount", BigDecimal.class)),
                                nzLong(row.get("row_count", Long.class)))))
                .all()
                .collectMap(Map.Entry::getKey, Map.Entry::getValue);
    }

    private static BigDecimal nz(BigDecimal v) { return v != null ? v : BigDecimal.ZERO; }
    private static long nzLong(Long v) { return v != null ? v : 0L; }

    private String selectClause() {
        return """
                SELECT n.id, n.note_number, n.provider_id, n.member_id,
                       n.direction, n.note_type, n.type, n.reverses_note_id,
                       n.amount, n.currency_code, n.reason,
                       n.status, n.approved_by, n.approved_at, n.posted_at,
                       n.created_at, n.updated_at, n.created_by,
                       COALESCE(m.first_name || ' ' || m.last_name, '') AS member_name,
                       m.member_number AS member_number,
                       p.name AS provider_name
                """;
    }

    private String baseFrom() {
        return " FROM notes n "
             + " LEFT JOIN members   m ON m.id = n.member_id "
             + " LEFT JOIN providers p ON p.id = n.provider_id ";
    }

    private String whereClause(NoteFilterParams f, boolean hasQ) {
        StringBuilder sb = new StringBuilder(" WHERE 1 = 1 ");
        if (f.status() != null && !f.status().isBlank()) {
            sb.append(" AND LOWER(n.status) = LOWER(:status) ");
        }
        if (f.direction() != null && !f.direction().isBlank()) {
            sb.append(" AND UPPER(n.direction) = UPPER(:direction) ");
        }
        if (f.noteType() != null && !f.noteType().isBlank()) {
            sb.append(" AND UPPER(n.note_type) = UPPER(:noteType) ");
        }
        if (f.providerId() != null) sb.append(" AND n.provider_id = :providerId ");
        if (f.memberId() != null)   sb.append(" AND n.member_id = :memberId ");
        if (f.currencyCode() != null && !f.currencyCode().isBlank()) {
            sb.append(" AND UPPER(n.currency_code) = UPPER(:currencyCode) ");
        }
        if (hasQ) {
            sb.append(" AND (LOWER(n.note_number) LIKE :search "
                   + "     OR LOWER(COALESCE(n.reason, '')) LIKE :search "
                   + "     OR LOWER(COALESCE(m.first_name || ' ' || m.last_name, '')) LIKE :search "
                   + "     OR LOWER(COALESCE(m.member_number, '')) LIKE :search "
                   + "     OR LOWER(COALESCE(p.name, '')) LIKE :search) ");
        }
        return sb.toString();
    }

    private DatabaseClient.GenericExecuteSpec bindFilters(DatabaseClient.GenericExecuteSpec spec,
                                                          NoteFilterParams f,
                                                          boolean hasQ,
                                                          String search) {
        if (f.status() != null && !f.status().isBlank())             spec = spec.bind("status", f.status());
        if (f.direction() != null && !f.direction().isBlank())       spec = spec.bind("direction", f.direction());
        if (f.noteType() != null && !f.noteType().isBlank())         spec = spec.bind("noteType", f.noteType());
        if (f.providerId() != null)                                  spec = spec.bind("providerId", f.providerId());
        if (f.memberId() != null)                                    spec = spec.bind("memberId", f.memberId());
        if (f.currencyCode() != null && !f.currencyCode().isBlank()) spec = spec.bind("currencyCode", f.currencyCode());
        if (hasQ)                                                    spec = spec.bind("search", search);
        return spec;
    }

    private String sortClause(String sortKey, String sortDirection) {
        String col = SORT_COLUMNS.getOrDefault(sortKey, "n.created_at");
        String dir = "asc".equalsIgnoreCase(sortDirection) ? "ASC" : "DESC";
        return col + " " + dir + " NULLS LAST, n.id ASC";
    }

    private NoteRow toRow(io.r2dbc.spi.Readable row) {
        return new NoteRow(
                row.get("id", UUID.class),
                row.get("note_number", String.class),
                row.get("provider_id", UUID.class),
                row.get("provider_name", String.class),
                row.get("member_id", UUID.class),
                row.get("member_name", String.class),
                row.get("member_number", String.class),
                row.get("direction", String.class),
                row.get("note_type", String.class),
                row.get("type", String.class),
                row.get("reverses_note_id", UUID.class),
                row.get("amount", BigDecimal.class),
                row.get("currency_code", String.class),
                row.get("reason", String.class),
                row.get("status", String.class),
                row.get("approved_by", UUID.class),
                row.get("approved_at", Instant.class),
                row.get("posted_at", Instant.class),
                row.get("created_at", Instant.class),
                row.get("updated_at", Instant.class),
                row.get("created_by", UUID.class)
        );
    }
}
