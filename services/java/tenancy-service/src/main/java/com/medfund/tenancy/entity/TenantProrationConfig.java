package com.medfund.tenancy.entity;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One row per tenant; absence of a row means fall through to the platform
 * default (ProrationStrategy.NONE). See V127__tenant_proration_config.sql.
 */
@Getter
@Setter
@NoArgsConstructor
@Table(schema = "public", value = "tenant_proration_config")
public class TenantProrationConfig {

    @Id
    @Column("tenant_id")
    private UUID tenantId;

    @Column("default_strategy")
    private String defaultStrategy;

    @Column("upgrade_strategy")
    private String upgradeStrategy;

    @Column("downgrade_strategy")
    private String downgradeStrategy;

    @Column("currency_strategy")
    private String currencyStrategy;

    @Column("increment_wait_days")
    private Integer incrementWaitDays;

    @Column("updated_at")
    private OffsetDateTime updatedAt;

    @Column("updated_by")
    private UUID updatedBy;
}
