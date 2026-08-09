package com.medfund.rules.fact;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Fact for finance / provider-payment rules. Drives:
 * <ul>
 *   <li>SCHEDULE_PAYMENT_RUN — when to actually pay a provider out.</li>
 *   <li>WITHHOLD_PAYMENT — hold back a percentage pending audit/reconciliation.</li>
 *   <li>MATCH_RECORDS — mark a candidate match in reconciliation flows.</li>
 * </ul>
 */
@Getter
@Setter
@NoArgsConstructor
public class PaymentRunFact {

    private String paymentRunId;
    private String providerId;
    private LocalDate runDate;
    private LocalDate previousRunDate;
    private BigDecimal amountDue;
    private BigDecimal advancePaid;
    private String currencyCode;
    private int daysSinceLastRun;
    private int outstandingClaimsCount;
    private boolean providerVerified;

    /**
     * Convenience predicate for the shipped starter template
     * "PR04 - Auto-offset when advance covers item". True when the
     * provider's outstanding advance balance (in this item's currency)
     * is at least the item's amountDue and the item has a positive
     * amountDue. Computed at fact build time — the rule engine can
     * treat this as a plain boolean without needing property-to-property
     * comparisons (which the JSON->DRL compiler does not support).
     */
    public boolean isAdvanceCoversAmount() {
        return amountDue != null
            && amountDue.signum() > 0
            && advancePaid != null
            && advancePaid.compareTo(amountDue) >= 0;
    }

    // ── Outputs ──────────────────────────────────────────────────────────────

    private boolean scheduled;
    private LocalDate scheduledDate;
    private BigDecimal withholdAmount = BigDecimal.ZERO;
    private double withholdPercentage = 0.0;
    private boolean reconciled;
    private List<RuleResult> results = new ArrayList<>();

    // ── Action methods ───────────────────────────────────────────────────────

    public void schedule(LocalDate date, String reason) {
        this.scheduled = true;
        this.scheduledDate = date;
        this.results.add(new RuleResult("SCHEDULE_PAYMENT_RUN", null, reason));
    }

    public void withhold(double percentage, String reason) {
        this.withholdPercentage = percentage;
        if (this.amountDue != null) {
            this.withholdAmount = this.amountDue.multiply(BigDecimal.valueOf(percentage / 100.0));
        }
        this.results.add(new RuleResult("WITHHOLD_PAYMENT", null, reason, this.withholdAmount));
    }

    public void matchRecords(String code, String reason) {
        this.reconciled = true;
        this.results.add(new RuleResult("MATCH_RECORDS", code, reason));
    }

    public void addOutcome(String code, String message) {
        this.results.add(new RuleResult("OUTCOME", code, message));
    }
}
