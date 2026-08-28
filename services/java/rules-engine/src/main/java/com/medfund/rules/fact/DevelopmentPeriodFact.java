package com.medfund.rules.fact;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * Fact for the {@code ACTUARIAL} rule category — per-development-period
 * snapshot of the three candidate LDFs (volume-weighted, simple average,
 * N-year weighted). Populated alongside {@link TriangleFact} by
 * finance-service's {@code TriangleShapingService}; rules can compare the
 * three candidates and record a per-dev-period selection on
 * {@link #selectedLdf}.
 *
 * <p>Rules with {@code SELECT_LDF} actions read the candidate columns and
 * set {@link #selectedLdf} in place; the actuarial pipeline reads the
 * mutated value straight off this fact after firing.
 */
@Getter
@Setter
@NoArgsConstructor
public class DevelopmentPeriodFact {

    private String insuranceLine;

    /** 1-based index of the development period within the triangle. */
    private int devPeriodIndex;

    private BigDecimal volumeWeightedLdf;
    private BigDecimal simpleAverageLdf;
    private BigDecimal fiveYearWeightedLdf;

    /**
     * Selected LDF for this development period. OUT-only — the caller
     * reads this after DRL fires. Left null when no rule selected a value,
     * in which case the caller falls back to {@link #volumeWeightedLdf}.
     */
    private BigDecimal selectedLdf;
}
