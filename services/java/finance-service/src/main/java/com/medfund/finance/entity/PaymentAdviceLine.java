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
 * One typed row inside a payment advice ledger. Debit amounts are money
 * owed TO the payee (carried-forward balance, claims paid); credit amounts
 * are deductions FROM that balance (CTC offsets, advance draw-downs, tax
 * withholdings, shortfalls). The V071 CHECK ensures exactly one side is
 * non-zero per row.
 */
@Getter
@Setter
@Table("payment_advice_lines")
public class PaymentAdviceLine {

    @Id
    private UUID id;

    @Column("payment_advice_id")
    private UUID paymentAdviceId;

    @Column("line_type")
    private String lineType;

    @Column("reference_type")
    private String referenceType;

    @Column("reference_id")
    private UUID referenceId;

    private String description;

    @Column("debit_amount")
    private BigDecimal debitAmount = BigDecimal.ZERO;

    @Column("credit_amount")
    private BigDecimal creditAmount = BigDecimal.ZERO;

    @Column("currency_code")
    private String currencyCode;

    @Column("posted_at")
    private Instant postedAt;

    private Integer sequence;

    @CreatedDate
    @Column("created_at")
    private Instant createdAt;
}
