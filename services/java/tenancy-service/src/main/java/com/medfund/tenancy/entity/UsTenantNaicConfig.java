package com.medfund.tenancy.entity;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * R2DBC row for {@code public.us_tenant_naic_config} — per-tenant US NAIC
 * company identity consumed by the NAIC Schedule P / F shapers (Phase 12 /
 * 13). Rows are effective-dated so a tenant that changes state of
 * domicile or NAIC code preserves the historical identity.
 */
@Getter
@Setter
@NoArgsConstructor
@Table(schema = "public", value = "us_tenant_naic_config")
public class UsTenantNaicConfig {

    @Id
    private UUID id;

    @Column("tenant_id")
    private UUID tenantId;

    @Column("state_domicile")
    private String stateDomicile;

    @Column("naic_company_code")
    private String naicCompanyCode;

    @Column("naic_group_code")
    private String naicGroupCode;

    @Column("fein")
    private String fein;

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
