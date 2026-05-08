package com.medfund.tenancy.dto;

import com.medfund.tenancy.entity.Currency;

public record CurrencyResponse(
        String code,
        String name,
        String symbol,
        Short decimalPlaces,
        Boolean isActive
) {
    public static CurrencyResponse from(Currency c) {
        return new CurrencyResponse(c.getCode(), c.getName(), c.getSymbol(),
                c.getDecimalPlaces(), c.getIsActive());
    }
}
