package com.medfund.user.repository;

import com.medfund.user.dto.EmailCampaignFilterParams;
import com.medfund.user.dto.EmailCampaignRow;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Dynamic-SQL search powering the paginated email-campaigns list. Joins
 * email_senders so rows expose senderAddress + senderDisplayName inline.
 */
@Repository
public class EmailCampaignQueryRepository {

    private static final Map<String, String> SORT_COLUMNS = Map.of(
            "subject",           "c.subject",
            "status",            "c.status",
            "senderAddress",     "COALESCE(s.address, '')",
            "senderDisplayName", "COALESCE(s.display_name, '')",
            "recipientCount",    "c.recipient_count",
            "scheduledFor",      "c.scheduled_for",
            "sentAt",            "c.sent_at",
            "createdAt",         "c.created_at",
            "updatedAt",         "c.updated_at"
    );

    private final DatabaseClient db;

    public EmailCampaignQueryRepository(DatabaseClient db) {
        this.db = db;
    }

    public Flux<EmailCampaignRow> search(EmailCampaignFilterParams f, int limit, int offset) {
        boolean hasQ = f.q() != null && !f.q().isBlank();
        String search = hasQ ? "%" + f.q().toLowerCase() + "%" : null;

        String sql = """
                SELECT c.id, c.sender_id, c.subject, c.status,
                       c.scheduled_for, c.sent_at, c.recipient_count,
                       c.created_at, c.updated_at,
                       s.address AS sender_address,
                       s.display_name AS sender_display_name
                  FROM email_campaigns c
                  LEFT JOIN email_senders s ON s.id = c.sender_id
                """
                + whereClause(f, hasQ)
                + " ORDER BY " + sortClause(f.sortKey(), f.sortDirection())
                + " LIMIT :limit OFFSET :offset";

        var spec = bindFilters(db.sql(sql), f, hasQ, search)
                .bind("limit", limit)
                .bind("offset", offset);
        return spec.map(this::toRow).all();
    }

    public Mono<Long> count(EmailCampaignFilterParams f) {
        boolean hasQ = f.q() != null && !f.q().isBlank();
        String search = hasQ ? "%" + f.q().toLowerCase() + "%" : null;

        String sql = """
                SELECT COUNT(*) AS total FROM email_campaigns c
                  LEFT JOIN email_senders s ON s.id = c.sender_id
                """
                + whereClause(f, hasQ);
        var spec = bindFilters(db.sql(sql), f, hasQ, search);
        return spec.map(row -> ((Number) row.get("total")).longValue()).one();
    }

    private String whereClause(EmailCampaignFilterParams f, boolean hasQ) {
        StringBuilder sb = new StringBuilder(" WHERE 1 = 1 ");
        if (f.status() != null && !f.status().isBlank()) {
            sb.append(" AND c.status = :status ");
        }
        if (f.senderId() != null) {
            sb.append(" AND c.sender_id = :senderId ");
        }
        if (hasQ) {
            sb.append(" AND (LOWER(c.subject) LIKE :search "
                    + "     OR LOWER(COALESCE(s.address, '')) LIKE :search "
                    + "     OR LOWER(COALESCE(s.display_name, '')) LIKE :search) ");
        }
        return sb.toString();
    }

    private DatabaseClient.GenericExecuteSpec bindFilters(DatabaseClient.GenericExecuteSpec spec,
                                                          EmailCampaignFilterParams f,
                                                          boolean hasQ,
                                                          String search) {
        if (f.status() != null && !f.status().isBlank()) {
            spec = spec.bind("status", f.status());
        }
        if (f.senderId() != null) {
            spec = spec.bind("senderId", f.senderId());
        }
        if (hasQ) spec = spec.bind("search", search);
        return spec;
    }

    private String sortClause(String sortKey, String sortDirection) {
        String col = SORT_COLUMNS.getOrDefault(sortKey, "c.created_at");
        String dir = "asc".equalsIgnoreCase(sortDirection) ? "ASC" : "DESC";
        return col + " " + dir + " NULLS LAST, c.id ASC";
    }

    private EmailCampaignRow toRow(io.r2dbc.spi.Readable row) {
        return new EmailCampaignRow(
                row.get("id", UUID.class),
                row.get("sender_id", UUID.class),
                row.get("sender_address", String.class),
                row.get("sender_display_name", String.class),
                row.get("subject", String.class),
                row.get("status", String.class),
                row.get("scheduled_for", Instant.class),
                row.get("sent_at", Instant.class),
                row.get("recipient_count", Integer.class),
                row.get("created_at", Instant.class),
                row.get("updated_at", Instant.class)
        );
    }
}
