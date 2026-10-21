package com.medfund.finance.reinsurance.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record UpsertTreatyLayerRequest(
        @NotNull @Positive Integer layerOrder,
        @NotNull @DecimalMin("0.00") BigDecimal retention,
        @NotNull @DecimalMin("0.01") BigDecimal layerLimit,
        @NotNull @Size(min = 3, max = 3) String layerCurrency,
        @NotNull @DecimalMin("0.00") BigDecimal rate,
        @PositiveOrZero Integer reinstatementCount
) {}
