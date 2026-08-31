package com.medfund.finance.regulatory.naic;

import java.math.BigDecimal;

/**
 * Raw source data for the NAIC Schedule F shaper — every monetary value
 * already converted to USD by {@link NaicScheduleFRawDataProvider}
 * implementations via
 * {@link com.medfund.finance.regulatory.service.RegulatoryFxPolicy}.
 *
 * <p>Phase 13 ships the {@link NaicScheduleFRawDataProvider} SPI with a
 * stub default implementation that returns zeroes and a WARN log until
 * Phase 13b wires the real cross-service peer calls (finance-service
 * reinsurance module for ceded premiums + recoverables per reinsurer
 * stratum; claims-service for outstanding case + IBNR by reinsurer).
 *
 * <p>Per-stratum fields are named {@code {stratum}{Premiums|LossesPaid|LossesUnpaid}}
 * for the four ceded strata (affiliated, non-affiliated authorized,
 * non-affiliated unauthorized, certified) plus the assumed-side aggregate.
 * The synthetic template compresses NAIC's per-reinsurer detail to this
 * per-stratum aggregate; the real template swap-in expands the record.
 *
 * <p>Company identity fields ({@code companyName}, {@code naicCode},
 * {@code groupCode}, {@code fein}, {@code stateOfDomicile}) are pulled
 * from {@code public.us_tenant_naic_config} (Phase 14) by the concrete
 * provider — the stub leaves them null.
 */
public record NaicScheduleFRawData(
        String companyName,
        String naicCode,
        String groupCode,
        String fein,
        String stateOfDomicile,
        // Assumed reinsurance (informational aggregate)
        BigDecimal assumedPremiums,
        BigDecimal assumedLossesPaid,
        BigDecimal assumedLossesUnpaid,
        // Ceded to affiliated reinsurers
        BigDecimal cededAffiliatedPremiums,
        BigDecimal cededAffiliatedLossesPaid,
        BigDecimal cededAffiliatedLossesUnpaid,
        // Ceded to non-affiliated authorized reinsurers
        BigDecimal cededAuthorizedPremiums,
        BigDecimal cededAuthorizedLossesPaid,
        BigDecimal cededAuthorizedLossesUnpaid,
        // Ceded to non-affiliated unauthorized reinsurers
        BigDecimal cededUnauthorizedPremiums,
        BigDecimal cededUnauthorizedLossesPaid,
        BigDecimal cededUnauthorizedLossesUnpaid,
        // Ceded to certified reinsurers
        BigDecimal cededCertifiedPremiums,
        BigDecimal cededCertifiedLossesPaid,
        BigDecimal cededCertifiedLossesUnpaid) {

    public NaicScheduleFRawData {
        // Null → ZERO so downstream arithmetic is safe on partial peer failures.
        assumedPremiums = orZero(assumedPremiums);
        assumedLossesPaid = orZero(assumedLossesPaid);
        assumedLossesUnpaid = orZero(assumedLossesUnpaid);
        cededAffiliatedPremiums = orZero(cededAffiliatedPremiums);
        cededAffiliatedLossesPaid = orZero(cededAffiliatedLossesPaid);
        cededAffiliatedLossesUnpaid = orZero(cededAffiliatedLossesUnpaid);
        cededAuthorizedPremiums = orZero(cededAuthorizedPremiums);
        cededAuthorizedLossesPaid = orZero(cededAuthorizedLossesPaid);
        cededAuthorizedLossesUnpaid = orZero(cededAuthorizedLossesUnpaid);
        cededUnauthorizedPremiums = orZero(cededUnauthorizedPremiums);
        cededUnauthorizedLossesPaid = orZero(cededUnauthorizedLossesPaid);
        cededUnauthorizedLossesUnpaid = orZero(cededUnauthorizedLossesUnpaid);
        cededCertifiedPremiums = orZero(cededCertifiedPremiums);
        cededCertifiedLossesPaid = orZero(cededCertifiedLossesPaid);
        cededCertifiedLossesUnpaid = orZero(cededCertifiedLossesUnpaid);
    }

    private static BigDecimal orZero(BigDecimal v) {
        return v != null ? v : BigDecimal.ZERO;
    }
}
