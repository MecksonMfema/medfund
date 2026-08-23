package com.medfund.finance.producer.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;

public record CreateRateCardRequest(
        @NotBlank @Size(max = 120) String name,
        @NotBlank
        @Pattern(regexp = "^(HEALTH|LIFE|FUNERAL|GROUP|TRAVEL|DISABILITY|VEHICLE|PROPERTY)$",
                 message = "insuranceLine must be a known InsuranceLine")
        String insuranceLine,
        @Size(max = 40) String producerTier,
        @NotNull
        @DecimalMin(value = "0", inclusive = true)
        @DecimalMax(value = "100", inclusive = true)
        BigDecimal baseRatePct,
        @Min(0) @Max(3650) Integer clawbackWindowDays,
        @NotNull LocalDate effectiveFrom,
        LocalDate effectiveTo
) {}
