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

@Table("claims")
public class Claim {

    @Id
    private UUID id;

    @Column("claim_number")
    private String claimNumber;

    @Column("member_id")
    private UUID memberId;

    @Column("dependant_id")
    private UUID dependantId;

    @Column("provider_id")
    private UUID providerId;

    /** V066 — payment routing. 'PROVIDER' pays the service provider
     *  directly; 'MEMBER' reimburses the sponsoring member (used when the
     *  member paid out-of-pocket, or for LIFE / DISABILITY payouts). Kept
     *  distinct from providerId so an adjudicator sees provider info even
     *  when the payment goes to the member. */
    @Column("payee_type")
    private String payeeType;

    @Column("scheme_id")
    private UUID schemeId;

    @Column("benefit_id")
    private UUID benefitId;

    @Column("claim_type")
    private String claimType;

    @Column("insurance_line")
    private String insuranceLine;

    /** Optional per-claim tag for capture flows that group related
     *  claims. Null for ad-hoc single-claim submissions. Never
     *  server-generated — the operator types it or leaves it blank. */
    @Column("batch_number")
    private String batchNumber;

    private String status;

    @Column("service_date")
    private LocalDate serviceDate;

    @Column("submission_date")
    private Instant submissionDate;

    @Column("claimed_amount")
    private BigDecimal claimedAmount;

    @Column("approved_amount")
    private BigDecimal approvedAmount;

    @Column("paid_amount")
    private BigDecimal paidAmount;

    @Column("currency_code")
    private String currencyCode;

    @Column("diagnosis_codes")
    private String diagnosisCodes;

    @Column("procedure_codes")
    private String procedureCodes;

    private String notes;

    @Column("rejection_reason")
    private String rejectionReason;

    @Column("rejection_notes")
    private String rejectionNotes;

    @Column("verification_code")
    private String verificationCode;

    @Column("verified_at")
    private Instant verifiedAt;

    @Column("adjudicated_at")
    private Instant adjudicatedAt;

    @Column("adjudicated_by")
    private UUID adjudicatedBy;

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

    /** JSON array of {@code ClaimAttachment} metadata captured at
     *  submission. Nullable — most claims won't carry attachments,
     *  and an empty array is stored as NULL to keep the row tight. */
    @Column("attachments_json")
    private String attachmentsJson;

    // Getters and setters
    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public String getClaimNumber() { return claimNumber; }
    public void setClaimNumber(String claimNumber) { this.claimNumber = claimNumber; }

    public UUID getMemberId() { return memberId; }
    public void setMemberId(UUID memberId) { this.memberId = memberId; }

    public UUID getDependantId() { return dependantId; }
    public void setDependantId(UUID dependantId) { this.dependantId = dependantId; }

    public UUID getProviderId() { return providerId; }
    public void setProviderId(UUID providerId) { this.providerId = providerId; }

    public String getPayeeType() { return payeeType; }
    public void setPayeeType(String payeeType) { this.payeeType = payeeType; }

    public UUID getSchemeId() { return schemeId; }
    public void setSchemeId(UUID schemeId) { this.schemeId = schemeId; }

    public UUID getBenefitId() { return benefitId; }
    public void setBenefitId(UUID benefitId) { this.benefitId = benefitId; }

    public String getClaimType() { return claimType; }
    public void setClaimType(String claimType) { this.claimType = claimType; }

    public String getInsuranceLine() { return insuranceLine; }
    public void setInsuranceLine(String insuranceLine) { this.insuranceLine = insuranceLine; }

    public String getBatchNumber() { return batchNumber; }
    public void setBatchNumber(String batchNumber) { this.batchNumber = batchNumber; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public LocalDate getServiceDate() { return serviceDate; }
    public void setServiceDate(LocalDate serviceDate) { this.serviceDate = serviceDate; }

    public Instant getSubmissionDate() { return submissionDate; }
    public void setSubmissionDate(Instant submissionDate) { this.submissionDate = submissionDate; }

    public BigDecimal getClaimedAmount() { return claimedAmount; }
    public void setClaimedAmount(BigDecimal claimedAmount) { this.claimedAmount = claimedAmount; }

    public BigDecimal getApprovedAmount() { return approvedAmount; }
    public void setApprovedAmount(BigDecimal approvedAmount) { this.approvedAmount = approvedAmount; }

    public BigDecimal getPaidAmount() { return paidAmount; }
    public void setPaidAmount(BigDecimal paidAmount) { this.paidAmount = paidAmount; }

    public String getCurrencyCode() { return currencyCode; }
    public void setCurrencyCode(String currencyCode) { this.currencyCode = currencyCode; }

    public String getDiagnosisCodes() { return diagnosisCodes; }
    public void setDiagnosisCodes(String diagnosisCodes) { this.diagnosisCodes = diagnosisCodes; }

    public String getProcedureCodes() { return procedureCodes; }
    public void setProcedureCodes(String procedureCodes) { this.procedureCodes = procedureCodes; }

    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }

    public String getRejectionReason() { return rejectionReason; }
    public void setRejectionReason(String rejectionReason) { this.rejectionReason = rejectionReason; }

    public String getRejectionNotes() { return rejectionNotes; }
    public void setRejectionNotes(String rejectionNotes) { this.rejectionNotes = rejectionNotes; }

    public String getVerificationCode() { return verificationCode; }
    public void setVerificationCode(String verificationCode) { this.verificationCode = verificationCode; }

    public Instant getVerifiedAt() { return verifiedAt; }
    public void setVerifiedAt(Instant verifiedAt) { this.verifiedAt = verifiedAt; }

    public Instant getAdjudicatedAt() { return adjudicatedAt; }
    public void setAdjudicatedAt(Instant adjudicatedAt) { this.adjudicatedAt = adjudicatedAt; }

    public UUID getAdjudicatedBy() { return adjudicatedBy; }
    public void setAdjudicatedBy(UUID adjudicatedBy) { this.adjudicatedBy = adjudicatedBy; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    public UUID getCreatedBy() { return createdBy; }
    public void setCreatedBy(UUID createdBy) { this.createdBy = createdBy; }

    public UUID getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(UUID updatedBy) { this.updatedBy = updatedBy; }

    public String getAttachmentsJson() { return attachmentsJson; }
    public void setAttachmentsJson(String attachmentsJson) { this.attachmentsJson = attachmentsJson; }
}
