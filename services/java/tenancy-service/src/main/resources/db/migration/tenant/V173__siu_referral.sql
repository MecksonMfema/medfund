-- Phase 19 §B Phase 7 — SIU external referral: law enforcement, regulator, HR.
-- Records the referral event; no automated API push to the external body
-- (deferred to Phase 19.5 per parent plan).

CREATE TABLE IF NOT EXISTS siu_referral (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    case_id              UUID NOT NULL REFERENCES siu_case(id) ON DELETE CASCADE,
    referral_to          VARCHAR(32) NOT NULL,    -- 'LAW_ENFORCEMENT'|'REGULATOR'|'INTERNAL_HR'
    referral_reference   VARCHAR(255),            -- external case number, if received
    referred_by          UUID NOT NULL,
    referred_by_email    VARCHAR(255) NOT NULL,   -- feedback_audit_actor_email
    referred_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    response_received_at TIMESTAMPTZ,
    response_notes       TEXT,

    CONSTRAINT siu_referral_target_chk CHECK (referral_to IN (
        'LAW_ENFORCEMENT','REGULATOR','INTERNAL_HR'))
);

CREATE INDEX IF NOT EXISTS siu_referral_case_id_idx  ON siu_referral (case_id);

COMMENT ON TABLE siu_referral IS 'External referrals recorded per case; automated body push deferred to Phase 19.5 (Phase 19 §B Phase 7).';
