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

    @CreatedDate
    @Column("created_at")
    private Instant createdAt;
}
