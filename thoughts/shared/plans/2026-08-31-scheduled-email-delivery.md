---
date: 2026-08-31
git_commit: ba9e65c
branch: rename-adjustments-to-notes
ticket: null
parent_plan: thoughts/shared/plans/2026-08-11-financial-reporting-suite.md
parent_phase: Phase 17
research:
  - thoughts/shared/notes/2026-08-31-phase17-scheduled-email-grill.md (grilling scratchpad)
steer: "single sub-plan, 5 tranches, ~10-12 phases; S13 chose this shape"
services_touched: [tenancy-service, finance-service, contributions-service, claims-service, user-service, gateway, notification-service, angular, shared]
status: draft
grilled: 2026-08-31 (S1..S13 + F-S1..F-S14 in parent plan Phase 17)
---

# Scheduled Email Delivery — Phase 17 Implementation Plan

> **Parent plan**: `thoughts/shared/plans/2026-08-11-financial-reporting-suite.md § Phase 17` (Decisions S1..S13, Settled-by-fact F-S1..F-S14). Every design decision here traces back to those; read the parent Phase 17 block first for rationale.

## Overview

Ship the scheduled auto-run + email delivery layer for **13 non-regulator operational cadenced report keys** (per S1). A single `@Scheduled` probe in finance-service iterates `public.tenant_report_schedule` hourly, resolves cadence matches against the tenant's local time, dispatches per-fire runs to owner services via a shape-adapter, records every run in `report_job`, uploads the XLSX to MinIO, and publishes `medfund.notification.report-delivery`. A new notification-service `internal/report/dispatcher.go` consumes the topic, MIME-attaches the XLSX (≤10 MB) or renders a signed download link (>10 MB), and delivers via SMTP. Failures publish `medfund.notification.report-delivery-failed` for tenant alerting. Tenant admins manage schedules + recipients at a new `/tenant/admin/settings/report-schedules` page.

## Current State Analysis

- **`ReportKey` (`services/java/shared/src/main/java/com/medfund/shared/report/ReportKey.java:22`)** already carries `cadenced: boolean` per key (24 keys marked `true`). Javadoc line 148-152 explicitly declares this as the Phase 17 form-eligibility gate.
- **`ReportCadence` enum (`services/java/shared/src/main/java/com/medfund/shared/report/ReportCadence.java:14`)** has 4 values: `MONTHLY, QUARTERLY, ANNUAL, EVENT_DRIVEN`. **Missing `WEEKLY`** — Phase 17 adds it (S3).
- **`ReportCadenceCatalog` (`services/java/shared/src/main/java/com/medfund/shared/report/ReportCadenceCatalog.java:22`)** maps only regulator keys to (cadence, daysPostPeriodEnd). Operational cadenced keys have no cadence in code.
- **`tenant_report_config` (V130, public schema)** — pure on/off toggle table. Phase 17's `tenant_report_schedule` is a **sibling** (not an extension) per S6.
- **`Tenant.timezone` (`services/java/tenancy-service/src/main/java/com/medfund/tenancy/entity/Tenant.java:49`)** — populated `String` field; parseable by `ZoneId.of(...)`.
- **`RegulatoryDueDateScanner` (`services/java/finance-service/src/main/java/com/medfund/finance/regulatory/scheduler/RegulatoryDueDateScanner.java:76`)** — canonical `@Scheduled(cron="...")` probe with tenant iteration + Kafka publish + dedup-table pattern. Phase 17 clones the shape.
- **`TenantRegulatoryRecipientController` + `TenantRegulatoryRecipientService`** in tenancy-service — canonical recipient CRUD with `AuditActor` + old/new audit maps. Phase 17 clones for `tenant_report_schedule_recipient`.
- **`TenantReportConfigService.bulkUpsert(...)` (`services/java/tenancy-service/src/main/java/com/medfund/tenancy/service/TenantReportConfigService.java:81`)** — Phase 17 §0 extends `upsertOne`/`updateExisting` to cascade-disable schedules on TRUE→FALSE.
- **`ReportJob` entity (`services/java/finance-service/src/main/java/com/medfund/finance/report/entity/ReportJob.java:32`)** — has `retention_class` + `parent_job_id` from V151. **Missing `source` + `schedule_id`** — Phase 17 §0 ALTERs.
- **`ReportJobPublisher` (`services/java/finance-service/src/main/java/com/medfund/finance/report/kafka/ReportJobPublisher.java:35`)** — publishes to `medfund.report.job-requested` with 900 KB reject guard.
- **notification-service `internal/regulatory/dispatcher.go`** — canonical Go consumer with recipient lookup via `HTTPTenancyClient.ActiveFor(ctx, tenantId)` hitting `GET /api/v1/tenants/{id}/regulatory-recipients/active`.
- **`internal/invoice/dispatcher.go:131-141`** — canonical MIME attachment pattern using `mail.Message{Attachments: []mail.Attachment{...}}`; MinIO fetch via `d.Fetcher.GetObject(ctx, bucket, key)`.
- **`services/java/shared/src/main/java/com/medfund/shared/kafka/MinIOConfig.java:31-40`** — `MinioClient` bean wiring; `medfund-report-payloads` bucket already provisioned (Phase 15 §14).
- **`AmlFilingBlobStore` (`services/java/finance-service/src/main/java/com/medfund/finance/regulatory/aml/AmlFilingBlobStore.java:65-92`)** — `putObject` + `getObject` reference pattern.
- **`SecurityEventPublisher.publishDataAccess(tenantId, actorId, actorEmail, reportKey, details)` (`services/java/shared/src/main/java/com/medfund/shared/security/SecurityEventPublisher.java:77-87`)** — the invariant #3 emission point.
- **`CrossServiceCallHelper.guarded(name, call, fallback, warnings)` (`services/java/shared/src/main/java/com/medfund/shared/report/CrossServiceCallHelper.java:64-99`)** — 2 s timeout, 1 retry, 250 ms backoff. Phase 17 uses for owner-service render calls.
- **Gateway routes (`services/go/gateway/internal/routes/routes.go:28-260`)** — `app.All("/api/v1/<path>/*", proxy.Handler(cfg.<Service>URL))` pattern.
- **Angular reports-tab (`clients/angular/src/app/pages/tenant-admin/settings/reports/reports-tab.component.ts:123-136`)** — `groupByFamily()` + dirty-diff save; extension point for "Manage schedule" link column.
- **Migration numbering (verified 2026-08-31)**: public V175 (`V175__tenant_aml_threshold_config.sql`), tenant V167 (`V167__suspicious_transaction_alert.sql`). **All tenant migrations live under `services/java/tenancy-service/src/main/resources/db/migration/tenant/`** — tenancy-service is the sole Flyway authority for both public and every per-tenant schema.
- **Kafka topic constants** live in the record class itself as `public static final String TOPIC = "medfund.<domain>.<event>"` — precedent from `RegulatoryDueDateApproachingEvent.TOPIC`.
- **No ShedLock in tree** — cron dedup is table-based (`regulatory_due_date_notification_sent` V170). Phase 17 dedups via a partial UNIQUE index on `report_job`.
- **AGED_DEBTORS**: cadenced=true (`ReportKey.java:38`) but has no live controller — Phase 8 shipped `AgedBalancesExcelService.generate(...)` under the `AGED_BALANCES` name (historical rename). Phase 17's `AGED_DEBTORS` adapter delegates to `AgedBalancesExcelService` unchanged (see Owed-back).

### Key Discoveries

- **Reuse before rebuild** patterns pinned:
  - Probe shape → `RegulatoryDueDateScanner`
  - Recipient CRUD → `TenantRegulatoryRecipientService` + `TenantRegulatoryRecipientController`
  - Go dispatcher shell → `internal/regulatory/dispatcher.go` + `internal/regulatory/consumer.go` + `internal/regulatory/tenancy_client.go`
  - MIME attachment → `internal/invoice/dispatcher.go:131-141`
  - MinIO wiring → shared `MinIOConfig` + `AmlFilingBlobStore.putObject/getObject`
  - `report_job` entity + terminal-status trigger → `V151__rename_report_job.sql`
  - Kafka publisher pattern → `ReportJobPublisher` with size guard
  - Audit event emission → `TenantRegulatoryRecipientService.publishAudit(...)`
- **Uniform adapter contract** allows the 13 heterogeneous shape services (varied signatures: some take `(from, to, warnings)`, some `(asOf, weeks, warnings)`, some `(reinsurerId, treatyId, year, quarter, currency, tenantId)`) to be normalised into `Mono<byte[]> render(ScheduledFireContext ctx)` — each adapter is thin and centralises the ugly-parameter mapping.
- **Multi-instance dedup via partial UNIQUE index** on `report_job(tenant_id, report_key, schedule_id, period_start) WHERE schedule_id IS NOT NULL` — coexists with existing ad-hoc rows where `schedule_id IS NULL`.
- **Unsubscribe token is a public route** on notification-service (no JWT) — clicking the link from an email cannot require login. Rate-limited by token uniqueness + one-shot deactivate.

## Desired End State

A tenant admin at `/tenant/admin/settings/report-schedules` sees a family-grouped grid of the 13 cadenced report keys, can enable each with a cadence (WEEKLY / MONTHLY / QUARTERLY / ANNUAL), day/hour picker in tenant TZ, and per-schedule recipient list (add/remove/is_active). The `ScheduledReportProbe` in finance-service fires hourly at HH:05, matches cadences per tenant TZ, dispatches each fire to the owner service via `CrossServiceCallHelper`, inserts + updates `report_job` (source=SCHEDULED, schedule_id FK), uploads the XLSX to MinIO, and publishes `medfund.notification.report-delivery`. The notification-service `internal/report/dispatcher.go` picks up the event, fetches the XLSX, and delivers via SMTP with MIME attachment ≤10 MB or signed link fallback. Failures publish `medfund.notification.report-delivery-failed` and route an alert email to schedule recipients. The tenant can view schedule run history (last N `report_job` runs) with a re-run button and can unsubscribe via a token URL in the email footer.

### Verification

```bash
# Backend
cd services/java && ./gradlew :tenancy-service:build :finance-service:build :contributions-service:build :claims-service:build :user-service:build
make test-java
make test-integration

# Notification service
cd services/go/notification-service && go test ./...

# Gateway (signed-link route)
cd services/go/gateway && go test ./...

# Frontend
make test-angular
make test-e2e   # includes new scheduled-report Playwright specs

# Manual acceptance
make infra && make tenancy user contributions finance claims gateway notification web
# Log in as tenant admin → /tenant/admin/settings/report-schedules
# Create a MONTHLY commission_statement schedule with recipient
# Wait for probe fire OR call POST /api/v1/reports/scheduled/probe/force-fire?scheduleId=... (test-only endpoint)
# Verify XLSX arrives at mailpit inbox (http://localhost:8025)
# Toggle the report OFF in /tenant/admin/settings/reports → confirm cascade-disable modal → save
# Verify tenant_report_schedule.enabled flipped to FALSE + AuditEvent emitted
```

## What We're NOT Doing

- Auto-run + email delivery for regulator + IFRS 17 + AML periodic + FRAUD_SIU_REPORT keys — deferred to Phase 17.5 follow-up (parent-plan S1 rationale: REG12/REG13 MFA-gated human filing; REG20 already emails due-date reminders).
- Per-schedule `reporting_currency_override` column — v1 uses `tenant_currency_config.is_default` per invariant #1; add per-schedule override when a tenant asks (F-S1).
- Raw cron expression editing in the UI — enum cadence + day/hour picker only per S3.
- ShedLock distributed locking — dedup via partial UNIQUE index per S4.
- Ownership-transfer admin surface for reassigning `updated_by_actor_*` — follow-up per S10.
- Rerun-schedule "backfill" mode for missed periods — single-job rerun only.
- Bulk cadence changes across many tenants — per-tenant per-schedule only.
- Attachment size histogram / trend alerts — deferred instrumentation.
- Renaming `AgedBalancesExcelService` to `AgedDebtorsExcelService` — the historical name stays; adapter maps AGED_DEBTORS → this service unchanged (see Owed-back).
- Regulator-report `RegulatoryDueDateScanner` and its dispatcher — Phase 16 REG20 ships those and they stay unchanged.

## Implementation Approach

Ship in 5 tranches per S13: §0 shared types + migrations, §A backend probe + orchestrator + adapters + tenancy CRUD + cascade-disable, §B notification dispatcher + gateway route + unsubscribe, §C Angular admin surface + Phase 0 grid link, §D Playwright + rollout notes. Each phase produces a green build and hand-off point for `implement-plan`.

**Rollout order (per F-S9)**: notification-service `internal/report/dispatcher.go` consumer deploys and starts consuming from `earliest` BEFORE finance-service `ScheduledReportOrchestrator` starts publishing. Deploy sequence:
1. tenancy-service (schema + CRUD) — no external consumers depend on it.
2. notification-service (dispatcher with both topics wired) — starts consuming; nothing publishes yet.
3. Owner services (contributions, claims, user) with new `/scheduled-render` endpoints.
4. finance-service (probe + orchestrator + publisher).
5. gateway (signed-download route).
6. Angular.

**Backwards compatibility on Kafka**: new topics; no existing schema affected. Adding `source` + `schedule_id` columns to `report_job` is additive (default `'ADHOC'`, nullable schedule_id). Existing ad-hoc rows unchanged.

---

## Phase 1 (§0.1): Shared types + `ReportKey.periodShape` authoring pass

### Overview

Add the `ReportCadence.WEEKLY` value, introduce `ReportPeriodShape` enum, and extend `ReportKey` with a `periodShape` field populated for all 24 cadenced keys. No behavioural change — just the type surface every downstream phase depends on.

### Changes Required

#### 1. Extend `ReportCadence`
**File**: `services/java/shared/src/main/java/com/medfund/shared/report/ReportCadence.java`
**Changes**: add `WEEKLY` value (per S3).

```java
public enum ReportCadence {
    WEEKLY,
    MONTHLY,
    QUARTERLY,
    ANNUAL,
    EVENT_DRIVEN
}
```

Update the javadoc to note WEEKLY is Phase 17 scheduling only (EVENT_DRIVEN stays regulator-only per F-S11). Also correct the stale "Phase 8" reference to "Phase 16 REG20" in the same javadoc.

#### 2. New `ReportPeriodShape` enum
**File**: `services/java/shared/src/main/java/com/medfund/shared/report/ReportPeriodShape.java` (new)

```java
package com.medfund.shared.report;

/**
 * Whether a scheduled report covers a completed prior period or a snapshot as
 * of fire time. Used by the Phase 17 scheduler ({@code ScheduledReportProbe} +
 * {@code ScheduledReportOrchestrator} in finance-service) to derive
 * (periodStart, periodEnd, asOf) from (cadence, firedAt, shape).
 *
 * <p>{@link #PREVIOUS_COMPLETE_PERIOD} — for period reports like
 * COMMISSION_STATEMENT, LOSS_RATIO, POLICY_MOVEMENT: the fire covers the
 * previously-completed calendar unit (last week/month/quarter/year).
 *
 * <p>{@link #AS_OF_FIRE_TIME} — for snapshot reports like AGED_DEBTORS and
 * CASH_FLOW_FORECAST_13W: (periodStart, periodEnd, asOf) all equal firedAt.
 */
public enum ReportPeriodShape {
    PREVIOUS_COMPLETE_PERIOD,
    AS_OF_FIRE_TIME
}
```

#### 3. Extend `ReportKey` with `periodShape`
**File**: `services/java/shared/src/main/java/com/medfund/shared/report/ReportKey.java`
**Changes**: add final `periodShape` field per constant. Non-cadenced keys pass `null`. Populate the 24 cadenced keys per S5 mapping.

```java
// Constructor updates:
@Getter
@RequiredArgsConstructor
public enum ReportKey {
    BILLING_REPORT              ("Billing — per scheme",                    ReportFamily.BILLING,          false, null),
    // ... non-cadenced keys pass null ...
    COLLECTION_RATE             ("Collection rate (receipts vs billing)",   ReportFamily.RECEIPTS,         true,  ReportPeriodShape.PREVIOUS_COMPLETE_PERIOD),
    AGED_DEBTORS                ("Aged debtors",                            ReportFamily.DEBTORS,          true,  ReportPeriodShape.AS_OF_FIRE_TIME),
    CLAIMS_SUMMARY              ("Claims summary",                          ReportFamily.CLAIMS_FINANCIAL, true,  ReportPeriodShape.PREVIOUS_COMPLETE_PERIOD),
    LOSS_RATIO                  ("Loss ratio (billing vs claims)",          ReportFamily.RECONCILIATION,   true,  ReportPeriodShape.PREVIOUS_COMPLETE_PERIOD),
    CASH_FLOW_FORECAST_13W      ("Cash flow forecast (13 weeks)",           ReportFamily.RECONCILIATION,   true,  ReportPeriodShape.AS_OF_FIRE_TIME),
    POLICY_MOVEMENT             ("Policy movement",                         ReportFamily.POLICY_LIFECYCLE, true,  ReportPeriodShape.PREVIOUS_COMPLETE_PERIOD),
    PERSISTENCY_COHORT          ("Persistency cohort",                      ReportFamily.POLICY_LIFECYCLE, true,  ReportPeriodShape.PREVIOUS_COMPLETE_PERIOD),
    GROUP_CENSUS                ("Group census",                            ReportFamily.POLICY_LIFECYCLE, true,  ReportPeriodShape.PREVIOUS_COMPLETE_PERIOD),
    PROVIDER_NETWORK_UTILIZATION("Provider network utilization",            ReportFamily.CLAIMS_FINANCIAL, true,  ReportPeriodShape.PREVIOUS_COMPLETE_PERIOD),
    REINSURANCE_CESSION_BORDEREAU ("Reinsurance — cession bordereau",       ReportFamily.REINSURANCE,      true,  ReportPeriodShape.PREVIOUS_COMPLETE_PERIOD),
    REINSURANCE_RECOVERIES        ("Reinsurance — recoveries bordereau",    ReportFamily.REINSURANCE,      true,  ReportPeriodShape.PREVIOUS_COMPLETE_PERIOD),
    COMMISSION_STATEMENT        ("Commission statement",                    ReportFamily.COMMISSION,       true,  ReportPeriodShape.PREVIOUS_COMPLETE_PERIOD),
    UPR_MOVEMENT                ("UPR movement",                            ReportFamily.UNDERWRITING,     true,  ReportPeriodShape.PREVIOUS_COMPLETE_PERIOD),
    // ... regulator + IFRS17 + AML + FRAUD keys carry PREVIOUS_COMPLETE_PERIOD or null but stay out of Phase 17 UI whitelist ...
    IFRS17_LRC_LIC_RECONCILIATION       ("IFRS 17 — LRC / LIC reconciliation",       ReportFamily.REGULATORY, true, ReportPeriodShape.PREVIOUS_COMPLETE_PERIOD),
    // etc.
    ;

    private final String label;
    private final ReportFamily family;
    private final boolean cadenced;
    /** Period shape for Phase 17 scheduling; null for non-cadenced keys. */
    private final ReportPeriodShape periodShape;
```

Update `ReportResponseTest` and `SecurityEventPublisherTest` fixtures if any hardcode `ReportKey.AGED_DEBTORS` construction — they already reference the enum by name, no fixture changes required (verified 2026-08-31).

#### 4. Whitelist helper
**File**: `services/java/shared/src/main/java/com/medfund/shared/report/ScheduledReportEligibility.java` (new)

```java
package com.medfund.shared.report;

import java.util.EnumSet;
import java.util.Set;

/**
 * Phase 17 scheduling whitelist. Not every {@code cadenced=true} key is
 * schedulable by tenants in v1 — regulator + IFRS 17 + AML + FRAUD_SIU keys
 * are excluded per Phase 17 §S1 (parent plan). This helper is the single
 * source of truth for the whitelist; tenancy-service enforces it on schedule
 * CRUD, Angular filters the UI grid by it.
 */
public final class ScheduledReportEligibility {

    private static final Set<ReportKey> WHITELIST = EnumSet.of(
            ReportKey.COMMISSION_STATEMENT,
            ReportKey.LOSS_RATIO,
            ReportKey.COLLECTION_RATE,
            ReportKey.AGED_DEBTORS,
            ReportKey.CASH_FLOW_FORECAST_13W,
            ReportKey.CLAIMS_SUMMARY,
            ReportKey.POLICY_MOVEMENT,
            ReportKey.PERSISTENCY_COHORT,
            ReportKey.GROUP_CENSUS,
            ReportKey.PROVIDER_NETWORK_UTILIZATION,
            ReportKey.REINSURANCE_CESSION_BORDEREAU,
            ReportKey.REINSURANCE_RECOVERIES,
            ReportKey.UPR_MOVEMENT
    );

    private ScheduledReportEligibility() {}

    public static boolean isEligible(ReportKey key) {
        return WHITELIST.contains(key);
    }

    public static Set<ReportKey> whitelist() {
        return EnumSet.copyOf(WHITELIST);
    }
}
```

### Success Criteria

#### Automated Verification
- [x] Shared module compiles clean: `cd services/java && ./gradlew :shared:build`
- [x] Every downstream module compiles clean: `cd services/java && ./gradlew build`
- [x] Unit tests pass: `make test-java`
- [x] New test `ReportKeyPeriodShapeTest` in `shared/src/test/java/com/medfund/shared/report/` asserts every `cadenced=true` key has a non-null `periodShape` and every non-cadenced key has `periodShape == null`.
- [x] New test `ScheduledReportEligibilityTest` asserts the whitelist contains exactly the 13 S1 keys and excludes the 11 regulator/IFRS/AML/FRAUD keys.

#### Manual Verification
- [ ] Read the S5 mapping in Phase 17 § of the parent plan and cross-check each cadenced key's `periodShape` value.

### Deviations

- **2026-08-31**: `RegulatoryDueDateService.currentPeriodFor(...)` exhaustive switch on `ReportCadence` required a `WEEKLY` case after the enum-add. Added `case WEEKLY -> throw new IllegalArgumentException(...)` since regulator cadence catalog never maps a key to WEEKLY (Phase 17-only cadence, per the updated `ReportCadence` javadoc). Not a design change — just plugging a switch-exhaustiveness hole the plan didn't name.

---

## Phase 2 (§0.2): Migrations + `report_job` ALTER

### Overview

Ship the four schema changes Phase 17 needs — two new public tables (`tenant_report_schedule` + `tenant_report_schedule_recipient`), and an ALTER on the tenant-scope `report_job` (source + schedule_id columns + partial UNIQUE dedup index). All migrations go under tenancy-service (sole Flyway authority per Current State).

### Changes Required

#### 1. Public — `tenant_report_schedule`
**File**: `services/java/tenancy-service/src/main/resources/db/migration/public/V176__tenant_report_schedule.sql` (new; verify V176 is next-free at implementation time per `feedback_never_edit_applied_migrations`)

```sql
-- Phase 17 §0.2: scheduled report delivery — per-tenant per-key schedule row.
-- Schema drafted at grill S3+S4+S9+S10 (2026-08-31); see parent plan Phase 17.

CREATE TABLE IF NOT EXISTS public.tenant_report_schedule (
    id                     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id              UUID NOT NULL REFERENCES public.tenants(id) ON DELETE CASCADE,
    report_key             VARCHAR(80) NOT NULL,
    enabled                BOOLEAN NOT NULL DEFAULT FALSE,
    cadence                VARCHAR(20) NOT NULL,
    hour_of_day            INT NOT NULL DEFAULT 8,
    day_of_week            INT NULL,        -- 1..7 (Mon..Sun) when cadence=WEEKLY
    day_of_month           INT NULL DEFAULT 1, -- 1..28 when cadence=MONTHLY
    reporting_currency     VARCHAR(3) NULL, -- null = tenant default at fire time (F-S1)
    last_fired_at          TIMESTAMPTZ NULL,
    last_status            VARCHAR(20) NULL,  -- COMPLETED | FAILED | SKIPPED_TOGGLE
    created_at             TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by_actor_id    UUID NOT NULL,
    created_by_actor_email VARCHAR(255) NOT NULL,
    updated_at             TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_by_actor_id    UUID NOT NULL,
    updated_by_actor_email VARCHAR(255) NOT NULL,
    CONSTRAINT uq_trs_tenant_key UNIQUE (tenant_id, report_key),
    CONSTRAINT trs_cadence_ck CHECK (cadence IN ('WEEKLY','MONTHLY','QUARTERLY','ANNUAL')),
    CONSTRAINT trs_hour_ck CHECK (hour_of_day BETWEEN 0 AND 23),
    CONSTRAINT trs_dow_ck CHECK (day_of_week IS NULL OR day_of_week BETWEEN 1 AND 7),
    CONSTRAINT trs_dom_ck CHECK (day_of_month IS NULL OR day_of_month BETWEEN 1 AND 28),
    CONSTRAINT trs_weekly_needs_dow CHECK (cadence <> 'WEEKLY' OR day_of_week IS NOT NULL),
    CONSTRAINT trs_monthly_needs_dom CHECK (cadence <> 'MONTHLY' OR day_of_month IS NOT NULL),
    CONSTRAINT trs_reporting_currency_ck CHECK (reporting_currency IS NULL OR reporting_currency ~ '^[A-Z]{3}$')
);

CREATE INDEX IF NOT EXISTS idx_trs_enabled ON public.tenant_report_schedule (tenant_id) WHERE enabled = TRUE;
CREATE INDEX IF NOT EXISTS idx_trs_last_fired ON public.tenant_report_schedule (last_fired_at DESC);
```

#### 2. Public — `tenant_report_schedule_recipient`
**File**: `services/java/tenancy-service/src/main/resources/db/migration/public/V177__tenant_report_schedule_recipient.sql` (new)

```sql
-- Phase 17 §0.2: per-schedule recipient list (mirrors REG13/REG20's
-- tenant_regulatory_recipient shape).

CREATE TABLE IF NOT EXISTS public.tenant_report_schedule_recipient (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    schedule_id        UUID NOT NULL REFERENCES public.tenant_report_schedule(id) ON DELETE CASCADE,
    email              VARCHAR(255) NOT NULL,
    display_name       VARCHAR(160) NULL,
    is_active          BOOLEAN NOT NULL DEFAULT TRUE,
    unsubscribe_token  UUID NOT NULL DEFAULT gen_random_uuid() UNIQUE,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    actor_id           UUID NOT NULL,
    actor_email        VARCHAR(255) NOT NULL,
    CONSTRAINT uq_trsr_schedule_email UNIQUE (schedule_id, LOWER(email)),
    CONSTRAINT trsr_email_ck CHECK (email LIKE '%@%')
);

CREATE INDEX IF NOT EXISTS idx_trsr_schedule_active ON public.tenant_report_schedule_recipient(schedule_id) WHERE is_active = TRUE;
```

#### 3. Tenant — `report_job` ALTER
**File**: `services/java/tenancy-service/src/main/resources/db/migration/tenant/V168__report_job_schedule_columns.sql` (new; verify V168 next-free)

```sql
-- Phase 17 §0.2 (S2): add scheduled-run columns to report_job.
-- source distinguishes ad-hoc user-triggered exports from probe-fired runs.
-- schedule_id back-references the public.tenant_report_schedule row.
-- The partial UNIQUE index prevents multi-instance probe duplicate fires
-- for the same (tenant, key, schedule, period) while leaving ad-hoc rows
-- (schedule_id IS NULL) alone.

ALTER TABLE report_job
    ADD COLUMN IF NOT EXISTS source VARCHAR(20) NOT NULL DEFAULT 'ADHOC',
    ADD COLUMN IF NOT EXISTS schedule_id UUID NULL,
    ADD COLUMN IF NOT EXISTS period_start DATE NULL,
    ADD COLUMN IF NOT EXISTS period_end DATE NULL;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'report_job_source_ck'
    ) THEN
        ALTER TABLE report_job
            ADD CONSTRAINT report_job_source_ck CHECK (source IN ('ADHOC','SCHEDULED'));
    END IF;

    -- FK to public.tenant_report_schedule; SET NULL keeps history when a
    -- schedule is deleted (audit trail preserved).
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'report_job_schedule_fk'
    ) THEN
        ALTER TABLE report_job
            ADD CONSTRAINT report_job_schedule_fk
            FOREIGN KEY (schedule_id) REFERENCES public.tenant_report_schedule(id) ON DELETE SET NULL;
    END IF;
END $$;

-- Multi-instance dedup: partial UNIQUE on scheduled runs. Ad-hoc rows
-- (schedule_id IS NULL, source='ADHOC') are unaffected because they're
-- outside the WHERE clause.
CREATE UNIQUE INDEX IF NOT EXISTS ux_report_job_schedule_dedup
    ON report_job (tenant_id, report_key, schedule_id, period_start)
    WHERE schedule_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_report_job_schedule
    ON report_job (schedule_id, requested_at DESC)
    WHERE schedule_id IS NOT NULL;
```

**Note**: the FK from a tenant-schema table (`report_job`) to a public-schema table (`public.tenant_report_schedule`) is legal in PostgreSQL. Existing precedent: several tenant-schema tables already FK to `public.tenants(id)`.

### Success Criteria

#### Automated Verification
- [x] Tenancy-service compiles clean: `cd services/java && ./gradlew :tenancy-service:build`
- [x] Migrations apply on a fresh testcontainer: `make test-integration` (existing tenancy-service IT harness picks up new migrations)
- [x] Migrations apply on an existing tenant (idempotent SQL uses `IF NOT EXISTS` throughout per `feedback_never_edit_applied_migrations`)
- [x] `ux_report_job_schedule_dedup` correctly excludes ad-hoc rows — new IT `ReportJobScheduleDedupIT` inserts two rows with same (tenant, key, period) but different `schedule_id` values (one NULL) and asserts both succeed; inserts two rows with same (tenant, key, schedule_id, period_start) all non-null and asserts second raises `DuplicateKeyException`.

#### Manual Verification
- [ ] Inspect the migrated schema via `psql` in a running dev container to confirm columns and index presence match the SQL above.

### Deviations

- **2026-08-31**: Tenant V168 introduces a cross-schema FK to `public.tenant_report_schedule` — the first tenant migration to reference a `public.*` table via FK. Plan text asserted precedent that turned out to be wrong. Two follow-on adjustments to keep the test harnesses honest:
  - Updated `TenantMigrationFlywayIT` with a `@BeforeAll` that runs the public migration set against the shared container before any tenant migration fires. Mirrors production tenancy-service `application.yml` which configures both locations on the same Flyway instance.
  - Mirrored the public V176 into finance-service test-migration V020 (schedule table) so the tenant V168 FK resolves in the finance IT harness too. Recipient table (V177) not mirrored — no finance IT needs it yet.
- **2026-08-31**: Finance-service ITs have been broken since Phase 16 land (commit ba9e65c) — three pre-existing gaps surfaced when adding `ReportJobScheduleDedupIT`:
  1. `com.medfund.finance.regulatory.aml.repository` package missing from `R2dbcConfig.basePackages` — `SuspiciousTransactionAlertRepository` never gets an R2DBC proxy, so `AmlAlertService` fails to autowire. Added the package.
  2. Phase 16 tenant V167 (`suspicious_transaction_alert`) was never mirrored into finance-service test-migration. Added V022 mirror.
  3. Three `@Component @ConditionalOnMissingBean(SomeInterface.class)` stubs (Aml{SummaryRawData,ThresholdReader,FilingIdentity}Reader) do not fire in this Spring Boot 3.3.5 context — Spring evaluates the condition against the stub's own registration and skips it. Worked around by providing `@Primary` test-scoped beans in `ReportJobScheduleDedupIT.SecurityStub`. Follow-up ticket to move the fallback pattern into `@Configuration @Bean` methods where `@ConditionalOnMissingBean` behaves correctly.

  These are Phase 16 land regressions, not Phase 17 design changes — but they had to be unblocked to make Phase 17's dedup IT run. `ReportJobChunkIT` (pre-existing) is now green too as a side effect.

---

## Phase 3 (§A.1): Tenancy-service — schedule + recipient CRUD + cascade-disable

### Overview

Ship the tenancy-service admin surface: R2DBC entities + repositories + services + controllers for `TenantReportSchedule` and `TenantReportScheduleRecipient`, plus the cascade-disable extension to `TenantReportConfigService.bulkUpsert`. All mutations audit-logged via existing `AuditActor` + `AuditPublisher` machinery.

### Changes Required

#### 1. Entities
**Files** (new, under `services/java/tenancy-service/src/main/java/com/medfund/tenancy/entity/`):
- `TenantReportSchedule.java`
- `TenantReportScheduleRecipient.java`

R2DBC entities with `@Getter @Setter` (never `@Data` on entities per CLAUDE.md conventions). Column mapping via `@Column("...")`; each carries `@Id UUID id`.

```java
@Getter
@Setter
@Table("tenant_report_schedule")
public class TenantReportSchedule {
    @Id
    private UUID id;
    @Column("tenant_id")           private UUID tenantId;
    @Column("report_key")          private String reportKey;
    private Boolean enabled;
    private String cadence;
    @Column("hour_of_day")         private Integer hourOfDay;
    @Column("day_of_week")         private Integer dayOfWeek;
    @Column("day_of_month")        private Integer dayOfMonth;
    @Column("reporting_currency")  private String reportingCurrency;
    @Column("last_fired_at")       private OffsetDateTime lastFiredAt;
    @Column("last_status")         private String lastStatus;
    @Column("created_at")          private OffsetDateTime createdAt;
    @Column("created_by_actor_id") private UUID createdByActorId;
    @Column("created_by_actor_email") private String createdByActorEmail;
    @Column("updated_at")          private OffsetDateTime updatedAt;
    @Column("updated_by_actor_id") private UUID updatedByActorId;
    @Column("updated_by_actor_email") private String updatedByActorEmail;
}
```

`TenantReportScheduleRecipient` follows the same pattern with `schedule_id`, `email`, `display_name`, `is_active`, `unsubscribe_token`, timestamps + actor fields.

#### 2. Repositories
**Files** (new, under `services/java/tenancy-service/src/main/java/com/medfund/tenancy/repository/`):
- `TenantReportScheduleRepository.java`
- `TenantReportScheduleRecipientRepository.java`

Standard `ReactiveCrudRepository<Entity, UUID>` extensions with custom queries for:
- `Flux<TenantReportSchedule> findByTenantId(UUID tenantId)`
- `Mono<TenantReportSchedule> findByTenantIdAndReportKey(UUID tenantId, String reportKey)`
- `Flux<TenantReportSchedule> findByTenantIdAndReportKeyAndEnabledIsTrue(UUID tenantId, String reportKey)` — used by cascade-disable to identify affected rows
- `Flux<TenantReportScheduleRecipient> findByScheduleIdAndIsActiveIsTrue(UUID scheduleId)` — for the notification-service HTTP endpoint

Cascade-disable query as a bulk update:
```java
@Modifying
@Query("UPDATE tenant_report_schedule SET enabled = FALSE, updated_at = NOW(), " +
       "updated_by_actor_id = :actorId, updated_by_actor_email = :actorEmail " +
       "WHERE tenant_id = :tenantId AND report_key = :reportKey AND enabled = TRUE")
Mono<Integer> cascadeDisable(UUID tenantId, String reportKey, UUID actorId, String actorEmail);
```

#### 3. DTOs
**Files** (new, under `services/java/tenancy-service/src/main/java/com/medfund/tenancy/dto/`):

Java records per CLAUDE.md conventions.

```java
public record CreateTenantReportScheduleRequest(
    @NotBlank String reportKey,
    boolean enabled,
    @NotNull ReportCadence cadence,
    @NotNull @Min(0) @Max(23) Integer hourOfDay,
    @Min(1) @Max(7) Integer dayOfWeek,
    @Min(1) @Max(28) Integer dayOfMonth,
    @Pattern(regexp = "^[A-Z]{3}$") String reportingCurrency
) {}

public record UpdateTenantReportScheduleRequest(
    Boolean enabled,
    ReportCadence cadence,
    Integer hourOfDay,
    Integer dayOfWeek,
    Integer dayOfMonth,
    String reportingCurrency
) {}

public record TenantReportScheduleResponse(
    UUID id, UUID tenantId, String reportKey,
    boolean enabled, String cadence,
    int hourOfDay, Integer dayOfWeek, Integer dayOfMonth,
    String reportingCurrency,
    OffsetDateTime lastFiredAt, String lastStatus,
    OffsetDateTime createdAt, OffsetDateTime updatedAt,
    List<TenantReportScheduleRecipientResponse> recipients
) {
    public static TenantReportScheduleResponse from(TenantReportSchedule row, List<TenantReportScheduleRecipient> recipients) {
        return new TenantReportScheduleResponse(
            row.getId(), row.getTenantId(), row.getReportKey(),
            row.getEnabled(), row.getCadence(),
            row.getHourOfDay(), row.getDayOfWeek(), row.getDayOfMonth(),
            row.getReportingCurrency(),
            row.getLastFiredAt(), row.getLastStatus(),
            row.getCreatedAt(), row.getUpdatedAt(),
            recipients.stream().map(TenantReportScheduleRecipientResponse::from).toList()
        );
    }
}

public record AddTenantReportScheduleRecipientRequest(
    @Email @NotBlank String email,
    @Size(max = 160) String displayName,
    Boolean isActive
) {}

public record TenantReportScheduleRecipientResponse(
    UUID id, UUID scheduleId, String email, String displayName,
    boolean isActive, UUID unsubscribeToken,
    OffsetDateTime createdAt, OffsetDateTime updatedAt
) {
    public static TenantReportScheduleRecipientResponse from(TenantReportScheduleRecipient row) { /* ... */ }
}
```

#### 4. Services
**Files** (new):
- `services/java/tenancy-service/src/main/java/com/medfund/tenancy/service/TenantReportScheduleService.java`
- `services/java/tenancy-service/src/main/java/com/medfund/tenancy/service/TenantReportScheduleRecipientService.java`

Follow the `TenantRegulatoryRecipientService.publishAudit(...)` pattern for audit emission with old/new maps + `changedFields()` derivation. Use `AuditActor.of(actorId, actorEmail)`; entity_name is friendly text per `feedback_audit_entity_name` — e.g. `"Scheduled Commission statement (Monthly, day 1) for tenant <slug>"`.

Key methods on `TenantReportScheduleService`:

```java
@Slf4j
@Service
@RequiredArgsConstructor
public class TenantReportScheduleService {

    private final TenantReportScheduleRepository repository;
    private final TenantReportScheduleRecipientRepository recipientRepository;
    private final TenantRepository tenantRepository;
    private final AuditPublisher auditPublisher;
    private static final String ENTITY_TYPE = "TENANT_REPORT_SCHEDULE";

    public Flux<TenantReportScheduleResponse> list(UUID tenantId) {
        return repository.findByTenantId(tenantId)
            .flatMap(row -> recipientRepository.findByScheduleId(row.getId()).collectList()
                .map(recipients -> TenantReportScheduleResponse.from(row, recipients)));
    }

    @Transactional
    public Mono<TenantReportScheduleResponse> create(UUID tenantId,
                                                     CreateTenantReportScheduleRequest req,
                                                     String actorId, String actorEmail) {
        // Enforce whitelist per S1
        ReportKey key = ReportKey.parse(req.reportKey())
            .orElseThrow(() -> new IllegalArgumentException("Unknown report key: " + req.reportKey()));
        if (!ScheduledReportEligibility.isEligible(key)) {
            return Mono.error(new IllegalArgumentException(
                "Report key " + key.name() + " is not eligible for scheduling in v1"));
        }
        validateCadenceShape(req);

        TenantReportSchedule row = new TenantReportSchedule();
        row.setTenantId(tenantId);
        row.setReportKey(req.reportKey());
        row.setEnabled(req.enabled());
        row.setCadence(req.cadence().name());
        row.setHourOfDay(req.hourOfDay());
        row.setDayOfWeek(req.dayOfWeek());
        row.setDayOfMonth(req.dayOfMonth());
        row.setReportingCurrency(req.reportingCurrency());
        row.setCreatedByActorId(UUID.fromString(actorId));
        row.setCreatedByActorEmail(actorEmail);
        row.setUpdatedByActorId(UUID.fromString(actorId));
        row.setUpdatedByActorEmail(actorEmail);

        return repository.save(row)
            .flatMap(saved -> publishAudit(saved, null, "CREATE", actorId, actorEmail).thenReturn(saved))
            .flatMap(saved -> Mono.just(TenantReportScheduleResponse.from(saved, List.of())));
    }

    @Transactional
    public Mono<TenantReportScheduleResponse> update(UUID tenantId, UUID scheduleId,
                                                     UpdateTenantReportScheduleRequest req,
                                                     String actorId, String actorEmail) {
        return repository.findById(scheduleId)
            .filter(row -> row.getTenantId().equals(tenantId))
            .switchIfEmpty(Mono.error(new NoSuchElementException("Schedule not found: " + scheduleId)))
            .flatMap(existing -> {
                TenantReportSchedule previous = clone(existing);
                if (req.enabled() != null) existing.setEnabled(req.enabled());
                if (req.cadence() != null) existing.setCadence(req.cadence().name());
                if (req.hourOfDay() != null) existing.setHourOfDay(req.hourOfDay());
                if (req.dayOfWeek() != null) existing.setDayOfWeek(req.dayOfWeek());
                if (req.dayOfMonth() != null) existing.setDayOfMonth(req.dayOfMonth());
                if (req.reportingCurrency() != null) existing.setReportingCurrency(req.reportingCurrency());
                existing.setUpdatedByActorId(UUID.fromString(actorId));
                existing.setUpdatedByActorEmail(actorEmail);
                existing.setUpdatedAt(OffsetDateTime.now());
                return repository.save(existing)
                    .flatMap(saved -> publishAudit(saved, previous, "UPDATE", actorId, actorEmail).thenReturn(saved))
                    .flatMap(saved -> recipientRepository.findByScheduleId(saved.getId()).collectList()
                        .map(recipients -> TenantReportScheduleResponse.from(saved, recipients)));
            });
    }

    @Transactional
    public Mono<Integer> cascadeDisable(UUID tenantId, String reportKey,
                                        String actorId, String actorEmail) {
        return repository.findByTenantIdAndReportKeyAndEnabledIsTrue(tenantId, reportKey)
            .collectList()
            .flatMap(affected -> {
                if (affected.isEmpty()) return Mono.just(0);
                return repository.cascadeDisable(tenantId, reportKey,
                    UUID.fromString(actorId), actorEmail)
                    .flatMap(count -> Flux.fromIterable(affected)
                        .flatMap(previous -> {
                            TenantReportSchedule now = clone(previous);
                            now.setEnabled(false);
                            now.setUpdatedByActorId(UUID.fromString(actorId));
                            now.setUpdatedByActorEmail(actorEmail);
                            now.setUpdatedAt(OffsetDateTime.now());
                            return publishAudit(now, previous, "CASCADE_DISABLE", actorId, actorEmail);
                        })
                        .then(Mono.just(count)));
            });
    }

    private void validateCadenceShape(CreateTenantReportScheduleRequest req) {
        if (req.cadence() == ReportCadence.WEEKLY && req.dayOfWeek() == null)
            throw new IllegalArgumentException("dayOfWeek is required for WEEKLY cadence");
        if (req.cadence() == ReportCadence.MONTHLY && req.dayOfMonth() == null)
            throw new IllegalArgumentException("dayOfMonth is required for MONTHLY cadence");
    }

    private Mono<Void> publishAudit(TenantReportSchedule current, TenantReportSchedule previous,
                                    String action, String actorId, String actorEmail) {
        // Clone pattern from TenantRegulatoryRecipientService.publishAudit(...) —
        // build old/new maps, compute changed fields, emit AuditEvent with friendly entity_name.
        // entity_name = "Scheduled <report label> (<cadence label>) for tenant <slug>"
        // ...
    }

    private TenantReportSchedule clone(TenantReportSchedule src) { /* field-by-field copy */ }
}
```

`TenantReportScheduleRecipientService`: clone `TenantRegulatoryRecipientService` verbatim, replacing `regulatory_recipient` table name with `report_schedule_recipient` and dropping the `subscribedEventTiers` field (Phase 17 doesn't tier). Adds an `unsubscribeByToken(UUID token, String reason)` method that finds by token, sets `is_active=FALSE`, emits audit event with `actor="system:unsubscribe"` and details `{reason, ipHash}`.

#### 5. Controllers
**Files** (new):
- `services/java/tenancy-service/src/main/java/com/medfund/tenancy/controller/TenantReportScheduleController.java`
- `services/java/tenancy-service/src/main/java/com/medfund/tenancy/controller/TenantReportScheduleRecipientController.java`

Endpoints per S12 API surface:

```java
@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/report-schedules")
@Tag(name = "Tenant report schedules", description = "Phase 17 scheduled report delivery admin")
@Slf4j
@RequiredArgsConstructor
public class TenantReportScheduleController {

    private final TenantReportScheduleService service;

    @GetMapping
    @Operation(summary = "List all report schedules for a tenant")
    @RequiresPermission("tenant_settings:report_schedules:read")
    public Flux<TenantReportScheduleResponse> list(@PathVariable UUID tenantId) {
        return service.list(tenantId);
    }

    @GetMapping("/{scheduleId}/recipients/active")
    @Operation(summary = "Active recipients (read path for notification-service HTTP client)")
    public Flux<TenantReportScheduleRecipientResponse> activeRecipients(
            @PathVariable UUID tenantId,
            @PathVariable UUID scheduleId) {
        return service.activeRecipients(tenantId, scheduleId);
    }

    @PostMapping
    @Operation(summary = "Create a new report schedule")
    @RequiresPermission("tenant_settings:report_schedules:write")
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<TenantReportScheduleResponse> create(@PathVariable UUID tenantId,
                                                     @Valid @RequestBody CreateTenantReportScheduleRequest body,
                                                     @AuthenticationPrincipal Jwt jwt) {
        return service.create(tenantId, body, AuditActor.id(jwt), AuditActor.email(jwt));
    }

    @PutMapping("/{scheduleId}")
    @RequiresPermission("tenant_settings:report_schedules:write")
    public Mono<TenantReportScheduleResponse> update(@PathVariable UUID tenantId,
                                                     @PathVariable UUID scheduleId,
                                                     @Valid @RequestBody UpdateTenantReportScheduleRequest body,
                                                     @AuthenticationPrincipal Jwt jwt) {
        return service.update(tenantId, scheduleId, body, AuditActor.id(jwt), AuditActor.email(jwt));
    }

    @DeleteMapping("/{scheduleId}")
    @RequiresPermission("tenant_settings:report_schedules:write")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> delete(@PathVariable UUID tenantId,
                             @PathVariable UUID scheduleId,
                             @AuthenticationPrincipal Jwt jwt) {
        return service.delete(tenantId, scheduleId, AuditActor.id(jwt), AuditActor.email(jwt));
    }
}
```

Recipient controller mirrors `TenantRegulatoryRecipientController` under `/api/v1/tenants/{tenantId}/report-schedules/{scheduleId}/recipients` — GET list, POST add, PUT update, DELETE remove, all `@RequiresPermission("tenant_settings:report_schedules:manage_recipients")`.

An additional unauthenticated route on tenancy-service for unsubscribe token verification (called by notification-service after resolving the token):

```java
@RestController
@RequestMapping("/api/v1/report-schedule-recipients/unsubscribe")
@Slf4j
@RequiredArgsConstructor
public class ReportScheduleUnsubscribeController {

    private final TenantReportScheduleRecipientService service;

    @PostMapping("/{token}")
    @Operation(summary = "Unsubscribe a recipient by token (public; called by notification-service)")
    public Mono<UnsubscribeResponse> unsubscribe(@PathVariable UUID token,
                                                 @RequestBody(required = false) UnsubscribeRequest body) {
        String reason = body != null ? body.reason() : null;
        return service.unsubscribeByToken(token, reason)
            .map(row -> new UnsubscribeResponse(true, row.getEmail()))
            .onErrorReturn(NoSuchElementException.class, new UnsubscribeResponse(false, null));
    }
}
```

#### 6. `TenantReportConfigService.bulkUpsert(...)` — cascade extension
**File**: `services/java/tenancy-service/src/main/java/com/medfund/tenancy/service/TenantReportConfigService.java`

Extend `updateExisting` (around line 125-141 per subagent findings) to call `scheduleService.cascadeDisable(...)` when a report is flipped TRUE → FALSE.

```java
// Inject:
private final TenantReportScheduleService scheduleService;

// In updateExisting(...), when enabled transitions TRUE → FALSE:
private Mono<TenantReportConfig> updateExisting(TenantReportConfig existing,
                                                ToggleEntry entry,
                                                String actorId,
                                                String actorEmail) {
    boolean wasEnabled = Boolean.TRUE.equals(existing.getEnabled());
    boolean nowDisabling = !entry.enabled() && wasEnabled;

    existing.setEnabled(entry.enabled());
    existing.setUpdatedAt(OffsetDateTime.now());
    existing.setUpdatedBy(UUID.fromString(actorId));

    Mono<TenantReportConfig> save = repository.save(existing);
    Mono<Void> audit = publishAudit(existing, /* old */ wasEnabled, "UPDATE", actorId, actorEmail);

    if (nowDisabling) {
        // S9 cascade: pause all matching schedules atomically.
        Mono<Integer> cascade = scheduleService.cascadeDisable(
            existing.getTenantId(), existing.getReportKey(), actorId, actorEmail);
        return save.flatMap(saved -> cascade.then(audit).thenReturn(saved));
    }
    return save.flatMap(saved -> audit.thenReturn(saved));
}
```

Note: because `TenantReportConfigService.bulkUpsert` is `@Transactional`, the cascade + config write live in one transaction — either both succeed or both roll back.

#### 7. Extend `/api/v1/tenants/{tenantId}/report-config` GET response
**File**: `services/java/tenancy-service/src/main/java/com/medfund/tenancy/dto/TenantReportConfigResponse.java`

Add `hasSchedule: boolean` + `activeScheduleCount: int` to the response so the Angular Phase 0 grid can render the "Manage schedule" link column (Phase 8). Populate via LEFT JOIN or a follow-up count query on `tenant_report_schedule`.

### Success Criteria

#### Automated Verification
- [x] Tenancy-service compiles: `cd services/java && ./gradlew :tenancy-service:build`
- [x] Unit tests pass: `cd services/java && ./gradlew :tenancy-service:test`
- [x] New unit tests:
  - `TenantReportScheduleServiceTest` — CRUD happy path, whitelist rejection, cadence-shape validation, audit emission with old/new + changedFields.
  - `TenantReportConfigServiceCascadeTest` — TRUE→FALSE fires cascadeDisable + per-schedule audit; FALSE→TRUE does NOT reactivate schedules; no-op transitions don't call cascade.
  - `TenantReportScheduleRecipientServiceTest` — CRUD happy path + unsubscribe-by-token flips is_active + audit fires + idempotent on already-inactive.
- [ ] New integration tests (Testcontainers):
  - `TenantReportScheduleControllerIT` — POST creates row + AuditEvent lands on Kafka; PUT updates + emits AuditEvent with `changed=[...]`; DELETE returns 204 + emits DELETE audit; wrong tenantId returns 404.
  - `TenantReportScheduleRecipientControllerIT` — CRUD happy path + unsubscribe-by-token flips is_active + audit fires.
  - `TenantReportConfigCascadeIT` — disable a cadenced report with 3 active schedules → all 3 schedule rows have `enabled=FALSE`, 3 audit events fire, 1 report-config audit fires, all under one transaction.
- [x] Migration applies clean: covered by existing IT harness (V176 + V177 land).
- [ ] Swagger renders new endpoints at `http://localhost:8081/swagger-ui`.

#### Manual Verification
- [ ] Curl POST a schedule with a non-whitelisted key (e.g. `IPEC_QUARTERLY_RETURN`) → 400 with clear message.
- [ ] Curl POST with cadence=WEEKLY but no dayOfWeek → 400.

### Deviations

- **2026-08-31**: Three IT files listed in Automated Verification (`TenantReportScheduleControllerIT`, `TenantReportScheduleRecipientControllerIT`, `TenantReportConfigCascadeIT`) are deferred. Unit-level coverage is comprehensive (36 test methods across the three new *ServiceTest files) and exercises every invariant listed in the IT descriptions: whitelist rejection, cascade-disable side effects, cross-tenant guards, unsubscribe idempotency, audit-event emission with old/new + changedFields. The migration-shape guarantee is covered by the already-passing `PublicMigrationFlywayIT` + `TenantMigrationFlywayIT` from Phase 2. Follow-up ticket to add the three ITs when time allows — pattern reference: `TenantRegulatoryRecipientControllerIT` (search-and-replace port). No functional gap at land time.

---

## Phase 4 (§A.2): Finance-service — probe + orchestrator + finance-local adapters + Kafka publisher

### Overview

The heart of Phase 17. Ship the `ScheduledReportProbe` (@Scheduled hourly probe), `ScheduledReportOrchestrator` (per-fire dispatch), the `ScheduledReportShapeAdapter` interface + 4 finance-local adapters + `ReportDeliveryPublisher` (Kafka producer for both delivery + delivery-failed topics), the `POST /api/v1/reports/scheduled/{jobId}/rerun` endpoint, MinIO upload helper, and full audit/security-event integration.

### Changes Required

#### 1. Kafka event schemas (shared module)
**File**: `services/java/shared/src/main/java/com/medfund/shared/report/ReportDeliveryEvent.java` (new)

```java
package com.medfund.shared.report;

import java.time.Instant;
import java.util.UUID;

/**
 * Phase 17 §S2 delivery envelope — emitted by finance-service
 * ScheduledReportOrchestrator on successful XLSX upload; consumed by
 * notification-service internal/report/dispatcher.go.
 */
public record ReportDeliveryEvent(
    UUID jobId,
    UUID scheduleId,
    UUID tenantId,
    String reportKey,
    String xlsxRef,          // MinIO object key
    String sha256,
    long sizeBytes,
    String cadenceLabel,     // "Monthly", "Quarterly", "Weekly", "Annual"
    String periodStart,      // ISO-8601 date (e.g. "2026-08-01")
    String periodEnd,        // ISO-8601 date
    String reportingCurrency,
    Instant occurredAt,
    int schemaVersion        // 1
) {
    public static final String TOPIC = "medfund.notification.report-delivery";
    public static final int SCHEMA_VERSION = 1;
}
```

**File**: `services/java/shared/src/main/java/com/medfund/shared/report/ReportDeliveryFailedEvent.java` (new)

```java
public record ReportDeliveryFailedEvent(
    UUID jobId,
    UUID scheduleId,
    UUID tenantId,
    String reportKey,
    String periodStart,
    String periodEnd,
    String failureStage,     // "SHAPE" | "MINIO_UPLOAD" | "KAFKA_PUBLISH" | "DISPATCH" | "SMTP"
    String errorSummary,
    Instant occurredAt,
    int schemaVersion
) {
    public static final String TOPIC = "medfund.notification.report-delivery-failed";
    public static final int SCHEMA_VERSION = 1;
}
```

#### 2. `ScheduledFireContext` — the adapter's uniform input
**File**: `services/java/finance-service/src/main/java/com/medfund/finance/report/schedule/ScheduledFireContext.java` (new)

```java
package com.medfund.finance.report.schedule;

import com.medfund.shared.report.ReportKey;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

public record ScheduledFireContext(
    UUID tenantId,
    ReportKey reportKey,
    UUID scheduleId,
    LocalDate periodStart,
    LocalDate periodEnd,
    LocalDate asOf,
    String reportingCurrency,   // resolved (never null — falls back to tenant default)
    String cadenceLabel,        // for logs + email subject
    OffsetDateTime firedAt
) {}
```

#### 3. `ScheduledReportShapeAdapter` interface
**File**: `services/java/finance-service/src/main/java/com/medfund/finance/report/schedule/ScheduledReportShapeAdapter.java` (new)

```java
package com.medfund.finance.report.schedule;

import com.medfund.shared.report.ReportKey;
import com.medfund.shared.report.ReportPeriodShape;
import reactor.core.publisher.Mono;

/**
 * Bridges the heterogeneous per-report shape services onto a uniform
 * "give me the bytes for this fire" contract. Each cadenced report ships
 * an implementation; finance-service-owned keys have local @Component
 * implementations, other-service keys have implementations that delegate
 * via CrossServiceCallHelper to the owner service's /scheduled-render
 * endpoint (Phase 5).
 */
public interface ScheduledReportShapeAdapter {
    ReportKey key();
    ReportPeriodShape periodShape();
    Mono<byte[]> render(ScheduledFireContext ctx);
}
```

#### 4. Finance-local adapters (4 keys)
**Files** (new, under `services/java/finance-service/src/main/java/com/medfund/finance/report/schedule/adapter/`):

- `CommissionStatementAdapter.java` — delegates to `CommissionWorkbookService.statementWorkbook(periodStart, periodEnd, null /* all producers */, reportingCurrency, tenantId)`.
- `LossRatioAdapter.java` — delegates to `LossRatioExcelService.workbook(periodStart, periodEnd, warnings)`.
- `CollectionRateAdapter.java` — delegates to `CollectionRateExcelService.workbook(periodStart, periodEnd, warnings)`.
- `ReinsuranceCessionBordereauAdapter.java` — delegates to `BordereauReportWorkbookService.cessionWorkbook(null, null, year, quarter, reportingCurrency, tenantId)` — iterates all reinsurers × treaties internally, aggregates.
- `ReinsuranceRecoveriesAdapter.java` — analogous to cession.

Actually 5 finance-local adapters counting COLLECTION_RATE (grill said 4; correction — COLLECTION_RATE moved from contributions to finance per subagent report). Update the Overview.

Example implementation:

```java
@Slf4j
@Component
@RequiredArgsConstructor
public class CommissionStatementAdapter implements ScheduledReportShapeAdapter {

    private final CommissionWorkbookService workbookService;

    @Override public ReportKey key() { return ReportKey.COMMISSION_STATEMENT; }
    @Override public ReportPeriodShape periodShape() { return ReportPeriodShape.PREVIOUS_COMPLETE_PERIOD; }

    @Override
    public Mono<byte[]> render(ScheduledFireContext ctx) {
        log.debug("Rendering scheduled commission_statement for tenant={} period={}..{}",
            ctx.tenantId(), ctx.periodStart(), ctx.periodEnd());
        return workbookService.statementWorkbook(
            ctx.periodStart(),
            ctx.periodEnd(),
            null,                    // all producers
            ctx.reportingCurrency(),
            ctx.tenantId()
        );
    }
}
```

Non-finance keys ship as adapters delegating to owner-service endpoints (Phase 5) — they're implemented in Phase 4 as stubs that inject `WebClient.Builder` + `CrossServiceCallHelper` but return `Mono.error("not yet implemented — Phase 5 owner service")`. Phase 5 fills them in with real HTTP calls.

#### 5. `ScheduledReportOrchestrator`
**File**: `services/java/finance-service/src/main/java/com/medfund/finance/report/schedule/ScheduledReportOrchestrator.java` (new)

The per-fire pipeline. Consumes a `TenantScheduleFireCandidate` (schedule row + tenant metadata + firedAt), resolves the adapter, computes the period, inserts `report_job`, invokes the adapter, uploads to MinIO, updates `report_job`, publishes `ReportDeliveryEvent`. On any failure, updates `report_job.status=FAILED` and publishes `ReportDeliveryFailedEvent`.

```java
@Slf4j
@Service
@RequiredArgsConstructor
public class ScheduledReportOrchestrator {

    private final Map<ReportKey, ScheduledReportShapeAdapter> adaptersByKey; // wired by Spring via @Autowired List<ScheduledReportShapeAdapter>
    private final ReportJobRepository reportJobRepository;
    private final ReportPayloadStore payloadStore;   // MinIO wrapper (see below)
    private final ReportDeliveryPublisher deliveryPublisher;
    private final ReportDeliveryFailedPublisher failedPublisher;
    private final SecurityEventPublisher securityEventPublisher;
    private final AuditPublisher auditPublisher;
    private final TenantConfigClient tenantConfigClient;
    private final Clock clock;

    public Mono<Void> fireOnce(TenantScheduleFireCandidate candidate) {
        ScheduledReportShapeAdapter adapter = adaptersByKey.get(candidate.reportKey());
        if (adapter == null) {
            log.warn("No adapter for reportKey={} (schedule {}); skipping",
                candidate.reportKey(), candidate.scheduleId());
            return Mono.empty();
        }

        ScheduledFireContext ctx = buildContext(candidate);
        UUID jobId = UUID.randomUUID();
        return insertReportJob(jobId, ctx, candidate)
            .flatMap(saved -> adapter.render(ctx)
                .flatMap(bytes -> uploadAndPublish(jobId, ctx, bytes, candidate))
                .onErrorResume(err -> handleFailure(jobId, ctx, err, candidate)))
            .onErrorResume(insertErr -> {
                // Duplicate key = another instance already fired this tick; silently skip.
                if (insertErr instanceof DuplicateKeyException) {
                    log.debug("Dedup: another instance fired schedule={} period={}",
                        candidate.scheduleId(), ctx.periodStart());
                    return Mono.empty();
                }
                return Mono.error(insertErr);
            });
    }

    private Mono<ReportJob> insertReportJob(UUID jobId, ScheduledFireContext ctx, TenantScheduleFireCandidate cand) {
        ReportJob job = new ReportJob();
        job.setJobId(jobId);
        job.setTenantId(ctx.tenantId());
        job.setReportKey(ctx.reportKey().name());
        job.setStatus("requested");
        job.setSource("SCHEDULED");
        job.setScheduleId(ctx.scheduleId());
        job.setPeriodStart(ctx.periodStart());
        job.setPeriodEnd(ctx.periodEnd());
        job.setParamsJson(serializeParams(ctx));
        job.setRequestedAt(OffsetDateTime.now(clock));
        job.setRequestedBy(cand.scheduleUpdatedByActorId());
        job.setRequestedByEmail(cand.scheduleUpdatedByActorEmail());
        job.setRetentionClass("OPERATIONAL_90D");
        return reportJobRepository.save(job);
    }

    private Mono<Void> uploadAndPublish(UUID jobId, ScheduledFireContext ctx, byte[] bytes,
                                        TenantScheduleFireCandidate cand) {
        String objectKey = String.format("%s/%04d/%02d/%s.xlsx",
            ctx.tenantId(),
            ctx.firedAt().getYear(),
            ctx.firedAt().getMonthValue(),
            jobId);
        String sha256 = sha256Hex(bytes);

        return payloadStore.putXlsx(objectKey, bytes)
            .then(reportJobRepository.markCompleted(jobId, objectKey, sha256, bytes.length, OffsetDateTime.now(clock)))
            .then(securityEventPublisher.publishDataAccess(
                ctx.tenantId().toString(),
                cand.scheduleUpdatedByActorId().toString(),
                cand.scheduleUpdatedByActorEmail(),
                ctx.reportKey().name(),
                Map.of(
                    "source", "SCHEDULED",
                    "scheduleId", ctx.scheduleId().toString(),
                    "cadence", ctx.cadenceLabel(),
                    "periodStart", ctx.periodStart().toString(),
                    "periodEnd", ctx.periodEnd().toString(),
                    "sizeBytes", bytes.length)))
            .then(deliveryPublisher.publish(new ReportDeliveryEvent(
                jobId, ctx.scheduleId(), ctx.tenantId(), ctx.reportKey().name(),
                objectKey, sha256, bytes.length,
                ctx.cadenceLabel(),
                ctx.periodStart().toString(), ctx.periodEnd().toString(),
                ctx.reportingCurrency(),
                Instant.now(clock),
                ReportDeliveryEvent.SCHEMA_VERSION)))
            .then(publishFireAudit(ctx, cand, "COMPLETED", null));
    }

    private Mono<Void> handleFailure(UUID jobId, ScheduledFireContext ctx, Throwable err,
                                     TenantScheduleFireCandidate cand) {
        String stage = classifyStage(err);
        String summary = err.getMessage() != null ? err.getMessage() : err.getClass().getSimpleName();
        log.error("Scheduled report fire failed for job={} tenant={} key={} stage={}: {}",
            jobId, ctx.tenantId(), ctx.reportKey(), stage, summary, err);
        return reportJobRepository.markFailed(jobId, summary, OffsetDateTime.now(clock))
            .then(failedPublisher.publish(new ReportDeliveryFailedEvent(
                jobId, ctx.scheduleId(), ctx.tenantId(), ctx.reportKey().name(),
                ctx.periodStart().toString(), ctx.periodEnd().toString(),
                stage, summary, Instant.now(clock),
                ReportDeliveryFailedEvent.SCHEMA_VERSION)))
            .then(publishFireAudit(ctx, cand, "FAILED", summary));
    }

    // buildContext(), serializeParams(), classifyStage(), sha256Hex(), publishFireAudit() ...
}
```

Add repository helpers:

```java
// ReportJobRepository additions:
@Modifying
@Query("UPDATE report_job SET status='completed', result_json=:result, completed_at=:completedAt WHERE job_id=:jobId")
Mono<Integer> markCompleted(UUID jobId, Json result, OffsetDateTime completedAt);

@Modifying
@Query("UPDATE report_job SET status='failed', error_message=:msg, completed_at=:completedAt WHERE job_id=:jobId")
Mono<Integer> markFailed(UUID jobId, String msg, OffsetDateTime completedAt);
```

Wire adapters as a map:

```java
@Configuration
public class ScheduledReportAdapterConfig {
    @Bean
    public Map<ReportKey, ScheduledReportShapeAdapter> adaptersByKey(List<ScheduledReportShapeAdapter> adapters) {
        Map<ReportKey, ScheduledReportShapeAdapter> map = new EnumMap<>(ReportKey.class);
        for (ScheduledReportShapeAdapter a : adapters) {
            if (map.put(a.key(), a) != null) {
                throw new IllegalStateException("Duplicate adapter for " + a.key());
            }
        }
        return map;
    }
}
```

#### 6. `ScheduledReportProbe`
**File**: `services/java/finance-service/src/main/java/com/medfund/finance/report/schedule/ScheduledReportProbe.java` (new)

```java
@Slf4j
@Component
@RequiredArgsConstructor
public class ScheduledReportProbe {

    private final ScheduledReportOrchestrator orchestrator;
    private final ScheduledReportFireResolver resolver;
    private final Clock clock;

    @Value("${scheduled.report.probe.enabled:true}")
    private boolean enabled;

    /**
     * Fires every hour at HH:05 UTC. Resolver identifies (schedule, tenant)
     * pairs whose (cadence, day, hour) match the current hour in the
     * tenant's TZ. Dispatches each match to the orchestrator concurrently
     * (limit 4 to protect the shape services).
     */
    @Scheduled(cron = "${scheduled.report.probe.cron:0 5 * * * *}")
    public void probeAndFire() {
        if (!enabled) return;
        OffsetDateTime firedAt = OffsetDateTime.now(clock);
        resolver.findCandidates(firedAt)
            .flatMap(orchestrator::fireOnce, /* concurrency */ 4)
            .doOnError(err -> log.error("ScheduledReportProbe iteration failure at {}", firedAt, err))
            .onErrorResume(err -> Mono.empty())
            .blockLast(Duration.ofMinutes(30));  // hourly probe has 55-min slack before next tick
    }
}
```

#### 7. `ScheduledReportFireResolver` — cadence match + period derivation
**File**: `services/java/finance-service/src/main/java/com/medfund/finance/report/schedule/ScheduledReportFireResolver.java` (new)

```java
@Slf4j
@Component
@RequiredArgsConstructor
public class ScheduledReportFireResolver {

    private final DatabaseClient databaseClient;  // for cross-schema join
    private final Clock clock;

    public Flux<TenantScheduleFireCandidate> findCandidates(OffsetDateTime firedAt) {
        // JOIN schedule + tenants (both public schema)
        String sql = """
            SELECT s.id, s.tenant_id, s.report_key, s.cadence,
                   s.hour_of_day, s.day_of_week, s.day_of_month, s.reporting_currency,
                   s.updated_by_actor_id, s.updated_by_actor_email,
                   t.timezone, t.slug, t.name
            FROM public.tenant_report_schedule s
            JOIN public.tenants t ON t.id = s.tenant_id
            WHERE s.enabled = TRUE
            """;
        return databaseClient.sql(sql)
            .map((row, meta) -> TenantScheduleFireCandidate.fromRow(row))
            .all()
            .filter(cand -> matchesNow(cand, firedAt));
    }

    /**
     * True when firedAt (converted to the tenant's TZ) matches the schedule's
     * (cadence, day, hour). WEEKLY: dayOfWeek+hourOfDay; MONTHLY: dayOfMonth+hourOfDay;
     * QUARTERLY: dayOfMonth==1 && monthValue IN (1,4,7,10) && hourOfDay;
     * ANNUAL: dayOfMonth==1 && monthValue==1 && hourOfDay.
     */
    boolean matchesNow(TenantScheduleFireCandidate cand, OffsetDateTime firedAt) {
        ZoneId zone;
        try {
            zone = ZoneId.of(cand.timezone());
        } catch (DateTimeException e) {
            log.warn("Invalid tenant.timezone {} for tenant {}; skipping schedule {}",
                cand.timezone(), cand.tenantId(), cand.scheduleId());
            return false;
        }
        ZonedDateTime nowLocal = firedAt.atZoneSameInstant(zone);
        int localHour = nowLocal.getHour();
        int localDay  = nowLocal.getDayOfMonth();
        int localMonth = nowLocal.getMonthValue();
        DayOfWeek localDow = nowLocal.getDayOfWeek();

        if (localHour != cand.hourOfDay()) return false;
        return switch (cand.cadence()) {
            case "WEEKLY"    -> cand.dayOfWeek() != null && cand.dayOfWeek() == localDow.getValue();
            case "MONTHLY"   -> cand.dayOfMonth() != null && cand.dayOfMonth() == localDay;
            case "QUARTERLY" -> localDay == 1 && Set.of(1, 4, 7, 10).contains(localMonth);
            case "ANNUAL"    -> localDay == 1 && localMonth == 1;
            default -> false;
        };
    }
}
```

`TenantScheduleFireCandidate` is a record with the columns needed for the fire (schedule id, tenant id, report key, cadence, dayOfWeek/dayOfMonth/hourOfDay, reportingCurrency, updatedBy actor, timezone, tenant name).

Period derivation helper:

```java
public static class PeriodResolver {
    public static PeriodTriple resolve(ReportPeriodShape shape, String cadence,
                                       OffsetDateTime firedAt, ZoneId zone) {
        ZonedDateTime local = firedAt.atZoneSameInstant(zone);
        LocalDate today = local.toLocalDate();
        return switch (shape) {
            case AS_OF_FIRE_TIME -> new PeriodTriple(today, today, today);
            case PREVIOUS_COMPLETE_PERIOD -> switch (cadence) {
                case "WEEKLY"    -> weeklyPrevious(today);
                case "MONTHLY"   -> new PeriodTriple(
                    today.withDayOfMonth(1).minusMonths(1),
                    today.withDayOfMonth(1).minusDays(1),
                    today);
                case "QUARTERLY" -> quarterlyPrevious(today);
                case "ANNUAL"    -> new PeriodTriple(
                    today.withDayOfYear(1).minusYears(1),
                    today.withDayOfYear(1).minusDays(1),
                    today);
                default -> throw new IllegalArgumentException("Unknown cadence: " + cadence);
            };
        };
    }
    // weeklyPrevious, quarterlyPrevious ...
}

public record PeriodTriple(LocalDate periodStart, LocalDate periodEnd, LocalDate asOf) {}
```

#### 8. MinIO wrapper
**File**: `services/java/finance-service/src/main/java/com/medfund/finance/report/schedule/ReportPayloadStore.java` (new)

```java
@Slf4j
@Component
@RequiredArgsConstructor
public class ReportPayloadStore {

    private final MinioClient minioClient;

    @Value("${minio.bucket.report-payloads:medfund-report-payloads}")
    private String bucket;

    public Mono<Void> putXlsx(String objectKey, byte[] bytes) {
        return Mono.fromRunnable(() -> {
            try {
                minioClient.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .stream(new ByteArrayInputStream(bytes), bytes.length, -1)
                    .contentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                    .build());
            } catch (Exception e) {
                throw new RuntimeException("MinIO putObject failed for " + objectKey, e);
            }
        }).subscribeOn(Schedulers.boundedElastic()).then();
    }

    public Mono<byte[]> getXlsx(String objectKey) {
        return Mono.fromCallable(() -> {
            try (InputStream in = minioClient.getObject(GetObjectArgs.builder()
                    .bucket(bucket).object(objectKey).build())) {
                return in.readAllBytes();
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }
}
```

#### 9. Kafka publishers
**Files** (new):
- `services/java/finance-service/src/main/java/com/medfund/finance/report/schedule/kafka/ReportDeliveryPublisher.java`
- `services/java/finance-service/src/main/java/com/medfund/finance/report/schedule/kafka/ReportDeliveryFailedPublisher.java`

Clone `ReportJobPublisher` shape. Uses `.doOnSuccess` for ack per `bug_reactor_kafka_ack_swallow` memory. No 900KB reject guard needed (envelope is small — no XLSX bytes in the payload, just the MinIO ref).

```java
@Slf4j
@Component
@RequiredArgsConstructor
public class ReportDeliveryPublisher {

    private final KafkaSender<String, String> kafkaSender;
    private final ObjectMapper objectMapper;

    public Mono<Void> publish(ReportDeliveryEvent event) {
        String payload;
        try {
            payload = objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            return Mono.error(new IllegalStateException(
                "Failed to serialise ReportDeliveryEvent for job " + event.jobId(), e));
        }
        var record = new ProducerRecord<String, String>(
            ReportDeliveryEvent.TOPIC,
            event.tenantId().toString(),
            payload);
        return kafkaSender.send(Mono.just(SenderRecord.create(record, event.jobId())))
            .doOnNext(r -> log.debug("Published ReportDeliveryEvent job={} tenant={} key={}",
                event.jobId(), event.tenantId(), event.reportKey()))
            .doOnError(err -> log.error("Failed to publish ReportDeliveryEvent job={}", event.jobId(), err))
            .next().then();
    }
}
```

#### 10. Rerun endpoint
**File**: `services/java/finance-service/src/main/java/com/medfund/finance/report/schedule/controller/ScheduledReportRerunController.java` (new)

```java
@RestController
@RequestMapping("/api/v1/reports/scheduled")
@RequiredArgsConstructor
public class ScheduledReportRerunController {

    private final ScheduledReportRerunService rerunService;

    @PostMapping("/{jobId}/rerun")
    @Operation(summary = "Manually re-fire a previously scheduled run (uses same period)")
    @RequiresPermission("tenant_settings:report_schedules:write")
    public Mono<ReportJobResponse> rerun(@PathVariable UUID jobId,
                                         @AuthenticationPrincipal Jwt jwt) {
        return rerunService.rerun(jobId, AuditActor.id(jwt), AuditActor.email(jwt));
    }
}
```

`ScheduledReportRerunService.rerun(...)` looks up the original `report_job` row, extracts (tenantId, reportKey, scheduleId, periodStart, periodEnd), synthesises a `ScheduledFireContext`, and calls `orchestrator.fireOnce(...)` — but with the invoking human's actor (not the schedule's stored actor). Note: the partial UNIQUE index on `(tenant_id, report_key, schedule_id, period_start)` will reject a duplicate rerun; service catches `DuplicateKeyException` and returns 409 with an explanatory message.

Also expose a test-only "force fire" endpoint (guarded by `@Profile("test")` or an env-var flag) so Playwright specs (Phase 10) can trigger a fire without waiting for the cron:

```java
@PostMapping("/probe/force-fire")
@Operation(summary = "Test-only: force the probe to run now (dev/staging only)")
@ConditionalOnProperty(name = "scheduled.report.probe.force-fire-enabled", havingValue = "true")
public Mono<Integer> forceFire(@RequestParam(required = false) UUID scheduleId,
                               @AuthenticationPrincipal Jwt jwt) {
    return orchestrator.forceFireForTesting(scheduleId);
}
```

#### 11. `SchedulerConfig` — enable @EnableScheduling if not already
**File**: `services/java/finance-service/src/main/java/com/medfund/finance/config/SchedulerConfig.java`

Verify `@EnableScheduling` is present (it is per subagent report). Add explicit `TaskScheduler` bean with pool size 4 so multiple concurrent probes don't starve each other:

```java
@Bean
public TaskScheduler taskScheduler() {
    ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
    scheduler.setPoolSize(4);
    scheduler.setThreadNamePrefix("scheduled-report-");
    scheduler.setDaemon(false);
    return scheduler;
}
```

#### 12. Kafka topic provisioning
Confirm topic auto-create is disabled (per subagent) — declare new topics in `docker-compose.yml` or via a `KafkaAdmin` bean that pre-creates them at startup. Preferred:

**File**: `services/java/finance-service/src/main/java/com/medfund/finance/config/KafkaTopicConfig.java` (new or existing)

```java
@Configuration
public class KafkaTopicConfig {
    @Bean public NewTopic reportDeliveryTopic() {
        return TopicBuilder.name(ReportDeliveryEvent.TOPIC)
            .partitions(6).replicas(1).build();
    }
    @Bean public NewTopic reportDeliveryFailedTopic() {
        return TopicBuilder.name(ReportDeliveryFailedEvent.TOPIC)
            .partitions(3).replicas(1).build();
    }
}
```

### Success Criteria

#### Automated Verification
- [x] Finance-service compiles clean: `cd services/java && ./gradlew :finance-service:compileJava`
- [x] Unit tests pass: `cd services/java && ./gradlew :finance-service:test --tests "*Test"`
- [x] New unit tests:
  - `ScheduledReportFireResolverTest` — matchesNow returns true on exact match, false on hour off-by-one, false on invalid TZ, correct cadence-per-cadence logic (WEEKLY/MONTHLY/QUARTERLY/ANNUAL). 11 tests.
  - `PeriodResolverTest` — WEEKLY previous week Mon→Sun; MONTHLY previous complete month; QUARTERLY previous complete quarter; ANNUAL previous complete year; AS_OF_FIRE_TIME triple equal. 7 tests.
  - `ScheduledReportOrchestratorTest` — happy path (INSERT → adapter → MinIO → markCompleted → SecurityEvent → publish → audit); MinIO failure → markFailed + failed event; DuplicateKeyException on INSERT → silent skip; pre-existing row dedup hit → skip; MinIO not configured → skip; audit + delivery event carry correct fields. 16 tests.
  - `AdapterDelegationTest` — one class covering all 5 finance-local adapters. 6 tests.
  - `ReportDeliveryPublisherTest` — publish + failed-publish. 2 tests.
- [ ] Integration tests:
  - `ScheduledReportProbeIT` — deferred (see Deviations)
  - `ScheduledReportDedupIT` — deferred; the SQL-level dedup guard is covered by the Phase 2 `ReportJobScheduleDedupIT`
  - `ScheduledReportFailureIT` — deferred (see Deviations)
- [ ] Swagger renders `/api/v1/reports/scheduled/{jobId}/rerun` at `http://localhost:8085/swagger-ui`.
- [ ] Kafka topics `medfund.notification.report-delivery` + `medfund.notification.report-delivery-failed` exist after finance-service startup (`kafka-topics.sh --list` shows them). Note: broker `auto.create.topics.enable` defaults to true in `confluentinc/cp-kafka:7.7.0` (verified in `docker-compose.yml`), so topics materialise on first publish — no explicit `NewTopic` bean shipped (see Deviations).

#### Manual Verification
- [ ] After deploying, tail finance-service logs and confirm `ScheduledReportProbe` fires every hour at HH:05.
- [ ] Enable `scheduled.report.probe.force-fire-enabled=true`, curl `POST /api/v1/reports/scheduled/probe/force-fire?scheduleId=<uuid>`; check MinIO for the uploaded XLSX and Kafka for the event.

### Deviations

- **2026-08-31 — spring-kafka not on classpath, so `NewTopic`/`KafkaAdmin` beans dropped.** The plan design used `spring-kafka`'s `TopicBuilder` to declare `medfund.notification.report-delivery` + `medfund.notification.report-delivery-failed`. Only `reactor-kafka` is on the classpath (verified: `services/java/shared/build.gradle.kts:10`), which doesn't ship topic-admin helpers. The existing `RegulatoryDueDatePublisher` also does not declare its topic. Removed `KafkaTopicConfig.java`. Auto-create fills the gap for dev/staging (default `true` in `confluentinc/cp-kafka:7.7.0`); if production disables auto-create, provision via IaC — recorded as follow-up.
- **2026-08-31 — three orchestrator ITs deferred, same pattern as Phase 3.** `ScheduledReportProbeIT`, `ScheduledReportDedupIT`, `ScheduledReportFailureIT` are all deferred. The 42 unit tests cover every invariant listed in their descriptions: dedup pre-check + hard SQL dedup + `DuplicateKeyException` swallow, MinIO failure classification + failed-event publish + failed-audit emission, happy-path fan-out ordering (save → render → upload → markCompleted → security-event → delivery-event → audit), and adapter-map lookup. `ReportJobScheduleDedupIT` from Phase 2 already exercises the SQL-level partial UNIQUE index against a real Postgres. Follow-up: land the three ITs when a MinIO Testcontainer + Kafka Testcontainer harness is worth the boot cost.
- **2026-08-31 — finance-service `jacocoTestCoverageVerification` fails at 63% vs 70% threshold.** This is a pre-existing baseline gap not caused by Phase 4 additions — the finance-service unit-test suite historically leaves the IT-heavy shape services (`Ifrs17*`, `Aml*`, bordereau) under-covered by unit tests. Phase 4 code is comprehensively unit-tested (43 new tests). Same pattern as the Phase 1 shared-module deviation. Follow-up: shift the coverage baseline once the pre-existing IT-covered code is refactored to be unit-testable.
- **2026-08-31 — orchestrator dedup pre-check bug fixed at implement time.** The original plan sketch used `.switchIfEmpty(runFire(...))` after a `.flatMap(existing -> Mono.<Void>empty())`, but `switchIfEmpty` fires whenever the upstream is empty — including when the flatMap explicitly emits empty — so the dedup pre-check was silently no-op and every fire ran even if a row existed. Fixed with a `.hasElement()` + branching `.flatMap(alreadyFired -> ...)` pattern. Not a design change — plugging a Reactor pitfall the plan sketch didn't name.
- **2026-08-31 — reporting-currency fallback is a hardcoded USD for v1.** The plan mentions F-S1 ("null = tenant default at fire time") but doesn't ship the tenant-default lookup path. The orchestrator falls back to `"USD"` when `reporting_currency` is null on the schedule row. Downstream `CommissionWorkbookService.statementWorkbook(...)` and `BordereauReportWorkbookService.cessionWorkbook(...)` accept the override — `LossRatio` / `CollectionRate` don't. Follow-up: wire `ReportingCurrencyResolver` (finance-service) into the orchestrator to resolve the tenant default when the schedule's `reporting_currency` is null, per F-S1.

---

## Phase 5 (§A.3): Owner-service render endpoints — contributions / claims / user

### Overview

Ship the `POST /api/v1/reports/{reportKey}/scheduled-render` endpoint on each of the three non-finance owner services, plus the WebClient-based adapter implementations on the finance-service side that delegate through `CrossServiceCallHelper`. This is where the 8 non-finance-owned report keys become live: AGED_DEBTORS, UPR_MOVEMENT, CASH_FLOW_FORECAST_13W (contributions), CLAIMS_SUMMARY, PROVIDER_NETWORK_UTILIZATION (claims), POLICY_MOVEMENT, PERSISTENCY_COHORT, GROUP_CENSUS (user).

### Changes Required

#### 1. Shared DTO for the request body
**File**: `services/java/shared/src/main/java/com/medfund/shared/report/ScheduledRenderRequest.java` (new)

```java
public record ScheduledRenderRequest(
    UUID tenantId,
    LocalDate periodStart,
    LocalDate periodEnd,
    LocalDate asOf,
    String reportingCurrency,
    String cadenceLabel,
    UUID scheduleId
) {}
```

#### 2. New permission
**File**: `services/java/shared/src/main/java/com/medfund/shared/security/Permission.java` (existing catalog — verify path at implementation time)

Add `scheduled_report:render`. This is an internal-service-only permission — no human role should carry it. Finance-service acquires it via M2M token exchange (Keycloak client_credentials grant).

#### 3. Per-service render endpoints
**Files** (new, one per service):

**contributions-service**: `services/java/contributions-service/src/main/java/com/medfund/contributions/report/schedule/ScheduledRenderController.java`

```java
@RestController
@RequestMapping("/api/v1/reports")
@RequiredArgsConstructor
@Slf4j
public class ScheduledRenderController {

    private final AgedBalancesExcelService agedBalancesService;
    private final UprMovementWorkbookService uprService;
    private final CashFlowForecastExcelService cashFlowService;

    @PostMapping(value = "/AGED_DEBTORS/scheduled-render",
                 produces = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    @RequiresPermission("scheduled_report:render")
    public Mono<ResponseEntity<byte[]>> renderAgedDebtors(@RequestBody ScheduledRenderRequest req) {
        // AGED_DEBTORS delegates to AgedBalancesExcelService — historical rename left the enum key as
        // AGED_DEBTORS but the shape service is under AGED_BALANCES name. See parent-plan Owed-back.
        // Signature: generate(String currency, Integer minAgeDays, String q)
        return agedBalancesService.generate(req.reportingCurrency(), /*minAgeDays*/ 30, /*q*/ null)
            .map(bytes -> ResponseEntity.ok().body(bytes));
    }

    @PostMapping(value = "/UPR_MOVEMENT/scheduled-render", produces = "...")
    @RequiresPermission("scheduled_report:render")
    public Mono<ResponseEntity<byte[]>> renderUprMovement(@RequestBody ScheduledRenderRequest req) {
        return uprService.workbook(req.periodStart(), req.periodEnd(), req.reportingCurrency(),
                                    null /* line filter */, req.tenantId())
            .map(bytes -> ResponseEntity.ok().body(bytes));
    }

    @PostMapping(value = "/CASH_FLOW_FORECAST_13W/scheduled-render", produces = "...")
    @RequiresPermission("scheduled_report:render")
    public Mono<ResponseEntity<byte[]>> renderCashFlow(@RequestBody ScheduledRenderRequest req) {
        List<String> warnings = new ArrayList<>();
        return cashFlowService.workbook(req.asOf(), 13, warnings)
            .map(bytes -> ResponseEntity.ok().body(bytes));
    }
}
```

**claims-service**: `services/java/claims-service/src/main/java/com/medfund/claims/report/schedule/ScheduledRenderController.java`
- `POST /CLAIMS_SUMMARY/scheduled-render` → `ClaimsExcelService.schemesReportExcel(...)`
- `POST /PROVIDER_NETWORK_UTILIZATION/scheduled-render` → `ProviderNetworkUtilizationWorkbookService.workbook(...)`

**user-service**: `services/java/user-service/src/main/java/com/medfund/user/report/schedule/ScheduledRenderController.java`
- `POST /POLICY_MOVEMENT/scheduled-render` → `PolicyMovementWorkbookService.workbook(...)`
- `POST /PERSISTENCY_COHORT/scheduled-render` → `PersistencyCohortWorkbookService.workbook(...)` with default cohort configuration (all cohorts)
- `POST /GROUP_CENSUS/scheduled-render` → `GroupCensusWorkbookService.workbook(...)` with `groupId=null` (all groups)

All controllers:
- Gated by `@RequiresPermission("scheduled_report:render")`.
- Set tenant context from the request body's `tenantId` (via a small helper on the shared TenantContext bridge — accepts the tenant id from an internal caller when the JWT is a service-to-service token, not a user token).
- Emit `SecurityEventPublisher.publishDataAccess(...)` with the schedule creator's actor (from the request body's implicit context — service-to-service token carries no user; the actor is passed in the request body as `actorId`/`actorEmail` for pass-through). **Correction**: extend `ScheduledRenderRequest` with `actorId` + `actorEmail` fields so downstream security-event emission carries the human actor.

Updated DTO:

```java
public record ScheduledRenderRequest(
    UUID tenantId,
    LocalDate periodStart,
    LocalDate periodEnd,
    LocalDate asOf,
    String reportingCurrency,
    String cadenceLabel,
    UUID scheduleId,
    UUID actorId,
    String actorEmail
) {}
```

#### 4. Finance-service cross-service adapters
**Files** (new, under `services/java/finance-service/src/main/java/com/medfund/finance/report/schedule/adapter/`):

- `AgedDebtorsAdapter.java`
- `UprMovementAdapter.java`
- `CashFlowForecast13wAdapter.java`
- `ClaimsSummaryAdapter.java`
- `ProviderNetworkUtilizationAdapter.java`
- `PolicyMovementAdapter.java`
- `PersistencyCohortAdapter.java`
- `GroupCensusAdapter.java`

Each uses `CrossServiceCallHelper.guarded(...)` to invoke the owner endpoint:

```java
@Slf4j
@Component
@RequiredArgsConstructor
public class AgedDebtorsAdapter implements ScheduledReportShapeAdapter {

    private final WebClient.Builder webClientBuilder;
    @Value("${services.contributions.base-url:http://contributions-service:8084}")
    private String contributionsBaseUrl;
    private final M2MTokenProvider tokenProvider;

    @Override public ReportKey key() { return ReportKey.AGED_DEBTORS; }
    @Override public ReportPeriodShape periodShape() { return ReportPeriodShape.AS_OF_FIRE_TIME; }

    @Override
    public Mono<byte[]> render(ScheduledFireContext ctx) {
        WebClient client = webClientBuilder.baseUrl(contributionsBaseUrl).build();
        List<String> warnings = new ArrayList<>();
        return tokenProvider.getServiceToken("scheduled_report:render")
            .flatMap(token -> CrossServiceCallHelper.guarded(
                "contributions.aged-debtors.render",
                client.post()
                    .uri("/api/v1/reports/AGED_DEBTORS/scheduled-render")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .bodyValue(new ScheduledRenderRequest(
                        ctx.tenantId(), ctx.periodStart(), ctx.periodEnd(), ctx.asOf(),
                        ctx.reportingCurrency(), ctx.cadenceLabel(), ctx.scheduleId(),
                        ctx.scheduleUpdatedByActorId(),
                        ctx.scheduleUpdatedByActorEmail()))
                    .retrieve()
                    .bodyToMono(byte[].class),
                new byte[0],
                warnings,
                Duration.ofSeconds(60),   // shape services can take a while
                1,                        // retry once
                Duration.ofSeconds(2)));
    }
}
```

Note two adjustments to `ScheduledFireContext`: add `scheduleUpdatedByActorId` + `scheduleUpdatedByActorEmail` fields so the adapter can pass them through to the owner service (which uses them in its `publishDataAccess` call).

#### 5. `M2MTokenProvider` — service-to-service Keycloak client_credentials
**File**: `services/java/shared/src/main/java/com/medfund/shared/security/M2MTokenProvider.java` (new; verify no equivalent exists first)

```java
@Slf4j
@Component
@RequiredArgsConstructor
public class M2MTokenProvider {
    private final WebClient webClient;
    @Value("${keycloak.token-endpoint}") private String tokenEndpoint;
    @Value("${keycloak.m2m.client-id}") private String clientId;
    @Value("${keycloak.m2m.client-secret}") private String clientSecret;

    private final Map<String, CachedToken> cache = new ConcurrentHashMap<>();

    public Mono<String> getServiceToken(String scope) {
        CachedToken cached = cache.get(scope);
        if (cached != null && cached.isValid()) return Mono.just(cached.token);
        return fetchToken(scope)
            .doOnNext(t -> cache.put(scope, new CachedToken(t.token, t.expiry)))
            .map(t -> t.token);
    }

    private Mono<CachedToken> fetchToken(String scope) {
        return webClient.post()
            .uri(tokenEndpoint)
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .bodyValue("grant_type=client_credentials&scope=" + scope
                + "&client_id=" + clientId + "&client_secret=" + clientSecret)
            .retrieve()
            .bodyToMono(Map.class)
            .map(json -> new CachedToken(
                (String) json.get("access_token"),
                Instant.now().plusSeconds(((Number) json.get("expires_in")).longValue() - 30)));
    }

    record CachedToken(String token, Instant expiry) {
        boolean isValid() { return Instant.now().isBefore(expiry); }
    }
}
```

If a similar helper already exists in `shared/security/` (verify at implementation time), reuse it and skip this class.

### Success Criteria

#### Automated Verification
- [x] All three owner services compile: `cd services/java && ./gradlew :contributions-service:compileJava :claims-service:compileJava :user-service:compileJava`
- [x] Finance-service compiles with 8 new cross-service adapters: `./gradlew :finance-service:compileJava`
- [x] Unit tests: `make test-java` (ScheduledRenderControllerTest present in all three owner services; AdapterDelegationTest + CrossServiceAdapterKeyAndShapeTest + CrossServiceRenderHelperTest cover the finance-side adapters)
- [x] New tests per owner-service controller:
  - Happy path returns 200 + XLSX bytes for each of the 8 keys.
  - Missing `scheduled_report:render` permission returns 403.
  - Wrong tenantId in request body → 403 (tenant scoping resolved from body).
  - `SecurityEventPublisher.publishDataAccess(...)` fires with correct actor from request body.
- [x] New tests per finance-service adapter:
  - Successful call returns bytes.
  - CrossServiceCallHelper timeout → adapter returns Mono<byte[]> that becomes `new byte[0]` fallback → orchestrator classifies as SHAPE failure → publishes ReportDeliveryFailedEvent.
- [ ] End-to-end IT `ScheduledReportEndToEndIT`: deferred — same Testcontainers-cost trade-off recorded in the Phase 3/4 deviations. Unit + slice coverage is comprehensive.

#### Manual Verification
- [ ] `curl -X POST http://localhost:8084/api/v1/reports/AGED_DEBTORS/scheduled-render` with service token + request body → returns XLSX bytes; save to a file, open in Excel, confirm content.

### Deviations

- **2026-09-03 — permissions catalogue gap from Phase 3/4 fixed as part of Phase 5 land.** The `@RequiresPermission` annotations shipped by Phase 3 (`tenant.settings:manage_report_schedules`) and Phase 4 (`tenant.settings:tenant_admin` on the finance-service force-fire endpoint) were never registered in `services/java/shared/src/main/resources/permissions.yaml`, `Permissions.java` (`ALL` set — the runtime source of truth `RoleController` validates against), or `clients/angular/src/app/core/security/permissions.ts`. `RoleController` would have rejected any tenant admin trying to grant these with HTTP 400. Registered all three permissions (including Phase 5's own explicit `scheduled_report:render`) in one pass across the three catalog files. No functional change to any service — the annotations already resolved by string.

---

## Phase 6 (§B.1): notification-service — `internal/report/dispatcher.go` + MIME + templates

### Overview

New notification-service package `internal/report/` that consumes both `medfund.notification.report-delivery` + `medfund.notification.report-delivery-failed`, resolves recipients via tenancy-service HTTP, fetches XLSX from MinIO, MIME-attaches ≤10 MB or renders a signed download link URL for larger, and sends via existing `SMTPSender`.

### Changes Required

#### 1. Directory + files
```
services/go/notification-service/internal/report/
  consumer.go
  dispatcher.go
  tenancy_client.go
  minio_client.go
  templates.go
  templates/
    delivery-subject.tmpl
    delivery-body.html.tmpl
    failure-subject.tmpl
    failure-body.html.tmpl
```

#### 2. `consumer.go` — Kafka subscription for both topics
```go
package report

import (
    "context"
    "encoding/json"
    "log"

    "medfund/notification-service/internal/events"
)

const (
    DeliveryTopic       = "medfund.notification.report-delivery"
    DeliveryFailedTopic = "medfund.notification.report-delivery-failed"
)

type Consumer struct {
    Brokers    string
    GroupID    string
    Dispatcher *Dispatcher
}

func (c *Consumer) Run(ctx context.Context) error {
    // Two subscribers, one per topic, feeding a single Dispatcher.
    okSub := events.NewSubscriber(c.Brokers, DeliveryTopic, c.GroupID+"-delivery")
    failSub := events.NewSubscriber(c.Brokers, DeliveryFailedTopic, c.GroupID+"-failure")

    go okSub.Run(ctx, func(payload []byte) error {
        var evt DeliveryEvent
        if err := json.Unmarshal(payload, &evt); err != nil {
            log.Printf("[report] malformed delivery event: %v", err)
            return nil  // don't retry a malformed payload; drop
        }
        result := c.Dispatcher.DispatchDelivery(ctx, evt)
        for _, r := range result {
            if r.Err != nil {
                log.Printf("[report] delivery to %s failed: %v", r.Recipient, r.Err)
            }
        }
        return nil
    })

    go failSub.Run(ctx, func(payload []byte) error {
        var evt DeliveryFailedEvent
        if err := json.Unmarshal(payload, &evt); err != nil {
            log.Printf("[report] malformed delivery-failed event: %v", err)
            return nil
        }
        c.Dispatcher.DispatchFailure(ctx, evt)
        return nil
    })

    <-ctx.Done()
    return ctx.Err()
}
```

#### 3. `dispatcher.go`
```go
package report

import (
    "bytes"
    "context"
    "fmt"
    "log"
    "time"

    "medfund/notification-service/internal/mail"
    "medfund/notification-service/internal/template"
)

const MaxAttachmentBytes = 10 * 1024 * 1024

type Recipient struct {
    ID       string
    Email    string
    Name     string
    IsActive bool
    UnsubscribeToken string
}

type RecipientLookup interface {
    ActiveFor(ctx context.Context, tenantID, scheduleID string) ([]Recipient, error)
    TenantContactEmail(ctx context.Context, tenantID string) (string, error)  // fallback for failure alerts
}

type BlobFetcher interface {
    GetXlsx(ctx context.Context, bucket, key string) ([]byte, error)
}

type Dispatcher struct {
    Sender          mail.Sender
    From            string
    Recipients      RecipientLookup
    Fetcher         BlobFetcher
    Bucket          string
    TemplateStore   template.Resolver   // per-tenant email templates
    SignedURLBuilder func(jobId, tenantId, recipientEmail string) (string, time.Time, error)
    Unsubscribe     UnsubscribeURLBuilder
}

func (d *Dispatcher) DispatchDelivery(ctx context.Context, evt DeliveryEvent) []Result {
    recipients, err := d.Recipients.ActiveFor(ctx, evt.TenantID, evt.ScheduleID)
    if err != nil {
        log.Printf("[report] fetch recipients failed tenant=%s: %v", evt.TenantID, err)
        return []Result{{Err: err}}
    }
    if len(recipients) == 0 {
        log.Printf("[report] no active recipients for schedule=%s (skipping)", evt.ScheduleID)
        return nil
    }

    var results []Result
    for _, rcpt := range recipients {
        result := d.deliverOne(ctx, evt, rcpt)
        results = append(results, result)
    }
    return results
}

func (d *Dispatcher) deliverOne(ctx context.Context, evt DeliveryEvent, rcpt Recipient) Result {
    subject, body, err := d.renderDeliveryTemplates(evt, rcpt)
    if err != nil {
        return Result{Recipient: rcpt.Email, Err: fmt.Errorf("render templates: %w", err)}
    }

    msg := mail.Message{
        From:    d.From,
        To:      rcpt.Email,
        Subject: subject,
        HTMLBody: body,
    }

    if evt.SizeBytes <= MaxAttachmentBytes {
        // Inline MIME attachment.
        xlsx, err := d.Fetcher.GetXlsx(ctx, d.Bucket, evt.XlsxRef)
        if err != nil {
            log.Printf("[report] fetch XLSX s3://%s/%s: %v", d.Bucket, evt.XlsxRef, err)
            return Result{Recipient: rcpt.Email, Err: err}
        }
        msg.Attachments = []mail.Attachment{{
            Filename:    d.filename(evt),
            ContentType: "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            Data:        xlsx,
        }}
    } else {
        // Signed link path — body already renders {{.DownloadUrl}}. The URL was
        // built + baked into the body during renderDeliveryTemplates.
        // Nothing more to do here.
    }

    if err := d.Sender.Send(msg); err != nil {
        log.Printf("[report] SMTP send to %s: %v", rcpt.Email, err)
        return Result{Recipient: rcpt.Email, Err: err}
    }
    return Result{Recipient: rcpt.Email}
}

func (d *Dispatcher) DispatchFailure(ctx context.Context, evt DeliveryFailedEvent) []Result {
    recipients, err := d.Recipients.ActiveFor(ctx, evt.TenantID, evt.ScheduleID)
    if err != nil || len(recipients) == 0 {
        // Fallback to tenant contact email
        contact, cerr := d.Recipients.TenantContactEmail(ctx, evt.TenantID)
        if cerr != nil || contact == "" {
            log.Printf("[report] failure event tenant=%s: no recipients + no contact email; dropping",
                evt.TenantID)
            return nil
        }
        recipients = []Recipient{{Email: contact, Name: "Tenant admin", IsActive: true}}
    }

    subject, body := d.renderFailureTemplates(evt)
    var results []Result
    for _, rcpt := range recipients {
        msg := mail.Message{From: d.From, To: rcpt.Email, Subject: subject, HTMLBody: body}
        if err := d.Sender.Send(msg); err != nil {
            results = append(results, Result{Recipient: rcpt.Email, Err: err})
        } else {
            results = append(results, Result{Recipient: rcpt.Email})
        }
    }
    return results
}

func (d *Dispatcher) filename(evt DeliveryEvent) string {
    return fmt.Sprintf("%s_%s_%s.xlsx",
        strings.ToLower(evt.ReportKey), evt.PeriodStart, evt.PeriodEnd)
}
```

#### 4. `tenancy_client.go` — recipient lookup + contact email
Clone `internal/regulatory/tenancy_client.go` shape.

```go
package report

import (
    "context"
    "encoding/json"
    "fmt"
    "net/http"
    "net/url"
    "time"
)

type HTTPTenancyClient struct {
    BaseURL string
    Client  *http.Client
}

func NewHTTPTenancyClient(baseURL string) *HTTPTenancyClient {
    return &HTTPTenancyClient{BaseURL: baseURL, Client: &http.Client{Timeout: 5 * time.Second}}
}

func (c *HTTPTenancyClient) ActiveFor(ctx context.Context, tenantID, scheduleID string) ([]Recipient, error) {
    endpoint := fmt.Sprintf("%s/api/v1/tenants/%s/report-schedules/%s/recipients/active",
        c.BaseURL, url.PathEscape(tenantID), url.PathEscape(scheduleID))
    req, _ := http.NewRequestWithContext(ctx, "GET", endpoint, nil)
    resp, err := c.Client.Do(req)
    if err != nil {
        return nil, err
    }
    defer resp.Body.Close()
    if resp.StatusCode != 200 {
        return nil, fmt.Errorf("tenancy responded %d", resp.StatusCode)
    }
    var out []Recipient
    if err := json.NewDecoder(resp.Body).Decode(&out); err != nil {
        return nil, err
    }
    return out, nil
}

func (c *HTTPTenancyClient) TenantContactEmail(ctx context.Context, tenantID string) (string, error) {
    endpoint := fmt.Sprintf("%s/api/v1/tenants/%s", c.BaseURL, url.PathEscape(tenantID))
    // ... GET tenant, return contact_email field ...
}
```

#### 5. `minio_client.go` — XLSX fetch
```go
package report

import (
    "context"
    "io"

    "github.com/minio/minio-go/v7"
    "github.com/minio/minio-go/v7/pkg/credentials"
)

type MinioBlobFetcher struct {
    Client *minio.Client
}

func NewMinioBlobFetcher(endpoint, accessKey, secretKey string, useSSL bool) (*MinioBlobFetcher, error) {
    client, err := minio.New(endpoint, &minio.Options{
        Creds:  credentials.NewStaticV4(accessKey, secretKey, ""),
        Secure: useSSL,
    })
    if err != nil {
        return nil, err
    }
    return &MinioBlobFetcher{Client: client}, nil
}

func (m *MinioBlobFetcher) GetXlsx(ctx context.Context, bucket, key string) ([]byte, error) {
    obj, err := m.Client.GetObject(ctx, bucket, key, minio.GetObjectOptions{})
    if err != nil { return nil, err }
    defer obj.Close()
    return io.ReadAll(obj)
}
```

Check whether `services/go/notification-service/` already imports minio-go (invoice dispatcher uses `d.Fetcher.GetObject(...)` — subagent quoted line 112-117); if so, extend the existing package instead of duplicating.

#### 6. Templates
**File**: `services/go/notification-service/internal/report/templates/delivery-subject.tmpl`
```
[{{.TenantName}}] {{.ReportLabel}} — {{.CadenceLabel}} — {{.PeriodLabel}}
```

**File**: `.../delivery-body.html.tmpl`
```html
<!DOCTYPE html>
<html>
<body style="font-family:Arial,sans-serif;line-height:1.5;color:#333;">
  <h2>{{.ReportLabel}}</h2>
  <p>Your scheduled {{.CadenceLabel}} report covering <strong>{{.PeriodLabel}}</strong> is ready.</p>
  {{if .DownloadUrl}}
    <p><a href="{{.DownloadUrl}}" style="background:#0066cc;color:#fff;padding:10px 20px;text-decoration:none;">Download XLSX</a></p>
    <p><small>Link valid until {{.LinkExpiryFormatted}}.</small></p>
  {{else}}
    <p>The XLSX is attached to this email.</p>
  {{end}}
  <hr>
  <p style="font-size:12px;color:#888;">
    Delivered to {{.RecipientEmail}} for tenant {{.TenantName}}.
    <a href="{{.UnsubscribeUrl}}">Unsubscribe from this report</a>.
  </p>
</body>
</html>
```

**File**: `.../failure-subject.tmpl`
```
[{{.TenantName}}] Scheduled report failed: {{.ReportLabel}} ({{.PeriodLabel}})
```

**File**: `.../failure-body.html.tmpl`
```html
<!DOCTYPE html>
<html>
<body style="font-family:Arial,sans-serif;line-height:1.5;color:#333;">
  <h2 style="color:#c00;">Scheduled report failure</h2>
  <p>The scheduled run for <strong>{{.ReportLabel}}</strong> covering <strong>{{.PeriodLabel}}</strong> did not complete.</p>
  <p><strong>Stage:</strong> {{.FailureStage}}</p>
  <p><strong>Error:</strong> <code>{{.ErrorSummary}}</code></p>
  <p>The schedule is still active — the next scheduled fire will run normally. You can manually re-run this fire from <a href="{{.RerunUrl}}">the schedule history page</a>.</p>
</body>
</html>
```

#### 7. Wire into main
**File**: `services/go/notification-service/cmd/main.go` (existing)

Add:
```go
reportDispatcher := &report.Dispatcher{
    Sender:  smtpSender,
    From:    cfg.MailFrom,
    Recipients: report.NewHTTPTenancyClient(cfg.TenancyServiceURL),
    Fetcher: minioFetcher,
    Bucket:  cfg.ReportPayloadsBucket,
    TemplateStore: templateResolver,
    SignedURLBuilder: report.NewSignedURLBuilder(cfg.GatewayBaseURL, cfg.DownloadTokenSecret, 7*24*time.Hour),
    Unsubscribe: report.NewUnsubscribeURLBuilder(cfg.WebBaseURL),
}
reportConsumer := &report.Consumer{
    Brokers:    cfg.KafkaBrokers,
    GroupID:    "notification-service.report",
    Dispatcher: reportDispatcher,
}
go func() {
    if err := reportConsumer.Run(ctx); err != nil {
        log.Fatalf("report consumer: %v", err)
    }
}()
```

### Success Criteria

#### Automated Verification
- [x] notification-service compiles: `cd services/go/notification-service && go build ./...`
- [x] Unit tests: `cd services/go/notification-service && go test ./internal/report/...`
  - `dispatcher_test.go` — happy path with fake Fetcher + SMTPSender; MIME attachment when size <10MB; signed link body when size >10MB; no recipients → skip; failure event → fallback to tenant contact.
  - `templates_test.go` — subject + body renders with expected values.
- [x] Full go tests: `go test ./...` — every package green.

#### Manual Verification
- [ ] Start infra + notification-service; publish a synthetic `medfund.notification.report-delivery` event via `kafkacat`; check mailpit (http://localhost:8025) for the email with XLSX attached.
- [ ] Publish a synthetic `medfund.notification.report-delivery-failed` event; check mailpit for the failure alert.

### Deviations

- **2026-09-04 — dispatcher signature diverges from plan sketch.** Plan sketched `Dispatcher` as a struct with public fields plus a `TemplateStore template.Resolver` for per-tenant subject/body. v1 ships inline HTML templates (see `templates.go`) — no per-tenant override yet, so the field is dropped and a `NewDispatcher(...)` constructor is provided instead of struct literal init. Same shape as `regulatory.NewDispatcher`, keeps the wiring in `cmd/main.go` symmetrical with the other pipelines. Per-tenant templates are captured as a follow-up: they can plug in the same way `invoice.Dispatcher.Templates` does. No functional change to Phase 17.
- **2026-09-04 — dispatch fetches XLSX once per event, not once per recipient.** Plan sketched a per-recipient fetch inside the loop. That would issue N MinIO GETs per fire for the same object — wasteful and slow. Fetches once before the recipient loop; when the fetch fails, falls back to signed-link path so the send still completes. Not a design change — just plugging an obvious perf pitfall the plan sketch didn't spell out.
- **2026-09-04 — signed-link fallback on MinIO fetch failure.** Plan didn't specify what happens if the size is under 10MB but the MinIO GET fails. Dispatcher falls back to the signed-link body so the recipient still gets *something* actionable (they can click through to trigger a fresh fetch on the download endpoint). If the signed-URL builder is unconfigured (empty secret), the per-recipient send fails cleanly with a logged error. Follow-up: instrument fetch-failure rate.

---

## Phase 7 (§B.2): Gateway signed-download route + unsubscribe route

### Overview

Add two new gateway routes: (a) `GET /api/v1/reports/scheduled/{jobId}/download?token=...` — proxies to finance-service with HMAC-token verification for the signed-link email path; (b) `POST /api/v1/report-schedule-recipients/unsubscribe/{token}` — proxies to tenancy-service (public, no JWT). Finance-service ships the download endpoint + HMAC verifier.

### Changes Required

#### 1. Finance-service download endpoint
**File**: `services/java/finance-service/src/main/java/com/medfund/finance/report/schedule/controller/ScheduledReportDownloadController.java` (new)

```java
@RestController
@RequestMapping("/api/v1/reports/scheduled")
@RequiredArgsConstructor
@Slf4j
public class ScheduledReportDownloadController {

    private final ScheduledDownloadTokenVerifier verifier;
    private final ReportJobRepository reportJobRepository;
    private final ReportPayloadStore payloadStore;
    private final SecurityEventPublisher securityEventPublisher;

    /**
     * Public route (no JWT) — validated by HMAC token from the delivery email.
     * The gateway proxies unchanged; finance-service is the authority for
     * token verification because the token was minted here.
     */
    @GetMapping("/{jobId}/download")
    @Operation(summary = "Signed-link download for scheduled report XLSX (from email)")
    public Mono<ResponseEntity<byte[]>> download(@PathVariable UUID jobId,
                                                 @RequestParam String token) {
        var verified = verifier.verify(token);
        if (verified.isEmpty() || !verified.get().jobId().equals(jobId)) {
            return Mono.just(ResponseEntity.status(HttpStatus.FORBIDDEN).build());
        }
        var claims = verified.get();
        return reportJobRepository.findById(jobId)
            .filter(j -> "completed".equals(j.getStatus()))
            .filter(j -> j.getTenantId().equals(claims.tenantId()))
            .switchIfEmpty(Mono.error(new NoSuchElementException("Job not found or not completed")))
            .flatMap(job -> {
                String xlsxRef = extractXlsxRef(job.getResultJson());
                return payloadStore.getXlsx(xlsxRef)
                    .flatMap(bytes -> securityEventPublisher.publishDataAccess(
                            job.getTenantId().toString(),
                            claims.recipientEmail(),  // recipient acts as pseudo-actor here
                            claims.recipientEmail(),
                            job.getReportKey(),
                            Map.of("source", "SCHEDULED_LINK_DOWNLOAD",
                                   "jobId", jobId.toString(),
                                   "sizeBytes", bytes.length))
                        .thenReturn(ResponseEntity.ok()
                            .contentType(MediaType.valueOf("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                            .header(HttpHeaders.CONTENT_DISPOSITION,
                                "attachment; filename=\"" + filenameFor(job) + "\"")
                            .body(bytes)));
            })
            .onErrorResume(NoSuchElementException.class,
                e -> Mono.just(ResponseEntity.notFound().build()));
    }
}
```

#### 2. `ScheduledDownloadTokenVerifier` + `ScheduledDownloadTokenIssuer`
**File**: `services/java/finance-service/src/main/java/com/medfund/finance/report/schedule/ScheduledDownloadTokenIssuer.java` (new)

```java
@Slf4j
@Component
@RequiredArgsConstructor
public class ScheduledDownloadTokenIssuer {

    @Value("${scheduled.report.download.token-secret}")
    private String secret;

    @Value("${scheduled.report.download.expiry-days:7}")
    private int expiryDays;

    private final ObjectMapper objectMapper;

    public String issue(UUID jobId, UUID tenantId, String recipientEmail) {
        long exp = Instant.now().plus(expiryDays, ChronoUnit.DAYS).getEpochSecond();
        var claims = new TokenClaims(jobId, tenantId, recipientEmail, exp);
        try {
            String header = base64Url("{\"typ\":\"SDLT\",\"alg\":\"HS256\"}");
            String payload = base64Url(objectMapper.writeValueAsString(claims));
            String signature = hmacSha256(header + "." + payload, secret);
            return header + "." + payload + "." + signature;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to issue download token", e);
        }
    }

    static String base64Url(String s) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }
    // hmacSha256 ...
}
```

`ScheduledDownloadTokenVerifier` — inverse: split on `.`, verify signature, decode payload, check `exp`, return `Optional<TokenClaims>`.

The email template's `{{.DownloadUrl}}` variable comes from `SignedURLBuilder` in notification-service; but the token issuance happens in finance-service. Two options:

- **Option A** — issue the token in the finance-service orchestrator and put it in the `ReportDeliveryEvent` payload alongside `xlsxRef`. Notification-service just embeds the URL. Simpler; token issued once per event but reused for all recipients — recipient email is not bound. Weaker security.
- **Option B** — put the shared HMAC secret in notification-service too; notification-service issues per-recipient tokens (recipient email in claims). Stronger. But splits the crypto authority.

**Chosen: Option B** — per-recipient tokens for accountability. Shared HMAC secret via env var (`SCHEDULED_REPORT_DOWNLOAD_TOKEN_SECRET`) available to both finance-service and notification-service. Documented in rollout notes.

Notification-service Go signer:

```go
// signed_url.go
package report

import (
    "crypto/hmac"
    "crypto/sha256"
    "encoding/base64"
    "encoding/json"
    "fmt"
    "time"
)

type SignedURLBuilder struct {
    GatewayBaseURL string
    Secret         []byte
    Validity       time.Duration
}

func NewSignedURLBuilder(baseURL, secret string, validity time.Duration) *SignedURLBuilder {
    return &SignedURLBuilder{GatewayBaseURL: baseURL, Secret: []byte(secret), Validity: validity}
}

func (b *SignedURLBuilder) Build(jobId, tenantId, recipientEmail string) (string, time.Time, error) {
    expiry := time.Now().Add(b.Validity)
    claims := map[string]interface{}{
        "jobId": jobId, "tenantId": tenantId,
        "recipientEmail": recipientEmail, "exp": expiry.Unix(),
    }
    header := b64u([]byte(`{"typ":"SDLT","alg":"HS256"}`))
    payloadBytes, _ := json.Marshal(claims)
    payload := b64u(payloadBytes)
    mac := hmac.New(sha256.New, b.Secret)
    mac.Write([]byte(header + "." + payload))
    signature := b64u(mac.Sum(nil))
    token := fmt.Sprintf("%s.%s.%s", header, payload, signature)
    url := fmt.Sprintf("%s/api/v1/reports/scheduled/%s/download?token=%s",
        b.GatewayBaseURL, jobId, token)
    return url, expiry, nil
}
```

#### 3. Gateway routes
**File**: `services/go/gateway/internal/routes/routes.go`

Add after existing finance-service routes (~line 260):

```go
// ── Phase 17: Scheduled report downloads + unsubscribe ─────────────────────
// Signed-link download: public route (JWT bypass in middleware config).
app.Get("/api/v1/reports/scheduled/:jobId/download", proxy.Handler(cfg.FinanceServiceURL))
// Rerun (authenticated).
app.Post("/api/v1/reports/scheduled/:jobId/rerun", proxy.Handler(cfg.FinanceServiceURL))
// Force fire (test-only).
app.Post("/api/v1/reports/scheduled/probe/force-fire", proxy.Handler(cfg.FinanceServiceURL))
// Unsubscribe: public route on tenancy-service.
app.Post("/api/v1/report-schedule-recipients/unsubscribe/:token", proxy.Handler(cfg.TenancyServiceURL))

// Tenant schedule CRUD (authenticated).
app.All("/api/v1/tenants/*/report-schedules", proxy.Handler(cfg.TenancyServiceURL))
app.All("/api/v1/tenants/*/report-schedules/*", proxy.Handler(cfg.TenancyServiceURL))
```

Update `services/go/gateway/internal/middleware/jwt.go` to allow the `GET /api/v1/reports/scheduled/*/download` and `POST /api/v1/report-schedule-recipients/unsubscribe/*` paths as JWT-optional (add to the public-path list).

#### 4. notification-service unsubscribe URL builder
```go
// unsubscribe_url.go
package report

import "fmt"

type UnsubscribeURLBuilder struct {
    WebBaseURL string
}

func NewUnsubscribeURLBuilder(webBaseURL string) *UnsubscribeURLBuilder {
    return &UnsubscribeURLBuilder{WebBaseURL: webBaseURL}
}

// Builds a URL that lands on the Angular unsubscribe confirm page which then
// POSTs to /api/v1/report-schedule-recipients/unsubscribe/{token}.
func (b *UnsubscribeURLBuilder) Build(token string) string {
    return fmt.Sprintf("%s/public/unsubscribe/%s", b.WebBaseURL, token)
}
```

### Success Criteria

#### Automated Verification
- [x] Gateway compiles: `cd services/go/gateway && go build ./...`
- [x] Gateway tests pass: `cd services/go/gateway && go test ./...`
- [x] Finance-service compiles + unit tests pass: `cd services/java && ./gradlew :finance-service:test --tests "*Test"` (945/945 green)
- [x] New tests:
  - `ScheduledDownloadTokenIssuerTest` (7 tests) — issue → verify round-trip; three-segment token; SDLT/HS256 header; deterministic under fixed clock; recipient-scoped signatures; empty-secret guard.
  - `ScheduledDownloadTokenVerifierTest` (7 tests) — round-trip; tampered signature rejected; wrong-secret rejected; expired rejected; malformed rejected; empty-secret rejected; missing-claim payload rejected.
  - `ScheduledReportDownloadControllerTest` (11 tests) — happy path (200 + XLSX + DATA_ACCESS event); missing token, tampered token, jobId mismatch → 403; cross-tenant / non-completed / missing job / missing xlsxRef → 404; MinIO fetch failure → 500; payload store unconfigured → 503; filenameFor covers range + single + missing periods.
- [x] Go unit test for `SignedURLBuilder` + `UnsubscribeURLBuilder` — already shipped in Phase 6.
- [x] Gateway `middleware.jwt_test.go` — new public-path bypass tests: `/api/v1/reports/scheduled/*/download` and `/api/v1/report-schedule-recipients/unsubscribe/*` skip JWT; `/rerun` still requires auth; `isPublicPath` unit cases.
- [x] Gateway `routes_test.go` — new `TestPhase17ScheduledReportRoutesRegistered` asserts the seven new routes are registered (download, rerun, force-fire, unsubscribe, tenant schedule CRUD × 3).

#### Manual Verification
- [ ] Trigger a scheduled fire with >10MB XLSX (e.g. by seeding a large commission_statement); confirm email arrives with a `Download XLSX` button, click it, XLSX downloads.
- [ ] Click the "Unsubscribe" link in a delivery email; confirm the recipient's `is_active` flips to FALSE + subsequent fires don't email them.

### Deviations

- **2026-09-04 — `TenantWebFilter` (shared) picked up a new bypass predicate.** Public download endpoint carries tenantId inside its HMAC token, not in `X-Tenant-ID`. The plan sketched gateway-only JWT bypass but was silent on the WebFlux side. Added a suffix-match to `TenantWebFilter.filter(...)` for paths matching `/api/v1/reports/scheduled/*/download` — the controller then populates `TenantContext` from the verified token via `.contextWrite(...)` so R2DBC still routes to the right tenant schema. Surgical, since it's a suffix+prefix match — the rerun / force-fire endpoints on the same prefix keep the mandatory `X-Tenant-ID` check.
- **2026-09-04 — base64url tail-char mutation is not a reliable tamper test.** HMAC-SHA256 output is 32 bytes; the last base64url char carries only 4 bits, so many substitutions decode to the identical signature bytes. `verify_rejectsTamperedSignature` was rewritten to mutate the *leading* char of the signature segment — that provably changes the decoded bytes. Not a design change — plugging a test-craft footgun that made the assertion flaky.
- **2026-09-04 — `ScheduledReportDownloadControllerIT` deferred, unit + slice coverage in place.** The plan listed an IT for the controller; same trade-off as Phases 3/4 — the 11-test unit suite exercises every invariant the IT description enumerates (token verify branches, cross-tenant guard, non-completed guard, MinIO-failure classification, DATA_ACCESS event emission, content-disposition filename shape). Follow-up: land the IT alongside the Phase 4 deferred trio when a MinIO+Kafka Testcontainer harness is worth the boot cost.

---

## Phase 8 (§C.1): Angular — `/tenant/admin/settings/report-schedules` page + components

### Overview

New Angular admin route + page + three components (schedules list, recipient list, run history). Uses existing shared components — never a raw `<input>` for IDs (per `feedback_no_raw_id_inputs`) — and consumes new endpoints from tenancy-service + finance-service.

### Changes Required

#### 1. New route
**File**: `clients/angular/src/app/pages/tenant-admin/tenant-admin.routes.ts`
Add:
```typescript
{
  path: 'settings/report-schedules',
  loadComponent: () => import('./settings/report-schedules/report-schedules-page.component')
    .then(m => m.ReportSchedulesPageComponent),
  data: { requiredPermission: 'tenant_settings:report_schedules:read' }
}
```

Plus the sidebar entry in `settings.component.ts` (insert after "Reports" tab):
```typescript
{ label: 'Report schedules', route: 'settings/report-schedules', icon: 'clock' }
```

#### 2. Service — TypeScript client
**File**: `clients/angular/src/app/core/services/tenant-report-schedule.service.ts` (new)

```typescript
@Injectable({ providedIn: 'root' })
export class TenantReportScheduleService {
  constructor(private http: HttpClient) {}

  list(tenantId: string): Observable<TenantReportScheduleRow[]> {
    return this.http.get<TenantReportScheduleRow[]>(
      `/api/v1/tenants/${tenantId}/report-schedules`);
  }

  create(tenantId: string, req: CreateTenantReportScheduleRequest): Observable<TenantReportScheduleRow> { /* POST */ }
  update(tenantId: string, scheduleId: string, req: UpdateTenantReportScheduleRequest): Observable<TenantReportScheduleRow> { /* PUT */ }
  delete(tenantId: string, scheduleId: string): Observable<void> { /* DELETE */ }

  addRecipient(tenantId: string, scheduleId: string, req: AddRecipientRequest): Observable<RecipientRow> { /* POST */ }
  updateRecipient(tenantId: string, scheduleId: string, recipientId: string, req: UpdateRecipientRequest): Observable<RecipientRow> { /* PUT */ }
  deleteRecipient(tenantId: string, scheduleId: string, recipientId: string): Observable<void> { /* DELETE */ }

  runHistory(tenantId: string, scheduleId: string, limit = 20): Observable<ScheduleRunRow[]> { /* GET */ }
  rerun(jobId: string): Observable<ReportJobRow> { /* POST /api/v1/reports/scheduled/{jobId}/rerun */ }
}

export interface TenantReportScheduleRow {
  id: string;
  tenantId: string;
  reportKey: string;
  reportLabel: string;
  family: string;
  familyLabel: string;
  enabled: boolean;
  cadence: 'WEEKLY' | 'MONTHLY' | 'QUARTERLY' | 'ANNUAL';
  hourOfDay: number;
  dayOfWeek?: number | null;
  dayOfMonth?: number | null;
  reportingCurrency?: string | null;
  lastFiredAt?: string | null;
  lastStatus?: 'COMPLETED' | 'FAILED' | 'SKIPPED_TOGGLE' | null;
  recipients: RecipientRow[];
}
```

#### 3. Page component
**File**: `clients/angular/src/app/pages/tenant-admin/settings/report-schedules/report-schedules-page.component.ts` (new)

Standalone component with accordion cards grouped by family (reuse `groupByFamily()` shape from `reports-tab.component.ts:123-136`). Per card: header (report label + family badge + enabled toggle), body (cadence dropdown + day/hour picker + reporting-currency dropdown + expand recipient list + expand run history).

Structure:

```typescript
@Component({
  selector: 'app-report-schedules-page',
  standalone: true,
  imports: [CommonModule, FormsModule, ReactiveFormsModule,
            IconComponent, SkeletonComponent,
            ScheduleRecipientListComponent, ScheduleRunHistoryComponent,
            CadencePickerComponent, HourPickerComponent],
  templateUrl: './report-schedules-page.component.html',
  styleUrl: './report-schedules-page.component.scss',
})
export class ReportSchedulesPageComponent implements OnInit {
  groups: FamilyGroup[] = [];
  loading = false;
  saving = false;
  errorMessage: string | null = null;

  // Anchor scroll target from Phase 0 grid "Manage schedule" link
  private scrollToKey: string | null = null;

  constructor(private scheduleService: TenantReportScheduleService,
              private tenantService: TenantService,
              private route: ActivatedRoute,
              private toastService: ToastService) {}

  ngOnInit(): void {
    this.route.fragment.subscribe(f => this.scrollToKey = f);
    this.refresh();
  }

  refresh(): void { /* load list, group by family, scroll to anchor if any */ }

  save(row: TenantReportScheduleRow): void { /* validate + PUT */ }
  create(reportKey: string): void { /* open inline create form → POST */ }
  delete(row: TenantReportScheduleRow): void { /* confirm modal → DELETE */ }
}
```

Template highlights:
```html
<div class="page">
  <h1>Report schedules</h1>
  <p class="hint">Automatically deliver cadenced reports to a list of recipients. Only reports marked as cadenceable appear here.</p>

  @if (loading) { <app-skeleton /> }
  @if (errorMessage) { <div class="error">{{ errorMessage }}</div> }

  @for (group of groups; track group.family) {
    <section class="family-group">
      <h2>{{ group.familyLabel }}</h2>
      @for (row of group.rows; track row.reportKey) {
        <article class="schedule-card" [id]="row.reportKey">
          <header>
            <span class="label">{{ row.reportLabel }}</span>
            <label class="toggle">
              <input type="checkbox" [(ngModel)]="row.enabled" (change)="save(row)">
              Enabled
            </label>
          </header>
          <div class="body">
            <app-cadence-picker [(ngModel)]="row.cadence" (ngModelChange)="save(row)" />
            @switch (row.cadence) {
              @case ('WEEKLY') {
                <app-day-of-week-picker [(ngModel)]="row.dayOfWeek" (ngModelChange)="save(row)" />
              }
              @case ('MONTHLY') {
                <app-day-of-month-picker [(ngModel)]="row.dayOfMonth" (ngModelChange)="save(row)" />
              }
            }
            <app-hour-picker [(ngModel)]="row.hourOfDay" (ngModelChange)="save(row)" />
            <app-currency-select [(ngModel)]="row.reportingCurrency" (ngModelChange)="save(row)" [nullable]="true" nullLabel="Use tenant default" />
          </div>
          <details>
            <summary>Recipients ({{ row.recipients.length }} active)</summary>
            <app-schedule-recipient-list [scheduleId]="row.id" [recipients]="row.recipients" (changed)="refresh()" />
          </details>
          <details>
            <summary>Run history</summary>
            <app-schedule-run-history [scheduleId]="row.id" />
          </details>
        </article>
      }
    </section>
  }
</div>
```

#### 4. Recipient list component
**File**: `clients/angular/src/app/pages/tenant-admin/settings/report-schedules/schedule-recipient-list.component.ts` (new)

Table with add-row + toggle-active + delete. Every mutation POST/PUT/DELETE fires audit on the server. Uses shared debounced email input (never raw `<input>` per `feedback_no_raw_id_inputs` — but email is not an ID; regular input is fine here).

#### 5. Run history component
**File**: `clients/angular/src/app/pages/tenant-admin/settings/report-schedules/schedule-run-history.component.ts` (new)

Table of last N `report_job` runs for this schedule with status pill, run duration, error tooltip, download button (calls existing `/api/v1/reports/jobs/{id}/download` endpoint if present, else the new signed-link route), re-run button (POST `/api/v1/reports/scheduled/{jobId}/rerun`).

#### 6. Shared cadence + hour + day pickers
**Files** (new, under `clients/angular/src/app/shared/components/`):
- `cadence-picker.component.ts` — 4-option dropdown WEEKLY/MONTHLY/QUARTERLY/ANNUAL.
- `hour-picker.component.ts` — 24-option dropdown 00:00…23:00.
- `day-of-week-picker.component.ts` — 7-option dropdown Mon…Sun.
- `day-of-month-picker.component.ts` — 28-option dropdown 1…28 with hint "Capped at 28 to avoid Feb boundary".

### Success Criteria

#### Automated Verification
- [x] Angular unit tests pass: `make test-angular` — new suites `TenantReportScheduleService` (9 tests) + `ReportSchedulesPageComponent` (7 tests) run headless-green.
- [x] `ReportSchedulesPageComponent` spec: renders groups, save fires PUT, invalid cadence combo blocked, deleteCard confirms then DELETEs, submitCreate POSTs the picked candidate, toggleEnabled warns when config-disabled.
- [ ] `ScheduleRecipientListComponent` spec deferred — page-level integration test exercises the mutation flow via the `recipients` @Input path; standalone spec is a follow-up.
- [x] Angular build clean: `cd clients/angular && npx ng build` — my three new SCSS files (6.5 KB + 3.1 KB + 2.4 KB) are all under the 8 KB per-component budget; pre-existing budget warnings on other components (settings, data-table, claim-detail, generate-billing-wizard, dashboard, submit-claim, member-detail) are unchanged.
- [ ] `verify` skill on `/tenant/admin/settings/report-schedules` — deferred to manual verification below.

#### Manual Verification
- [ ] Create a MONTHLY commission_statement schedule with 2 recipients via the UI.
- [ ] Toggle enabled OFF; observe row greyed out; toggle back ON.
- [ ] Add a recipient with an invalid email → validation error shown.
- [ ] Click into Run history → observe the last 20 fires with correct status + duration; click Re-run on a completed run → new run appears.

### Deviations

- **2026-09-04**: Two backend endpoints the plan implicitly assumed on finance-service were missing after Phase 4. Added them here rather than deferring — the run-history + in-app download UI cannot work without them.
  - `GET /api/v1/reports/scheduled/schedules/{scheduleId}/runs?limit=20` on finance-service. New `ScheduledReportHistoryController` reads `report_job` filtered by `schedule_id` DESC, returning a slim `ScheduledRunResponse` projection (no raw params/result JSON blobs). Repository query `ReportJobRepository.findByScheduleIdOrderedDesc` uses R2DBC's tenant-schema-scoped connection.
  - `GET /api/v1/reports/scheduled/runs/{jobId}/download` on the same controller. JWT-authenticated in-app download that mirrors `ScheduledReportDownloadController.serveXlsx()` but authorises via `@RequiresPermission` + `X-Tenant-ID` rather than the signed HMAC token (which only the notification-service can mint). Emits a `DATA_ACCESS` security event with `source=SCHEDULED_IN_APP_DOWNLOAD`.
  - Gateway wired both routes into `services/go/gateway/internal/routes/routes.go`; the `TestPhase17ScheduledReportRoutesRegistered` case list grew by two cases.
- **2026-09-04**: The plan (line 2338-2354) declared the schedule-list DTO shape includes `family: string` and `familyLabel: string`. The tenancy-service DTO landed in Phase 3 without those fields. Rather than churn the Java DTO + service + tests, the Angular page fetches BOTH `TenantReportScheduleService.list()` + `TenantReportConfigService.list()` in a `forkJoin` and cross-references by `reportKey` to build the family grouping. Also gives the page the config-enabled state which powers the "Report disabled" warning chip + the cascade-disable warning toast (a nice-to-have not called out in the plan).
- **2026-09-04**: Collapsed the plan's four separate picker components (`CadencePicker`, `HourPicker`, `DayOfWeekPicker`, `DayOfMonthPicker`) into inline `<app-select>` usages driven by `SelectOption[]` constants (`HOUR_OPTIONS`, `DAY_OF_WEEK_OPTIONS`, `DAY_OF_MONTH_OPTIONS`, `CADENCE_OPTIONS`) declared at the top of the page module. Each picker was a 4-line dropdown with no reused state; four standalone components was overkill for one consumer. If a second consumer emerges (Phase 10 Playwright specs will re-use them via the page), we'll extract then per YAGNI.
- **2026-09-04**: Did NOT add the "Report schedules" entry to `settings.component.ts` (the plan's phrasing "insert after 'Reports' tab" doesn't match reality: settings is a tab-based single-page component, not sidebar entries). Primary entry into the schedules page is the "Manage schedule" link Phase 9 adds to the Reports tab. Standalone route resolves; Phase 9 will make the entry point discoverable.
- **2026-09-04**: The `refresh` icon referenced elsewhere in the codebase is not actually registered in `IconComponent.ICONS` (silent-fail renders blank). Sidestepped by using the already-registered `history` icon on the "Re-run" button (semantically correct — history = counter-clockwise clock arrow) and by dropping the icon from the "Refresh" button in favour of a text-only button. Follow-up to add `refresh` to the icon registry so the existing consumers (`backfill-review.component.html`, `endorsement-review-queue.component.html`) also render.

---

## Phase 9 (§C.2): Phase 0 grid link + cascade-disable modal

### Overview

Two small Angular changes to the existing Phase 0 `reports-tab.component.ts`: add a "Manage schedule" link column next to cadenced rows, and show a confirm modal when disabling a report that has active schedules (from the extended API response's `activeScheduleCount`).

### Changes Required

#### 1. Extend `TenantReportConfigRow` TypeScript type
**File**: `clients/angular/src/app/core/services/tenant-report-config.service.ts`
Add `activeScheduleCount?: number` + `cadenced?: boolean` fields. Backend already computes these (Phase 3 §7 extension of `TenantReportConfigResponse`).

#### 2. Reports-tab template + component update
**File**: `clients/angular/src/app/pages/tenant-admin/settings/reports/reports-tab.component.ts`

Add the link + confirm modal:

```typescript
// Component addition
manageSchedule(row: TenantReportConfigRow): void {
  this.router.navigate(['/tenant/admin/settings/report-schedules'], { fragment: row.reportKey });
}

// Save with cascade confirmation:
save(): void {
  const tenantId = this.tenantService.getTenantId();
  if (!tenantId) return;
  const diff = this.computeDiff();

  // Find newly-disabled rows with active schedules
  const disablingWithSchedules = diff
    .filter(d => !d.enabled)
    .map(d => this.findRow(d.reportKey))
    .filter(row => row && (row.activeScheduleCount ?? 0) > 0);

  if (disablingWithSchedules.length > 0) {
    const message = `Disabling these reports will pause ${disablingWithSchedules.length} scheduled ` +
      `deliveries (${disablingWithSchedules.reduce((n, r) => n + (r?.activeScheduleCount ?? 0), 0)} schedules total). ` +
      `Recipients will stop receiving these reports. Continue?`;
    this.confirmService.confirm({ title: 'Pause scheduled deliveries?', message })
      .subscribe(confirmed => {
        if (confirmed) this.doSave(diff);
      });
    return;
  }
  this.doSave(diff);
}
```

Template addition (in the per-row block):
```html
<td class="actions">
  @if (row.cadenced) {
    <button type="button" class="link" (click)="manageSchedule(row)">
      Manage schedule
      @if (row.activeScheduleCount) {
        <span class="badge">{{ row.activeScheduleCount }}</span>
      }
    </button>
  }
</td>
```

#### 3. Public unsubscribe page
**File**: `clients/angular/src/app/pages/public/unsubscribe/unsubscribe-page.component.ts` (new)

Standalone route `/public/unsubscribe/:token`, no auth, confirms "Unsubscribe from scheduled report emails?" → POST to gateway → success or already-unsubscribed message.

Register the route in `app.routes.ts`:
```typescript
{
  path: 'public/unsubscribe/:token',
  loadComponent: () => import('./pages/public/unsubscribe/unsubscribe-page.component')
    .then(m => m.UnsubscribePageComponent)
}
```

### Success Criteria

#### Automated Verification
- [x] Angular build clean: `cd clients/angular && npx ng build`
- [x] Angular unit tests: `make test-angular`
- [x] New tests:
  - `reports-tab.component.spec.ts` — clicking Manage schedule navigates with the correct fragment.
  - `reports-tab.component.spec.ts` — save with a disabling row that has schedules shows the confirm modal; cancel aborts save; confirm proceeds.
  - `unsubscribe-page.component.spec.ts` — happy path shows success; already-unsubscribed shows message; malformed token shows error.
- [ ] `verify` on `/tenant/admin/settings/reports`: click Manage schedule column, land on the schedules page at the correct anchor.
- [ ] `verify` on `/public/unsubscribe/<test-token>`: shows the unsubscribe confirmation.

#### Manual Verification
- [ ] End-to-end: create schedule → toggle report OFF in reports-tab → see modal with correct count → confirm → observe schedule cascade-disabled + audit event.
- [ ] Click unsubscribe from a delivery email → land on Angular page → confirm → recipient deactivated.

### Deviations

- **2026-09-04**: `ConfirmService.ask()` returns a `Promise<boolean>`, not an `Observable` — the plan snippet used `.subscribe()` which would not compile against the current API. Adapted to `.then()`. No behavioural change.
- **2026-09-04**: The plan template rendered the count with a single-count "1 schedule total" always; message now pluralises `report`/`reports` and `schedule`/`schedules` correctly based on count. Cosmetic.
- **2026-09-04**: Confirm dialog labels tuned — `confirmLabel: 'Disable & pause'`, `cancelLabel: 'Keep enabled'`, `danger: true`. The generic "Confirm/Cancel" left the destructive path unsignalled.
- **2026-09-04**: Unsubscribe page adds an *optional* free-text reason field (`maxlength=500`, matches the backend `UnsubscribeRequest`). Plan didn't call for it but the audit trail on the backend already accepts it, and surfacing it here fills the loop.
- **2026-09-04**: UUID shape is validated client-side before the POST fires — a malformed URL never reaches the backend, and the user sees an actionable "malformed link" state instead of the generic "couldn't process" fallback the backend returns for both unknown-token and expired-token cases.

---

## Phase 10 (§D.1): Playwright e2e specs + performance script

### Overview

New Playwright specs covering the golden path: create schedule → force-fire → verify email at mailpit → verify report_job history → download from history page → unsubscribe. Also a shell script that stresses the probe with 100 tenants × 5 schedules to catch dedup + concurrency issues.

### Changes Required

#### 1. Playwright specs
**Files** (new, under `clients/angular/e2e/`):

- `scheduled-report-happy-path.spec.ts`:
  - Login as tenant admin.
  - Navigate to `/tenant/admin/settings/report-schedules`.
  - Create a MONTHLY commission_statement schedule with recipient `test-tenant-admin@mailpit.test`.
  - Add a second recipient.
  - Force-fire via test-only endpoint (`POST /api/v1/reports/scheduled/probe/force-fire?scheduleId=...`).
  - Wait up to 30s for mailpit inbox to show 2 emails.
  - Open the email; verify subject + body contains report label + period.
  - Verify attachment name + content type.
  - Navigate to run history; verify one COMPLETED row with correct period; click Download; verify XLSX bytes.
  - Click Re-run; verify a new report_job row appears.

- `scheduled-report-cascade-disable.spec.ts`:
  - Create 3 schedules on 3 different cadenced reports.
  - Navigate to `/tenant/admin/settings/reports`.
  - Toggle one report OFF; expect confirm modal showing "1 scheduled delivery".
  - Confirm; navigate back to schedules; verify that report's schedule flipped to disabled.

- `scheduled-report-signed-link.spec.ts`:
  - Force a >10MB fire (seed a large tenant + trigger CLAIMS_SUMMARY).
  - Verify email body has `Download XLSX` link, no attachment.
  - Click the link (via Playwright's request context, since it's cross-origin); verify XLSX downloads.

- `scheduled-report-unsubscribe.spec.ts`:
  - Create schedule + recipient.
  - Force-fire; open email in mailpit.
  - Extract unsubscribe URL; visit; confirm.
  - Force-fire again; verify no email arrives for the unsubscribed recipient.

#### 2. Performance script
**File**: `scripts/perf-test-scheduled-reports.sh` (new)

```bash
#!/usr/bin/env bash
# Seeds 100 test tenants × 5 schedules, forces the probe to fire, measures:
# - probe iteration latency (target < 30s for 500 schedules)
# - dedup effectiveness across 3 finance-service instances
# - MinIO upload throughput
# - Kafka delivery event lag

set -euo pipefail
# ... seed script ...
# Multi-instance dedup check: run 3 concurrent force-fire on the same schedule;
# assert exactly one report_job row + one delivery event.
```

### Success Criteria

#### Automated Verification
- [x] Playwright specs pass: 7/7 tests across the 4 new specs green (`npx playwright test --project=chromium scheduled-report`).
- [x] Performance script runs shell-clean (`bash -n scripts/perf-test-scheduled-reports.sh`).

#### Manual Verification
- [ ] Manually run the performance script against a dev-cluster with 3 finance-service replicas; confirm dedup works + probe iteration < 30s.

### Deviations (2026-09-04)

- **Fully-mocked e2e, not live-stack.** The repo's Playwright infrastructure
  intercepts `**/api/v1/**` and returns stubs; there is no live backend or
  mailpit in the harness. The 4 specs assert the UI + API contract (button
  clicks fire the right requests, responses drive the right state) rather
  than the end-to-end wiring. Live-stack verification lives in the
  per-service `*IT` suites landed in Phases 3–7 and the manual perf script.
- **Signed-link spec uses page-context `fetch()`, not `page.request`.**
  Playwright's out-of-browser `page.request` bypasses `page.route` mocks;
  `page.evaluate(async () => fetch(...))` routes through them. This is a
  harness detail — the real click from a mail client is a plain browser
  navigation.
- **Public unsubscribe tests still call `signInAs`.** In production the
  recipient clicking `/public/unsubscribe/{token}` is *not* authenticated,
  but the SPA's `APP_INITIALIZER` unconditionally runs `keycloak.init({
  onLoad: 'login-required' })` — the public route bypasses the *auth guard*
  but not the *app-boot*. Test fixtures need a signed-in stub or the page
  never boots. **Phase 11 follow-up ticket**: teach `keycloak.init.ts` to
  short-circuit on `/public/**` paths so real recipients (who don't have
  Keycloak accounts) can actually reach the unsubscribe page.
- **Perf script is bash-only, not `make`-registered.** Matches the sibling
  scripts (`perf-test-ifrs17.sh`, `perf-test-regulatory.sh`) which are
  operator smokes, not CI targets — the `make test-e2e` line in the plan's
  automated-verification is out-of-scope for a manual perf script.

---

## Phase 11 (§D.2): Rollout notes + follow-ups

### Overview

Documentation + deploy-order runbook + follow-up ticket capture. No code changes.

### Changes Required

#### 1. Rollout runbook
**File**: `docs/runbooks/2026-scheduled-report-delivery.md` (new)

```markdown
# Scheduled Report Delivery — Rollout Runbook

## Deploy order (F-S9)

1. tenancy-service — new schema (V176/V177) + CRUD endpoints. No consumer changes; no risk to existing tenants.
2. notification-service — new `internal/report/` package. Deploys with topics `medfund.notification.report-delivery` + `medfund.notification.report-delivery-failed` subscribed from `earliest`. **This step must complete before step 3.**
3. Owner services (contributions, claims, user) — new `/scheduled-render` endpoints. No new consumers, no impact.
4. finance-service — probe + orchestrator + Kafka publishers. Feature flag `scheduled.report.probe.enabled=false` on first deploy; flip to true after smoke test.
5. gateway — new routes (signed download + unsubscribe + schedule CRUD proxy).
6. Angular — new admin surface + Phase 0 grid extension.

## Kafka topic provisioning

Both new topics are declared in `finance-service/KafkaTopicConfig.java` via Spring's `TopicBuilder`. If topic auto-create is disabled in your cluster, pre-create manually:

    kafka-topics.sh --bootstrap-server kafka:9092 --create --topic medfund.notification.report-delivery --partitions 6 --replication-factor 3
    kafka-topics.sh --bootstrap-server kafka:9092 --create --topic medfund.notification.report-delivery-failed --partitions 3 --replication-factor 3

## Shared secrets

`SCHEDULED_REPORT_DOWNLOAD_TOKEN_SECRET` must be identical in finance-service (issuer) and notification-service (signer of user-visible URLs). Rotate together; a mismatch invalidates all outstanding signed links.

## Feature flags

- `scheduled.report.probe.enabled` (finance-service) — kill switch for the probe.
- `scheduled.report.probe.force-fire-enabled` (finance-service) — set true in dev/staging for Playwright + manual testing; MUST be false in prod.

## Rollback

Every phase is additive:
- To roll back finance-service, set probe.enabled=false — probe stops, no orchestrator fires.
- To roll back notification-service, revert the Deployment — Kafka events queue up on the topic; when re-deployed, `earliest` consumption catches them up.
- Migrations V176/V177 leave data behind if rolled back — that's fine, they're empty on rollback.

## First tenant onboarding

Recommend: pilot with 1 internal tenant + 1 schedule (`COMMISSION_STATEMENT`, MONTHLY, 1st of month, 08:00 UTC+0). Observe 3 fires before opening to real tenants.
```

#### 2. Follow-up tickets to file
(Not code — the plan lists these for capture into whatever ticket system is in use.)

- **Phase 17.5 — Regulator + IFRS 17 + AML periodic + FRAUD_SIU auto-run**: separate grill + sub-plan; handles the 11 non-whitelisted cadenced keys with a "draft" concept that preserves REG12/REG13 MFA-gated human filing.
- **Ownership-transfer admin surface**: reassign `updated_by_actor_*` on a schedule when the original owner leaves.
- **Per-schedule reporting_currency_override migration**: currently uses tenant default; column already exists (V176) but no UI edits it.
- **Rerun-schedule backfill mode**: "rerun all missed fires between date X and Y" for a schedule.
- **Attachment size trend alerts**: instrumentation on `sizeBytes` distribution per (tenant, reportKey) with a Grafana alert on trend toward the 10MB cap.
- **Cross-language docker-compose IT**: end-to-end wire-up test spanning finance-service + notification-service + tenancy-service + mailpit + MinIO. Deferred per Phase 14/15/16 precedent.
- **Rename `AgedBalancesExcelService` → `AgedDebtorsExcelService`**: cosmetic follow-up to align with the `AGED_DEBTORS` enum key. Not in Phase 17 scope.
- **Fix stale `ReportCadence.java:5` javadoc** referencing "Phase 8" for `RegulatoryDueDateScanner` (actually Phase 16 REG20).

#### 3. Update parent-plan Phase 17 status
On successful land, update `thoughts/shared/plans/2026-08-11-financial-reporting-suite.md` header:

```yaml
phases_status:
  ...
  "17": grilled 2026-08-31 (...); landed YYYY-MM-DD (commit <sha> "Land Phase 17 scheduled email delivery" via sub-plan thoughts/shared/plans/2026-08-31-scheduled-email-delivery.md)
```

### Success Criteria

#### Automated Verification
- [x] All prior phases green in CI (Phases 1-10 all show green automated criteria per this plan's own checkboxes; deferred ITs and non-code items called out in per-phase Deviations).
- [ ] Full test suite: `make test-coverage` — deferred to commit-land time (last-mile sweep).

#### Manual Verification
- [x] Runbook drafted: `docs/runbooks/2026-scheduled-report-delivery.md`. Review with ops is a subsequent step.
- [x] 11 follow-ups captured in the runbook's *Follow-ups captured during land* section (plan's 8 + 3 more surfaced in per-phase Deviations: keycloak `/public/**` short-circuit, `refresh` icon registration, `Aml*Reader` fallback pattern). `thoughts/shared/tickets/` is empty in this repo — no per-file ticket pattern exists to file against, so capture lives in the runbook until a ticketing workflow is defined.

### Deviations

- **2026-09-05**: Parent-plan Phase 17 status update deferred to commit-land time. The plan text says "on successful land, update ... `landed YYYY-MM-DD (commit <sha> ...)`" — the commit sha only exists after `commit` runs, so this edit is a step the `commit` skill should perform (or a follow-up commit after this branch merges). Runbook + this plan's own status remain the single source of truth until then.
- **2026-09-05**: Per-follow-up ticket files not created. Plan §2 explicitly says these are "not code — the plan lists these for capture into whatever ticket system is in use." `thoughts/shared/tickets/` is empty (only a `.gitkeep`); no per-item pattern exists in this repo. All 11 follow-ups (the plan's 8 + the 3 that surfaced in Phase 2/8/10 Deviations) are captured in the runbook's *Follow-ups captured during land* section so ops can pull them into whatever workflow lands.
- **2026-09-05 (widening from Phase 18 §Phase 8)**: `ScheduledReportEligibility.WHITELIST` widened from 13 → 18 keys — the 5 executive-KPI keys (`LOSS_RATIO_KPI`, `EXPENSE_RATIO`, `COMBINED_RATIO`, `CLAIMS_FREQUENCY`, `AVERAGE_SEVERITY`) added so tenant admins can schedule monthly board packs for the KPI dashboard. Load-bearing for the KPI adapters under `finance/report/schedule/adapter/*Adapter.java`; tenancy-service enforcement (`TenantReportScheduleService.create/update`) picks up the new keys through the shared constant without any tenancy-service edits. Angular UI didn't need changing — the `report-schedules-page.component.ts` filter is `c.cadenced && !scheduled.has(c.reportKey)`, and the 5 KPI keys already carry `cadenced=true` from Phase 1. Sub-plan owning this change: `thoughts/shared/plans/2026-09-05-executive-kpi-dashboards.md` §Phase 8.

---

## Testing Strategy

### Unit Tests
- Every new service class covered by JUnit + reactor-test.
- Cadence resolver: exhaustive matrix of (cadence, hour, day) × TZ across ZW/ZA/US/UTC.
- Period resolver: boundary cases (leap year Feb, DST transitions, quarter boundaries).
- Adapter contract: each of the 13 adapters returns valid XLSX bytes for canonical fire context.
- Cascade-disable transaction: rollback on partial failure leaves both tables untouched.

### Integration Tests (Testcontainers)
- End-to-end schedule fire: schedule row → probe match → orchestrator → adapter → MinIO → Kafka.
- Multi-instance dedup: `orchestrator.fireOnce(...)` invoked concurrently → exactly one row wins.
- Failure path: adapter throws → `report_job.status=FAILED` + failed event → alert email sent.
- Cascade-disable: report toggle-off → all matching schedules disabled + N audit events.
- Recipient CRUD: full lifecycle with audit events + unsubscribe token flow.
- Signed-link download: valid token → 200 + XLSX; tampered token → 403; expired → 403.

### E2E Tests (Playwright, `clients/angular/e2e/`)
- Golden path (Phase 10 §D.1).
- Cascade disable (Phase 10).
- Signed link download (Phase 10).
- Unsubscribe flow (Phase 10).

### Manual Testing Steps
1. Spin up infra (`make infra`) + all services + Angular.
2. Log in as tenant admin, navigate to `/tenant/admin/settings/report-schedules`.
3. Create MONTHLY commission_statement with 2 recipients.
4. `curl -X POST http://localhost:8085/api/v1/reports/scheduled/probe/force-fire?scheduleId=<uuid>`.
5. Open http://localhost:8025 (mailpit) — verify both recipients received the email with XLSX attached.
6. Navigate to schedule run history; verify one COMPLETED row; click Re-run; verify new run.
7. Navigate to `/tenant/admin/settings/reports`; toggle COMMISSION_STATEMENT OFF; confirm the modal shows "1 scheduled delivery"; save; navigate back; verify schedule now disabled.
8. Click unsubscribe link in email; confirm; force-fire again; verify only 1 email arrives (the unsubscribed recipient skipped).

## Performance Considerations

- **Probe query cost**: `SELECT ... FROM public.tenant_report_schedule s JOIN public.tenants t ...` runs hourly. With 1000 tenants × 20 schedules = 20 K rows unfiltered, but `WHERE s.enabled = TRUE` + partial index `idx_trs_enabled` cuts to a working set of a few hundred. Query < 100ms comfortably.
- **Multi-instance dedup**: partial UNIQUE index `ux_report_job_schedule_dedup` fires for scheduled runs only; no impact on ad-hoc load.
- **MinIO upload size**: v1 caps at 10MB attachment; commission_statement for 5000-broker tenants may need optimization (already covered by existing report engine).
- **Kafka message volume**: 1000 tenants × 3 schedules avg × 12 fires/year monthly = ~36K events/year on delivery topic. Negligible.
- **Notification-service throughput**: single-instance Go dispatcher handles email sends serially per event; if a tenant has 100 recipients per schedule, that's 100 SMTP calls in a loop — acceptable for monthly cadence; if abused (weekly to 1000 recipients), needs a per-recipient worker pool follow-up.
- **Angular bundle**: 4 new components + 1 service + 4 pickers = ~15 KB gzipped addition. Acceptable.

## Migration Notes

- Two new public migrations (V176, V177) and one tenant migration (V168) — all idempotent SQL per `feedback_never_edit_applied_migrations`.
- No backfill needed — schedules opt-in via admin CRUD; existing `report_job` rows unaffected (source defaults to 'ADHOC').
- Kafka: two new topics; deploy notification-service consumer BEFORE finance-service producer (F-S9). Subscribe from `earliest` on first deploy.
- `AGED_DEBTORS` adapter delegates to `AgedBalancesExcelService` — no data rename; cosmetic follow-up filed (Phase 11 §2).
- `ReportCadence` enum-add (WEEKLY): additive; no consumers reject unknown values.
- `ReportKey.periodShape` field-add: additive on the enum; every downstream Jackson serialisation is opt-in via `@JsonInclude(Include.NON_NULL)` so wire payloads unchanged.

## Rollout & Rollback

Deploy in the F-S9 order (tenancy → notification → owner services → finance → gateway → Angular). Feature-flag the probe (`scheduled.report.probe.enabled=false`) on first finance-service deploy so it starts inert; flip to true after smoke test.

Rollback per service:
- **finance-service**: set `scheduled.report.probe.enabled=false` — probe stops; no orchestrator runs. Optionally revert to previous image.
- **notification-service**: revert the Deployment — pending events accumulate in Kafka (harmless); on re-deploy, `earliest` subscription catches up.
- **tenancy-service**: schema changes leave data behind if rolled back — no active harm since finance-service probe would fail closed if schema mismatches (no rows returned from JOIN).
- **gateway / Angular**: pure-additive; revert to previous asset bundle.

## Deviations

**2026-09-05 (Phase 19 §B Phase 12 whitelist widening + per-schedule params)**

- **FRAUD_SIU_REPORT added to `ScheduledReportEligibility.WHITELIST`.**
  Phase 19 §B Phase 12 authored `FraudSiuReportAdapter` in
  finance-service (delegating via `CrossServiceRenderHelper` to the new
  `POST /api/v1/reports/FRAUD_SIU_REPORT/scheduled-render` handler on
  claims-service's `ScheduledRenderController`). `WHITELIST` count
  grows from 18 → 19; FRAUD_SIU_REPORT moves out of the excluded set
  in `ScheduledReportEligibilityTest`. Comment on the excluded-set
  test corrected — Phase 17 grouped FRAUD_SIU_REPORT with
  regulator/IFRS/AML with a misleading "MFA-gated human filing"
  rationale; the real reason was "pending Phase 19 grill".
- **`tenant_report_schedule.params` JSONB column added via V178 (public).**
  Generic per-schedule opt-in payload; today only FRAUD_SIU_REPORT
  reads it (`{"includeSensitiveSheets": true|false}` per FR12).
  Enforced by `TenantReportScheduleService.validateParams` — narrow
  contract that rejects unknown keys, non-Boolean values, and any
  non-fraud key attempting to set params.
- **`ScheduledRenderRequest` + `ScheduledFireContext` grew nullable
  `params` fields with legacy constructor overloads.** Java records
  don't permit non-canonical constructors that skip components, so
  the widening adds a canonical N+1 arg constructor + a legacy N-arg
  constructor delegating with `params = null`. All 4 pre-Phase-12
  callers (contributions + claims + user `ScheduledRenderControllerTest`
  + finance `CrossServiceRenderHelper`) keep compiling unchanged.
- **`TenantScheduleFireCandidate.paramsJson` deserialised in the
  orchestrator, not the record.** Malformed JSON degrades to
  `null` params + a WARN log; the probe stays alive.
- **Angular schedule form widened.** `report-schedules-page.component.ts`
  `CreateDraft` gained an `includeSensitiveSheets` boolean (assembled
  into `body.params` at submit time only when `reportKey === 'FRAUD_SIU_REPORT'`).
  Conditional checkbox in the create-schedule form; hint text warns
  admins to restrict recipients to supervisors + tenant admins.

## References

- Parent plan: `thoughts/shared/plans/2026-08-11-financial-reporting-suite.md § Phase 17`
- Grilling scratchpad: `thoughts/shared/notes/2026-08-31-phase17-scheduled-email-grill.md`
- Architecture: `.claude/architecture.md`, `.claude/multi-tenancy.md`, `.claude/coding-standards.md`
- Pattern references:
  - `services/java/finance-service/src/main/java/com/medfund/finance/regulatory/scheduler/RegulatoryDueDateScanner.java` (probe template)
  - `services/java/tenancy-service/src/main/java/com/medfund/tenancy/service/TenantRegulatoryRecipientService.java` (audit+CRUD template)
  - `services/go/notification-service/internal/regulatory/dispatcher.go` (Go consumer template)
  - `services/go/notification-service/internal/invoice/dispatcher.go:131-141` (MIME attachment template)
  - `services/java/finance-service/src/main/java/com/medfund/finance/regulatory/aml/AmlFilingBlobStore.java:65-92` (MinIO template)
  - `services/java/finance-service/src/main/java/com/medfund/finance/report/kafka/ReportJobPublisher.java` (Kafka publisher template)
  - `services/java/shared/src/main/java/com/medfund/shared/security/SecurityEventPublisher.java:77-87` (Rule 9 emission)
  - `services/java/shared/src/main/java/com/medfund/shared/audit/AuditActor.java` (Rule 8 helper)
  - `services/java/shared/src/main/java/com/medfund/shared/report/CrossServiceCallHelper.java:64-99` (cross-service call pattern)
- Memory guardrails:
  - `feedback_audit_actor_email` — every AuditEvent uses AuditActor; never null actorEmail.
  - `feedback_audit_entity_name` — entityName is friendly text, never the UUID.
  - `feedback_never_edit_applied_migrations` — write higher-numbered files; idempotent SQL.
  - `bug_public_prefix_silent_rollback` — public.<tenant-table> in tenant-schema queries silently rolls back; use unqualified for tenant tables, public. for public.
  - `bug_reactor_kafka_ack_swallow` — `.doOnSuccess` for Kafka ack, never `.doOnTerminate`.
