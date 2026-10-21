package com.medfund.tenancy.dto;

import jakarta.validation.constraints.Size;

/**
 * Mutates a {@code tenant_market_data_config} row. Only {@code
 * autoFetchEnabled} and {@code source} are mutable — swap RBZ_AUTO
 * ↔ SARB_AUTO or pause/resume auto-fetch. The (tenant, currency) tuple
 * is immutable; a currency swap is add + delete, not an in-place edit.
 */
public record UpdateTenantMarketDataConfigRequest(
        @Size(max = 20) String source,
        Boolean autoFetchEnabled
) {}
