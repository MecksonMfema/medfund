-- Party-neutral KYC. subject = (subject_type, subject_id); the only common identity handle across
-- party types is the UUID id. MEMBER subjects live in THIS tenant schema; PROVIDER subjects live in
-- public.providers — no FK is enforced on subject_id for that reason (resolution is per subject_type
-- in kyc-service). Mirrors siu_evidence for the document handle.
-- G6: verification is PER-SUBJECT (one row per subject) — the UNIQUE is (subject_type, subject_id).
--     insurance_line is CONTEXTUAL only (the line that triggered/contextualizes this verification and
--     feeds the rules); it is NOT part of identity. Enhanced per-line/jurisdiction requirements are
--     expressed as a higher verification_level via VERIFICATION rules, never as a second row.
CREATE TABLE IF NOT EXISTS kyc_verifications (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    subject_type       VARCHAR(24) NOT NULL,
    subject_id         UUID NOT NULL,
    insurance_line     VARCHAR(24),   -- G6: contextual only, not part of the UNIQUE identity
    verification_status VARCHAR(24) NOT NULL DEFAULT 'unverified',
    verification_level  SMALLINT NOT NULL DEFAULT 0,
    method             VARCHAR(16),
    ocr_prediction_id       UUID,
    screening_prediction_id UUID,
    screening_result   VARCHAR(16),
    verified_at        TIMESTAMPTZ,
    verified_by        UUID,
    verified_by_email  VARCHAR(255),
    rejected_reason    TEXT,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_by         UUID,
    -- G8: CHECK allows all four types now (forward-compat, user's call). The DB therefore does NOT
    --     guard against a write for a type with no resolver — so the COMPENSATING CONTROL is at the
    --     service layer: kyc-service's SubjectResolverRegistry MUST reject a verification request whose
    --     subject_type has no registered resolver (HTTP 400), until DEPENDANT/POLICYHOLDER resolvers
    --     exist (Plan B builds MEMBER + PROVIDER only). Prevents orphan rows the decision flow can't action.
    CONSTRAINT kyc_subject_type_chk CHECK (subject_type IN ('MEMBER','DEPENDANT','PROVIDER','POLICYHOLDER')),
    CONSTRAINT kyc_status_chk CHECK (verification_status IN ('unverified','pending_verification','verified','rejected','expired')),
    -- G6: per-subject identity. 'expired' (G7) is set by a LATER scheduled job, not in the pilot.
    CONSTRAINT kyc_verifications_subject_uq UNIQUE (subject_type, subject_id)
);
CREATE INDEX IF NOT EXISTS kyc_verifications_status_idx ON kyc_verifications (verification_status);

CREATE TABLE IF NOT EXISTS kyc_documents (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    subject_type      VARCHAR(24) NOT NULL,
    subject_id        UUID NOT NULL,
    document_type     VARCHAR(32) NOT NULL,
    file_service_ref  VARCHAR(255) NOT NULL,
    bucket            VARCHAR(128) NOT NULL,
    content_type      VARCHAR(128) NOT NULL,
    size_bytes        BIGINT NOT NULL,
    sha256            VARCHAR(64) NOT NULL,
    status            VARCHAR(20) NOT NULL DEFAULT 'uploaded',
    uploaded_by       UUID NOT NULL,
    uploaded_by_email VARCHAR(255) NOT NULL,   -- feedback_audit_actor_email
    uploaded_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT kyc_documents_type_chk CHECK (document_type IN
        ('NATIONAL_ID','PASSPORT','DRIVERS_LICENSE','PROOF_OF_ADDRESS','SELFIE','BIRTH_CERTIFICATE','OTHER')),
    CONSTRAINT kyc_documents_status_chk CHECK (status IN ('uploaded','verified','rejected','expired'))
);
CREATE INDEX IF NOT EXISTS kyc_documents_subject_idx ON kyc_documents (subject_type, subject_id);
