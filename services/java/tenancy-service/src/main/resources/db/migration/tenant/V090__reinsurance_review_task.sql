-- =====================================================================
-- V090: Reinsurance review queue (Phase 10 §B)
-- =====================================================================
-- One row per open manual-review task. Populated primarily by claim-
-- regression detection in ReinsuranceLossCessionConsumer (a re-
-- adjudication that lowers approvedAmount below a prior cession's
-- basis). Also holds recovery disputes and manual void requests.
--
-- FK ON DELETE SET NULL on cession_id / recovery_id / treaty_id: a task
-- is a historical audit surface — deleting the underlying entity
-- should preserve the task but drop the pointer so operators still
-- see the trail.

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

-- Partial index for the open-task queue view.
CREATE INDEX ix_reinsurance_review_open ON reinsurance_review_task (status, created_at)
    WHERE status IN ('OPEN','IN_PROGRESS');
