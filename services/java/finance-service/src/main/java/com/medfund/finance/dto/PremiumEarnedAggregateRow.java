package com.medfund.finance.dto;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Finance-side parallel of {@code contributions.premium.dto.PremiumEarnedAggregateRow}
 * — one row of the {@code /aggregate/premium-earned} feed consumed by the
 * Phase 18 KPI composer's LOSS_RATIO denominator and CLAIMS_FREQUENCY
 * policy-months denominator (K3/K1). Mirrors the wire shape produced by
 * contributions-service; native-currency, never converted.
 */
public record PremiumEarnedAggregateRow(
        UUID schemeId,
        String schemeName,
        String insuranceLine,
        String currencyCode,
        BigDecimal earnedPremium,
        long rowCount) {}
