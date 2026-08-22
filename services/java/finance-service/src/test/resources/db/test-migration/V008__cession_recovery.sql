-- Phase 3 additions. Mirrors production tenant migrations V087 (cession)
-- and V088 (recovery). Written as a separate file — V007 was already
-- applied against test databases when Phase 2 shipped, and per
-- feedback_never_edit_applied_migrations we never mutate an applied file
-- even in the test-migration layer.

CREATE TABLE cession (
    id                  UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    treaty_id           UUID          NOT NULL REFERENCES treaty(id) ON DELETE RESTRICT,
    treaty_layer_id     UUID          REFERENCES treaty_layer(id) ON DELETE RESTRICT,
    cession_type        VARCHAR(20)   NOT NULL,
    source              VARCHAR(20)   NOT NULL,
    status              VARCHAR(20)   NOT NULL,
    source_event_id     UUID          NOT NULL,
    source_event_type   VARCHAR(40)   NOT NULL,
    ceded_amount        DECIMAL(19,4) NOT NULL,
    currency_code       CHAR(3)       NOT NULL,
    basis_amount        DECIMAL(19,4) NOT NULL,
    occurred_at         TIMESTAMPTZ   NOT NULL,
    voided_reason       TEXT,
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    actor_id            UUID,
    actor_email         VARCHAR(255),
    CONSTRAINT cession_type_ck   CHECK (cession_type IN ('LOSS','PREMIUM')),
    CONSTRAINT cession_source_ck CHECK (source IN ('AUTOMATIC','FACULTATIVE')),
    CONSTRAINT cession_status_ck CHECK (status IN ('ACTIVE','DRAFT','APPROVED','CEDED','VOIDED')),
    CONSTRAINT cession_auto_status_ck CHECK (
        source = 'FACULTATIVE' OR status IN ('ACTIVE','VOIDED')
    ),
    CONSTRAINT cession_fac_status_ck CHECK (
        source = 'AUTOMATIC' OR status IN ('DRAFT','APPROVED','CEDED','VOIDED')
    )
);
CREATE UNIQUE INDEX ux_cession_source_event
    ON cession (treaty_id, source_event_id, cession_type);
CREATE INDEX ix_cession_treaty_occurred ON cession (treaty_id, occurred_at);
CREATE INDEX ix_cession_source_event_id ON cession (source_event_id);
CREATE INDEX ix_cession_status_source   ON cession (status, source);

CREATE TABLE recovery (
    id                UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    cession_id        UUID          NOT NULL REFERENCES cession(id) ON DELETE RESTRICT,
    status            VARCHAR(20)   NOT NULL DEFAULT 'EXPECTED',
    expected_amount   DECIMAL(19,4) NOT NULL,
    received_amount   DECIMAL(19,4),
    currency_code     CHAR(3)       NOT NULL,
    invoiced_at       TIMESTAMPTZ,
    received_at       TIMESTAMPTZ,
    write_off_reason  TEXT,
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    actor_id          UUID,
    actor_email       VARCHAR(255),
    CONSTRAINT recovery_status_ck CHECK (status IN ('EXPECTED','INVOICED','RECEIVED','WRITTEN_OFF')),
    CONSTRAINT recovery_cession_uq UNIQUE (cession_id)
);
CREATE INDEX ix_recovery_status ON recovery (status)
    WHERE status IN ('EXPECTED','INVOICED');

-- Extend the schema-wide grant to include the new tables.
GRANT SELECT, INSERT, UPDATE, DELETE, REFERENCES, TRIGGER
    ON ALL TABLES IN SCHEMA public TO public_role;
