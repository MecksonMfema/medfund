package com.medfund.finance.regulatory.cms;

import java.math.BigDecimal;

/**
 * Raw source data for the CMS Annual Statutory Return shaper — every
 * monetary value already converted to ZAR by {@link CmsAsrRawDataProvider}
 * implementations via
 * {@link com.medfund.finance.regulatory.service.RegulatoryFxPolicy}.
 *
 * <p>Phase 11 ships the {@link CmsAsrRawDataProvider} SPI with a stub
 * default implementation that returns zeroes and a WARN log until
 * Phase 11b wires the real cross-service peer calls (contributions-service
 * for gross + net contributions, claims-service for risk claims incurred,
 * finance-service internal for admin / broker / managed-care spend +
 * balance sheet).
 *
 * <p>Membership counts are integers on the write path; the raw record
 * carries them as {@code long} to survive JSON round-trip through the
 * report_job persistence layer.
 */
public record CmsAsrRawData(
        String schemeName,
        String registrationNumber,
        long principalMembers,
        long dependants,
        BigDecimal pensionerRatio,
        BigDecimal totalAssets,
        BigDecimal totalLiabilities,
        BigDecimal grossContributions,
        BigDecimal netContributions,
        BigDecimal riskClaimsIncurred,
        BigDecimal adminExpenses,
        BigDecimal brokerFees,
        BigDecimal managedCareFees) {

    public CmsAsrRawData {
        // Null → ZERO so downstream arithmetic is safe on partial peer failures.
        pensionerRatio = orZero(pensionerRatio);
        totalAssets = orZero(totalAssets);
        totalLiabilities = orZero(totalLiabilities);
        grossContributions = orZero(grossContributions);
        netContributions = orZero(netContributions);
        riskClaimsIncurred = orZero(riskClaimsIncurred);
        adminExpenses = orZero(adminExpenses);
        brokerFees = orZero(brokerFees);
        managedCareFees = orZero(managedCareFees);
    }

    /** Total beneficiaries — principals + dependants. */
    public long totalBeneficiaries() {
        return principalMembers + dependants;
    }

    /** Non-healthcare cost total — admin + broker + managed-care. */
    public BigDecimal nonHealthcareTotal() {
        return adminExpenses.add(brokerFees).add(managedCareFees);
    }

    /** Net surplus / (deficit) = net contributions − risk claims − non-healthcare. */
    public BigDecimal netSurplus() {
        return netContributions.subtract(riskClaimsIncurred).subtract(nonHealthcareTotal());
    }

    private static BigDecimal orZero(BigDecimal v) {
        return v != null ? v : BigDecimal.ZERO;
    }
}
