package com.medfund.finance.repository;

import com.medfund.finance.dto.MemberCostShareLiabilityRow;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Dynamic-SQL search powering the member-liabilities list. Joins members so
 * the operational table renders a friendly name + member number without a
 * follow-up per-row lookup.
 */
@Repository
public class MemberCostShareLiabilityQueryRepository {

    private static final Map<String, String> SORT_COLUMNS = Map.of(
            "memberName",      "COALESCE(m.first_name || ' ' || m.last_name, '')",
            "memberNumber",    "m.member_number",
            "claimNumber",     "l.claim_number",
            "totalOwed",       "l.total_owed",
            "totalSettled",    "l.total_settled",
            "currencyCode",    "l.currency_code",
            "status",          "l.status",
            "createdAt",       "l.created_at",
            "updatedAt",       "l.updated_at"
    );

    private final DatabaseClient db;

    public MemberCostShareLiabilityQueryRepository(DatabaseClient db) {
        this.db = db;
    }

    public Flux<MemberCostShareLiabilityRow> search(String status, String currencyCode, UUID memberId,
                                                     String q, String sortKey, String sortDirection,
                                                     int limit, int offset) {
        boolean hasQ = q != null && !q.isBlank();
        String search = hasQ ? "%" + q.toLowerCase() + "%" : null;

        String sql = """
                SELECT l.id, l.member_id, l.claim_id, l.claim_number,
                       l.total_owed, l.total_settled, l.currency_code,
                       l.currency_code_original, l.status,
                       l.created_at, l.updated_at,
                       (m.first_name || ' ' || m.last_name) AS member_name,
                       m.member_number AS member_number
                  FROM member_cost_share_liability l
                  LEFT JOIN members m ON m.id = l.member_id
                """
                + whereClause(status, currencyCode, memberId, hasQ)
                + " ORDER BY " + sortClause(sortKey, sortDirection)
                + " LIMIT :limit OFFSET :offset";
        var spec = bindFilters(db.sql(sql), status, currencyCode, memberId, hasQ, search)
                .bind("limit", limit)
                .bind("offset", offset);
        return spec.map(this::toRow).all();
    }

    public Mono<Long> count(String status, String currencyCode, UUID memberId, String q) {
        boolean hasQ = q != null && !q.isBlank();
        String search = hasQ ? "%" + q.toLowerCase() + "%" : null;
        String sql = """
                SELECT COUNT(*) AS total FROM member_cost_share_liability l
                LEFT JOIN members m ON m.id = l.member_id
                """
                + whereClause(status, currencyCode, memberId, hasQ);
        var spec = bindFilters(db.sql(sql), status, currencyCode, memberId, hasQ, search);
        return spec.map(row -> ((Number) row.get("total")).longValue()).one();
    }

    private String whereClause(String status, String currencyCode, UUID memberId, boolean hasQ) {
        StringBuilder sb = new StringBuilder(" WHERE 1 = 1 ");
        if (status != null && !status.isBlank()) sb.append(" AND l.status = :status ");
        if (currencyCode != null && !currencyCode.isBlank())
            sb.append(" AND UPPER(l.currency_code) = UPPER(:currencyCode) ");
        if (memberId != null) sb.append(" AND l.member_id = :memberId ");
        if (hasQ) sb.append(" AND (LOWER(COALESCE(m.first_name || ' ' || m.last_name, '')) LIKE :search "
                + "     OR LOWER(COALESCE(m.member_number, '')) LIKE :search "
                + "     OR LOWER(COALESCE(l.claim_number, '')) LIKE :search) ");
        return sb.toString();
    }

    private DatabaseClient.GenericExecuteSpec bindFilters(DatabaseClient.GenericExecuteSpec spec,
                                                          String status, String currencyCode,
                                                          UUID memberId, boolean hasQ, String search) {
        if (status != null && !status.isBlank()) spec = spec.bind("status", status);
        if (currencyCode != null && !currencyCode.isBlank()) spec = spec.bind("currencyCode", currencyCode);
        if (memberId != null) spec = spec.bind("memberId", memberId);
        if (hasQ) spec = spec.bind("search", search);
        return spec;
    }

    private String sortClause(String sortKey, String sortDirection) {
        String col = SORT_COLUMNS.getOrDefault(sortKey, "l.created_at");
        String dir = "asc".equalsIgnoreCase(sortDirection) ? "ASC" : "DESC";
        return col + " " + dir + " NULLS LAST, l.id ASC";
    }

    private MemberCostShareLiabilityRow toRow(io.r2dbc.spi.Readable row) {
        return new MemberCostShareLiabilityRow(
                row.get("id", UUID.class),
                row.get("member_id", UUID.class),
                row.get("member_name", String.class),
                row.get("member_number", String.class),
                row.get("claim_id", UUID.class),
                row.get("claim_number", String.class),
                row.get("total_owed", BigDecimal.class),
                row.get("total_settled", BigDecimal.class),
                row.get("currency_code", String.class),
                row.get("currency_code_original", String.class),
                row.get("status", String.class),
                row.get("created_at", Instant.class),
                row.get("updated_at", Instant.class));
    }
}
