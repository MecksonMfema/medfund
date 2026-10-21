package com.medfund.finance.producer.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.UUID;

public record CreateProducerRequest(
        @NotBlank @Size(max = 40) String producerCode,
        @NotBlank @Size(max = 200) String name,
        @Email @Size(max = 255) String contactEmail,
        @Size(max = 40) String contactPhone,
        @Size(max = 20) String jurisdictionCode,
        @NotBlank @Pattern(regexp = "^[A-Z]{3}$", message = "homeCurrency must be a 3-letter ISO-4217 code")
        String homeCurrency,
        UUID parentProducerId,
        @DecimalMin(value = "0", inclusive = true)
        @DecimalMax(value = "100", inclusive = true)
        BigDecimal whtPctOverride,
        String bankingDetailsJson
) {}
