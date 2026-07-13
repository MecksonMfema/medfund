package com.medfund.claims.repository;

import com.medfund.claims.dto.RejectionReasonFilterParams;
import com.medfund.claims.entity.RejectionReason;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.UUID;

/**
 * Dynamic-SQL search powering the rejection-reasons catalogue list.
 * No joins — catalogue rows are self-contained.
 */
@Repository
public class RejectionReasonQueryRepository {

    private static final Map<String, String> SORT_COLUMNS = Map.of(
            "code",        "code",
            "description", "description",
            "category",    "category",
            "isActive",    "is_active"
    );

    private final DatabaseClient db;

    public RejectionReasonQueryRepository(DatabaseClient db) {
        this.db = db;
    }

    public Flux<RejectionReason> search(RejectionReasonFilterParams f, int limit, int offset) {
        boolean hasQ = f.q() != null && !f.q().isBlank();
        String search = hasQ ? "%" + f.q().toLowerCase() + "%" : null;

        String sql = "SELECT id, code, description, category, is_active FROM rejection_reasons "
                + whereClause(f, hasQ)
                + " ORDER BY " + sortClause(f.sortKey(), f.sortDirection())
                + " LIMIT :limit OFFSET :offset";
        var spec = bindFilters(db.sql(sql), f, hasQ, search)
                .bind("limit", limit)
                .bind("offset", offset);
        return spec.map(this::toEntity).all();
    }

    public Mono<Long> count(RejectionReasonFilterParams f) {
        boolean hasQ = f.q() != null && !f.q().isBlank();
        String search = hasQ ? "%" + f.q().toLowerCase() + "%" : null;

        String sql = "SELECT COUNT(*) AS total FROM rejection_reasons " + whereClause(f, hasQ);
        var spec = bindFilters(db.sql(sql), f, hasQ, search);
        return spec.map(row -> ((Number) row.get("total")).longValue()).one();
    }

    private String whereClause(RejectionReasonFilterParams f, boolean hasQ) {
        StringBuilder sb = new StringBuilder(" WHERE 1 = 1 ");
        if (Boolean.TRUE.equals(f.activeOnly()))                            sb.append(" AND is_active = TRUE ");
        if (f.category() != null && !f.category().isBlank())                sb.append(" AND LOWER(category) = LOWER(:category) ");
        if (hasQ) {
            sb.append(" AND (LOWER(code) LIKE :search "
                   + "     OR LOWER(COALESCE(description, '')) LIKE :search "
                   + "     OR LOWER(COALESCE(category, '')) LIKE :search) ");
        }
        return sb.toString();
    }

    private DatabaseClient.GenericExecuteSpec bindFilters(DatabaseClient.GenericExecuteSpec spec,
                                                          RejectionReasonFilterParams f,
                                                          boolean hasQ,
                                                          String search) {
        if (f.category() != null && !f.category().isBlank()) spec = spec.bind("category", f.category());
        if (hasQ)                                            spec = spec.bind("search", search);
        return spec;
    }

    private String sortClause(String sortKey, String sortDirection) {
        String col = SORT_COLUMNS.getOrDefault(sortKey, "code");
        String dir = "desc".equalsIgnoreCase(sortDirection) ? "DESC" : "ASC";
        return col + " " + dir + " NULLS LAST, id ASC";
    }

    private RejectionReason toEntity(io.r2dbc.spi.Readable row) {
        RejectionReason r = new RejectionReason();
        r.setId(row.get("id", UUID.class));
        r.setCode(row.get("code", String.class));
        r.setDescription(row.get("description", String.class));
        r.setCategory(row.get("category", String.class));
        r.setIsActive(row.get("is_active", Boolean.class));
        return r;
    }
}
