package com.medfund.claims.repository;

import com.medfund.claims.dto.TariffCodeFilterParams;
import com.medfund.claims.dto.TariffCodeRow;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

/**
 * Dynamic-SQL search powering the tariff-codes list (server-side
 * sort + pagination + join). Mirrors {@code SchemeQueryRepository} in
 * contributions-service.
 *
 * <p>The AHFOZ March seed loads ~4,860 codes into a single schedule.
 * The previous unpaginated endpoint returned all of them + a client-side
 * join against the categories catalogue; that made the page slow to
 * open and expensive to change-detect. This repository joins in
 * {@code categoryLabel} on the server so the client renders each page
 * as-is without further lookups.
 *
 * <p>Sort safety: {@code sortKey} from the HTTP request is translated
 * to a column name through {@link #SORT_COLUMNS}. Direction is forced to
 * {@code ASC} or {@code DESC}. A stable tiebreaker on {@code id} keeps
 * paging deterministic when two rows share the sort value.
 */
@Repository
public class TariffCodeQueryRepository {

    private static final Map<String, String> SORT_COLUMNS = Map.of(
            "code",            "tc.code",
            "description",     "tc.description",
            "categoryLabel",   "COALESCE(cat.label, tc.category)",
            "unitPrice",       "tc.unit_price",
            "currencyCode",    "tc.currency_code",
            "requiresPreAuth", "tc.requires_pre_auth"
    );

    private final DatabaseClient db;

    public TariffCodeQueryRepository(DatabaseClient db) {
        this.db = db;
    }

    public Flux<TariffCodeRow> search(TariffCodeFilterParams f, int limit, int offset) {
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

    public Mono<Long> count(TariffCodeFilterParams f) {
        boolean hasQ = f.q() != null && !f.q().isBlank();
        String search = hasQ ? "%" + f.q().toLowerCase() + "%" : null;

        String sql = "SELECT COUNT(*) AS total FROM tariff_codes tc "
                + "LEFT JOIN tariff_categories cat ON cat.id = tc.category_id "
                + whereClause(f, hasQ);
        var spec = bindFilters(db.sql(sql), f, hasQ, search);
        return spec.map(row -> ((Number) row.get("total")).longValue()).one();
    }

    private String selectClause() {
        return """
                SELECT tc.id, tc.schedule_id, tc.code, tc.description,
                       tc.category, tc.category_id, cat.label AS category_label,
                       tc.unit_price, tc.currency_code, tc.requires_pre_auth
                """;
    }

    private String baseFrom() {
        return " FROM tariff_codes tc "
             + " LEFT JOIN tariff_categories cat ON cat.id = tc.category_id ";
    }

    private String whereClause(TariffCodeFilterParams f, boolean hasQ) {
        StringBuilder sb = new StringBuilder(" WHERE 1 = 1 ");
        if (f.scheduleId() != null) {
            sb.append(" AND tc.schedule_id = :scheduleId ");
        }
        if (f.categoryId() != null) {
            sb.append(" AND tc.category_id = :categoryId ");
        }
        if (f.requiresPreAuth() != null) {
            sb.append(" AND tc.requires_pre_auth = :requiresPreAuth ");
        }
        if (hasQ) {
            sb.append(" AND (LOWER(tc.code) LIKE :search "
                   + "     OR LOWER(COALESCE(tc.description, '')) LIKE :search "
                   + "     OR LOWER(COALESCE(cat.label, tc.category, '')) LIKE :search) ");
        }
        return sb.toString();
    }

    private DatabaseClient.GenericExecuteSpec bindFilters(DatabaseClient.GenericExecuteSpec spec,
                                                          TariffCodeFilterParams f,
                                                          boolean hasQ,
                                                          String search) {
        if (f.scheduleId() != null)       spec = spec.bind("scheduleId", f.scheduleId());
        if (f.categoryId() != null)       spec = spec.bind("categoryId", f.categoryId());
        if (f.requiresPreAuth() != null)  spec = spec.bind("requiresPreAuth", f.requiresPreAuth());
        if (hasQ)                         spec = spec.bind("search", search);
        return spec;
    }

    private String sortClause(String sortKey, String sortDirection) {
        String col = SORT_COLUMNS.getOrDefault(sortKey, "tc.code");
        String dir = "desc".equalsIgnoreCase(sortDirection) ? "DESC" : "ASC";
        return col + " " + dir + " NULLS LAST, tc.id ASC";
    }

    private TariffCodeRow toRow(io.r2dbc.spi.Readable row) {
        return new TariffCodeRow(
                row.get("id", UUID.class),
                row.get("schedule_id", UUID.class),
                row.get("code", String.class),
                row.get("description", String.class),
                row.get("category_id", UUID.class),
                // Prefer the joined catalogue label; fall back to the
                // denormalised legacy string on rows that predate V063.
                labelOrLegacy(row),
                row.get("unit_price", BigDecimal.class),
                row.get("currency_code", String.class),
                row.get("requires_pre_auth", Boolean.class)
        );
    }

    private String labelOrLegacy(io.r2dbc.spi.Readable row) {
        String joined = row.get("category_label", String.class);
        if (joined != null && !joined.isBlank()) return joined;
        return row.get("category", String.class);
    }
}
