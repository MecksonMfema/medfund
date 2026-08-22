package com.medfund.rules.fact;

import java.math.BigDecimal;

/**
 * Represents the outcome of a single rule evaluation. Collected in ClaimFact.results
 * during Drools session execution.
 * This is a plain POJO — not a JPA entity.
 */
public class RuleResult {

    private String type;
    private String code;
    private String message;
    private BigDecimal adjustedAmount;
    /**
     * Populated by CEDE_TO_TREATY results — the treaty layer id for
     * excess-of-loss / stop-loss cessions, null for proportional cessions.
     * Kept as a plain string so RuleResult stays a pure POJO (no UUID import).
     */
    private String layerId;

    public RuleResult() {
    }

    public RuleResult(String type, String code, String message) {
        this.type = type;
        this.code = code;
        this.message = message;
    }

    public RuleResult(String type, String code, String message, BigDecimal adjustedAmount) {
        this.type = type;
        this.code = code;
        this.message = message;
        this.adjustedAmount = adjustedAmount;
    }

    public RuleResult(String type, String code, String message, BigDecimal adjustedAmount, String layerId) {
        this.type = type;
        this.code = code;
        this.message = message;
        this.adjustedAmount = adjustedAmount;
        this.layerId = layerId;
    }

    // --- Getters and Setters ---

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public BigDecimal getAdjustedAmount() {
        return adjustedAmount;
    }

    public void setAdjustedAmount(BigDecimal adjustedAmount) {
        this.adjustedAmount = adjustedAmount;
    }

    public String getLayerId() {
        return layerId;
    }

    public void setLayerId(String layerId) {
        this.layerId = layerId;
    }
}
