package com.medfund.rules.fact;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Fact for contribution / premium-billing rules. Carries everything a tenant
 * rule needs to compute a premium, decide whether a contribution is overdue,
 * or apply a late fee.
 *
 * <p>Action methods invoked by rules:
 * <ul>
 *   <li>{@link #setPremium(BigDecimal, String)} — assign computed premium amount.</li>
 *   <li>{@link #applyLoadedPremium(double, String)} — multiply standard premium for risk loading.</li>
 *   <li>{@link #applyLateFee(BigDecimal, String)} — append a late-fee charge.</li>
 *   <li>{@link #addOutcome(String, String)} — generic outcome trace for diagnostics.</li>
 * </ul>
 */
@Getter
@Setter
@NoArgsConstructor
public class ContributionFact {

    /** Surrogate id of the contribution row (when re-evaluating an existing one). */
    private String contributionId;

    /** Member the contribution is for. */
    private String memberId;
    /** Scheme the member is on at the contribution period (drives base price). */
    private String schemeId;
    /** Group / employer (if applicable — null for individual policies). */
    private String groupId;
    private String currencyCode;

    /** Period this contribution covers — first day of the billing period. */
    private LocalDate periodStart;
    /** Period this contribution covers — last day of the billing period. */
    private LocalDate periodEnd;
    /** Date the contribution was actually invoiced (drives late-fee math). */
    private LocalDate billingDate;

    /** Member age at the start of the period (-1 if unknown — rules should guard). */
    private int memberAge;
    private String memberRegion;
    private String memberGender;
    private int dependantCount;
    private boolean hasChronicConditions;

    // ── Phase B — medical-history fact projection ────────────────────────────
    // Sourced from members.medical_history JSONB (V031). HEALTH-specific
    // typed fields. They stay around for back-compat with rules already
    // written against them; new rules SHOULD prefer the line-agnostic
    // {@link #attributes} map below so the same DRL works for any line.
    // All default to safe sentinel values so a member with no medical_history
    // captured behaves like the legacy "unknown risk" case — no loading.

    /** Number of chronic conditions logged for this member (0 when unknown). */
    private int chronicConditionCount;
    /** "NEVER" | "FORMER" | "CURRENT" | null when unknown. */
    private String smokingStatus;
    /** Body mass index, null when not captured. */
    private java.math.BigDecimal bmi;
    /** Active medication count (0 when unknown). */
    private int medicationCount;

    // ── Line-agnostic attribute bag (Phase B follow-up) ──────────────────────
    // Holds whatever signals the scheme's insurance line cares about — for
    // HEALTH the medical-history projection lands here in parallel to the
    // typed fields above; for MOTOR a tenant FactBuilder would put
    // {"vehicle.age": 12, "vehicle.value": 8500, "driver.claims_last_3y": 2}
    // and so on. DRL rules reference attributes via {@code fact.attribute("key")}
    // which keeps rule syntax line-agnostic.
    //
    // Empty map (never null) so DRL .containsKey checks are safe.
    private java.util.Map<String, Object> attributes = new java.util.HashMap<>();

    /** Read an attribute by key. Returns null when absent — rules should
     *  guard with isNotNull where the field is required. */
    public Object attribute(String key) { return attributes != null ? attributes.get(key) : null; }

    /** Convenience: numeric attribute or {@code 0} when absent or non-numeric. */
    public double attributeAsDouble(String key) {
        Object v = attribute(key);
        if (v instanceof Number n) return n.doubleValue();
        if (v instanceof String s) {
            try { return Double.parseDouble(s); } catch (NumberFormatException e) { return 0; }
        }
        return 0;
    }

    /** Convenience: string attribute or null. */
    public String attributeAsString(String key) {
        Object v = attribute(key);
        return v != null ? v.toString() : null;
    }

    /** Amount due before any rule-driven adjustments. */
    private BigDecimal baseAmount;
    /** Amount written by SET_PREMIUM rules. Reflects final billing. */
    private BigDecimal premiumAmount;
    /** Sum of late fees / penalties added by rules. */
    private BigDecimal totalLateFees = BigDecimal.ZERO;
    /** Multiplier applied by APPLY_LOADED_PREMIUM rules (default 1.0). */
    private double premiumLoadingFactor = 1.0;

    /** Days the contribution is overdue (0 if paid on time or not yet due). */
    private int daysOverdue;
    private boolean paid;
    private boolean inPaymentPlan;

    /** Trace of every rule mutation. Useful in dry-runs and audit. */
    private List<RuleResult> results = new ArrayList<>();

    // ── Action methods invoked from DRL ──────────────────────────────────────

    /** SET_PREMIUM action — write the computed premium for the period. */
    public void setPremium(BigDecimal amount, String reason) {
        this.premiumAmount = amount;
        this.results.add(new RuleResult("SET_PREMIUM", null, reason, amount));
    }

    /** APPLY_LOADED_PREMIUM action — multiply the standard premium by a risk factor. */
    public void applyLoadedPremium(double multiplier, String reason) {
        this.premiumLoadingFactor *= multiplier;
        if (this.premiumAmount != null) {
            this.premiumAmount = this.premiumAmount.multiply(BigDecimal.valueOf(multiplier));
        }
        this.results.add(new RuleResult("APPLY_LOADED_PREMIUM", null, reason));
    }

    /** APPLY_LATE_FEE action — append a fee, accumulating into {@link #totalLateFees}. */
    public void applyLateFee(BigDecimal amount, String reason) {
        this.totalLateFees = this.totalLateFees == null
                ? amount : this.totalLateFees.add(amount);
        this.results.add(new RuleResult("APPLY_LATE_FEE", null, reason, amount));
    }

    /** Generic trace — for rules that just want to surface a finding. */
    public void addOutcome(String code, String message) {
        this.results.add(new RuleResult("OUTCOME", code, message));
    }
}
