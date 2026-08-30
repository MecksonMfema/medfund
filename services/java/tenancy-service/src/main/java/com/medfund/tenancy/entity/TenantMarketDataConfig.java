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
 * R2DBC row for {@code public.tenant_market_data_config} — per-tenant
 * opt-in for the Phase 24 market-data-service yield-curve auto-fetch
 * (Phase 15 §24 / I12 + I16). One row per (tenant, currency); {@code
 * source} names the jurisdictional adapter (RBZ_AUTO for Zimbabwe /
 * SARB_AUTO for South Africa). The Go service reads the enabled rows
 * daily and publishes fetched curves back to the tenancy-service.
 */
@Getter
@Setter
@NoArgsConstructor
@Table(schema = "public", value = "tenant_market_data_config")
public class TenantMarketDataConfig {

    @Id
    private UUID id;

    @Column("tenant_id")
    private UUID tenantId;

    @Column("currency")
    private String currency;

    @Column("auto_fetch_enabled")
    private Boolean autoFetchEnabled;

    @Column("source")
    private String source;

    @Column("created_at")
    private OffsetDateTime createdAt;

    @Column("updated_at")
    private OffsetDateTime updatedAt;

    @Column("updated_by")
    private UUID updatedBy;

    @Column("updated_by_email")
    private String updatedByEmail;
}
