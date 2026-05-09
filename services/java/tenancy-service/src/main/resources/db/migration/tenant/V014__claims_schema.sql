-- Claims-domain reference tables that have entities + repositories defined in
-- claims-service but no backing tables in V001__baseline.sql. The five tables
-- below match the @Table / @Column annotations on the entities exactly so the
-- existing repositories and DTO mappers light up without further changes.
--
-- Tables added:
--   pre_authorizations           — request/decision lifecycle for elective +
--                                  high-cost services that require prior
--                                  approval before the claim is submitted.
--   tariff_modifiers             — modifier catalogue (e.g. bilateral,
--                                  after-hours, assistant). adjustment_type
--                                  is one of PERCENTAGE | FIXED | MULTIPLIER.
--   icd_codes                    — diagnosis code catalogue. Seeded from the
--                                  WHO ICD-10 reference set in a follow-up
--                                  data load; this migration only creates the
--                                  table.
--   diagnosis_procedure_mappings — clinical-validity matrix used by the
--                                  Adjudication pipeline's stage 6 to flag
--                                  diagnosis-procedure mismatches.
--   rejection_reasons            — reason catalogue keyed by R-code (R01..)
--                                  shared across automatic + manual rejection
--                                  paths.
--
-- All tables live in the tenant schema (each tenant has its own copy). No
-- public references — these are tenant-config tables, not shared ones.

-- ── pre_authorizations ────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS pre_authorizations (
    id                UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    auth_number       VARCHAR(50)  NOT NULL UNIQUE,
    member_id         UUID         NOT NULL,
    provider_id       UUID         NOT NULL,
    scheme_id         UUID,
    tariff_code       VARCHAR(50),
    diagnosis_code    VARCHAR(20),
    status            VARCHAR(32)  NOT NULL DEFAULT 'pending'
                        CHECK (status IN ('pending', 'approved', 'rejected', 'expired')),
    requested_amount  NUMERIC(19,4),
    approved_amount   NUMERIC(19,4),
    currency_code     VARCHAR(3),
    requested_date    DATE         NOT NULL DEFAULT CURRENT_DATE,
    decision_date     DATE,
    expiry_date       DATE,
    decision_by       UUID,
    notes             TEXT,
    rejection_reason  VARCHAR(50),
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by        UUID
);

CREATE INDEX IF NOT EXISTS idx_pre_authorizations_member   ON pre_authorizations(member_id);
CREATE INDEX IF NOT EXISTS idx_pre_authorizations_provider ON pre_authorizations(provider_id);
CREATE INDEX IF NOT EXISTS idx_pre_authorizations_status   ON pre_authorizations(status);
CREATE INDEX IF NOT EXISTS idx_pre_authorizations_expiry   ON pre_authorizations(expiry_date)
    WHERE status = 'approved';

-- ── tariff_modifiers ──────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS tariff_modifiers (
    id                UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    code              VARCHAR(50)  NOT NULL UNIQUE,
    name              VARCHAR(200) NOT NULL,
    description       TEXT,
    adjustment_type   VARCHAR(32)  NOT NULL
                        CHECK (adjustment_type IN ('PERCENTAGE', 'FIXED', 'MULTIPLIER')),
    adjustment_value  NUMERIC(19,4) NOT NULL DEFAULT 0,
    is_active         BOOLEAN      NOT NULL DEFAULT TRUE
);

CREATE INDEX IF NOT EXISTS idx_tariff_modifiers_active ON tariff_modifiers(is_active);

-- ── icd_codes ─────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS icd_codes (
    id           UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    code         VARCHAR(20)  NOT NULL UNIQUE,
    description  TEXT         NOT NULL,
    category     VARCHAR(100),
    chapter      VARCHAR(100),
    is_active    BOOLEAN      NOT NULL DEFAULT TRUE
);

CREATE INDEX IF NOT EXISTS idx_icd_codes_chapter  ON icd_codes(chapter);
CREATE INDEX IF NOT EXISTS idx_icd_codes_active   ON icd_codes(is_active);

-- ── diagnosis_procedure_mappings ──────────────────────────────────────────
CREATE TABLE IF NOT EXISTS diagnosis_procedure_mappings (
    id           UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    icd_code     VARCHAR(20)  NOT NULL,
    tariff_code  VARCHAR(50)  NOT NULL,
    validity     VARCHAR(32)  NOT NULL DEFAULT 'VALID'
                   CHECK (validity IN ('VALID', 'REVIEW', 'INVALID')),
    notes        TEXT,
    CONSTRAINT uq_dpm_pair UNIQUE (icd_code, tariff_code)
);

CREATE INDEX IF NOT EXISTS idx_dpm_icd_code    ON diagnosis_procedure_mappings(icd_code);
CREATE INDEX IF NOT EXISTS idx_dpm_tariff_code ON diagnosis_procedure_mappings(tariff_code);

-- ── rejection_reasons ─────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS rejection_reasons (
    id           UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    code         VARCHAR(20)  NOT NULL UNIQUE,
    description  TEXT         NOT NULL,
    category     VARCHAR(50),
    is_active    BOOLEAN      NOT NULL DEFAULT TRUE
);

CREATE INDEX IF NOT EXISTS idx_rejection_reasons_active   ON rejection_reasons(is_active);
CREATE INDEX IF NOT EXISTS idx_rejection_reasons_category ON rejection_reasons(category);

-- ── Seed: standard rejection reasons (R01..R18) ───────────────────────────
-- These are the canonical codes referenced throughout adjudication.md. Each
-- tenant gets their own copy; they can disable codes they don't use but the
-- catalogue itself is platform-defined.

INSERT INTO rejection_reasons (code, category, description) VALUES
    ('R01', 'ELIGIBILITY',     'Member not active'),
    ('R02', 'WAITING_PERIOD',  'Within waiting period for benefit category'),
    ('R03', 'BENEFIT',         'Benefit limit exhausted'),
    ('R04', 'PREAUTH',         'Pre-authorization required but not provided'),
    ('R05', 'PREAUTH',         'Pre-authorization expired'),
    ('R06', 'TARIFF',          'Tariff code invalid or inactive'),
    ('R07', 'TARIFF',          'Claimed amount exceeds tariff rate'),
    ('R08', 'CLINICAL',        'Duplicate claim detected'),
    ('R09', 'CLINICAL',        'Diagnosis-procedure mismatch'),
    ('R10', 'ELIGIBILITY',     'Provider not registered with tenant'),
    ('R11', 'ELIGIBILITY',     'Member contributions in arrears'),
    ('R12', 'BENEFIT',         'Service excluded from member benefits'),
    ('R13', 'CLINICAL',        'Frequency limit exceeded for this service'),
    ('R14', 'ELIGIBILITY',     'Member exceeds age limit for this benefit'),
    ('R15', 'ELIGIBILITY',     'Claim submitted past deadline'),
    ('R16', 'FRAUD',           'Flagged for fraud review'),
    ('R17', 'CLINICAL',        'Service not appropriate for member gender'),
    ('R18', 'CLINICAL',        'Service not appropriate for member age')
ON CONFLICT (code) DO NOTHING;
