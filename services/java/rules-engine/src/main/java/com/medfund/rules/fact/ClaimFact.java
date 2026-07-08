package com.medfund.rules.fact;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Fact object representing a claim inserted into the Drools KieSession for rule evaluation.
 * This is a plain POJO — not a JPA entity.
 */
public class ClaimFact {

    private String claimId;
    private String memberId;
    private String providerId;
    private String schemeId;
    private String benefitCategory;
    private String tariffCode;
    private String icdCode;
    private BigDecimal amount;
    private String currencyCode;
    private LocalDate dateOfService;
    private LocalDate submissionDate;
    private int daysSinceService;
    private boolean isEmergency;
    private boolean isAccident;
    private boolean isElective;
    private boolean hasPreAuth;
    private String preAuthStatus;
    private BigDecimal preAuthAmount;
    private int procedureCount;
    private List<ClaimDetailFact> details;
    private List<RuleResult> results = new ArrayList<>();
    /**
     * Set by a BENEFIT_PRORATION rule via {@link #setProrationStrategy(String)}.
     * Read back by {@code ProrationService} to route the arithmetic. Null when
     * no rule fired — service falls through to tenant config then platform default.
     */
    private String prorationStrategy;
    /** Populated by ClaimFactBuilder when the member has an effective scheme change on or before dateOfService. */
    private SchemeChangeContext schemeChange;

    public ClaimFact() {
    }

    // --- Convenience methods ---

    public void addRejection(String code, String message) {
        results.add(new RuleResult("REJECT", code, message));
    }

    public void addWarning(String message) {
        results.add(new RuleResult("WARN", null, message));
    }

    public void addWarning(String code, String message) {
        results.add(new RuleResult("WARN", code, message));
    }

    public void addFlag(String message) {
        results.add(new RuleResult("FLAG", null, message));
    }

    public void addFlag(String code, String message) {
        results.add(new RuleResult("FLAG", code, message));
    }

    /**
     * APPLY_COPAY action — record a percentage-of-amount co-pay against the claim.
     * The actual deduction is applied by the consuming service when it reads the
     * result; the engine only emits the intent and the computed amount.
     */
    public void applyPercentageCopay(double percentage, String message) {
        BigDecimal pct = BigDecimal.valueOf(percentage);
        BigDecimal copay = amount != null ? amount.multiply(pct).movePointLeft(2) : BigDecimal.ZERO;
        results.add(new RuleResult("APPLY_COPAY", "PCT-" + percentage, message, copay));
    }

    /** APPLY_COPAY action — record a fixed-amount co-pay. */
    public void applyFixedCopay(BigDecimal copayAmount, String message) {
        results.add(new RuleResult("APPLY_COPAY", "FIXED", message, copayAmount));
    }

    public boolean hasRejections() {
        return results.stream().anyMatch(r -> "REJECT".equals(r.getType()));
    }

    public List<RuleResult> getRejections() {
        return results.stream()
                .filter(r -> "REJECT".equals(r.getType()))
                .collect(Collectors.toList());
    }

    // --- Getters and Setters ---

    public String getClaimId() {
        return claimId;
    }

    public void setClaimId(String claimId) {
        this.claimId = claimId;
    }

    public String getMemberId() {
        return memberId;
    }

    public void setMemberId(String memberId) {
        this.memberId = memberId;
    }

    public String getProviderId() {
        return providerId;
    }

    public void setProviderId(String providerId) {
        this.providerId = providerId;
    }

    public String getSchemeId() {
        return schemeId;
    }

    public void setSchemeId(String schemeId) {
        this.schemeId = schemeId;
    }

    public String getBenefitCategory() {
        return benefitCategory;
    }

    public void setBenefitCategory(String benefitCategory) {
        this.benefitCategory = benefitCategory;
    }

    public String getTariffCode() {
        return tariffCode;
    }

    public void setTariffCode(String tariffCode) {
        this.tariffCode = tariffCode;
    }

    public String getIcdCode() {
        return icdCode;
    }

    public void setIcdCode(String icdCode) {
        this.icdCode = icdCode;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public String getCurrencyCode() {
        return currencyCode;
    }

    public void setCurrencyCode(String currencyCode) {
        this.currencyCode = currencyCode;
    }

    public LocalDate getDateOfService() {
        return dateOfService;
    }

    public void setDateOfService(LocalDate dateOfService) {
        this.dateOfService = dateOfService;
    }

    public LocalDate getSubmissionDate() {
        return submissionDate;
    }

    public void setSubmissionDate(LocalDate submissionDate) {
        this.submissionDate = submissionDate;
    }

    public int getDaysSinceService() {
        return daysSinceService;
    }

    public void setDaysSinceService(int daysSinceService) {
        this.daysSinceService = daysSinceService;
    }

    public boolean isEmergency() {
        return isEmergency;
    }

    public void setEmergency(boolean emergency) {
        isEmergency = emergency;
    }

    public boolean isAccident() {
        return isAccident;
    }

    public void setAccident(boolean accident) {
        isAccident = accident;
    }

    public boolean isElective() {
        return isElective;
    }

    public void setElective(boolean elective) {
        isElective = elective;
    }

    public boolean isHasPreAuth() {
        return hasPreAuth;
    }

    public void setHasPreAuth(boolean hasPreAuth) {
        this.hasPreAuth = hasPreAuth;
    }

    public String getPreAuthStatus() {
        return preAuthStatus;
    }

    public void setPreAuthStatus(String preAuthStatus) {
        this.preAuthStatus = preAuthStatus;
    }

    public BigDecimal getPreAuthAmount() {
        return preAuthAmount;
    }

    public void setPreAuthAmount(BigDecimal preAuthAmount) {
        this.preAuthAmount = preAuthAmount;
    }

    public int getProcedureCount() {
        return procedureCount;
    }

    public void setProcedureCount(int procedureCount) {
        this.procedureCount = procedureCount;
    }

    public List<ClaimDetailFact> getDetails() {
        return details;
    }

    public void setDetails(List<ClaimDetailFact> details) {
        this.details = details;
    }

    public List<RuleResult> getResults() {
        return results;
    }

    public void setResults(List<RuleResult> results) {
        this.results = results;
    }

    public String getProrationStrategy() {
        return prorationStrategy;
    }

    public void setProrationStrategy(String prorationStrategy) {
        this.prorationStrategy = prorationStrategy;
    }

    public SchemeChangeContext getSchemeChange() {
        return schemeChange;
    }

    public void setSchemeChange(SchemeChangeContext schemeChange) {
        this.schemeChange = schemeChange;
    }
}
