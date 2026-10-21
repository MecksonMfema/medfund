package com.medfund.claims.repository;

import com.medfund.claims.dto.PreAuthorizationFilterParams;
import com.medfund.claims.dto.PreAuthorizationRow;
import com.medfund.shared.tenant.TenantContext;
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
 * Dynamic-SQL search powering the pre-authorizations list. Joins
 * {@code members} and {@code public.providers} so the client renders the
 * authorised member + provider names inline. Providers are platform-scoped,
 * so the join carries the {@code public.provider_tenants} membership guard
 * (CLAUDE.md Critical Rule 2) and a provider outside this tenant's network
 * renders blank rather than leaking a name.
 *
 * <p>Sort safety: whitelist in {@link #SORT_COLUMNS}; anything else
 * falls back to {@code created_at DESC}.
 */
@Repository
public class PreAuthorizationQueryRepository {

    private static final Map<String, String> SORT_COLUMNS = Map.of(
            "authNumber",     "pa.auth_number",
            "memberName",     "COALESCE(m.first_name || ' ' || m.last_name, '')",
            "providerName",   "COALESCE(p.name, '')",
            "tariffCode",     "pa.tariff_code",
            "status",         "pa.status",
            "requestedAmount", "pa.requested_amount",
            "approvedAmount", "pa.approved_amount",
            "requestedDate",  "pa.requested_date",
            "expiryDate",     "pa.expiry_date",
            "createdAt",      "pa.created_at"
    );

    /** LEFT JOIN onto the platform provider table, membership-guarded. */
    private static final String PROVIDER_LEFT_JOIN =
              " LEFT JOIN public.providers p ON p.id = pa.provider_id "
            + "   AND EXISTS (SELECT 1 FROM public.provider_tenants pt "
            + "                WHERE pt.provider_id = p.id AND pt.tenant_id = :tenantId) ";

    private final DatabaseClient db;

    public PreAuthorizationQueryRepository(DatabaseClient db) {
        this.db = db;
    }

    public Flux<PreAuthorizationRow> search(PreAuthorizationFilterParams f, int limit, int offset) {
        boolean hasQ = f.q() != null && !f.q().isBlank();
        String search = hasQ ? "%" + f.q().toLowerCase() + "%" : null;

        String sql = selectClause() + baseFrom() + whereClause(f, hasQ)
                + " ORDER BY " + sortClause(f.sortKey(), f.sortDirection())
                + " LIMIT :limit OFFSET :offset";
        return Flux.deferContextual(ctx -> bindFilters(db.sql(sql), f, hasQ, search)
                .bind("tenantId", TenantContext.requireUuid(ctx))
                .bind("limit", limit)
                .bind("offset", offset)
                .map(this::toRow).all());
    }

    public Mono<Long> count(PreAuthorizationFilterParams f) {
        boolean hasQ = f.q() != null && !f.q().isBlank();
        String search = hasQ ? "%" + f.q().toLowerCase() + "%" : null;

        String sql = "SELECT COUNT(*) AS total FROM pre_authorizations pa "
                + " LEFT JOIN members   m ON m.id = pa.member_id "
                + PROVIDER_LEFT_JOIN
                + whereClause(f, hasQ);
        return Mono.deferContextual(ctx -> bindFilters(db.sql(sql), f, hasQ, search)
                .bind("tenantId", TenantContext.requireUuid(ctx))
                .map(row -> ((Number) row.get("total")).longValue()).one());
    }

    private String selectClause() {
        return """
                SELECT pa.id, pa.auth_number, pa.member_id, pa.dependant_id,
                       pa.provider_id, pa.scheme_id, pa.tariff_code,
                       pa.diagnosis_code, pa.status, pa.requested_amount,
                       pa.approved_amount, pa.currency_code, pa.requested_date,
                       pa.decision_date, pa.expiry_date, pa.created_at,
                       COALESCE(m.first_name || ' ' || m.last_name, '') AS member_name,
                       m.member_number AS member_number,
                       p.name AS provider_name
                """;
    }

    private String baseFrom() {
        return " FROM pre_authorizations pa "
             + " LEFT JOIN members   m ON m.id = pa.member_id "
             + PROVIDER_LEFT_JOIN;
    }

    private String whereClause(PreAuthorizationFilterParams f, boolean hasQ) {
        StringBuilder sb = new StringBuilder(" WHERE 1 = 1 ");
        if (f.status() != null && !f.status().isBlank()) {
            sb.append(" AND LOWER(pa.status) = LOWER(:status) ");
        }
        if (f.memberId() != null)   sb.append(" AND pa.member_id = :memberId ");
        if (f.providerId() != null) sb.append(" AND pa.provider_id = :providerId ");
        if (f.schemeId() != null)   sb.append(" AND pa.scheme_id = :schemeId ");
        if (f.tariffCode() != null && !f.tariffCode().isBlank()) {
            sb.append(" AND UPPER(pa.tariff_code) = UPPER(:tariffCode) ");
        }
        if (hasQ) {
            sb.append(" AND (LOWER(pa.auth_number) LIKE :search "
                   + "     OR LOWER(COALESCE(pa.tariff_code, '')) LIKE :search "
                   + "     OR LOWER(COALESCE(pa.diagnosis_code, '')) LIKE :search "
                   + "     OR LOWER(COALESCE(m.first_name || ' ' || m.last_name, '')) LIKE :search "
                   + "     OR LOWER(COALESCE(m.member_number, '')) LIKE :search "
                   + "     OR LOWER(COALESCE(p.name, '')) LIKE :search) ");
        }
        return sb.toString();
    }

    private DatabaseClient.GenericExecuteSpec bindFilters(DatabaseClient.GenericExecuteSpec spec,
                                                          PreAuthorizationFilterParams f,
                                                          boolean hasQ,
                                                          String search) {
        if (f.status() != null && !f.status().isBlank())          spec = spec.bind("status", f.status());
        if (f.memberId() != null)                                 spec = spec.bind("memberId", f.memberId());
        if (f.providerId() != null)                               spec = spec.bind("providerId", f.providerId());
        if (f.schemeId() != null)                                 spec = spec.bind("schemeId", f.schemeId());
        if (f.tariffCode() != null && !f.tariffCode().isBlank())  spec = spec.bind("tariffCode", f.tariffCode());
        if (hasQ)                                                 spec = spec.bind("search", search);
        return spec;
    }

    private String sortClause(String sortKey, String sortDirection) {
        String col = SORT_COLUMNS.getOrDefault(sortKey, "pa.created_at");
        String dir = "asc".equalsIgnoreCase(sortDirection) ? "ASC" : "DESC";
        return col + " " + dir + " NULLS LAST, pa.id ASC";
    }

    private PreAuthorizationRow toRow(io.r2dbc.spi.Readable row) {
        return new PreAuthorizationRow(
                row.get("id", UUID.class),
                row.get("auth_number", String.class),
                row.get("member_id", UUID.class),
                row.get("member_name", String.class),
                row.get("member_number", String.class),
                row.get("dependant_id", UUID.class),
                row.get("provider_id", UUID.class),
                row.get("provider_name", String.class),
                row.get("scheme_id", UUID.class),
                row.get("tariff_code", String.class),
                row.get("diagnosis_code", String.class),
                row.get("status", String.class),
                row.get("requested_amount", BigDecimal.class),
                row.get("approved_amount", BigDecimal.class),
                row.get("currency_code", String.class),
                row.get("requested_date", LocalDate.class),
                row.get("decision_date", LocalDate.class),
                row.get("expiry_date", LocalDate.class),
                row.get("created_at", Instant.class)
        );
    }
}
