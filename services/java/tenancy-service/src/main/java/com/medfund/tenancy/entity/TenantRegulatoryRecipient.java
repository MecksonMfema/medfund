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
 * R2DBC row for {@code public.tenant_regulatory_recipient} — the per-tenant
 * email recipients the notification-service Go dispatcher fans out to on
 * {@code medfund.regulatory.due-date-approaching} (Phase 16 §0 REG20).
 */
@Getter
@Setter
@NoArgsConstructor
@Table(schema = "public", value = "tenant_regulatory_recipient")
public class TenantRegulatoryRecipient {

    @Id
    private UUID id;

    @Column("tenant_id")
    private UUID tenantId;

    @Column("email")
    private String email;

    @Column("display_name")
    private String displayName;

    /**
     * Subset of {@code {DUE_DATE_7D, DUE_DATE_1D, DUE_DATE_0D, DUE_DATE_OVERDUE}}.
     * Postgres {@code VARCHAR[]} maps to a Java {@code String[]} via R2DBC.
     */
    @Column("subscribed_event_tiers")
    private String[] subscribedEventTiers;

    @Column("is_active")
    private Boolean isActive;

    @Column("created_at")
    private OffsetDateTime createdAt;

    @Column("updated_at")
    private OffsetDateTime updatedAt;

    @Column("actor_id")
    private UUID actorId;

    @Column("actor_email")
    private String actorEmail;
}
