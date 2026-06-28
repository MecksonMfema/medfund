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

@Table("properties")
public class Property {

    @Id
    private UUID id;

    @Column("scheme_id")
    private UUID schemeId;

    @Column("group_id")
    private UUID groupId;

    @Column("owner_member_id")
    private UUID ownerMemberId;

    @Column("property_name")
    private String propertyName;

    private String address;

    @Column("sum_insured")
    private BigDecimal sumInsured;

    @Column("construction_type")
    private String constructionType;

    @Column("roof_type")
    private String roofType;

    @Column("location_risk_band")
    private String locationRiskBand;

    @Column("security_features_count")
    private Integer securityFeaturesCount;

    @Column("property_age_years")
    private Integer propertyAgeYears;

    private String occupancy;

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
    public UUID getOwnerMemberId() { return ownerMemberId; }
    public void setOwnerMemberId(UUID ownerMemberId) { this.ownerMemberId = ownerMemberId; }
    public String getPropertyName() { return propertyName; }
    public void setPropertyName(String propertyName) { this.propertyName = propertyName; }
    public String getAddress() { return address; }
    public void setAddress(String address) { this.address = address; }
    public BigDecimal getSumInsured() { return sumInsured; }
    public void setSumInsured(BigDecimal sumInsured) { this.sumInsured = sumInsured; }
    public String getConstructionType() { return constructionType; }
    public void setConstructionType(String constructionType) { this.constructionType = constructionType; }
    public String getRoofType() { return roofType; }
    public void setRoofType(String roofType) { this.roofType = roofType; }
    public String getLocationRiskBand() { return locationRiskBand; }
    public void setLocationRiskBand(String locationRiskBand) { this.locationRiskBand = locationRiskBand; }
    public Integer getSecurityFeaturesCount() { return securityFeaturesCount; }
    public void setSecurityFeaturesCount(Integer securityFeaturesCount) { this.securityFeaturesCount = securityFeaturesCount; }
    public Integer getPropertyAgeYears() { return propertyAgeYears; }
    public void setPropertyAgeYears(Integer propertyAgeYears) { this.propertyAgeYears = propertyAgeYears; }
    public String getOccupancy() { return occupancy; }
    public void setOccupancy(String occupancy) { this.occupancy = occupancy; }
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
