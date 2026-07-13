package com.medfund.finance.repository;

import com.medfund.finance.dto.CtcPaymentFilterParams;
import com.medfund.finance.dto.CtcPaymentRow;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Dynamic-SQL search powering the CTC-payments list (server-side sort +
 * pagination + join). Mirrors {@code ClaimQueryRepository} in
 * claims-service.
 *
 * <p>Joins {@code members} and {@code groups} into every row so the
 * client renders the beneficiary chip inline. Either FK is populated,
 * never both — {@code CreateCtcPaymentRequest} enforces XOR — so both
 * joins are {@code LEFT} and the frontend picks whichever column is
 * populated to render the row.
 *
 * <p>Sort safety: {@code sortKey} whitelist in {@link #SORT_COLUMNS};
 * anything else falls back to {@code created_at DESC}. Stable
 * tiebreaker on {@code c.id} keeps paging deterministic.
 */
@Repository
public class CtcPaymentQueryRepository {

    private static final Map<String, String> SORT_COLUMNS = Map.of(
            "amount",       "c.amount",
            "currencyCode", "c.currency_code",
            "committed",    "c.committed",
            "memberName",   "COALESCE(m.first_name || ' ' || m.last_name, '')",
            "groupName",    "COALESCE(g.name, '')",
            "createdAt",    "c.created_at"
    );

    private final DatabaseClient db;

    public CtcPaymentQueryRepository(DatabaseClient db) {
        this.db = db;
    }

    public Flux<CtcPaymentRow> search(CtcPaymentFilterParams f, int limit, int offset) {
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

    public Mono<Long> count(CtcPaymentFilterParams f) {
        boolean hasQ = f.q() != null && !f.q().isBlank();
        String search = hasQ ? "%" + f.q().toLowerCase() + "%" : null;

        String sql = "SELECT COUNT(*) AS total FROM ctc_payments c "
                + " LEFT JOIN members m ON m.id = c.member_id "
                + " LEFT JOIN groups  g ON g.id = c.group_id "
                + whereClause(f, hasQ);
        var spec = bindFilters(db.sql(sql), f, hasQ, search);
        return spec.map(row -> ((Number) row.get("total")).longValue()).one();
    }

    private String selectClause() {
        return """
                SELECT c.id, c.group_id, c.member_id, c.amount, c.currency_code,
                       c.contribution_id, c.committed, c.created_at, c.created_by,
                       COALESCE(m.first_name || ' ' || m.last_name, '') AS member_name,
                       m.member_number AS member_number,
                       g.name AS group_name
                """;
    }

    private String baseFrom() {
        return " FROM ctc_payments c "
             + " LEFT JOIN members m ON m.id = c.member_id "
             + " LEFT JOIN groups  g ON g.id = c.group_id ";
    }

    private String whereClause(CtcPaymentFilterParams f, boolean hasQ) {
        StringBuilder sb = new StringBuilder(" WHERE 1 = 1 ");
        if (f.committed() != null) {
            sb.append(" AND c.committed = :committed ");
        }
        if (f.currencyCode() != null && !f.currencyCode().isBlank()) {
            sb.append(" AND UPPER(c.currency_code) = UPPER(:currencyCode) ");
        }
        if (hasQ) {
            sb.append(" AND (LOWER(COALESCE(m.first_name || ' ' || m.last_name, '')) LIKE :search "
                   + "     OR LOWER(COALESCE(m.member_number, '')) LIKE :search "
                   + "     OR LOWER(COALESCE(g.name, '')) LIKE :search) ");
        }
        return sb.toString();
    }

    private DatabaseClient.GenericExecuteSpec bindFilters(DatabaseClient.GenericExecuteSpec spec,
                                                          CtcPaymentFilterParams f,
                                                          boolean hasQ,
                                                          String search) {
        if (f.committed() != null)                                       spec = spec.bind("committed", f.committed());
        if (f.currencyCode() != null && !f.currencyCode().isBlank())     spec = spec.bind("currencyCode", f.currencyCode());
        if (hasQ)                                                        spec = spec.bind("search", search);
        return spec;
    }

    private String sortClause(String sortKey, String sortDirection) {
        String col = SORT_COLUMNS.getOrDefault(sortKey, "c.created_at");
        String dir = "asc".equalsIgnoreCase(sortDirection) ? "ASC" : "DESC";
        return col + " " + dir + " NULLS LAST, c.id ASC";
    }

    private CtcPaymentRow toRow(io.r2dbc.spi.Readable row) {
        return new CtcPaymentRow(
                row.get("id", UUID.class),
                row.get("group_id", UUID.class),
                row.get("group_name", String.class),
                row.get("member_id", UUID.class),
                row.get("member_name", String.class),
                row.get("member_number", String.class),
                row.get("amount", BigDecimal.class),
                row.get("currency_code", String.class),
                row.get("contribution_id", UUID.class),
                row.get("committed", Boolean.class),
                row.get("created_at", Instant.class),
                row.get("created_by", UUID.class)
        );
    }
}
