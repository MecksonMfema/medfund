-- Phase 15 §5 (I19): cohort_loss_component_history append-only journal +
-- cohort_loss_component_current matview for opening-balance lookup.
--
-- Writes come from (a) §15 onerous-test compute path (AUTO) and (b) manual
-- tenant-admin adjustment via the "Loss component" tab. Every write emits
-- an AuditEvent per Rule 8. Matview refresh happens at the end of each
-- report run (§17 controller triggers REFRESH CONCURRENTLY).
--
-- Numbering deviation: plan cites V144 but concurrent Phase 1-4 migrations
-- consumed V151-V156 already, so V157 is the next free slot in the shared
-- Flyway history (per bug_public_flyway_history_load_bearing).

CREATE TABLE IF NOT EXISTS cohort_loss_component_history (
    id            UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    cohort_id     UUID         NOT NULL REFERENCES ifrs17_cohort(id) ON DELETE CASCADE,
    effective_at  TIMESTAMPTZ  NOT NULL,
    movement_type VARCHAR(50)  NOT NULL
        CHECK (movement_type IN (
            'INITIAL_RECOGNITION',
            'RELEASE',
            'REVERSAL',
            'RECLASSIFICATION_TO_NON_ONEROUS')),
    amount        NUMERIC(18,2) NOT NULL,
    currency      VARCHAR(3)   NOT NULL,
    source_run_id UUID,
    reason_note   TEXT,
    actor_id      UUID,
    actor_email   VARCHAR(200),
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS ix_cohort_loss_component_history_lookup
    ON cohort_loss_component_history (cohort_id, effective_at DESC);

-- Matview: current balance per (portfolio_id, cohort_id, currency).
-- RELEASE / REVERSAL / RECLASSIFICATION_TO_NON_ONEROUS subtract; INITIAL_RECOGNITION adds.
CREATE MATERIALIZED VIEW IF NOT EXISTS cohort_loss_component_current AS
SELECT
    c.portfolio_id,
    h.cohort_id,
    h.currency,
    COALESCE(SUM(
        CASE h.movement_type
            WHEN 'INITIAL_RECOGNITION' THEN h.amount
            WHEN 'RELEASE' THEN -h.amount
            WHEN 'REVERSAL' THEN -h.amount
            WHEN 'RECLASSIFICATION_TO_NON_ONEROUS' THEN -h.amount
            ELSE 0
        END
    ), 0) AS balance
FROM cohort_loss_component_history h
JOIN ifrs17_cohort c ON c.id = h.cohort_id
GROUP BY c.portfolio_id, h.cohort_id, h.currency;

-- Unique index is a prerequisite for REFRESH MATERIALIZED VIEW CONCURRENTLY.
CREATE UNIQUE INDEX IF NOT EXISTS ux_cohort_loss_component_current
    ON cohort_loss_component_current (cohort_id, currency);
