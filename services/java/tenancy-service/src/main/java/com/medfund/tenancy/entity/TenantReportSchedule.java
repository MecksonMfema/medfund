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
 * R2DBC row for {@code public.tenant_report_schedule} — the per-tenant
 * per-key schedule row that the Phase 17 finance-service
 * {@code ScheduledReportProbe} iterates each hour. Persisted via V176.
 */
@Getter
@Setter
@NoArgsConstructor
@Table(schema = "public", value = "tenant_report_schedule")
public class TenantReportSchedule {

    @Id
    private UUID id;

    @Column("tenant_id")
    private UUID tenantId;

    @Column("report_key")
    private String reportKey;

    private Boolean enabled;

    private String cadence;

    @Column("hour_of_day")
    private Integer hourOfDay;

    @Column("day_of_week")
    private Integer dayOfWeek;

    @Column("day_of_month")
    private Integer dayOfMonth;

    @Column("reporting_currency")
    private String reportingCurrency;

    @Column("last_fired_at")
    private OffsetDateTime lastFiredAt;

    @Column("last_status")
    private String lastStatus;

    @Column("created_at")
    private OffsetDateTime createdAt;

    @Column("created_by_actor_id")
    private UUID createdByActorId;

    @Column("created_by_actor_email")
    private String createdByActorEmail;

    @Column("updated_at")
    private OffsetDateTime updatedAt;

    @Column("updated_by_actor_id")
    private UUID updatedByActorId;

    @Column("updated_by_actor_email")
    private String updatedByActorEmail;
}
