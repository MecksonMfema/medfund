-- Phase 17 §0.2: scheduled report delivery — per-tenant per-key schedule row.
-- Sibling to V130 tenant_report_config (which stays a pure on/off toggle);
-- Phase 17 schedules live here and reference the tenant + report_key pair.
--
-- Cadence + day-of-week / day-of-month / hour-of-day are validated by CHECK
-- constraints, not enum types, so the tenant admin UI can extend the
-- allow-list without a schema change (a follow-up cadence addition, e.g.
-- BIWEEKLY, would only touch this CHECK).
--
-- reporting_currency NULL means "use the tenant default at fire time" per
-- multi-currency Invariant #1 (F-S1).

CREATE TABLE IF NOT EXISTS public.tenant_report_schedule (
    id                        UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id                 UUID          NOT NULL REFERENCES public.tenants(id) ON DELETE CASCADE,
    report_key                VARCHAR(80)   NOT NULL,
    enabled                   BOOLEAN       NOT NULL DEFAULT FALSE,
    cadence                   VARCHAR(20)   NOT NULL,
    hour_of_day               INT           NOT NULL DEFAULT 8,
    day_of_week               INT           NULL,        -- 1..7 (Mon..Sun) when cadence=WEEKLY
    day_of_month              INT           NULL DEFAULT 1, -- 1..28 when cadence=MONTHLY
    reporting_currency        VARCHAR(3)    NULL,        -- null = tenant default at fire time (F-S1)
    last_fired_at             TIMESTAMPTZ   NULL,
    last_status               VARCHAR(20)   NULL,        -- COMPLETED | FAILED | SKIPPED_TOGGLE
    created_at                TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    created_by_actor_id       UUID          NOT NULL,
    created_by_actor_email    VARCHAR(255)  NOT NULL,
    updated_at                TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_by_actor_id       UUID          NOT NULL,
    updated_by_actor_email    VARCHAR(255)  NOT NULL,
    CONSTRAINT uq_trs_tenant_key UNIQUE (tenant_id, report_key),
    CONSTRAINT trs_cadence_ck CHECK (cadence IN ('WEEKLY','MONTHLY','QUARTERLY','ANNUAL')),
    CONSTRAINT trs_hour_ck CHECK (hour_of_day BETWEEN 0 AND 23),
    CONSTRAINT trs_dow_ck CHECK (day_of_week IS NULL OR day_of_week BETWEEN 1 AND 7),
    CONSTRAINT trs_dom_ck CHECK (day_of_month IS NULL OR day_of_month BETWEEN 1 AND 28),
    CONSTRAINT trs_weekly_needs_dow  CHECK (cadence <> 'WEEKLY'  OR day_of_week  IS NOT NULL),
    CONSTRAINT trs_monthly_needs_dom CHECK (cadence <> 'MONTHLY' OR day_of_month IS NOT NULL),
    CONSTRAINT trs_reporting_currency_ck CHECK (reporting_currency IS NULL OR reporting_currency ~ '^[A-Z]{3}$')
);

CREATE INDEX IF NOT EXISTS idx_trs_enabled
    ON public.tenant_report_schedule (tenant_id)
    WHERE enabled = TRUE;

CREATE INDEX IF NOT EXISTS idx_trs_last_fired
    ON public.tenant_report_schedule (last_fired_at DESC);
