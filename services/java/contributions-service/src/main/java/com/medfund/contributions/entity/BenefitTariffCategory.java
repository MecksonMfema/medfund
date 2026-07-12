package com.medfund.contributions.entity;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * V063 join row linking one scheme_benefit to one tariff_category.
 * Read + write from {@link com.medfund.contributions.repository.BenefitTariffCategoryRepository}.
 */
@Table("benefit_tariff_categories")
public class BenefitTariffCategory {

    @Id
    private UUID id;

    @Column("scheme_benefit_id")
    private UUID schemeBenefitId;

    @Column("tariff_category_id")
    private UUID tariffCategoryId;

    @CreatedDate
    @Column("created_at")
    private Instant createdAt;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getSchemeBenefitId() { return schemeBenefitId; }
    public void setSchemeBenefitId(UUID schemeBenefitId) { this.schemeBenefitId = schemeBenefitId; }
    public UUID getTariffCategoryId() { return tariffCategoryId; }
    public void setTariffCategoryId(UUID tariffCategoryId) { this.tariffCategoryId = tariffCategoryId; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
