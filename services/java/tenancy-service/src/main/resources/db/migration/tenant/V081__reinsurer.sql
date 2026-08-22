-- =====================================================================
-- V081: Reinsurer master (Phase 10 §A)
-- =====================================================================
-- First migration of the reinsurance module. A reinsurer represents an
-- external counterparty on one or more treaties. The name-unique index
-- is partial on is_active so a deactivated reinsurer can be replaced by
-- a new active row with the same display name (rebrand, re-onboarding).

CREATE TABLE reinsurer (
    id                UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    name              VARCHAR(200) NOT NULL,
    contact_email     VARCHAR(255),
    contact_address   TEXT,
    jurisdiction_code VARCHAR(20),
    home_currency     CHAR(3),
    credit_rating     VARCHAR(20),
    is_active         BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    actor_id          UUID,
    actor_email       VARCHAR(255)
);

CREATE UNIQUE INDEX ux_reinsurer_name_active
    ON reinsurer (name)
 WHERE is_active = TRUE;
