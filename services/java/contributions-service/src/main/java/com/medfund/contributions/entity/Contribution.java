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

@Table("contributions")
public class Contribution {

    @Id
    private UUID id;

    @Column("member_id")
    private UUID memberId;

    @Column("group_id")
    private UUID groupId;

    @Column("scheme_id")
    private UUID schemeId;

    private BigDecimal amount;

    @Column("currency_code")
    private String currencyCode;

    @Column("period_start")
    private LocalDate periodStart;

    @Column("period_end")
    private LocalDate periodEnd;

    private String status = "pending";

    @Column("payment_method")
    private String paymentMethod;

    @Column("payment_reference")
    private String paymentReference;

    @Column("paid_at")
    private Instant paidAt;

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

    /** Back-link to the invoice that aggregated this contribution row.
     *  NULL during the brief preview/commit window before invoices are
     *  generated, or for legacy rows from before V020. */
    @Column("invoice_id")
    private UUID invoiceId;

    /**
     * Set only when this row is the dependant's line. The parent member's
     * own line has {@code dependantId == null}. {@link #memberId} is always
     * set (it carries the parent member used for invoice routing) so the
     * pairing (memberId, dependantId) tells the reader which person the
     * line is for.
     */
    @Column("dependant_id")
    private UUID dependantId;

    /**
     * Age bucket used to price this row at run time. Frozen for the life
     * of the row — never updated even when the person's canonical bucket
     * later changes — so historical invoices stay reproducible.
     */
    @Column("age_group_id")
    private UUID ageGroupId;

    // Getters and setters
    public UUID getInvoiceId() { return invoiceId; }
    public void setInvoiceId(UUID invoiceId) { this.invoiceId = invoiceId; }

    public UUID getDependantId() { return dependantId; }
    public void setDependantId(UUID dependantId) { this.dependantId = dependantId; }

    public UUID getAgeGroupId() { return ageGroupId; }
    public void setAgeGroupId(UUID ageGroupId) { this.ageGroupId = ageGroupId; }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UUID getMemberId() { return memberId; }
    public void setMemberId(UUID memberId) { this.memberId = memberId; }

    public UUID getGroupId() { return groupId; }
    public void setGroupId(UUID groupId) { this.groupId = groupId; }

    public UUID getSchemeId() { return schemeId; }
    public void setSchemeId(UUID schemeId) { this.schemeId = schemeId; }

    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }

    public String getCurrencyCode() { return currencyCode; }
    public void setCurrencyCode(String currencyCode) { this.currencyCode = currencyCode; }

    public LocalDate getPeriodStart() { return periodStart; }
    public void setPeriodStart(LocalDate periodStart) { this.periodStart = periodStart; }

    public LocalDate getPeriodEnd() { return periodEnd; }
    public void setPeriodEnd(LocalDate periodEnd) { this.periodEnd = periodEnd; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getPaymentMethod() { return paymentMethod; }
    public void setPaymentMethod(String paymentMethod) { this.paymentMethod = paymentMethod; }

    public String getPaymentReference() { return paymentReference; }
    public void setPaymentReference(String paymentReference) { this.paymentReference = paymentReference; }

    public Instant getPaidAt() { return paidAt; }
    public void setPaidAt(Instant paidAt) { this.paidAt = paidAt; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    public UUID getCreatedBy() { return createdBy; }
    public void setCreatedBy(UUID createdBy) { this.createdBy = createdBy; }

    public UUID getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(UUID updatedBy) { this.updatedBy = updatedBy; }
}
