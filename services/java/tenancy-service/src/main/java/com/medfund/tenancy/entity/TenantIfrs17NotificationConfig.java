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
 * R2DBC row for {@code public.tenant_ifrs17_notification_config} — per-tenant
 * recipient list for IFRS 17 material events (Phase 15 §19 / I30). Each row
 * pairs one event type with one recipient + delivery channel + throttle.
 */
@Getter
@Setter
@NoArgsConstructor
@Table(schema = "public", value = "tenant_ifrs17_notification_config")
public class TenantIfrs17NotificationConfig {

    @Id
    private UUID id;

    @Column("tenant_id")
    private UUID tenantId;

    @Column("event_type")
    private String eventType;

    @Column("delivery_method")
    private String deliveryMethod;

    @Column("recipient")
    private String recipient;

    @Column("throttle_minutes")
    private Integer throttleMinutes;

    @Column("is_active")
    private Boolean isActive;

    @Column("created_at")
    private OffsetDateTime createdAt;

    @Column("updated_at")
    private OffsetDateTime updatedAt;

    @Column("updated_by")
    private UUID updatedBy;

    @Column("updated_by_email")
    private String updatedByEmail;
}
