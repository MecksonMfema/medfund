package com.medfund.tenancy.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Adds one row to {@code tenant_ifrs17_notification_config}. {@code eventType}
 * is one of {@code ONEROUS_TRANSITION | CSM_NEGATIVE | LOCKED_IN_CURVE_FALLBACK
 * | IBNR_SUB_JOB_STALE | OPENING_BALANCE_AUTO_DERIVED | ALL}. {@code
 * deliveryMethod} is {@code EMAIL | WEBHOOK | BOTH}. {@code recipient} is the
 * email address (EMAIL / BOTH) or webhook URL (WEBHOOK / BOTH). Uniqueness is
 * (tenant_id, event_type, recipient); conflicts surface as HTTP 409.
 */
public record AddTenantIfrs17NotificationConfigRequest(
        @NotBlank @Size(max = 50) String eventType,
        @NotBlank @Size(max = 20) String deliveryMethod,
        @NotBlank @Size(max = 500) String recipient,
        @Min(0) @Max(1440) Integer throttleMinutes,
        Boolean isActive
) {}
