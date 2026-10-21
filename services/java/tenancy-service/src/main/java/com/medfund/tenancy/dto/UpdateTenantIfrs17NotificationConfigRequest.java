package com.medfund.tenancy.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Updates the mutable fields of a {@code tenant_ifrs17_notification_config}
 * row. The tuple key (tenant_id, event_type, recipient) is immutable —
 * changing any of these three is a new row, not an in-place edit. The
 * delivery channel, throttle and active flag are the mutable knobs.
 */
public record UpdateTenantIfrs17NotificationConfigRequest(
        @NotBlank @Size(max = 20) String deliveryMethod,
        @Min(0) @Max(1440) Integer throttleMinutes,
        Boolean isActive
) {}
