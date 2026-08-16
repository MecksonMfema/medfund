package com.medfund.finance.dto;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Finance-local mirror of the claims-service {@code ClaimsAggregateRow}.
 * Service-local DTOs are not importable across Gradle modules, so each
 * module keeps its own copy of the wire shape (same fields, same names).
 */
public record ClaimsAggregateRow(
        String dimension,
        UUID dimensionId,
        String dimensionName,
        String currencyCode,
        BigDecimal totalClaimed,
        BigDecimal totalApproved,
        BigDecimal totalPaid
) {}
