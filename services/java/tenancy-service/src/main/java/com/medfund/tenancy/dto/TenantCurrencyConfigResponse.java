package com.medfund.tenancy.dto;

import com.medfund.tenancy.entity.TenantCurrencyConfig;

import java.util.UUID;

public record TenantCurrencyConfigResponse(
        UUID id,
        UUID tenantId,
        String currencyCode,
        Boolean isDefault,
        Boolean isActive,
        Boolean isBillingCurrency,
        Boolean isClaimsCurrency,
        Boolean isPaymentCurrency,
        String exchangeRateSource
) {
    public static TenantCurrencyConfigResponse from(TenantCurrencyConfig c) {
        return new TenantCurrencyConfigResponse(
                c.getId(), c.getTenantId(), c.getCurrencyCode(),
                c.getIsDefault(), c.getIsActive(),
                c.getIsBillingCurrency(), c.getIsClaimsCurrency(),
                c.getIsPaymentCurrency(), c.getExchangeRateSource());
    }
}
