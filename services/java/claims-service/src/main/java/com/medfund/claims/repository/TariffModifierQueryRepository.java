package com.medfund.claims.repository;

import com.medfund.claims.dto.TariffModifierFilterParams;
import com.medfund.claims.entity.TariffModifier;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

/**
 * Dynamic-SQL search powering the tariff-modifiers catalogue list.
 * No joins — modifier rows are self-contained.
 */
@Repository
public class TariffModifierQueryRepository {

    private static final Map<String, String> SORT_COLUMNS = Map.of(
            "code",            "code",
            "name",            "name",
            "adjustmentType",  "adjustment_type",
            "adjustmentValue", "adjustment_value",
            "isActive",        "is_active"
    );

    private final DatabaseClient db;

    public TariffModifierQueryRepository(DatabaseClient db) {
        this.db = db;
    }

    public Flux<TariffModifier> search(TariffModifierFilterParams f, int limit, int offset) {
        boolean hasQ = f.q() != null && !f.q().isBlank();
        String search = hasQ ? "%" + f.q().toLowerCase() + "%" : null;

        String sql = "SELECT id, code, name, description, adjustment_type, adjustment_value, is_active "
                + " FROM tariff_modifiers "
                + whereClause(f, hasQ)
                + " ORDER BY " + sortClause(f.sortKey(), f.sortDirection())
                + " LIMIT :limit OFFSET :offset";
        var spec = bindFilters(db.sql(sql), f, hasQ, search)
                .bind("limit", limit)
                .bind("offset", offset);
        return spec.map(this::toEntity).all();
    }

    public Mono<Long> count(TariffModifierFilterParams f) {
        boolean hasQ = f.q() != null && !f.q().isBlank();
        String search = hasQ ? "%" + f.q().toLowerCase() + "%" : null;

        String sql = "SELECT COUNT(*) AS total FROM tariff_modifiers " + whereClause(f, hasQ);
        var spec = bindFilters(db.sql(sql), f, hasQ, search);
        return spec.map(row -> ((Number) row.get("total")).longValue()).one();
    }

    private String whereClause(TariffModifierFilterParams f, boolean hasQ) {
        StringBuilder sb = new StringBuilder(" WHERE 1 = 1 ");
        if (Boolean.TRUE.equals(f.activeOnly())) sb.append(" AND is_active = TRUE ");
        if (f.adjustmentType() != null && !f.adjustmentType().isBlank()) {
            sb.append(" AND UPPER(adjustment_type) = UPPER(:adjustmentType) ");
        }
        if (hasQ) {
            sb.append(" AND (LOWER(code) LIKE :search "
                   + "     OR LOWER(name) LIKE :search "
                   + "     OR LOWER(COALESCE(description, '')) LIKE :search) ");
        }
        return sb.toString();
    }

    private DatabaseClient.GenericExecuteSpec bindFilters(DatabaseClient.GenericExecuteSpec spec,
                                                          TariffModifierFilterParams f,
                                                          boolean hasQ,
                                                          String search) {
        if (f.adjustmentType() != null && !f.adjustmentType().isBlank()) {
            spec = spec.bind("adjustmentType", f.adjustmentType());
        }
        if (hasQ) spec = spec.bind("search", search);
        return spec;
    }

    private String sortClause(String sortKey, String sortDirection) {
        String col = SORT_COLUMNS.getOrDefault(sortKey, "code");
        String dir = "desc".equalsIgnoreCase(sortDirection) ? "DESC" : "ASC";
        return col + " " + dir + " NULLS LAST, id ASC";
    }

    private TariffModifier toEntity(io.r2dbc.spi.Readable row) {
        TariffModifier m = new TariffModifier();
        m.setId(row.get("id", UUID.class));
        m.setCode(row.get("code", String.class));
        m.setName(row.get("name", String.class));
        m.setDescription(row.get("description", String.class));
        m.setAdjustmentType(row.get("adjustment_type", String.class));
        m.setAdjustmentValue(row.get("adjustment_value", BigDecimal.class));
        m.setIsActive(row.get("is_active", Boolean.class));
        return m;
    }
}
