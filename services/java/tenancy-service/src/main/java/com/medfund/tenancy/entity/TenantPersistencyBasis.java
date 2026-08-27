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
@Table(schema = "public", value = "tenant_persistency_basis")
public class TenantPersistencyBasis {

    @Id
    private UUID id;

    @Column("tenant_id")
    private UUID tenantId;

    @Column("insurance_line")
    private String insuranceLine;

    @Column("cohort_months")
    private Integer cohortMonths;

    @Column("expected_retention_pct")
    private BigDecimal expectedRetentionPct;

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
