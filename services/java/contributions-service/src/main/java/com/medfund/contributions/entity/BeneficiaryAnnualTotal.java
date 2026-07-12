package com.medfund.contributions.entity;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * V062 scheme-level aggregate cap ledger. One row per
 * (scheme, member/dependant, policy_year). Dedicated ledger (not
 * derived from beneficiary_benefits) because cap-only lines have no
 * per-benefit row to sum from.
 *
 * <p>{@code dependant_id} is NULL when the beneficiary IS the member —
 * same NULL-dependant pattern as beneficiary_benefits.
 */
@Table("beneficiary_annual_totals")
public class BeneficiaryAnnualTotal {

    @Id
    private UUID id;

    @Column("scheme_id")
    private UUID schemeId;

    @Column("member_id")
    private UUID memberId;

    @Column("dependant_id")
    private UUID dependantId;

    @Column("policy_year")
    private Integer policyYear;

    @Column("consumed_amount")
    private BigDecimal consumedAmount;

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
    public UUID getSchemeId() { return schemeId; }
    public void setSchemeId(UUID schemeId) { this.schemeId = schemeId; }
    public UUID getMemberId() { return memberId; }
    public void setMemberId(UUID memberId) { this.memberId = memberId; }
    public UUID getDependantId() { return dependantId; }
    public void setDependantId(UUID dependantId) { this.dependantId = dependantId; }
    public Integer getPolicyYear() { return policyYear; }
    public void setPolicyYear(Integer policyYear) { this.policyYear = policyYear; }
    public BigDecimal getConsumedAmount() { return consumedAmount; }
    public void setConsumedAmount(BigDecimal consumedAmount) { this.consumedAmount = consumedAmount; }
    public String getCurrencyCode() { return currencyCode; }
    public void setCurrencyCode(String currencyCode) { this.currencyCode = currencyCode; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
