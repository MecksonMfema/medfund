package com.medfund.finance.dto;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Finance-local mirror of the contributions-service {@code BillingAggregateRow}.
 * Service-local DTOs are not importable across Gradle modules, so each
 * module keeps its own copy of the wire shape (same fields, same names).
 */
public record BillingAggregateRow(
        UUID schemeId,
        String schemeName,
        String currencyCode,
        BigDecimal totalBilled
) {}
