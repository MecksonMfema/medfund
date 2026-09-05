-- Phase 19 §A Phase 2 — SIU case note: append-only activity log.
-- Every state transition, evidence upload, referral emits a note row
-- (in addition to the AuditEvent per Rule 8). Investigators + supervisors
-- can also add COMMENT-type notes for narrative context.
--
-- Rule-2 guard: tenant-schema table — queries must NOT use `public.`
-- prefix per bug_public_prefix_silent_rollback.

CREATE TABLE IF NOT EXISTS siu_case_note (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    case_id      UUID NOT NULL REFERENCES siu_case(id) ON DELETE CASCADE,
    author_id    UUID NOT NULL,
    author_email VARCHAR(255) NOT NULL,
    note_type    VARCHAR(32) NOT NULL,   -- 'COMMENT'|'STATUS_CHANGE'|'EVIDENCE_ADDED'|'ASSIGNED'|'REFERRAL_ADDED'|'FLAG_LINKED'
    body         TEXT NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT siu_case_note_type_chk CHECK (note_type IN (
        'COMMENT','STATUS_CHANGE','EVIDENCE_ADDED','ASSIGNED','REFERRAL_ADDED','FLAG_LINKED'))
);

CREATE INDEX IF NOT EXISTS siu_case_note_case_id_idx    ON siu_case_note (case_id);
CREATE INDEX IF NOT EXISTS siu_case_note_created_at_idx ON siu_case_note (created_at);

COMMENT ON TABLE  siu_case_note IS 'Append-only activity log for SIU cases — never UPDATE / DELETE by application code.';
