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
 * Freeze-frame of a member's balance at payment-run execution (Phase 6,
 * V080) — the member-side mirror of {@link ProviderBalanceSnapshot}.
 * Written inside the run's transaction by
 * {@code PaymentRunService.snapshotBalances}; rows exist only for members
 * present in the run's items (D6-2). Pure freeze-frame (D6-1): opening
 * equals closing equals the live outstanding balance at {@code taken_at},
 * with the run's {@code net_due} attached.
 */
@Getter
@Setter
@Table("member_balance_snapshot")
public class MemberBalanceSnapshot {

    @Id
    private UUID id;

    @Column("payment_run_id")
    private UUID paymentRunId;

    @Column("member_id")
    private UUID memberId;

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
