package com.medfund.finance.reinsurance.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Underwriter-supplied body for {@code POST /api/v1/reinsurance/facultative}.
 * {@code layerId} is optional and only meaningful for XoL/StopLoss treaties —
 * for proportional treaties the field is ignored server-side.
 */
public record CreateFacultativeCessionRequest(
        @NotNull UUID claimId,
        @NotNull UUID treatyId,
        UUID layerId,
        @NotNull @DecimalMin(value = "0.01", message = "cededAmount must be positive") BigDecimal cededAmount,
        @NotNull @DecimalMin(value = "0.01", message = "basisAmount must be positive") BigDecimal basisAmount,
        @Size(min = 3, max = 3) String currencyCode,
        @Size(max = 500) String reason
) {}
