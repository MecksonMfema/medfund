# Scheduled Report Delivery — Rollout Runbook

Origin: Phase 17 of `thoughts/shared/plans/2026-08-11-financial-reporting-suite.md`,
implemented via sub-plan
`thoughts/shared/plans/2026-08-31-scheduled-email-delivery.md`.

Ships auto-run + email delivery for **13 non-regulator operational cadenced
report keys**: COMMISSION_STATEMENT, LOSS_RATIO, COLLECTION_RATE,
AGED_DEBTORS, CASH_FLOW_FORECAST_13W, CLAIMS_SUMMARY, POLICY_MOVEMENT,
PERSISTENCY_COHORT, GROUP_CENSUS, PROVIDER_NETWORK_UTILIZATION,
REINSURANCE_CESSION_BORDEREAU, REINSURANCE_RECOVERIES, UPR_MOVEMENT.

Regulator + IFRS 17 + AML periodic + FRAUD_SIU auto-run is **out of scope**
(deferred to Phase 17.5).

## Deploy order (F-S9)

The notification-service consumer must be online and subscribed to both new
topics **before** finance-service starts publishing. Otherwise the first
few probe fires produce events with no consumer, and the delivery lands
only when the consumer eventually catches up from `earliest`.

1. **tenancy-service** — new schema (V176 + V177 public, V168 tenant) + schedule /
   recipient CRUD + `TenantReportConfigService.bulkUpsert` cascade-disable +
   `ReportScheduleUnsubscribeController`. No external consumer depends on
   this; no risk to existing tenants.
2. **notification-service** — new `internal/report/` package. Subscribes to
   `medfund.notification.report-delivery` +
   `medfund.notification.report-delivery-failed` from `earliest`.
   **Must be up before step 4.**
3. **Owner services** — contributions-service, claims-service, user-service.
   Each ships a `POST /api/v1/reports/{reportKey}/scheduled-render` endpoint
   gated by the internal-only `scheduled_report:render` permission.
4. **finance-service** — `ScheduledReportProbe` + `ScheduledReportOrchestrator`
   + 5 finance-local adapters + 8 cross-service adapters + Kafka publishers
   + signed-download controller. First deploy with the probe kill-switch
   **off** (see Feature flags); flip on after smoke test.
5. **gateway** — new public routes (signed download, unsubscribe) + new
   authenticated routes (schedule CRUD proxy, rerun, force-fire).
6. **Angular** — `/tenant/admin/settings/report-schedules` page + Phase 0
   grid "Manage schedule" link + cascade-disable confirm modal +
   `/public/unsubscribe/:token` page.

## Kafka topic provisioning

Two new topics:

- `medfund.notification.report-delivery`
- `medfund.notification.report-delivery-failed`

Spring `TopicBuilder` beans were dropped during implementation (Phase 4
Deviation, 2026-08-31): only reactor-kafka is on the classpath, no
`KafkaAdmin`. Provisioning path:

- **Dev / staging.** The broker's `auto.create.topics.enable` defaults to
  `true` in `confluentinc/cp-kafka:7.7.0` (verified in `docker-compose.yml`).
  Topics materialise on first publish; no operator action needed.
- **Production.** If auto-create is disabled, pre-create via IaC or:

  ```
  kafka-topics.sh --bootstrap-server kafka:9092 --create \
    --topic medfund.notification.report-delivery \
    --partitions 6 --replication-factor 3
  kafka-topics.sh --bootstrap-server kafka:9092 --create \
    --topic medfund.notification.report-delivery-failed \
    --partitions 3 --replication-factor 3
  ```

Both are partitioned by `tenantId` (the record key) so a single tenant's
events land on one partition and can be replayed in order.

## Shared secrets

`SCHEDULED_REPORT_DOWNLOAD_TOKEN_SECRET` (env var) must be **identical** in
finance-service (mints the HMAC) and notification-service (signs the
per-recipient download URL baked into the email). A mismatch invalidates
every outstanding signed link — recipients get 403 when they click.

Rotate together. Keep the previous secret alive for the token TTL window
(`scheduled.report.download.expiry-days`, default 7) if a hot rotation
is unavoidable — otherwise all in-flight emails break.

## Feature flags

| Property (finance-service) | Default | Purpose |
| --- | --- | --- |
| `scheduled.report.probe.enabled` | `true` | Kill switch for the hourly probe. Set to `false` on first deploy; flip after smoke test. |
| `scheduled.report.probe.cron` | `0 5 * * * *` | Fires HH:05 UTC hourly. Override for local testing. |
| `scheduled.report.probe.force-fire-enabled` | `false` | Enables `POST /api/v1/reports/scheduled/probe/force-fire` for Playwright + manual testing. **Must be false in prod.** |
| `scheduled.report.download.expiry-days` | `7` | Signed-link validity window. |

## Multi-instance dedup

The partial UNIQUE index `ux_report_job_schedule_dedup` on
`report_job (tenant_id, report_key, schedule_id, period_start) WHERE
schedule_id IS NOT NULL` is the single source of dedup truth. Two
finance-service replicas racing the same tick land as one `INSERT` and one
`DuplicateKeyException`; the orchestrator swallows the duplicate and
returns `Mono.empty()`. Ad-hoc rows (`schedule_id IS NULL`) are excluded
from the WHERE clause and unaffected.

No ShedLock — verified with the Phase 2 IT `ReportJobScheduleDedupIT`
against a real Postgres.

## Rollback

Every phase is additive.

- **finance-service** — set `scheduled.report.probe.enabled=false`. Probe
  stops on next tick; no orchestrator fires. Optionally revert the image.
- **notification-service** — revert the Deployment. Kafka events queue on
  the topic (harmless); on re-deploy, `earliest` consumption catches up.
  If the schema of the event envelope changed, bump the `schemaVersion`
  field on both publisher + consumer before re-deploying.
- **tenancy-service** — schema is additive; migrations leave data behind
  if rolled back. finance-service probe fails closed if the schema
  mismatches (no rows returned from JOIN).
- **gateway / Angular** — pure additive; revert to previous asset bundle.

The `report_job` `source` + `schedule_id` columns default to `'ADHOC'` /
`NULL` — existing ad-hoc export rows are unaffected on the migration path
and on rollback.

## Smoke test after first prod deploy

1. Log in as tenant admin → `/tenant/admin/settings/report-schedules`.
2. Create MONTHLY COMMISSION_STATEMENT schedule with `1st of month`,
   `08:00` in tenant TZ, one recipient (an ops mailbox).
3. `POST /api/v1/reports/scheduled/probe/force-fire?scheduleId=<uuid>`
   (dev/staging only — see Feature flags).
4. Verify:
   - MinIO bucket `medfund-report-payloads` has the XLSX at
     `<tenantId>/<yyyy>/<MM>/<jobId>.xlsx`.
   - `report_job` row has `status='completed'`, `source='SCHEDULED'`,
     `schedule_id` set, `result_json` includes `xlsxRef` + `sha256`.
   - Kafka `medfund.notification.report-delivery` shows the event
     (`kcat -C -b kafka:9092 -t medfund.notification.report-delivery`).
   - Recipient mailbox receives one email; XLSX attached; subject
     contains report label + cadence + period.
   - `security_events` (Rule 9) has one `DATA_ACCESS` row with
     `details.source='SCHEDULED'`, correct `schedule_id`, `periodStart`,
     `periodEnd`.
5. Toggle the report OFF in `/tenant/admin/settings/reports`:
   - Confirm modal shows "1 scheduled delivery" cascade warning.
   - Save; verify `tenant_report_schedule.enabled=FALSE` +
     one `AuditEvent` per schedule (`action=CASCADE_DISABLE`).
6. Click the unsubscribe link in the delivered email:
   - Land on `/public/unsubscribe/<token>`; confirm.
   - Verify `tenant_report_schedule_recipient.is_active=FALSE`.
   - Force-fire again; verify no email arrives for the unsubscribed
     recipient.

## First tenant onboarding

Pilot with **1 internal tenant + 1 schedule** (COMMISSION_STATEMENT,
MONTHLY, 1st of month, 08:00 tenant TZ, one ops recipient). Observe **3
fires** before opening to real tenants — that's a full quarter of the
monthly cadence and catches the calendar-boundary edge cases that unit
tests can't.

## Follow-ups captured during land

These are the deferred items from the sub-plan and Phase deviations. File
them into ticketing when a workflow exists — the tickets folder is
currently empty and doesn't carry a per-item pattern.

- **Phase 17.5** — regulator + IFRS 17 + AML periodic + FRAUD_SIU auto-run
  (11 non-whitelisted cadenced keys). Own grill + sub-plan; preserves
  REG12/REG13 MFA-gated human filing via a "draft" concept.
- **Ownership-transfer admin surface** — reassign
  `updated_by_actor_id / actor_email` on a schedule when the original
  owner leaves. Today the fire's audit trail carries the last admin who
  edited the row; leavers stay attributed.
- **Per-schedule reporting_currency_override UI** — column already exists
  on `tenant_report_schedule` (V176) but no UI edits it; orchestrator
  falls back to hardcoded `"USD"` today (Phase 4 Deviation, 2026-08-31).
  Wire `ReportingCurrencyResolver` to resolve the tenant default when
  the schedule's `reporting_currency` is null, per F-S1.
- **Rerun-schedule backfill mode** — "rerun all missed fires between date
  X and Y" for a schedule. v1 ships single-job rerun only.
- **Attachment size trend alerts** — instrumentation on `sizeBytes`
  distribution per (tenant, reportKey) with a Grafana alert on trend
  toward the 10MB cap and on the signed-link-fallback rate.
- **Cross-language docker-compose IT** — end-to-end wire-up spanning
  finance-service + notification-service + tenancy-service + mailpit +
  MinIO. Deferred alongside the Phase 3/4/5/7 IT trio per the
  Testcontainers boot-cost trade-off recorded in those Deviations.
- **Rename `AgedBalancesExcelService` → `AgedDebtorsExcelService`** —
  cosmetic follow-up to align with the `AGED_DEBTORS` enum key.
  Historical rename; not in Phase 17 scope.
- **Fix stale `ReportCadence.java` javadoc** — the Phase-16 REG20 rewrite
  left a "Phase 8" reference; Phase 1 already corrected but verify.
- **Public `/public/**` boot short-circuit** — `keycloak.init.ts` runs
  `login-required` unconditionally at app-boot, so
  `/public/unsubscribe/:token` needs a signed-in stub in tests. Real
  recipients don't have Keycloak accounts. Teach the initializer to
  short-circuit on `/public/**`. Captured in Phase 10 Deviation.
- **Register `refresh` icon** in `IconComponent.ICONS` — silently
  rendering blank on `backfill-review.component.html` +
  `endorsement-review-queue.component.html`. Captured in Phase 8
  Deviation.
- **Move Aml{SummaryRawData,ThresholdReader,FilingIdentity}Reader**
  fallback stubs from `@Component @ConditionalOnMissingBean` (which
  Spring evaluates against the stub's own registration and skips) to
  `@Configuration @Bean` methods where the condition behaves correctly.
  Captured in Phase 2 Deviation.

## References

- Sub-plan: `thoughts/shared/plans/2026-08-31-scheduled-email-delivery.md`
- Parent plan Phase 17:
  `thoughts/shared/plans/2026-08-11-financial-reporting-suite.md`
- Grilling scratchpad:
  `thoughts/shared/notes/2026-08-31-phase17-scheduled-email-grill.md`
