-- Test-migration mirror for Phase 16 REG8 tenant V167 suspicious_transaction_alert.
-- Mirrors production tenancy-service V167 so finance-service ITs can load
-- SuspiciousTransactionAlertRepository against a real table.
--
-- Ships as part of Phase 17 §0.2 test-migration additions because the
-- Phase 16 land landed the repository + service without mirroring the
-- backing table — every finance-service full-context IT since has failed
-- to load, including the pre-existing ReportJobChunkIT. See Phase 17
-- Deviations for the incident context.

CREATE TABLE IF NOT EXISTS suspicious_transaction_alert (
    id                     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    raised_by_actor_id     UUID NOT NULL,
    raised_by_actor_email  VARCHAR(320) NOT NULL,
    raised_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    transaction_ref        VARCHAR(120) NOT NULL,
    transaction_type       VARCHAR(40) NOT NULL,
    amount_native          NUMERIC(18,2) NOT NULL,
    currency               VARCHAR(3) NOT NULL,
    member_id              UUID NULL,
    provider_id            UUID NULL,
    description            TEXT NOT NULL,
    status                 VARCHAR(20) NOT NULL DEFAULT 'RAISED'
        CHECK (status IN ('RAISED','REVIEWED','FILED','CLOSED')),
    reviewer_actor_id      UUID NULL,
    reviewer_actor_email   VARCHAR(320) NULL,
    reviewed_at            TIMESTAMPTZ NULL,
    review_note            TEXT NULL,
    filer_actor_id         UUID NULL,
    filer_actor_email      VARCHAR(320) NULL,
    filed_at               TIMESTAMPTZ NULL,
    filed_ref              VARCHAR(120) NULL,
    filed_xlsx_ref         VARCHAR(255) NULL,
    closer_actor_id        UUID NULL,
    closer_actor_email     VARCHAR(320) NULL,
    closed_at              TIMESTAMPTZ NULL,
    closed_reason          VARCHAR(120) NULL,
    created_at             TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at             TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT sta_transaction_type_ck
        CHECK (transaction_type IN ('PREMIUM','CLAIM_PAYOUT','ADVANCE_PAYMENT',
                                    'REFUND','COMMISSION','ADJUSTMENT','OTHER')),
    CONSTRAINT sta_amount_positive_ck CHECK (amount_native > 0),
    CONSTRAINT sta_currency_len_ck CHECK (length(currency) = 3),
    CONSTRAINT sta_description_min_ck CHECK (length(description) >= 20)
);

CREATE INDEX IF NOT EXISTS ix_suspicious_transaction_alert_status
    ON suspicious_transaction_alert (status, raised_at DESC);

CREATE INDEX IF NOT EXISTS ix_suspicious_transaction_alert_transaction_ref
    ON suspicious_transaction_alert (transaction_ref);

CREATE INDEX IF NOT EXISTS ix_suspicious_transaction_alert_member_id
    ON suspicious_transaction_alert (member_id) WHERE member_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS ix_suspicious_transaction_alert_provider_id
    ON suspicious_transaction_alert (provider_id) WHERE provider_id IS NOT NULL;
