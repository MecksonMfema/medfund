package com.medfund.tenancy.dto;

import com.medfund.tenancy.entity.ExchangeRate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record ExchangeRateResponse(
        UUID id,
        String baseCurrency,
        String quoteCurrency,
        BigDecimal rate,
        LocalDate rateDate,
        String source,
        UUID tenantId,
        Instant createdAt
) {
    public static ExchangeRateResponse from(ExchangeRate r) {
        return new ExchangeRateResponse(
                r.getId(), r.getBaseCurrency(), r.getQuoteCurrency(),
                r.getRate(), r.getRateDate(), r.getSource(),
                r.getTenantId(), r.getCreatedAt());
    }
}
