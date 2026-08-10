package com.medfund.finance.repository;

import com.medfund.finance.dto.PaymentAdviceFilterParams;
import com.medfund.finance.dto.PaymentAdviceRowResponse;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Dynamic-SQL search powering the paged payment-advices list. Joins
 * {@code payment_runs} to surface {@code run_number}, and
 * {@code providers}/{@code members} to surface a human-friendly
 * {@code payeeName} so the Angular list doesn't have to substring UUIDs.
 */
@Repository
public class PaymentAdviceQueryRepository {

    private static final Map<String, String> SORT_COLUMNS = Map.ofEntries(
            Map.entry("adviceNumber",   "pa.advice_number"),
            Map.entry("payeeType",      "pa.payee_type"),
            Map.entry("status",         "pa.status"),
            Map.entry("netDueAmount",   "pa.net_due_amount"),
            Map.entry("totalAmount",    "pa.total_amount"),
            Map.entry("claimCount",     "pa.claim_count"),
            Map.entry("currencyCode",   "pa.currency_code"),
            Map.entry("issuedAt",       "pa.issued_at"),
            Map.entry("periodEndAt",    "pa.period_end_at"),
            Map.entry("createdAt",      "pa.created_at"),
            Map.entry("runNumber",      "pr.run_number")
    );

    private static final String BASE_SELECT = ""
            + "SELECT pa.id, pa.advice_number, pa.payment_run_id, "
            + "       pr.run_number, "
            + "       pa.payee_type, pa.provider_id, pa.member_id, "
            + "       COALESCE(pv.name, "
            + "                TRIM(BOTH ' ' FROM COALESCE(mb.first_name, '') || ' ' || COALESCE(mb.last_name, '')), "
            + "                '') AS payee_name, "
            + "       pa.currency_code, pa.total_amount, pa.claim_count, pa.status, "
            + "       pa.issued_at, pa.period_start_at, pa.period_end_at, "
            + "       pa.carried_in_amount, pa.claims_paid_amount, pa.ctc_applied_amount, "
            + "       pa.advance_applied_amount, pa.tax_withheld_amount, pa.shortfall_amount, "
            + "       pa.net_due_amount, pa.created_at "
            + "  FROM payment_advices pa "
            + "  LEFT JOIN payment_runs pr ON pr.id = pa.payment_run_id "
            + "  LEFT JOIN providers    pv ON pv.id = pa.provider_id "
            + "  LEFT JOIN members      mb ON mb.id = pa.member_id ";

    private final DatabaseClient db;

    public PaymentAdviceQueryRepository(DatabaseClient db) {
        this.db = db;
    }

    public Flux<PaymentAdviceRowResponse> search(PaymentAdviceFilterParams f, int limit, int offset) {
        boolean hasQ = f.q() != null && !f.q().isBlank();
        String search = hasQ ? "%" + f.q().toLowerCase() + "%" : null;

        String sql = BASE_SELECT
                + whereClause(f, hasQ)
                + " ORDER BY " + sortClause(f.sortKey(), f.sortDirection())
                + " LIMIT :limit OFFSET :offset";
        var spec = bindFilters(db.sql(sql), f, hasQ, search)
                .bind("limit", limit)
                .bind("offset", offset);
        return spec.map(this::toRow).all();
    }

    public Mono<Long> count(PaymentAdviceFilterParams f) {
        boolean hasQ = f.q() != null && !f.q().isBlank();
        String search = hasQ ? "%" + f.q().toLowerCase() + "%" : null;

        // Same joins as BASE_SELECT so a q-filter against provider / member
        // names counts consistently with what search() returns.
        String sql = "SELECT COUNT(*) AS total FROM payment_advices pa "
                + "  LEFT JOIN payment_runs pr ON pr.id = pa.payment_run_id "
                + "  LEFT JOIN providers    pv ON pv.id = pa.provider_id "
                + "  LEFT JOIN members      mb ON mb.id = pa.member_id "
                + whereClause(f, hasQ);
        var spec = bindFilters(db.sql(sql), f, hasQ, search);
        return spec.map(row -> ((Number) row.get("total")).longValue()).one();
    }

    private String whereClause(PaymentAdviceFilterParams f, boolean hasQ) {
        StringBuilder sb = new StringBuilder(" WHERE 1 = 1 ");
        if (f.paymentRunId() != null)                                sb.append(" AND pa.payment_run_id = :paymentRunId ");
        if (f.providerId()   != null)                                sb.append(" AND pa.provider_id    = :providerId ");
        if (f.memberId()     != null)                                sb.append(" AND pa.member_id      = :memberId ");
        if (f.payeeType()    != null && !f.payeeType().isBlank())    sb.append(" AND UPPER(pa.payee_type) = UPPER(:payeeType) ");
        if (f.status()       != null && !f.status().isBlank())       sb.append(" AND LOWER(pa.status)     = LOWER(:status) ");
        if (f.currencyCode() != null && !f.currencyCode().isBlank()) sb.append(" AND UPPER(pa.currency_code) = UPPER(:currencyCode) ");
        if (f.periodStart()  != null)                                sb.append(" AND pa.period_end_at >= :periodStart ");
        if (f.periodEnd()    != null)                                sb.append(" AND pa.period_end_at <= :periodEnd ");
        if (hasQ) {
            sb.append(" AND ( LOWER(COALESCE(pa.advice_number, '')) LIKE :search ")
              .append("    OR LOWER(COALESCE(pr.run_number,     '')) LIKE :search ")
              .append("    OR LOWER(COALESCE(pv.name,           '')) LIKE :search ")
              .append("    OR LOWER(COALESCE(mb.first_name,     '')) LIKE :search ")
              .append("    OR LOWER(COALESCE(mb.last_name,      '')) LIKE :search ) ");
        }
        return sb.toString();
    }

    private DatabaseClient.GenericExecuteSpec bindFilters(DatabaseClient.GenericExecuteSpec spec,
                                                          PaymentAdviceFilterParams f,
                                                          boolean hasQ,
                                                          String search) {
        if (f.paymentRunId() != null)                                spec = spec.bind("paymentRunId", f.paymentRunId());
        if (f.providerId()   != null)                                spec = spec.bind("providerId",   f.providerId());
        if (f.memberId()     != null)                                spec = spec.bind("memberId",     f.memberId());
        if (f.payeeType()    != null && !f.payeeType().isBlank())    spec = spec.bind("payeeType",    f.payeeType());
        if (f.status()       != null && !f.status().isBlank())       spec = spec.bind("status",       f.status());
        if (f.currencyCode() != null && !f.currencyCode().isBlank()) spec = spec.bind("currencyCode", f.currencyCode());
        if (f.periodStart()  != null)                                spec = spec.bind("periodStart",  f.periodStart());
        if (f.periodEnd()    != null)                                spec = spec.bind("periodEnd",    f.periodEnd());
        if (hasQ)                                                    spec = spec.bind("search",       search);
        return spec;
    }

    private String sortClause(String sortKey, String sortDirection) {
        String col = SORT_COLUMNS.getOrDefault(sortKey, "pa.issued_at");
        String dir = "asc".equalsIgnoreCase(sortDirection) ? "ASC" : "DESC";
        return col + " " + dir + " NULLS LAST, pa.id ASC";
    }

    private PaymentAdviceRowResponse toRow(io.r2dbc.spi.Readable row) {
        return new PaymentAdviceRowResponse(
                row.get("id",                     UUID.class),
                row.get("advice_number",          String.class),
                row.get("payment_run_id",         UUID.class),
                row.get("run_number",             String.class),
                row.get("payee_type",             String.class),
                row.get("provider_id",            UUID.class),
                row.get("member_id",              UUID.class),
                row.get("payee_name",             String.class),
                row.get("currency_code",          String.class),
                row.get("total_amount",           BigDecimal.class),
                row.get("claim_count",            Integer.class),
                row.get("status",                 String.class),
                row.get("issued_at",              Instant.class),
                row.get("period_start_at",        Instant.class),
                row.get("period_end_at",          Instant.class),
                row.get("carried_in_amount",      BigDecimal.class),
                row.get("claims_paid_amount",     BigDecimal.class),
                row.get("ctc_applied_amount",     BigDecimal.class),
                row.get("advance_applied_amount", BigDecimal.class),
                row.get("tax_withheld_amount",    BigDecimal.class),
                row.get("shortfall_amount",       BigDecimal.class),
                row.get("net_due_amount",         BigDecimal.class),
                row.get("created_at",             Instant.class)
        );
    }
}
