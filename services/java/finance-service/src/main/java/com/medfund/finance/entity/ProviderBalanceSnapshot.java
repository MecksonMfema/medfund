package com.medfund.finance.entity;

import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Freeze-frame of a provider's balance at payment-run execution (Phase 6,
 * V080). Written inside the run's transaction by
 * {@code PaymentRunService.snapshotBalances}, so any past run's creditor
 * state stays reproducible even though the live {@link ProviderBalance}
 * keeps moving on claim adjudication, CTC commits and advance drawdowns.
 *
 * <p>Per grilling D6-1 this is a pure freeze-frame: {@code opening_balance}
 * equals {@code closing_balance} equals the live outstanding balance as it
 * stood at {@code taken_at}. {@code net_due} carries the run's payout for
 * that payee (from its advice). One row per (payment_run, provider, currency).
 */
@Getter
@Setter
@Table("provider_balance_snapshot")
public class ProviderBalanceSnapshot {

    @Id
    private UUID id;

    @Column("payment_run_id")
    private UUID paymentRunId;

    @Column("provider_id")
    private UUID providerId;

    @Column("currency_code")
    private String currencyCode;

    @Column("opening_balance")
    private BigDecimal openingBalance = BigDecimal.ZERO;

    @Column("closing_balance")
    private BigDecimal closingBalance = BigDecimal.ZERO;

    @Column("total_claimed")
    private BigDecimal totalClaimed = BigDecimal.ZERO;

    @Column("total_approved")
    private BigDecimal totalApproved = BigDecimal.ZERO;

    @Column("total_paid")
    private BigDecimal totalPaid = BigDecimal.ZERO;

    @Column("net_due")
    private BigDecimal netDue = BigDecimal.ZERO;

    @Column("taken_at")
    private Instant takenAt;
}
