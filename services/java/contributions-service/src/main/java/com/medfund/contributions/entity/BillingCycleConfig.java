package com.medfund.contributions.entity;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@Table("billing_cycle_config")
public class BillingCycleConfig {

    public static final UUID SINGLETON_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Id
    private UUID id;

    /** MONTHLY | QUARTERLY | ANNUAL | CUSTOM. */
    private String frequency;

    @Column("day_of_month")
    private Short dayOfMonth;

    @Column("auto_generate")
    private Boolean autoGenerate;

    /** Minimum hours between two successful billing commits. */
    @Column("commit_cooldown_hours")
    private Short commitCooldownHours;

    @Column("last_committed_at")
    private Instant lastCommittedAt;

    @Column("updated_at")
    private Instant updatedAt;

    @Column("updated_by")
    private UUID updatedBy;
}
