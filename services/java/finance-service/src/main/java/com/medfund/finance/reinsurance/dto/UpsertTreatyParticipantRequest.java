package com.medfund.finance.reinsurance.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.math.BigDecimal;
import java.util.UUID;

public record UpsertTreatyParticipantRequest(
        @NotNull UUID reinsurerId,
        @NotNull @DecimalMin(value = "0.0001") @DecimalMax(value = "100.0000") BigDecimal sharePct,
        @NotNull @Pattern(regexp = "LEADER|FOLLOWING") String shareRole
) {}
