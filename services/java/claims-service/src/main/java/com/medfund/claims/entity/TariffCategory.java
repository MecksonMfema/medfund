package com.medfund.claims.entity;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * V063 tenant-configurable tariff category catalogue. Every tariff
 * belongs to exactly one category (mandatory FK), and every scheme
 * benefit declares which categories it covers via
 * {@code benefit_tariff_categories}. Together they replace the
 * V062 {@code tariff_benefit_mappings} table.
 *
 * <p>{@code is_cap_only=true} means tariffs in this category deduct
 * from the scheme's annual cap without touching any per-benefit
 * ledger row — no {@code benefit_tariff_categories} link is required
 * for those to route correctly.
 */
@Table("tariff_categories")
public class TariffCategory {

    @Id
    private UUID id;

    private String code;

    private String label;

    private String description;

    @Column("is_cap_only")
    private Boolean isCapOnly = Boolean.FALSE;

    @Column("is_active")
    private Boolean isActive = Boolean.TRUE;

    @Column("sort_order")
    private Integer sortOrder = 0;

    @CreatedDate
    @Column("created_at")
    private Instant createdAt;

    @LastModifiedDate
    @Column("updated_at")
    private Instant updatedAt;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public Boolean getIsCapOnly() { return isCapOnly; }
    public void setIsCapOnly(Boolean isCapOnly) { this.isCapOnly = isCapOnly; }
    public Boolean getIsActive() { return isActive; }
    public void setIsActive(Boolean isActive) { this.isActive = isActive; }
    public Integer getSortOrder() { return sortOrder; }
    public void setSortOrder(Integer sortOrder) { this.sortOrder = sortOrder; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
