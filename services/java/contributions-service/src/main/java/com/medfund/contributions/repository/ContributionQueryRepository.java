package com.medfund.contributions.repository;

import com.medfund.contributions.dto.ContributionFilterParams;
import com.medfund.contributions.dto.ContributionRow;
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
 * Dynamic-SQL search powering the contribution-statements list
 * (server-side sort + pagination + join). Mirrors
 * {@link SchemeQueryRepository}.
 *
 * <p>Joins {@code members}, {@code groups}, and {@code schemes} into
 * every row so the operational statements table renders the
 * beneficiary + product chips inline without a second lookup.
 *
 * <p>Sort safety: whitelist in {@link #SORT_COLUMNS}; anything else
 * falls back to {@code period_start DESC}. Stable tiebreaker on
 * {@code c.id} keeps paging deterministic.
 */
@Repository
public class ContributionQueryRepository {

    private static final Map<String, String> SORT_COLUMNS = Map.of(
            "memberName",   "COALESCE(m.first_name || ' ' || m.last_name, '')",
            "groupName",    "COALESCE(g.name, '')",
            "schemeName",   "COALESCE(s.name, '')",
            "amount",       "c.amount",
            "currencyCode", "c.currency_code",
            "status",       "c.status",
            "periodStart",  "c.period_start",
            "periodEnd",    "c.period_end",
            "paidAt",       "c.paid_at",
            "createdAt",    "c.created_at"
    );

    private final DatabaseClient db;

    public ContributionQueryRepository(DatabaseClient db) {
        this.db = db;
    }

    public Flux<ContributionRow> search(ContributionFilterParams f, int limit, int offset) {
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

    public Mono<Long> count(ContributionFilterParams f) {
        boolean hasQ = f.q() != null && !f.q().isBlank();
        String search = hasQ ? "%" + f.q().toLowerCase() + "%" : null;

        String sql = "SELECT COUNT(*) AS total FROM contributions c "
                + " LEFT JOIN members m ON m.id = c.member_id "
                + " LEFT JOIN groups  g ON g.id = c.group_id "
                + " LEFT JOIN schemes s ON s.id = c.scheme_id "
                + whereClause(f, hasQ);
        var spec = bindFilters(db.sql(sql), f, hasQ, search);
        return spec.map(row -> ((Number) row.get("total")).longValue()).one();
    }

    private String selectClause() {
        return """
                SELECT c.id, c.member_id, c.group_id, c.scheme_id, c.amount,
                       c.currency_code, c.period_start, c.period_end, c.status,
                       c.payment_method, c.payment_reference, c.paid_at,
                       c.created_at, c.updated_at,
                       COALESCE(m.first_name || ' ' || m.last_name, '') AS member_name,
                       m.member_number AS member_number,
                       g.name AS group_name,
                       s.name AS scheme_name
                """;
    }

    private String baseFrom() {
        return " FROM contributions c "
             + " LEFT JOIN members m ON m.id = c.member_id "
             + " LEFT JOIN groups  g ON g.id = c.group_id "
             + " LEFT JOIN schemes s ON s.id = c.scheme_id ";
    }

    private String whereClause(ContributionFilterParams f, boolean hasQ) {
        StringBuilder sb = new StringBuilder(" WHERE 1 = 1 ");
        if (f.status() != null && !f.status().isBlank()) {
            sb.append(" AND LOWER(c.status) = LOWER(:status) ");
        }
        if (f.memberId() != null)  sb.append(" AND c.member_id = :memberId ");
        if (f.groupId() != null)   sb.append(" AND c.group_id = :groupId ");
        if (f.schemeId() != null)  sb.append(" AND c.scheme_id = :schemeId ");
        if (f.currencyCode() != null && !f.currencyCode().isBlank()) {
            sb.append(" AND UPPER(c.currency_code) = UPPER(:currencyCode) ");
        }
        if (f.periodStartFrom() != null) sb.append(" AND c.period_start >= :periodStartFrom ");
        if (f.periodStartTo() != null)   sb.append(" AND c.period_start <= :periodStartTo ");
        if (hasQ) {
            sb.append(" AND (LOWER(COALESCE(m.first_name || ' ' || m.last_name, '')) LIKE :search "
                   + "     OR LOWER(COALESCE(m.member_number, '')) LIKE :search "
                   + "     OR LOWER(COALESCE(g.name, '')) LIKE :search "
                   + "     OR LOWER(COALESCE(s.name, '')) LIKE :search "
                   + "     OR LOWER(COALESCE(c.payment_reference, '')) LIKE :search) ");
        }
        return sb.toString();
    }

    private DatabaseClient.GenericExecuteSpec bindFilters(DatabaseClient.GenericExecuteSpec spec,
                                                          ContributionFilterParams f,
                                                          boolean hasQ,
                                                          String search) {
        if (f.status() != null && !f.status().isBlank())               spec = spec.bind("status", f.status());
        if (f.memberId() != null)                                      spec = spec.bind("memberId", f.memberId());
        if (f.groupId() != null)                                       spec = spec.bind("groupId", f.groupId());
        if (f.schemeId() != null)                                      spec = spec.bind("schemeId", f.schemeId());
        if (f.currencyCode() != null && !f.currencyCode().isBlank())   spec = spec.bind("currencyCode", f.currencyCode());
        if (f.periodStartFrom() != null)                               spec = spec.bind("periodStartFrom", f.periodStartFrom());
        if (f.periodStartTo() != null)                                 spec = spec.bind("periodStartTo", f.periodStartTo());
        if (hasQ)                                                      spec = spec.bind("search", search);
        return spec;
    }

    private String sortClause(String sortKey, String sortDirection) {
        String col = SORT_COLUMNS.getOrDefault(sortKey, "c.period_start");
        String dir = "asc".equalsIgnoreCase(sortDirection) ? "ASC" : "DESC";
        return col + " " + dir + " NULLS LAST, c.id ASC";
    }

    private ContributionRow toRow(io.r2dbc.spi.Readable row) {
        return new ContributionRow(
                row.get("id", UUID.class),
                row.get("member_id", UUID.class),
                row.get("member_name", String.class),
                row.get("member_number", String.class),
                row.get("group_id", UUID.class),
                row.get("group_name", String.class),
                row.get("scheme_id", UUID.class),
                row.get("scheme_name", String.class),
                row.get("amount", BigDecimal.class),
                row.get("currency_code", String.class),
                row.get("period_start", LocalDate.class),
                row.get("period_end", LocalDate.class),
                row.get("status", String.class),
                row.get("payment_method", String.class),
                row.get("payment_reference", String.class),
                row.get("paid_at", Instant.class),
                row.get("created_at", Instant.class),
                row.get("updated_at", Instant.class)
        );
    }
}
