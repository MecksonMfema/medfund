package com.medfund.finance.repository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Aggregate reads over the advance-payment ledger. The outstanding balance
 * for a payee/currency is defined as:
 *
 * <pre>
 *   sum(ADVANCE.amount)
 *   - sum(REVERSAL.amount)
 *   - sum(applications.amount_applied)
 * </pre>
 *
 * Only advances with status in ('approved','applied') contribute (pending +
 * reversed rows are excluded). Rows with a non-positive net balance are
 * filtered out so callers can treat every returned row as spendable.
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class AdvancePaymentBalanceRepository {

    private final DatabaseClient db;

    public Flux<OutstandingAdvanceBalance> findOutstandingByProvider(UUID providerId) {
        return db.sql("""
                WITH totals AS (
                    SELECT ap.currency_code AS currency_code,
                           SUM(CASE WHEN ap.type = 'ADVANCE'  THEN ap.amount ELSE 0 END) AS advanced,
                           SUM(CASE WHEN ap.type = 'REVERSAL' THEN ap.amount ELSE 0 END) AS reversed
                      FROM advance_payments ap
                     WHERE ap.provider_id = :payeeId
                       AND ap.status IN ('approved', 'applied')
                     GROUP BY ap.currency_code
                ),
                applied AS (
                    SELECT ap.currency_code AS currency_code,
                           SUM(apa.amount_applied) AS applied
                      FROM advance_payment_applications apa
                      JOIN advance_payments ap ON ap.id = apa.advance_payment_id
                     WHERE ap.provider_id = :payeeId
                     GROUP BY ap.currency_code
                )
                SELECT t.currency_code AS currency_code,
                       (t.advanced - t.reversed - COALESCE(a.applied, 0)) AS outstanding
                  FROM totals t
             LEFT JOIN applied a ON a.currency_code = t.currency_code
                 WHERE (t.advanced - t.reversed - COALESCE(a.applied, 0)) > 0
                """)
                .bind("payeeId", providerId)
                .map((row, meta) -> new OutstandingAdvanceBalance(
                        row.get("currency_code", String.class),
                        row.get("outstanding", BigDecimal.class)))
                .all();
    }

    public Flux<OutstandingAdvanceBalance> findOutstandingByMember(UUID memberId) {
        return db.sql("""
                WITH totals AS (
                    SELECT ap.currency_code AS currency_code,
                           SUM(CASE WHEN ap.type = 'ADVANCE'  THEN ap.amount ELSE 0 END) AS advanced,
                           SUM(CASE WHEN ap.type = 'REVERSAL' THEN ap.amount ELSE 0 END) AS reversed
                      FROM advance_payments ap
                     WHERE ap.member_id = :payeeId
                       AND ap.status IN ('approved', 'applied')
                     GROUP BY ap.currency_code
                ),
                applied AS (
                    SELECT ap.currency_code AS currency_code,
                           SUM(apa.amount_applied) AS applied
                      FROM advance_payment_applications apa
                      JOIN advance_payments ap ON ap.id = apa.advance_payment_id
                     WHERE ap.member_id = :payeeId
                     GROUP BY ap.currency_code
                )
                SELECT t.currency_code AS currency_code,
                       (t.advanced - t.reversed - COALESCE(a.applied, 0)) AS outstanding
                  FROM totals t
             LEFT JOIN applied a ON a.currency_code = t.currency_code
                 WHERE (t.advanced - t.reversed - COALESCE(a.applied, 0)) > 0
                """)
                .bind("payeeId", memberId)
                .map((row, meta) -> new OutstandingAdvanceBalance(
                        row.get("currency_code", String.class),
                        row.get("outstanding", BigDecimal.class)))
                .all();
    }

    /**
     * How much of a single advance row remains unconsumed. Callers use this
     * to decide how much to write into the next application row without
     * overshooting the individual advance's balance.
     */
    public reactor.core.publisher.Mono<BigDecimal> remainingOn(UUID advanceId) {
        return db.sql("""
                SELECT ap.amount
                     - COALESCE((SELECT SUM(amount_applied)
                                   FROM advance_payment_applications
                                  WHERE advance_payment_id = ap.id), 0) AS remaining
                  FROM advance_payments ap
                 WHERE ap.id = :advanceId
                """)
                .bind("advanceId", advanceId)
                .map((row, meta) -> row.get("remaining", BigDecimal.class))
                .one()
                .defaultIfEmpty(BigDecimal.ZERO);
    }
}
