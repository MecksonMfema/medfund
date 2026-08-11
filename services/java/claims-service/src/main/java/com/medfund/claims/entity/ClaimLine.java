package com.medfund.claims.entity;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Table("claim_lines")
public class ClaimLine {

    @Id
    private UUID id;

    @Column("claim_id")
    private UUID claimId;

    @Column("tariff_code")
    private String tariffCode;

    private String description;

    private Integer quantity;

    @Column("unit_price")
    private BigDecimal unitPrice;

    @Column("claimed_amount")
    private BigDecimal claimedAmount;

    @Column("approved_amount")
    private BigDecimal approvedAmount;

    @Column("modifier_codes")
    private String modifierCodes;

    /** Snapshot of {@link #tariffCode} at capture time, populated only
     *  when the adjudicator overrode it. Null on unedited rows so the
     *  current column stays the single source of truth for those. */
    @Column("original_tariff_code")
    private String originalTariffCode;

    /** Snapshot of {@link #modifierCodes} at capture time — same
     *  contract as {@link #originalTariffCode}. */
    @Column("original_modifier_codes")
    private String originalModifierCodes;

    @Column("currency_code")
    private String currencyCode;

    /** V062 per-line benefit resolution. Populated at ingestion by
     *  TariffBenefitResolver: tariff_code → tariff_codes.category →
     *  tariff_benefit_mappings.benefit_type_id → the scheme_benefit for
     *  this scheme + benefit_type. NULL means the tariff category is
     *  cap-only (the mapping row exists but benefit_type_id is NULL) —
     *  the line deducts from the scheme's annual cap without touching
     *  a per-benefit ledger row. If the tariff category has no mapping
     *  row at all, adjudication rejects the line at Stage 3. */
    @Column("benefit_id")
    private UUID benefitId;

    /** Per-line adjudication state. Defaults to PENDING until an
     *  adjudicator decides; then ACCEPTED or REJECTED. */
    private String status;

    /** Populated only when {@link #status} is REJECTED. Matches a code
     *  from the tenant's rejection_reasons catalogue. */
    @Column("rejection_reason")
    private String rejectionReason;

    @CreatedDate
    @Column("created_at")
    private Instant createdAt;

    // ── V077 per-line cost-share breakdown (Phase 2 copayments, G3) ─────────
    // All nullable — populated by CostShareCalculator on the auto-approve branch;
    // manual-review / reject leaves them null and the EOB page falls back to
    // the "breakdown unavailable" banner.

    @Column("allowed_amount")
    private BigDecimal allowedAmount;

    @Column("deductible_applied")
    private BigDecimal deductibleApplied;

    @Column("copay_amount")
    private BigDecimal copayAmount;

    @Column("coinsurance_amount")
    private BigDecimal coinsuranceAmount;

    @Column("not_covered_amount")
    private BigDecimal notCoveredAmount;

    @Column("shortfall_amount")
    private BigDecimal shortfallAmount;

    @Column("member_responsibility")
    private BigDecimal memberResponsibility;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UUID getClaimId() { return claimId; }
    public void setClaimId(UUID claimId) { this.claimId = claimId; }

    public String getTariffCode() { return tariffCode; }
    public void setTariffCode(String tariffCode) { this.tariffCode = tariffCode; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public Integer getQuantity() { return quantity; }
    public void setQuantity(Integer quantity) { this.quantity = quantity; }

    public BigDecimal getUnitPrice() { return unitPrice; }
    public void setUnitPrice(BigDecimal unitPrice) { this.unitPrice = unitPrice; }

    public BigDecimal getClaimedAmount() { return claimedAmount; }
    public void setClaimedAmount(BigDecimal claimedAmount) { this.claimedAmount = claimedAmount; }

    public BigDecimal getApprovedAmount() { return approvedAmount; }
    public void setApprovedAmount(BigDecimal approvedAmount) { this.approvedAmount = approvedAmount; }

    public String getModifierCodes() { return modifierCodes; }
    public void setModifierCodes(String modifierCodes) { this.modifierCodes = modifierCodes; }

    public String getOriginalTariffCode() { return originalTariffCode; }
    public void setOriginalTariffCode(String originalTariffCode) { this.originalTariffCode = originalTariffCode; }

    public String getOriginalModifierCodes() { return originalModifierCodes; }
    public void setOriginalModifierCodes(String originalModifierCodes) { this.originalModifierCodes = originalModifierCodes; }

    public String getCurrencyCode() { return currencyCode; }
    public void setCurrencyCode(String currencyCode) { this.currencyCode = currencyCode; }

    public UUID getBenefitId() { return benefitId; }
    public void setBenefitId(UUID benefitId) { this.benefitId = benefitId; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getRejectionReason() { return rejectionReason; }
    public void setRejectionReason(String rejectionReason) { this.rejectionReason = rejectionReason; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public BigDecimal getAllowedAmount() { return allowedAmount; }
    public void setAllowedAmount(BigDecimal allowedAmount) { this.allowedAmount = allowedAmount; }

    public BigDecimal getDeductibleApplied() { return deductibleApplied; }
    public void setDeductibleApplied(BigDecimal deductibleApplied) { this.deductibleApplied = deductibleApplied; }

    public BigDecimal getCopayAmount() { return copayAmount; }
    public void setCopayAmount(BigDecimal copayAmount) { this.copayAmount = copayAmount; }

    public BigDecimal getCoinsuranceAmount() { return coinsuranceAmount; }
    public void setCoinsuranceAmount(BigDecimal coinsuranceAmount) { this.coinsuranceAmount = coinsuranceAmount; }

    public BigDecimal getNotCoveredAmount() { return notCoveredAmount; }
    public void setNotCoveredAmount(BigDecimal notCoveredAmount) { this.notCoveredAmount = notCoveredAmount; }

    public BigDecimal getShortfallAmount() { return shortfallAmount; }
    public void setShortfallAmount(BigDecimal shortfallAmount) { this.shortfallAmount = shortfallAmount; }

    public BigDecimal getMemberResponsibility() { return memberResponsibility; }
    public void setMemberResponsibility(BigDecimal memberResponsibility) { this.memberResponsibility = memberResponsibility; }
}
