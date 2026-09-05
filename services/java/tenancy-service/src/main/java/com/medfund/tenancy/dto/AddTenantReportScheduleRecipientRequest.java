package com.medfund.tenancy.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Add body for
 * {@code POST /api/v1/tenants/{tenantId}/report-schedules/{scheduleId}/recipients}.
 * Uniqueness on {@code (schedule_id, email)} surfaces as HTTP 409.
 */
public record AddTenantReportScheduleRecipientRequest(
        @NotBlank @Email @Size(max = 255) String email,
        @Size(max = 160) String displayName,
        Boolean isActive
) {}
