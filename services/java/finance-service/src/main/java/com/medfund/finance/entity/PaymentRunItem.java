package com.medfund.finance.entity;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Table("payment_run_items")
public class PaymentRunItem {

    @Id
    private UUID id;

    @Column("payment_run_id")
    private UUID paymentRunId;

    @Column("payment_id")
    private UUID paymentId;

    @Column("provider_id")
    private UUID providerId;

    @Column("member_id")
    private UUID memberId;

    @Column("payee_type")
    private String payeeType = "PROVIDER";

    private BigDecimal amount;

    @Column("currency_code")
    private String currencyCode;

    private String status = "pending";

    @CreatedDate
    @Column("created_at")
    private Instant createdAt;

    // Getters and setters
    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UUID getPaymentRunId() { return paymentRunId; }
    public void setPaymentRunId(UUID paymentRunId) { this.paymentRunId = paymentRunId; }

    public UUID getPaymentId() { return paymentId; }
    public void setPaymentId(UUID paymentId) { this.paymentId = paymentId; }

    public UUID getProviderId() { return providerId; }
    public void setProviderId(UUID providerId) { this.providerId = providerId; }

    public UUID getMemberId() { return memberId; }
    public void setMemberId(UUID memberId) { this.memberId = memberId; }

    public String getPayeeType() { return payeeType; }
    public void setPayeeType(String payeeType) { this.payeeType = payeeType; }

    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }

    public String getCurrencyCode() { return currencyCode; }
    public void setCurrencyCode(String currencyCode) { this.currencyCode = currencyCode; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
