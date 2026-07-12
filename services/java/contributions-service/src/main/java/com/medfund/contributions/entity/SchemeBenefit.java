package com.medfund.contributions.entity;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Table("scheme_benefits")
public class SchemeBenefit {

    @Id
    private UUID id;

    @Column("scheme_id")
    private UUID schemeId;

    private String name;

    @Column("benefit_type")
    private String benefitType;

    @Column("annual_limit")
    private BigDecimal annualLimit;

    @Column("daily_limit")
    private BigDecimal dailyLimit;

    @Column("event_limit")
    private BigDecimal eventLimit;

    @Column("currency_code")
    private String currencyCode;

    @Column("waiting_period_days")
    private Integer waitingPeriodDays;

    /**
     * V051 benefit-level age gate. AdjudicationPipeline Stage 3 rejects
     * claims whose member.age is outside [min_age, max_age] with
     * AGE_OUT_OF_RANGE. Null = unbounded. Common patterns:
     *   - "Preventive care 40+" → minAge=40
     *   - "Youth dental tops at 17" → maxAge=17
     */
    @Column("min_age")
    private Short minAge;

    @Column("max_age")
    private Short maxAge;

    /**
     * V051 payout-mode flag. When false, adjudication rejects CASH-payout
     * claims for this benefit with IN_KIND_ONLY (must pay provider directly).
     * Defaults to true so existing benefits keep their behaviour.
     */
    @Column("cash_claim_allowed")
    private Boolean cashClaimAllowed = Boolean.TRUE;

    /**
     * V061 benefit usage classification. Drives the per-member ledger:
     * running-balance benefits accrue amount; one-time benefits are
     * exhausted after a single successful claim; per-event benefits are
     * capped by count AND amount; NO_TRACKING skips the ledger entirely.
     */
    @Column("usage_mode")
    private String usageMode = "RUNNING_BALANCE";

    private String description;

    private String status = "active";

    @CreatedDate
    @Column("created_at")
    private Instant createdAt;

    @LastModifiedDate
    @Column("updated_at")
    private Instant updatedAt;

    // Getters and setters
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UUID getSchemeId() { return schemeId; }
    public void setSchemeId(UUID schemeId) { this.schemeId = schemeId; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getBenefitType() { return benefitType; }
    public void setBenefitType(String benefitType) { this.benefitType = benefitType; }

    public BigDecimal getAnnualLimit() { return annualLimit; }
    public void setAnnualLimit(BigDecimal annualLimit) { this.annualLimit = annualLimit; }

    public BigDecimal getDailyLimit() { return dailyLimit; }
    public void setDailyLimit(BigDecimal dailyLimit) { this.dailyLimit = dailyLimit; }

    public BigDecimal getEventLimit() { return eventLimit; }
    public void setEventLimit(BigDecimal eventLimit) { this.eventLimit = eventLimit; }

    public String getCurrencyCode() { return currencyCode; }
    public void setCurrencyCode(String currencyCode) { this.currencyCode = currencyCode; }

    public Integer getWaitingPeriodDays() { return waitingPeriodDays; }
    public void setWaitingPeriodDays(Integer waitingPeriodDays) { this.waitingPeriodDays = waitingPeriodDays; }

    public Short getMinAge() { return minAge; }
    public void setMinAge(Short minAge) { this.minAge = minAge; }

    public Short getMaxAge() { return maxAge; }
    public void setMaxAge(Short maxAge) { this.maxAge = maxAge; }

    public Boolean getCashClaimAllowed() { return cashClaimAllowed; }
    public void setCashClaimAllowed(Boolean cashClaimAllowed) { this.cashClaimAllowed = cashClaimAllowed; }

    public String getUsageMode() { return usageMode; }
    public void setUsageMode(String usageMode) { this.usageMode = usageMode; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
