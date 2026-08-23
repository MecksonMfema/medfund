-- =====================================================================
-- V098: Producer backfill candidate staging (Phase 11 §B P11 / Phase 10)
-- =====================================================================
-- Fuzzy-match candidates linking legacy treaty.producer_ref VARCHAR text
-- to producer.id. High-confidence matches auto-accept; the rest queue for
-- tenant-admin review. Idempotency via ux_pbc_treaty_candidate — a rerun
-- of ProducerBackfillJob never writes duplicate candidates.
-- =====================================================================

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
