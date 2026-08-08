package com.medfund.claims.repository;

import com.medfund.claims.dto.ClaimFilterParams;
import com.medfund.claims.dto.ClaimRow;
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
 * Dynamic-SQL search powering the claims list (server-side sort +
 * pagination + join). Mirrors {@code TariffCodeQueryRepository}.
 *
 * <p>Joins {@code members} and {@code providers} into every row so the
 * client renders member + provider names inline without a second call.
 * Providers are optional on some insurance lines (member-reimbursed
 * flows), so the join is {@code LEFT} — provider_name comes back null
 * when there's no provider.
 *
 * <p>Sort safety: {@code sortKey} from the HTTP request is translated
 * to a column name through {@link #SORT_COLUMNS}. Anything not in the
 * whitelist falls back to {@code submission_date DESC}. A stable
 * tiebreaker on {@code c.id} keeps paging deterministic when two rows
 * share the sort value.
 */
@Repository
public class ClaimQueryRepository {

    private static final Map<String, String> SORT_COLUMNS = Map.of(
            "claimNumber",    "c.claim_number",
            "memberName",     "COALESCE(m.first_name || ' ' || m.last_name, '')",
            "providerName",   "COALESCE(p.name, '')",
            "status",         "c.status",
            "claimType",      "c.claim_type",
            "insuranceLine",  "c.insurance_line",
            "serviceDate",    "c.service_date",
            "submissionDate", "c.submission_date",
            "claimedAmount",  "c.claimed_amount",
            "approvedAmount", "c.approved_amount"
    );

    private final DatabaseClient db;

    public ClaimQueryRepository(DatabaseClient db) {
        this.db = db;
    }

    public Flux<ClaimRow> search(ClaimFilterParams f, int limit, int offset) {
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

    public Mono<Long> count(ClaimFilterParams f) {
        boolean hasQ = f.q() != null && !f.q().isBlank();
        String search = hasQ ? "%" + f.q().toLowerCase() + "%" : null;

        String sql = "SELECT COUNT(*) AS total FROM claims c "
                + " LEFT JOIN members m   ON m.id = c.member_id "
                + " LEFT JOIN providers p ON p.id = c.provider_id "
                + whereClause(f, hasQ);
        var spec = bindFilters(db.sql(sql), f, hasQ, search);
        return spec.map(row -> ((Number) row.get("total")).longValue()).one();
    }

    private String selectClause() {
        return """
                SELECT c.id, c.claim_number, c.member_id, c.dependant_id,
                       c.provider_id, c.payee_type, c.scheme_id, c.claim_type,
                       c.insurance_line, c.status, c.service_date,
                       c.submission_date, c.claimed_amount, c.approved_amount,
                       c.currency_code, c.batch_number, c.created_at,
                       COALESCE(m.first_name || ' ' || m.last_name, '') AS member_name,
                       m.member_number AS member_number,
                       p.name AS provider_name
                """;
    }

    private String baseFrom() {
        return " FROM claims c "
             + " LEFT JOIN members   m ON m.id = c.member_id "
             + " LEFT JOIN providers p ON p.id = c.provider_id ";
    }

    private String whereClause(ClaimFilterParams f, boolean hasQ) {
        StringBuilder sb = new StringBuilder(" WHERE 1 = 1 ");
        if (f.status() != null && !f.status().isBlank()) {
            sb.append(" AND UPPER(c.status) = UPPER(:status) ");
        }
        if (f.claimType() != null && !f.claimType().isBlank()) {
            sb.append(" AND LOWER(c.claim_type) = LOWER(:claimType) ");
        }
        if (f.insuranceLine() != null && !f.insuranceLine().isBlank()) {
            sb.append(" AND UPPER(c.insurance_line) = UPPER(:insuranceLine) ");
        }
        if (f.memberId() != null)   sb.append(" AND c.member_id = :memberId ");
        if (f.providerId() != null) sb.append(" AND c.provider_id = :providerId ");
        if (f.schemeId() != null)   sb.append(" AND c.scheme_id = :schemeId ");
        if (hasQ) {
            sb.append(" AND (LOWER(c.claim_number) LIKE :search "
                   + "     OR LOWER(COALESCE(m.first_name || ' ' || m.last_name, '')) LIKE :search "
                   + "     OR LOWER(COALESCE(m.member_number, '')) LIKE :search "
                   + "     OR LOWER(COALESCE(p.name, '')) LIKE :search) ");
        }
        return sb.toString();
    }

    private DatabaseClient.GenericExecuteSpec bindFilters(DatabaseClient.GenericExecuteSpec spec,
                                                          ClaimFilterParams f,
                                                          boolean hasQ,
                                                          String search) {
        if (f.status() != null && !f.status().isBlank())               spec = spec.bind("status", f.status());
        if (f.claimType() != null && !f.claimType().isBlank())         spec = spec.bind("claimType", f.claimType());
        if (f.insuranceLine() != null && !f.insuranceLine().isBlank()) spec = spec.bind("insuranceLine", f.insuranceLine());
        if (f.memberId() != null)   spec = spec.bind("memberId", f.memberId());
        if (f.providerId() != null) spec = spec.bind("providerId", f.providerId());
        if (f.schemeId() != null)   spec = spec.bind("schemeId", f.schemeId());
        if (hasQ)                   spec = spec.bind("search", search);
        return spec;
    }

    private String sortClause(String sortKey, String sortDirection) {
        String col = SORT_COLUMNS.getOrDefault(sortKey, "c.submission_date");
        String dir = "asc".equalsIgnoreCase(sortDirection) ? "ASC" : "DESC";
        return col + " " + dir + " NULLS LAST, c.id ASC";
    }

    private ClaimRow toRow(io.r2dbc.spi.Readable row) {
        return new ClaimRow(
                row.get("id", UUID.class),
                row.get("claim_number", String.class),
                row.get("member_id", UUID.class),
                row.get("member_name", String.class),
                row.get("member_number", String.class),
                row.get("dependant_id", UUID.class),
                row.get("provider_id", UUID.class),
                row.get("provider_name", String.class),
                row.get("payee_type", String.class),
                row.get("scheme_id", UUID.class),
                row.get("claim_type", String.class),
                row.get("insurance_line", String.class),
                row.get("status", String.class),
                row.get("service_date", LocalDate.class),
                row.get("submission_date", Instant.class),
                row.get("claimed_amount", BigDecimal.class),
                row.get("approved_amount", BigDecimal.class),
                row.get("currency_code", String.class),
                row.get("batch_number", String.class),
                row.get("created_at", Instant.class)
        );
    }
}
