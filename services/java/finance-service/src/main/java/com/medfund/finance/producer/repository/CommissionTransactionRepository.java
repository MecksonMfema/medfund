package com.medfund.finance.producer.repository;

import com.medfund.finance.producer.entity.CommissionTransaction;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.UUID;

public interface CommissionTransactionRepository extends R2dbcRepository<CommissionTransaction, UUID> {

    /**
     * All non-reversal accruals for the member with a status in {@code statuses}.
     * Used by the clawback service to enumerate ACCRUED / PAID rows on member
     * lapse. Skips REVERSED / CLAWED_BACK / VOIDED so clawback is idempotent
     * on replay.
     */
    @Query("""
            SELECT * FROM commission_transaction
             WHERE member_id = :memberId
               AND reversal_of_txn_id IS NULL
               AND status IN (:statuses)
             ORDER BY occurred_at
            """)
    Flux<CommissionTransaction> findByMemberIdAndStatusIn(UUID memberId, Collection<String> statuses);

    /**
     * Find the original (non-reversal) accrual for a given contribution. Used
     * by the contribution-revoke consumer to locate the row to compensate.
     * Filters {@code reversal_of_txn_id IS NULL} so reruns key off the
     * original, not its compensating twin.
     */
    @Query("""
            SELECT * FROM commission_transaction
             WHERE contribution_id = :contributionId
               AND reversal_of_txn_id IS NULL
             ORDER BY occurred_at
             LIMIT 1
            """)
    Mono<CommissionTransaction> findByContributionIdAndReversalOfTxnIdIsNull(UUID contributionId);

    @Query("SELECT COUNT(*) FROM commission_transaction WHERE reference LIKE :prefix || '%'")
    Mono<Long> countByReferenceStartingWith(String prefix);

    /**
     * Aggregate ACCRUED commissions ready for payout. Bucketed by
     * {@code (producer, nativeCurrency)} — one producer with commissions in
     * two contribution currencies yields two rows, letting the caller
     * convert each leg independently to the producer's home currency at
     * commit-time FX.
     *
     * <p>Filters by {@code home_currency = :homeCurrency} so the resulting
     * PaymentRun stays homogeneous by (home_currency, period) — an invariant
     * the V072 item-parent trigger enforces at the DB layer.
     *
     * <p>Half-open interval {@code [periodStart, periodEnd)} — the caller
     * passes the exclusive end (period_end + 1 day at 00:00 UTC).
     */
    @Query("""
            SELECT ct.producer_id       AS producer_id,
                   p.home_currency      AS home_currency,
                   SUM(ct.native_amount) AS total_native,
                   ct.native_currency   AS native_currency,
                   COUNT(*)             AS txn_count
              FROM commission_transaction ct
              JOIN producer p ON p.id = ct.producer_id
             WHERE ct.status = 'ACCRUED'
               AND p.home_currency = :homeCurrency
               AND ct.occurred_at >= :periodStart
               AND ct.occurred_at <  :periodEnd
             GROUP BY ct.producer_id, p.home_currency, ct.native_currency
             ORDER BY ct.producer_id
            """)
    Flux<ProducerCommissionSummary> aggregateForPayout(String homeCurrency,
                                                       OffsetDateTime periodStart,
                                                       OffsetDateTime periodEnd);

    /**
     * Flip every ACCRUED commission for {@code producerId} in the period
     * to PAID, stamping the run id + paidAt. Called on payment-run execute
     * so a subsequent aggregation for the same period returns zero rows
     * (defence in depth — the caller should also skip already-paid rows).
     *
     * <p>Same half-open interval as {@link #aggregateForPayout}.
     */
    @Query("""
            UPDATE commission_transaction
               SET status = 'PAID',
                   paid_run_id = :runId,
                   paid_at = NOW(),
                   updated_at = NOW()
             WHERE producer_id = :producerId
               AND status = 'ACCRUED'
               AND occurred_at >= :periodStart
               AND occurred_at <  :periodEnd
            """)
    Mono<Integer> markPaidByProducerAndPeriod(UUID producerId, UUID runId,
                                              OffsetDateTime periodStart,
                                              OffsetDateTime periodEnd);
}
