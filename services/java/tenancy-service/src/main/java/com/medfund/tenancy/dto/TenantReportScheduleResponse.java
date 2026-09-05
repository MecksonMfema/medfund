package com.medfund.tenancy.dto;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medfund.shared.report.ReportKey;
import com.medfund.tenancy.entity.TenantReportSchedule;
import com.medfund.tenancy.entity.TenantReportScheduleRecipient;
import com.medfund.tenancy.util.JsonString;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
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
        Map<String, Object> params,
        OffsetDateTime lastFiredAt,
        String lastStatus,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        List<TenantReportScheduleRecipientResponse> recipients) {

    // Shared throw-away deserialiser — the response DTO is a value type,
    // and Jackson's ObjectMapper is thread-safe so a static instance is fine
    // for this narrow read-only path.
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

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
                deserialiseParams(row.getParams()),
                row.getLastFiredAt(),
                row.getLastStatus(),
                row.getCreatedAt(),
                row.getUpdatedAt(),
                recipientDtos);
    }

    private static Map<String, Object> deserialiseParams(JsonString raw) {
        if (raw == null || raw.value() == null || raw.value().isBlank()) return Map.of();
        try {
            return MAPPER.readValue(raw.value(), MAP_TYPE);
        } catch (Exception e) {
            return Map.of();
        }
    }
}
