package com.medfund.finance.repository;

import com.medfund.finance.dto.PaymentFilterParams;
import com.medfund.finance.dto.PaymentRow;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Dynamic-SQL search powering the payments list. Joins providers so
 * the operational table renders the provider name inline.
 */
@Repository
public class PaymentQueryRepository {

    private static final Map<String, String> SORT_COLUMNS = Map.of(
            "paymentNumber", "p.payment_number",
            "providerName",  "COALESCE(pr.name, '')",
            "amount",        "p.amount",
            "currencyCode",  "p.currency_code",
            "paymentType",   "p.payment_type",
            "status",        "p.status",
            "paidAt",        "p.paid_at",
            "createdAt",     "p.created_at"
    );

    private final DatabaseClient db;

    public PaymentQueryRepository(DatabaseClient db) {
        this.db = db;
    }

    public Flux<PaymentRow> search(PaymentFilterParams f, int limit, int offset) {
        boolean hasQ = f.q() != null && !f.q().isBlank();
        String search = hasQ ? "%" + f.q().toLowerCase() + "%" : null;

        String sql = """
                SELECT p.id, p.payment_number, p.provider_id, p.amount, p.currency_code,
                       p.payment_type, p.status, p.payment_method, p.reference,
                       p.paid_at, p.created_at, p.updated_at,
                       pr.name AS provider_name
                  FROM payments p
                  LEFT JOIN providers pr ON pr.id = p.provider_id
                """
                + whereClause(f, hasQ)
                + " ORDER BY " + sortClause(f.sortKey(), f.sortDirection())
                + " LIMIT :limit OFFSET :offset";
        var spec = bindFilters(db.sql(sql), f, hasQ, search)
                .bind("limit", limit)
                .bind("offset", offset);
        return spec.map(this::toRow).all();
    }

    public Mono<Long> count(PaymentFilterParams f) {
        boolean hasQ = f.q() != null && !f.q().isBlank();
        String search = hasQ ? "%" + f.q().toLowerCase() + "%" : null;

        String sql = "SELECT COUNT(*) AS total FROM payments p "
                + " LEFT JOIN providers pr ON pr.id = p.provider_id "
                + whereClause(f, hasQ);
        var spec = bindFilters(db.sql(sql), f, hasQ, search);
        return spec.map(row -> ((Number) row.get("total")).longValue()).one();
    }

    private String whereClause(PaymentFilterParams f, boolean hasQ) {
        StringBuilder sb = new StringBuilder(" WHERE 1 = 1 ");
        if (f.status() != null && !f.status().isBlank()) {
            sb.append(" AND LOWER(p.status) = LOWER(:status) ");
        }
        if (f.paymentType() != null && !f.paymentType().isBlank()) {
            sb.append(" AND UPPER(p.payment_type) = UPPER(:paymentType) ");
        }
        if (f.providerId() != null) sb.append(" AND p.provider_id = :providerId ");
        if (f.currencyCode() != null && !f.currencyCode().isBlank()) {
            sb.append(" AND UPPER(p.currency_code) = UPPER(:currencyCode) ");
        }
        if (hasQ) {
            sb.append(" AND (LOWER(p.payment_number) LIKE :search "
                   + "     OR LOWER(COALESCE(p.reference, '')) LIKE :search "
                   + "     OR LOWER(COALESCE(pr.name, '')) LIKE :search) ");
        }
        return sb.toString();
    }

    private DatabaseClient.GenericExecuteSpec bindFilters(DatabaseClient.GenericExecuteSpec spec,
                                                          PaymentFilterParams f,
                                                          boolean hasQ,
                                                          String search) {
        if (f.status() != null && !f.status().isBlank())              spec = spec.bind("status", f.status());
        if (f.paymentType() != null && !f.paymentType().isBlank())    spec = spec.bind("paymentType", f.paymentType());
        if (f.providerId() != null)                                   spec = spec.bind("providerId", f.providerId());
        if (f.currencyCode() != null && !f.currencyCode().isBlank())  spec = spec.bind("currencyCode", f.currencyCode());
        if (hasQ)                                                     spec = spec.bind("search", search);
        return spec;
    }

    private String sortClause(String sortKey, String sortDirection) {
        String col = SORT_COLUMNS.getOrDefault(sortKey, "p.created_at");
        String dir = "asc".equalsIgnoreCase(sortDirection) ? "ASC" : "DESC";
        return col + " " + dir + " NULLS LAST, p.id ASC";
    }

    private PaymentRow toRow(io.r2dbc.spi.Readable row) {
        return new PaymentRow(
                row.get("id", UUID.class),
                row.get("payment_number", String.class),
                row.get("provider_id", UUID.class),
                row.get("provider_name", String.class),
                row.get("amount", BigDecimal.class),
                row.get("currency_code", String.class),
                row.get("payment_type", String.class),
                row.get("status", String.class),
                row.get("payment_method", String.class),
                row.get("reference", String.class),
                row.get("paid_at", Instant.class),
                row.get("created_at", Instant.class),
                row.get("updated_at", Instant.class)
        );
    }
}
