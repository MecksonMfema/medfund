package com.medfund.finance.regulatory.ipec;

import java.math.BigDecimal;

/**
 * Raw source data for the IPEC quarterly return shaper — every monetary
 * value already converted to ZWL by {@link IpecRawDataProvider}
 * implementations via
 * {@link com.medfund.finance.regulatory.service.RegulatoryFxPolicy}.
 *
 * <p>Phase 10 ships the {@link IpecRawDataProvider} SPI with a stub
 * default implementation that returns zeroes and a WARN log until
 * Phase 10b wires the real cross-service peer calls (contributions-service
 * for GWP, claims-service for OSC/IBNR, finance-service internal for
 * reinsurance recoverables + balance sheet).
 */
public record IpecRawData(
        String tenantName,
        String licenceNumber,
        BigDecimal totalAssets,
        BigDecimal totalLiabilities,
        BigDecimal gwpHealth,
        BigDecimal gwpMotor,
        BigDecimal gwpProperty,
        BigDecimal cededReinsurancePremium,
        BigDecimal netEarnedPremium,
        BigDecimal netClaimsIncurred,
        BigDecimal managementExpenses,
        BigDecimal uprHealth,
        BigDecimal uprMotor,
        BigDecimal uprProperty,
        BigDecimal oscHealth,
        BigDecimal oscMotor,
        BigDecimal oscProperty,
        BigDecimal ibnrHealth,
        BigDecimal ibnrMotor,
        BigDecimal ibnrProperty,
        BigDecimal reiRecoverablesOutstanding,
        BigDecimal reiRecoverablesIbnr) {

    public IpecRawData {
        // Null → ZERO so downstream arithmetic is safe on partial peer failures.
        totalAssets = orZero(totalAssets);
        totalLiabilities = orZero(totalLiabilities);
        gwpHealth = orZero(gwpHealth);
        gwpMotor = orZero(gwpMotor);
        gwpProperty = orZero(gwpProperty);
        cededReinsurancePremium = orZero(cededReinsurancePremium);
        netEarnedPremium = orZero(netEarnedPremium);
        netClaimsIncurred = orZero(netClaimsIncurred);
        managementExpenses = orZero(managementExpenses);
        uprHealth = orZero(uprHealth);
        uprMotor = orZero(uprMotor);
        uprProperty = orZero(uprProperty);
        oscHealth = orZero(oscHealth);
        oscMotor = orZero(oscMotor);
        oscProperty = orZero(oscProperty);
        ibnrHealth = orZero(ibnrHealth);
        ibnrMotor = orZero(ibnrMotor);
        ibnrProperty = orZero(ibnrProperty);
        reiRecoverablesOutstanding = orZero(reiRecoverablesOutstanding);
        reiRecoverablesIbnr = orZero(reiRecoverablesIbnr);
    }

    /** Total revenue across all short-term lines — convenience for the shaper. */
    public BigDecimal gwpTotal() {
        return gwpHealth.add(gwpMotor).add(gwpProperty);
    }

    /** Total OSC + IBNR across all lines — feeds the solvency calculator. */
    public BigDecimal reservesTotal() {
        return oscHealth.add(oscMotor).add(oscProperty)
                .add(ibnrHealth).add(ibnrMotor).add(ibnrProperty);
    }

    private static BigDecimal orZero(BigDecimal v) {
        return v != null ? v : BigDecimal.ZERO;
    }
}
