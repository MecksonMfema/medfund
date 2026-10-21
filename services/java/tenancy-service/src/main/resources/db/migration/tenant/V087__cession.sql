-- =====================================================================
-- V087: Cession (Phase 10 §A/§B)
-- =====================================================================
-- One row per ceded loss or premium. cession_type + source discriminators
-- fold four flows (auto-loss, auto-premium, facultative-loss, facultative-
-- premium) into a single table with one bordereau SQL shape.
--
-- Per-source status subsets are enforced by two CHECK constraints so a
-- rogue AUTOMATIC row cannot appear in DRAFT (the facultative workflow
-- state).
--
-- ux_cession_source_event is the idempotency guard for consumers +
-- backfill — a Kafka redelivery or a treaty re-activation bounces off
-- this index rather than duplicating rows.

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
