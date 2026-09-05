package com.medfund.tenancy.dto;

import jakarta.validation.constraints.Size;

/**
 * PATCH-shaped update body. Email is immutable — to change a recipient's
 * address, delete the row and re-add.
 */
public record UpdateTenantReportScheduleRecipientRequest(
        @Size(max = 160) String displayName,
        Boolean isActive
) {}
