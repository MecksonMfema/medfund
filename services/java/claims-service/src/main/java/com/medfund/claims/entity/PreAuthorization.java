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

@Table("pre_authorizations")
public class PreAuthorization {

    @Id
    private UUID id;

    @Column("auth_number")
    private String authNumber;

    @Column("member_id")
    private UUID memberId;

    @Column("dependant_id")
    private UUID dependantId;

    @Column("provider_id")
    private UUID providerId;

    @Column("scheme_id")
    private UUID schemeId;

    @Column("tariff_code")
    private String tariffCode;

    @Column("diagnosis_code")
    private String diagnosisCode;

    private String status;

    @Column("requested_amount")
    private BigDecimal requestedAmount;

    @Column("approved_amount")
    private BigDecimal approvedAmount;

    @Column("currency_code")
    private String currencyCode;

    @Column("requested_date")
    private LocalDate requestedDate;

    @Column("decision_date")
    private LocalDate decisionDate;

    @Column("expiry_date")
    private LocalDate expiryDate;

    @Column("decision_by")
    private UUID decisionBy;

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

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public String getAuthNumber() { return authNumber; }
    public void setAuthNumber(String authNumber) { this.authNumber = authNumber; }

    public UUID getMemberId() { return memberId; }
    public void setMemberId(UUID memberId) { this.memberId = memberId; }

    public UUID getDependantId() { return dependantId; }
    public void setDependantId(UUID dependantId) { this.dependantId = dependantId; }

    public UUID getProviderId() { return providerId; }
    public void setProviderId(UUID providerId) { this.providerId = providerId; }

    public UUID getSchemeId() { return schemeId; }
    public void setSchemeId(UUID schemeId) { this.schemeId = schemeId; }

    public String getTariffCode() { return tariffCode; }
    public void setTariffCode(String tariffCode) { this.tariffCode = tariffCode; }

    public String getDiagnosisCode() { return diagnosisCode; }
    public void setDiagnosisCode(String diagnosisCode) { this.diagnosisCode = diagnosisCode; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public BigDecimal getRequestedAmount() { return requestedAmount; }
    public void setRequestedAmount(BigDecimal requestedAmount) { this.requestedAmount = requestedAmount; }

    public BigDecimal getApprovedAmount() { return approvedAmount; }
    public void setApprovedAmount(BigDecimal approvedAmount) { this.approvedAmount = approvedAmount; }

    public String getCurrencyCode() { return currencyCode; }
    public void setCurrencyCode(String currencyCode) { this.currencyCode = currencyCode; }

    public LocalDate getRequestedDate() { return requestedDate; }
    public void setRequestedDate(LocalDate requestedDate) { this.requestedDate = requestedDate; }

    public LocalDate getDecisionDate() { return decisionDate; }
    public void setDecisionDate(LocalDate decisionDate) { this.decisionDate = decisionDate; }

    public LocalDate getExpiryDate() { return expiryDate; }
    public void setExpiryDate(LocalDate expiryDate) { this.expiryDate = expiryDate; }

    public UUID getDecisionBy() { return decisionBy; }
    public void setDecisionBy(UUID decisionBy) { this.decisionBy = decisionBy; }

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
