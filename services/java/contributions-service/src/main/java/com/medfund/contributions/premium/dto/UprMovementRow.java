package com.medfund.contributions.premium.dto;

import java.math.BigDecimal;

/**
 * One row per (insurance_line, currency_code) of the Phase 12 §B UPR
 * Movement report. Rows stay native-currency (parent-plan invariant #1);
 * the envelope's {@code perCurrency} + {@code fxRates} carry any
 * reporting-currency conversion the client wants to render.
 *
 * <p>{@code openingUpr} + {@code writtenPremium} − {@code earnedPremium}
 * + {@code endorsementDelta} = {@code closingUpr} for the period — the SQL
 * already reconciles the identity so a client can render the movement
 * table without recomputing.
 */
public record UprMovementRow(
        String insuranceLine,
        String currencyCode,
        BigDecimal openingUpr,
        BigDecimal writtenPremium,
        BigDecimal earnedPremium,
        BigDecimal endorsementDelta,
        BigDecimal closingUpr
) {}
