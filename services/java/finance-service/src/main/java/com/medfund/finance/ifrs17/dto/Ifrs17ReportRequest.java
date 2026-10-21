package com.medfund.finance.ifrs17.dto;

import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Request body for {@link com.medfund.finance.ifrs17.controller.Ifrs17ReportController}'s
 * two POST endpoints (LRC/LIC reconciliation + insurance revenue + service result).
 *
 * <p>The same shape covers both keys — the report_key is derived from the endpoint
 * path so the caller only sends the reporting scope (period + optional portfolio
 * filter + optional currency override).
 *
 * <p>Defaults:
 * <ul>
 *   <li>{@code portfolioIds} null or empty → shape all active portfolios for the tenant.</li>
 *   <li>{@code reportingCurrency} null → resolve tenant's default via {@code ReportingCurrencyResolver}.</li>
 * </ul>
 */
public record Ifrs17ReportRequest(
        @NotNull LocalDate periodStart,
        @NotNull LocalDate periodEnd,
        List<UUID> portfolioIds,
        String reportingCurrency) {
}
