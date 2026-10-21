package com.medfund.finance.actuarial.dto;

import com.medfund.finance.actuarial.service.TriangleShapingService.Grain;
import com.medfund.finance.actuarial.service.TriangleShapingService.Shape;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

/**
 * Request body for {@code POST /api/v1/reports/actuarial/ibnr} and its
 * loss-triangle sibling — both share the same shape because the compute
 * pipeline distinguishes on {@code ReportKey}, not payload shape.
 *
 * <p>{@code insuranceLine} null = all lines aggregated.
 * {@code reportingCurrency} null = tenant default resolved server-side per A6.
 * {@code ldfMethod} null = compute defaults to {@code volume} (Phase 15/16
 * flip the default to whatever the tenant's ACTUARIAL rule selects).
 */
public record IbnrJobRequest(
        @NotNull LocalDate periodStart,
        @NotNull LocalDate periodEnd,
        String insuranceLine,
        @NotNull Shape shape,
        @NotNull Grain grain,
        String reportingCurrency,
        String ldfMethod) {}
