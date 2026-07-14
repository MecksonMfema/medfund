package com.medfund.claims.repository;

import com.medfund.claims.dto.TariffCategoryFilterParams;
import com.medfund.claims.entity.TariffCategory;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.UUID;

/**
 * Dynamic-SQL search powering the tariff-categories catalogue list
 * (server-side sort + pagination). No joins.
 */
@Repository
public class TariffCategoryQueryRepository {

    private static final Map<String, String> SORT_COLUMNS = Map.of(
            "code",       "code",
            "label",      "label",
            "isCapOnly",  "is_cap_only",
            "isActive",   "is_active",
            "sortOrder",  "sort_order"
    );

    private final DatabaseClient db;

    public TariffCategoryQueryRepository(DatabaseClient db) {
        this.db = db;
    }

    public Flux<TariffCategory> search(TariffCategoryFilterParams f, int limit, int offset) {
        boolean hasQ = f.q() != null && !f.q().isBlank();
        String search = hasQ ? "%" + f.q().toLowerCase() + "%" : null;

        String sql = "SELECT id, code, label, description, is_cap_only, is_active, sort_order "
                + "  FROM tariff_categories "
                + whereClause(f, hasQ)
                + " ORDER BY " + sortClause(f.sortKey(), f.sortDirection())
                + " LIMIT :limit OFFSET :offset";
        var spec = bindFilters(db.sql(sql), f, hasQ, search)
                .bind("limit", limit)
                .bind("offset", offset);
        return spec.map(this::toEntity).all();
    }

    public Mono<Long> count(TariffCategoryFilterParams f) {
        boolean hasQ = f.q() != null && !f.q().isBlank();
        String search = hasQ ? "%" + f.q().toLowerCase() + "%" : null;

        String sql = "SELECT COUNT(*) AS total FROM tariff_categories " + whereClause(f, hasQ);
        var spec = bindFilters(db.sql(sql), f, hasQ, search);
        return spec.map(row -> ((Number) row.get("total")).longValue()).one();
    }

    private String whereClause(TariffCategoryFilterParams f, boolean hasQ) {
        StringBuilder sb = new StringBuilder(" WHERE 1 = 1 ");
        if (Boolean.TRUE.equals(f.activeOnly())) sb.append(" AND is_active = TRUE ");
        if (f.capOnly() != null)                 sb.append(" AND is_cap_only = :capOnly ");
        if (hasQ) {
            sb.append(" AND (LOWER(code) LIKE :search "
                   + "     OR LOWER(label) LIKE :search "
                   + "     OR LOWER(COALESCE(description, '')) LIKE :search) ");
        }
        return sb.toString();
    }

    private DatabaseClient.GenericExecuteSpec bindFilters(DatabaseClient.GenericExecuteSpec spec,
                                                          TariffCategoryFilterParams f,
                                                          boolean hasQ,
                                                          String search) {
        if (f.capOnly() != null) spec = spec.bind("capOnly", f.capOnly());
        if (hasQ) spec = spec.bind("search", search);
        return spec;
    }

    private String sortClause(String sortKey, String sortDirection) {
        String col = SORT_COLUMNS.getOrDefault(sortKey, "sort_order");
        String dir = "desc".equalsIgnoreCase(sortDirection) ? "DESC" : "ASC";
        return col + " " + dir + " NULLS LAST, id ASC";
    }

    private TariffCategory toEntity(io.r2dbc.spi.Readable row) {
        TariffCategory c = new TariffCategory();
        c.setId(row.get("id", UUID.class));
        c.setCode(row.get("code", String.class));
        c.setLabel(row.get("label", String.class));
        c.setDescription(row.get("description", String.class));
        c.setIsCapOnly(row.get("is_cap_only", Boolean.class));
        c.setIsActive(row.get("is_active", Boolean.class));
        c.setSortOrder(row.get("sort_order", Integer.class));
        return c;
    }
}
