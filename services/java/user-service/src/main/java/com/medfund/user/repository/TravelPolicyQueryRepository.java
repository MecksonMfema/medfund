package com.medfund.user.repository;

import com.medfund.user.dto.TravelPolicyFilterParams;
import com.medfund.user.dto.TravelPolicyRow;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

@Repository
public class TravelPolicyQueryRepository {

    private static final Map<String, String> SORT_COLUMNS = Map.of(
            "policyNumber",         "p.policy_number",
            "tripStartDate",        "p.trip_start_date",
            "tripEndDate",          "p.trip_end_date",
            "destinationBand",      "p.destination_band",
            "status",               "p.status",
            "schemeName",           "COALESCE(s.name, '')",
            "travelerMemberName",   "COALESCE(m.last_name || ' ' || m.first_name, '')",
            "createdAt",            "p.created_at"
    );

    private final DatabaseClient db;

    public TravelPolicyQueryRepository(DatabaseClient db) {
        this.db = db;
    }

    public Flux<TravelPolicyRow> search(TravelPolicyFilterParams f, int limit, int offset) {
        boolean hasQ = f.q() != null && !f.q().isBlank();
        String search = hasQ ? "%" + f.q().toLowerCase() + "%" : null;

        String sql = """
                SELECT p.id, p.scheme_id, p.traveler_member_id,
                       p.policy_number, p.trip_start_date, p.trip_end_date,
                       p.destination_band, p.coverage_level, p.status,
                       p.billing_override_amount, p.created_at, p.updated_at,
                       s.name AS scheme_name,
                       m.first_name AS traveler_first_name,
                       m.last_name  AS traveler_last_name,
                       m.member_number AS traveler_member_number
                  FROM travel_policies p
                  LEFT JOIN schemes s ON s.id = p.scheme_id
                  LEFT JOIN members m ON m.id = p.traveler_member_id
                """
                + whereClause(f, hasQ)
                + " ORDER BY " + sortClause(f.sortKey(), f.sortDirection())
                + " LIMIT :limit OFFSET :offset";

        var spec = bindFilters(db.sql(sql), f, hasQ, search)
                .bind("limit", limit)
                .bind("offset", offset);
        return spec.map(this::toRow).all();
    }

    public Mono<Long> count(TravelPolicyFilterParams f) {
        boolean hasQ = f.q() != null && !f.q().isBlank();
        String search = hasQ ? "%" + f.q().toLowerCase() + "%" : null;

        String sql = """
                SELECT COUNT(*) AS total FROM travel_policies p
                  LEFT JOIN schemes s ON s.id = p.scheme_id
                  LEFT JOIN members m ON m.id = p.traveler_member_id
                """
                + whereClause(f, hasQ);
        var spec = bindFilters(db.sql(sql), f, hasQ, search);
        return spec.map(row -> ((Number) row.get("total")).longValue()).one();
    }

    private String whereClause(TravelPolicyFilterParams f, boolean hasQ) {
        StringBuilder sb = new StringBuilder(" WHERE 1 = 1 ");
        if (f.status() != null && !f.status().isBlank()) {
            sb.append(" AND p.status = :status ");
        }
        if (f.schemeId() != null) {
            sb.append(" AND p.scheme_id = :schemeId ");
        }
        if (hasQ) {
            sb.append(" AND (LOWER(p.policy_number) LIKE :search "
                    + "     OR LOWER(COALESCE(p.destination_band, '')) LIKE :search "
                    + "     OR LOWER(COALESCE(s.name, '')) LIKE :search "
                    + "     OR LOWER(COALESCE(m.first_name || ' ' || m.last_name, '')) LIKE :search "
                    + "     OR LOWER(COALESCE(m.last_name  || ' ' || m.first_name, '')) LIKE :search "
                    + "     OR LOWER(COALESCE(m.member_number, '')) LIKE :search) ");
        }
        return sb.toString();
    }

    private DatabaseClient.GenericExecuteSpec bindFilters(DatabaseClient.GenericExecuteSpec spec,
                                                          TravelPolicyFilterParams f,
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

    private TravelPolicyRow toRow(io.r2dbc.spi.Readable row) {
        String travelerName = VehicleQueryRepository.fullName(
                row.get("traveler_first_name", String.class),
                row.get("traveler_last_name", String.class),
                row.get("traveler_member_number", String.class));
        return new TravelPolicyRow(
                row.get("id", UUID.class),
                row.get("scheme_id", UUID.class),
                row.get("scheme_name", String.class),
                row.get("traveler_member_id", UUID.class),
                travelerName,
                row.get("policy_number", String.class),
                row.get("trip_start_date", LocalDate.class),
                row.get("trip_end_date", LocalDate.class),
                row.get("destination_band", String.class),
                row.get("coverage_level", String.class),
                row.get("status", String.class),
                row.get("billing_override_amount", BigDecimal.class),
                row.get("created_at", Instant.class),
                row.get("updated_at", Instant.class)
        );
    }
}
