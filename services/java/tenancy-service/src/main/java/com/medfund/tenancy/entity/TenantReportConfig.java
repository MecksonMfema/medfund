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
 * Row in {@code public.tenant_report_config} (V130). One per
 * (tenant, report_key); absence of a row = enabled (default TRUE).
 */
@Getter
@Setter
@NoArgsConstructor
@Table(schema = "public", value = "tenant_report_config")
public class TenantReportConfig {

    @Id
    private UUID id;

    @Column("tenant_id")
    private UUID tenantId;

    @Column("report_key")
    private String reportKey;

    private Boolean enabled;

    @Column("updated_at")
    private OffsetDateTime updatedAt;

    @Column("updated_by")
    private UUID updatedBy;
}
