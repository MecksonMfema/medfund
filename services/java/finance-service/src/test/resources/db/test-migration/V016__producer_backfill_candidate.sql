-- Test-migration layer for Phase 11 §B Phase 10 producer-backfill tests.
-- Mirrors production tenant migrations V098 (producer_backfill_candidate)
-- + V099 Part 2 (treaty.producer_id FK). Applies after V011 (producer) +
-- V007 (treaty) so both FK targets exist.

ALTER TABLE treaty
    ADD COLUMN IF NOT EXISTS producer_id UUID REFERENCES producer(id) ON DELETE RESTRICT;

CREATE TABLE producer_backfill_candidate (
    id                      UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    treaty_id               UUID          NOT NULL REFERENCES treaty(id) ON DELETE CASCADE,
    treaty_producer_ref     VARCHAR(120)  NOT NULL,
    candidate_producer_id   UUID          REFERENCES producer(id) ON DELETE SET NULL,
    confidence_score        NUMERIC(4,3)  NOT NULL,
    match_strategy          VARCHAR(30)   NOT NULL,
    status                  VARCHAR(20)   NOT NULL DEFAULT 'PENDING',
    resolved_at             TIMESTAMPTZ,
    resolved_actor_id       UUID,
    resolved_actor_email    VARCHAR(255),
    created_at              TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    CONSTRAINT pbc_status_ck   CHECK (status IN ('PENDING','ACCEPTED','REJECTED')),
    CONSTRAINT pbc_strategy_ck CHECK (match_strategy IN ('LEVENSHTEIN','ILIKE_SUBSTRING','EXACT_CI'))
);

CREATE UNIQUE INDEX ux_pbc_treaty_candidate ON producer_backfill_candidate
    (treaty_id, candidate_producer_id);
CREATE INDEX ix_pbc_pending ON producer_backfill_candidate (status, created_at)
    WHERE status = 'PENDING';

GRANT SELECT, INSERT, UPDATE, DELETE, REFERENCES, TRIGGER
    ON ALL TABLES IN SCHEMA public TO public_role;
