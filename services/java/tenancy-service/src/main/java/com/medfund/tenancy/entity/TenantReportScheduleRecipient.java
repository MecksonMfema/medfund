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
 * R2DBC row for {@code public.tenant_report_schedule_recipient} — per-schedule
 * recipient list. {@code unsubscribeToken} is unique across the table and is
 * used by the public gateway route to flip {@code isActive=false} without a JWT.
 */
@Getter
@Setter
@NoArgsConstructor
@Table(schema = "public", value = "tenant_report_schedule_recipient")
public class TenantReportScheduleRecipient {

    @Id
    private UUID id;

    @Column("schedule_id")
    private UUID scheduleId;

    private String email;

    @Column("display_name")
    private String displayName;

    @Column("is_active")
    private Boolean isActive;

    @Column("unsubscribe_token")
    private UUID unsubscribeToken;

    @Column("created_at")
    private OffsetDateTime createdAt;

    @Column("updated_at")
    private OffsetDateTime updatedAt;

    @Column("actor_id")
    private UUID actorId;

    @Column("actor_email")
    private String actorEmail;
}
