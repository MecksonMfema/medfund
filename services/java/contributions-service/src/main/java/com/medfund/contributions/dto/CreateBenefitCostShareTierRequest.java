package com.medfund.contributions.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record CreateBenefitCostShareTierRequest(
        @NotBlank @Size(max = 100) String tierName,
        @DecimalMin("0") BigDecimal copayAmount,
        @DecimalMin("0") @DecimalMax("100") BigDecimal copayPercentage,
        @DecimalMin("0") BigDecimal copayMax) {
}
