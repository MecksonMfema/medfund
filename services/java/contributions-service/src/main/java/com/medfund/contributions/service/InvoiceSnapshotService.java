package com.medfund.contributions.service;

import com.medfund.contributions.entity.Invoice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Captures the financial snapshot that lives on an invoice at the moment
 * of commit (see plan §3b). Once written the fields are immutable —
 * historic statements stay accurate even if a back-dated transaction
 * lands later, and the next invoice's window starts at this one's
 * {@code committed_at} so no transaction is ever consumed by two
 * statements.
 *
 * <p>The window for payments + adjustments is the half-open interval
 * {@code [prior.committed_at, this.committed_at)}. A transaction with
 * {@code created_at} equal to {@code this.committed_at} belongs on the
 * NEXT invoice — strict-less-than per the user's instant-precise rule.
 *
 * <p>Sign convention (mirrors {@code StatementService.assemble}):
 * <ul>
 *   <li>{@code transaction_types.sign = '-'} → reduces the balance
 *       (payment-like). Sum goes into {@code payments_in_window}.</li>
 *   <li>{@code transaction_types.sign = '+'} → adds to the balance
 *       (adjustment / debit note / loaded premium). Sum goes into
 *       {@code adjustments_in_window}.</li>
 * </ul>
 *
 * <p>Closing balance = opening + total_amount − payments + adjustments.
 */
@Service
public class InvoiceSnapshotService {

    private static final Logger log = LoggerFactory.getLogger(InvoiceSnapshotService.class);

    private final DatabaseClient db;

    public InvoiceSnapshotService(DatabaseClient db) {
        this.db = db;
    }

    /**
     * Populate {@code committed_at}, {@code opening_balance},
     * {@code payments_in_window}, {@code adjustments_in_window},
     * {@code closing_balance}, and {@code prior_invoice_id} on the
     * invoice in-place. Called from {@code BillingService.persistInvoiceFor}
     * inside the same {@code @Transactional} that inserts the invoice.
     *
     * @param invoice           the invoice about to be inserted; must already
     *                          have totalAmount, currencyCode, group_id|member_id set
     * @param committedAt       the single commit instant shared across all
     *                          invoices from this commit (caller passes the
     *                          same value for every invoice in one commitBilling)
     */
    public Mono<Invoice> stampSnapshot(Invoice invoice, Instant committedAt) {
        if (invoice.getCurrencyCode() == null) {
            return Mono.error(new IllegalStateException(
                    "Cannot snapshot invoice without currency_code"));
        }
        if (invoice.getGroupId() == null && invoice.getMemberId() == null) {
            return Mono.error(new IllegalStateException(
                    "Cannot snapshot invoice with neither group_id nor member_id"));
        }

        invoice.setCommittedAt(committedAt);

        return findPrior(invoice, committedAt)
                .defaultIfEmpty(Prior.EMPTY)
                .flatMap(prior -> sumWindowedTransactions(invoice, prior.committedAt(), committedAt)
                        .map(window -> {
                            BigDecimal total       = nz(invoice.getTotalAmount());
                            BigDecimal opening     = nz(prior.closingBalance());
                            BigDecimal payments    = nz(window.payments());
                            BigDecimal adjustments = nz(window.adjustments());
                            BigDecimal closing     = opening.add(total)
                                    .subtract(payments)
                                    .add(adjustments);

                            invoice.setOpeningBalance(opening);
                            invoice.setPaymentsInWindow(payments);
                            invoice.setAdjustmentsInWindow(adjustments);
                            invoice.setClosingBalance(closing);
                            invoice.setPriorInvoiceId(prior.id());

                            log.info("[snapshot] invoice={} holder={} currency={} opening={} +total={} -payments={} +adjustments={} = closing={}",
                                    invoice.getInvoiceNumber(),
                                    invoice.getGroupId() != null ? invoice.getGroupId() : invoice.getMemberId(),
                                    invoice.getCurrencyCode(),
                                    opening, total, payments, adjustments, closing);
                            return invoice;
                        }));
    }

    /**
     * The latest prior invoice for this {@code (holder, currency)} whose
     * {@code committed_at} is strictly less than the current commit instant.
     * Returns empty when this is the first invoice for the holder/currency.
     */
    private Mono<Prior> findPrior(Invoice invoice, Instant committedAt) {
        return db.sql("""
                SELECT id, closing_balance, committed_at
                  FROM invoices
                 WHERE currency_code = :currency
                   AND committed_at IS NOT NULL
                   AND committed_at < :committedAt
                   AND ( (:groupId  IS NOT NULL AND group_id  = :groupId)
                      OR (:memberId IS NOT NULL AND member_id = :memberId) )
                 ORDER BY committed_at DESC
                 LIMIT 1
                """)
                .bind("currency",    invoice.getCurrencyCode())
                .bind("committedAt", committedAt)
                .bind("groupId",     invoice.getGroupId()  != null ? invoice.getGroupId()  : new java.util.UUID(0,0))
                .bind("memberId",    invoice.getMemberId() != null ? invoice.getMemberId() : new java.util.UUID(0,0))
                .map(row -> new Prior(
                        row.get("id", UUID.class),
                        row.get("closing_balance", BigDecimal.class),
                        row.get("committed_at", Instant.class)))
                .one();
    }

    /**
     * Sum payments + adjustments posted in {@code [priorCommittedAt, committedAt)}
     * for this {@code (holder, currency)}. Transactions attach to the
     * holder via one of two paths:
     * <ol>
     *   <li>Their {@code contribution_id} points at a contribution row
     *       whose {@code group_id} / {@code member_id} matches. This is
     *       the classic per-invoice payment.</li>
     *   <li>Their own {@code group_id} / {@code member_id} (V039 direct
     *       owner columns) matches the holder — the "top of group"
     *       payment case where an operator credits the group without
     *       picking a specific contribution row.</li>
     * </ol>
     * LEFT JOIN so a transaction with {@code contribution_id IS NULL}
     * doesn't get dropped by the inner join.
     */
    /** Package-private for unit tests that capture the SQL predicate. */
    Mono<WindowSums> sumWindowedTransactions(Invoice invoice,
                                              Instant priorCommittedAt,
                                              Instant committedAt) {
        String sql = """
                SELECT
                  COALESCE(SUM(CASE WHEN tt.sign = '-' THEN t.amount ELSE 0 END), 0) AS payments,
                  COALESCE(SUM(CASE WHEN tt.sign = '+' THEN t.amount ELSE 0 END), 0) AS adjustments
                  FROM transactions t
                  LEFT JOIN contributions c     ON c.id = t.contribution_id
                  LEFT JOIN transaction_types tt ON tt.code = t.transaction_type
                 WHERE t.currency_code = :currency
                   AND t.created_at <  :committedAt
                   AND (:lower IS NULL OR t.created_at >= :lower)
                   AND ( (:groupId  IS NOT NULL AND (c.group_id  = :groupId  OR t.group_id  = :groupId))
                      OR (:memberId IS NOT NULL AND (c.member_id = :memberId OR t.member_id = :memberId)) )
                """;
        var spec = db.sql(sql)
                .bind("currency",    invoice.getCurrencyCode())
                .bind("committedAt", committedAt)
                .bind("groupId",     invoice.getGroupId()  != null ? invoice.getGroupId()  : new java.util.UUID(0,0))
                .bind("memberId",    invoice.getMemberId() != null ? invoice.getMemberId() : new java.util.UUID(0,0));
        // R2DBC: nulls must go through bindNull(name, type).
        spec = (priorCommittedAt != null) ? spec.bind("lower", priorCommittedAt)
                                          : spec.bindNull("lower", Instant.class);
        return spec
                .map(row -> new WindowSums(
                        row.get("payments", BigDecimal.class),
                        row.get("adjustments", BigDecimal.class)))
                .one()
                .defaultIfEmpty(WindowSums.ZERO);
    }

    private static BigDecimal nz(BigDecimal v) { return v != null ? v : BigDecimal.ZERO; }

    private record Prior(UUID id, BigDecimal closingBalance, Instant committedAt) {
        static final Prior EMPTY = new Prior(null, BigDecimal.ZERO, null);
    }

    /** Package-private so unit tests can assert on the returned aggregates. */
    record WindowSums(BigDecimal payments, BigDecimal adjustments) {
        static final WindowSums ZERO = new WindowSums(BigDecimal.ZERO, BigDecimal.ZERO);
    }
}
