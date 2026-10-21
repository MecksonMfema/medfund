package com.medfund.finance.regulatory.pmb;

import com.medfund.shared.report.regulatory.RegulatoryReportGenerationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/**
 * Derives the summary ratios for the PMB Spend report from raw category
 * totals. Unlike IPEC/CMS/NAIC, PMB spend needs no configurable statutory
 * parameters — the report is a straight aggregation, and this class only
 * exists to keep the per-regulator structure parallel and to house the
 * two ratio derivations (PMB share of total spend + PMB spend per
 * beneficiary) with proper divide-by-zero handling.
 *
 * <p>All monetary inputs must be in the reporting currency (ZAR) — the
 * shaper wires the {@link com.medfund.finance.regulatory.service.RegulatoryFxPolicy}
 * upstream in {@link PmbSpendRawDataProvider}.
 */
@Slf4j
@Component
public class PmbSpendCalculator {

    /** Derived summary metrics — populated into the {@code TOTAL_*} cells. */
    public record Summary(
            BigDecimal totalPmbPaid,
            BigDecimal totalNonPmbPaid,
            BigDecimal totalAllClaimsPaid,
            BigDecimal pmbRatio,
            BigDecimal pmbPaidPerBeneficiary) {}

    /**
     * Compute the summary metrics for the given raw data. Ratios are
     * decimals (0.35 = 35 %). Divide-by-zero yields
     * {@link BigDecimal#ZERO} for the ratio + per-beneficiary metric so
     * a scheme with no claim spend (or zero beneficiaries) doesn't blow
     * up the export — the surrounding template already flags the empty
     * totals for a compliance reader.
     */
    public Summary computeSummary(BigDecimal totalPmbPaid,
                                  BigDecimal totalNonPmbPaid,
                                  long totalBeneficiaries) {
        requireNonNull(totalPmbPaid, "totalPmbPaid");
        requireNonNull(totalNonPmbPaid, "totalNonPmbPaid");
        if (totalBeneficiaries < 0) {
            throw new RegulatoryReportGenerationException(
                    "totalBeneficiaries must be non-negative (was " + totalBeneficiaries + ")");
        }
        BigDecimal totalAll = totalPmbPaid.add(totalNonPmbPaid);
        BigDecimal ratio = totalAll.signum() == 0
                ? BigDecimal.ZERO
                : totalPmbPaid.divide(totalAll, MathContext.DECIMAL64).setScale(4, RoundingMode.HALF_UP);
        BigDecimal perBeneficiary = totalBeneficiaries == 0
                ? BigDecimal.ZERO
                : totalPmbPaid.divide(BigDecimal.valueOf(totalBeneficiaries), MathContext.DECIMAL64)
                        .setScale(2, RoundingMode.HALF_UP);
        return new Summary(
                totalPmbPaid.setScale(2, RoundingMode.HALF_UP),
                totalNonPmbPaid.setScale(2, RoundingMode.HALF_UP),
                totalAll.setScale(2, RoundingMode.HALF_UP),
                ratio,
                perBeneficiary);
    }

    private static void requireNonNull(BigDecimal v, String name) {
        if (v == null) {
            throw new RegulatoryReportGenerationException(name + " required");
        }
    }
}
