-- Phase 19 §B Phase 7 test schema — SIU evidence + external referral.
-- Mirrors the prod tenant migrations
--   V172__siu_evidence.sql, V173__siu_referral.sql
-- for the flat `public`-schema IT world.

CREATE TABLE IF NOT EXISTS siu_evidence (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    case_id           UUID NOT NULL REFERENCES siu_case(id) ON DELETE CASCADE,
    file_service_ref  VARCHAR(255) NOT NULL,
    description       TEXT NOT NULL,
    evidence_type     VARCHAR(32) NOT NULL,
    uploaded_by       UUID NOT NULL,
    uploaded_by_email VARCHAR(255) NOT NULL,
    uploaded_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT siu_evidence_type_chk CHECK (evidence_type IN (
        'DOCUMENT','PHOTO','PROVIDER_RECORD','MEMBER_RECORD','OTHER'))
);

CREATE INDEX IF NOT EXISTS siu_evidence_case_id_idx  ON siu_evidence (case_id);

CREATE TABLE IF NOT EXISTS siu_referral (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    case_id              UUID NOT NULL REFERENCES siu_case(id) ON DELETE CASCADE,
    referral_to          VARCHAR(32) NOT NULL,
    referral_reference   VARCHAR(255),
    referred_by          UUID NOT NULL,
    referred_by_email    VARCHAR(255) NOT NULL,
    referred_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    response_received_at TIMESTAMPTZ,
    response_notes       TEXT,

    CONSTRAINT siu_referral_target_chk CHECK (referral_to IN (
        'LAW_ENFORCEMENT','REGULATOR','INTERNAL_HR'))
);

CREATE INDEX IF NOT EXISTS siu_referral_case_id_idx  ON siu_referral (case_id);

GRANT SELECT, INSERT, UPDATE, DELETE, REFERENCES, TRIGGER
    ON siu_evidence, siu_referral TO public_role;
