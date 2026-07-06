package com.medfund.contributions.entity;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Table("scheme_changes")
public class SchemeChange {

    @Id
    private UUID id;

    @Column("member_id")
    private UUID memberId;

    @Column("from_scheme_id")
    private UUID fromSchemeId;

    @Column("to_scheme_id")
    private UUID toSchemeId;

    private String status = "PENDING";

    @Column("requested_date")
    private LocalDate requestedDate;

    @Column("effective_date")
    private LocalDate effectiveDate;

    private String reason;

    @Column("rejection_reason")
    private String rejectionReason;

    @Column("approved_by")
    private UUID approvedBy;

    @Column("approved_at")
    private Instant approvedAt;

    @CreatedDate
    @Column("created_at")
    private Instant createdAt;

    @LastModifiedDate
    @Column("updated_at")
    private Instant updatedAt;

    @Column("created_by")
    private UUID createdBy;

    /**
     * V048 classification for adjustment routing:
     * <ul>
     *   <li>UPGRADE — new scheme's absolute benefit sum &gt; old scheme's
     *       → SchemeChangedConsumer posts SCHEME_UPGRADE_ARREARS.</li>
     *   <li>DOWNGRADE — new sum &lt; old sum → SCHEME_DOWNGRADE_REBATE.</li>
     *   <li>CURRENCY_CHANGE — schemes carry different currency codes
     *       → CURRENCY_CHANGE_ADJUSTMENT with FX-aware delta.</li>
     *   <li>CROSS_GRADE — same currency, comparable benefits → no
     *       automatic ledger post (default for historical rows).</li>
     * </ul>
     */
    @Column("change_kind")
    private String changeKind = "CROSS_GRADE";

    // Getters and setters
    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UUID getMemberId() { return memberId; }
    public void setMemberId(UUID memberId) { this.memberId = memberId; }

    public UUID getFromSchemeId() { return fromSchemeId; }
    public void setFromSchemeId(UUID fromSchemeId) { this.fromSchemeId = fromSchemeId; }

    public UUID getToSchemeId() { return toSchemeId; }
    public void setToSchemeId(UUID toSchemeId) { this.toSchemeId = toSchemeId; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public LocalDate getRequestedDate() { return requestedDate; }
    public void setRequestedDate(LocalDate requestedDate) { this.requestedDate = requestedDate; }

    public LocalDate getEffectiveDate() { return effectiveDate; }
    public void setEffectiveDate(LocalDate effectiveDate) { this.effectiveDate = effectiveDate; }

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }

    public String getRejectionReason() { return rejectionReason; }
    public void setRejectionReason(String rejectionReason) { this.rejectionReason = rejectionReason; }

    public UUID getApprovedBy() { return approvedBy; }
    public void setApprovedBy(UUID approvedBy) { this.approvedBy = approvedBy; }

    public Instant getApprovedAt() { return approvedAt; }
    public void setApprovedAt(Instant approvedAt) { this.approvedAt = approvedAt; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    public UUID getCreatedBy() { return createdBy; }
    public void setCreatedBy(UUID createdBy) { this.createdBy = createdBy; }

    public String getChangeKind() { return changeKind; }
    public void setChangeKind(String changeKind) { this.changeKind = changeKind; }
}
