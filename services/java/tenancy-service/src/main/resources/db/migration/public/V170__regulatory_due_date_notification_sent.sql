-- Dedupe log for the Phase 16 §0 REG20 RegulatoryDueDateScanner cron.
-- The scanner runs daily; every publish is idempotent within a 24-hour
-- window per (tenant, report_key, event_tier) — a duplicate row here
-- blocks a duplicate email. Rows are pruned by the retention job in a
-- later phase; for Phase 8 the table just grows (~ 8 keys × 4 tiers ×
-- N tenants per year, negligible).

CREATE TABLE IF NOT EXISTS public.regulatory_due_date_notification_sent (
    id           UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id    UUID          NOT NULL REFERENCES public.tenants(id) ON DELETE CASCADE,
    report_key   VARCHAR(80)   NOT NULL,
    event_tier   VARCHAR(30)   NOT NULL
        CHECK (event_tier IN ('DUE_DATE_7D', 'DUE_DATE_1D', 'DUE_DATE_0D', 'DUE_DATE_OVERDUE')),
    sent_at      TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);

-- One index carries the two access patterns the scanner needs:
--   1. dedupe check: "any row for (tenant, key, tier) within last 24h?" — a
--      DESC scan on sent_at bounded by tenant/key/tier walks 0-1 rows.
--   2. retention prune (future): a range scan on sent_at.
CREATE INDEX IF NOT EXISTS ix_regulatory_due_date_dedupe
    ON public.regulatory_due_date_notification_sent
       (tenant_id, report_key, event_tier, sent_at DESC);

COMMENT ON TABLE public.regulatory_due_date_notification_sent IS
    'Phase 16 §0 REG20: dedupe log for RegulatoryDueDateScanner cron. Blocks duplicate publishes within a 24-hour window per (tenant, report_key, event_tier).';
