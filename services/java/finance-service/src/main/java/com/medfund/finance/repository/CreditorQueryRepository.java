package com.medfund.finance.repository;

import com.medfund.finance.dto.CreditorFilterParams;
import com.medfund.finance.dto.CreditorRow;
import com.medfund.shared.report.PerCurrencyTotal;
import com.medfund.shared.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.context.ContextView;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Dynamic-SQL search behind GET /api/v1/creditors/page. Unions
 * {@code provider_balances} + {@code member_balances} — either subject
 * type can appear on the same page depending on {@code subjectType}
 * (PROVIDER | MEMBER | BOTH). Both halves project the same 11 columns
 * so the SELECT list is uniform and sortable.
 *
 * <p>Members share nothing structural with providers, so the join /
 * search predicate differs per branch; each half owns its own WHERE
 * fragment which is stitched together with UNION ALL. All binds are
 * shared across both halves so the parameter names stay stable.
 *
 * <p>Providers are platform-scoped, so the provider half joins
 * {@code public.providers} gated on a {@code public.provider_tenants}
 * membership row for the current tenant (see {@link ProviderJoins}). That
 * guard is the only thing binding {@code :tenantId}, so the bind is applied
 * only when the provider branch is actually part of the union: binding a
 * parameter that does not appear in the SQL is an error.
 */
@Repository
@RequiredArgsConstructor
public class CreditorQueryRepository {

    private static final Map<String, String> SORT_COLUMNS = Map.of(
            "subjectName",        "subject_name",
            "subjectCode",        "subject_code",
            "totalClaimed",       "total_claimed",
            "totalApproved",      "total_approved",
            "totalPaid",          "total_paid",
            "outstandingBalance", "outstanding_balance",
            "currencyCode",       "currency_code",
            "lastActivityAt",     "last_activity_at"
    );

    private final DatabaseClient db;

    public Flux<CreditorRow> search(CreditorFilterParams f, int limit, int offset) {
        String union = buildUnion(f);
        String sql = "SELECT * FROM (" + union + ") u"
                + " ORDER BY " + sortClause(f.sortKey(), f.sortDirection())
                + " LIMIT :limit OFFSET :offset";
        return Flux.deferContextual(ctx -> bindFilters(db.sql(sql), f, ctx)
                .bind("limit", limit)
                .bind("offset", offset)
                .map(this::toRow).all());
    }

    public Mono<Long> count(CreditorFilterParams f) {
        String union = buildUnion(f);
        String sql = "SELECT COUNT(*) AS total FROM (" + union + ") u";
        return Mono.deferContextual(ctx -> bindFilters(db.sql(sql), f, ctx)
                .map(row -> ((Number) row.get("total")).longValue()).one());
    }

    /**
     * Filtered-set per-currency totals (G18) — same WHERE clause as the
     * paged query, grouped by currency. Sums {@code outstanding_balance}
     * across both branches so the envelope's {@code perCurrency} map
     * reflects the same slice the caller sees on-page.
     */
    public Mono<Map<String, PerCurrencyTotal>> perCurrencyTotals(CreditorFilterParams f) {
        String union = buildUnion(f);
        String sql = "SELECT currency_code,"
                + "        COALESCE(SUM(outstanding_balance), 0) AS total_amount,"
                + "        COUNT(*)                                AS row_count"
                + "   FROM (" + union + ") u"
                + "  WHERE currency_code IS NOT NULL"
                + "  GROUP BY currency_code";
        return Mono.deferContextual(ctx -> bindFilters(db.sql(sql), f, ctx)
                .map((row, meta) -> Map.entry(
                        row.get("currency_code", String.class),
                        new PerCurrencyTotal(
                                nz(row.get("total_amount", BigDecimal.class)),
                                nzLong(row.get("row_count", Long.class)))))
                .all()
                .collectMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    private static BigDecimal nz(BigDecimal v) { return v != null ? v : BigDecimal.ZERO; }
    private static long nzLong(Long v) { return v != null ? v : 0L; }

    /** True when the union carries the provider half, and therefore {@code :tenantId}. */
    private static boolean includesProviders(CreditorFilterParams f) {
        String subjectType = f.subjectType() == null ? "BOTH" : f.subjectType().toUpperCase();
        return "PROVIDER".equals(subjectType) || "BOTH".equals(subjectType);
    }

    private String buildUnion(CreditorFilterParams f) {
        String subjectType = f.subjectType() == null ? "BOTH" : f.subjectType().toUpperCase();
        boolean incProvider = includesProviders(f);
        boolean incMember   = "MEMBER".equals(subjectType)   || "BOTH".equals(subjectType);
        List<String> parts = new ArrayList<>();
        if (incProvider) parts.add(providerBranch(f));
        if (incMember)   parts.add(memberBranch(f));
        // Empty union — return a query yielding no rows. Would only fire on
        // an unrecognised subjectType like "" or "foo"; keep the response
        // shape valid instead of erroring.
        if (parts.isEmpty()) {
            return "SELECT 'PROVIDER'::text AS subject_type, gen_random_uuid() AS subject_id,"
                    + " NULL::text AS subject_code, NULL::text AS subject_name, NULL::text AS subject_email,"
                    + " NULL::text AS currency_code, 0::numeric AS total_claimed, 0::numeric AS total_approved,"
                    + " 0::numeric AS total_paid, 0::numeric AS outstanding_balance,"
                    + " NULL::timestamptz AS last_activity_at WHERE FALSE";
        }
        return String.join(" UNION ALL ", parts);
    }

    private String providerBranch(CreditorFilterParams f) {
        StringBuilder where = new StringBuilder(" WHERE 1=1 ");
        if (f.currencyCode() != null && !f.currencyCode().isBlank()) {
            where.append(" AND UPPER(b.currency_code) = UPPER(:currencyCode) ");
        }
        if (f.q() != null && !f.q().isBlank()) {
            where.append(" AND (LOWER(COALESCE(pr.name, '')) LIKE :qLower "
                    + " OR LOWER(COALESCE(pr.registration_number, '')) LIKE :qLower) ");
        }
        return "SELECT 'PROVIDER' AS subject_type,"
                + "       b.provider_id AS subject_id,"
                + "       pr.registration_number AS subject_code,"
                + "       pr.name       AS subject_name,"
                + "       pr.email      AS subject_email,"
                + "       b.currency_code AS currency_code,"
                + "       b.total_claimed, b.total_approved, b.total_paid, b.outstanding_balance,"
                + "       b.last_updated_at AS last_activity_at"
                + "  FROM provider_balances b"
                + ProviderJoins.leftJoin("pr", "b.provider_id")
                + where;
    }

    private String memberBranch(CreditorFilterParams f) {
        StringBuilder where = new StringBuilder(" WHERE 1=1 ");
        if (f.currencyCode() != null && !f.currencyCode().isBlank()) {
            where.append(" AND UPPER(b.currency_code) = UPPER(:currencyCode) ");
        }
        if (f.q() != null && !f.q().isBlank()) {
            where.append(" AND (LOWER(COALESCE(m.first_name, '') || ' ' || COALESCE(m.last_name, '')) LIKE :qLower "
                    + " OR LOWER(COALESCE(m.member_number, '')) LIKE :qLower "
                    + " OR LOWER(COALESCE(m.email, '')) LIKE :qLower) ");
        }
        return "SELECT 'MEMBER' AS subject_type,"
                + "       b.member_id AS subject_id,"
                + "       m.member_number AS subject_code,"
                + "       CONCAT_WS(' ', m.first_name, m.last_name) AS subject_name,"
                + "       m.email AS subject_email,"
                + "       b.currency_code AS currency_code,"
                + "       b.total_claimed, b.total_approved, b.total_paid, b.outstanding_balance,"
                + "       b.last_updated_at AS last_activity_at"
                + "  FROM member_balances b"
                + "  LEFT JOIN members m ON m.id = b.member_id"
                + where;
    }

    private DatabaseClient.GenericExecuteSpec bindFilters(DatabaseClient.GenericExecuteSpec spec,
                                                          CreditorFilterParams f,
                                                          ContextView ctx) {
        if (f.currencyCode() != null && !f.currencyCode().isBlank()) {
            spec = spec.bind("currencyCode", f.currencyCode());
        }
        if (f.q() != null && !f.q().isBlank()) {
            spec = spec.bind("qLower", "%" + f.q().toLowerCase() + "%");
        }
        if (includesProviders(f)) {
            spec = spec.bind("tenantId", TenantContext.requireUuid(ctx));
        }
        return spec;
    }

    private String sortClause(String sortKey, String sortDirection) {
        String col = SORT_COLUMNS.getOrDefault(sortKey, "outstanding_balance");
        String dir = "asc".equalsIgnoreCase(sortDirection) ? "ASC" : "DESC";
        return col + " " + dir + " NULLS LAST, subject_id ASC";
    }

    private CreditorRow toRow(io.r2dbc.spi.Readable row) {
        return new CreditorRow(
                row.get("subject_type",         String.class),
                row.get("subject_id",           UUID.class),
                row.get("subject_code",         String.class),
                row.get("subject_name",         String.class),
                row.get("subject_email",        String.class),
                row.get("currency_code",        String.class),
                row.get("total_claimed",        BigDecimal.class),
                row.get("total_approved",       BigDecimal.class),
                row.get("total_paid",           BigDecimal.class),
                row.get("outstanding_balance",  BigDecimal.class),
                row.get("last_activity_at",     Instant.class)
        );
    }
}
