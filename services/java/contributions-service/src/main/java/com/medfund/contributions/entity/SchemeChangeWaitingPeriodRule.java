package com.medfund.contributions.entity;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Waiting-period rule applied when a member moves between schemes.
 * Persisted by V011. Optional {@code benefitType} means "applies to all
 * benefits in this change direction".
 */
@Getter
@Setter
@NoArgsConstructor
@Table("scheme_change_waiting_period_rules")
public class SchemeChangeWaitingPeriodRule {

    @Id
    private UUID id;

    /** UPGRADE | DOWNGRADE */
    @Column("change_type")
    private String changeType;

    @Column("benefit_type")
    private String benefitType;

    @Column("waiting_days")
    private Integer waitingDays;

    private String description;

    @Column("is_active")
    private Boolean isActive;

    @Column("created_at")
    private Instant createdAt;

    @Column("updated_at")
    private Instant updatedAt;
}
