package com.medfund.user.entity;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Table("disability_policies")
public class DisabilityPolicy {

    @Id
    private UUID id;

    @Column("scheme_id")
    private UUID schemeId;

    @Column("group_id")
    private UUID groupId;

    @Column("insured_member_id")
    private UUID insuredMemberId;

    @Column("policy_number")
    private String policyNumber;

    @Column("occupation_hazard_class")
    private String occupationHazardClass;

    @Column("waiting_period_days")
    private Integer waitingPeriodDays;

    @Column("benefit_period")
    private String benefitPeriod;

    @Column("monthly_benefit")
    private BigDecimal monthlyBenefit;

    private String status;

    @Column("billing_override_amount")
    private BigDecimal billingOverrideAmount;

    @Column("billing_override_reason")
    private String billingOverrideReason;

    @Column("billing_override_effective_from")
    private LocalDate billingOverrideEffectiveFrom;

    @Column("written_premium")
    private BigDecimal writtenPremium;

    @Column("written_premium_currency")
    private String writtenPremiumCurrency;

    @Column("bound_at")
    private Instant boundAt;

    @Column("coverage_start")
    private LocalDate coverageStart;

    @Column("coverage_end")
    private LocalDate coverageEnd;

    @Column("renewed_from_policy_id")
    private UUID renewedFromPolicyId;

    @Column("portfolio_id")
    private UUID portfolioId;

    @Column("cohort_id")
    private UUID cohortId;

    @CreatedDate
    @Column("created_at")
    private Instant createdAt;

    @LastModifiedDate
    @Column("updated_at")
    private Instant updatedAt;

    @Column("created_by")
    private UUID createdBy;

    @Column("updated_by")
    private UUID updatedBy;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getSchemeId() { return schemeId; }
    public void setSchemeId(UUID schemeId) { this.schemeId = schemeId; }
    public UUID getGroupId() { return groupId; }
    public void setGroupId(UUID groupId) { this.groupId = groupId; }
    public UUID getInsuredMemberId() { return insuredMemberId; }
    public void setInsuredMemberId(UUID insuredMemberId) { this.insuredMemberId = insuredMemberId; }
    public String getPolicyNumber() { return policyNumber; }
    public void setPolicyNumber(String policyNumber) { this.policyNumber = policyNumber; }
    public String getOccupationHazardClass() { return occupationHazardClass; }
    public void setOccupationHazardClass(String occupationHazardClass) { this.occupationHazardClass = occupationHazardClass; }
    public Integer getWaitingPeriodDays() { return waitingPeriodDays; }
    public void setWaitingPeriodDays(Integer waitingPeriodDays) { this.waitingPeriodDays = waitingPeriodDays; }
    public String getBenefitPeriod() { return benefitPeriod; }
    public void setBenefitPeriod(String benefitPeriod) { this.benefitPeriod = benefitPeriod; }
    public BigDecimal getMonthlyBenefit() { return monthlyBenefit; }
    public void setMonthlyBenefit(BigDecimal monthlyBenefit) { this.monthlyBenefit = monthlyBenefit; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public BigDecimal getBillingOverrideAmount() { return billingOverrideAmount; }
    public void setBillingOverrideAmount(BigDecimal billingOverrideAmount) { this.billingOverrideAmount = billingOverrideAmount; }
    public String getBillingOverrideReason() { return billingOverrideReason; }
    public void setBillingOverrideReason(String billingOverrideReason) { this.billingOverrideReason = billingOverrideReason; }
    public LocalDate getBillingOverrideEffectiveFrom() { return billingOverrideEffectiveFrom; }
    public void setBillingOverrideEffectiveFrom(LocalDate billingOverrideEffectiveFrom) { this.billingOverrideEffectiveFrom = billingOverrideEffectiveFrom; }
    public BigDecimal getWrittenPremium() { return writtenPremium; }
    public void setWrittenPremium(BigDecimal writtenPremium) { this.writtenPremium = writtenPremium; }
    public String getWrittenPremiumCurrency() { return writtenPremiumCurrency; }
    public void setWrittenPremiumCurrency(String writtenPremiumCurrency) { this.writtenPremiumCurrency = writtenPremiumCurrency; }
    public Instant getBoundAt() { return boundAt; }
    public void setBoundAt(Instant boundAt) { this.boundAt = boundAt; }
    public LocalDate getCoverageStart() { return coverageStart; }
    public void setCoverageStart(LocalDate coverageStart) { this.coverageStart = coverageStart; }
    public LocalDate getCoverageEnd() { return coverageEnd; }
    public void setCoverageEnd(LocalDate coverageEnd) { this.coverageEnd = coverageEnd; }
    public UUID getRenewedFromPolicyId() { return renewedFromPolicyId; }
    public void setRenewedFromPolicyId(UUID renewedFromPolicyId) { this.renewedFromPolicyId = renewedFromPolicyId; }
    public UUID getPortfolioId() { return portfolioId; }
    public void setPortfolioId(UUID portfolioId) { this.portfolioId = portfolioId; }
    public UUID getCohortId() { return cohortId; }
    public void setCohortId(UUID cohortId) { this.cohortId = cohortId; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public UUID getCreatedBy() { return createdBy; }
    public void setCreatedBy(UUID createdBy) { this.createdBy = createdBy; }
    public UUID getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(UUID updatedBy) { this.updatedBy = updatedBy; }
}
