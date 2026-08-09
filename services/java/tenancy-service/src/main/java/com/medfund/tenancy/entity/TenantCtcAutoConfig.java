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
 * One row per tenant; absence of a row means auto-CTC is disabled for that
 * tenant (matches the {@code enabled=FALSE} column default in V129).
 */
@Getter
@Setter
@NoArgsConstructor
@Table(schema = "public", value = "tenant_ctc_auto_config")
public class TenantCtcAutoConfig {

    @Id
    @Column("tenant_id")
    private UUID tenantId;

    private Boolean enabled;

    @Column("min_member_balance_threshold")
    private BigDecimal minMemberBalanceThreshold;

    @Column("max_per_ctc_amount")
    private BigDecimal maxPerCtcAmount;

    @Column("threshold_currency")
    private String thresholdCurrency;

    @Column("updated_at")
    private OffsetDateTime updatedAt;

    @Column("updated_by")
    private UUID updatedBy;
}
