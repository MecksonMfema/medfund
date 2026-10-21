package com.medfund.tenancy.entity;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Row in {@code public.tenant_endorsement_config} (V134). One per tenant.
 * Absence of a row (or {@code enabled = FALSE}) means the four-eyes
 * endorsement gate is off — {@code PolicyEndorsementService.createDraft}
 * auto-commits every endorsement and publishes
 * {@code medfund.user.policy-endorsed} inline.
 */
@Getter
@Setter
@NoArgsConstructor
@Table(schema = "public", value = "tenant_endorsement_config")
public class TenantEndorsementConfig {

    @Id
    private UUID id;

    @Column("tenant_id")
    private UUID tenantId;

    @Column("enabled")
    private boolean enabled;

    /** Above this amount, an endorsement parks at DRAFT and needs a second-actor commit. Nullable = no threshold. */
    @Column("four_eyes_threshold_amount")
    private BigDecimal fourEyesThresholdAmount;

    /** ISO currency code the threshold is expressed in; paired with the amount (CHECK constraint). */
    @Column("threshold_currency")
    private String thresholdCurrency;

    @Column("created_at")
    private OffsetDateTime createdAt;

    @Column("updated_at")
    private OffsetDateTime updatedAt;

    @Column("actor_id")
    private UUID actorId;

    @Column("actor_email")
    private String actorEmail;
}
