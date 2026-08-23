package com.medfund.finance.producer.entity;

import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Commission accrual ledger row (V095). One row per {@code
 * (contribution, producer, rate_card)} combination — idempotency enforced by
 * the partial UNIQUE index {@code ux_commission_txn_source} so a replayed
 * {@code medfund.contributions.paid} event never writes a duplicate.
 *
 * <p>{@code nativeAmount} + {@code nativeCurrency} carry the raw
 * commission in the contribution's own currency; conversion to the
 * producer's home currency happens at payout-run creation per
 * {@code .claude/multi-currency.md:164}. {@code contributionAmount} and
 * {@code appliedRatePct} are snapshotted so downstream statements stay
 * reproducible even if the rate card is later reworded.
 *
 * <p>Reversals: a compensating row with {@code reversalOfTxnId} pointing to
 * the original and a negated {@code nativeAmount}; the original's status
 * flips to {@code REVERSED} or {@code CLAWED_BACK} and links back via
 * {@code reversedByTxnId}. The compensating row is excluded from the
 * source-uniqueness index (which is scoped to
 * {@code WHERE reversal_of_txn_id IS NULL}).
 */
@Getter
@Setter
@Table("commission_transaction")
public class CommissionTransaction {

    @Id
    private UUID id;

    @Column("reference")
    private String reference;

    @Column("producer_id")
    private UUID producerId;

    @Column("contribution_id")
    private UUID contributionId;

    @Column("member_id")
    private UUID memberId;

    @Column("insurance_line")
    private String insuranceLine;

    @Column("rate_card_id")
    private UUID rateCardId;

    @Column("native_amount")
    private BigDecimal nativeAmount;

    @Column("native_currency")
    private String nativeCurrency;

    @Column("contribution_amount")
    private BigDecimal contributionAmount;

    @Column("applied_rate_pct")
    private BigDecimal appliedRatePct;

    @Column("status")
    private String status = "ACCRUED";

    @Column("paid_run_id")
    private UUID paidRunId;

    @Column("paid_at")
    private OffsetDateTime paidAt;

    @Column("reversed_by_txn_id")
    private UUID reversedByTxnId;

    @Column("reversal_of_txn_id")
    private UUID reversalOfTxnId;

    @Column("occurred_at")
    private OffsetDateTime occurredAt;

    @Column("created_at")
    private OffsetDateTime createdAt;

    @Column("updated_at")
    private OffsetDateTime updatedAt;

    @Column("actor_id")
    private UUID actorId;

    @Column("actor_email")
    private String actorEmail;
}
