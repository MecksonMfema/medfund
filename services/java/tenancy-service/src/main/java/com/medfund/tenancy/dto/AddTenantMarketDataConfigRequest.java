package com.medfund.tenancy.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Adds one row to {@code tenant_market_data_config}. {@code currency} is
 * an ISO 4217 code (3 letters). {@code source} is the jurisdictional
 * adapter — one of {@code RBZ_AUTO | SARB_AUTO}. Auto-fetch defaults to
 * enabled if omitted. Uniqueness is (tenant_id, currency); conflicts
 * surface as HTTP 409.
 */
public record AddTenantMarketDataConfigRequest(
        @NotBlank @Size(min = 3, max = 3) String currency,
        @NotBlank @Size(max = 20) String source,
        Boolean autoFetchEnabled
) {}
