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

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getRejectionReason() { return rejectionReason; }
    public void setRejectionReason(String rejectionReason) { this.rejectionReason = rejectionReason; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
