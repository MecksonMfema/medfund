-- Phase 4 addition. Mirrors production tenant migration V089
-- (bordereau_period_export). Separate file per feedback_never_edit_applied_migrations —
-- V007 / V008 were applied against test databases when Phases 2/3 shipped.

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

-- Extend the schema-wide grant to include the new table.
GRANT SELECT, INSERT, UPDATE, DELETE, REFERENCES, TRIGGER
    ON ALL TABLES IN SCHEMA public TO public_role;
