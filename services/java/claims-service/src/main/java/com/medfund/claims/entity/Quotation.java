package com.medfund.claims.entity;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Table("quotations")
public class Quotation {

    @Id
    private UUID id;

    @Column("quotation_number")
    private String quotationNumber;

    @Column("member_id")
    private UUID memberId;

    @Column("provider_id")
    private UUID providerId;

    @Column("scheme_id")
    private UUID schemeId;

    @Column("diagnosis_code")
    private String diagnosisCode;

    @Column("procedure_codes")
    private String procedureCodes;  // JSON array

    private String description;

    @Column("estimated_amount")
    private BigDecimal estimatedAmount;

    @Column("covered_amount")
    private BigDecimal coveredAmount;

    @Column("co_payment_amount")
    private BigDecimal coPaymentAmount;

    /** V077 — copay auto-computed by CostShareCalculator during review. The
     *  operator's manual entry lands in {@link #coPaymentAmount}; the two are
     *  compared so the review UI can warn on override and both are audited. */
    @Column("computed_copay_amount")
    private BigDecimal computedCoPaymentAmount;

    @Column("currency_code")
    private String currencyCode;

    private String status;  // PENDING, REVIEWED, APPROVED, REJECTED, EXPIRED

    @Column("valid_until")
    private LocalDate validUntil;

    @Column("reviewed_by")
    private UUID reviewedBy;

    @Column("reviewed_at")
    private Instant reviewedAt;

    private String notes;

    @Column("rejection_reason")
    private String rejectionReason;

    @CreatedDate
    @Column("created_at")
    private Instant createdAt;

    @LastModifiedDate
    @Column("updated_at")
    private Instant updatedAt;

    @Column("created_by")
    private UUID createdBy;

    // Getters and setters for ALL fields
    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getQuotationNumber() { return quotationNumber; }
    public void setQuotationNumber(String quotationNumber) { this.quotationNumber = quotationNumber; }
    public UUID getMemberId() { return memberId; }
    public void setMemberId(UUID memberId) { this.memberId = memberId; }
    public UUID getProviderId() { return providerId; }
    public void setProviderId(UUID providerId) { this.providerId = providerId; }
    public UUID getSchemeId() { return schemeId; }
    public void setSchemeId(UUID schemeId) { this.schemeId = schemeId; }
    public String getDiagnosisCode() { return diagnosisCode; }
    public void setDiagnosisCode(String diagnosisCode) { this.diagnosisCode = diagnosisCode; }
    public String getProcedureCodes() { return procedureCodes; }
    public void setProcedureCodes(String procedureCodes) { this.procedureCodes = procedureCodes; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public BigDecimal getEstimatedAmount() { return estimatedAmount; }
    public void setEstimatedAmount(BigDecimal estimatedAmount) { this.estimatedAmount = estimatedAmount; }
    public BigDecimal getCoveredAmount() { return coveredAmount; }
    public void setCoveredAmount(BigDecimal coveredAmount) { this.coveredAmount = coveredAmount; }
    public BigDecimal getCoPaymentAmount() { return coPaymentAmount; }
    public void setCoPaymentAmount(BigDecimal coPaymentAmount) { this.coPaymentAmount = coPaymentAmount; }
    public BigDecimal getComputedCoPaymentAmount() { return computedCoPaymentAmount; }
    public void setComputedCoPaymentAmount(BigDecimal computedCoPaymentAmount) { this.computedCoPaymentAmount = computedCoPaymentAmount; }
    public String getCurrencyCode() { return currencyCode; }
    public void setCurrencyCode(String currencyCode) { this.currencyCode = currencyCode; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public LocalDate getValidUntil() { return validUntil; }
    public void setValidUntil(LocalDate validUntil) { this.validUntil = validUntil; }
    public UUID getReviewedBy() { return reviewedBy; }
    public void setReviewedBy(UUID reviewedBy) { this.reviewedBy = reviewedBy; }
    public Instant getReviewedAt() { return reviewedAt; }
    public void setReviewedAt(Instant reviewedAt) { this.reviewedAt = reviewedAt; }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
    public String getRejectionReason() { return rejectionReason; }
    public void setRejectionReason(String rejectionReason) { this.rejectionReason = rejectionReason; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public UUID getCreatedBy() { return createdBy; }
    public void setCreatedBy(UUID createdBy) { this.createdBy = createdBy; }
}
