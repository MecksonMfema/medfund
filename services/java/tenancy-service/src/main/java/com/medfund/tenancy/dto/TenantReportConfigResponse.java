package com.medfund.tenancy.dto;

import com.medfund.shared.report.ReportFamily;
import com.medfund.shared.report.ReportKey;
import com.medfund.tenancy.entity.TenantReportConfig;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One row per report catalogue key. The tenant-admin settings screen renders
 * every known {@link ReportKey} — rows with no persisted config are returned
 * synthesised with {@code enabled=true, id=null} so the UI can toggle without
 * distinguishing "no row yet" from "explicitly enabled".
 */
public record TenantReportConfigResponse(
        UUID id,
        UUID tenantId,
        String reportKey,
        String label,
        String family,
        String familyLabel,
        boolean enabled,
        boolean cadenced,
        OffsetDateTime updatedAt,
        UUID updatedBy) {

    public static TenantReportConfigResponse from(TenantReportConfig row) {
        ReportKey k = ReportKey.parse(row.getReportKey()).orElse(null);
        return new TenantReportConfigResponse(
                row.getId(),
                row.getTenantId(),
                row.getReportKey(),
                k != null ? k.getLabel() : row.getReportKey(),
                k != null ? k.getFamily().name() : null,
                k != null ? k.getFamily().getLabel() : null,
                Boolean.TRUE.equals(row.getEnabled()),
                k != null && k.isCadenced(),
                row.getUpdatedAt(),
                row.getUpdatedBy());
    }

    /** Synthesised row for a catalogue key the tenant hasn't touched yet. */
    public static TenantReportConfigResponse defaultFor(UUID tenantId, ReportKey key) {
        return new TenantReportConfigResponse(
                null, tenantId, key.name(),
                key.getLabel(),
                key.getFamily().name(),
                key.getFamily().getLabel(),
                true,               // default enabled
                key.isCadenced(),
                null, null);
    }
}
