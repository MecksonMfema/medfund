package com.medfund.user.entity;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Phase 15 §8 (I4) — VFA underlying-item catalog. NAV history, policy unit
 * ledger and variable fee schedule hang off this row. §16 VFA compute reads
 * the fund + its children for direct-participation contracts under IFRS 17.71.
 *
 * <p>Leave {@code id} null on insert per {@code bug_r2dbc_pre_populated_id_update_mode}.
 */
@Table("unit_linked_fund")
public class UnitLinkedFund {

    @Id
    private UUID id;

    @Column("name")
    private String name;

    @Column("currency")
    private String currency;

    @Column("base_asset_class")
    private String baseAssetClass;

    @Column("is_active")
    private Boolean isActive;

    @Column("created_at")
    private Instant createdAt;

    @Column("updated_at")
    private Instant updatedAt;

    @Column("actor_id")
    private UUID actorId;

    @Column("actor_email")
    private String actorEmail;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public String getBaseAssetClass() { return baseAssetClass; }
    public void setBaseAssetClass(String baseAssetClass) { this.baseAssetClass = baseAssetClass; }
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
}
