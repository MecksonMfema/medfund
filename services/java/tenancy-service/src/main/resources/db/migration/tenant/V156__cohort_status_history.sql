-- Phase 15 §4 (I11): cohort_status_history — append-only journal of
-- transitions between IFRS 17 cohort_type states. Two write paths:
--   • MANUAL — tenant admin edits cohort_type via Ifrs17CohortController.update
--   • AUTO   — Phase 15 §15 onerous-test compute (posted from ai-service)
-- Every insert emits an AuditEvent; AUTO inserts additionally emit a
-- medfund.ifrs17.material-event (publisher populated in §19).
--
-- Numbering note: plan §4 originally allocated V143, but V139-V142 were
-- consumed by Phase 14 and V151/V155 by Phase 1/§3 renames — bumped forward
-- per the plan's "concurrent PR takes a number, bump forward" rule.

CREATE TABLE IF NOT EXISTS cohort_status_history (
    id                 UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    cohort_id          UUID        NOT NULL REFERENCES ifrs17_cohort(id) ON DELETE CASCADE,
    from_status        VARCHAR(20) NOT NULL,
    to_status          VARCHAR(20) NOT NULL
        CHECK (to_status IN ('ONEROUS', 'NON_ONEROUS', 'UNCERTAIN')),
    transition_reason  VARCHAR(50) NOT NULL
        CHECK (transition_reason IN (
            'AUTO_TEST_FAILED',
            'AUTO_TEST_RECOVERED',
            'MANUAL_OVERRIDE',
            'INITIAL_CLASSIFICATION')),
    transition_source  VARCHAR(10) NOT NULL
        CHECK (transition_source IN ('AUTO', 'MANUAL')),
    source_run_id      UUID,
    effective_at       TIMESTAMPTZ NOT NULL,
    reason_note        TEXT,
    actor_id           UUID,
    actor_email        VARCHAR(200),
    created_at         TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS ix_cohort_status_history_lookup
    ON cohort_status_history (cohort_id, effective_at DESC);
