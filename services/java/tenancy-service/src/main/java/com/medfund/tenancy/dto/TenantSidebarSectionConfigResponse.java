package com.medfund.tenancy.dto;

import com.medfund.shared.sidebar.SidebarSectionKey;
import com.medfund.tenancy.entity.TenantSidebarSectionConfig;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One row per catalogued sidebar section. The tenant-admin
 * settings screen renders every known {@link SidebarSectionKey} —
 * rows with no persisted config are returned synthesised with
 * {@code enabled=true, id=null} so the UI can toggle without
 * distinguishing "no row yet" from "explicitly enabled".
 */
public record TenantSidebarSectionConfigResponse(
        UUID id,
        UUID tenantId,
        String sectionKey,
        String label,
        String group,
        String groupLabel,
        boolean enabled,
        OffsetDateTime updatedAt,
        UUID updatedBy) {

    public static TenantSidebarSectionConfigResponse from(TenantSidebarSectionConfig row) {
        SidebarSectionKey k = SidebarSectionKey.parse(row.getSectionKey()).orElse(null);
        return new TenantSidebarSectionConfigResponse(
                row.getId(),
                row.getTenantId(),
                row.getSectionKey(),
                k != null ? k.getLabel() : row.getSectionKey(),
                k != null ? k.getGroup().name() : null,
                k != null ? k.getGroup().getLabel() : null,
                Boolean.TRUE.equals(row.getEnabled()),
                row.getUpdatedAt(),
                row.getUpdatedBy());
    }

    /** Synthesised row for a catalogue key the tenant hasn't touched yet. */
    public static TenantSidebarSectionConfigResponse defaultFor(UUID tenantId, SidebarSectionKey key) {
        return new TenantSidebarSectionConfigResponse(
                null, tenantId, key.name(),
                key.getLabel(),
                key.getGroup().name(),
                key.getGroup().getLabel(),
                true,                // default enabled
                null, null);
    }
}
