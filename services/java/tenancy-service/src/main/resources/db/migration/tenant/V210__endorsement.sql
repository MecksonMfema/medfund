-- =====================================================================
-- V110: Policy endorsement (Phase 12 §C)
-- =====================================================================
-- Per-policy endorsement with four-eyes lifecycle mirroring
-- commission_adjustment (V097). The state machine is
--   DRAFT → APPROVED → COMMITTED   (terminal)
--   DRAFT | APPROVED → VOIDED       (terminal)
--   COMMITTED → COMPUTED            (set by contributions-service
--                                    EarningScheduleClosureService
--                                    once retro-recompute finishes)
--
-- change_type tags the operator intent (7 arms per grill note 10).
-- premium_delta is signed and in the policy's native currency —
-- earning-schedule retro-recompute never re-denominates.
-- effective_from is DATE (snap to 1st-of-month per
-- feedback_effective_date_snap; enforced at the service layer).
-- =====================================================================

CREATE TABLE IF NOT EXISTS endorsement (
    id                    UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    reference             VARCHAR(40)  NOT NULL UNIQUE,
    policy_id             UUID         NOT NULL,
    policy_source         VARCHAR(30)  NOT NULL CHECK (policy_source IN (
                              'LIFE_POLICY', 'FUNERAL_POLICY', 'DISABILITY_POLICY',
                              'TRAVEL_POLICY', 'VEHICLE_POLICY', 'PROPERTY_POLICY'
                          )),
    insurance_line        VARCHAR(20)  NOT NULL,
    change_type           VARCHAR(30)  NOT NULL CHECK (change_type IN (
                              'PREMIUM_ADJUSTMENT', 'COVERAGE_EXTENSION', 'BENEFIT_CHANGE',
                              'BENEFICIARY_CHANGE', 'ADMIN_CHANGE',
                              'PRODUCT_SWITCH', 'RENEWAL_ADVANCE'
                          )),
    effective_from        DATE         NOT NULL,
    premium_delta         NUMERIC(19, 4),
    currency_code         CHAR(3),
    reason                TEXT         NOT NULL,
    status                VARCHAR(20)  NOT NULL DEFAULT 'DRAFT' CHECK (status IN (
                              'DRAFT', 'APPROVED', 'COMMITTED', 'VOIDED', 'COMPUTED'
                          )),
    draft_actor_id        UUID         NOT NULL,
    draft_actor_email     VARCHAR(255) NOT NULL,
    draft_at              TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    approve_actor_id      UUID,
    approve_actor_email   VARCHAR(255),
    approve_at            TIMESTAMPTZ,
    commit_actor_id       UUID,
    commit_actor_email    VARCHAR(255),
    commit_at             TIMESTAMPTZ,
    voided_reason         VARCHAR(500),
    voided_at             TIMESTAMPTZ,
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at            TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_endorsement_reason_len   CHECK (length(trim(reason)) >= 10),
    CONSTRAINT chk_endorsement_delta_paired CHECK (
        (premium_delta IS NULL AND currency_code IS NULL)
        OR (premium_delta IS NOT NULL AND currency_code IS NOT NULL)
    )
);

CREATE INDEX IF NOT EXISTS ix_endorsement_policy         ON endorsement (policy_id, policy_source);
CREATE INDEX IF NOT EXISTS ix_endorsement_status         ON endorsement (status);
CREATE INDEX IF NOT EXISTS ix_endorsement_effective_from ON endorsement (effective_from);
CREATE INDEX IF NOT EXISTS ix_endorsement_reference      ON endorsement (reference);

COMMENT ON TABLE endorsement IS
    'Per-policy endorsement with four-eyes lifecycle (Phase 12 §C). The reference is END-YYYY-NNNNNN monotonic-per-year. On COMMIT the user-service PolicyEndorsedPublisher fires medfund.user.policy-endorsed; the contributions-service PolicyEndorsedConsumer runs the retro earning-schedule recompute and transitions the endorsement to COMPUTED.';
