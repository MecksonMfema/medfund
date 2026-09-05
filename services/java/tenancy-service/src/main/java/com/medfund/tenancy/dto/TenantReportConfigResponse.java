package com.medfund.tenancy.dto;

import com.medfund.shared.report.ReportFamily;
import com.medfund.shared.report.ReportKey;
import com.medfund.shared.report.ScheduledReportEligibility;
import com.medfund.tenancy.entity.TenantReportConfig;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One row per report catalogue key. The tenant-admin settings screen renders
 * every known {@link ReportKey} — rows with no persisted config are returned
 * synthesised with {@code enabled=true, id=null} so the UI can toggle without
 * distinguishing "no row yet" from "explicitly enabled".
 *
 * <p>{@code activeScheduleCount} — the number of enabled schedule rows this
 * tenant has for a cadenced key. Powers the Phase 9 "Manage schedule (N)"
 * link and the cascade-disable confirm modal. Always 0 for non-cadenced keys.
 *
 * <p>{@code scheduleEligible} — mirrors {@link ScheduledReportEligibility}
 * so the UI can hide the schedule affordance for cadenced-but-not-whitelisted
 * keys (regulator/IFRS/AML/FRAUD) without duplicating the whitelist.
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
        boolean scheduleEligible,
        long activeScheduleCount,
        OffsetDateTime updatedAt,
        UUID updatedBy) {

    public static TenantReportConfigResponse from(TenantReportConfig row) {
        return from(row, 0L);
    }

    public static TenantReportConfigResponse from(TenantReportConfig row, long activeScheduleCount) {
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
                k != null && ScheduledReportEligibility.isEligible(k),
                activeScheduleCount,
                row.getUpdatedAt(),
                row.getUpdatedBy());
    }

    /** Synthesised row for a catalogue key the tenant hasn't touched yet. */
    public static TenantReportConfigResponse defaultFor(UUID tenantId, ReportKey key) {
        return defaultFor(tenantId, key, 0L);
    }

    public static TenantReportConfigResponse defaultFor(UUID tenantId, ReportKey key,
                                                        long activeScheduleCount) {
        return new TenantReportConfigResponse(
                null, tenantId, key.name(),
                key.getLabel(),
                key.getFamily().name(),
                key.getFamily().getLabel(),
                true,               // default enabled
                key.isCadenced(),
                ScheduledReportEligibility.isEligible(key),
                activeScheduleCount,
                null, null);
    }
}
