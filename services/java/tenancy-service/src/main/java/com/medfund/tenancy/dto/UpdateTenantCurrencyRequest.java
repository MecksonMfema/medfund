package com.medfund.tenancy.dto;

public record UpdateTenantCurrencyRequest(
        Boolean isDefault,
        Boolean isActive,
        Boolean isBillingCurrency,
        Boolean isClaimsCurrency,
        Boolean isPaymentCurrency,
        String exchangeRateSource
) {}
