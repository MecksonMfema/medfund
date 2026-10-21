-- Phase 19 §B Phase 7 — SIU case evidence: file-service refs + descriptions.
-- One row per uploaded artifact (document, photo, provider record).
-- File bytes live in file-service; this table stores the handle only.

CREATE TABLE IF NOT EXISTS siu_evidence (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    case_id           UUID NOT NULL REFERENCES siu_case(id) ON DELETE CASCADE,
    file_service_ref  VARCHAR(255) NOT NULL,   -- file-service URI/handle
    description       TEXT NOT NULL,
    evidence_type     VARCHAR(32) NOT NULL,    -- 'DOCUMENT'|'PHOTO'|'PROVIDER_RECORD'|'MEMBER_RECORD'|'OTHER'
    uploaded_by       UUID NOT NULL,
    uploaded_by_email VARCHAR(255) NOT NULL,   -- feedback_audit_actor_email
    uploaded_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT siu_evidence_type_chk CHECK (evidence_type IN (
        'DOCUMENT','PHOTO','PROVIDER_RECORD','MEMBER_RECORD','OTHER'))
);

CREATE INDEX IF NOT EXISTS siu_evidence_case_id_idx  ON siu_evidence (case_id);

COMMENT ON TABLE siu_evidence IS 'Investigator-uploaded evidence linked to an siu_case; file bytes live in file-service (Phase 19 §B Phase 7).';
