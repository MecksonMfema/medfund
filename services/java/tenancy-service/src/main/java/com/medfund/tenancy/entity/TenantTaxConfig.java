package com.medfund.tenancy.entity;

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
 * R2DBC row for {@code public.tenant_tax_config} — per-tenant statutory
 * tax rate (VAT / Withholding) per transaction category × currency.
 * Consumed by the VAT Return shaper (Phase 20) + TaxWithheldReturn
 * shaper (Phase 21). Rows are effective-dated so a tenant that receives
 * a mid-year rate change from the revenue authority can supersede
 * cleanly.
 */
@Getter
@Setter
@NoArgsConstructor
@Table(schema = "public", value = "tenant_tax_config")
public class TenantTaxConfig {

    @Id
    private UUID id;

    @Column("tenant_id")
    private UUID tenantId;

    @Column("country_code")
    private String countryCode;

    @Column("tax_type")
    private String taxType;

    @Column("transaction_category")
    private String transactionCategory;

    @Column("currency")
    private String currency;

    @Column("rate")
    private BigDecimal rate;

    @Column("is_registered")
    private boolean registered;

    @Column("registration_number")
    private String registrationNumber;

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

    @Column("updated_by")
    private UUID updatedBy;

    @Column("updated_by_email")
    private String updatedByEmail;
}
