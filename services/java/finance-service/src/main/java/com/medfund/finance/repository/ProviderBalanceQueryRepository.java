package com.medfund.finance.repository;

import com.medfund.finance.dto.ProviderBalanceFilterParams;
import com.medfund.finance.dto.ProviderBalanceRow;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Dynamic-SQL search powering the provider-balances (creditors) list.
 * Joins providers so the operational table renders the provider name
 * inline.
 */
@Repository
public class ProviderBalanceQueryRepository {

    private static final Map<String, String> SORT_COLUMNS = Map.of(
            "providerName",       "COALESCE(pr.name, '')",
            "totalClaimed",       "b.total_claimed",
            "totalApproved",      "b.total_approved",
            "totalPaid",          "b.total_paid",
            "outstandingBalance", "b.outstanding_balance",
            "currencyCode",       "b.currency_code",
            "lastUpdatedAt",      "b.last_updated_at"
    );

    private final DatabaseClient db;

    public ProviderBalanceQueryRepository(DatabaseClient db) {
        this.db = db;
    }

    public Flux<ProviderBalanceRow> search(ProviderBalanceFilterParams f, int limit, int offset) {
        boolean hasQ = f.q() != null && !f.q().isBlank();
        String search = hasQ ? "%" + f.q().toLowerCase() + "%" : null;

        String sql = """
                SELECT b.id, b.provider_id, b.total_claimed, b.total_approved,
                       b.total_paid, b.outstanding_balance, b.currency_code,
                       b.last_updated_at, pr.name AS provider_name
                  FROM provider_balances b
                  LEFT JOIN providers pr ON pr.id = b.provider_id
                """
                + whereClause(f, hasQ)
                + " ORDER BY " + sortClause(f.sortKey(), f.sortDirection())
                + " LIMIT :limit OFFSET :offset";
        var spec = bindFilters(db.sql(sql), f, hasQ, search)
                .bind("limit", limit)
                .bind("offset", offset);
        return spec.map(this::toRow).all();
    }

    public Mono<Long> count(ProviderBalanceFilterParams f) {
        boolean hasQ = f.q() != null && !f.q().isBlank();
        String search = hasQ ? "%" + f.q().toLowerCase() + "%" : null;

        String sql = "SELECT COUNT(*) AS total FROM provider_balances b "
                + " LEFT JOIN providers pr ON pr.id = b.provider_id "
                + whereClause(f, hasQ);
        var spec = bindFilters(db.sql(sql), f, hasQ, search);
        return spec.map(row -> ((Number) row.get("total")).longValue()).one();
    }

    private String whereClause(ProviderBalanceFilterParams f, boolean hasQ) {
        StringBuilder sb = new StringBuilder(" WHERE 1 = 1 ");
        if (f.currencyCode() != null && !f.currencyCode().isBlank()) {
            sb.append(" AND UPPER(b.currency_code) = UPPER(:currencyCode) ");
        }
        if (hasQ) {
            sb.append(" AND LOWER(COALESCE(pr.name, '')) LIKE :search ");
        }
        return sb.toString();
    }

    private DatabaseClient.GenericExecuteSpec bindFilters(DatabaseClient.GenericExecuteSpec spec,
                                                          ProviderBalanceFilterParams f,
                                                          boolean hasQ,
                                                          String search) {
        if (f.currencyCode() != null && !f.currencyCode().isBlank()) {
            spec = spec.bind("currencyCode", f.currencyCode());
        }
        if (hasQ) spec = spec.bind("search", search);
        return spec;
    }

    private String sortClause(String sortKey, String sortDirection) {
        String col = SORT_COLUMNS.getOrDefault(sortKey, "b.outstanding_balance");
        String dir = "asc".equalsIgnoreCase(sortDirection) ? "ASC" : "DESC";
        return col + " " + dir + " NULLS LAST, b.id ASC";
    }

    private ProviderBalanceRow toRow(io.r2dbc.spi.Readable row) {
        return new ProviderBalanceRow(
                row.get("id", UUID.class),
                row.get("provider_id", UUID.class),
                row.get("provider_name", String.class),
                row.get("total_claimed", BigDecimal.class),
                row.get("total_approved", BigDecimal.class),
                row.get("total_paid", BigDecimal.class),
                row.get("outstanding_balance", BigDecimal.class),
                row.get("currency_code", String.class),
                row.get("last_updated_at", Instant.class)
        );
    }
}
