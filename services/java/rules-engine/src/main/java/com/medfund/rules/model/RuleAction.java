package com.medfund.rules.model;

import java.math.BigDecimal;

/**
 * The action to take when a rule's conditions are satisfied.
 *
 * <p>Field semantics by {@link com.medfund.rules.model.ActionType}:
 * <ul>
 *   <li><b>REJECT</b> — {@code rejectionCode} + {@code message}.</li>
 *   <li><b>FLAG_FOR_REVIEW / WARN</b> — {@code message}.</li>
 *   <li><b>APPLY_COPAY</b> — {@code value} = {@code "10"} for 10% or
 *       {@code "FIXED:25"} for a fixed 25-currency-unit copay.</li>
 *   <li><b>SET_PREMIUM</b> / <b>APPLY_LATE_FEE</b> — {@code value} = decimal amount.</li>
 *   <li><b>APPLY_LOADED_PREMIUM</b> — {@code value} = multiplier (e.g. {@code "1.25"}).</li>
 *   <li><b>SET_AGE_GROUP</b> — {@code value} = label (e.g. {@code "ADULT"}).</li>
 *   <li><b>REQUIRE_UNDERWRITING</b> — {@code value} = level ({@code "LOW"}/{@code "MED"}/{@code "HIGH"}).</li>
 *   <li><b>SCHEDULE_PAYMENT_RUN</b> — {@code value} = ISO date or {@code "TODAY"}.</li>
 *   <li><b>WITHHOLD_PAYMENT</b> — {@code value} = percentage (e.g. {@code "10"}).</li>
 *   <li><b>MATCH_RECORDS</b> / <b>AUTO_RENEW</b> / <b>TERMINATE_MEMBERSHIP</b> —
 *       {@code message} (and {@code rejectionCode} as a categorisation tag for the latter two).</li>
 * </ul>
 */
public class RuleAction {

    private String type;
    private String rejectionCode;
    private String message;

    /** Generic, action-specific parameter — see class-level Javadoc for shape per type. */
    private Object value;

    /** Pre-computed monetary adjustment captured for audit; populated at evaluation time. */
    private BigDecimal adjustedAmount;

    public RuleAction() {}

    // --- Getters and Setters ---

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getRejectionCode() { return rejectionCode; }
    public void setRejectionCode(String rejectionCode) { this.rejectionCode = rejectionCode; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public Object getValue() { return value; }
    public void setValue(Object value) { this.value = value; }

    public BigDecimal getAdjustedAmount() { return adjustedAmount; }
    public void setAdjustedAmount(BigDecimal adjustedAmount) { this.adjustedAmount = adjustedAmount; }
}
