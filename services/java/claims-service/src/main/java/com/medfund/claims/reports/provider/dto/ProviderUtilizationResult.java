package com.medfund.claims.reports.provider.dto;

import java.util.List;
import java.util.Map;

/**
 * Phase 13 §C Phase 9 wire shape for PROVIDER_NETWORK_UTILIZATION per L12.
 * Two-level: {@code summary} keyed by network tier, {@code detail} the
 * full per-provider row set.
 */
public record ProviderUtilizationResult(
    Map<String, NetworkTierTotals> summary,
    List<ProviderUtilizationRow> detail
) {}
