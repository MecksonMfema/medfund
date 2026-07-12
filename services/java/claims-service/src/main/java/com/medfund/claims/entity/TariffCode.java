package com.medfund.claims.entity;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Table("tariff_codes")
public class TariffCode {

    @Id
    private UUID id;

    @Column("schedule_id")
    private UUID scheduleId;

    private String code;

    private String description;

    private String category;

    /** V063 — mandatory FK to the tariff_categories catalogue. The
     *  legacy {@link #category} string stays alongside as a
     *  denormalised label so downstream reporting doesn't break, but
     *  the resolver reads only category_id. */
    @Column("category_id")
    private UUID categoryId;

    @Column("unit_price")
    private BigDecimal unitPrice;

    @Column("currency_code")
    private String currencyCode;

    @Column("requires_pre_auth")
    private Boolean requiresPreAuth;

    @CreatedDate
    @Column("created_at")
    private Instant createdAt;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UUID getScheduleId() { return scheduleId; }
    public void setScheduleId(UUID scheduleId) { this.scheduleId = scheduleId; }

    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }

    public UUID getCategoryId() { return categoryId; }
    public void setCategoryId(UUID categoryId) { this.categoryId = categoryId; }

    public BigDecimal getUnitPrice() { return unitPrice; }
    public void setUnitPrice(BigDecimal unitPrice) { this.unitPrice = unitPrice; }

    public String getCurrencyCode() { return currencyCode; }
    public void setCurrencyCode(String currencyCode) { this.currencyCode = currencyCode; }

    public Boolean getRequiresPreAuth() { return requiresPreAuth; }
    public void setRequiresPreAuth(Boolean requiresPreAuth) { this.requiresPreAuth = requiresPreAuth; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
