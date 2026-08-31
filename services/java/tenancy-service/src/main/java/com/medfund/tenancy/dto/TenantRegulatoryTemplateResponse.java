package com.medfund.tenancy.dto;

import com.medfund.tenancy.entity.TenantRegulatoryTemplate;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Metadata-only response — XLSX bytes never appear on the list view. Callers
 * fetch bytes via the {@code GET /{id}/download} endpoint on demand.
 */
public record TenantRegulatoryTemplateResponse(
        UUID id,
        UUID tenantId,
        String regulator,
        String reportKey,
        String versionLabel,
        LocalDate effectiveFrom,
        LocalDate effectiveTo,
        long fileSizeBytes,
        String contentHash,
        OffsetDateTime uploadedAt,
        UUID actorId,
        String actorEmail,
        String notes
) {
    public static TenantRegulatoryTemplateResponse from(TenantRegulatoryTemplate row) {
        return new TenantRegulatoryTemplateResponse(
                row.getId(),
                row.getTenantId(),
                row.getRegulator(),
                row.getReportKey(),
                row.getVersionLabel(),
                row.getEffectiveFrom(),
                row.getEffectiveTo(),
                row.getFileSizeBytes() != null ? row.getFileSizeBytes() : 0L,
                row.getContentHash(),
                row.getUploadedAt(),
                row.getActorId(),
                row.getActorEmail(),
                row.getNotes()
        );
    }
}
