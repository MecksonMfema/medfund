-- Test-migration layer for Phase 10 reinsurance IT harness. Mirrors the
-- production tenant migrations V081..V090 (in tenancy-service) so
-- finance-service reinsurance repositories can be exercised by
-- ReinsuranceCrudIT + BordereauReportControllerIT without the full
-- tenancy-service migration stack.
--
-- Constraint definitions are copy-parity with V081..V090; keep this file
-- in step whenever a corresponding production migration is added under
-- services/java/tenancy-service/src/main/resources/db/migration/tenant/.

CREATE TABLE reinsurer (
    id                UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    name              VARCHAR(200) NOT NULL,
    contact_email     VARCHAR(255),
    contact_address   TEXT,
    jurisdiction_code VARCHAR(20),
    home_currency     CHAR(3),
    credit_rating     VARCHAR(20),
    is_active         BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    actor_id          UUID,
    actor_email       VARCHAR(255)
);
CREATE UNIQUE INDEX ux_reinsurer_name_active ON reinsurer (name) WHERE is_active = TRUE;

CREATE TABLE treaty (
    id                        UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    treaty_ref                VARCHAR(120)  NOT NULL,
    treaty_type               VARCHAR(20)   NOT NULL,
    declared_currency         CHAR(3)       NOT NULL,
    inception_date            DATE          NOT NULL,
    expiry_date               DATE          NOT NULL,
    status                    VARCHAR(20)   NOT NULL DEFAULT 'DRAFT',
    renewed_from_treaty_id    UUID          REFERENCES treaty(id) ON DELETE RESTRICT,
    aggregate_limit           DECIMAL(19,4),
    aggregate_limit_currency  CHAR(3),
    expected_annual_premium   DECIMAL(19,4),
    producer_ref              VARCHAR(120),
    activated_at              TIMESTAMPTZ,
    created_at                TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at                TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    actor_id                  UUID,
    actor_email               VARCHAR(255),
    CONSTRAINT treaty_type_ck   CHECK (treaty_type IN ('QUOTA_SHARE','SURPLUS_SHARE','EXCESS_OF_LOSS','STOP_LOSS')),
    CONSTRAINT treaty_status_ck CHECK (status IN ('DRAFT','ACTIVE','EXPIRED','RENEWED','LAPSED','COMMUTED')),
    CONSTRAINT treaty_period_ck CHECK (expiry_date > inception_date)
);
CREATE UNIQUE INDEX ux_treaty_ref ON treaty (treaty_ref);

CREATE TABLE treaty_layer (
    id                    UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    treaty_id             UUID          NOT NULL REFERENCES treaty(id) ON DELETE CASCADE,
    layer_order           INT           NOT NULL,
    retention             DECIMAL(19,4) NOT NULL,
    layer_limit           DECIMAL(19,4) NOT NULL,
    layer_currency        CHAR(3)       NOT NULL,
    rate                  DECIMAL(9,6)  NOT NULL,
    reinstatement_count   INT,
    created_at            TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    CONSTRAINT treaty_layer_ordered_uq UNIQUE (treaty_id, layer_order),
    CONSTRAINT treaty_layer_amounts_ck CHECK (retention >= 0 AND layer_limit > 0 AND rate >= 0)
);

CREATE TABLE treaty_participant (
    treaty_id     UUID          NOT NULL REFERENCES treaty(id)     ON DELETE CASCADE,
    reinsurer_id  UUID          NOT NULL REFERENCES reinsurer(id)  ON DELETE RESTRICT,
    share_pct     DECIMAL(7,4)  NOT NULL,
    share_role    VARCHAR(20)   NOT NULL DEFAULT 'FOLLOWING',
    created_at    TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    PRIMARY KEY (treaty_id, reinsurer_id),
    CONSTRAINT treaty_participant_role_ck  CHECK (share_role IN ('LEADER','FOLLOWING')),
    CONSTRAINT treaty_participant_share_ck CHECK (share_pct > 0 AND share_pct <= 100)
);

CREATE TABLE treaty_applicable_line (
    treaty_id      UUID        NOT NULL REFERENCES treaty(id) ON DELETE CASCADE,
    insurance_line VARCHAR(20) NOT NULL,
    PRIMARY KEY (treaty_id, insurance_line),
    CONSTRAINT treaty_applicable_line_ck CHECK (insurance_line IN
        ('HEALTH','LIFE','FUNERAL','GROUP','TRAVEL','DISABILITY','VEHICLE','PROPERTY'))
);

CREATE TABLE cession_rule (
    id                  UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    treaty_id           UUID        NOT NULL REFERENCES treaty(id) ON DELETE CASCADE,
    rule_definition_id  UUID        NOT NULL,
    enabled             BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    actor_id            UUID,
    actor_email         VARCHAR(255),
    CONSTRAINT cession_rule_treaty_rule_uq UNIQUE (treaty_id, rule_definition_id)
);

-- Re-apply the schema-wide grant (V006's ran before these tables existed;
-- the test harness's public_role needs write access to every fresh table).
GRANT SELECT, INSERT, UPDATE, DELETE, REFERENCES, TRIGGER
    ON ALL TABLES IN SCHEMA public TO public_role;
