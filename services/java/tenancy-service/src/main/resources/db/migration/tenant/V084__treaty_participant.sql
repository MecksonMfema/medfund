-- =====================================================================
-- V084: Treaty participants (Phase 10 §A)
-- =====================================================================
-- Composite key on (treaty_id, reinsurer_id). share_pct across all
-- participants must sum to 100 — enforced app-side in
-- TreatyValidationService on the activation transition, not by a
-- deferred DB trigger (activation is the natural gate).

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

CREATE INDEX ix_treaty_participant_reinsurer ON treaty_participant (reinsurer_id);
