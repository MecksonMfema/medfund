package com.medfund.finance.repository;

import com.medfund.finance.dto.AdvancePaymentFilterParams;
import com.medfund.finance.dto.AdvancePaymentRow;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Repository
public class AdvancePaymentQueryRepository {

    private static final Map<String, String> SORT_COLUMNS = Map.of(
            "providerName", "COALESCE(pr.name, '')",
            "memberName",   "COALESCE(m.first_name || ' ' || m.last_name, '')",
            "amount",       "a.amount",
            "currencyCode", "a.currency_code",
            "recordedAt",   "a.recorded_at"
    );

    private final DatabaseClient db;

    public AdvancePaymentQueryRepository(DatabaseClient db) {
        this.db = db;
    }

    public Flux<AdvancePaymentRow> search(AdvancePaymentFilterParams f, int limit, int offset) {
        boolean hasQ = f.q() != null && !f.q().isBlank();
        String search = hasQ ? "%" + f.q().toLowerCase() + "%" : null;

        String sql = """
                SELECT a.id, a.payment_id, a.provider_id, a.member_id, a.amount,
                       a.currency_code, a.payment_method, a.reference, a.comment,
                       a.recorded_at, a.type, a.status,
                       pr.name AS provider_name,
                       COALESCE(m.first_name || ' ' || m.last_name, '') AS member_name,
                       m.member_number AS member_number
                  FROM advance_payments a
                  LEFT JOIN providers pr ON pr.id = a.provider_id
                  LEFT JOIN members   m  ON m.id  = a.member_id
                """
                + whereClause(f, hasQ)
                + " ORDER BY " + sortClause(f.sortKey(), f.sortDirection())
                + " LIMIT :limit OFFSET :offset";
        var spec = bindFilters(db.sql(sql), f, hasQ, search)
                .bind("limit", limit)
                .bind("offset", offset);
        return spec.map(this::toRow).all();
    }

    public Mono<Long> count(AdvancePaymentFilterParams f) {
        boolean hasQ = f.q() != null && !f.q().isBlank();
        String search = hasQ ? "%" + f.q().toLowerCase() + "%" : null;

        String sql = "SELECT COUNT(*) AS total FROM advance_payments a "
                + " LEFT JOIN providers pr ON pr.id = a.provider_id "
                + " LEFT JOIN members   m  ON m.id  = a.member_id "
                + whereClause(f, hasQ);
        var spec = bindFilters(db.sql(sql), f, hasQ, search);
        return spec.map(row -> ((Number) row.get("total")).longValue()).one();
    }

    private String whereClause(AdvancePaymentFilterParams f, boolean hasQ) {
        StringBuilder sb = new StringBuilder(" WHERE 1 = 1 ");
        if (f.providerId() != null) sb.append(" AND a.provider_id = :providerId ");
        if (f.memberId() != null)   sb.append(" AND a.member_id = :memberId ");
        if (f.currencyCode() != null && !f.currencyCode().isBlank()) {
            sb.append(" AND UPPER(a.currency_code) = UPPER(:currencyCode) ");
        }
        if (hasQ) {
            sb.append(" AND (LOWER(COALESCE(a.reference, '')) LIKE :search "
                   + "     OR LOWER(COALESCE(a.comment, '')) LIKE :search "
                   + "     OR LOWER(COALESCE(pr.name, '')) LIKE :search "
                   + "     OR LOWER(COALESCE(m.first_name || ' ' || m.last_name, '')) LIKE :search) ");
        }
        return sb.toString();
    }

    private DatabaseClient.GenericExecuteSpec bindFilters(DatabaseClient.GenericExecuteSpec spec,
                                                          AdvancePaymentFilterParams f,
                                                          boolean hasQ,
                                                          String search) {
        if (f.providerId() != null) spec = spec.bind("providerId", f.providerId());
        if (f.memberId() != null)   spec = spec.bind("memberId", f.memberId());
        if (f.currencyCode() != null && !f.currencyCode().isBlank()) {
            spec = spec.bind("currencyCode", f.currencyCode());
        }
        if (hasQ) spec = spec.bind("search", search);
        return spec;
    }

    private String sortClause(String sortKey, String sortDirection) {
        String col = SORT_COLUMNS.getOrDefault(sortKey, "a.recorded_at");
        String dir = "asc".equalsIgnoreCase(sortDirection) ? "ASC" : "DESC";
        return col + " " + dir + " NULLS LAST, a.id ASC";
    }

    private AdvancePaymentRow toRow(io.r2dbc.spi.Readable row) {
        return new AdvancePaymentRow(
                row.get("id", UUID.class),
                row.get("payment_id", UUID.class),
                row.get("provider_id", UUID.class),
                row.get("provider_name", String.class),
                row.get("member_id", UUID.class),
                row.get("member_name", String.class),
                row.get("member_number", String.class),
                row.get("amount", BigDecimal.class),
                row.get("currency_code", String.class),
                row.get("payment_method", String.class),
                row.get("reference", String.class),
                row.get("comment", String.class),
                row.get("recorded_at", Instant.class),
                row.get("type", String.class),
                row.get("status", String.class)
        );
    }
}
