-- ── Phase 13 §A per L2: per-policy status-transition audit trail ─────
-- Written by PolicyStatusTransitionService (user-service, §A Phase 3) in
-- the same reactive transaction as the policy entity update. Reports read
-- directly from this table (§C). Polymorphic policy_id — no FK, one row
-- namespace across all six annual-bind policy tables.
--
-- Backfill per L7: one seed row per existing policy at
-- (from=NULL, to=<current status>, effective_at=bound_at OR created_at).
-- Six-way UNION-ALL across the annual-bind policy tables; CONTRIBUTION
-- excluded — HEALTH has no policy entity.

CREATE TABLE IF NOT EXISTS policy_status_history (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    policy_id        UUID NOT NULL,
    policy_source    VARCHAR(30) NOT NULL,
    from_status      VARCHAR(30),
    to_status        VARCHAR(30) NOT NULL,
    effective_at     TIMESTAMPTZ NOT NULL,
    actor_id         UUID,
    actor_email      VARCHAR(255),
    reason_code      VARCHAR(50),
    reason_note      TEXT,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_policy_status_history_source CHECK (policy_source IN (
        'LIFE_POLICY', 'FUNERAL_POLICY', 'DISABILITY_POLICY',
        'TRAVEL_POLICY', 'VEHICLE_POLICY', 'PROPERTY_POLICY'
    ))
);

CREATE INDEX IF NOT EXISTS ix_policy_status_history_policy_effective
    ON policy_status_history (policy_id, effective_at DESC);

CREATE INDEX IF NOT EXISTS ix_policy_status_history_source_status_effective
    ON policy_status_history (policy_source, to_status, effective_at DESC);

COMMENT ON TABLE policy_status_history IS
    'Phase 13 §A per L2: per-policy status-transition audit trail. Every write goes through
     PolicyStatusTransitionService in user-service; reports read directly.';

INSERT INTO policy_status_history (policy_id, policy_source, from_status, to_status, effective_at, actor_email, reason_code)
SELECT id, 'LIFE_POLICY',       NULL, status, COALESCE(bound_at, created_at), 'migration', 'initial_backfill' FROM life_policies
UNION ALL
SELECT id, 'FUNERAL_POLICY',    NULL, status, COALESCE(bound_at, created_at), 'migration', 'initial_backfill' FROM funeral_policies
UNION ALL
SELECT id, 'DISABILITY_POLICY', NULL, status, COALESCE(bound_at, created_at), 'migration', 'initial_backfill' FROM disability_policies
UNION ALL
SELECT id, 'TRAVEL_POLICY',     NULL, status, COALESCE(bound_at, created_at), 'migration', 'initial_backfill' FROM travel_policies
UNION ALL
SELECT id, 'VEHICLE_POLICY',    NULL, status, COALESCE(bound_at, created_at), 'migration', 'initial_backfill' FROM vehicles
UNION ALL
SELECT id, 'PROPERTY_POLICY',   NULL, status, COALESCE(bound_at, created_at), 'migration', 'initial_backfill' FROM properties;
