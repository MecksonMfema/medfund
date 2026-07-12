package com.medfund.claims.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.UUID;

public record CreateTariffCodeRequest(
        @NotNull UUID scheduleId,
        @NotBlank String code,
        @NotBlank String description,
        /** V063 — mandatory category FK. The legacy free-text {@code category}
         *  string is kept on the entity as a denormalised label so old
         *  reports keep rendering, but the resolver reads only categoryId. */
        @NotNull UUID categoryId,
        @NotNull BigDecimal unitPrice,
        String currencyCode,
        Boolean requiresPreAuth
) {
    public CreateTariffCodeRequest {
        if (currencyCode == null || currencyCode.isBlank()) {
            currencyCode = "USD";
        }
    }
}
