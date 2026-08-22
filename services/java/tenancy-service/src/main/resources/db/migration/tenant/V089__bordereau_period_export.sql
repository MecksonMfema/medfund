-- =====================================================================
-- V089: Bordereau period export registry (Phase 10 §A)
-- =====================================================================
-- The soft-lock table for the quarter-aligned bordereau workflow.
-- First export of a (reinsurer, treaty, reportKey, year, quarter)
-- tuple inserts; subsequent re-exports increment export_count. Cessions
-- created after first_exported_at inside the same quarter are flagged
-- isPriorPeriodAdjustment on subsequent exports.

CREATE TABLE bordereau_period_export (
    id                UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    reinsurer_id      UUID        NOT NULL REFERENCES reinsurer(id) ON DELETE RESTRICT,
    treaty_id         UUID        REFERENCES treaty(id) ON DELETE RESTRICT,
    report_key        VARCHAR(80) NOT NULL,
    year              INT         NOT NULL,
    quarter           INT         NOT NULL,
    first_exported_at TIMESTAMPTZ NOT NULL,
    export_count      INT         NOT NULL DEFAULT 1,
    actor_id          UUID,
    actor_email       VARCHAR(255),
    CONSTRAINT bordereau_period_export_uq UNIQUE (reinsurer_id, treaty_id, report_key, year, quarter),
    CONSTRAINT bordereau_period_report_ck CHECK (report_key IN
        ('REINSURANCE_CESSION_BORDEREAU','REINSURANCE_RECOVERIES','REINSURANCE_TREATY_UTILIZATION')),
    CONSTRAINT bordereau_period_quarter_ck CHECK (quarter BETWEEN 1 AND 4)
);
