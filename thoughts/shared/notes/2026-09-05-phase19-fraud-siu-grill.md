---
date: 2026-09-05
plan: thoughts/shared/plans/2026-08-11-financial-reporting-suite.md
phase: 19
title: Fraud / SIU Report — grilling scratchpad
status: in-progress
---

# Phase 19 grilling — Fraud / SIU Report

Numbering scheme: `FR1..FRn` for grill decisions — `FR` for Fraud, chosen after the initial `F*` choice was found to collide with the plan's existing `F<n>` settled-by-fact markers used in earlier phases (F6-F11 in Phase 0, F12-F17 in Phase 1, F18-F29 in Phase 2, F52-F61 in Phase 4). Avoids collision with plan-wide `G*` numbering and prior phase decision prefixes `R*` reinsurance / `P*` producer / `U*` underwriting / `L*` lifecycle / `A*` actuarial / `I*` IFRS 17 / `REG*` regulatory / `S*` scheduled email / `K*` KPI. Settled-by-fact prefix `F19-*` follows plan convention (each phase's settled-by-fact set uses `F<phase>-<n>`).

## Settled by fact (not asked)

- **F19-1** — `FRAUD_SIU_REPORT` enum key + `ReportFamily.FRAUD` already exist (`services/java/shared/src/main/java/com/medfund/shared/report/ReportKey.java:150`, `services/java/shared/src/main/java/com/medfund/shared/report/ReportFamily.java:35`). No shared-module additions needed.
- **F19-2** — Topic `medfund.claims.fraud-flagged` does not exist anywhere; producer must be added in the AI service's Kafka consumer at `services/python/ai-service/app/core/kafka_consumer.py:68-96`.
- **F19-3** — `Claim` entity has no fraud fields (`services/java/claims-service/src/main/java/com/medfund/claims/entity/Claim.java:1-284`). Tenant-schema Flyway migration is unavoidable.
- **F19-4** — No SIU adjudication controller / Angular page exists in the codebase today.
- **F19-5** — AI fraud detection is live (`services/python/ai-service/app/services/fraud_service.py:12-41`, `services/python/ai-service/app/services/ml_models.py:11-96`) — IsolationForest, outputs (risk_score, risk_level {LOW,MEDIUM,HIGH}, indicators[], model_version) on every `CLAIM_SUBMITTED` Kafka event.
- **F19-6** — `RejectionCode` seed already includes `R16 FRAUD` (`services/java/tenancy-service/src/main/resources/db/migration/tenant/V014__claims_schema.sql:116-135`) — a categorical rejection code, not a per-claim column.
- **F19-7** — Retention taxonomy today: `OPERATIONAL_90D` + `STATUTORY_7Y` only (`services/java/finance-service/src/main/java/com/medfund/finance/report/entity/ReportJob.java:35-36`); everything defaults to 90D except IFRS 17 / regulatory.

## Decisions (FR1..FRn)

- **FR1 — Scope: full SIU case-management module.** Cases (`siu_case`), investigators (siu_officer + siu_supervisor roles), evidence attachments via file-service, case notes / activity log, related-claims linking (one case → many claims), referrals to law enforcement, four-eyes case-closure, case-audit trail. Sub-plan expected to be ~15-20 phases across 3-5 tranches. Rejected: report-only (dishonest "confirmed" number, weak Rule 3 auditability); report + minimal review workflow (real SIU teams outgrow it, incremental follow-up cost higher than up-front build).

- **FR2 — Service ownership: claims-service.** SIU is a claims-domain workflow. `Claim` entity is already here, tenant schema is here, `TenantContext` is wired, AI publishes to `medfund.claims.*` namespace. Report aggregations compose from claims-service + optional finance-service call (recovery amounts) via `CrossServiceCallHelper`. Rejected: new siu-service (breaks precedent of folding into existing services; speculative decomposition, no team asking); split claims/finance (cross-service chatter for every op, violates Rule 6, 'recovery' is only one closure outcome).

- **FR3 — Angular routing: split.** Workflow (case queue, case detail, evidence panel, referrals, activity log) lives under `/tenant/claims/siu/*` (peer of `preauth`, `pending`, `tax-withheld`). Summary analytics + XLSX export lives under `/tenant/finance/reports/fraud/*` in the Reports Hub. Follows precedent (claims-status has a workflow page in Claims + a report page in Finance/Reports). Sidebar role-filter handles siu-only users. Rejected: all-in-claims (breaks Reports Hub 'one place for every report' promise + breaks parent-plan outline); all-in-finance (mismatches hub's read-only shape); new top-level /tenant/siu/ (adds 7th portal section, breaks precedent, empty section for tenants without SIU staff).

- **FR4 — Case creation: tenant-configurable via rules-engine.** New `RuleCategory.FRAUD_TRIAGE` with fact `FraudFlagFact(riskScore, riskLevel, insuranceLine, providerId, claimAmount, ...)` and action `emitCaseCreation`. Tenants configure policy ("auto-open if risk > 0.85 AND amount > 5000", "always manual triage", etc.); default (no rules configured) = auto-open HIGH-risk. `fraud_flag` and `siu_case` stay separate entities. Rejected: auto-open every HIGH (floods queue, no tenant control); officer triage of raw queue (defeats AI value); hardcoded threshold (not per-tenant tunable, no audit trail for dropped low-risk flags).

- **FR5 — Entity model: 5 entities, many-to-many via flag-as-evidence.**
  - `fraud_flag` — one per (claim, AI-run OR manual-open); cols include `claim_id`, `siu_case_id` (nullable), `flag_source ENUM('AI_MODEL','MANUAL_OFFICER')`, `model_version`, `risk_score`, `risk_level`, `indicators JSONB`, `flagged_at`. Officer-initiated cases get a synthetic MANUAL_OFFICER flag row so the case-to-claim link is always via a flag.
  - `siu_case` — one per investigation; cols include `case_number` (VARCHAR, tenant-scoped unique), `status`, `assigned_to`, `saved_amount`, `priority`, `tags[]`, `opened_by`, `opened_at`, `closed_by`, `closed_at`, `closure_reason`, `outcome`.
  - `siu_case_note` — append-only activity log; cols `case_id`, `author_id`, `note_type ENUM('COMMENT','STATUS_CHANGE','EVIDENCE_ADDED','ASSIGNED','REFERRAL_ADDED')`, `body`, `created_at`.
  - `siu_evidence` — file-service refs; cols `case_id`, `file_service_ref`, `description`, `uploaded_by`, `uploaded_at`, `evidence_type`.
  - `siu_referral` — external referrals; cols `case_id`, `referral_to ENUM('LAW_ENFORCEMENT','REGULATOR','INTERNAL_HR')`, `referral_reference`, `referred_by`, `referred_at`, `response_received_at`, `response_notes`.

  Rejected: 4 entities with `siu_case_claim` join (breaks flag-as-evidence-audit for officer-initiated cases); 3 entities 1:1 (fraud rings force sibling cases); 2 entities JSONB (kills Rule 8 audit + concurrent-update race).

- **FR6 — State machine: 5-state, four-eyes on non-dismissal closures, reopen supported.**
  - States: `OPEN → ASSIGNED → UNDER_REVIEW → PENDING_APPROVAL → (CLOSED_CONFIRMED_FRAUD | CLOSED_REFERRED_LAW_ENFORCEMENT | CLOSED_ACTION_TAKEN)`.
  - Fourth terminal state: `CLOSED_DISMISSED_FALSE_POSITIVE` reachable directly from `UNDER_REVIEW` (no four-eyes).
  - `REOPENED` jumps any `CLOSED_*` back to `UNDER_REVIEW`; report metric `reopened count` becomes possible.
  - Four-eyes enforced via `AuditActor` + `updated_by_actor_id != checker_id` service-layer constraint (Phase 11 CommissionAdjustment precedent). Rejected: 3-state (can't distinguish referral; loses audit thread on reopen); 2-state (governance gap); four-eyes on every closure (clogs supervisor queue with dismissal noise).

- **FR7 — Permissions: fine-grained (8 perms) + 2 Keycloak roles.**
  - New perms in `services/java/shared/src/main/java/com/medfund/shared/security/Permissions.java`: `claims:siu:view`, `claims:siu:create`, `claims:siu:assign`, `claims:siu:investigate`, `claims:siu:approve`, `claims:siu:reopen`, `claims:siu:refer`, `claims:siu:admin` (rules-engine FRAUD_TRIAGE config).
  - New Keycloak roles: `siu_officer` (view+create+investigate+refer), `siu_supervisor` (officer set + approve+assign+reopen). `admin` scoped to `tenant_admin`.
  - Report page gated by existing `finance:reports:view` + `@RequiresReport(FRAUD_SIU_REPORT)` (Phase 0 precedent).
  - Rejected: mid-grained 4-perm (bundles refer+assign into write, risky); coarse 2-perm 1-role (four-eyes becomes honor system, breaks Rule 8); ultra-fine per-transition 12+ perms (permission bloat, no team asking).

- **FR8 — Savings: per-case, investigator-entered on closure, defaults to `SUM(claimed - paid)`.** On PENDING_APPROVAL, investigator enters `saved_amount` — UI prefills with `SUM(claimed_amount - paid_amount)` across the case's flagged claims. Report sums `saved_amount` across CONFIRMED cases only. Supervisor can adjust during four-eyes; every edit hits AuditEvent per Rule 8. Mixed-currency cases capture per-claim currency (see FR14). Rejected: auto-computed no-override (doesn't capture post-close clawback / 'let X through to catch a bigger fish'); auto at flag-time (undercounts, no manual-case path); two-number avoided+recovered split (double surface for marginal UX gain).

- **FR9 — Kafka publish: every AI prediction; topic `medfund.claims.fraud-flagged`.** AI-service Kafka consumer at `services/python/ai-service/app/core/kafka_consumer.py:68-96` publishes on every classified claim (LOW/MEDIUM/HIGH). Claims-service consumer writes a `fraud_flag` row per event. `FRAUD_TRIAGE` rules-engine (FR4) then decides case creation. Rule 3 satisfied by construction. Rules can be re-run over historical flags on policy change. Downside acknowledged: high row volume (100K claims/mo/tenant → 100K flag rows/mo); retention (FR13) manages it. Payload shape: `{eventType, eventId, occurredAt, tenantId, claimId, modelVersion, riskScore, riskLevel, indicators[], computedAt}`. Rejected: threshold-only (Rule 3 gap, no per-tenant tune); two-topic raw+action (marginal Rule 3 gain, doubles infra); HIGH-only (worst combined loss).

- **FR10 — AI audit fidelity: top-N `indicators` on `fraud_flag`; full feature vector deferred to Phase 19.5 ML-ops tranche.** `fraud_flag` row carries `model_version` + `risk_score` + `risk_level` + `indicators JSONB` (top-N, ~5 entries) + `flagged_at`. Satisfies Rule 3 compliance read ("why did you flag this — these indicators, this model, this score"). Full feature vector + model weights + reproducibility deferred to a Phase 19.5 ML-ops tranche (registry, MLflow-lite, model-artefact store) — no team has asked and gap is documented in sub-plan explicitly. Rejected: full feature vector inline (150MB/mo/tenant of blob without closing reproducibility gap); separate ai_audit_log table (ML-ops MVP inside this phase); Kafka-log-as-audit (not human-reviewable, retention risk).

- **FR11 — Report metrics scope: full analytics.** Tile-grid (6 tiles: cases opened / confirmed fraud / savings realised / confirmation rate / avg cycle time / reopened) + monthly trend chart + top-N drill-tables (top-10 providers, top-10 members by confirmed-fraud amount) + AI model calibration (precision/recall by risk_level, false-positive rate curve; "Insufficient data" fallback for N<50 confirmed cases) + investigator productivity (cases-closed per officer; role-gated so `siu_officer` sees own stats only, `siu_supervisor` + `tenant_admin` see all). Filter chips: period, insurance line, closure outcome. XLSX has 6 sheets (Summary / Cases detail / Provider top-N / Member top-N / AI calibration / Investigator productivity). Sub-plan adds a dedicated §Analytics tranche given ballooned surface. Rejected: minimum-viable 4 metrics (undelivers vs full-SIU scope); tile-only (misses top-N execs demand day 2); split fraud-report + SIU-workload page (duplicates infra, splits catalogue).

- **FR12 — Cadenced: added to Phase 17 whitelist with per-schedule sensitive-sheet gate.** `FRAUD_SIU_REPORT` joins the 13-key (→14 including Phase 18 additions, →19 with FRAUD_SIU_REPORT) Phase 17 scheduled-delivery whitelist. Default cadenced XLSX has 4 sheets (Summary + Cases + Provider top-N + Member top-N); investigator-productivity + AI-calibration sheets included **only** when schedule creator opts in via new per-schedule flag `includeSensitiveSheets` (defaults false). Cascade-on-disable per Phase 17 S9 applies. New `ScheduledReportShapeAdapter` in claims-service. Phase 17 sub-plan gets a Deviations note that whitelist widened to include `FRAUD_SIU_REPORT`. Note: FR16 defers this to §B (MVP §A has no scheduled dispatch). Rejected: no sensitive-sheet gate (GDPR/POPIA/labour-law risk); on-demand only (execs don't get monthly digest); tenant-admin-only creator (friction, wrong role knows the recipient list).

- **FR13 — Retention: add 2 new classes.** New `RetentionClass` values: `FRAUD_FLAG_1Y` (raw AI flags with no linked case → auto-purge after 12 months) + `SIU_CASE_7Y` (any case + its linked flags + evidence + notes + referrals + `report_job` rows for FRAUD_SIU_REPORT → 7-year retention aligns with insurance-fraud statute). Classifier: `fraud_flag WHERE siu_case_id IS NULL AND flagged_at < NOW() - INTERVAL '1 year'` → purged; anything linked to a case retained 7y past case-closure. Extend existing `ReportJobRetentionJob` (or introduce a peer `FraudFlagRetentionJob`). Per-tenant jurisdiction override ('ZW wants 10y, ZA wants 7y') deferred to Phase 19.5 via `tenants.jurisdiction_code` widening. Rejected: reuse 7Y for everything (100K/mo/tenant × 7y noise); split 90D flag / 7Y case (breaks flag-as-evidence chain-of-custody); single 10Y blanket (over-retains for shorter jurisdictions).

- **FR14 — Multi-currency: one currency per case.** `siu_case.saved_amount NUMERIC + saved_currency VARCHAR(3)`. Investigator picks one currency at close (defaults to majority currency across the case's flagged claims). Report groups `SUM(saved_amount)` by `saved_currency` for envelope `perCurrency`; composite scalar converts via `FxRateReader.convert(...)` at asOf date, fails loud if any `perCurrency` currency lacks FX rate (invariant #6 + G28). Investigator UI shows soft warning when case has multi-currency flagged claims — investigator collapses to one number + adds a case note explaining the mix. Rejected: proportional per-claim auto-breakdown (arbitrary math investigator can't defend); full join table `siu_case_savings` (6th entity, real UX cost for rare case shape); reporting-currency-only (violates invariant #6, non-reproducible).

- **FR15 — Rules-engine FRAUD_TRIAGE: 1 fact + 6 templates.**
  - **FraudFlagFact**: `riskScore, riskLevel, insuranceLine, providerId, memberId, claimAmount, currencyCode, indicators[], flaggedAt, historicalMemberFlagCount, historicalProviderHighFlagCount`.
  - **6 templates**: (1) 'Auto-open above risk threshold' — param `minScore`; (2) 'Auto-open large claim + high risk' — params `minScore + minAmount`; (3) 'Auto-open for watchlisted provider' — param `providerIds[]`; (4) 'Auto-open on member repeat-offender pattern' — params `windowDays + minCount`; (5) 'Auto-open on provider high-flag pattern' — params `windowDays + minCount`; (6) 'Never auto-open' — explicit off switch.
  - Default policy (no tenant rules) = template (1) with `minScore=0.85`.
  - Note: FR16 defers templates (4)+(5)+(6) to §B (MVP §A ships templates (1)+(2)+(3)).
  - Rejected: 3-template minimum (loses pattern-rec seeds); 8 templates (extra depends on non-existent fact fields); 2-fact ProviderRiskProfileFact (materialized-view refresh infra cost).

- **FR16 — Sub-plan structure: 2 tranches, MVP-first then expansion.** Single sub-plan at `thoughts/shared/plans/2026-09-05-fraud-siu-report.md` split into two tranches, both landed as part of Phase 19:

  **§A MVP (~5-6 phases)** — early value ship:
  - 3 entities: `fraud_flag`, `siu_case`, `siu_case_note`
  - 3-state machine: `OPEN → UNDER_REVIEW → (CLOSED_CONFIRMED | CLOSED_DISMISSED)` — four-eyes deferred to §B
  - Permissions subset: `claims:siu:view`, `claims:siu:create`, `claims:siu:investigate`, `claims:siu:admin` (4 of the 8 FR7 perms); one role `siu_officer`
  - AI service Kafka producer + `medfund.claims.fraud-flagged` event + claims-service `FraudFlaggedConsumer` (FR9 shape, top-N indicators per FR10)
  - FRAUD_TRIAGE rules-engine category + FraudFlagFact + 3 templates (threshold, threshold+amount, watchlist)
  - Angular `/tenant/claims/siu/` — queue + case-detail (no evidence panel, no referrals)
  - Angular `/tenant/finance/reports/fraud/` — 4 tiles (cases opened, confirmed, savings, confirmation rate) + XLSX with 2 sheets (Summary + Cases detail)
  - Retention: add `FRAUD_FLAG_1Y` + `SIU_CASE_7Y` enum values; classifier + purge job

  **§B Expansion (~5-6 phases)** — completes to full FR5/FR6/FR11/FR15:
  - 2 additional entities: `siu_evidence` (file-service refs), `siu_referral`
  - Full 5-state machine: adds `ASSIGNED`, `PENDING_APPROVAL`, `REOPENED`, `CLOSED_REFERRED_LAW_ENFORCEMENT`, `CLOSED_ACTION_TAKEN` + four-eyes gate on non-dismissal closures
  - 4 remaining permissions: `claims:siu:assign`, `claims:siu:approve`, `claims:siu:reopen`, `claims:siu:refer`; second role `siu_supervisor`
  - 3 additional templates: repeat-offender, provider high-flag pattern, never-auto-open
  - Angular workflow additions: evidence upload panel, referral form, activity-timeline enhancement, admin UI for FRAUD_TRIAGE rules
  - Angular report additions: trend chart, top-10 providers, top-10 members, AI calibration (precision/recall by risk_level with N<50 fallback), investigator productivity (role-gated so `siu_officer` sees own stats only)
  - XLSX widens from 2 sheets to 6 sheets (adds Provider top-N, Member top-N, AI calibration, Investigator productivity)
  - Phase 17 wiring: `ScheduledReportShapeAdapter`, whitelist add, per-schedule `includeSensitiveSheets` flag (FR12)

  **Total ~10-12 phases across both tranches.** Rejected: 5-tranche 20-24-phase full-scope-up-front (implementer session cost); 3-tranche mega-§A (harder to reviewer-unbundle); 6-tranche with dedicated Playwright (thin last tranche, typically folded).

## Owed back to plan authors

- **Parent-plan frontmatter `phases_status["19"]` stale**: line 31 currently says `"19": outline depth; needs its own grilling pass before implementation`. Update to `"19": grilled 2026-09-05 (FR1..FR16 — fraud-SIU decisions numbered F* for Fraud to avoid collision with plan-wide G* and prior phase prefixes R*/P*/U*/L*/A*/I*/REG*/S*/K*); 2-tranche MVP-first sub-plan per FR16 planned via create-plan at implement time`. Update `last_grilled_phase: 19`, `last_grilled_date: 2026-09-05`.
- **Phase 17 S1 whitelist widens (FR12 consequence)**: Phase 17 sub-plan `thoughts/shared/plans/2026-08-31-scheduled-email-delivery.md` needs a Deviations note that Phase 19 §B adds `FRAUD_SIU_REPORT` to the scheduled-delivery whitelist, plus a new per-schedule `includeSensitiveSheets` flag. Similar to Phase 18's whitelist widening from 13→18 keys, Phase 19 §B widens further to include FRAUD_SIU_REPORT. `ScheduledReportEligibilityTest.java:38-49` currently *excludes* FRAUD_SIU_REPORT with comment "bespoke draft workflows" — that exclusion + comment need updating in §B to include the key and reference the sensitive-sheet gate.
- **Phase 15 retention taxonomy extension (FR13 consequence)**: `RetentionClass` enum on `services/java/finance-service/src/main/java/com/medfund/finance/report/entity/ReportJob.java:35-36` currently has 2 values (`OPERATIONAL_90D`, `STATUTORY_7Y`). Phase 19 §A widens to 4 values (adds `FRAUD_FLAG_1Y`, `SIU_CASE_7Y`) and the classifier at `Ifrs17JobService.java:239-242` needs a new branch. Also: extend `ReportJobRetentionJob` (or add peer `FraudFlagRetentionJob`) to purge aged `fraud_flag` rows lacking a case link. Not a bug in Phase 15; a foreseeable extension.
- **Phase 4 claims-service SIU migration numbering**: last claims-service migration in the tenant folder is `V139__claim_reserve_history.sql`. Phase 19 §A migrations start at V140+ and land 3 tables (fraud_flag, siu_case, siu_case_note). Phase 19 §B lands 2 more (siu_evidence, siu_referral). Numbering to be verified at sub-plan write time — never edit an applied migration (per `feedback_never_edit_applied_migrations`).
- **Phase 0 `TenantReportConfig` — FRAUD_SIU_REPORT default enabled?** `FRAUD_SIU_REPORT` is in the `ReportKey` enum but absent from `tenant_report_config` seed. Absent-row defaults to enabled per V130 semantics. No seed migration needed; first tenant toggle load will seed it. Consistent with parent-plan FR12 (Phase 0).
- **No `.claude/*.md` architecture doc covers SIU workflow or fraud detection**: Phase 19 §A could optionally add a short section to `.claude/adjudication.md` (references SIU as an adjacent post-adjudication concern) or a new `.claude/siu.md` naming the state machine + role model + AI producer + FRAUD_TRIAGE rules category. Not strictly required but avoids re-litigating the design at code-review time.
- **AI service Kafka producer wiring**: `services/python/ai-service` today has no Kafka producer at all (only a consumer). Phase 19 §A adds the first outbound Kafka path from the AI service. May need `aiokafka` producer wiring in a new `app/core/kafka_producer.py`. Docker compose + local dev startup should verify the producer is provisioned even when the AI service isn't actively consuming (avoid startup-order coupling to the consumer).
- **Live defect surfaced during grill**: `ScheduledReportEligibilityTest.java:33-49` groups FRAUD_SIU_REPORT with "regulator + IFRS 17 + AML periodic" for whitelist exclusion. The comment says "depend on MFA-gated human filing (REG12/REG13) or bespoke draft workflows" — but FRAUD_SIU_REPORT was never MFA-gated (it's an internal SIU report, not a regulator filing). The comment should be corrected in §B to name the actual reason: "excluded until Phase 19 authors the case-management module + per-schedule sensitive-sheet gate". Not blocking — just misleading to future readers.
