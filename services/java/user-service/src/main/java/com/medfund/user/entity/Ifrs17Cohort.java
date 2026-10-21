package com.medfund.user.entity;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * IFRS 17 cohort dimension — the second aggregation level under a portfolio.
 * Grouped by cohort_year + cohort_type per IFRS 17 aggregation rules; Phase 15
 * consumes these for CSM measurement + onerous-contract detection.
 */
@Table("ifrs17_cohort")
public class Ifrs17Cohort {

    @Id
    private UUID id;

    @Column("portfolio_id")
    private UUID portfolioId;

    @Column("cohort_year")
    private Integer cohortYear;

    @Column("cohort_type")
    private String cohortType;

    private String name;

    @Column("is_active")
    private Boolean isActive;

    @CreatedDate
    @Column("created_at")
    private Instant createdAt;

    @LastModifiedDate
    @Column("updated_at")
    private Instant updatedAt;

    @Column("actor_id")
    private UUID actorId;

    @Column("actor_email")
    private String actorEmail;

    /**
     * IFRS 17.44 lock-in — JSON serialized shape
     * {@code [{"tenorMonths": 12, "spotRate": 0.075}, ...]} captured on the day
     * the first policy is bound into the cohort. Read back at each report run
     * for CSM interest accretion so the discount rate does not drift with the
     * current curve. Null until first policy issuance (or backfill).
     */
    @Column("locked_in_yield_curve_snapshot")
    private String lockedInYieldCurveSnapshot;

    /**
     * Timestamp of the lock. Also acts as the idempotency guard —
     * {@code WHERE locked_in_at IS NULL} is the only path that mutates the
     * snapshot column, so re-issuances into the same cohort are no-ops.
     */
    @Column("locked_in_at")
    private Instant lockedInAt;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getPortfolioId() { return portfolioId; }
    public void setPortfolioId(UUID portfolioId) { this.portfolioId = portfolioId; }
    public Integer getCohortYear() { return cohortYear; }
    public void setCohortYear(Integer cohortYear) { this.cohortYear = cohortYear; }
    public String getCohortType() { return cohortType; }
    public void setCohortType(String cohortType) { this.cohortType = cohortType; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public Boolean getIsActive() { return isActive; }
    public void setIsActive(Boolean isActive) { this.isActive = isActive; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public UUID getActorId() { return actorId; }
    public void setActorId(UUID actorId) { this.actorId = actorId; }
    public String getActorEmail() { return actorEmail; }
    public void setActorEmail(String actorEmail) { this.actorEmail = actorEmail; }
    public String getLockedInYieldCurveSnapshot() { return lockedInYieldCurveSnapshot; }
    public void setLockedInYieldCurveSnapshot(String lockedInYieldCurveSnapshot) {
        this.lockedInYieldCurveSnapshot = lockedInYieldCurveSnapshot;
    }
    public Instant getLockedInAt() { return lockedInAt; }
    public void setLockedInAt(Instant lockedInAt) { this.lockedInAt = lockedInAt; }
}
