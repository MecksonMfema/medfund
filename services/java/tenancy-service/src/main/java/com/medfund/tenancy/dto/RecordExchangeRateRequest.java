package com.medfund.tenancy.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record RecordExchangeRateRequest(
        @NotBlank @Size(min = 3, max = 3) @Pattern(regexp = "^[A-Z]{3}$")
        String baseCurrency,

        @NotBlank @Size(min = 3, max = 3) @Pattern(regexp = "^[A-Z]{3}$")
        String quoteCurrency,

        @NotNull @DecimalMin(value = "0.0000000001", message = "rate must be greater than zero")
        BigDecimal rate,

        @NotNull
        LocalDate rateDate,

        String source,

        UUID tenantId
) {}
