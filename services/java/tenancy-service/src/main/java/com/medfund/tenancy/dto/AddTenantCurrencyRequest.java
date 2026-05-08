package com.medfund.tenancy.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record AddTenantCurrencyRequest(
        @NotBlank @Size(min = 3, max = 3) @Pattern(regexp = "^[A-Z]{3}$", message = "ISO 4217 code must be 3 uppercase letters")
        String currencyCode,

        Boolean isDefault,
        Boolean isBillingCurrency,
        Boolean isClaimsCurrency,
        Boolean isPaymentCurrency,
        String exchangeRateSource
) {}
