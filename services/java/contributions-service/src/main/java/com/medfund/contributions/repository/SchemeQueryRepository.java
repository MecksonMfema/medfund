package com.medfund.contributions.repository;

import com.medfund.contributions.dto.SchemeFilterParams;
import com.medfund.contributions.entity.Scheme;
import lombok.RequiredArgsConstructor;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

/**
 * Dynamic-SQL search powering the operational schemes list (server-side
 * sort + pagination). Mirrors the pattern in {@link TransactionQueryRepository}.
 *
 * <p>Sort safety: {@code sortKey} from the HTTP request is translated to a
 * column name through {@link #SORT_COLUMNS} — anything not in that map falls
 * back to {@code name}. Direction is forced to {@code ASC} or {@code DESC}.
 */
@Repository
@RequiredArgsConstructor
public class SchemeQueryRepository {

    private static final Map<String, String> SORT_COLUMNS = Map.of(
            "name",          "name",
            "schemeType",    "scheme_type",
            "currencyCode",  "currency_code",
            "status",        "status",
            "effectiveDate", "effective_date",
            "createdAt",     "created_at"
    );

    private final DatabaseClient db;

    public Flux<Scheme> search(SchemeFilterParams f, int limit, int offset) {
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

    public Mono<Long> count(SchemeFilterParams f) {
        boolean hasQ = f.q() != null && !f.q().isBlank();
        String search = hasQ ? "%" + f.q().toLowerCase() + "%" : null;

        String sql = "SELECT COUNT(*) AS total FROM (" + baseQuery(f, hasQ) + ") sub";
        var spec = bindFilters(db.sql(sql), f, hasQ, search);
        return spec.map(row -> ((Number) row.get("total")).longValue()).one();
    }

    private String baseQuery(SchemeFilterParams f, boolean hasQ) {
        StringBuilder sb = new StringBuilder("""
                SELECT id, name, description, scheme_type, insurance_line, status,
                       effective_date, end_date, currency_code,
                       created_at, updated_at, created_by, updated_by
                  FROM schemes
                 WHERE 1 = 1
                """);
        if (f.status() != null && !f.status().isBlank()) {
            sb.append(" AND status = :status ");
        }
        if (f.insuranceLine() != null && !f.insuranceLine().isBlank()) {
            sb.append(" AND UPPER(insurance_line) = UPPER(:insuranceLine) ");
        }
        if (f.schemeType() != null && !f.schemeType().isBlank()) {
            sb.append(" AND LOWER(scheme_type) = LOWER(:schemeType) ");
        }
        if (hasQ) {
            sb.append(" AND (LOWER(name) LIKE :search OR LOWER(COALESCE(scheme_type, '')) LIKE :search) ");
        }
        return sb.toString();
    }

    private DatabaseClient.GenericExecuteSpec bindFilters(DatabaseClient.GenericExecuteSpec spec,
                                                          SchemeFilterParams f,
                                                          boolean hasQ,
                                                          String search) {
        if (f.status() != null && !f.status().isBlank())                spec = spec.bind("status", f.status());
        if (f.insuranceLine() != null && !f.insuranceLine().isBlank())  spec = spec.bind("insuranceLine", f.insuranceLine());
        if (f.schemeType() != null && !f.schemeType().isBlank())        spec = spec.bind("schemeType", f.schemeType());
        if (hasQ)                                                       spec = spec.bind("search", search);
        return spec;
    }

    private String sortClause(String sortKey, String sortDirection) {
        String col = SORT_COLUMNS.getOrDefault(sortKey, "name");
        String dir = "desc".equalsIgnoreCase(sortDirection) ? "DESC" : "ASC";
        // Stable tiebreaker so equal sort values keep a deterministic order
        // across pages.
        return col + " " + dir + " NULLS LAST, id ASC";
    }

    private Scheme toEntity(io.r2dbc.spi.Readable row) {
        Scheme s = new Scheme();
        s.setId(row.get("id", UUID.class));
        s.setName(row.get("name", String.class));
        s.setDescription(row.get("description", String.class));
        s.setSchemeType(row.get("scheme_type", String.class));
        s.setInsuranceLine(row.get("insurance_line", String.class));
        s.setStatus(row.get("status", String.class));
        s.setEffectiveDate(row.get("effective_date", LocalDate.class));
        s.setEndDate(row.get("end_date", LocalDate.class));
        s.setCurrencyCode(row.get("currency_code", String.class));
        // schemes.created_at / updated_at are TIMESTAMPTZ, which r2dbc-postgres
        // returns as OffsetDateTime by default. Ask for Instant explicitly so
        // the driver converts instead of letting a raw cast blow up at runtime.
        s.setCreatedAt(row.get("created_at", Instant.class));
        s.setUpdatedAt(row.get("updated_at", Instant.class));
        s.setCreatedBy(row.get("created_by", UUID.class));
        s.setUpdatedBy(row.get("updated_by", UUID.class));
        return s;
    }
}
