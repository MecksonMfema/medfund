package com.medfund.claims.entity;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * claims-service view of {@code beneficiary_benefits} — used for the
 * adjudication write path. Read-side lives in contributions-service.
 */
@Table("beneficiary_benefits")
public class BeneficiaryBenefit {

    @Id
    private UUID id;

    @Column("member_id")
    private UUID memberId;

    @Column("dependant_id")
    private UUID dependantId;

    @Column("benefit_id")
    private UUID benefitId;

    @Column("scheme_id")
    private UUID schemeId;

    @Column("policy_year")
    private Integer policyYear;

    @Column("consumed_amount")
    private BigDecimal consumedAmount;

    @Column("consumed_count")
    private Integer consumedCount;

    @Column("currency_code")
    private String currencyCode;

    @CreatedDate
    @Column("created_at")
    private Instant createdAt;

    @LastModifiedDate
    @Column("updated_at")
    private Instant updatedAt;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getMemberId() { return memberId; }
    public void setMemberId(UUID memberId) { this.memberId = memberId; }
    public UUID getDependantId() { return dependantId; }
    public void setDependantId(UUID dependantId) { this.dependantId = dependantId; }
    public UUID getBenefitId() { return benefitId; }
    public void setBenefitId(UUID benefitId) { this.benefitId = benefitId; }
    public UUID getSchemeId() { return schemeId; }
    public void setSchemeId(UUID schemeId) { this.schemeId = schemeId; }
    public Integer getPolicyYear() { return policyYear; }
    public void setPolicyYear(Integer policyYear) { this.policyYear = policyYear; }
    public BigDecimal getConsumedAmount() { return consumedAmount; }
    public void setConsumedAmount(BigDecimal consumedAmount) { this.consumedAmount = consumedAmount; }
    public Integer getConsumedCount() { return consumedCount; }
    public void setConsumedCount(Integer consumedCount) { this.consumedCount = consumedCount; }
    public String getCurrencyCode() { return currencyCode; }
    public void setCurrencyCode(String currencyCode) { this.currencyCode = currencyCode; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
