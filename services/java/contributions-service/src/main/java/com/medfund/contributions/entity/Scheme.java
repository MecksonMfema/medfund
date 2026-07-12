package com.medfund.contributions.entity;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Table("schemes")
public class Scheme {

    @Id
    private UUID id;

    private String name;

    private String description;

    @Column("scheme_type")
    private String schemeType = "medical_aid";

    @Column("insurance_line")
    private String insuranceLine = "HEALTH";

    private String status = "active";

    @Column("effective_date")
    private LocalDate effectiveDate;

    @Column("end_date")
    private LocalDate endDate;

    @Column("currency_code")
    private String currencyCode;

    /**
     * V050 age-eligibility gate. When either bound is set, MemberService.enroll
     * rejects new members whose age at enrolment falls outside the range with
     * 422 AGE_OUT_OF_RANGE. Null = unbounded. Populated only for person-centric
     * lines (HEALTH, LIFE, FUNERAL, GROUP, TRAVEL, DISABILITY).
     */
    @Column("min_age")
    private Short minAge;

    @Column("max_age")
    private Short maxAge;

    /**
     * V061 product-level ledger opt-out. When false, no per-member
     * beneficiary_benefits rows are seeded on enrolment and adjudication
     * Stage 3 skips the balance check — the product uses scheme-level
     * limits only (indemnity model, typical for VEHICLE / PROPERTY).
     */
    @Column("tracks_member_balances")
    private Boolean tracksMemberBalances = Boolean.TRUE;

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

    // Getters and setters
    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getSchemeType() { return schemeType; }
    public void setSchemeType(String schemeType) { this.schemeType = schemeType; }

    public String getInsuranceLine() { return insuranceLine; }
    public void setInsuranceLine(String insuranceLine) { this.insuranceLine = insuranceLine; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public LocalDate getEffectiveDate() { return effectiveDate; }
    public void setEffectiveDate(LocalDate effectiveDate) { this.effectiveDate = effectiveDate; }

    public LocalDate getEndDate() { return endDate; }
    public void setEndDate(LocalDate endDate) { this.endDate = endDate; }

    public String getCurrencyCode() { return currencyCode; }
    public void setCurrencyCode(String currencyCode) { this.currencyCode = currencyCode; }

    public Short getMinAge() { return minAge; }
    public void setMinAge(Short minAge) { this.minAge = minAge; }

    public Short getMaxAge() { return maxAge; }
    public void setMaxAge(Short maxAge) { this.maxAge = maxAge; }

    public Boolean getTracksMemberBalances() { return tracksMemberBalances; }
    public void setTracksMemberBalances(Boolean tracksMemberBalances) { this.tracksMemberBalances = tracksMemberBalances; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    public UUID getCreatedBy() { return createdBy; }
    public void setCreatedBy(UUID createdBy) { this.createdBy = createdBy; }

    public UUID getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(UUID updatedBy) { this.updatedBy = updatedBy; }
}
