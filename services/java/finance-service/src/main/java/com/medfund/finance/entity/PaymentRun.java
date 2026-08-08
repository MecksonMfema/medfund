package com.medfund.finance.entity;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Table("payment_runs")
public class PaymentRun {

    @Id
    private UUID id;

    @Column("run_number")
    private String runNumber;

    private String status = "draft";

    @Column("total_amount")
    private BigDecimal totalAmount;

    @Column("currency_code")
    private String currencyCode;

    @Column("payment_count")
    private Integer paymentCount;

    private String description;

    @Column("executed_at")
    private Instant executedAt;

    @Column("executed_by")
    private UUID executedBy;

    /** V067 — snapshot of the unpaid balance carried in from prior runs
     *  at generation time. Zero when nothing was rolled forward. */
    @Column("carried_in_amount")
    private BigDecimal carriedInAmount;

    /** V067 — snapshot of the unpaid balance still remaining when this
     *  run was executed. Rolls into the next run's carriedInAmount. */
    @Column("carried_out_amount")
    private BigDecimal carriedOutAmount;

    /** V067 — moment the last item in the run transitioned to paid.
     *  Null until every item in the run has settled. */
    @Column("settlement_date")
    private Instant settlementDate;

    @CreatedDate
    @Column("created_at")
    private Instant createdAt;

    @LastModifiedDate
    @Column("updated_at")
    private Instant updatedAt;

    @Column("created_by")
    private UUID createdBy;

    // Getters and setters
    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public String getRunNumber() { return runNumber; }
    public void setRunNumber(String runNumber) { this.runNumber = runNumber; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public BigDecimal getTotalAmount() { return totalAmount; }
    public void setTotalAmount(BigDecimal totalAmount) { this.totalAmount = totalAmount; }

    public String getCurrencyCode() { return currencyCode; }
    public void setCurrencyCode(String currencyCode) { this.currencyCode = currencyCode; }

    public Integer getPaymentCount() { return paymentCount; }
    public void setPaymentCount(Integer paymentCount) { this.paymentCount = paymentCount; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public Instant getExecutedAt() { return executedAt; }
    public void setExecutedAt(Instant executedAt) { this.executedAt = executedAt; }

    public UUID getExecutedBy() { return executedBy; }
    public void setExecutedBy(UUID executedBy) { this.executedBy = executedBy; }

    public BigDecimal getCarriedInAmount() { return carriedInAmount; }
    public void setCarriedInAmount(BigDecimal carriedInAmount) { this.carriedInAmount = carriedInAmount; }

    public BigDecimal getCarriedOutAmount() { return carriedOutAmount; }
    public void setCarriedOutAmount(BigDecimal carriedOutAmount) { this.carriedOutAmount = carriedOutAmount; }

    public Instant getSettlementDate() { return settlementDate; }
    public void setSettlementDate(Instant settlementDate) { this.settlementDate = settlementDate; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    public UUID getCreatedBy() { return createdBy; }
    public void setCreatedBy(UUID createdBy) { this.createdBy = createdBy; }
}
