package com.medfund.rules.fact;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Context describing a member's most-recent effective scheme change relative
 * to a claim's date of service. Populated by ClaimFactBuilder when — and only
 * when — the member has at least one {@code EFFECTIVE} SchemeChange row with
 * {@code effective_date <= dateOfService}. Members on their first scheme have
 * a null context and skip proration entirely.
 *
 * <p>Carried on {@link ClaimFact#setSchemeChange(SchemeChangeContext)}. Read by
 * both Drools rules (routing via APPLY_PRORATION_STRATEGY) and by the Java
 * strategy arithmetic in {@code ProrationStrategy.apply(...)}.
 */
public class SchemeChangeContext {

    private String prevSchemeId;
    private String newSchemeId;
    private String prevBenefitId;
    private String newBenefitId;
    private BigDecimal prevAnnualLimit;
    private BigDecimal newAnnualLimit;
    private BigDecimal consumedUnderPrevScheme;
    private BigDecimal consumedUnderNewScheme;
    private String prevCurrencyCode;
    private String newCurrencyCode;
    private LocalDate effectiveDate;
    /** UPGRADE / DOWNGRADE / CURRENCY_CHANGE / CROSS_GRADE — copied from SchemeChange.changeKind. */
    private String changeKind;
    private int daysSinceChange;
    private int daysRemainingInYear;
    private int increment;
    /** Sum of the periods' days — 365 for a normal year, 366 for a leap year. */
    private int daysInYear;

    public SchemeChangeContext() {
    }

    public boolean isCrossCurrency() {
        return prevCurrencyCode != null && newCurrencyCode != null
                && !prevCurrencyCode.equalsIgnoreCase(newCurrencyCode);
    }

    public BigDecimal totalConsumed() {
        BigDecimal p = consumedUnderPrevScheme != null ? consumedUnderPrevScheme : BigDecimal.ZERO;
        BigDecimal n = consumedUnderNewScheme != null ? consumedUnderNewScheme : BigDecimal.ZERO;
        return p.add(n);
    }

    // --- Getters and Setters ---

    public String getPrevSchemeId() { return prevSchemeId; }
    public void setPrevSchemeId(String prevSchemeId) { this.prevSchemeId = prevSchemeId; }

    public String getNewSchemeId() { return newSchemeId; }
    public void setNewSchemeId(String newSchemeId) { this.newSchemeId = newSchemeId; }

    public String getPrevBenefitId() { return prevBenefitId; }
    public void setPrevBenefitId(String prevBenefitId) { this.prevBenefitId = prevBenefitId; }

    public String getNewBenefitId() { return newBenefitId; }
    public void setNewBenefitId(String newBenefitId) { this.newBenefitId = newBenefitId; }

    public BigDecimal getPrevAnnualLimit() { return prevAnnualLimit; }
    public void setPrevAnnualLimit(BigDecimal prevAnnualLimit) { this.prevAnnualLimit = prevAnnualLimit; }

    public BigDecimal getNewAnnualLimit() { return newAnnualLimit; }
    public void setNewAnnualLimit(BigDecimal newAnnualLimit) { this.newAnnualLimit = newAnnualLimit; }

    public BigDecimal getConsumedUnderPrevScheme() { return consumedUnderPrevScheme; }
    public void setConsumedUnderPrevScheme(BigDecimal v) { this.consumedUnderPrevScheme = v; }

    public BigDecimal getConsumedUnderNewScheme() { return consumedUnderNewScheme; }
    public void setConsumedUnderNewScheme(BigDecimal v) { this.consumedUnderNewScheme = v; }

    public String getPrevCurrencyCode() { return prevCurrencyCode; }
    public void setPrevCurrencyCode(String prevCurrencyCode) { this.prevCurrencyCode = prevCurrencyCode; }

    public String getNewCurrencyCode() { return newCurrencyCode; }
    public void setNewCurrencyCode(String newCurrencyCode) { this.newCurrencyCode = newCurrencyCode; }

    public LocalDate getEffectiveDate() { return effectiveDate; }
    public void setEffectiveDate(LocalDate effectiveDate) { this.effectiveDate = effectiveDate; }

    public String getChangeKind() { return changeKind; }
    public void setChangeKind(String changeKind) { this.changeKind = changeKind; }

    public int getDaysSinceChange() { return daysSinceChange; }
    public void setDaysSinceChange(int daysSinceChange) { this.daysSinceChange = daysSinceChange; }

    public int getDaysRemainingInYear() { return daysRemainingInYear; }
    public void setDaysRemainingInYear(int daysRemainingInYear) { this.daysRemainingInYear = daysRemainingInYear; }

    public int getIncrement() { return increment; }
    public void setIncrement(int increment) { this.increment = increment; }

    public int getDaysInYear() { return daysInYear; }
    public void setDaysInYear(int daysInYear) { this.daysInYear = daysInYear; }
}
