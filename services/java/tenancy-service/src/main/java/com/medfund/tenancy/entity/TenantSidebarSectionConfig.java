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
 * Row in {@code public.tenant_sidebar_section_config} (V179). One
 * per (tenant, section_key); absence of a row = enabled (default
 * TRUE). Powers the tenant-admin Sidebar Visibility settings tab
 * and the operational sidebar's per-item hide filter.
 */
@Getter
@Setter
@NoArgsConstructor
@Table(schema = "public", value = "tenant_sidebar_section_config")
public class TenantSidebarSectionConfig {

    @Id
    private UUID id;

    @Column("tenant_id")
    private UUID tenantId;

    @Column("section_key")
    private String sectionKey;

    private Boolean enabled;

    @Column("updated_at")
    private OffsetDateTime updatedAt;

    @Column("updated_by")
    private UUID updatedBy;
}
