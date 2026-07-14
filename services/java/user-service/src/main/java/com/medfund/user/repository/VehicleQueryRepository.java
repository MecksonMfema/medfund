package com.medfund.user.repository;

import com.medfund.user.dto.VehicleFilterParams;
import com.medfund.user.dto.VehicleRow;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Dynamic-SQL search powering the paginated vehicles list. Joins
 * {@code schemes} and {@code members} (owner) so the operational table
 * renders friendly names inline.
 */
@Repository
public class VehicleQueryRepository {

    private static final Map<String, String> SORT_COLUMNS = Map.of(
            "registrationNumber", "v.registration_number",
            "make",               "v.make",
            "model",              "v.model",
            "year",               "v.year",
            "vehicleValue",       "v.vehicle_value",
            "status",             "v.status",
            "schemeName",         "COALESCE(s.name, '')",
            "ownerMemberName",    "COALESCE(m.last_name || ' ' || m.first_name, '')",
            "createdAt",          "v.created_at"
    );

    private final DatabaseClient db;

    public VehicleQueryRepository(DatabaseClient db) {
        this.db = db;
    }

    public Flux<VehicleRow> search(VehicleFilterParams f, int limit, int offset) {
        boolean hasQ = f.q() != null && !f.q().isBlank();
        String search = hasQ ? "%" + f.q().toLowerCase() + "%" : null;

        String sql = """
                SELECT v.id, v.scheme_id, v.owner_member_id,
                       v.registration_number, v.make, v.model, v.year,
                       v.vehicle_value, v.body_type, v.usage_type, v.status,
                       v.billing_override_amount, v.created_at, v.updated_at,
                       s.name AS scheme_name,
                       m.first_name AS owner_first_name,
                       m.last_name  AS owner_last_name,
                       m.member_number AS owner_member_number
                  FROM vehicles v
                  LEFT JOIN schemes s ON s.id = v.scheme_id
                  LEFT JOIN members m ON m.id = v.owner_member_id
                """
                + whereClause(f, hasQ)
                + " ORDER BY " + sortClause(f.sortKey(), f.sortDirection())
                + " LIMIT :limit OFFSET :offset";

        var spec = bindFilters(db.sql(sql), f, hasQ, search)
                .bind("limit", limit)
                .bind("offset", offset);
        return spec.map(this::toRow).all();
    }

    public Mono<Long> count(VehicleFilterParams f) {
        boolean hasQ = f.q() != null && !f.q().isBlank();
        String search = hasQ ? "%" + f.q().toLowerCase() + "%" : null;

        String sql = """
                SELECT COUNT(*) AS total FROM vehicles v
                  LEFT JOIN schemes s ON s.id = v.scheme_id
                  LEFT JOIN members m ON m.id = v.owner_member_id
                """
                + whereClause(f, hasQ);
        var spec = bindFilters(db.sql(sql), f, hasQ, search);
        return spec.map(row -> ((Number) row.get("total")).longValue()).one();
    }

    private String whereClause(VehicleFilterParams f, boolean hasQ) {
        StringBuilder sb = new StringBuilder(" WHERE 1 = 1 ");
        if (f.status() != null && !f.status().isBlank()) {
            sb.append(" AND v.status = :status ");
        }
        if (f.schemeId() != null) {
            sb.append(" AND v.scheme_id = :schemeId ");
        }
        if (hasQ) {
            sb.append(" AND (LOWER(v.registration_number) LIKE :search "
                    + "     OR LOWER(COALESCE(v.make, '')) LIKE :search "
                    + "     OR LOWER(COALESCE(v.model, '')) LIKE :search "
                    + "     OR LOWER(COALESCE(s.name, '')) LIKE :search "
                    + "     OR LOWER(COALESCE(m.first_name || ' ' || m.last_name, '')) LIKE :search "
                    + "     OR LOWER(COALESCE(m.last_name  || ' ' || m.first_name, '')) LIKE :search "
                    + "     OR LOWER(COALESCE(m.member_number, '')) LIKE :search) ");
        }
        return sb.toString();
    }

    private DatabaseClient.GenericExecuteSpec bindFilters(DatabaseClient.GenericExecuteSpec spec,
                                                          VehicleFilterParams f,
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
        String col = SORT_COLUMNS.getOrDefault(sortKey, "v.created_at");
        String dir = "asc".equalsIgnoreCase(sortDirection) ? "ASC" : "DESC";
        return col + " " + dir + " NULLS LAST, v.id ASC";
    }

    private VehicleRow toRow(io.r2dbc.spi.Readable row) {
        String first = row.get("owner_first_name", String.class);
        String last  = row.get("owner_last_name", String.class);
        String memberNumber = row.get("owner_member_number", String.class);
        String ownerName = fullName(first, last, memberNumber);
        return new VehicleRow(
                row.get("id", UUID.class),
                row.get("scheme_id", UUID.class),
                row.get("scheme_name", String.class),
                row.get("owner_member_id", UUID.class),
                ownerName,
                row.get("registration_number", String.class),
                row.get("make", String.class),
                row.get("model", String.class),
                row.get("year", Integer.class),
                row.get("vehicle_value", BigDecimal.class),
                row.get("body_type", String.class),
                row.get("usage_type", String.class),
                row.get("status", String.class),
                row.get("billing_override_amount", BigDecimal.class),
                row.get("created_at", Instant.class),
                row.get("updated_at", Instant.class)
        );
    }

    static String fullName(String first, String last, String memberNumber) {
        String f = first == null ? "" : first.trim();
        String l = last  == null ? "" : last.trim();
        String combined = (f + " " + l).trim();
        if (!combined.isEmpty()) return combined;
        return memberNumber == null || memberNumber.isBlank() ? null : memberNumber;
    }
}
