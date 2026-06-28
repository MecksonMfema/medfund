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

@Table("life_policies")
public class LifePolicy {

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

    @Column("sum_assured")
    private BigDecimal sumAssured;

    @Column("occupation_hazard_class")
    private String occupationHazardClass;

    @Column("term_months")
    private Integer termMonths;

    private String status;

    @Column("billing_override_amount")
    private BigDecimal billingOverrideAmount;

    @Column("billing_override_reason")
    private String billingOverrideReason;

    @Column("billing_override_effective_from")
    private LocalDate billingOverrideEffectiveFrom;

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
    public BigDecimal getSumAssured() { return sumAssured; }
    public void setSumAssured(BigDecimal sumAssured) { this.sumAssured = sumAssured; }
    public String getOccupationHazardClass() { return occupationHazardClass; }
    public void setOccupationHazardClass(String occupationHazardClass) { this.occupationHazardClass = occupationHazardClass; }
    public Integer getTermMonths() { return termMonths; }
    public void setTermMonths(Integer termMonths) { this.termMonths = termMonths; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public BigDecimal getBillingOverrideAmount() { return billingOverrideAmount; }
    public void setBillingOverrideAmount(BigDecimal billingOverrideAmount) { this.billingOverrideAmount = billingOverrideAmount; }
    public String getBillingOverrideReason() { return billingOverrideReason; }
    public void setBillingOverrideReason(String billingOverrideReason) { this.billingOverrideReason = billingOverrideReason; }
    public LocalDate getBillingOverrideEffectiveFrom() { return billingOverrideEffectiveFrom; }
    public void setBillingOverrideEffectiveFrom(LocalDate billingOverrideEffectiveFrom) { this.billingOverrideEffectiveFrom = billingOverrideEffectiveFrom; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public UUID getCreatedBy() { return createdBy; }
    public void setCreatedBy(UUID createdBy) { this.createdBy = createdBy; }
    public UUID getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(UUID updatedBy) { this.updatedBy = updatedBy; }
}
