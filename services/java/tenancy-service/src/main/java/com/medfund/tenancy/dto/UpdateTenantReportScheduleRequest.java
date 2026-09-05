package com.medfund.tenancy.dto;

import com.medfund.shared.report.ReportCadence;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;

/**
 * PATCH-shaped body — every field nullable so the caller can update a
 * single value without echoing the rest. Cadence-shape validation
 * (WEEKLY needs dayOfWeek, MONTHLY needs dayOfMonth) is applied service-side
 * against the merged view of the existing row + the request.
 */
public record UpdateTenantReportScheduleRequest(
        Boolean enabled,
        ReportCadence cadence,
        @Min(0) @Max(23) Integer hourOfDay,
        @Min(1) @Max(7) Integer dayOfWeek,
        @Min(1) @Max(28) Integer dayOfMonth,
        @Pattern(regexp = "^[A-Z]{3}$") String reportingCurrency
) {}
