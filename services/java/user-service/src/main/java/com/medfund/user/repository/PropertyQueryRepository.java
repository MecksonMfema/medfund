package com.medfund.user.repository;

import com.medfund.user.dto.PropertyFilterParams;
import com.medfund.user.dto.PropertyRow;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Dynamic-SQL search powering the paginated properties list. Joins
 * schemes + members(owner) so the operational table renders names inline.
 */
@Repository
public class PropertyQueryRepository {

    private static final Map<String, String> SORT_COLUMNS = Map.of(
            "propertyName",     "p.property_name",
            "address",          "p.address",
            "sumInsured",       "p.sum_insured",
            "status",           "p.status",
            "schemeName",       "COALESCE(s.name, '')",
            "ownerMemberName",  "COALESCE(m.last_name || ' ' || m.first_name, '')",
            "createdAt",        "p.created_at"
    );

    private final DatabaseClient db;

    public PropertyQueryRepository(DatabaseClient db) {
        this.db = db;
    }

    public Flux<PropertyRow> search(PropertyFilterParams f, int limit, int offset) {
        boolean hasQ = f.q() != null && !f.q().isBlank();
        String search = hasQ ? "%" + f.q().toLowerCase() + "%" : null;

        String sql = """
                SELECT p.id, p.scheme_id, p.owner_member_id,
                       p.property_name, p.address, p.sum_insured,
                       p.construction_type, p.occupancy, p.status,
                       p.billing_override_amount, p.created_at, p.updated_at,
                       s.name AS scheme_name,
                       m.first_name AS owner_first_name,
                       m.last_name  AS owner_last_name,
                       m.member_number AS owner_member_number
                  FROM properties p
                  LEFT JOIN schemes s ON s.id = p.scheme_id
                  LEFT JOIN members m ON m.id = p.owner_member_id
                """
                + whereClause(f, hasQ)
                + " ORDER BY " + sortClause(f.sortKey(), f.sortDirection())
                + " LIMIT :limit OFFSET :offset";

        var spec = bindFilters(db.sql(sql), f, hasQ, search)
                .bind("limit", limit)
                .bind("offset", offset);
        return spec.map(this::toRow).all();
    }

    public Mono<Long> count(PropertyFilterParams f) {
        boolean hasQ = f.q() != null && !f.q().isBlank();
        String search = hasQ ? "%" + f.q().toLowerCase() + "%" : null;

        String sql = """
                SELECT COUNT(*) AS total FROM properties p
                  LEFT JOIN schemes s ON s.id = p.scheme_id
                  LEFT JOIN members m ON m.id = p.owner_member_id
                """
                + whereClause(f, hasQ);
        var spec = bindFilters(db.sql(sql), f, hasQ, search);
        return spec.map(row -> ((Number) row.get("total")).longValue()).one();
    }

    private String whereClause(PropertyFilterParams f, boolean hasQ) {
        StringBuilder sb = new StringBuilder(" WHERE 1 = 1 ");
        if (f.status() != null && !f.status().isBlank()) {
            sb.append(" AND p.status = :status ");
        }
        if (f.schemeId() != null) {
            sb.append(" AND p.scheme_id = :schemeId ");
        }
        if (hasQ) {
            sb.append(" AND (LOWER(p.property_name) LIKE :search "
                    + "     OR LOWER(COALESCE(p.address, '')) LIKE :search "
                    + "     OR LOWER(COALESCE(s.name, '')) LIKE :search "
                    + "     OR LOWER(COALESCE(m.first_name || ' ' || m.last_name, '')) LIKE :search "
                    + "     OR LOWER(COALESCE(m.last_name  || ' ' || m.first_name, '')) LIKE :search "
                    + "     OR LOWER(COALESCE(m.member_number, '')) LIKE :search) ");
        }
        return sb.toString();
    }

    private DatabaseClient.GenericExecuteSpec bindFilters(DatabaseClient.GenericExecuteSpec spec,
                                                          PropertyFilterParams f,
                                                          boolean hasQ,
                                                          String search) {
        if (f.status() != null && !f.status().isBlank()) {
            spec = spec.bind("status", f.status());
        }
        if (f.schemeId() != null) {
            spec = spec.bind("schemeId", f.schemeId());
        }
        if (hasQ) spec = spec.bind("search", search);
        return spec;
    }

    private String sortClause(String sortKey, String sortDirection) {
        String col = SORT_COLUMNS.getOrDefault(sortKey, "p.created_at");
        String dir = "asc".equalsIgnoreCase(sortDirection) ? "ASC" : "DESC";
        return col + " " + dir + " NULLS LAST, p.id ASC";
    }

    private PropertyRow toRow(io.r2dbc.spi.Readable row) {
        String ownerName = VehicleQueryRepository.fullName(
                row.get("owner_first_name", String.class),
                row.get("owner_last_name", String.class),
                row.get("owner_member_number", String.class));
        return new PropertyRow(
                row.get("id", UUID.class),
                row.get("scheme_id", UUID.class),
                row.get("scheme_name", String.class),
                row.get("owner_member_id", UUID.class),
                ownerName,
                row.get("property_name", String.class),
                row.get("address", String.class),
                row.get("sum_insured", BigDecimal.class),
                row.get("construction_type", String.class),
                row.get("occupancy", String.class),
                row.get("status", String.class),
                row.get("billing_override_amount", BigDecimal.class),
                row.get("created_at", Instant.class),
                row.get("updated_at", Instant.class)
        );
    }
}
