-- =====================================================================
-- V086: Cession rule → treaty link (Phase 10 §A)
-- =====================================================================
-- A join table between a treaty and the rules-engine RuleDefinition rows
-- (rules-engine business_rules table lives in its own schema — the FK is
-- enforced at the app layer in CessionRuleService, matching the pattern
-- used elsewhere when finance/tenancy services reference rules).

CREATE TABLE cession_rule (
    id                  UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    treaty_id           UUID        NOT NULL REFERENCES treaty(id) ON DELETE CASCADE,
    -- FK into rules-engine business_rules(id), enforced app-layer.
    rule_definition_id  UUID        NOT NULL,
    enabled             BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    actor_id            UUID,
    actor_email         VARCHAR(255),
    CONSTRAINT cession_rule_treaty_rule_uq UNIQUE (treaty_id, rule_definition_id)
);
