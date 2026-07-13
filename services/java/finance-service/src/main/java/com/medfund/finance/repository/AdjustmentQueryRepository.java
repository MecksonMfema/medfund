package com.medfund.finance.repository;

import com.medfund.finance.dto.AdjustmentFilterParams;
import com.medfund.finance.dto.AdjustmentRow;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Dynamic-SQL search powering the adjustments list (server-side sort +
 * pagination + join). Mirrors {@code CtcPaymentQueryRepository}.
 *
 * <p>The tax-withheld page uses this via
 * {@code adjustmentType=TAX_WITHHELD}; the general adjustments page
 * omits the type filter to see everything. Either way, member + provider
 * names are joined in so the client never renders a raw UUID.
 */
@Repository
public class AdjustmentQueryRepository {

    private static final Map<String, String> SORT_COLUMNS = Map.of(
            "adjustmentNumber", "a.adjustment_number",
            "memberName",       "COALESCE(m.first_name || ' ' || m.last_name, '')",
            "providerName",     "COALESCE(p.name, '')",
            "adjustmentType",   "a.adjustment_type",
            "amount",           "a.amount",
            "currencyCode",     "a.currency_code",
            "status",           "a.status",
            "createdAt",        "a.created_at"
    );

    private final DatabaseClient db;

    public AdjustmentQueryRepository(DatabaseClient db) {
        this.db = db;
    }

    public Flux<AdjustmentRow> search(AdjustmentFilterParams f, int limit, int offset) {
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

    public Mono<Long> count(AdjustmentFilterParams f) {
        boolean hasQ = f.q() != null && !f.q().isBlank();
        String search = hasQ ? "%" + f.q().toLowerCase() + "%" : null;

        String sql = "SELECT COUNT(*) AS total FROM adjustments a "
                + " LEFT JOIN members   m ON m.id = a.member_id "
                + " LEFT JOIN providers p ON p.id = a.provider_id "
                + whereClause(f, hasQ);
        var spec = bindFilters(db.sql(sql), f, hasQ, search);
        return spec.map(row -> ((Number) row.get("total")).longValue()).one();
    }

    private String selectClause() {
        return """
                SELECT a.id, a.adjustment_number, a.provider_id, a.member_id,
                       a.adjustment_type, a.amount, a.currency_code, a.reason,
                       a.status, a.approved_by, a.approved_at,
                       a.created_at, a.updated_at, a.created_by,
                       COALESCE(m.first_name || ' ' || m.last_name, '') AS member_name,
                       m.member_number AS member_number,
                       p.name AS provider_name
                """;
    }

    private String baseFrom() {
        return " FROM adjustments a "
             + " LEFT JOIN members   m ON m.id = a.member_id "
             + " LEFT JOIN providers p ON p.id = a.provider_id ";
    }

    private String whereClause(AdjustmentFilterParams f, boolean hasQ) {
        StringBuilder sb = new StringBuilder(" WHERE 1 = 1 ");
        if (f.status() != null && !f.status().isBlank()) {
            sb.append(" AND LOWER(a.status) = LOWER(:status) ");
        }
        if (f.adjustmentType() != null && !f.adjustmentType().isBlank()) {
            sb.append(" AND UPPER(a.adjustment_type) = UPPER(:adjustmentType) ");
        }
        if (f.providerId() != null) sb.append(" AND a.provider_id = :providerId ");
        if (f.memberId() != null)   sb.append(" AND a.member_id = :memberId ");
        if (f.currencyCode() != null && !f.currencyCode().isBlank()) {
            sb.append(" AND UPPER(a.currency_code) = UPPER(:currencyCode) ");
        }
        if (hasQ) {
            sb.append(" AND (LOWER(a.adjustment_number) LIKE :search "
                   + "     OR LOWER(COALESCE(a.reason, '')) LIKE :search "
                   + "     OR LOWER(COALESCE(m.first_name || ' ' || m.last_name, '')) LIKE :search "
                   + "     OR LOWER(COALESCE(m.member_number, '')) LIKE :search "
                   + "     OR LOWER(COALESCE(p.name, '')) LIKE :search) ");
        }
        return sb.toString();
    }

    private DatabaseClient.GenericExecuteSpec bindFilters(DatabaseClient.GenericExecuteSpec spec,
                                                          AdjustmentFilterParams f,
                                                          boolean hasQ,
                                                          String search) {
        if (f.status() != null && !f.status().isBlank())                 spec = spec.bind("status", f.status());
        if (f.adjustmentType() != null && !f.adjustmentType().isBlank()) spec = spec.bind("adjustmentType", f.adjustmentType());
        if (f.providerId() != null)                                      spec = spec.bind("providerId", f.providerId());
        if (f.memberId() != null)                                        spec = spec.bind("memberId", f.memberId());
        if (f.currencyCode() != null && !f.currencyCode().isBlank())     spec = spec.bind("currencyCode", f.currencyCode());
        if (hasQ)                                                        spec = spec.bind("search", search);
        return spec;
    }

    private String sortClause(String sortKey, String sortDirection) {
        String col = SORT_COLUMNS.getOrDefault(sortKey, "a.created_at");
        String dir = "asc".equalsIgnoreCase(sortDirection) ? "ASC" : "DESC";
        return col + " " + dir + " NULLS LAST, a.id ASC";
    }

    private AdjustmentRow toRow(io.r2dbc.spi.Readable row) {
        return new AdjustmentRow(
                row.get("id", UUID.class),
                row.get("adjustment_number", String.class),
                row.get("provider_id", UUID.class),
                row.get("provider_name", String.class),
                row.get("member_id", UUID.class),
                row.get("member_name", String.class),
                row.get("member_number", String.class),
                row.get("adjustment_type", String.class),
                row.get("amount", BigDecimal.class),
                row.get("currency_code", String.class),
                row.get("reason", String.class),
                row.get("status", String.class),
                row.get("approved_by", UUID.class),
                row.get("approved_at", Instant.class),
                row.get("created_at", Instant.class),
                row.get("updated_at", Instant.class),
                row.get("created_by", UUID.class)
        );
    }
}
