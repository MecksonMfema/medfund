package com.medfund.finance.repository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Derived-aggregate reads over the member-payable ledger — no snapshot
 * table means the balance is always consistent by construction.
 * Outstanding-per-currency for a member is:
 *
 * <pre>
 *   sum(member_payables.amount)
 *   - sum(member_payable_applications.amount_applied)
 * </pre>
 *
 * Only payables in status ('open','applied') contribute — 'reversed' rows
 * are excluded. Rows with a non-positive net balance are filtered out so
 * callers can treat every returned row as spendable by a new CTC.
 *
 * <p>Cloned from {@code AdvancePaymentBalanceRepository} — see design
 * decision #2 in {@code thoughts/shared/plans/2026-08-09-ctc-payments.md}
 * for the rationale (low volume, no historical claimed/approved/paid
 * breakdown needed, no drift risk).
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class MemberPayableBalanceRepository {

    private final DatabaseClient db;

    public Flux<OutstandingMemberPayable> findOutstandingByMember(UUID memberId) {
        return db.sql("""
                WITH totals AS (
                    SELECT mp.currency_code AS currency_code,
                           SUM(mp.amount) AS payable
                      FROM member_payables mp
                     WHERE mp.member_id = :memberId
                       AND mp.status IN ('open', 'applied')
                     GROUP BY mp.currency_code
                ),
                applied AS (
                    SELECT mp.currency_code AS currency_code,
                           SUM(mpa.amount_applied) AS applied
                      FROM member_payable_applications mpa
                      JOIN member_payables mp ON mp.id = mpa.member_payable_id
                     WHERE mp.member_id = :memberId
                     GROUP BY mp.currency_code
                )
                SELECT t.currency_code AS currency_code,
                       (t.payable - COALESCE(a.applied, 0)) AS outstanding
                  FROM totals t
             LEFT JOIN applied a ON a.currency_code = t.currency_code
                 WHERE (t.payable - COALESCE(a.applied, 0)) > 0
                """)
                .bind("memberId", memberId)
                .map((row, meta) -> new OutstandingMemberPayable(
                        row.get("currency_code", String.class),
                        row.get("outstanding", BigDecimal.class)))
                .all();
    }

    /**
     * All members with a positive outstanding balance in the given
     * currency — used by the payment-run generator to enumerate MEMBER
     * payees to bundle into a new run. Extends the per-member CTE to
     * group by member as well as currency and filters by the requested
     * currency.
     */
    public Flux<OutstandingMemberPayableByMember> findOutstandingByCurrency(String currencyCode) {
        return db.sql("""
                WITH totals AS (
                    SELECT mp.member_id AS member_id,
                           mp.currency_code AS currency_code,
                           SUM(mp.amount) AS payable
                      FROM member_payables mp
                     WHERE mp.currency_code = :currencyCode
                       AND mp.status IN ('open', 'applied')
                     GROUP BY mp.member_id, mp.currency_code
                ),
                applied AS (
                    SELECT mp.member_id AS member_id,
                           mp.currency_code AS currency_code,
                           SUM(mpa.amount_applied) AS applied
                      FROM member_payable_applications mpa
                      JOIN member_payables mp ON mp.id = mpa.member_payable_id
                     WHERE mp.currency_code = :currencyCode
                     GROUP BY mp.member_id, mp.currency_code
                )
                SELECT t.member_id AS member_id,
                       t.currency_code AS currency_code,
                       (t.payable - COALESCE(a.applied, 0)) AS outstanding
                  FROM totals t
             LEFT JOIN applied a
                    ON a.member_id = t.member_id
                   AND a.currency_code = t.currency_code
                 WHERE (t.payable - COALESCE(a.applied, 0)) > 0
                """)
                .bind("currencyCode", currencyCode)
                .map((row, meta) -> new OutstandingMemberPayableByMember(
                        row.get("member_id", UUID.class),
                        row.get("currency_code", String.class),
                        row.get("outstanding", BigDecimal.class)))
                .all();
    }

    /**
     * How much of a single payable remains unconsumed. Callers use this to
     * decide how much to write into the next application row without
     * overshooting the individual payable's balance.
     */
    public Mono<BigDecimal> remainingOn(UUID payableId) {
        return db.sql("""
                SELECT mp.amount
                     - COALESCE((SELECT SUM(amount_applied)
                                   FROM member_payable_applications
                                  WHERE member_payable_id = mp.id), 0) AS remaining
                  FROM member_payables mp
                 WHERE mp.id = :payableId
                """)
                .bind("payableId", payableId)
                .map((row, meta) -> row.get("remaining", BigDecimal.class))
                .one()
                .defaultIfEmpty(BigDecimal.ZERO);
    }

    public record OutstandingMemberPayable(String currencyCode, BigDecimal outstanding) {}

    public record OutstandingMemberPayableByMember(UUID memberId, String currencyCode, BigDecimal outstanding) {}
}
