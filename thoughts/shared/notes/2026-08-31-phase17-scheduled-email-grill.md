---
date: 2026-08-31
target: thoughts/shared/plans/2026-08-11-financial-reporting-suite.md § Phase 17
scope: Grill Phase 17 (Scheduled Email Delivery) to code altitude, ready for create-plan → sub-plan
decision-prefix: S* (Scheduled) — to avoid collision with plan-wide G* + phase prefixes R*/P*/U*/L*/A*/I*/REG*
---

# Ground verification (2026-08-31)

Subagent report + spot-checks confirmed:

- `tenant_report_config` (V130, tenancy-service) is pure on/off — no `recipients`/`cadence`/`enabled_scheduled_delivery` fields.
  Columns: `id, tenant_id, report_key, enabled, updated_at, updated_by`. UNIQUE(tenant_id, report_key).
  Angular admin surface: `clients/angular/src/app/pages/tenant-admin/settings/reports/reports-tab.component.ts:25` — enable/disable grid, no scheduling UI.
- `ReportKey.cadenced` (services/java/shared/src/main/java/com/medfund/shared/report/ReportKey.java:153) already declared per key — 24 keys marked `cadenced=true` today.
  Javadoc line 148-152: "Drives whether the Phase 17 schedule form exposes the switch for this report at all — one-off exports don't make sense to schedule." **Phase 17's per-key eligibility gate is already in tree.**
- `ReportCadence` enum exists (services/java/shared/src/main/java/com/medfund/shared/report/ReportCadence.java): MONTHLY / QUARTERLY / ANNUAL / EVENT_DRIVEN.
- `ReportCadenceCatalog` maps **regulatory keys only** (Phase 16 REG14) → (cadence, daysPostPeriodEnd). Non-regulatory cadenced keys (COMMISSION_STATEMENT, LOSS_RATIO, COLLECTION_RATE, CASH_FLOW_FORECAST_13W, POLICY_MOVEMENT, PERSISTENCY_COHORT, GROUP_CENSUS, PROVIDER_NETWORK_UTILIZATION, REINSURANCE_*, UPR_MOVEMENT, CLAIMS_SUMMARY, AGED_DEBTORS, IFRS17_*) have no cadence in code yet.
- Phase 16 REG20 ships live: `finance-service/regulatory/scheduler/RegulatoryDueDateScanner.java:57` (@Scheduled 03:00 UTC daily) → Kafka `medfund.regulatory.due-date-approaching` (tiers 7d/1d/0d/-1d) → notification-service `internal/regulatory/dispatcher.go:29` (reads `public.tenant_regulatory_recipient` V169: `email, display_name, subscribed_event_tiers VARCHAR(30)[], is_active`, unique(tenant_id, email)) → email (no XLSX attachment; body-only reminder).
  **Regulatory path already emails the humans — but it's a "your filing is due" reminder, not "here's the generated XLSX." Phase 17 must not double-notify.**
- notification-service Mail sender fully supports MIME attachments (invoice dispatcher already sends them: `internal/invoice/dispatcher.go:136-140`). SMTPSender uses PLAIN auth when creds set, no auth when empty (mailpit dev mode).
- `report_job` (V151 rename from V141) + `report_job_chunk` (V155) + retention job (OPERATIONAL_90D / STATUTORY_7Y) + MinIO bucket `medfund-report-payloads` all deployed.
- `ReportJobPublisher` (finance-service) → `medfund.report.job-requested` Kafka. Consumer (Python ai-service for actuarial keys; Java finance-service for non-actuarial keys per Phase 14 A8 dispatch table). Terminal-status trigger prevents rewrites.
- No ShedLock — cron dedup is table-based. Regulatory scanner uses `public.regulatory_due_date_notification_sent` (24h dedup per tenant + key + tier).
- Grep of `report_schedule|report-delivery|ReportDelivery|ReportSchedule` = empty; no collisions.
- Kafka naming convention: `medfund.<domain>.<event>` (dot-separated, dashed lowercase).

## Cadenced keys (24 today per ReportKey.java grep)

| Key | Family | Home service | Notes |
|---|---|---|---|
| COLLECTION_RATE | RECEIPTS | contributions | Non-regulatory, Java-only |
| AGED_DEBTORS | DEBTORS | contributions | Non-regulatory, Java-only |
| CLAIMS_SUMMARY | CLAIMS_FINANCIAL | claims | Java-only |
| LOSS_RATIO | RECONCILIATION | finance (cross) | Java-only |
| CASH_FLOW_FORECAST_13W | RECONCILIATION | contributions | Java-only |
| POLICY_MOVEMENT | POLICY_LIFECYCLE | user-service | Java-only |
| PERSISTENCY_COHORT | POLICY_LIFECYCLE | user-service | Java-only |
| GROUP_CENSUS | POLICY_LIFECYCLE | user-service | Java-only |
| PROVIDER_NETWORK_UTILIZATION | CLAIMS_FINANCIAL | claims | Java-only |
| REINSURANCE_CESSION_BORDEREAU | REINSURANCE | finance | Java-only |
| REINSURANCE_RECOVERIES | REINSURANCE | finance | Java-only |
| COMMISSION_STATEMENT | COMMISSION | finance | Java-only |
| UPR_MOVEMENT | UNDERWRITING | contributions | Java-only |
| IFRS17_LRC_LIC_RECONCILIATION | REGULATORY | finance (async, chunked) | Python-heavy compute |
| IFRS17_INSURANCE_REVENUE_SERVICE_RESULT | REGULATORY | finance (async, chunked) | Python-heavy compute |
| IPEC_QUARTERLY_RETURN | PRUDENTIAL | finance (async) | Regulator template; jurisdiction-gated |
| CMS_ASR | PRUDENTIAL | finance (async) | Regulator template; jurisdiction-gated |
| NAIC_SCHEDULE_P | PRUDENTIAL | finance (async) | Regulator template; jurisdiction-gated |
| NAIC_SCHEDULE_F | PRUDENTIAL | finance (async) | Regulator template; jurisdiction-gated |
| PMB_SPEND | COMPLIANCE | finance | Regulator; country-gated |
| AML_STR | COMPLIANCE | finance | Two shapes — event-driven (per-STR) + periodic (Phase 17 target) |
| TAX_WITHHELD_RETURN | TAX | finance | Regulator; country-gated |
| VAT_RETURN | TAX | finance | Regulator; country-gated |
| FRAUD_SIU_REPORT | FRAUD | claims (Phase 19 not built) | Speculative — Phase 19 pending |

## Decisions Log

### S1 — Scope of Phase 17 v1: 13 non-regulator operational cadenced keys.

**Options offered**: (a) Non-regulator subset [13 keys]; (b) all 24 cadenced; (c) operational + regulatory drafts; (d) operational + FRAUD_SIU speculative.
**Chosen**: (a).
**Reasoning**: Regulator + IFRS 17 filing is a human MFA-gated act (REG13) with REG20 due-date reminders already in tree — auto-generating draft XLSX conflates the "draft workspace" and "official submission" concepts (regulatory_submission chain per REG12). AML_STR periodic can join once the per-STR event-driven primary path settles. FRAUD_SIU compute doesn't exist (Phase 19 not built). Keeping v1 tight lets Phase 17 ship the delivery mechanism cleanly; regulatory auto-run is a natural follow-up phase.
**Keys in scope**: COMMISSION_STATEMENT, LOSS_RATIO, COLLECTION_RATE, AGED_DEBTORS, CASH_FLOW_FORECAST_13W, CLAIMS_SUMMARY, POLICY_MOVEMENT, PERSISTENCY_COHORT, GROUP_CENSUS, PROVIDER_NETWORK_UTILIZATION, REINSURANCE_CESSION_BORDEREAU, REINSURANCE_RECOVERIES, UPR_MOVEMENT.
**Consequence**: Phase 17 does NOT touch RegulatoryDueDateScanner path; NO MFA-step-up; NO regulatory_submission integration; NO ai-service Python dispatch.

### S2 — Execution architecture: reuse report_job + MinIO + medfund.notification.report-delivery.

**Options offered**: (a) report_job + MinIO + delivery event; (b) direct publish (no report_job); (c) inline XLSX bytes in Kafka; (d) reuse full report_job Kafka async pipeline.
**Chosen**: (a).
**Reasoning**: Uniform persistence with ad-hoc exports (Phase 14/15/16 precedent), free re-download via Angular history, existing retention job handles cleanup, MinIO already the XLSX blob store. Direct-publish would duplicate retention logic; inline bytes hit ReportJobPublisher's 900KB reject; full-async Kafka pipe adds a hop for a compute that lives in the finance-service caller anyway.
**Shape**:
- `@Scheduled` job in finance-service fires per (tenant, report_key, cadence) → INSERT `report_job (status=REQUESTED, source=SCHEDULED, schedule_id FK)` → invokes existing Java shape service in-process → `Workbook` bytes → uploads to MinIO `medfund-report-payloads/{tenantId}/{yyyy}/{MM}/{jobId}.xlsx` → UPDATE `report_job (status=COMPLETED, result_json={xlsxRef, sha256, sizeBytes, rowCount}, completed_at)` → publish `medfund.notification.report-delivery` event `{jobId, scheduleId, tenantId, reportKey, xlsxRef, sha256, sizeBytes, cadenceLabel, periodStart, periodEnd, occurredAt, schemaVersion:1}`.
- notification-service `internal/report/dispatcher.go` consumes, fetches XLSX from MinIO, renders per-tenant template body, attaches XLSX (MIME), sends via SMTPSender.
- Adds two new columns to `report_job`: `source VARCHAR(20) NOT NULL DEFAULT 'ADHOC' CHECK IN ('ADHOC','SCHEDULED')` + `schedule_id UUID NULL REFERENCES tenant_report_schedule(id) ON DELETE SET NULL` (nullable because schedule can be deleted while runs remain).
- No new retention logic; existing OPERATIONAL_90D applies (keep last 20 per (tenant, report_key) covers 20 months of monthly runs — enough for "last year's Feb" re-download).

### S3 — Cadence source: per-tenant ReportCadence enum + fixed conventions.

**Options offered**: (a) enum + fixed conventions; (b) raw cron_expr; (c) enum + optional cron override; (d) code-declared per ReportKey.
**Chosen**: (a).
**Reasoning**: Regulator reports have cadence-in-code (REG14) because law says so. Operational reports need per-tenant flex (broker with quarterly commission vs. broker with monthly). But full cron is a footgun; 95% of tenants want "1st of every month at 08:00" which the enum expresses cleanly.
**Shape**:
- Extend `ReportCadence` enum to add `WEEKLY` (AGED_DEBTORS + COLLECTION_RATE are the natural weekly candidates; existing enum + `WEEKLY` = 5 values including EVENT_DRIVEN which stays unused by Phase 17).
- `tenant_report_schedule` carries: `cadence VARCHAR(20) NOT NULL CHECK IN ('WEEKLY','MONTHLY','QUARTERLY','ANNUAL')` + `hour_of_day INT NOT NULL DEFAULT 8 CHECK BETWEEN 0 AND 23` + `day_of_week INT NULL CHECK BETWEEN 1 AND 7` (WEEKLY only) + `day_of_month INT NULL DEFAULT 1 CHECK BETWEEN 1 AND 28` (MONTHLY only; capped at 28 to avoid Feb-boundary confusion).
- Server derives the Quartz cron internally at write time and stores it too (`cron_expr VARCHAR(64) NOT NULL GENERATED ALWAYS AS ...` or computed in Java at INSERT). Tenant never sees the cron string.
- QUARTERLY convention = 1st day of Jan/Apr/Jul/Oct. ANNUAL = 1st day of Jan (tenant fiscal-year support = follow-up).

### S4 — Timezone: tenant TZ + hourly probe.

**Options offered**: (a) tenant TZ + hourly probe; (b) UTC everywhere; (c) tenant TZ + per-schedule TaskScheduler bean; (d) server-local TZ.
**Chosen**: (a).
**Reasoning**: `Tenant.timezone` (String, `services/java/tenancy-service/src/main/java/com/medfund/tenancy/entity/Tenant.java:49`) is already populated. Hourly probe pattern is simple, multi-instance safe via table dedup (no ShedLock needed — precedent: RegulatoryDueDateScanner+regulatory_due_date_notification_sent). Per-schedule TaskScheduler bean is over-engineered for a monthly cadence. UTC-only would produce disruptive email arrival times across ZW/ZA/US tenants.
**Shape**:
- New `ScheduledReportProbe` @Scheduled(cron="0 5 * * * *") in finance-service — runs every hour at HH:05.
- Query: `SELECT trs.*, t.timezone FROM tenant_report_schedule trs JOIN tenants t ON trs.tenant_id=t.id WHERE trs.enabled=TRUE`.
- For each row: compute "now in tenant TZ" = `Instant.now().atZone(ZoneId.of(t.timezone))`. Check cadence match:
  - WEEKLY: `dayOfWeek == schedule.day_of_week AND hourOfDay == schedule.hour_of_day`
  - MONTHLY: `dayOfMonth == schedule.day_of_month AND hourOfDay == schedule.hour_of_day`
  - QUARTERLY: `dayOfMonth == 1 AND monthValue IN (1,4,7,10) AND hourOfDay == schedule.hour_of_day`
  - ANNUAL: `dayOfMonth == 1 AND monthValue == 1 AND hourOfDay == schedule.hour_of_day`
- On match: derive `periodStart/periodEnd` (S5), enqueue `report_job` (S2), fire.
- Multi-instance dedup: `report_job` UNIQUE index on `(tenant_id, report_key, schedule_id, period_start)` — INSERT of a duplicate raises `DuplicateKeyException` which the probe swallows silently (one instance wins the race, others log-and-move-on).
- Tenant TZ change: takes effect on the next hourly probe; no rehydration needed.
- Invalid `tenant.timezone` (unparseable by `ZoneId.of`) → log warning, skip that tenant's schedules for the probe; audit event on tenant TZ mutation catches the bad-write at source.

### S5 — Report period: per-ReportKey shape enum.

**Options offered**: (a) `ReportPeriodShape` per ReportKey; (b) per-schedule lookback; (c) hardcode previous-period; (d) firedAt-only, shape decides.
**Chosen**: (a).
**Reasoning**: 13 cadenced keys in scope split cleanly into "for the period" (period reports) and "as-of now" (snapshot reports); baking the split into the enum keeps every downstream layer (scheduler, shape service, audit log, email subject line) uniform.
**Shape**:
- New sealed enum `ReportPeriodShape { PREVIOUS_COMPLETE_PERIOD, AS_OF_FIRE_TIME }` in `shared/report/`.
- Extend `ReportKey` with `periodShape` field (final, per-constant declaration). Values:
  - `PREVIOUS_COMPLETE_PERIOD`: COMMISSION_STATEMENT, LOSS_RATIO, COLLECTION_RATE, CLAIMS_SUMMARY, POLICY_MOVEMENT, PERSISTENCY_COHORT, GROUP_CENSUS, PROVIDER_NETWORK_UTILIZATION, REINSURANCE_CESSION_BORDEREAU, REINSURANCE_RECOVERIES, UPR_MOVEMENT.
  - `AS_OF_FIRE_TIME`: AGED_DEBTORS, CASH_FLOW_FORECAST_13W.
  - Non-cadenced keys: field is nullable / defaults to null (unused).
- Scheduler resolves `(periodStart, periodEnd, asOf)` from `(cadence, firedAt, shape)`:
  - PREVIOUS_COMPLETE_PERIOD + WEEKLY → periodStart=firedAt.minus(7d).atStartOfWeek, periodEnd=firedAt.atStartOfWeek.minus(1s).
  - PREVIOUS_COMPLETE_PERIOD + MONTHLY → periodStart=firedAt.withDayOfMonth(1).minusMonths(1), periodEnd=firedAt.withDayOfMonth(1).minus(1s).
  - PREVIOUS_COMPLETE_PERIOD + QUARTERLY → previous complete calendar quarter.
  - PREVIOUS_COMPLETE_PERIOD + ANNUAL → previous complete calendar year.
  - AS_OF_FIRE_TIME → periodStart=periodEnd=asOf=firedAt (dates all equal).
- All shape-service call signatures already accept `(from, to)` or `(asOf)` — new adapter wraps each to the uniform `(periodStart, periodEnd, asOf)` shape.

### S6 — Recipients: sibling table `tenant_report_schedule_recipient`.

**Options offered**: (a) sibling table per schedule; (b) JSONB per outline; (c) generic per-report recipient; (d) generalise tenant_regulatory_recipient.
**Chosen**: (a).
**Reasoning**: Mirror REG13/REG20's proven pattern (`tenant_regulatory_recipient`). Rule 8 audit is cleaner per-row. Unsubscribe token needs a stable recipient identity. Regulatory shape stays untouched.
**Shape**:
- New public migration `V17x__tenant_report_schedule_recipient.sql`:
  ```sql
  CREATE TABLE IF NOT EXISTS public.tenant_report_schedule_recipient (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    schedule_id UUID NOT NULL REFERENCES public.tenant_report_schedule(id) ON DELETE CASCADE,
    email VARCHAR(255) NOT NULL,
    display_name VARCHAR(160) NULL,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    unsubscribe_token UUID NOT NULL DEFAULT gen_random_uuid() UNIQUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    actor_id UUID NOT NULL,
    actor_email VARCHAR(255) NOT NULL,
    CONSTRAINT uq_trsr UNIQUE (schedule_id, LOWER(email))
  );
  CREATE INDEX idx_trsr_schedule_active ON public.tenant_report_schedule_recipient(schedule_id) WHERE is_active = TRUE;
  ```
- Overrides the outline's JSONB shape — outline superseded.
- Admin CRUD in tenancy-service (`TenantReportScheduleRecipientController` + service) — clone pattern from `TenantRegulatoryRecipientController` (Phase 16 §0).
- Unsubscribe: notification-service exposes `POST /unsubscribe/{token}` (public route, no auth) → sets `is_active=FALSE` + audit event; email body renders the token URL.

### S7 — XLSX delivery mechanism: MIME attachment ≤10 MB, else signed download link.

**Options offered**: (a) attach ≤10MB, else link; (b) always attach; (c) always link; (d) both.
**Chosen**: (a).
**Reasoning**: Invoice dispatcher precedent for attachments; 13-key operational XLSX typically well under 10 MB; commission_statement for large broker networks can exceed SMTP relay caps (10-25 MB); link path avoids silent bounces + integrates with existing MinIO storage.
**Shape**:
- Dispatcher reads `sizeBytes` from delivery event payload; if < `10 * 1024 * 1024` → `mail.Attachment{Filename, ContentType: "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", Data: minIOFetchBytes()}` + set on `Message.Attachments`.
- Else → render body with `{{.DownloadUrl}}` template variable pointing at gateway route `GET /api/v1/reports/scheduled/{jobId}/download?token={signedToken}` — gateway verifies token (HMAC over `{jobId, tenantId, recipientEmail, exp}` with 7-day expiry) then fans out to finance-service which streams from MinIO.
- Email subject template: `[{{.TenantName}}] {{.ReportLabel}} — {{.CadenceLabel}} — {{.PeriodLabel}}` (e.g. `[Steward] Commission statement — Monthly — August 2026`).
- Email body template: per-tenant override via existing `tenant_email_templates` mechanism (`services/go/notification-service/internal/template/resolver.go`); bundled default renders period + attachment note or download link + unsubscribe URL footer.
- Filename convention: `{report_key_lower}_{period_start}_{period_end}.xlsx` (e.g. `commission_statement_2026-08-01_2026-08-31.xlsx`).

### S8 — Failure semantics: fail-once + alert + manual re-run; no auto-retry, no auto-disable.

**Options offered**: (a) fail-once + alert; (b) retry-N + alert; (c) fail silently; (d) auto-disable after N failures.
**Chosen**: (a).
**Reasoning**: Retry loops add state-machine complexity (retry_count, next_retry_at) for a small transient-failure surface; auto-disable is a big hammer for a tenant who then has to re-enable manually. Fail-loud + operator handoff is well-matched to a monthly cadence and to the audit-trail-first philosophy.
**Shape**:
- Failure at any of: shape service exception, MinIO PUT failure, Kafka publish failure, delivery event dispatch, SMTP 5xx → `report_job.status=FAILED, error_message=<exception summary>, completed_at=NOW()`.
- Publish `medfund.notification.report-delivery-failed` event `{jobId, scheduleId, tenantId, reportKey, periodStart, periodEnd, failureStage, errorSummary, occurredAt, schemaVersion:1}`.
- notification-service `internal/report/dispatcher.go` handles both `report-delivery` and `report-delivery-failed` topics (single subscriber, dispatch by event type) — failure emails go to schedule recipients + fallback to tenant admin email (`tenant.contact_email`) if schedule has zero active recipients.
- Schedule stays `enabled=TRUE`; next fire proceeds normally.
- Angular schedule-history page exposes `POST /api/v1/reports/scheduled/{jobId}/rerun` — enqueues a fresh `report_job` with `source=SCHEDULED, schedule_id=<original>, params_json=<original>` (period preserved for re-fill). Rerun is audit-logged with the invoking human actor (not a system actor).
- Alert email subject: `[{{.TenantName}}] Scheduled report failed: {{.ReportLabel}} ({{.PeriodLabel}})`; body includes error summary + link to schedule history.

### S9 — Report toggle-off ↔ schedule: cascade-disable.

**Options offered**: (a) silent skip at fire time; (b) cascade disable; (c) fire + 403 in job; (d) reject at CRUD only.
**Chosen**: (b).
**Reasoning**: Explicit + auditable cascade; the schedule table always reflects the truth so operators reading `tenant_report_schedule.enabled` don't have to know about the master switch.
**Shape**:
- Tenancy-service `TenantReportConfigService.updateEnabled(...)` — when flipping enabled TRUE→FALSE, executes a transactional cascade: UPDATE `tenant_report_schedule SET enabled=FALSE, updated_at=NOW(), updated_by=<actor>` WHERE `tenant_id=? AND report_key=? AND enabled=TRUE`; emit one `AuditEvent` per affected schedule row (per `feedback_audit_entity_name` — friendly entity_name = `"Scheduled {reportLabel} ({cadenceLabel})"`).
- Re-enabling the report toggle (FALSE→TRUE) does **NOT** cascade back on — admin must explicitly re-enable each schedule (asymmetric on purpose; disable is the safe direction).
- Angular admin surface (Phase 0 reports-tab component) shows a confirm modal on disable when `count > 0`: "Disabling this report will pause N scheduled deliveries. Continue?"
- Scheduler probe still short-circuits on `schedule.enabled=FALSE` (belt-and-braces; a schedule flipped by cascade won't fire even if the probe queries stale data).
- No fire, no report_job insert, no failure alert on toggle-off — the cascade + schedule state IS the visibility.

### S10 — Actor for scheduled runs: schedule creator carried through.

**Options offered**: (a) schedule creator carried; (b) system actor; (c) tenant-admin fallback; (d) empty actor.
**Chosen**: (a).
**Reasoning**: Preserves Rule 8/9 accountability; the human who set up the recurring export is the one Rule 9 wants named on the DATA_ACCESS event. Uses `AuditActor` helper unchanged.
**Shape**:
- `tenant_report_schedule` columns include: `created_by_actor_id UUID NOT NULL, created_by_actor_email VARCHAR(255) NOT NULL, updated_by_actor_id UUID NOT NULL, updated_by_actor_email VARCHAR(255) NOT NULL`.
- Every fire: `report_job.requested_by = schedule.updated_by_actor_id, report_job.requested_by_email = schedule.updated_by_actor_email` (uses "most-recent editor" so if the schedule was subsequently edited by a different admin, the editor takes over responsibility).
- `AuditEvent` on schedule fire uses same actor via `AuditActor.of(updatedByActorId, updatedByActorEmail)`.
- `SecurityEventPublisher.publishDataAccess(actor=..., reportKey=..., context={source:"SCHEDULED", scheduleId:..., cadence:..., periodStart:...})`.
- Ownership transfer (future): admin surface for reassigning schedule owner → `updated_by_actor_*` update + audit. Not in v1 scope.
- Notification `report/` dispatcher: sender field on email is a fixed no-reply tenant address (`noreply@{tenant.contact_domain}` or platform fallback); the *audit* actor is the human, but the *from* header is a system mailbox — those are different concerns.

### S11 — Probe + orchestration topology: central in finance-service.

**Options offered**: (a) central finance-service; (b) distributed per service; (c) central probe + Kafka fan-out; (d) new scheduler-service.
**Chosen**: (a).
**Reasoning**: RegulatoryDueDateScanner precedent; CrossServiceCallHelper already in tree; single probe to reason about and monitor; ReportJobPublisher + MinIO client already in finance-service. Extra HTTP hop for 8 reports is acceptable overhead for the operational simplicity gain.
**Shape**:
- New `services/java/finance-service/src/main/java/com/medfund/finance/report/schedule/ScheduledReportProbe.java` — `@Scheduled(cron="0 5 * * * *")`, invokes `ScheduledReportOrchestrator.probeAndFire()`.
- New `ScheduledReportOrchestrator` service — queries `public.tenant_report_schedule` JOIN `public.tenants` (via `TenantConfigClient` — verify or introduce), filters cadence match per S4 rules, calls `dispatchFire(schedule, tenant, firedAt)`.
- `dispatchFire`: INSERT report_job → invoke shape-service adapter → upload XLSX → UPDATE report_job → publish delivery event.
- Adapter interface: `ScheduledReportShapeAdapter { Mono<byte[]> render(ScheduledFireContext); ReportKey key(); ReportPeriodShape periodShape(); }`. Adapters for finance-owned keys are local `@Component`s; adapters for other services delegate via `CrossServiceCallHelper` to new HTTP endpoints on the owner service.
- Owner-service HTTP contract: `POST /api/v1/reports/{reportKey}/scheduled-render` (path parameterised) body `{tenantId, periodStart, periodEnd, asOf, reportingCurrency, cadenceLabel, scheduleId}` → returns `application/vnd.openxmlformats-officedocument.spreadsheetml.sheet` bytes. Endpoint gated by `@RequiresPermission("scheduled_report:render")` (new permission; internal-service-only issued via M2M token) + tenant scoping resolved from tenantId in body against the service's own TenantContext bridge.
- Per-service inventory for the new render endpoints:
  - contributions-service: COLLECTION_RATE, AGED_DEBTORS, UPR_MOVEMENT, CASH_FLOW_FORECAST_13W
  - claims-service: CLAIMS_SUMMARY, PROVIDER_NETWORK_UTILIZATION
  - user-service: POLICY_MOVEMENT, PERSISTENCY_COHORT, GROUP_CENSUS
  - finance-service: COMMISSION_STATEMENT, LOSS_RATIO, REINSURANCE_CESSION_BORDEREAU, REINSURANCE_RECOVERIES

### S12 — Angular UI: dedicated `/tenant/admin/settings/report-schedules` page with grid link-through.

**Options offered**: (a) dedicated page + grid link; (b) expand-row on grid; (c) modal from grid; (d) under finance-officer per-report pages.
**Chosen**: (a).
**Reasoning**: Keeps Phase 0 grid clean; dedicated page has room for recipient CRUD; role separation preserved.
**Shape**:
- New route `/tenant/admin/settings/report-schedules` — sidebar entry under Settings (add to `settings.component.ts` tab list, position after "Reports" tab).
- New Angular components:
  - `ReportSchedulesPageComponent` — lists cadenced reports as accordion cards, grouped by family (same grouping as Phase 0). Card shows: report label, family badge, enabled toggle, cadence dropdown (WEEKLY / MONTHLY / QUARTERLY / ANNUAL), day/hour picker (contextual — day_of_week for WEEKLY, day_of_month for MONTHLY, no day picker for QUARTERLY/ANNUAL), reportingCurrency override (optional; blank = use tenant default), collapsible recipient list.
  - `ScheduleRecipientListComponent` — table with add/remove/toggle-active + per-row unsubscribe token display (read-only), audit-logged mutations via existing pattern.
  - `ScheduleRunHistoryComponent` — reused per card + also linked from Phase 0 grid; drills into last N `report_job` runs for (schedule, report_key) with status, run duration, error message (if FAILED), re-run button.
- Phase 0 reports-tab.component.ts gets a "Manage schedule" link column next to cadenced rows; clicking navigates to `/tenant/admin/settings/report-schedules#{reportKey}` (anchor scrolls to card).
- Backend APIs (tenancy-service):
  - `GET /api/v1/tenants/{tenantId}/report-schedules` — list all schedules with recipients.
  - `POST/PUT/DELETE /api/v1/tenants/{tenantId}/report-schedules/{scheduleId}` — CRUD (creates/updates row + recipients as one transaction).
  - `POST /api/v1/tenants/{tenantId}/report-schedules/{scheduleId}/recipients` — add recipient.
  - `PUT /api/v1/tenants/{tenantId}/report-schedules/{scheduleId}/recipients/{recipientId}` — update (is_active + display_name).
  - `DELETE /api/v1/tenants/{tenantId}/report-schedules/{scheduleId}/recipients/{recipientId}` — soft-delete (set is_active=FALSE + audit).
  - `GET /api/v1/tenants/{tenantId}/report-schedules/{scheduleId}/history` — list of last N report_job runs for this schedule.
- Permission gates: `tenant_settings:report_schedules:read` + `tenant_settings:report_schedules:write` + `tenant_settings:report_schedules:manage_recipients`.

### S13 — Sub-plan structure: one plan, 5 tranches (~10-12 phases).

**Options offered**: (a) single sub-plan, 5 tranches, 10-12 phases; (b) 2 sub-plans; (c) flat phase list; (d) 3 sub-plans.
**Chosen**: (a).
**Reasoning**: Right-sized for the scope; matches Phase 8/9 precedent; each tranche shippable as one commit.
**Shape**:
- Sub-plan file: `thoughts/shared/plans/2026-08-31-scheduled-email-delivery.md`.
- Tranches:
  - **§0 shared types + migrations** (~2 phases): `ReportCadence.WEEKLY` add, `ReportPeriodShape` enum, `ReportKey.periodShape` field authoring pass across 13 keys, V171+ public migrations for `tenant_report_schedule` + `tenant_report_schedule_recipient`, tenant migration none (all public tables), ALTER `report_job` adding `source` + `schedule_id` columns (in finance-service tenant migration folder).
  - **§A backend probe + orchestrator + adapters** (~3 phases): `ScheduledReportProbe` + `ScheduledReportOrchestrator` in finance-service; `ScheduledReportShapeAdapter` interface + 4 finance-local adapters (COMMISSION_STATEMENT, LOSS_RATIO, REINSURANCE_CESSION_BORDEREAU, REINSURANCE_RECOVERIES); new `/scheduled-render` endpoints on contributions-service, claims-service, user-service; Kafka publisher for `medfund.notification.report-delivery` + `medfund.notification.report-delivery-failed`; tenancy-service CRUD (schedule + recipients) + cascade-disable in `TenantReportConfigService.updateEnabled`.
  - **§B notification-service dispatcher** (~2 phases): `services/go/notification-service/internal/report/dispatcher.go` — subscribes to both topics; MIME-attach ≤10MB path; signed-link path via new gateway route `GET /api/v1/reports/scheduled/{jobId}/download?token=...`; unsubscribe route `POST /unsubscribe/{token}` (public); template files in `services/go/notification-service/internal/report/templates/` for subject + body + failure-alert body.
  - **§C Angular** (~2 phases): `/tenant/admin/settings/report-schedules` page + `ReportSchedulesPageComponent` + `ScheduleRecipientListComponent` + `ScheduleRunHistoryComponent`; Phase 0 grid "Manage schedule" link column + cascade-disable confirm modal.
  - **§D e2e + rollout** (~2 phases): Playwright specs (create schedule → force fire via test-only endpoint → verify email arrives via mailpit → verify report_job history + XLSX download); docker-compose IT (deferred per Phase 15/16 precedent, land in follow-up); rollout notes with deploy order (notification-service dispatcher BEFORE finance-service publisher per `bug_reactor_kafka_ack_swallow` + F-S9 below).

## Settled by fact (not asked)

- **F-S1 — Reporting-currency default**: Cross-service invariant #1 (`thoughts/shared/plans/2026-08-11-financial-reporting-suite.md:120`) already mandates every wrapped report endpoint uses `tenant_currency_config.is_default` unless overridden. Scheduled runs use the same source via `TenantConfigClient.getDefaultCurrency(tenantId)` at fire time. No per-schedule `reporting_currency_override` column in v1 (add later if a tenant asks). Missing-FX semantics inherit invariant #6 warning-only behaviour for the general reports in scope (regulatory keys excluded from Phase 17 per S1).

- **F-S2 — Rule 8 audit on every mutation**: `AuditEvent` on schedule CRUD, recipient CRUD, cascade-disable, `filing_ref` capture (N/A for Phase 17), owner-transfer (N/A v1). Uses `AuditActor` helper (`feedback_audit_actor_email` memory) with entity_name as friendly text per `feedback_audit_entity_name` — e.g. `"Scheduled Commission statement (Monthly, first day)"` not the UUID.

- **F-S3 — Rule 9 SecurityEvent on every scheduled export**: `SecurityEventPublisher.publishDataAccess(...)` (`services/java/shared/src/main/java/com/medfund/shared/security/SecurityEventPublisher.java:74-80`) fires from the orchestrator right before returning the XLSX bytes to MinIO upload. `context.source = "SCHEDULED"`.

- **F-S4 — Kafka ack pattern**: `.doOnSuccess` acknowledgement per `bug_reactor_kafka_ack_swallow` memory in the finance-service Reactor-Kafka publisher path. Go notification-service consumer uses the existing `events.Subscriber` callback pattern (implicit commit-on-callback-return) — precedent from regulatory dispatcher.

- **F-S5 — Public vs tenant-schema prefix**: `tenant_report_schedule` + `tenant_report_schedule_recipient` are public tables → SQL uses `public.` prefix. `report_job` is a tenant-schema table (Phase 15) → unqualified name. Enforced by `bug_public_prefix_silent_rollback` memory.

- **F-S6 — Retention**: `report_job.retention_class = OPERATIONAL_90D` classification (Phase 15 §14 / I28) applies uniformly to scheduled runs. No new retention job; existing `ReportJobRetentionJob` (`services/java/finance-service/src/main/java/com/medfund/finance/report/scheduler/ReportJobRetentionJob.java:57`) handles cleanup (>90 days, keep top 20 per (tenant, report_key)).

- **F-S7 — Migration numbering**: Last applied public migration is `V170__regulatory_due_date_notification_sent.sql` (Phase 16 REG20). Phase 17 public migrations start V171+. Numbers to reserve: `V171__tenant_report_schedule.sql`, `V172__tenant_report_schedule_recipient.sql`. Tenant-schema ALTER `report_job` add columns: verify latest applied tenant migration at implementation time per `feedback_never_edit_applied_migrations` memory (~V16x-V17x range as of Phase 15).

- **F-S8 — Kafka topic naming convention**: `medfund.<domain>.<event>` (dot-separated, dashed lowercase). New topics: `medfund.notification.report-delivery` + `medfund.notification.report-delivery-failed`. Both follow convention.

- **F-S9 — Deploy order invariant**: notification-service `internal/report/dispatcher.go` MUST start consuming both `medfund.notification.report-delivery` + `medfund.notification.report-delivery-failed` BEFORE finance-service `ScheduledReportOrchestrator` starts publishing. New consumer subscribes from `earliest` on first deploy to catch pre-cutover events. Standard multi-service ordering per Phase 15 I22 pattern.

- **F-S10 — ReportKey.cadenced authoring already done**: The 24 cadenced keys are already tagged in `services/java/shared/src/main/java/com/medfund/shared/report/ReportKey.java:33-139`. Phase 17 §0 authoring pass adds `periodShape` to those 24 + updates the 13 in S1 scope to their concrete `PREVIOUS_COMPLETE_PERIOD` / `AS_OF_FIRE_TIME` values; the 11 out-of-scope cadenced keys get their periodShape too (for future use) but no schedule row can be created for them yet (Phase 17 UI whitelist).

- **F-S11 — ReportCadence.EVENT_DRIVEN**: Existing enum value stays; unused by Phase 17 UI (dropdown offers WEEKLY/MONTHLY/QUARTERLY/ANNUAL only). Kept for regulatory REG20's due-date scanner semantics.

- **F-S12 — Angular JURISDICTIONS + country gates**: Not touched by Phase 17 (S1 scope excludes regulatory keys, so no jurisdiction gate applies to scheduled reports in v1).

- **F-S13 — MinIO bucket + path**: Reuse `medfund-report-payloads` (Phase 15 §14). Path convention: `{tenantId}/{yyyy}/{MM}/{jobId}.xlsx` (consistent with Phase 15 pattern; per-year/per-month sharding for listing efficiency).

- **F-S14 — SMTP relay + mailpit dev**: notification-service `SMTPSender` already supports PLAIN auth + mailpit no-auth dev mode (`services/go/notification-service/internal/mail/sender.go:41-71`). MIME attachment path already used by invoice dispatcher (`services/go/notification-service/internal/invoice/dispatcher.go:136-140`).

## Owed back to plan authors

Corrections + contradictions the grill surfaced that touch other sections of the parent plan:

- **Parent-plan header `phases_status` stale**: header line `"16-19": outline depth; each needs its own grilling pass before implementation` is superseded — Phase 16 landed 2026-08-30 (commit `ba9e65c` "Land Phase 16 regulatory-format reports (Phases 1-28)"); Phase 17 grilled 2026-08-31 (this scratchpad). Apply-step will update `phases_status["16"]` to landed and `["17"]` to grilled.

- **Phase 17 outline `recipients JSONB DEFAULT '[]'`** (`thoughts/shared/plans/2026-08-11-financial-reporting-suite.md:4319`): superseded by S6 sibling table `tenant_report_schedule_recipient`. Apply strike-through with pointer.

- **Phase 17 outline `V13x__tenant_report_schedule.sql`** (`thoughts/shared/plans/2026-08-11-financial-reporting-suite.md:4311`): actual number V171+ per F-S7. Apply.

- **Phase 17 outline "per-report `@Scheduled` job"** (`thoughts/shared/plans/2026-08-11-financial-reporting-suite.md:4327`): superseded by S11 single central `ScheduledReportProbe` in finance-service with adapter fan-out. Apply strike-through.

- **Cross-invariant #2 tension with S9 cascade** (`thoughts/shared/plans/2026-08-11-financial-reporting-suite.md:121`): invariant says GET endpoints 403 when tenant_report_config disabled. S9 chose cascade-disable so schedules never fire against a disabled report. No contradiction, but sub-plan §0 should include a note in the tenancy-service `TenantReportConfigService.updateEnabled` phase spelling out the two-write transaction so a reviewer doesn't wonder why the scheduler doesn't also check.

- **`ReportCadence.EVENT_DRIVEN` semantics** (`services/java/shared/src/main/java/com/medfund/shared/report/ReportCadence.java`): unused by Phase 17. Regulatory REG20 uses it implicitly for tier-driven emissions. Sub-plan §0 note: "EVENT_DRIVEN excluded from Phase 17 schedule cadence dropdown per F-S11."

- **AML_STR + IFRS_17 + regulatory cadenced keys stay out of Phase 17**: per S1 scope decision. Sub-plan intro should call out that scheduled auto-run + email for regulator/IFRS keys is a follow-up ("Phase 17.5 — Regulator draft auto-run") to prevent scope-creep during implementation.

- **`ReportKey.cadenced` javadoc line 148-152** already correctly says "Drives whether the Phase 17 schedule form exposes the switch for this report at all" — no correction needed; note that the S1 scope decision further restricts to 13 keys within the 24 marked (UI whitelist per F-S10).

- **`report_job.source` + `schedule_id` ALTER** — Phase 15 ReportJob entity + migration need to grow two new columns (S2 design). Not a contradiction but a follow-on schema change to keep in mind for anyone reading Phase 15's tables in isolation.

- **Phase 18 KPI dashboards + Phase 19 FRAUD_SIU_REPORT (cadenced=true) reference**: Phase 19's `FRAUD_SIU_REPORT` is marked `cadenced=true` in `ReportKey.java:139` but Phase 19 is not built. Phase 17 does not deliver it (per S1). Note in sub-plan §0: the Phase 17 UI whitelist explicitly excludes FRAUD_SIU_REPORT until Phase 19 lands.














## Settled by fact (not asked)

_(populated as facts crystallise)_

## Owed back to plan authors

_(populated on findings that touch other plan sections)_
