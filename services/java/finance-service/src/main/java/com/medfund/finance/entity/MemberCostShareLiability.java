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
 * Finance-side record of "what this member owes on this adjudicated claim"
 * (V078, Phase 4 copayments). One row per adjudicated claim, written by
 * {@code ClaimAdjudicatedConsumer} when the enriched
 * {@code medfund.claims.adjudicated} payload includes a non-null
 * {@code memberResponsibility}.
 *
 * <p>Sibling of {@link MemberPayable} — the payable tracks fund → member
 * (out-of-pocket reimbursement); this row tracks member → fund
 * (cost-share debt). Same event ({@code claim.adjudicated}) writes both
 * when the claim has both a plan-paid portion and a member share.
 *
 * <p>The cash-first path (payeeType=MEMBER) pre-sets {@code status='SETTLED'}
 * and writes a synthetic {@link MemberCostShareSettlement}
 * ({@code source='MEMBER_PAID_PROVIDER'}) per G12 — the member paid the
 * provider up-front, so the liability is closed at creation.
 */
@Getter
@Setter
@Table("member_cost_share_liability")
public class MemberCostShareLiability {

    @Id
    private UUID id;

    @Column("member_id")
    private UUID memberId;

    @Column("claim_id")
    private UUID claimId;

    @Column("claim_number")
    private String claimNumber;

    private BigDecimal deductible;
    private BigDecimal copay;
    private BigDecimal coinsurance;
    private BigDecimal shortfall;

    @Column("not_covered")
    private BigDecimal notCovered;

    @Column("total_owed")
    private BigDecimal totalOwed;

    @Column("total_settled")
    private BigDecimal totalSettled;

    @Column("currency_code")
    private String currencyCode;

    /** G6: benefit currency captured for downstream reporting. Null when the
     *  benefit and claim currencies match. */
    @Column("currency_code_original")
    private String currencyCodeOriginal;

    /** OPEN | PARTIALLY_SETTLED | SETTLED | WRITTEN_OFF. */
    private String status;

    @Column("created_at")
    private Instant createdAt;

    @Column("updated_at")
    private Instant updatedAt;
}
