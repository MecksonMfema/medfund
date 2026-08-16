package com.medfund.tenancy.entity;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Row in {@code public.tenant_high_cost_claimant_config} (V132). One per
 * tenant; absence of a row means the HIGH_COST_CLAIMANT report renders
 * empty with a "threshold not configured" warning (config gap, not a data
 * gap — the report still succeeds).
 */
@Getter
@Setter
@NoArgsConstructor
@Table(schema = "public", value = "tenant_high_cost_claimant_config")
public class TenantHighCostClaimantConfig {

    @Id
    private UUID id;

    @Column("tenant_id")
    private UUID tenantId;

    @Column("threshold_amount")
    private BigDecimal thresholdAmount;

    @Column("currency_code")
    private String currencyCode;

    @Column("updated_at")
    private OffsetDateTime updatedAt;

    @Column("updated_by")
    private UUID updatedBy;
}
