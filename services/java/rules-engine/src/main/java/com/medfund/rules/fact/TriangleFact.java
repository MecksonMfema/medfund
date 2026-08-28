package com.medfund.rules.fact;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Fact for the {@code ACTUARIAL} rule category. Populated at report-submit
 * time by finance-service's {@code TriangleShapingService}; the
 * {@code SelectLdfEmitter} fires rules that pick an LDF method and record
 * it on this fact for downstream consumption by the actuarial pipeline.
 *
 * <p>Action methods invoked from DRL:
 * <ul>
 *   <li>{@link #selectLdf(String, String)} — set the LDF method chosen by
 *       the fired rule (e.g. {@code volume}, {@code simple}, {@code 5yr}).</li>
 * </ul>
 *
 * <p>Rules with {@code SELECT_LDF} actions run inside the {@code ACTUARIAL}
 * agenda group and only fire when the caller focuses that group — see
 * {@code DrlCompiler.AGENDA_GATED_CATEGORIES}.
 */
@Getter
@Setter
@NoArgsConstructor
public class TriangleFact {

    /** Line-agnostic {@code InsuranceLine} name — {@code LIFE}, {@code HEALTH}, ... */
    private String insuranceLine;

    /** {@code month} | {@code quarter} | {@code year}. */
    private String grain;

    private LocalDate periodStart;
    private LocalDate periodEnd;

    /** Number of accident cohorts in the shaped triangle. */
    private int cohortCount;

    private String reportingCurrency;

    /**
     * LDF method chosen by the fired rule. Defaults to {@code volume} so a
     * report with no matching tenant rule still runs on chain-ladder's
     * volume-weighted default.
     */
    private String ldfMethod = "volume";

    /** Trace of every rule mutation. Useful in dry-runs and audit. */
    private List<RuleResult> results = new ArrayList<>();

    /**
     * SELECT_LDF action — record the chosen LDF method. Called from DRL by
     * {@code SelectLdfEmitter}.
     */
    public void selectLdf(String method, String reason) {
        this.ldfMethod = method;
        this.results.add(new RuleResult("SELECT_LDF", method, reason));
    }
}
