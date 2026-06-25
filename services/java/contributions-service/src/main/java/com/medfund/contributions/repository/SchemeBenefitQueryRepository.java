package com.medfund.contributions.repository;

import com.medfund.contributions.dto.SchemeBenefitFilterParams;
import com.medfund.contributions.entity.SchemeBenefit;
import lombok.RequiredArgsConstructor;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Dynamic-SQL search powering the operational benefits list (server-side
 * sort + pagination + free-text search). Mirrors {@link SchemeQueryRepository}.
 *
 * <p>Sort safety: {@code sortKey} from the HTTP request is translated to a
 * column name through {@link #SORT_COLUMNS} — anything not in that map falls
 * back to {@code name}. Direction is forced to {@code ASC} or {@code DESC}.
 * A trailing {@code id ASC} keeps the order deterministic across pages when
 * many rows share the same sort value.
 */
@Repository
@RequiredArgsConstructor
public class SchemeBenefitQueryRepository {

    private static final Map<String, String> SORT_COLUMNS = Map.of(
            "name",              "name",
            "benefitType",       "benefit_type",
            "status",            "status",
            "waitingPeriodDays", "waiting_period_days",
            "annualLimit",       "annual_limit",
            "dailyLimit",        "daily_limit",
            "eventLimit",        "event_limit",
            "createdAt",         "created_at"
    );

    private final DatabaseClient db;

    public Flux<SchemeBenefit> search(SchemeBenefitFilterParams f, int limit, int offset) {
        boolean hasQ = f.q() != null && !f.q().isBlank();
        String search = hasQ ? "%" + f.q().toLowerCase() + "%" : null;

        String sql = baseQuery(f, hasQ)
                + " ORDER BY " + sortClause(f.sortKey(), f.sortDirection())
                + " LIMIT :limit OFFSET :offset";
        var spec = bindFilters(db.sql(sql), f, hasQ, search)
                .bind("limit", limit)
                .bind("offset", offset);
        return spec.map(this::toEntity).all();
    }

    public Mono<Long> count(SchemeBenefitFilterParams f) {
        boolean hasQ = f.q() != null && !f.q().isBlank();
        String search = hasQ ? "%" + f.q().toLowerCase() + "%" : null;

        String sql = "SELECT COUNT(*) AS total FROM (" + baseQuery(f, hasQ) + ") sub";
        var spec = bindFilters(db.sql(sql), f, hasQ, search);
        return spec.map(row -> ((Number) row.get("total")).longValue()).one();
    }

    private String baseQuery(SchemeBenefitFilterParams f, boolean hasQ) {
        StringBuilder sb = new StringBuilder("""
                SELECT id, scheme_id, name, benefit_type,
                       annual_limit, daily_limit, event_limit, currency_code,
                       waiting_period_days, description, status,
                       created_at, updated_at
                  FROM scheme_benefits
                 WHERE 1 = 1
                """);
        if (f.schemeId() != null) {
            sb.append(" AND scheme_id = :schemeId ");
        }
        if (f.status() != null && !f.status().isBlank()) {
            sb.append(" AND status = :status ");
        }
        if (f.benefitType() != null && !f.benefitType().isBlank()) {
            sb.append(" AND LOWER(benefit_type) = LOWER(:benefitType) ");
        }
        if (hasQ) {
            sb.append(" AND (LOWER(name) LIKE :search " +
                      "OR LOWER(COALESCE(benefit_type, '')) LIKE :search " +
                      "OR LOWER(COALESCE(description, '')) LIKE :search) ");
        }
        return sb.toString();
    }

    private DatabaseClient.GenericExecuteSpec bindFilters(DatabaseClient.GenericExecuteSpec spec,
                                                          SchemeBenefitFilterParams f,
                                                          boolean hasQ,
                                                          String search) {
        if (f.schemeId() != null)                                       spec = spec.bind("schemeId", f.schemeId());
        if (f.status() != null && !f.status().isBlank())                spec = spec.bind("status", f.status());
        if (f.benefitType() != null && !f.benefitType().isBlank())      spec = spec.bind("benefitType", f.benefitType());
        if (hasQ)                                                       spec = spec.bind("search", search);
        return spec;
    }

    private String sortClause(String sortKey, String sortDirection) {
        String col = SORT_COLUMNS.getOrDefault(sortKey, "name");
        String dir = "desc".equalsIgnoreCase(sortDirection) ? "DESC" : "ASC";
        return col + " " + dir + " NULLS LAST, id ASC";
    }

    private SchemeBenefit toEntity(io.r2dbc.spi.Readable row) {
        SchemeBenefit b = new SchemeBenefit();
        b.setId(row.get("id", UUID.class));
        b.setSchemeId(row.get("scheme_id", UUID.class));
        b.setName(row.get("name", String.class));
        b.setBenefitType(row.get("benefit_type", String.class));
        b.setAnnualLimit(row.get("annual_limit", BigDecimal.class));
        b.setDailyLimit(row.get("daily_limit", BigDecimal.class));
        b.setEventLimit(row.get("event_limit", BigDecimal.class));
        b.setCurrencyCode(row.get("currency_code", String.class));
        b.setWaitingPeriodDays(row.get("waiting_period_days", Integer.class));
        b.setDescription(row.get("description", String.class));
        b.setStatus(row.get("status", String.class));
        // scheme_benefits.created_at / updated_at are TIMESTAMPTZ, which
        // r2dbc-postgres returns as OffsetDateTime by default. Ask for
        // Instant explicitly so the driver converts cleanly.
        b.setCreatedAt(row.get("created_at", Instant.class));
        b.setUpdatedAt(row.get("updated_at", Instant.class));
        return b;
    }
}
