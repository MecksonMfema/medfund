-- Test-migration mirror for Phase 17 §0.2 public V176 tenant_report_schedule.
-- Mirrors production public migration but drops the FK to public.tenants
-- because the finance-service test schema has no tenants table (see V013
-- for the same "test schema owns no tenants" precedent).
--
-- Present here so V021's FK to public.tenant_report_schedule resolves and
-- ReportJobScheduleDedupIT can insert schedule rows to satisfy the FK.

CREATE TABLE IF NOT EXISTS public.tenant_report_schedule (
    id                        UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id                 UUID          NOT NULL,
    report_key                VARCHAR(80)   NOT NULL,
    enabled                   BOOLEAN       NOT NULL DEFAULT FALSE,
    cadence                   VARCHAR(20)   NOT NULL,
    hour_of_day               INT           NOT NULL DEFAULT 8,
    day_of_week               INT           NULL,
    day_of_month              INT           NULL DEFAULT 1,
    reporting_currency        VARCHAR(3)    NULL,
    last_fired_at             TIMESTAMPTZ   NULL,
    last_status               VARCHAR(20)   NULL,
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
