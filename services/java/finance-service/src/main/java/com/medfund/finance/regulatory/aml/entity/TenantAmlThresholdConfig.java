package com.medfund.finance.regulatory.aml.entity;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Row from {@code public.tenant_aml_threshold_config} (V175). Effective-
 * dated per (tenant, transactionType, currency). Rule-2: this is a
 * platform-scope table so the query prefix {@code public.} IS required
 * (unlike tenant tables per {@code bug_public_prefix_silent_rollback}).
 */
@Getter
@Setter
@NoArgsConstructor
@Table("public.tenant_aml_threshold_config")
public class TenantAmlThresholdConfig {

    @Id
    private UUID id;

    @Column("tenant_id")
    private UUID tenantId;

    @Column("transaction_type")
    private String transactionType;

    @Column("threshold_amount")
    private BigDecimal thresholdAmount;

    @Column("currency")
    private String currency;

    @Column("effective_from")
    private LocalDate effectiveFrom;

    @Column("effective_to")
    private LocalDate effectiveTo;

    @Column("source_note")
    private String sourceNote;

    @Column("created_at")
    private OffsetDateTime createdAt;

    @Column("updated_at")
    private OffsetDateTime updatedAt;

    @Column("actor_id")
    private UUID actorId;

    @Column("actor_email")
    private String actorEmail;
}
