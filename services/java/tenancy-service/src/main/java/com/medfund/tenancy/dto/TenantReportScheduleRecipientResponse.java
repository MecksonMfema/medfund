package com.medfund.tenancy.dto;

import com.medfund.tenancy.entity.TenantReportScheduleRecipient;

import java.time.OffsetDateTime;
import java.util.UUID;

public record TenantReportScheduleRecipientResponse(
        UUID id,
        UUID scheduleId,
        String email,
        String displayName,
        boolean isActive,
        UUID unsubscribeToken,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    public static TenantReportScheduleRecipientResponse from(TenantReportScheduleRecipient row) {
        return new TenantReportScheduleRecipientResponse(
                row.getId(),
                row.getScheduleId(),
                row.getEmail(),
                row.getDisplayName(),
                Boolean.TRUE.equals(row.getIsActive()),
                row.getUnsubscribeToken(),
                row.getCreatedAt(),
                row.getUpdatedAt());
    }
}
