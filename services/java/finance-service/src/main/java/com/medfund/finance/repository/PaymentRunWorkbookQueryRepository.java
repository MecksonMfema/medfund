package com.medfund.finance.repository;

import com.medfund.finance.dto.PaymentRunWorkbookRow;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Read projection backing the payment-run workbook export (Phase 7): every
 * item in the run joined to its payment (number / method / reference / paid
 * date) and to its payee via providers/members so the sheet rows carry
 * human-friendly context (D7-4). Same payee-name shape as
 * {@code PaymentQueryRepository}: provider name wins, member is
 * {@code first_name || ' ' || last_name}, else blank.
 */
@Repository
public class PaymentRunWorkbookQueryRepository {

    private final DatabaseClient db;

    public PaymentRunWorkbookQueryRepository(DatabaseClient db) {
        this.db = db;
    }

    public Flux<PaymentRunWorkbookRow> findByRunId(UUID runId) {
        String sql = """
                SELECT i.id,
                       i.payment_id,
                       p.payment_number,
                       i.payee_type,
                       i.provider_id,
                       i.member_id,
                       COALESCE(pv.name,
                                TRIM(BOTH ' ' FROM COALESCE(mb.first_name, '') || ' ' || COALESCE(mb.last_name, '')),
                                '') AS payee_name,
                       i.amount,
                       i.currency_code,
                       i.status,
                       p.payment_method,
                       p.reference,
                       p.paid_at,
                       i.created_at
                  FROM payment_run_items i
                  LEFT JOIN payments   p  ON p.id  = i.payment_id
                  LEFT JOIN providers  pv ON pv.id = i.provider_id
                  LEFT JOIN members    mb ON mb.id = i.member_id
                 WHERE i.payment_run_id = :runId
                 ORDER BY i.created_at, i.id
                """;
        return db.sql(sql)
                .bind("runId", runId)
                .map((row, meta) -> new PaymentRunWorkbookRow(
                        row.get("id", UUID.class),
                        row.get("payment_id", UUID.class),
                        row.get("payment_number", String.class),
                        row.get("payee_type", String.class),
                        row.get("provider_id", UUID.class),
                        row.get("member_id", UUID.class),
                        row.get("payee_name", String.class),
                        row.get("amount", BigDecimal.class),
                        row.get("currency_code", String.class),
                        row.get("status", String.class),
                        row.get("payment_method", String.class),
                        row.get("reference", String.class),
                        row.get("paid_at", Instant.class),
                        row.get("created_at", Instant.class)))
                .all();
    }
}
