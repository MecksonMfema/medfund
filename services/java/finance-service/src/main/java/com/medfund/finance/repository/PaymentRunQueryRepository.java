package com.medfund.finance.repository;

import com.medfund.finance.dto.PaymentRunFilterParams;
import com.medfund.finance.entity.PaymentRun;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Dynamic-SQL search powering the payment-runs list. No joins —
 * runs are self-contained header rows.
 */
@Repository
public class PaymentRunQueryRepository {

    private static final Map<String, String> SORT_COLUMNS = Map.of(
            "runNumber",    "run_number",
            "status",       "status",
            "totalAmount",  "total_amount",
            "currencyCode", "currency_code",
            "paymentCount", "payment_count",
            "executedAt",   "executed_at",
            "createdAt",    "created_at"
    );

    private final DatabaseClient db;

    public PaymentRunQueryRepository(DatabaseClient db) {
        this.db = db;
    }

    public Flux<PaymentRun> search(PaymentRunFilterParams f, int limit, int offset) {
        boolean hasQ = f.q() != null && !f.q().isBlank();
        String search = hasQ ? "%" + f.q().toLowerCase() + "%" : null;

        String sql = "SELECT id, run_number, status, total_amount, currency_code, "
                + "       payment_count, description, executed_at, executed_by, "
                + "       carried_in_amount, carried_out_amount, settlement_date, "
                + "       created_at, updated_at "
                + "  FROM payment_runs "
                + whereClause(f, hasQ)
                + " ORDER BY " + sortClause(f.sortKey(), f.sortDirection())
                + " LIMIT :limit OFFSET :offset";
        var spec = bindFilters(db.sql(sql), f, hasQ, search)
                .bind("limit", limit)
                .bind("offset", offset);
        return spec.map(this::toEntity).all();
    }

    public Mono<Long> count(PaymentRunFilterParams f) {
        boolean hasQ = f.q() != null && !f.q().isBlank();
        String search = hasQ ? "%" + f.q().toLowerCase() + "%" : null;

        String sql = "SELECT COUNT(*) AS total FROM payment_runs " + whereClause(f, hasQ);
        var spec = bindFilters(db.sql(sql), f, hasQ, search);
        return spec.map(row -> ((Number) row.get("total")).longValue()).one();
    }

    private String whereClause(PaymentRunFilterParams f, boolean hasQ) {
        StringBuilder sb = new StringBuilder(" WHERE 1 = 1 ");
        if (f.status() != null && !f.status().isBlank()) {
            sb.append(" AND LOWER(status) = LOWER(:status) ");
        }
        if (f.currencyCode() != null && !f.currencyCode().isBlank()) {
            sb.append(" AND UPPER(currency_code) = UPPER(:currencyCode) ");
        }
        if (hasQ) {
            sb.append(" AND (LOWER(run_number) LIKE :search "
                   + "     OR LOWER(COALESCE(description, '')) LIKE :search) ");
        }
        return sb.toString();
    }

    private DatabaseClient.GenericExecuteSpec bindFilters(DatabaseClient.GenericExecuteSpec spec,
                                                          PaymentRunFilterParams f,
                                                          boolean hasQ,
                                                          String search) {
        if (f.status() != null && !f.status().isBlank())              spec = spec.bind("status", f.status());
        if (f.currencyCode() != null && !f.currencyCode().isBlank())  spec = spec.bind("currencyCode", f.currencyCode());
        if (hasQ) spec = spec.bind("search", search);
        return spec;
    }

    private String sortClause(String sortKey, String sortDirection) {
        String col = SORT_COLUMNS.getOrDefault(sortKey, "created_at");
        String dir = "asc".equalsIgnoreCase(sortDirection) ? "ASC" : "DESC";
        return col + " " + dir + " NULLS LAST, id ASC";
    }

    private PaymentRun toEntity(io.r2dbc.spi.Readable row) {
        PaymentRun r = new PaymentRun();
        r.setId(row.get("id", UUID.class));
        r.setRunNumber(row.get("run_number", String.class));
        r.setStatus(row.get("status", String.class));
        r.setTotalAmount(row.get("total_amount", BigDecimal.class));
        r.setCurrencyCode(row.get("currency_code", String.class));
        r.setPaymentCount(row.get("payment_count", Integer.class));
        r.setDescription(row.get("description", String.class));
        r.setExecutedAt(row.get("executed_at", Instant.class));
        r.setExecutedBy(row.get("executed_by", UUID.class));
        r.setCarriedInAmount(row.get("carried_in_amount", BigDecimal.class));
        r.setCarriedOutAmount(row.get("carried_out_amount", BigDecimal.class));
        r.setSettlementDate(row.get("settlement_date", Instant.class));
        r.setCreatedAt(row.get("created_at", Instant.class));
        r.setUpdatedAt(row.get("updated_at", Instant.class));
        return r;
    }
}
