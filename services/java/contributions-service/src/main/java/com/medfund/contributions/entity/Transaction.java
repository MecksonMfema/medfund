package com.medfund.contributions.entity;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Table("transactions")
public class Transaction {

    @Id
    private UUID id;

    @Column("transaction_number")
    private String transactionNumber;

    /** Optional refinement — a payment MAY point at a specific contribution
     *  it's meant to settle. Nullable; balance ownership is decided by
     *  {@link #groupId}/{@link #memberId}, not by this link. */
    @Column("contribution_id")
    private UUID contributionId;

    @Column("invoice_id")
    private UUID invoiceId;

    /** Direct owner columns (V039). Exactly one of the two is non-null
     *  for post-V039 rows; the CHECK constraint enforces the exclusivity. */
    @Column("group_id")
    private UUID groupId;

    @Column("member_id")
    private UUID memberId;

    private BigDecimal amount;

    @Column("currency_code")
    private String currencyCode;

    @Column("transaction_type")
    private String transactionType;

    @Column("payment_method")
    private String paymentMethod;

    private String reference;

    /**
     * Free-text justification, required for adjustment-style entries
     * (CREDIT / DEBIT). Distinct from {@link #reference} (an external
     * bank/wallet id) — this is the operator's own note about why the
     * ledger is being moved.
     */
    private String reason;

    private String status = "completed";

    @Column("transaction_date")
    private Instant transactionDate;

    @CreatedDate
    @Column("created_at")
    private Instant createdAt;

    @Column("created_by")
    private UUID createdBy;

    // Getters and setters
    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public String getTransactionNumber() { return transactionNumber; }
    public void setTransactionNumber(String transactionNumber) { this.transactionNumber = transactionNumber; }

    public UUID getContributionId() { return contributionId; }
    public void setContributionId(UUID contributionId) { this.contributionId = contributionId; }

    public UUID getInvoiceId() { return invoiceId; }
    public void setInvoiceId(UUID invoiceId) { this.invoiceId = invoiceId; }

    public UUID getGroupId() { return groupId; }
    public void setGroupId(UUID groupId) { this.groupId = groupId; }

    public UUID getMemberId() { return memberId; }
    public void setMemberId(UUID memberId) { this.memberId = memberId; }

    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }

    public String getCurrencyCode() { return currencyCode; }
    public void setCurrencyCode(String currencyCode) { this.currencyCode = currencyCode; }

    public String getTransactionType() { return transactionType; }
    public void setTransactionType(String transactionType) { this.transactionType = transactionType; }

    public String getPaymentMethod() { return paymentMethod; }
    public void setPaymentMethod(String paymentMethod) { this.paymentMethod = paymentMethod; }

    public String getReference() { return reference; }
    public void setReference(String reference) { this.reference = reference; }

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public Instant getTransactionDate() { return transactionDate; }
    public void setTransactionDate(Instant transactionDate) { this.transactionDate = transactionDate; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public UUID getCreatedBy() { return createdBy; }
    public void setCreatedBy(UUID createdBy) { this.createdBy = createdBy; }
}
