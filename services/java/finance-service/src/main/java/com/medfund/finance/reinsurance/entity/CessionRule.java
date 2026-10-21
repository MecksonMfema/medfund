package com.medfund.finance.reinsurance.entity;

import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Link row (V086) binding a rules-engine {@code RuleDefinition} to a
 * treaty. When the reinsurance consumer processes an adjudicated claim,
 * it loads the rules referenced by every ACTIVE treaty and focuses the
 * REINSURANCE agenda group so only cession rules fire.
 *
 * <p>{@code ruleDefinitionId} is a soft FK into the rules-engine's own
 * {@code business_rules} table — no cross-service DB FK; the app layer
 * enforces integrity on writes.
 */
@Getter
@Setter
@Table("cession_rule")
public class CessionRule {

    @Id
    private UUID id;

    @Column("treaty_id")
    private UUID treatyId;

    @Column("rule_definition_id")
    private UUID ruleDefinitionId;

    @Column("enabled")
    private Boolean enabled = true;

    @Column("created_at")
    private OffsetDateTime createdAt;

    @Column("updated_at")
    private OffsetDateTime updatedAt;

    @Column("actor_id")
    private UUID actorId;

    @Column("actor_email")
    private String actorEmail;
}
