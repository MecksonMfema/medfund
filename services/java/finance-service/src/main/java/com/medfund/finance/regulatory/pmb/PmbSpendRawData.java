package com.medfund.finance.regulatory.pmb;

import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.Map;

/**
 * Raw source data for the PMB Spend report shaper — every monetary value
 * already converted to ZAR by {@link PmbSpendRawDataProvider} implementations
 * via {@link com.medfund.finance.regulatory.service.RegulatoryFxPolicy}.
 *
 * <p>Phase 18 ships the {@link PmbSpendRawDataProvider} SPI with a stub
 * default implementation that returns zeroes and a WARN log until a later
 * sub-phase wires the real claims-service peer call
 * (SUM(paid_amount) GROUP BY is_pmb + pmb_condition_code + currency).
 *
 * <p>Per-category maps hold the aggregation output; the shaper transposes
 * category-keyed values into {@link PmbField} cells and derives grand totals.
 * Beneficiary count is a plain long to survive JSON round-trip through
 * report_job persistence.
 */
public record PmbSpendRawData(
        String schemeName,
        String registrationNumber,
        long totalBeneficiaries,
        Map<PmbCategory, BigDecimal> pmbPaidByCategory,
        Map<PmbCategory, Long> pmbCountByCategory,
        BigDecimal nonPmbPaid) {

    public PmbSpendRawData {
        pmbPaidByCategory = defensiveDecimalMap(pmbPaidByCategory);
        pmbCountByCategory = defensiveLongMap(pmbCountByCategory);
        nonPmbPaid = orZero(nonPmbPaid);
    }

    /** Total PMB paid across every category — convenience for the shaper. */
    public BigDecimal totalPmbPaid() {
        BigDecimal acc = BigDecimal.ZERO;
        for (BigDecimal v : pmbPaidByCategory.values()) {
            acc = acc.add(v);
        }
        return acc;
    }

    /** Total PMB claim count across every category. */
    public long totalPmbCount() {
        long acc = 0L;
        for (Long v : pmbCountByCategory.values()) {
            acc += v;
        }
        return acc;
    }

    /** All claims paid = PMB paid + non-PMB paid. */
    public BigDecimal totalAllClaimsPaid() {
        return totalPmbPaid().add(nonPmbPaid);
    }

    private static Map<PmbCategory, BigDecimal> defensiveDecimalMap(Map<PmbCategory, BigDecimal> in) {
        Map<PmbCategory, BigDecimal> out = new EnumMap<>(PmbCategory.class);
        for (PmbCategory c : PmbCategory.values()) {
            out.put(c, in != null && in.get(c) != null ? in.get(c) : BigDecimal.ZERO);
        }
        return out;
    }

    private static Map<PmbCategory, Long> defensiveLongMap(Map<PmbCategory, Long> in) {
        Map<PmbCategory, Long> out = new EnumMap<>(PmbCategory.class);
        for (PmbCategory c : PmbCategory.values()) {
            out.put(c, in != null && in.get(c) != null ? in.get(c) : 0L);
        }
        return out;
    }

    private static BigDecimal orZero(BigDecimal v) {
        return v != null ? v : BigDecimal.ZERO;
    }
}
