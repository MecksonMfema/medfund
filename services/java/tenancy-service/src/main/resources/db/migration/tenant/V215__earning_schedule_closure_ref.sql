-- ── Phase 13 §B per L6 + grill note 5 ────────────────────────────────
-- Idempotency / provenance columns for policy-status-triggered closure
-- writes on earning_schedule. Populated by
-- {@code PolicyStatusChangedConsumer} in contributions-service:
--
--   • LAPSED / TERMINATED  → is_closure=TRUE, earned_at_period_end=0,
--                            closure_ref=<event uuid>
--   • SUSPENDED            → is_closure=TRUE, earned stays NULL,
--                            closure_ref=<event uuid>
--   • ACTIVE (unwind)      → is_closure=FALSE, closure_ref=NULL,
--                            earned re-opened
--
-- closure_ref is *not* a UNIQUE column — a single close event fans out
-- across N future periods and every affected row carries the same ref
-- for traceability. Idempotency is enforced application-side by a
-- pre-write COUNT lookup on closure_ref (see
-- EarningScheduleClosureService.closeOutForPolicyClosure). The
-- non-unique btree index below covers that lookup.
--
-- is_closure is separate from is_endorsement (Phase 12 §C) — an
-- endorsement row can also be a closure row (both flags TRUE) if a
-- lapse fires after an endorsement rewrite.

ALTER TABLE earning_schedule
    ADD COLUMN IF NOT EXISTS closure_ref UUID;

ALTER TABLE earning_schedule
    ADD COLUMN IF NOT EXISTS is_closure BOOLEAN NOT NULL DEFAULT FALSE;

CREATE INDEX IF NOT EXISTS ix_earning_schedule_closure_ref
    ON earning_schedule (closure_ref) WHERE closure_ref IS NOT NULL;

CREATE INDEX IF NOT EXISTS ix_earning_schedule_closure_policy
    ON earning_schedule (policy_id, policy_source) WHERE is_closure = TRUE;

COMMENT ON COLUMN earning_schedule.closure_ref IS
    'Phase 13 §B per L6: idempotency ref for a policy-status-triggered close event. '
    'Set once per event across every affected period so admins can trace the write '
    'back to the emitting Kafka record. NULL for rows untouched by policy-status events.';

COMMENT ON COLUMN earning_schedule.is_closure IS
    'Phase 13 §B per L6: TRUE when the row was closed / frozen by a policy-status '
    'transition (LAPSED, TERMINATED, or SUSPENDED). Nightly PremiumEarningExecutor '
    'skips rows with is_closure=TRUE so a frozen period does not linear-earn.';
