package com.medfund.tenancy.entity;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Row in {@code public.tenant_auto_lapse_config} (V133). One per tenant.
 * Absence of a row (or {@code enabled = FALSE}) means the auto-lapse
 * chain is off: the arrears sweep will not publish
 * {@code arrears-threshold-breached} and the user-service consumer will
 * short-circuit even if the event slips through from a legacy publish.
 */
@Getter
@Setter
@NoArgsConstructor
@Table(schema = "public", value = "tenant_auto_lapse_config")
public class TenantAutoLapseConfig {

    @Id
    private UUID id;

    @Column("tenant_id")
    private UUID tenantId;

    @Column("enabled")
    private boolean enabled;

    /** Months of unbroken arrears required before the pipeline arms. Nullable — no configured cap. */
    @Column("arrears_threshold_months")
    private Integer arrearsThresholdMonths;

    /** Days between breach and the actual LAPSED transition. Nullable — 0 = immediate. */
    @Column("grace_window_days")
    private Integer graceWindowDays;

    @Column("created_at")
    private OffsetDateTime createdAt;

    @Column("updated_at")
    private OffsetDateTime updatedAt;

    @Column("actor_id")
    private UUID actorId;

    @Column("actor_email")
    private String actorEmail;
}
