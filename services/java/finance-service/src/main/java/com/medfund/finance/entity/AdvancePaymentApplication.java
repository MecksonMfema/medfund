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
 * Bridging table row: one advance payment can be applied against one
 * or more payment run items across one or more runs. Written by
 * PaymentRunService.execute() when a PROVIDER_PAYMENT rule consumes
 * advance balance against a run item.
 */
@Getter
@Setter
@Table("advance_payment_applications")
public class AdvancePaymentApplication {

    @Id
    private UUID id;

    @Column("advance_payment_id")
    private UUID advancePaymentId;

    @Column("payment_id")
    private UUID paymentId;

    @Column("payment_run_id")
    private UUID paymentRunId;

    @Column("payment_run_item_id")
    private UUID paymentRunItemId;

    @Column("amount_applied")
    private BigDecimal amountApplied;

    @Column("currency_code")
    private String currencyCode;

    @Column("applied_at")
    private Instant appliedAt;

    @Column("applied_by")
    private UUID appliedBy;
}
