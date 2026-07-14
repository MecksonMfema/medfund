package com.medfund.contributions.repository;

import com.medfund.contributions.dto.WaitingPeriodFilterParams;
import com.medfund.contributions.dto.WaitingPeriodRow;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Dynamic-SQL search powering the waiting-period-rules catalogue.
 * Joins {@code schemes} so the operational table renders each row's
 * scheme name inline.
 */
@Repository
public class WaitingPeriodQueryRepository {

    private static final Map<String, String> SORT_COLUMNS = Map.of(
            "schemeName",    "COALESCE(s.name, '')",
            "conditionType", "w.condition_type",
            "waitingDays",   "w.waiting_days",
            "minAge",        "w.min_age",
            "maxAge",        "w.max_age",
            "createdAt",     "w.created_at"
    );

    private final DatabaseClient db;

    public WaitingPeriodQueryRepository(DatabaseClient db) {
        this.db = db;
    }

    public Flux<WaitingPeriodRow> search(WaitingPeriodFilterParams f, int limit, int offset) {
        boolean hasQ = f.q() != null && !f.q().isBlank();
        String search = hasQ ? "%" + f.q().toLowerCase() + "%" : null;

        String sql = """
                SELECT w.id, w.scheme_id, w.condition_type, w.waiting_days,
                       w.min_age, w.max_age, w.description, w.created_at,
                       s.name AS scheme_name
                  FROM waiting_period_rules w
                  LEFT JOIN schemes s ON s.id = w.scheme_id
                """
                + whereClause(f, hasQ)
                + " ORDER BY " + sortClause(f.sortKey(), f.sortDirection())
                + " LIMIT :limit OFFSET :offset";
        var spec = bindFilters(db.sql(sql), f, hasQ, search)
                .bind("limit", limit)
                .bind("offset", offset);
        return spec.map(this::toRow).all();
    }

    public Mono<Long> count(WaitingPeriodFilterParams f) {
        boolean hasQ = f.q() != null && !f.q().isBlank();
        String search = hasQ ? "%" + f.q().toLowerCase() + "%" : null;

        String sql = "SELECT COUNT(*) AS total FROM waiting_period_rules w "
                + " LEFT JOIN schemes s ON s.id = w.scheme_id "
                + whereClause(f, hasQ);
        var spec = bindFilters(db.sql(sql), f, hasQ, search);
        return spec.map(row -> ((Number) row.get("total")).longValue()).one();
    }

    private String whereClause(WaitingPeriodFilterParams f, boolean hasQ) {
        StringBuilder sb = new StringBuilder(" WHERE 1 = 1 ");
        if (f.schemeId() != null) sb.append(" AND w.scheme_id = :schemeId ");
        if (f.conditionType() != null && !f.conditionType().isBlank()) {
            sb.append(" AND LOWER(w.condition_type) = LOWER(:conditionType) ");
        }
        if (hasQ) {
            sb.append(" AND (LOWER(w.condition_type) LIKE :search "
                   + "     OR LOWER(COALESCE(w.description, '')) LIKE :search "
                   + "     OR LOWER(COALESCE(s.name, '')) LIKE :search) ");
        }
        return sb.toString();
    }

    private DatabaseClient.GenericExecuteSpec bindFilters(DatabaseClient.GenericExecuteSpec spec,
                                                          WaitingPeriodFilterParams f,
                                                          boolean hasQ,
                                                          String search) {
        if (f.schemeId() != null) spec = spec.bind("schemeId", f.schemeId());
        if (f.conditionType() != null && !f.conditionType().isBlank()) {
            spec = spec.bind("conditionType", f.conditionType());
        }
        if (hasQ) spec = spec.bind("search", search);
        return spec;
    }

    private String sortClause(String sortKey, String sortDirection) {
        String col = SORT_COLUMNS.getOrDefault(sortKey, "w.created_at");
        String dir = "asc".equalsIgnoreCase(sortDirection) ? "ASC" : "DESC";
        return col + " " + dir + " NULLS LAST, w.id ASC";
    }

    private WaitingPeriodRow toRow(io.r2dbc.spi.Readable row) {
        return new WaitingPeriodRow(
                row.get("id", UUID.class),
                row.get("scheme_id", UUID.class),
                row.get("scheme_name", String.class),
                row.get("condition_type", String.class),
                row.get("waiting_days", Integer.class),
                row.get("min_age", Short.class),
                row.get("max_age", Short.class),
                row.get("description", String.class),
                row.get("created_at", Instant.class)
        );
    }
}
