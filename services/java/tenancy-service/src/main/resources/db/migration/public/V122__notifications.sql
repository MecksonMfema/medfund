-- In-app notifications — a single durable store the UI bell reads from.
--
-- Extensible by design: producers register their own {kind, source_type}
-- pair and write rows via NotificationWriter. Today's only producer is
-- JobEventPublisher writing JOB_COMPLETED entries; the roadmap is
-- INVOICE_ISSUED, CLAIM_SUBMITTED, PERMISSION_DENIED, CHAT_MESSAGE, and
-- so on — each new kind is a call site, not a schema change.
--
-- Lives in `public` (not tenant schemas) so the bell endpoint answers a
-- single indexed query per user regardless of which tenant produced the
-- row. Cross-tenant surfaces (super-admin console) can filter with the
-- tenant_id column.
CREATE TABLE IF NOT EXISTS public.notifications (
    id           UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    -- NULL tenant_id = platform-global notification (super-admin only).
    tenant_id    UUID,
    -- Recipient. Matches JWT subject (Keycloak user id).
    user_id      UUID        NOT NULL,
    -- Producer-owned string identifier — the code path that writes the
    -- row picks a stable value (JOB_COMPLETED, INVOICE_ISSUED, etc.).
    -- Kept as VARCHAR so a new producer can add a new kind without a
    -- schema migration; the UI maps kind → icon/severity at render time.
    kind         VARCHAR(64) NOT NULL,
    title        VARCHAR(255) NOT NULL,
    body         TEXT,
    -- Cosmetic hint for the bell — info | success | warning | error.
    -- Stored as text so the UI defines the vocabulary without a schema
    -- coupling; unknown values render as 'info'.
    severity     VARCHAR(16) NOT NULL DEFAULT 'info',
    -- Optional back-pointer to the underlying domain row (a scheduled
    -- job run, an invoice, a claim). source_type is a producer-owned
    -- string; source_id is the row's UUID. Indexed so we can efficiently
    -- de-duplicate ("has this run already produced a notification?").
    source_type  VARCHAR(64),
    source_id    UUID,
    -- Deep-link the operator can follow from the bell row. Optional —
    -- purely cosmetic notifications (a broadcast) leave it null.
    action_url   VARCHAR(500),
    -- JSON escape hatch for producer-specific detail — e.g. a job's
    -- durationMs, an invoice's amountDue. The bell doesn't need to
    -- read the schema of this column; downstream drill-in pages can.
    metadata     JSONB,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    -- NULL = unseen. When the operator marks a row read we stamp this;
    -- the bell badge is the count of rows where seen_at IS NULL.
    seen_at      TIMESTAMPTZ
);

-- Primary access pattern: "the current user's most recent notifications".
CREATE INDEX IF NOT EXISTS idx_notifications_user_created
    ON public.notifications(user_id, created_at DESC);

-- Badge count: user_id where seen_at IS NULL. Partial index keeps it
-- tiny — historically most notifications will be marked read.
CREATE INDEX IF NOT EXISTS idx_notifications_user_unseen
    ON public.notifications(user_id) WHERE seen_at IS NULL;

-- De-duplication lookup by (source_type, source_id) so a re-fired event
-- doesn't produce a duplicate row.
CREATE INDEX IF NOT EXISTS idx_notifications_source
    ON public.notifications(source_type, source_id)
    WHERE source_type IS NOT NULL;

-- Cross-tenant filtering (super-admin console).
CREATE INDEX IF NOT EXISTS idx_notifications_tenant_created
    ON public.notifications(tenant_id, created_at DESC)
    WHERE tenant_id IS NOT NULL;
