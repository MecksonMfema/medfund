package com.medfund.contributions.entity;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Table("invoices")
public class Invoice {

    @Id
    private UUID id;

    @Column("invoice_number")
    private String invoiceNumber;

    @Column("group_id")
    private UUID groupId;

    @Column("member_id")
    private UUID memberId;

    @Column("scheme_id")
    private UUID schemeId;

    @Column("total_amount")
    private BigDecimal totalAmount;

    @Column("currency_code")
    private String currencyCode;

    private String status = "draft";

    @Column("period_start")
    private LocalDate periodStart;

    @Column("period_end")
    private LocalDate periodEnd;

    @Column("due_date")
    private LocalDate dueDate;

    @Column("issued_at")
    private Instant issuedAt;

    @Column("paid_at")
    private Instant paidAt;

    // Snapshot fields captured at commit time by InvoiceSnapshotService.
    // See plan §3a/§3b — once written these are immutable; the next
    // commit's window starts at committed_at so every transaction is
    // consumed by exactly one invoice.
    @Column("committed_at")
    private Instant committedAt;

    @Column("opening_balance")
    private BigDecimal openingBalance;

    @Column("closing_balance")
    private BigDecimal closingBalance;

    @Column("payments_in_window")
    private BigDecimal paymentsInWindow;

    @Column("adjustments_in_window")
    private BigDecimal adjustmentsInWindow;

    @Column("prior_invoice_id")
    private UUID priorInvoiceId;

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

    public String getInvoiceNumber() { return invoiceNumber; }
    public void setInvoiceNumber(String invoiceNumber) { this.invoiceNumber = invoiceNumber; }

    public UUID getGroupId() { return groupId; }
    public void setGroupId(UUID groupId) { this.groupId = groupId; }

    public UUID getMemberId() { return memberId; }
    public void setMemberId(UUID memberId) { this.memberId = memberId; }

    public UUID getSchemeId() { return schemeId; }
    public void setSchemeId(UUID schemeId) { this.schemeId = schemeId; }

    public BigDecimal getTotalAmount() { return totalAmount; }
    public void setTotalAmount(BigDecimal totalAmount) { this.totalAmount = totalAmount; }

    public String getCurrencyCode() { return currencyCode; }
    public void setCurrencyCode(String currencyCode) { this.currencyCode = currencyCode; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public LocalDate getPeriodStart() { return periodStart; }
    public void setPeriodStart(LocalDate periodStart) { this.periodStart = periodStart; }

    public LocalDate getPeriodEnd() { return periodEnd; }
    public void setPeriodEnd(LocalDate periodEnd) { this.periodEnd = periodEnd; }

    public LocalDate getDueDate() { return dueDate; }
    public void setDueDate(LocalDate dueDate) { this.dueDate = dueDate; }

    public Instant getIssuedAt() { return issuedAt; }
    public void setIssuedAt(Instant issuedAt) { this.issuedAt = issuedAt; }

    public Instant getPaidAt() { return paidAt; }
    public void setPaidAt(Instant paidAt) { this.paidAt = paidAt; }

    public Instant getCommittedAt() { return committedAt; }
    public void setCommittedAt(Instant committedAt) { this.committedAt = committedAt; }

    public BigDecimal getOpeningBalance() { return openingBalance; }
    public void setOpeningBalance(BigDecimal openingBalance) { this.openingBalance = openingBalance; }

    public BigDecimal getClosingBalance() { return closingBalance; }
    public void setClosingBalance(BigDecimal closingBalance) { this.closingBalance = closingBalance; }

    public BigDecimal getPaymentsInWindow() { return paymentsInWindow; }
    public void setPaymentsInWindow(BigDecimal paymentsInWindow) { this.paymentsInWindow = paymentsInWindow; }

    public BigDecimal getAdjustmentsInWindow() { return adjustmentsInWindow; }
    public void setAdjustmentsInWindow(BigDecimal adjustmentsInWindow) { this.adjustmentsInWindow = adjustmentsInWindow; }

    public UUID getPriorInvoiceId() { return priorInvoiceId; }
    public void setPriorInvoiceId(UUID priorInvoiceId) { this.priorInvoiceId = priorInvoiceId; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    public UUID getCreatedBy() { return createdBy; }
    public void setCreatedBy(UUID createdBy) { this.createdBy = createdBy; }
}
