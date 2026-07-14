package com.medfund.user.repository;

import com.medfund.user.dto.EmailSenderFilterParams;
import com.medfund.user.dto.EmailSenderRow;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Dynamic-SQL search powering the paginated email-senders list. No
 * joins — senders are self-contained.
 */
@Repository
public class EmailSenderQueryRepository {

    private static final Map<String, String> SORT_COLUMNS = Map.of(
            "address",     "address",
            "displayName", "display_name",
            "status",      "status",
            "verifiedAt",  "verified_at",
            "createdAt",   "created_at",
            "updatedAt",   "updated_at"
    );

    private final DatabaseClient db;

    public EmailSenderQueryRepository(DatabaseClient db) {
        this.db = db;
    }

    public Flux<EmailSenderRow> search(EmailSenderFilterParams f, int limit, int offset) {
        boolean hasQ = f.q() != null && !f.q().isBlank();
        String search = hasQ ? "%" + f.q().toLowerCase() + "%" : null;

        String sql = """
                SELECT id, address, display_name, status, verified_at,
                       notes, created_at, updated_at
                  FROM email_senders
                """
                + whereClause(f, hasQ)
                + " ORDER BY " + sortClause(f.sortKey(), f.sortDirection())
                + " LIMIT :limit OFFSET :offset";

        var spec = bindFilters(db.sql(sql), f, hasQ, search)
                .bind("limit", limit)
                .bind("offset", offset);
        return spec.map(this::toRow).all();
    }

    public Mono<Long> count(EmailSenderFilterParams f) {
        boolean hasQ = f.q() != null && !f.q().isBlank();
        String search = hasQ ? "%" + f.q().toLowerCase() + "%" : null;

        String sql = "SELECT COUNT(*) AS total FROM email_senders" + whereClause(f, hasQ);
        var spec = bindFilters(db.sql(sql), f, hasQ, search);
        return spec.map(row -> ((Number) row.get("total")).longValue()).one();
    }

    private String whereClause(EmailSenderFilterParams f, boolean hasQ) {
        StringBuilder sb = new StringBuilder(" WHERE 1 = 1 ");
        if (f.status() != null && !f.status().isBlank()) {
            sb.append(" AND status = :status ");
        }
        if (hasQ) {
            sb.append(" AND (LOWER(address) LIKE :search "
                    + "     OR LOWER(COALESCE(display_name, '')) LIKE :search "
                    + "     OR LOWER(COALESCE(notes, '')) LIKE :search) ");
        }
        return sb.toString();
    }

    private DatabaseClient.GenericExecuteSpec bindFilters(DatabaseClient.GenericExecuteSpec spec,
                                                          EmailSenderFilterParams f,
                                                          boolean hasQ,
                                                          String search) {
        if (f.status() != null && !f.status().isBlank()) {
            spec = spec.bind("status", f.status());
        }
        if (hasQ) spec = spec.bind("search", search);
        return spec;
    }

    private String sortClause(String sortKey, String sortDirection) {
        String col = SORT_COLUMNS.getOrDefault(sortKey, "created_at");
        String dir = "asc".equalsIgnoreCase(sortDirection) ? "ASC" : "DESC";
        return col + " " + dir + " NULLS LAST, id ASC";
    }

    private EmailSenderRow toRow(io.r2dbc.spi.Readable row) {
        return new EmailSenderRow(
                row.get("id", UUID.class),
                row.get("address", String.class),
                row.get("display_name", String.class),
                row.get("status", String.class),
                row.get("verified_at", Instant.class),
                row.get("notes", String.class),
                row.get("created_at", Instant.class),
                row.get("updated_at", Instant.class)
        );
    }
}
