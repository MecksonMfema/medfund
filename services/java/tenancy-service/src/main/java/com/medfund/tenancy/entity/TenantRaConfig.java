package com.medfund.tenancy.entity;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@Table(schema = "public", value = "tenant_ra_config")
public class TenantRaConfig {

    @Id
    private UUID id;

    @Column("tenant_id")
    private UUID tenantId;

    @Column("portfolio_id")
    private UUID portfolioId;

    @Column("methodology")
    private String methodology;

    @Column("coc_rate")
    private BigDecimal cocRate;

    @Column("target_confidence_level")
    private BigDecimal targetConfidenceLevel;

    @Column("source_note")
    private String sourceNote;

    @Column("effective_from")
    private LocalDate effectiveFrom;

    @Column("effective_to")
    private LocalDate effectiveTo;

    @Column("created_at")
    private OffsetDateTime createdAt;

    @Column("updated_at")
    private OffsetDateTime updatedAt;

    @Column("updated_by")
    private UUID updatedBy;

    @Column("updated_by_email")
    private String updatedByEmail;
}
