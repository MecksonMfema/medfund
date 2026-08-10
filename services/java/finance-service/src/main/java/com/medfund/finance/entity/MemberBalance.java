package com.medfund.finance.entity;

import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Snapshot of the fund's obligation to a single member in one currency.
 * Mirrors {@link ProviderBalance} — one row per (member_id, currency_code)
 * with the four running totals that feed the unified Creditors listing.
 *
 * <p>Written by three paths:
 * <ul>
 *   <li>{@code ClaimAdjudicatedConsumer.handleMemberPayee} — bumps
 *       {@code total_claimed} on every event, {@code total_approved}
 *       on APPROVED/PARTIAL_APPROVED.</li>
 *   <li>{@code CtcPaymentService.commit / reverse} — bumps / decrements
 *       {@code total_paid}.</li>
 *   <li>{@code PaymentService.markPaid} (MEMBER branch, V072 Phase 3) —
 *       bumps {@code total_paid}.</li>
 * </ul>
 */
@Getter
@Setter
@Table("member_balances")
public class MemberBalance {

    @Id
    private UUID id;

    @Column("member_id")
    private UUID memberId;

    @Column("total_claimed")
    private BigDecimal totalClaimed;

    @Column("total_approved")
    private BigDecimal totalApproved;

    @Column("total_paid")
    private BigDecimal totalPaid;

    @Column("outstanding_balance")
    private BigDecimal outstandingBalance;

    @Column("currency_code")
    private String currencyCode;

    @Column("last_updated_at")
    private Instant lastUpdatedAt;

    @CreatedDate
    @Column("created_at")
    private Instant createdAt;
}
