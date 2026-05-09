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
 * Initial-enrolment waiting period for a scheme. Keyed on
 * (scheme_id, condition_type) — e.g. "30 days for chronic medication on
 * the Gold scheme". Created in V001 baseline; this entity wraps it for
 * the operational waiting-periods CRUD page.
 */
@Getter
@Setter
@NoArgsConstructor
@Table("waiting_period_rules")
public class WaitingPeriodRule {

    @Id
    private UUID id;

    @Column("scheme_id")
    private UUID schemeId;

    @Column("condition_type")
    private String conditionType;

    @Column("waiting_days")
    private Integer waitingDays;

    private String description;

    @Column("created_at")
    private Instant createdAt;
}
