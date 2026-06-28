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

@Table("travel_policies")
public class TravelPolicy {

    @Id
    private UUID id;

    @Column("scheme_id")
    private UUID schemeId;

    @Column("group_id")
    private UUID groupId;

    @Column("traveler_member_id")
    private UUID travelerMemberId;

    @Column("policy_number")
    private String policyNumber;

    @Column("trip_start_date")
    private LocalDate tripStartDate;

    @Column("trip_end_date")
    private LocalDate tripEndDate;

    @Column("destination_band")
    private String destinationBand;

    @Column("coverage_level")
    private String coverageLevel;

    @Column("pre_existing_declared")
    private Boolean preExistingDeclared;

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
    public UUID getTravelerMemberId() { return travelerMemberId; }
    public void setTravelerMemberId(UUID travelerMemberId) { this.travelerMemberId = travelerMemberId; }
    public String getPolicyNumber() { return policyNumber; }
    public void setPolicyNumber(String policyNumber) { this.policyNumber = policyNumber; }
    public LocalDate getTripStartDate() { return tripStartDate; }
    public void setTripStartDate(LocalDate tripStartDate) { this.tripStartDate = tripStartDate; }
    public LocalDate getTripEndDate() { return tripEndDate; }
    public void setTripEndDate(LocalDate tripEndDate) { this.tripEndDate = tripEndDate; }
    public String getDestinationBand() { return destinationBand; }
    public void setDestinationBand(String destinationBand) { this.destinationBand = destinationBand; }
    public String getCoverageLevel() { return coverageLevel; }
    public void setCoverageLevel(String coverageLevel) { this.coverageLevel = coverageLevel; }
    public Boolean getPreExistingDeclared() { return preExistingDeclared; }
    public void setPreExistingDeclared(Boolean preExistingDeclared) { this.preExistingDeclared = preExistingDeclared; }
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
