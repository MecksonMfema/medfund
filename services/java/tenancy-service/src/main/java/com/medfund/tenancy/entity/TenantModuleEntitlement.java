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
 * Row in {@code public.tenant_module_entitlements} (V187). One per
 * (tenant, module); absence of a row = NOT entitled (default FALSE — a
 * module is off until sold). Powers the super-admin module-entitlement
 * management surface and the gateway/aspect entitlement gate.
 */
@Getter
@Setter
@NoArgsConstructor
@Table(schema = "public", value = "tenant_module_entitlements")
public class TenantModuleEntitlement {

    @Id
    private UUID id;

    @Column("tenant_id")
    private UUID tenantId;

    @Column("module_key")
    private String moduleKey;

    private Boolean enabled;

    @Column("updated_at")
    private OffsetDateTime updatedAt;

    @Column("updated_by")
    private UUID updatedBy;

    private Long version;
}
