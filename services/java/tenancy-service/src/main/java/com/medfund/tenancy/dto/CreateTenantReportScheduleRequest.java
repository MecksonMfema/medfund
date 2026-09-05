package com.medfund.tenancy.dto;

import com.medfund.shared.report.ReportCadence;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * Create body for {@code POST /api/v1/tenants/{tenantId}/report-schedules}.
 * {@code reportKey} must be a whitelisted Phase 17 key per
 * {@link com.medfund.shared.report.ScheduledReportEligibility}; enforcement
 * lives in the service layer (400 on rejection).
 *
 * <p>{@code dayOfWeek} is required for {@code WEEKLY} cadence,
 * {@code dayOfMonth} for {@code MONTHLY}. QUARTERLY and ANNUAL derive their
 * day from the cadence itself (1st of the quarter / 1st of Jan).
 *
 * <p>{@code reportingCurrency} left null delegates to the tenant default at
 * fire time per multi-currency invariant #1 (F-S1).
 */
public record CreateTenantReportScheduleRequest(
        @NotBlank String reportKey,
        boolean enabled,
        @NotNull ReportCadence cadence,
        @NotNull @Min(0) @Max(23) Integer hourOfDay,
        @Min(1) @Max(7) Integer dayOfWeek,
        @Min(1) @Max(28) Integer dayOfMonth,
        @Pattern(regexp = "^[A-Z]{3}$") String reportingCurrency
) {}
