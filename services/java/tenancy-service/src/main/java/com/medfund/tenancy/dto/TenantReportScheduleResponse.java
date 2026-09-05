package com.medfund.tenancy.dto;

import com.medfund.shared.report.ReportKey;
import com.medfund.tenancy.entity.TenantReportSchedule;
import com.medfund.tenancy.entity.TenantReportScheduleRecipient;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record TenantReportScheduleResponse(
        UUID id,
        UUID tenantId,
        String reportKey,
        String reportLabel,
        boolean enabled,
        String cadence,
        int hourOfDay,
        Integer dayOfWeek,
        Integer dayOfMonth,
        String reportingCurrency,
        OffsetDateTime lastFiredAt,
        String lastStatus,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        List<TenantReportScheduleRecipientResponse> recipients) {

    public static TenantReportScheduleResponse from(TenantReportSchedule row,
                                                    List<TenantReportScheduleRecipient> recipients) {
        String label = ReportKey.parse(row.getReportKey())
                .map(ReportKey::getLabel)
                .orElse(row.getReportKey());
        List<TenantReportScheduleRecipientResponse> recipientDtos = recipients == null
                ? List.of()
                : recipients.stream().map(TenantReportScheduleRecipientResponse::from).toList();
        return new TenantReportScheduleResponse(
                row.getId(),
                row.getTenantId(),
                row.getReportKey(),
                label,
                Boolean.TRUE.equals(row.getEnabled()),
                row.getCadence(),
                row.getHourOfDay() != null ? row.getHourOfDay() : 8,
                row.getDayOfWeek(),
                row.getDayOfMonth(),
                row.getReportingCurrency(),
                row.getLastFiredAt(),
                row.getLastStatus(),
                row.getCreatedAt(),
                row.getUpdatedAt(),
                recipientDtos);
    }
}
