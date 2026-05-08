package com.medfund.rules.fact;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Fact for member lifecycle rules — onboarding, age-group classification,
 * underwriting, suspension, termination, reinstatement, scheme/group transfers.
 *
 * <p>Action methods invoked by rules:
 * <ul>
 *   <li>{@link #setAgeGroup(String, String)} — assign the tenant-defined age band label.</li>
 *   <li>{@link #requireUnderwriting(String, String)} — flag enrollment for human review.</li>
 *   <li>{@link #autoRenew(String)} — mark the membership as eligible for auto-renewal.</li>
 *   <li>{@link #terminate(String, String)} — auto-terminate (e.g. for non-payment).</li>
 *   <li>{@link #addOutcome(String, String)} — generic trace.</li>
 * </ul>
 */
@Getter
@Setter
@NoArgsConstructor
public class MemberLifecycleFact {

    private String memberId;
    private String currentStatus;          // ENROLLED / ACTIVE / SUSPENDED / TERMINATED
    private String requestedTransition;    // ENROLL / SUSPEND / TERMINATE / REINSTATE / GROUP_TRANSFER / SCHEME_CHANGE
    private LocalDate enrollmentDate;
    private LocalDate dateOfBirth;
    private int age;
    private String gender;
    private String region;
    private String groupId;
    private String schemeId;
    private int dependantCount;

    /** Risk-screening inputs consumed by underwriting rules. */
    private boolean hasPreExistingConditions;
    private boolean smoker;
    private double bmi;

    /** Drivers for auto-termination rules. */
    private int contributionsInArrearsMonths;

    // ── Outputs ──────────────────────────────────────────────────────────────

    /** Set by SET_AGE_GROUP rules. */
    private String ageGroup;
    /** Set by REQUIRE_UNDERWRITING rules. */
    private String underwritingLevel;
    private boolean autoRenewEligible;
    private boolean terminationRequested;
    private String terminationReason;
    private List<RuleResult> results = new ArrayList<>();

    // ── Action methods ───────────────────────────────────────────────────────

    public void setAgeGroup(String label, String reason) {
        this.ageGroup = label;
        this.results.add(new RuleResult("SET_AGE_GROUP", label, reason));
    }

    public void requireUnderwriting(String level, String reason) {
        this.underwritingLevel = level;
        this.results.add(new RuleResult("REQUIRE_UNDERWRITING", level, reason));
    }

    public void autoRenew(String reason) {
        this.autoRenewEligible = true;
        this.results.add(new RuleResult("AUTO_RENEW", null, reason));
    }

    public void terminate(String code, String reason) {
        this.terminationRequested = true;
        this.terminationReason = reason;
        this.results.add(new RuleResult("TERMINATE_MEMBERSHIP", code, reason));
    }

    public void addOutcome(String code, String message) {
        this.results.add(new RuleResult("OUTCOME", code, message));
    }
}
