-- Phase 8 addition. Mirrors production tenant migration V090.
-- Populated primarily by claim-regression detection in
-- ReinsuranceLossCessionConsumer; the operator queue works through
-- open tasks and resolves them with keep/void/dismiss.

CREATE TABLE reinsurance_review_task (
    id                UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    task_type         VARCHAR(30) NOT NULL,
    cession_id        UUID        REFERENCES cession(id)   ON DELETE SET NULL,
    recovery_id       UUID        REFERENCES recovery(id)  ON DELETE SET NULL,
    claim_id          UUID,
    treaty_id         UUID        REFERENCES treaty(id)    ON DELETE SET NULL,
    status            VARCHAR(20) NOT NULL DEFAULT 'OPEN',
    assignee_user_id  UUID,
    due_by            TIMESTAMPTZ,
    create_reason     TEXT        NOT NULL,
    resolution_notes  TEXT,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    actor_id          UUID,
    actor_email       VARCHAR(255),
    CONSTRAINT reinsurance_task_type_ck CHECK (task_type IN
        ('CLAIM_REGRESSION','RECOVERY_DISPUTE','MANUAL_VOID_REQUEST')),
    CONSTRAINT reinsurance_task_status_ck CHECK (status IN
        ('OPEN','IN_PROGRESS','RESOLVED_VOID','RESOLVED_KEEP','DISMISSED'))
);

CREATE INDEX ix_reinsurance_review_open ON reinsurance_review_task (status, created_at)
    WHERE status IN ('OPEN','IN_PROGRESS');

GRANT SELECT, INSERT, UPDATE, DELETE, REFERENCES, TRIGGER
    ON ALL TABLES IN SCHEMA public TO public_role;
