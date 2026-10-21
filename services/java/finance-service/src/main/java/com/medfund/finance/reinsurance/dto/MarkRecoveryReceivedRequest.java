package com.medfund.finance.reinsurance.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record MarkRecoveryReceivedRequest(
        @NotNull @DecimalMin("0.00") BigDecimal receivedAmount,
        OffsetDateTime receivedAt) {}
