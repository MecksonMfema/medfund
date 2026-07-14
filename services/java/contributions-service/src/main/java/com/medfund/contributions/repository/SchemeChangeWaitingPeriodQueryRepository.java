package com.medfund.contributions.repository;

import com.medfund.contributions.dto.SchemeChangeWaitingPeriodFilterParams;
import com.medfund.contributions.entity.SchemeChangeWaitingPeriodRule;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Dynamic-SQL search powering the scheme-change waiting-period rules
 * list. No joins — rules are self-contained.
 */
@Repository
public class SchemeChangeWaitingPeriodQueryRepository {

    private static final Map<String, String> SORT_COLUMNS = Map.of(
            "changeType",  "change_type",
            "benefitType", "benefit_type",
            "waitingDays", "waiting_days",
            "isActive",    "is_active",
            "createdAt",   "created_at"
    );

    private final DatabaseClient db;

    public SchemeChangeWaitingPeriodQueryRepository(DatabaseClient db) {
        this.db = db;
    }

    public Flux<SchemeChangeWaitingPeriodRule> search(SchemeChangeWaitingPeriodFilterParams f, int limit, int offset) {
        boolean hasQ = f.q() != null && !f.q().isBlank();
        String search = hasQ ? "%" + f.q().toLowerCase() + "%" : null;

        String sql = "SELECT id, change_type, benefit_type, waiting_days, description, "
                + "       is_active, created_at, updated_at "
                + "  FROM scheme_change_waiting_period_rules "
                + whereClause(f, hasQ)
                + " ORDER BY " + sortClause(f.sortKey(), f.sortDirection())
                + " LIMIT :limit OFFSET :offset";
        var spec = bindFilters(db.sql(sql), f, hasQ, search)
                .bind("limit", limit)
                .bind("offset", offset);
        return spec.map(this::toEntity).all();
    }

    public Mono<Long> count(SchemeChangeWaitingPeriodFilterParams f) {
        boolean hasQ = f.q() != null && !f.q().isBlank();
        String search = hasQ ? "%" + f.q().toLowerCase() + "%" : null;

        String sql = "SELECT COUNT(*) AS total FROM scheme_change_waiting_period_rules " + whereClause(f, hasQ);
        var spec = bindFilters(db.sql(sql), f, hasQ, search);
        return spec.map(row -> ((Number) row.get("total")).longValue()).one();
    }

    private String whereClause(SchemeChangeWaitingPeriodFilterParams f, boolean hasQ) {
        StringBuilder sb = new StringBuilder(" WHERE 1 = 1 ");
        if (f.changeType() != null && !f.changeType().isBlank()) {
            sb.append(" AND UPPER(change_type) = UPPER(:changeType) ");
        }
        if (f.benefitType() != null && !f.benefitType().isBlank()) {
            sb.append(" AND LOWER(benefit_type) = LOWER(:benefitType) ");
        }
        if (Boolean.TRUE.equals(f.activeOnly())) sb.append(" AND is_active = TRUE ");
        if (hasQ) {
            sb.append(" AND (LOWER(COALESCE(benefit_type, '')) LIKE :search "
                   + "     OR LOWER(COALESCE(description, '')) LIKE :search) ");
        }
        return sb.toString();
    }

    private DatabaseClient.GenericExecuteSpec bindFilters(DatabaseClient.GenericExecuteSpec spec,
                                                          SchemeChangeWaitingPeriodFilterParams f,
                                                          boolean hasQ,
                                                          String search) {
        if (f.changeType() != null && !f.changeType().isBlank()) spec = spec.bind("changeType", f.changeType());
        if (f.benefitType() != null && !f.benefitType().isBlank()) spec = spec.bind("benefitType", f.benefitType());
        if (hasQ) spec = spec.bind("search", search);
        return spec;
    }

    private String sortClause(String sortKey, String sortDirection) {
        String col = SORT_COLUMNS.getOrDefault(sortKey, "created_at");
        String dir = "asc".equalsIgnoreCase(sortDirection) ? "ASC" : "DESC";
        return col + " " + dir + " NULLS LAST, id ASC";
    }

    private SchemeChangeWaitingPeriodRule toEntity(io.r2dbc.spi.Readable row) {
        SchemeChangeWaitingPeriodRule r = new SchemeChangeWaitingPeriodRule();
        r.setId(row.get("id", UUID.class));
        r.setChangeType(row.get("change_type", String.class));
        r.setBenefitType(row.get("benefit_type", String.class));
        r.setWaitingDays(row.get("waiting_days", Integer.class));
        r.setDescription(row.get("description", String.class));
        r.setIsActive(row.get("is_active", Boolean.class));
        r.setCreatedAt(row.get("created_at", Instant.class));
        r.setUpdatedAt(row.get("updated_at", Instant.class));
        return r;
    }
}
