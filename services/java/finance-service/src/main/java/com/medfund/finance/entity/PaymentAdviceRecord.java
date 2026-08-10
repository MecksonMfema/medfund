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
 * Persistent record of a generated payment advice. Distinct from the
 * in-memory {@code com.medfund.finance.dto.PaymentAdvice} which carries
 * line items for a single rendering — this row exists per advice so we
 * can list / re-export later and track delivery status.
 */
@Getter
@Setter
@Table("payment_advices")
public class PaymentAdviceRecord {

    @Id
    private UUID id;

    @Column("payment_run_id")
    private UUID paymentRunId;

    @Column("provider_id")
    private UUID providerId;

    @Column("member_id")
    private UUID memberId;

    @Column("payee_type")
    private String payeeType = "PROVIDER";

    @Column("currency_code")
    private String currencyCode;

    @Column("total_amount")
    private BigDecimal totalAmount;

    @Column("claim_count")
    private Integer claimCount;

    @Column("document_url")
    private String documentUrl;

    @Column("excel_url")
    private String excelUrl;

    private String status = "generated";

    @Column("issued_at")
    private Instant issuedAt;

    @Column("period_start_at")
    private Instant periodStartAt;

    @Column("period_end_at")
    private Instant periodEndAt;

    @Column("carried_in_amount")
    private BigDecimal carriedInAmount = BigDecimal.ZERO;

    @Column("claims_paid_amount")
    private BigDecimal claimsPaidAmount = BigDecimal.ZERO;

    @Column("ctc_applied_amount")
    private BigDecimal ctcAppliedAmount = BigDecimal.ZERO;

    @Column("advance_applied_amount")
    private BigDecimal advanceAppliedAmount = BigDecimal.ZERO;

    @Column("tax_withheld_amount")
    private BigDecimal taxWithheldAmount = BigDecimal.ZERO;

    @Column("shortfall_amount")
    private BigDecimal shortfallAmount = BigDecimal.ZERO;

    @Column("net_due_amount")
    private BigDecimal netDueAmount = BigDecimal.ZERO;

    @Column("advice_number")
    private String adviceNumber;

    @CreatedDate
    @Column("created_at")
    private Instant createdAt;
}
