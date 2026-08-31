package com.medfund.finance.regulatory.naic;

import java.math.BigDecimal;

/**
 * Raw source data for the NAIC Schedule P shaper — every monetary value
 * already converted to USD by {@link NaicSchedulePRawDataProvider}
 * implementations via
 * {@link com.medfund.finance.regulatory.service.RegulatoryFxPolicy}.
 *
 * <p>Phase 12 ships the {@link NaicSchedulePRawDataProvider} SPI with a
 * stub default implementation that returns zeroes and a WARN log until
 * Phase 12b wires the real cross-service peer calls (claims-service for
 * paid + case reserves + IBNR by accident year, contributions-service for
 * earned premium by accident year).
 *
 * <p>Per-accident-year fields are named
 * {@code {paid|case|ibnr|earnedPremium}AyMinus{N}} for the three most
 * recent accident years (0 = current, 1 = prior, 2 = two years prior).
 * The synthetic template compresses NAIC's 10-year triangle to this
 * three-year span; the real template swap-in expands the record.
 *
 * <p>Company identity fields ({@code companyName}, {@code naicCode},
 * {@code groupCode}, {@code fein}, {@code stateOfDomicile}) are pulled
 * from {@code public.us_tenant_naic_config} (Phase 14) by the concrete
 * provider — the stub leaves them null.
 */
public record NaicSchedulePRawData(
        String companyName,
        String naicCode,
        String groupCode,
        String fein,
        String stateOfDomicile,
        BigDecimal paidAyMinus2,
        BigDecimal paidAyMinus1,
        BigDecimal paidAyCurrent,
        BigDecimal caseAyMinus2,
        BigDecimal caseAyMinus1,
        BigDecimal caseAyCurrent,
        BigDecimal ibnrAyMinus2,
        BigDecimal ibnrAyMinus1,
        BigDecimal ibnrAyCurrent,
        BigDecimal earnedPremiumAyMinus2,
        BigDecimal earnedPremiumAyMinus1,
        BigDecimal earnedPremiumAyCurrent) {

    public NaicSchedulePRawData {
        // Null → ZERO so downstream arithmetic is safe on partial peer failures.
        paidAyMinus2 = orZero(paidAyMinus2);
        paidAyMinus1 = orZero(paidAyMinus1);
        paidAyCurrent = orZero(paidAyCurrent);
        caseAyMinus2 = orZero(caseAyMinus2);
        caseAyMinus1 = orZero(caseAyMinus1);
        caseAyCurrent = orZero(caseAyCurrent);
        ibnrAyMinus2 = orZero(ibnrAyMinus2);
        ibnrAyMinus1 = orZero(ibnrAyMinus1);
        ibnrAyCurrent = orZero(ibnrAyCurrent);
        earnedPremiumAyMinus2 = orZero(earnedPremiumAyMinus2);
        earnedPremiumAyMinus1 = orZero(earnedPremiumAyMinus1);
        earnedPremiumAyCurrent = orZero(earnedPremiumAyCurrent);
    }

    private static BigDecimal orZero(BigDecimal v) {
        return v != null ? v : BigDecimal.ZERO;
    }
}
