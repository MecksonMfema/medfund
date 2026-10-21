package com.medfund.user.entity;

import lombok.Getter;
import lombok.Setter;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * (provider, tenant) membership with per-tenant contract terms (V185).
 *
 * <p>Composite key on {@code providerId + tenantId}; no surrogate ID. The
 * repository hand-rolls SQL via {@code DatabaseClient} because R2DBC's
 * {@code ReactiveCrudRepository} does not support composite keys out of the
 * box — see {@code TreatyParticipantRepository} in finance-service for the
 * canonical pattern.
 *
 * <p>Providers are platform-scoped: one row in {@code public.providers},
 * related to N tenants through this table. Every tenant-scoped read of a
 * provider must join through here (CLAUDE.md Critical Rule 2, "platform
 * tables with tenant membership").
 *
 * <p>The contract fields ({@code contractEffectiveFrom/To},
 * {@code creditLimit}, {@code creditLimitCurrency}, {@code tariffAgreementId})
 * are carried by the schema but have no consumer in v1; the admin UI only
 * exposes {@code status}, {@code networkTier} and {@code inNetwork}.
 */
@Getter
@Setter
@Table(schema = "public", value = "provider_tenants")
public class ProviderTenant {

    @Column("provider_id")
    private UUID providerId;

    @Column("tenant_id")
    private UUID tenantId;

    /** Vocab: active, pending, terminated, suspended (CHECK in V185). */
    private String status;

    /** Vocab: STANDARD, TIER_1, TIER_2, TIER_3 (CHECK in V185). */
    @Column("network_tier")
    private String networkTier;

    @Column("in_network")
    private Boolean inNetwork;

    @Column("contract_effective_from")
    private LocalDate contractEffectiveFrom;

    @Column("contract_effective_to")
    private LocalDate contractEffectiveTo;

    @Column("credit_limit")
    private BigDecimal creditLimit;

    @Column("credit_limit_currency")
    private String creditLimitCurrency;

    @Column("tariff_agreement_id")
    private UUID tariffAgreementId;

    @Column("created_at")
    private OffsetDateTime createdAt;

    @Column("updated_at")
    private OffsetDateTime updatedAt;

    @Column("created_by")
    private UUID createdBy;

    @Column("updated_by")
    private UUID updatedBy;
}
