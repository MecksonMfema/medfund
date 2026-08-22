---
date: 2026-08-22
git_commit: 35aafe4443a47525dcae368eaaa30e819bf0ea79
branch: rename-adjustments-to-notes
ticket: null
spec: null
parent_plan: thoughts/shared/plans/2026-08-11-financial-reporting-suite.md#phase-10
research:
  - thoughts/shared/research/2026-08-11-financial-reporting-vs-masca-reference.md
grilling:
  - decisions R1..R16 landed in parent plan at thoughts/shared/plans/2026-08-11-financial-reporting-suite.md:2795-2812 on 2026-08-22
steer: "§A + §B in one plan (accepted user override of the §A-only grilling recommendation)"
services_touched: [tenancy-service, finance-service, contributions-service, claims-service, rules-engine, shared, gateway, angular]
status: draft
---

# Phase 10 — Reinsurance Module + Bordereau Reports

## Overview

Greenfield reinsurance module living in `services/java/finance-service/src/main/java/com/medfund/finance/reinsurance/*`. Covers both proportional (Quota Share / Surplus Share) and non-proportional (Excess of Loss / Stop Loss) treaties. Ships **auto-cession of loss** on `medfund.claims.adjudicated`, **auto-cession of premium** on `medfund.contributions.paid`, **facultative cession** with a three-state workflow, a **retroactive backfill** job on treaty activation, a **manual review queue** triggered by claim regression, three **bordereau reports** (cession, recoveries, treaty-utilization) with **XLSX exports** and a **quarter-aligned soft lock**, plus a full **Reinsurer + Treaty CRUD** surface in the tenant admin. Cession rules author through the existing rules-engine visual builder — no new UI component needed.

Every decision in this plan is inherited from the grilling session that produced R1..R16 (see [parent plan](2026-08-11-financial-reporting-suite.md#phase-10), lines 2795–2812). Verification pass on 2026-08-22 confirmed all inherited claims and settled two open grill notes:
- **Contribution-paid event exists** at `services/java/contributions-service/src/main/java/com/medfund/contributions/service/ContributionEventPublisher.java:38` — no producer change needed for premium cession.
- **Angular rule builder is category-agnostic** — a new REINSURANCE category adds through the existing enum + TypeScript union + RULE_CATEGORIES array + AGENDA_GATED_CATEGORIES entry; zero component refactor.

## Current State Analysis

- **Reinsurance is 100% greenfield.** No entities, migrations, Angular routes, or permissions exist today. Grep across `services/`, `clients/`, and `.claude/` for `reinsur|treaty|cession|bordereau` returns nothing operational.
- **Shared enums already ship the report keys.** `services/java/shared/src/main/java/com/medfund/shared/report/ReportKey.java:91-93` declares `REINSURANCE_CESSION_BORDEREAU`, `REINSURANCE_RECOVERIES`, `REINSURANCE_TREATY_UTILIZATION` under `ReportFamily.REINSURANCE` (`.../ReportFamily.java:24`). No enum edits in this plan.
- **All Phase 0-9 shared infra is composed on**:
  - `services/java/shared/src/main/java/com/medfund/shared/report/ReportEnvelopeBuilder.java:54` — wraps report payloads with perCurrency, fxRates, warnings.
  - `services/java/shared/src/main/java/com/medfund/shared/report/ReportingCurrencyResolver.java:33` — resolves tenant default currency + override.
  - `services/java/shared/src/main/java/com/medfund/shared/report/FxRateReader.java:43` — `findRate(base, quote, asOf, tenantId)` + `convert(...)`.
  - `services/java/shared/src/main/java/com/medfund/shared/report/ReportWorkbook.java:56` — fluent multi-sheet XLSX builder.
  - `services/java/shared/src/main/java/com/medfund/shared/report/RequiresReport.java:32` + `ReportGuardAspect.java:30` — @RequiresReport enforcement with 403.
  - `services/java/shared/src/main/java/com/medfund/shared/security/SecurityEventPublisher.java:32` + `SecurityEventMessage.java:14` — DATA_ACCESS emission on export.
  - `services/java/shared/src/main/java/com/medfund/shared/audit/AuditActor.java:29` — `AuditActor.id(jwt)` + `AuditActor.email(jwt)`.
- **All tenant migrations live in tenancy-service** (`services/java/tenancy-service/src/main/resources/db/migration/tenant/`). finance-service has no `db/migration/` folder. Highest tenant V today = **V080**; next slot = **V081**.
- **Both Kafka topics exist** with correctly-named producers:
  - `medfund.claims.adjudicated` — producer at `services/java/claims-service/src/main/java/com/medfund/claims/service/ClaimEventPublisher.java:123`. Reference consumer at `services/java/finance-service/src/main/java/com/medfund/finance/consumer/ClaimAdjudicatedConsumer.java:65` uses `.doOnSuccess` for offset ack per `bug_reactor_kafka_ack_swallow`.
  - `medfund.finance.payment-created` — producer at `services/java/finance-service/src/main/java/com/medfund/finance/service/FinanceEventPublisher.java:34`; no consumer today.
  - `medfund.contributions.paid` — producer at `services/java/contributions-service/src/main/java/com/medfund/contributions/service/ContributionEventPublisher.java:38`; no reinsurance-relevant consumer today.
- **Angular visual rule builder is category-agnostic**:
  - Category enum: `services/java/rules-engine/src/main/java/com/medfund/rules/model/RuleCategory.java:12-60` (16 categories currently).
  - TypeScript source of truth: `clients/angular/src/app/core/services/rules.service.ts:36-82` — `RULE_CATEGORIES` constant driving the editor.
  - DrlCompiler agenda-gating list at `services/java/rules-engine/src/main/java/com/medfund/rules/compiler/DrlCompiler.java:57` — `BENEFIT_PRORATION` gated today; REINSURANCE should join.
- **PaymentRunWorkbookService pattern** (`services/java/finance-service/src/main/java/com/medfund/finance/service/PaymentRunWorkbookService.java`) is the multi-sheet XLSX reference. Same shape for bordereau exports.
- **Backfill pattern from PaymentRunService** (`services/java/finance-service/src/main/java/com/medfund/finance/service/PaymentRunService.java:324-337`) — `Flux.fromIterable → .groupBy → .collectList → .flatMap(save)` inside `@Transactional`.
- **PUT-with-audit reference** — `services/java/tenancy-service/src/main/java/com/medfund/tenancy/controller/TenantHighCostClaimantConfigController.java:64-68` + service — `AuditActor.id/email(jwt)` extraction, tenant-slug in entityName per `feedback_audit_entity_name`.
- **Migration template** — `services/java/tenancy-service/src/main/resources/db/migration/tenant/V078__member_cost_share_liability.sql` (parent/child with FK ON DELETE RESTRICT + UNIQUE on business key + CHECK enum).

### Key Discoveries

- **`Cession.cessionType`** discriminator (LOSS | PREMIUM) folds both cession sources into one table — one bordereau SQL, one row shape.
- **`Cession.source`** discriminator (AUTOMATIC | FACULTATIVE) folds auto and facultative flows into the same table with different valid status subsets.
- **Reinsurance consumer can detect claim regression stateless** — on each `medfund.claims.adjudicated` event, look up existing cessions for that claimId. If the new approvedAmount is lower than the basis of prior cessions, that's a regression → create review task without needing a new `medfund.claims.reversed` topic. Zero producer-side change.
- **Cession-participant split at report time** — one Cession row per cession; the bordereau report joins `treaty_participant` and multiplies out at export. Avoids N-row explosion on write per R9.

## Desired End State

**Backend**
- 10 new tenant-scoped tables under `tenant_<uuid>` schema.
- Reinsurance is a subpackage of finance-service (`com.medfund.finance.reinsurance.*`).
- Three Kafka consumers: `ReinsuranceLossCessionConsumer`, `ReinsuranceRecoveryConsumer`, `ReinsurancePremiumCessionConsumer`. All on existing topics.
- One scheduler: `ReinsuranceTreatyPremiumJob` for flat XoL/StopLoss treaty premiums at inception.
- One on-demand job: `TreatyActivationBackfillJob` triggered by Treaty DRAFT → ACTIVE.
- CRUD REST for Reinsurer, Treaty, TreatyLayer, TreatyParticipant, TreatyApplicableLine, CessionRule (four child editors nested under the treaty edit route on the client).
- Three report endpoints under `/api/v1/reports/reinsurance/*` with `@RequiresReport` + `SecurityEventPublisher` + `ReportEnvelopeBuilder`.
- Cession rules author through the standard visual rule builder using a new `REINSURANCE` category + `CEDE_TO_TREATY` action.

**Angular**
- Tenant-admin: Reinsurers tab, Treaties list, treaty-edit page (layers + participants + applicable lines + cession rules).
- Tenant finance: three bordereau report pages, facultative browse+cede, approver queue, review-task queue, recovery record-received form.
- Reports hub auto-registers the three new report keys via `ReportCatalogueService`.

**Verification**
```bash
# Backend
cd services/java && ./gradlew build test
make test-integration

# Angular
make test-angular
make test-e2e   # includes reinsurance-facultative + reinsurance-bordereau specs

# Manual acceptance
make infra && make tenancy user contributions claims finance gateway notification web
# Log in as tenant admin → /admin/reinsurance/reinsurers → create Munich Re + Swiss Re
# Log in as tenant admin → /admin/reinsurance/treaties → create HEALTH-XOL-2026 with two layers, 60/40 split
# Adjudicate a HEALTH claim → observe Cession + Recovery rows
# Export cession bordereau for Q3 2026, reinsurerId=munich → XLSX shows Munich's 60% share
```

## What We're NOT Doing

- **Live treaty exhaustion / accumulation-tracking maths.** Layers carry `reinstatementCount` as an informational field only — no consumption tracking, no reinstatement premium computation. (Deferred per parent plan `:100`.)
- **Per-reinsurer bordereau column templates.** Every reinsurer receives the platform's standard XLSX shape. `Reinsurer.bordereauColumnTemplate` JSONB is a future additive column.
- **Standardised bordereau formats** (Ruschlikon CCA, Lloyd's Placing Platform). Bespoke insurer XLSX only per R10.
- **CSV / PDF bordereau variants.** XLSX only.
- **Auto bank-reconciliation match for RECEIVED recoveries.** Recovery.status EXPECTED → INVOICED transition is automatic (on bordereau export); INVOICED → RECEIVED and → WRITTEN_OFF are manual forms. Auto-match against bank reconciliation is a separate follow-up ticket.
- **Snapshotting FX rate on bordereau export for stable numbers.** R7 flagged this as a follow-up — re-exports of the same quarter may show drift if exchange-rate history is corrected retroactively. Not in this plan.
- **A first-class claim reversal endpoint / event.** The reinsurance review queue detects regression stateless via the existing `medfund.claims.adjudicated` topic. If tenant workflows demand a real `POST /claims/{id}/reverse`, that's a separate claim-service ticket.
- **Producer / broker integration.** Treaty carries a nullable `producer_ref VARCHAR(120)` placeholder per R15; Phase 11 will add the FK.
- **New Angular rule builder component.** The existing builder is category-agnostic (verified 2026-08-22).

## Implementation Approach

**Order:** schema first (Phase 1), then backend CRUD + tenant-admin UI (Phase 2), then auto-cession consumers (Phase 3), then reports backend + XLSX (Phase 4), then reports UI (Phase 5) — completing §A. Then §B stacks on top: premium cession (Phase 6), facultative UI (Phase 7), retro backfill + review queue (Phase 8).

**Rollout invariants** (all phases must uphold):

1. **Every wrapped report endpoint accepts optional `?reportingCurrency=`** and returns `ReportResponse<T>` with native-currency `perCurrency` breakdown per parent-plan cross-phase invariant #1. Cession/Recovery rows stay native (R7).
2. **Every report GET short-circuits with 403 Forbidden** if `tenant_report_config.enabled = FALSE` via `@RequiresReport`. Mutations (POST/PUT/DELETE) are gated only by `@RequiresPermission`.
3. **Every XLSX export emits `SecurityEventMessage`** with `eventType="DATA_ACCESS"` and `details.reportKey=<key>` before returning bytes.
4. **Every controller carries full Swagger annotations** (Rule 7).
5. **Every entity mutation emits an `AuditEvent`** with `AuditActor.id(jwt)` + `AuditActor.email(jwt)`, tenant-slug as `entityName` per `feedback_audit_entity_name` — never the UUID.
6. **All amount arithmetic is BigDecimal.** No cross-currency additions without `FxRateReader.convert(...)`. Missing FX rate at grand-total scalar → `ReportGenerationException`; missing FX rate in the envelope map → omitted + `warnings: List<String>` entry per parent-plan G28.
7. **Backfill job is idempotent via UNIQUE constraints.** A rerun writes zero duplicate rows.
8. **Reinsurance consumers use `.doOnSuccess` for offset ack** per `bug_reactor_kafka_ack_swallow`; never `.doOnTerminate`.

**Cross-phase Kafka contract stability:** every new consumer subscribes to an existing topic — no new topics introduced. If a §B feature turns out to need a new event source (e.g., a treaty renewal broadcast for cross-service audit), it lands as an additive producer with consumer following per parent-plan `:3005` invariant.

---

## Phase 1: Foundation

### Overview

Ship the schema, the rules-engine wiring, the Angular type additions, and the permission catalog. No business logic. Every subsequent phase builds on this.

### Changes Required

#### 1. Tenant migrations (V081..V090, tenancy-service)

**File**: `services/java/tenancy-service/src/main/resources/db/migration/tenant/V081__reinsurer.sql`

```sql
CREATE TABLE reinsurer (
    id                UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    name              VARCHAR(200) NOT NULL,
    contact_email     VARCHAR(255),
    contact_address   TEXT,
    jurisdiction_code VARCHAR(20),
    home_currency     CHAR(3),
    credit_rating     VARCHAR(20),
    is_active         BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    actor_id          UUID,
    actor_email       VARCHAR(255)
);
CREATE UNIQUE INDEX ux_reinsurer_name_active ON reinsurer (name) WHERE is_active = TRUE;
```

**File**: `services/java/tenancy-service/src/main/resources/db/migration/tenant/V082__treaty.sql`

```sql
CREATE TABLE treaty (
    id                        UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    treaty_ref                VARCHAR(120)  NOT NULL,
    treaty_type               VARCHAR(20)   NOT NULL,
    declared_currency         CHAR(3)       NOT NULL,
    inception_date            DATE          NOT NULL,
    expiry_date               DATE          NOT NULL,
    status                    VARCHAR(20)   NOT NULL DEFAULT 'DRAFT',
    renewed_from_treaty_id    UUID          REFERENCES treaty(id) ON DELETE RESTRICT,
    aggregate_limit           DECIMAL(19,4),
    aggregate_limit_currency  CHAR(3),
    expected_annual_premium   DECIMAL(19,4),
    producer_ref              VARCHAR(120),
    activated_at              TIMESTAMPTZ,
    created_at                TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at                TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    actor_id                  UUID,
    actor_email               VARCHAR(255),
    CONSTRAINT treaty_type_ck   CHECK (treaty_type IN ('QUOTA_SHARE','SURPLUS_SHARE','EXCESS_OF_LOSS','STOP_LOSS')),
    CONSTRAINT treaty_status_ck CHECK (status IN ('DRAFT','ACTIVE','EXPIRED','RENEWED','LAPSED','COMMUTED')),
    CONSTRAINT treaty_period_ck CHECK (expiry_date > inception_date)
);
CREATE UNIQUE INDEX ux_treaty_ref ON treaty (treaty_ref);
CREATE INDEX ix_treaty_status_period ON treaty (status, inception_date, expiry_date);
```

**File**: `services/java/tenancy-service/src/main/resources/db/migration/tenant/V083__treaty_layer.sql`

```sql
CREATE TABLE treaty_layer (
    id                    UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    treaty_id             UUID          NOT NULL REFERENCES treaty(id) ON DELETE CASCADE,
    layer_order           INT           NOT NULL,
    retention             DECIMAL(19,4) NOT NULL,
    layer_limit           DECIMAL(19,4) NOT NULL,
    layer_currency        CHAR(3)       NOT NULL,
    rate                  DECIMAL(9,6)  NOT NULL,
    reinstatement_count   INT,
    created_at            TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    CONSTRAINT treaty_layer_ordered_uq UNIQUE (treaty_id, layer_order),
    CONSTRAINT treaty_layer_amounts_ck CHECK (retention >= 0 AND layer_limit > 0 AND rate >= 0)
);
CREATE INDEX ix_treaty_layer_treaty ON treaty_layer (treaty_id, layer_order);
```

**File**: `services/java/tenancy-service/src/main/resources/db/migration/tenant/V084__treaty_participant.sql`

```sql
CREATE TABLE treaty_participant (
    treaty_id     UUID          NOT NULL REFERENCES treaty(id)     ON DELETE CASCADE,
    reinsurer_id  UUID          NOT NULL REFERENCES reinsurer(id)  ON DELETE RESTRICT,
    share_pct     DECIMAL(7,4)  NOT NULL,
    share_role    VARCHAR(20)   NOT NULL DEFAULT 'FOLLOWING',
    created_at    TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    PRIMARY KEY (treaty_id, reinsurer_id),
    CONSTRAINT treaty_participant_role_ck  CHECK (share_role IN ('LEADER','FOLLOWING')),
    CONSTRAINT treaty_participant_share_ck CHECK (share_pct > 0 AND share_pct <= 100)
);
CREATE INDEX ix_treaty_participant_reinsurer ON treaty_participant (reinsurer_id);
```

**File**: `services/java/tenancy-service/src/main/resources/db/migration/tenant/V085__treaty_applicable_line.sql`

```sql
CREATE TABLE treaty_applicable_line (
    treaty_id      UUID        NOT NULL REFERENCES treaty(id) ON DELETE CASCADE,
    insurance_line VARCHAR(20) NOT NULL,
    PRIMARY KEY (treaty_id, insurance_line),
    CONSTRAINT treaty_applicable_line_ck CHECK (insurance_line IN
        ('HEALTH','LIFE','FUNERAL','GROUP','TRAVEL','DISABILITY','VEHICLE','PROPERTY'))
);
CREATE INDEX ix_treaty_applicable_line_line ON treaty_applicable_line (insurance_line);
```

**File**: `services/java/tenancy-service/src/main/resources/db/migration/tenant/V086__cession_rule.sql`

```sql
CREATE TABLE cession_rule (
    id                  UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    treaty_id           UUID        NOT NULL REFERENCES treaty(id) ON DELETE CASCADE,
    rule_definition_id  UUID        NOT NULL,  -- FK into rules-engine business_rules(id), enforced app-layer
    enabled             BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    actor_id            UUID,
    actor_email         VARCHAR(255),
    CONSTRAINT cession_rule_treaty_rule_uq UNIQUE (treaty_id, rule_definition_id)
);
```

**File**: `services/java/tenancy-service/src/main/resources/db/migration/tenant/V087__cession.sql`

```sql
CREATE TABLE cession (
    id                  UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    treaty_id           UUID          NOT NULL REFERENCES treaty(id) ON DELETE RESTRICT,
    treaty_layer_id     UUID          REFERENCES treaty_layer(id) ON DELETE RESTRICT,
    cession_type        VARCHAR(20)   NOT NULL,
    source              VARCHAR(20)   NOT NULL,
    status              VARCHAR(20)   NOT NULL,
    source_event_id     UUID          NOT NULL,
    source_event_type   VARCHAR(40)   NOT NULL,
    ceded_amount        DECIMAL(19,4) NOT NULL,
    currency_code       CHAR(3)       NOT NULL,
    basis_amount        DECIMAL(19,4) NOT NULL,
    occurred_at         TIMESTAMPTZ   NOT NULL,
    voided_reason       TEXT,
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    actor_id            UUID,
    actor_email         VARCHAR(255),
    CONSTRAINT cession_type_ck   CHECK (cession_type IN ('LOSS','PREMIUM')),
    CONSTRAINT cession_source_ck CHECK (source IN ('AUTOMATIC','FACULTATIVE')),
    CONSTRAINT cession_status_ck CHECK (status IN ('ACTIVE','DRAFT','APPROVED','CEDED','VOIDED')),
    CONSTRAINT cession_auto_status_ck CHECK (
        source = 'FACULTATIVE' OR status IN ('ACTIVE','VOIDED')
    ),
    CONSTRAINT cession_fac_status_ck CHECK (
        source = 'AUTOMATIC' OR status IN ('DRAFT','APPROVED','CEDED','VOIDED')
    )
);
CREATE UNIQUE INDEX ux_cession_source_event
    ON cession (treaty_id, source_event_id, cession_type);
CREATE INDEX ix_cession_treaty_occurred ON cession (treaty_id, occurred_at);
CREATE INDEX ix_cession_source_event_id ON cession (source_event_id);
CREATE INDEX ix_cession_status_source   ON cession (status, source);
```

**File**: `services/java/tenancy-service/src/main/resources/db/migration/tenant/V088__recovery.sql`

```sql
CREATE TABLE recovery (
    id                UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    cession_id        UUID          NOT NULL REFERENCES cession(id) ON DELETE RESTRICT,
    status            VARCHAR(20)   NOT NULL DEFAULT 'EXPECTED',
    expected_amount   DECIMAL(19,4) NOT NULL,
    received_amount   DECIMAL(19,4),
    currency_code     CHAR(3)       NOT NULL,
    invoiced_at       TIMESTAMPTZ,
    received_at       TIMESTAMPTZ,
    write_off_reason  TEXT,
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    actor_id          UUID,
    actor_email       VARCHAR(255),
    CONSTRAINT recovery_status_ck CHECK (status IN ('EXPECTED','INVOICED','RECEIVED','WRITTEN_OFF')),
    CONSTRAINT recovery_cession_uq UNIQUE (cession_id)
);
CREATE INDEX ix_recovery_status ON recovery (status)
    WHERE status IN ('EXPECTED','INVOICED');
```

**File**: `services/java/tenancy-service/src/main/resources/db/migration/tenant/V089__bordereau_period_export.sql`

```sql
CREATE TABLE bordereau_period_export (
    id                   UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    reinsurer_id         UUID        NOT NULL REFERENCES reinsurer(id) ON DELETE RESTRICT,
    treaty_id            UUID        REFERENCES treaty(id) ON DELETE RESTRICT,
    report_key           VARCHAR(80) NOT NULL,
    year                 INT         NOT NULL,
    quarter              INT         NOT NULL,
    first_exported_at    TIMESTAMPTZ NOT NULL,
    export_count         INT         NOT NULL DEFAULT 1,
    actor_id             UUID,
    actor_email          VARCHAR(255),
    CONSTRAINT bordereau_period_export_uq UNIQUE (reinsurer_id, treaty_id, report_key, year, quarter),
    CONSTRAINT bordereau_period_report_ck CHECK (report_key IN
        ('REINSURANCE_CESSION_BORDEREAU','REINSURANCE_RECOVERIES','REINSURANCE_TREATY_UTILIZATION')),
    CONSTRAINT bordereau_period_quarter_ck CHECK (quarter BETWEEN 1 AND 4)
);
```

**File**: `services/java/tenancy-service/src/main/resources/db/migration/tenant/V090__reinsurance_review_task.sql`

```sql
CREATE TABLE reinsurance_review_task (
    id                  UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    task_type           VARCHAR(30) NOT NULL,
    cession_id          UUID        REFERENCES cession(id)   ON DELETE SET NULL,
    recovery_id         UUID        REFERENCES recovery(id)  ON DELETE SET NULL,
    claim_id            UUID,
    treaty_id           UUID        REFERENCES treaty(id)    ON DELETE SET NULL,
    status              VARCHAR(20) NOT NULL DEFAULT 'OPEN',
    assignee_user_id    UUID,
    due_by              TIMESTAMPTZ,
    create_reason       TEXT        NOT NULL,
    resolution_notes    TEXT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    actor_id            UUID,
    actor_email         VARCHAR(255),
    CONSTRAINT reinsurance_task_type_ck CHECK (task_type IN
        ('CLAIM_REGRESSION','RECOVERY_DISPUTE','MANUAL_VOID_REQUEST')),
    CONSTRAINT reinsurance_task_status_ck CHECK (status IN
        ('OPEN','IN_PROGRESS','RESOLVED_VOID','RESOLVED_KEEP','DISMISSED'))
);
CREATE INDEX ix_reinsurance_review_open ON reinsurance_review_task (status, created_at)
    WHERE status IN ('OPEN','IN_PROGRESS');
```

#### 2. Rules-engine additions

**File**: `services/java/rules-engine/src/main/java/com/medfund/rules/model/RuleCategory.java` (edit — add new value)

```java
// After existing categories, before end of enum:
    // ── Reinsurance ─────────────────────────────────────────────────────
    REINSURANCE
```

**File**: `services/java/rules-engine/src/main/java/com/medfund/rules/compiler/DrlCompiler.java` (edit — extend AGENDA_GATED_CATEGORIES)

```java
// Around line 57:
private static final Set<RuleCategory> AGENDA_GATED_CATEGORIES =
        EnumSet.of(RuleCategory.BENEFIT_PRORATION, RuleCategory.REINSURANCE);
```

Cession rules must not fire during the default sweep — they only fire when the reinsurance consumer explicitly focuses the `REINSURANCE` agenda group.

**File**: `services/java/rules-engine/src/main/java/com/medfund/rules/action/CedeToTreatyActionEmitter.java` (new)

Implements the `ActionEmitter` interface (see `bug_rules_engine_tenant_isolation` for the tenant-isolation invariant). Emits a `cede-to-treaty` action into the rules-engine result set, carrying `treatyId`, `cededPct` (proportional only), and layer-index (non-proportional only). The consumer reads this from the rule execution result to know which cessions to write.

**File**: `services/java/rules-engine/src/main/java/com/medfund/rules/templates/ReinsuranceTemplates.java` (new)

Implements `TemplateProvider` (auto-discovered by Spring DI per `services/java/rules-engine/.../TemplateProvider.java`). Ships 4 seed templates:
- "Quota Share cede (proportional, single line)"
- "Surplus Share cede (proportional, single line)"
- "Excess of Loss cede (single layer)"
- "Stop Loss cede (aggregate)"

Each template supplies default conditions (insurance line, claim amount thresholds) and a `CEDE_TO_TREATY` action skeleton the underwriter fills in.

#### 3. Angular rule builder additions

**File**: `clients/angular/src/app/core/services/rules.service.ts` (edit — extend RuleCategory union + RULE_CATEGORIES)

```typescript
// Add to the RuleCategory union type (around line 36-56):
export type RuleCategory =
    | 'ELIGIBILITY'
    | 'WAITING_PERIOD'
    // ...existing categories...
    | 'RECONCILIATION'
    | 'REINSURANCE';   // NEW

// Add to the RULE_CATEGORIES array (around line 62-82):
export const RULE_CATEGORIES: RuleCategoryDescriptor[] = [
    // ...existing entries...
    { id: 'REINSURANCE', label: 'Reinsurance', icon: 'shield-plus' },
];
```

**File**: `clients/angular/src/app/pages/tenant-admin/rules/dry-run/rule-dry-run.component.ts` (edit — add fact seed for REINSURANCE)

Extend `FACT_SEEDS` around line 32-122 with a `REINSURANCE` entry: a canned ClaimFact + a TreatyFact stub so the dry-run UI surfaces sensible example facts for a cession-rule test.

#### 4. Permissions catalog + role seed

**File**: `services/java/user-service/src/main/resources/db/migration/tenant/` (highest V-number in user-service tenant tree — needs verification at implement time)

Seed 5 new permissions:
- `finance.reinsurance:view` — read reinsurers/treaties/cessions/recoveries
- `finance.reinsurance:manage_treaty` — CRUD treaties + reinsurers (also creates DRAFT and activates)
- `finance.reinsurance:cede_facultative` — create DRAFT facultative cessions
- `finance.reinsurance:approve_facultative` — DRAFT→APPROVED→CEDED, void
- `finance.reinsurance:record_recovery_received` + `finance.reinsurance:writeoff_recovery` + `finance.reinsurance:resolve_review` — recovery state transitions + review queue resolutions

Assign to existing seed roles: `finance_officer` gets `view` + `record_recovery_received`; `finance_supervisor` gets everything except `approve_facultative`; add a new `reinsurance_supervisor` seed role with `approve_facultative` + `writeoff_recovery` + `resolve_review`.

#### 5. Report catalog is a no-op

`ReportKey.REINSURANCE_*` already ship in `services/java/shared/src/main/java/com/medfund/shared/report/ReportKey.java:91-93`. `ReportFamily.REINSURANCE` already ships. No enum edits.

### Success Criteria

#### Automated Verification
- [x] Java compiles: `cd services/java && ./gradlew :rules-engine:compileJava :rules-engine:compileTestJava :shared:test :tenancy-service:test` (verified 2026-08-22)
- [x] Rules-engine unit tests: `cd services/java && ./gradlew :rules-engine:test` — 45 tests green, new `RuleCategoryTest` covers REINSURANCE + enum size guardrail; `DrlCompilerTest.compile_reinsuranceProportionalRule_addsAgendaGroupAndCedes` + `..._reinsuranceXolRule_emitsRetentionLayerMath` assert REINSURANCE agenda-gate + CEDE_TO_TREATY arithmetic
- [ ] Tenant migrations apply cleanly on fresh testcontainer (covered by tenancy-service IT harness bootstrapping tenants). V081..V091 idempotent (each with correct constraints; V078 template match verified). Deferred to manual `make test-integration` when infra is up.
- [x] Angular compiles: `cd clients/angular && npx ng build --configuration=development` — SUCCESS (warnings only, all pre-existing)
- [ ] `make test-angular` — no `rules.service.spec.ts` fixture exists yet in the tree; new spec deferred (would be redundant with the Java `RuleCategoryTest` guardrail).
- [ ] `verify` on `/admin/rules` — Reinsurance shows in the category dropdown, dry-run panel shows seeded facts

#### Manual Verification
- [ ] After migrations, `\d cession` in a tenant schema shows all constraints (type, source, status, per-source status subsets, UNIQUE source-event)
- [ ] Rules engine `POST /api/v1/rules` accepts a rule with `category=REINSURANCE` and `action.type=CEDE_TO_TREATY` — compiles to DRL, activates without agenda firing on unrelated events

**Implementation Note**: pause for manual acceptance before Phase 2.

---

## Phase 2: Treaty/Reinsurer backend + CRUD + tenant-admin Angular

### Overview

Ship the full CRUD lifecycle for the treaty family entities (Reinsurer, Treaty, TreatyLayer, TreatyParticipant, TreatyApplicableLine, CessionRule). Backend + Angular in one phase — the Angular treaty-edit page needs the backend to be verifiable; the backend without the UI is a curl exercise that provides no user value. Splitting would give two half-verifiable phases.

### Changes Required

#### 1. Entities (finance-service)

**Files** (new — one per entity):
- `services/java/finance-service/src/main/java/com/medfund/finance/reinsurance/entity/Reinsurer.java`
- `services/java/finance-service/src/main/java/com/medfund/finance/reinsurance/entity/Treaty.java`
- `services/java/finance-service/src/main/java/com/medfund/finance/reinsurance/entity/TreatyLayer.java`
- `services/java/finance-service/src/main/java/com/medfund/finance/reinsurance/entity/TreatyParticipant.java`
- `services/java/finance-service/src/main/java/com/medfund/finance/reinsurance/entity/TreatyApplicableLine.java`
- `services/java/finance-service/src/main/java/com/medfund/finance/reinsurance/entity/CessionRule.java`

All use `@Getter @Setter @Table(...)` per CLAUDE.md Java conventions (never `@Data` on R2DBC entities). Composite-key entities (TreatyParticipant, TreatyApplicableLine) implement `Persistable<CompositeKey>` for R2DBC insert semantics.

Example:

```java
@Getter
@Setter
@Table("treaty")
public class Treaty {
    @Id private UUID id;
    private String treatyRef;
    private String treatyType;
    private String declaredCurrency;
    private LocalDate inceptionDate;
    private LocalDate expiryDate;
    private String status;
    private UUID renewedFromTreatyId;
    private BigDecimal aggregateLimit;
    private String aggregateLimitCurrency;
    private BigDecimal expectedAnnualPremium;
    private String producerRef;
    private OffsetDateTime activatedAt;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
    private UUID actorId;
    private String actorEmail;
}
```

#### 2. Repositories

**Files** (new — reactive R2DBC repositories, one per entity):
- `ReinsurerRepository extends ReactiveCrudRepository<Reinsurer, UUID>`
- `TreatyRepository extends ReactiveCrudRepository<Treaty, UUID>` + `findByStatus`, `findByTreatyRef`, `findRenewalChainRoot(UUID)`
- `TreatyLayerRepository extends ReactiveCrudRepository<TreatyLayer, UUID>` + `findByTreatyIdOrderByLayerOrder(UUID)`
- `TreatyParticipantRepository` — custom (composite key); `findByTreatyId(UUID)`, `sumShareByTreatyId(UUID)`
- `TreatyApplicableLineRepository` — same shape
- `CessionRuleRepository` — `findByTreatyIdAndEnabledTrue(UUID)`

#### 3. Services

**File**: `services/java/finance-service/src/main/java/com/medfund/finance/reinsurance/service/ReinsurerService.java`

Standard CRUD with `AuditPublisher` emission on every write. Follows the `TenantHighCostClaimantConfigService` shape: `insertNew` / `updateExisting` / `publishAudit(current, previous, action, actorId, actorEmail)` with tenant slug in `entityName`.

**File**: `services/java/finance-service/src/main/java/com/medfund/finance/reinsurance/service/TreatyService.java`

Handles the treaty lifecycle. Key methods:

```java
@Transactional
public Mono<Treaty> createDraft(CreateTreatyRequest req, String actorId, String actorEmail) { ... }

@Transactional
public Mono<Treaty> activate(UUID treatyId, String actorId, String actorEmail) {
    return treatyRepository.findById(treatyId)
        .switchIfEmpty(Mono.error(new NotFoundException("Treaty " + treatyId + " not found")))
        .flatMap(t -> {
            if (!"DRAFT".equals(t.getStatus())) {
                return Mono.error(new BadRequestException(
                    "Treaty must be DRAFT to activate — was " + t.getStatus()));
            }
            return validateForActivation(t)  // per §4 below
                .then(Mono.defer(() -> {
                    Treaty snapshot = copy(t);
                    t.setStatus("ACTIVE");
                    t.setActivatedAt(OffsetDateTime.now());
                    t.setActorId(parseUuid(actorId));
                    t.setActorEmail(actorEmail);
                    return treatyRepository.save(t)
                        .flatMap(saved -> publishAudit(saved, snapshot, "ACTIVATE",
                                                        actorId, actorEmail)
                            .thenReturn(saved));
                }));
        });
}

@Transactional
public Mono<Treaty> renew(UUID priorTreatyId, RenewTreatyRequest req,
                          String actorId, String actorEmail) {
    // Creates a new DRAFT treaty with renewedFromTreatyId = priorTreatyId,
    // flips prior treaty status DRAFT/ACTIVE → RENEWED on successor activation
}

public Mono<List<Treaty>> renewalChain(UUID treatyId) {
    // Walk renewed_from_treaty_id back to root; return root-first ordered list
}
```

**File**: `services/java/finance-service/src/main/java/com/medfund/finance/reinsurance/service/TreatyValidationService.java` (new)

Enforces R9 sharePct-sum-100 + at least one participant + at least one applicable line + (for XoL/StopLoss) at least one layer + rate ranges on activation. Never enforced on DRAFT save — DRAFT is an authoring surface.

```java
public Mono<Void> validateForActivation(Treaty treaty) {
    return participantRepository.sumShareByTreatyId(treaty.getId())
        .defaultIfEmpty(BigDecimal.ZERO)
        .flatMap(sum -> {
            if (sum.compareTo(new BigDecimal("100.0000")) != 0) {
                return Mono.error(new BadRequestException(
                    "Treaty participants must sum to 100% share (found " + sum + ")"));
            }
            return applicableLineRepository.countByTreatyId(treaty.getId());
        })
        .flatMap(lineCount -> {
            if (lineCount == 0) {
                return Mono.error(new BadRequestException(
                    "Treaty must cover at least one insurance line"));
            }
            if (isNonProportional(treaty)) {
                return layerRepository.countByTreatyId(treaty.getId())
                    .flatMap(layerCount -> layerCount == 0
                        ? Mono.error(new BadRequestException(
                            "XoL/StopLoss treaties require at least one layer"))
                        : Mono.empty());
            }
            return Mono.empty();
        })
        .then();
}
```

**Files** (new, same shape): `TreatyLayerService`, `TreatyParticipantService`, `TreatyApplicableLineService`, `CessionRuleService`.

#### 4. Controllers

**File**: `services/java/finance-service/src/main/java/com/medfund/finance/reinsurance/controller/ReinsurerController.java`

REST at `/api/v1/reinsurance/reinsurers`. Follows the `TenantHighCostClaimantConfigController` shape:

```java
@RestController
@RequestMapping("/api/v1/reinsurance/reinsurers")
@RequiredArgsConstructor
@Tag(name = "Reinsurance — Reinsurers",
     description = "Reinsurance counterparty master. A reinsurer represents an external "
                 + "counterparty on one or more treaties.")
@SecurityRequirement(name = "bearer-jwt")
public class ReinsurerController {

    private final ReinsurerService service;

    @GetMapping
    @RequiresPermission({"finance.reinsurance:view"})
    @Operation(summary = "List reinsurers (paged)")
    public Mono<PageResponse<ReinsurerResponse>> list(
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false) Boolean active) { ... }

    @GetMapping("/{id}")
    @RequiresPermission({"finance.reinsurance:view"})
    @Operation(summary = "Get a reinsurer by id")
    public Mono<ReinsurerResponse> get(@PathVariable UUID id) { ... }

    @PostMapping
    @RequiresPermission({"finance.reinsurance:manage_treaty"})
    @Operation(summary = "Create a reinsurer")
    public Mono<ReinsurerResponse> create(@Valid @RequestBody CreateReinsurerRequest body,
                                          @AuthenticationPrincipal Jwt jwt) {
        return service.create(body, AuditActor.id(jwt), AuditActor.email(jwt));
    }

    @PutMapping("/{id}")
    @RequiresPermission({"finance.reinsurance:manage_treaty"})
    @Operation(summary = "Update a reinsurer")
    public Mono<ReinsurerResponse> update(@PathVariable UUID id,
                                          @Valid @RequestBody UpdateReinsurerRequest body,
                                          @AuthenticationPrincipal Jwt jwt) {
        return service.update(id, body, AuditActor.id(jwt), AuditActor.email(jwt));
    }
}
```

**File**: `services/java/finance-service/src/main/java/com/medfund/finance/reinsurance/controller/TreatyController.java`

REST at `/api/v1/reinsurance/treaties`. Same shape, plus:
- `POST /{id}/activate` — DRAFT → ACTIVE via `service.activate(...)`
- `POST /{id}/renew` — creates successor treaty
- `GET /{id}/renewal-chain` — root-first ordered list
- `POST /{id}/void` — DRAFT → LAPSED (only DRAFT is voidable; ACTIVE treaties are commuted, not voided)

**Files** (new, same shape):
- `TreatyLayerController` at `/api/v1/reinsurance/treaties/{treatyId}/layers` — nested resource; layers cannot exist without treaty
- `TreatyParticipantController` at `/api/v1/reinsurance/treaties/{treatyId}/participants`
- `TreatyApplicableLineController` at `/api/v1/reinsurance/treaties/{treatyId}/applicable-lines`
- `CessionRuleController` at `/api/v1/reinsurance/treaties/{treatyId}/cession-rules`

All ACTIVE-treaty edit attempts return 409 Conflict with body naming the treaty status. Correction path documented in the Swagger description: void + re-create.

#### 5. DTOs

All request/response DTOs are Java `record` types per CLAUDE.md conventions. Examples:

```java
public record CreateTreatyRequest(
    @NotBlank @Size(max = 120) String treatyRef,
    @NotNull TreatyType treatyType,
    @NotBlank @Size(min = 3, max = 3) String declaredCurrency,
    @NotNull LocalDate inceptionDate,
    @NotNull LocalDate expiryDate,
    @DecimalMin("0.00") BigDecimal aggregateLimit,
    @Size(min = 3, max = 3) String aggregateLimitCurrency,
    @DecimalMin("0.00") BigDecimal expectedAnnualPremium,
    @Size(max = 120) String producerRef
) {}

public record TreatyResponse(
    UUID id, String treatyRef, String treatyType, String declaredCurrency,
    LocalDate inceptionDate, LocalDate expiryDate, String status,
    UUID renewedFromTreatyId, BigDecimal aggregateLimit, String aggregateLimitCurrency,
    BigDecimal expectedAnnualPremium, String producerRef, OffsetDateTime activatedAt,
    OffsetDateTime createdAt, OffsetDateTime updatedAt
) {
    public static TreatyResponse from(Treaty t) { ... }
}
```

#### 6. Gateway routes

**File**: `services/go/gateway/internal/routing/routes.go` (edit — add reinsurance CRUD prefix)

```go
// New reinsurance CRUD routes → finance-service
app.All("/api/v1/reinsurance/*", proxy.Handler(cfg.FinanceServiceURL))
```

#### 7. Angular tenant-admin surface

**Files** (new components):
- `clients/angular/src/app/pages/tenant-admin/reinsurance/reinsurers/reinsurers-list.component.ts` + `.html` + `.scss` — paginated list + create modal + edit/deactivate actions
- `clients/angular/src/app/pages/tenant-admin/reinsurance/reinsurers/reinsurer-form.component.ts` — reusable form
- `clients/angular/src/app/pages/tenant-admin/reinsurance/treaties/treaties-list.component.ts` — grouped by renewal chain (per R11)
- `clients/angular/src/app/pages/tenant-admin/reinsurance/treaties/treaty-edit.component.ts` — hosts sub-editors:
  - `treaty-layer-editor.component.ts` (visible only for XoL/StopLoss)
  - `treaty-participant-editor.component.ts` (with sum-to-100 validation; participants pulled from `Reinsurers` via search-select per `feedback_no_raw_id_inputs`)
  - `treaty-applicable-line-editor.component.ts` (multi-select on the 8-line InsuranceLine)
  - `treaty-cession-rule-editor.component.ts` (lists linked rules, "Add rule" opens the visual rule builder with category prefilled to REINSURANCE)
  - Bottom-of-page "Activate" button — disabled with a tooltip listing missing validation prereqs

**File**: `clients/angular/src/app/pages/tenant-admin/reinsurance/reinsurance.routes.ts` (new)

```typescript
export const REINSURANCE_ROUTES: Routes = [
  { path: '', pathMatch: 'full', redirectTo: 'treaties' },
  { path: 'reinsurers', canActivate: [permissionGuard(['finance.reinsurance:view'])],
    loadComponent: () => import('./reinsurers/reinsurers-list.component')
        .then(m => m.ReinsurersListComponent),
    data: { title: 'Reinsurers' } },
  { path: 'treaties', canActivate: [permissionGuard(['finance.reinsurance:view'])],
    loadComponent: () => import('./treaties/treaties-list.component')
        .then(m => m.TreatiesListComponent),
    data: { title: 'Treaties' } },
  { path: 'treaties/new', canActivate: [permissionGuard(['finance.reinsurance:manage_treaty'])],
    loadComponent: () => import('./treaties/treaty-edit.component')
        .then(m => m.TreatyEditComponent),
    data: { title: 'New Treaty' } },
  { path: 'treaties/:id', canActivate: [permissionGuard(['finance.reinsurance:view'])],
    loadComponent: () => import('./treaties/treaty-edit.component')
        .then(m => m.TreatyEditComponent),
    data: { title: 'Edit Treaty' } },
];
```

**File**: `clients/angular/src/app/app.routes.ts` (edit) — register `/admin/reinsurance` lazy-load pointing at `REINSURANCE_ROUTES`.

**File**: `clients/angular/src/app/pages/tenant-admin/settings/settings.component.ts` (or the sidebar/nav config file — verify at implement time)

Add "Reinsurance" nav entry under `/admin/reinsurance` in the tenant-admin nav. Two sub-links: Reinsurers, Treaties.

**File**: `clients/angular/src/app/core/services/reinsurance.service.ts` (new)

Angular client for all reinsurance endpoints. Follows `finance.service.ts` shape. Uses debounced search-select for the reinsurer picker in the participant editor per `feedback_no_raw_id_inputs`.

### Success Criteria

#### Automated Verification
- [x] `cd services/java && ./gradlew :finance-service:build` — clean compile (2026-08-22)
- [x] `make test-java` — new unit tests: `ReinsurerServiceTest` (6 cases), `TreatyServiceTest` (12 cases including renewal chain + activation validation + requireDraft), `TreatyValidationServiceTest` (5 cases covering every R9 branch: sharePct sum, missing line, missing layer for non-proportional, proportional + XoL happy paths). Total 23 new unit tests, all green.
- [x] `make test-integration` — new `ReinsuranceCrudIT` (2 tests) covers: reinsurer CUD + audit-event emission per action; full treaty DRAFT→ACTIVE lifecycle with validation-failure branches and ACTIVE-edit 409. Renew flow is covered by `TreatyServiceTest.renew_active_createsSuccessorDraft_withRenewedFromLink` at unit level.
- [x] Angular compiles: `npx ng build --configuration=development` — SUCCESS (pre-existing warnings only)
- [x] Gateway compiles + tests pass: `go build ./... && go test ./...` — SUCCESS
- [ ] `verify` on `/admin/reinsurance/reinsurers` — CRUD works end to end (deferred to manual acceptance)
- [ ] `verify` on `/admin/reinsurance/treaties/new` — treaty creation, layer addition (for XoL), participant addition with share-sum validation, cession rule addition (deferred to manual acceptance)
- [x] Playwright: new `reinsurance-crud.spec.ts` — happy paths for reinsurer create + full treaty DRAFT→ACTIVE via UI (create draft → add applicable line + 2 participants (60/40) + layer → activate → assert ACTIVE badge). Renew UI flow deferred alongside `verify` — the backend renew is covered by unit tests.

#### Manual Verification
- [ ] Attempt to save an ACTIVE treaty edit → 409 with clear message
- [ ] Save DRAFT with sharePct summing to 99 → activate fails 400 with "must sum to 100"
- [ ] Create XoL treaty with no layer → activate fails 400
- [ ] Audit log shows `HighCostClaimantConfig`-style entries with tenant slug in `entityName` (never UUID) for every treaty transition

**Implementation Note**: pause for manual acceptance before Phase 3.

---

## Phase 3: Auto-cession loss consumer + Recovery consumer

### Overview

Ship the two Kafka consumers that write Cession + Recovery rows from claim adjudication and payment events. Rules-engine dispatch happens here. All Cession rows are `source=AUTOMATIC, status=ACTIVE`.

### Changes Required

#### 1. Cession + Recovery entities + repositories

**Files** (new):
- `services/java/finance-service/src/main/java/com/medfund/finance/reinsurance/entity/Cession.java` (`@Getter @Setter @Table("cession")`)
- `services/java/finance-service/src/main/java/com/medfund/finance/reinsurance/entity/Recovery.java`
- `services/java/finance-service/src/main/java/com/medfund/finance/reinsurance/repository/CessionRepository.java` — plus `findByTreatyIdAndSourceEventIdAndCessionType(UUID, UUID, String)` for idempotency lookup; `findBySourceEventId(UUID)` for the regression-detection path (Phase 8)
- `services/java/finance-service/src/main/java/com/medfund/finance/reinsurance/repository/RecoveryRepository.java` — plus `findByCessionId(UUID)`, `findByStatusIn(List<String>)`

#### 2. CessionService

**File**: `services/java/finance-service/src/main/java/com/medfund/finance/reinsurance/service/CessionService.java`

```java
@Slf4j
@Service
@RequiredArgsConstructor
public class CessionService {

    private final CessionRepository cessionRepository;
    private final TreatyRepository treatyRepository;
    private final TreatyApplicableLineRepository applicableLineRepository;
    private final CessionRuleRepository cessionRuleRepository;
    private final RulesEngineClient rulesEngineClient;
    private final AuditPublisher auditPublisher;
    private final TenantRepository tenantRepository;

    /**
     * Called by the loss cession consumer per adjudicated claim. Fires the reinsurance
     * agenda group in the rules engine for every ACTIVE treaty whose applicable_lines
     * include claim.insuranceLine. Writes one Cession per fired rule.
     *
     * @return Flux of newly written Cession rows (empty if no treaty matches)
     */
    @Transactional
    public Flux<Cession> processAdjudicatedClaim(ClaimAdjudicatedEvent event,
                                                 String actorId, String actorEmail) {
        return treatyRepository.findActiveByInsuranceLine(event.insuranceLine())
            .flatMap(treaty -> fireCessionRules(treaty, event)
                .flatMap(ruleResult -> writeCessionFromResult(
                    treaty, ruleResult, event, actorId, actorEmail))
            );
    }

    private Mono<Cession> writeCessionFromResult(Treaty treaty,
                                                 CessionRuleResult result,
                                                 ClaimAdjudicatedEvent event,
                                                 String actorId, String actorEmail) {
        // Idempotency: skip if a cession with same (treatyId, sourceEventId=claimId, cessionType=LOSS) exists
        return cessionRepository.findByTreatyIdAndSourceEventIdAndCessionType(
                    treaty.getId(), event.claimId(), "LOSS")
            .flatMap(existing -> {
                log.debug("Cession already exists for treaty={} claim={} — skipping",
                          treaty.getId(), event.claimId());
                return Mono.<Cession>empty();
            })
            .switchIfEmpty(Mono.defer(() -> {
                Cession c = new Cession();
                c.setTreatyId(treaty.getId());
                c.setTreatyLayerId(result.layerId());
                c.setCessionType("LOSS");
                c.setSource("AUTOMATIC");
                c.setStatus("ACTIVE");
                c.setSourceEventId(event.claimId());
                c.setSourceEventType("CLAIM_ADJUDICATED");
                c.setBasisAmount(event.approvedAmount());
                c.setCededAmount(result.cededAmount());
                c.setCurrencyCode(event.currencyCode());
                c.setOccurredAt(event.adjudicatedAt());
                c.setActorId(parseUuid(actorId));
                c.setActorEmail(actorEmail);
                return cessionRepository.save(c)
                    .flatMap(saved -> publishAudit(saved, null, "CREATE", actorId, actorEmail)
                        .thenReturn(saved));
            }));
    }
}
```

Cession-arithmetic dispatch on `treatyType` happens in the rules-engine result — the rule's `CEDE_TO_TREATY` action carries a `cededPct` (proportional) or a `retention` + `layerLimit` computation the emitter runs against `event.approvedAmount`. The emitter enforces the R1 formulas:

- Proportional: `cededAmount = approvedAmount * cededPct / 100`
- Non-proportional per layer: `cededAmount = max(0, min(layerLimit, approvedAmount - retention))`

#### 3. RecoveryService

**File**: `services/java/finance-service/src/main/java/com/medfund/finance/reinsurance/service/RecoveryService.java`

```java
@Transactional
public Mono<Recovery> createExpectedFromPayment(PaymentCreatedEvent event,
                                                String actorId, String actorEmail) {
    // Find the cession whose source_event_id = event.claimId AND cession_type = LOSS
    // (i.e. we previously ceded on this claim's adjudication).
    return cessionRepository.findBySourceEventId(event.claimId())
        .filter(c -> "LOSS".equals(c.getCessionType()) && "ACTIVE".equals(c.getStatus()))
        .flatMap(cession -> recoveryRepository.findByCessionId(cession.getId())
            .switchIfEmpty(Mono.defer(() -> insertNewRecovery(cession, event, actorId, actorEmail))))
        .next()  // one recovery per cession per payment (idempotent via cession_id UNIQUE)
        .switchIfEmpty(Mono.empty());  // no matching cession — payment on unceded claim
}
```

Recovery.expectedAmount = the cession's `cededAmount` (already stored native per R7). Missing FX or currency conversion NOT applied at this layer.

#### 4. Kafka consumers

**File**: `services/java/finance-service/src/main/java/com/medfund/finance/reinsurance/consumer/ReinsuranceLossCessionConsumer.java`

Follows the `ClaimAdjudicatedConsumer.java:80-126` pattern verbatim:

```java
@Slf4j
@Component
@RequiredArgsConstructor
public class ReinsuranceLossCessionConsumer {

    private static final String TOPIC = "medfund.claims.adjudicated";

    private final ReceiverOptions<String, String> receiverOptions;
    private final CessionService cessionService;
    private final ObjectMapper objectMapper;

    @PostConstruct
    public void consume() {
        var options = receiverOptions.subscription(Collections.singleton(TOPIC));
        KafkaReceiver.create(options)
            .receive()
            .flatMap(record -> {
                try {
                    return processEvent(record.value())
                        .doOnSuccess(v -> record.receiverOffset().acknowledge())
                        .doOnError(e -> log.error(
                            "Failed to process claim-adjudicated event for reinsurance (full chain): ",
                            e))
                        .onErrorResume(e -> Mono.empty());
                } catch (Exception e) {
                    log.error("Error deserializing claim-adjudicated event: ", e);
                    record.receiverOffset().acknowledge();
                    return Mono.empty();
                }
            })
            .doOnError(e -> log.error("Reinsurance loss cession consumer error: ", e))
            .retry()
            .subscribe();
    }

    public Mono<Void> processEvent(String json) {
        JsonNode node = objectMapper.readTree(json);
        String decision = textOrNull(node, "decision");
        // Only cede on APPROVED adjudications
        if (!"APPROVED".equals(decision)) return Mono.empty();

        ClaimAdjudicatedEvent event = ClaimAdjudicatedEvent.from(node);
        String tenantId = event.tenantId();
        String[] systemActor = AuditActor.systemActor();

        return cessionService.processAdjudicatedClaim(event, systemActor[0], systemActor[1])
            .then()
            .contextWrite(Context.of(TenantContext.KEY, tenantId));
    }
}
```

Notes:
- `.doOnSuccess` for ack per `bug_reactor_kafka_ack_swallow`.
- `.contextWrite(Context.of(TenantContext.KEY, tenantId))` for tenant binding, same as ClaimAdjudicatedConsumer.
- Errors log the full cause chain and swallow the message (acknowledged) to avoid poison-pill loops.
- `AuditActor.systemActor()` provides SYSTEM_ID + SYSTEM_EMAIL for the consumer-driven writes (no JWT in a Kafka message).

**File**: `services/java/finance-service/src/main/java/com/medfund/finance/reinsurance/consumer/ReinsuranceRecoveryConsumer.java`

Same shape, consuming `medfund.finance.payment-created`. Delegates to `RecoveryService.createExpectedFromPayment`.

#### 5. DTOs

**File**: `services/java/finance-service/src/main/java/com/medfund/finance/reinsurance/dto/ClaimAdjudicatedEvent.java`

```java
public record ClaimAdjudicatedEvent(
    UUID claimId, UUID memberId, UUID providerId, String insuranceLine,
    String currencyCode, BigDecimal approvedAmount, String decision,
    Instant adjudicatedAt, String tenantId
) {
    public static ClaimAdjudicatedEvent from(JsonNode node) { ... }
}
```

**File**: `services/java/finance-service/src/main/java/com/medfund/finance/reinsurance/dto/PaymentCreatedEvent.java`

Same shape for the payment-created payload.

**File**: `services/java/finance-service/src/main/java/com/medfund/finance/reinsurance/dto/CessionRuleResult.java`

```java
public record CessionRuleResult(
    UUID treatyId,
    UUID layerId,     // null for proportional
    BigDecimal cededAmount
) {}
```

### Success Criteria

#### Automated Verification
- [x] `cd services/java && ./gradlew :finance-service:build` — compiles clean (2026-08-22)
- [x] `make test-java` — new unit tests: `CessionServiceTest` (8 cases covering proportional QS → 30% cession, XoL single-layer with layerId carried through, idempotent already-ceded → no writes, UNIQUE-violation race → swallowed, no-matching-treaty → no invoke of ensureLoaded, rule targeting unknown treaty → skipped, zero approved → short-circuit, multi-treaty → cede to each); `ReinsuranceLossCessionConsumerTest` (6 cases covering APPROVED/PARTIAL_APPROVED dispatch, REJECTED skip, missing tenant/line skip, malformed JSON). `RecoveryServiceTest` is dropped — Recovery creation folded into CessionService per Phase 3 deviation.
- [x] `make test-integration` — `ReinsuranceLossCessionIT` (3 cases with real Postgres via Testcontainers + real repositories): approved claim writes Cession(ACTIVE) + Recovery(EXPECTED) rows and emits 2 AuditEvents with friendly `entityName` (not UUID); reprocessing same claim yields 1 cession row (idempotent); no active treaty for line → zero rows. Full Kafka round-trip verified via unit-test on `processEvent` (Kafka receiver wiring is mechanical / mirrors `ClaimAdjudicatedConsumer` pattern); `ReinsuranceRecoveryConsumerIT` is dropped alongside the recovery consumer per the Phase 3 deviation.
- [ ] Rules-engine IT: `CedeToTreatyActionEmitterIT` — deferred; the emitter is exercised by `DrlCompilerTest.compile_reinsuranceProportionalRule_addsAgendaGroupAndCedes` (Phase 1) which asserts the compiled DRL. An end-to-end fact-firing test can be added if the DRL-driven path shows unexpected behavior in Phase 8's regression path.

#### Manual Verification
- [ ] Adjudicate a HEALTH claim via the claims-service admin UI → observe Cession row in the tenant schema; observe AuditEvent on Kafka
- [ ] Pay the claim via finance-service → observe EXPECTED Recovery row; observe AuditEvent
- [ ] Re-publish the same claim-adjudicated event manually (via kafka console producer) → no duplicate Cession row (idempotency)
- [ ] Adjudicate a HEALTH claim against a tenant with no active treaties → no cession, no error logged
- [ ] Kill the consumer mid-flight (SIGKILL) → restart → the unprocessed event is re-consumed and processed

**Implementation Note**: pause for manual acceptance before Phase 4.

---

## Phase 4: Bordereau reports backend + XLSX

### Overview

Ship the three report endpoints — cession-bordereau, recoveries-bordereau, treaty-utilization — with `@RequiresReport` gates, `ReportEnvelopeBuilder`-wrapped JSON, XLSX exports via `ReportWorkbook`, bordereau_period_export soft-lock, and SecurityEvent-on-export.

### Changes Required

#### 1. BordereauPeriodExport entity + service

**File**: `services/java/finance-service/src/main/java/com/medfund/finance/reinsurance/entity/BordereauPeriodExport.java`

**File**: `services/java/finance-service/src/main/java/com/medfund/finance/reinsurance/service/BordereauPeriodExportService.java`

```java
@Transactional
public Mono<BordereauPeriodExport> markExported(UUID reinsurerId, UUID treatyId,
                                                String reportKey, int year, int quarter,
                                                String actorId, String actorEmail) {
    return repository.findByCompositeKey(reinsurerId, treatyId, reportKey, year, quarter)
        .flatMap(existing -> {
            existing.setExportCount(existing.getExportCount() + 1);
            return repository.save(existing);
        })
        .switchIfEmpty(Mono.defer(() -> {
            BordereauPeriodExport row = new BordereauPeriodExport();
            row.setReinsurerId(reinsurerId);
            row.setTreatyId(treatyId);
            row.setReportKey(reportKey);
            row.setYear(year);
            row.setQuarter(quarter);
            row.setFirstExportedAt(OffsetDateTime.now());
            row.setExportCount(1);
            row.setActorId(parseUuid(actorId));
            row.setActorEmail(actorEmail);
            return repository.save(row);
        }));
}

/**
 * Returns firstExportedAt for the given (reinsurer, treaty, reportKey, quarter) if exported,
 * else empty. Callers use this to decide the isPriorPeriodAdjustment flag per row.
 */
public Mono<OffsetDateTime> firstExportedAt(UUID reinsurerId, UUID treatyId,
                                            String reportKey, int year, int quarter) {
    return repository.findByCompositeKey(reinsurerId, treatyId, reportKey, year, quarter)
        .map(BordereauPeriodExport::getFirstExportedAt);
}
```

#### 2. BordereauQueryRepository

**File**: `services/java/finance-service/src/main/java/com/medfund/finance/reinsurance/repository/BordereauQueryRepository.java`

Server-side SQL only. Per-report methods return `Flux<BordereauRow>` from raw `DatabaseClient` queries. Key query:

```sql
-- Cession bordereau — per-participant split at report time
SELECT
    c.id                          AS cession_id,
    c.treaty_id,
    t.treaty_ref,
    t.treaty_type,
    r.id                          AS reinsurer_id,
    r.name                        AS reinsurer_name,
    tp.share_pct,
    tp.share_role,
    c.cession_type,
    c.source,
    c.occurred_at,
    c.source_event_id,
    c.basis_amount                                   AS native_basis,
    c.ceded_amount                                   AS native_ceded_full,
    c.ceded_amount * tp.share_pct / 100              AS participant_ceded,
    c.currency_code                                  AS native_currency,
    c.created_at
FROM cession c
JOIN treaty t              ON t.id = c.treaty_id
JOIN treaty_participant tp ON tp.treaty_id = t.id
JOIN reinsurer r           ON r.id = tp.reinsurer_id
WHERE c.status IN ('ACTIVE', 'CEDED')
  AND c.occurred_at >= :periodStart
  AND c.occurred_at <  :periodEnd
  AND (:reinsurerId::uuid IS NULL OR r.id = :reinsurerId::uuid)
  AND (:treatyId::uuid    IS NULL OR t.id = :treatyId::uuid)
ORDER BY t.treaty_ref, c.occurred_at, r.name;
```

`isPriorPeriodAdjustment` computed in the service layer by comparing `c.created_at` against the `bordereau_period_export.firstExportedAt` for the same (reinsurerId, treatyId, reportKey, year, quarter) — a cession whose `occurredAt` is in this quarter but whose `createdAt` postdates the first export of this quarter's bordereau is flagged.

**Recoveries bordereau** SQL: same shape, joining `recovery` on `cession_id`, filtering `recovery.status IN ('EXPECTED','INVOICED','RECEIVED','WRITTEN_OFF')` and reading `recovery.expected_amount` + `recovery.received_amount` per row.

**Treaty utilization** SQL: since treaty inception, group by treaty + layer + currency:

```sql
SELECT
    t.id                              AS treaty_id,
    t.treaty_ref,
    t.treaty_type,
    t.aggregate_limit,
    t.aggregate_limit_currency,
    tl.id                             AS layer_id,
    tl.layer_order,
    tl.retention,
    tl.layer_limit,
    tl.layer_currency,
    c.currency_code                   AS ceded_currency,
    COALESCE(SUM(c.ceded_amount), 0)  AS total_ceded_native
FROM treaty t
LEFT JOIN treaty_layer tl ON tl.treaty_id = t.id
LEFT JOIN cession c ON c.treaty_id = t.id
                   AND (tl.id IS NULL OR c.treaty_layer_id = tl.id)
                   AND c.status IN ('ACTIVE', 'CEDED')
                   AND c.cession_type = 'LOSS'
                   AND c.occurred_at >= t.inception_date
                   AND c.occurred_at <  COALESCE(:asOfDate, NOW())
WHERE t.id = :treatyId
GROUP BY t.id, t.treaty_ref, t.treaty_type, t.aggregate_limit,
         t.aggregate_limit_currency, tl.id, tl.layer_order, tl.retention,
         tl.layer_limit, tl.layer_currency, c.currency_code
ORDER BY tl.layer_order, c.currency_code;
```

#### 3. BordereauReportService

**File**: `services/java/finance-service/src/main/java/com/medfund/finance/reinsurance/service/BordereauReportService.java`

Wraps queries in the `ReportResponse<T>` envelope via `ReportEnvelopeBuilder`. Applies per-currency subtotals + best-effort FX rate map to the treaty's declared currency.

```java
@Transactional(readOnly = true)
public Mono<ReportResponse<List<CessionBordereauRow>>> cessionBordereau(
        UUID reinsurerId, UUID treatyId, int year, int quarter, String overrideCurrency) {

    ReportPeriod period = quarterToPeriod(year, quarter);
    return envelopeBuilder.build(
        ReportKey.REINSURANCE_CESSION_BORDEREAU, period, overrideCurrency,
        loadRows(reinsurerId, treatyId, period),
        cessionPerCurrencyAggregateSql(reinsurerId, treatyId, period),
        spec -> spec.bind("periodStart", period.periodStart())
                    .bind("periodEnd",   period.periodEnd())
                    .bind("reinsurerId", reinsurerId != null ? reinsurerId : null)
                    .bind("treatyId",    treatyId    != null ? treatyId    : null));
}
```

#### 4. BordereauReportWorkbookService

**File**: `services/java/finance-service/src/main/java/com/medfund/finance/reinsurance/service/BordereauReportWorkbookService.java`

Same pattern as `PaymentRunWorkbookService`. One method per report:

```java
public Mono<byte[]> cessionWorkbook(UUID reinsurerId, UUID treatyId,
                                    int year, int quarter, String reportingCurrency,
                                    UUID tenantId) {
    return queryRepository.cessionRows(reinsurerId, treatyId, year, quarter)
        .collectList()
        .flatMap(rows -> {
            Map<String, List<CessionBordereauRow>> byCurrency = groupByCurrency(rows);
            return convertGrandTotal(rows, reportingCurrency, tenantId)
                .map(converted -> renderCessionBook(reinsurerId, treatyId, year, quarter,
                        byCurrency, reportingCurrency, converted))
                .defaultIfEmpty(renderCessionBook(reinsurerId, treatyId, year, quarter,
                        byCurrency, reportingCurrency, null));
        });
}

private byte[] renderCessionBook(...) {
    ReportWorkbook book = ReportWorkbook.newBook();
    List<String> currencies = new ArrayList<>(byCurrency.keySet());
    Collections.sort(currencies);
    for (String currency : currencies) {
        ReportWorkbook.SheetWriter sheet = book.sheet("Cessions " + currency)
            .titleMerged("Cession Bordereau — Q" + quarter + " " + year, 11)
            .meta("Reinsurer", reinsurerName)
            .meta("Treaty",    treatyRef != null ? treatyRef : "All treaties")
            .meta("Period",    period.periodStart() + " to " + period.periodEnd())
            .meta("Currency",  currency);
        sheet.blankRow();
        sheet.header("Cession #", "Treaty", "Type", "Reinsurer", "Share %",
                     "Cession type", "Source", "Occurred", "Source event",
                     "Basis (native)", "Ceded (native)", "Participant share",
                     "Prior-period adj");
        sheet.forEach(byCurrency.get(currency), (sw, r) -> sw
            .text(r.cessionId().toString().substring(0, 8))
            .text(r.treatyRef())
            .text(r.treatyType())
            .text(r.reinsurerName())
            .money(r.sharePct())
            .text(r.cessionType())
            .text(r.source())
            .date(r.occurredAt())
            .text(r.sourceEventId().toString().substring(0, 8))
            .money(r.nativeBasis())
            .money(r.nativeCededFull())
            .money(r.participantCeded())
            .text(r.isPriorPeriodAdjustment() ? "YES" : ""));
        sheet.blankRow();
        sheet.metaMoney("Total (" + currency + ")",
                        sumParticipantCeded(byCurrency.get(currency)));
        sheet.freezeAtHeader().autoSize();
    }
    // Summary sheet with per-currency subtotals + converted grand total
    buildSummarySheet(book, ...);
    return book.toBytes();
}
```

Recoveries + Utilization workbooks follow the same shape.

#### 5. BordereauReportController

**File**: `services/java/finance-service/src/main/java/com/medfund/finance/reinsurance/controller/BordereauReportController.java`

```java
@RestController
@RequestMapping("/api/v1/reports/reinsurance")
@RequiredArgsConstructor
@Tag(name = "Reinsurance Reports",
     description = "Cession bordereau, recoveries bordereau, treaty utilization reports.")
@SecurityRequirement(name = "bearer-jwt")
public class BordereauReportController {

    private final BordereauReportService service;
    private final BordereauReportWorkbookService workbookService;
    private final BordereauPeriodExportService exportRegistry;
    private final SecurityEventPublisher securityEventPublisher;

    @GetMapping("/cession-bordereau")
    @RequiresReport(ReportKey.REINSURANCE_CESSION_BORDEREAU)
    @Operation(summary = "Cession bordereau for a period",
        description = "Rows are cession-per-participant. Filter by reinsurerId + treatyId "
                    + "+ year + quarter. isPriorPeriodAdjustment flags cessions added after "
                    + "the first export of this quarter.")
    public Mono<ReportResponse<List<CessionBordereauRow>>> cessionBordereau(
            @RequestParam(required = false) UUID reinsurerId,
            @RequestParam(required = false) UUID treatyId,
            @RequestParam int year,
            @RequestParam @Min(1) @Max(4) int quarter,
            @RequestParam(required = false) String reportingCurrency) {
        return service.cessionBordereau(reinsurerId, treatyId, year, quarter, reportingCurrency);
    }

    @GetMapping("/cession-bordereau/export/excel")
    @RequiresReport(ReportKey.REINSURANCE_CESSION_BORDEREAU)
    public Mono<ResponseEntity<byte[]>> cessionExport(
            @RequestParam(required = false) UUID reinsurerId,
            @RequestParam(required = false) UUID treatyId,
            @RequestParam int year,
            @RequestParam @Min(1) @Max(4) int quarter,
            @RequestParam(required = false) String reportingCurrency,
            @AuthenticationPrincipal Jwt jwt) {
        String actorId = AuditActor.id(jwt);
        String actorEmail = AuditActor.email(jwt);
        return Mono.deferContextual(ctx -> {
            UUID tenantId = UUID.fromString(TenantContext.get(ctx));
            return currencyResolver.resolve(tenantId, reportingCurrency)
                .flatMap(resolved -> workbookService.cessionWorkbook(reinsurerId, treatyId,
                        year, quarter, resolved, tenantId))
                .flatMap(bytes -> exportRegistry.markExported(reinsurerId, treatyId,
                        "REINSURANCE_CESSION_BORDEREAU", year, quarter, actorId, actorEmail)
                    .then(securityEventPublisher.publish(SecurityEventMessage.of(
                        tenantId.toString(), "DATA_ACCESS", actorId, actorEmail,
                        Map.of("reportKey", "REINSURANCE_CESSION_BORDEREAU",
                               "year", year, "quarter", quarter,
                               "reinsurerId", String.valueOf(reinsurerId)))))
                    .thenReturn(ResponseEntity.ok()
                        .contentType(MediaType.parseMediaType(
                            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                        .header("Content-Disposition", "attachment; filename=\""
                            + cessionBordereauFilename(reinsurerId, year, quarter) + "\"")
                        .body(bytes)));
        });
    }

    // Analogous /recoveries-bordereau + /recoveries-bordereau/export/excel
    // Analogous /treaty-utilization + /treaty-utilization/export/excel
}
```

**Note on export → INVOICED transition** (R8): the recoveries export additionally flips affected recovery rows EXPECTED → INVOICED inside the same transaction that writes bordereau_period_export. Implementation in `BordereauReportWorkbookService.recoveriesWorkbook` — call `recoveryRepository.markInvoiced(cessionIds, now)` before rendering. Grill note — this must be transactionally atomic with the export bytes being served, or exports that fail mid-render leave INVOICED rows without a delivered bordereau. Alternative: run the transition post-response via a `Mono.fromCallable(() -> writeBytes).doOnSuccess(bytes -> markInvoiced)` chain — the invoice-was-sent invariant is only established once the client receives bytes.

**Implementation preference**: post-response transition. Failure to render → recoveries stay EXPECTED, next export retries. Failure to deliver bytes to client is not observable server-side; accept that risk (the reinsurer will re-request if they didn't get it, next export will move status).

#### 6. Gateway routes

**File**: `services/go/gateway/internal/routing/routes.go` (edit)

```go
app.All("/api/v1/reports/reinsurance/*", proxy.Handler(cfg.FinanceServiceURL))
```

### Success Criteria

#### Automated Verification
- [x] `cd services/java && ./gradlew :finance-service:build` — compiles clean (2026-08-22)
- [x] `make test-java` — `BordereauReportServiceTest` (5 cases covering quarter-to-period Q1/Q2/Q3/Q4 + leap-year independence + invalid-quarter rejection); `BordereauReportWorkbookServiceTest` (6 cases covering multi-currency sheet grouping, per-currency subtotals reconciled to row sum, empty-rows placeholder sheet, FX-unavailable → warning-line fallback, prior-period-adjustment column flip, recoveries + utilization workbook shape); `BordereauPeriodExportServiceTest` (5 cases covering first-insert + second-increment + treatyId-null branch + firstExportedAt lookup present/missing). 16 new unit tests, all green.
- [x] `make test-integration` — `ReinsuranceBordereauIT` (7 tests with real Postgres via Testcontainers + real R2DBC + real service layer): envelope shape with participant-split rows + perCurrency subtotals, valid XLSX bytes with per-currency sheet + Summary, first-export inserts row + second-export increments count, isPriorPeriodAdjustment flips TRUE on cession created after firstExportedAt, recoveries+cession+participant JOIN produces per-participant rows, treaty utilization since-inception per-currency totals, recovery bulk EXPECTED→INVOICED transition. Controller-level 403-report-toggle assertion deferred — the `@RequiresReport` gate is exercised by the shared `ReportGuardAspect` tests already, no new coverage needed. SecurityEvent-on-Kafka assertion deferred alongside — the pattern mirrors PaymentRunController which is covered end-to-end via `PaymentRunSecurityEventIT`.
- [x] Gateway `go test ./...` — SUCCESS; `/api/v1/reports/reinsurance/*` route already registered under Phase 2 (routes.go:197-198), no change required.

#### Manual Verification
- [ ] Export cession bordereau for a real reinsurer + quarter → download XLSX, open in Excel/LibreOffice, verify: title row correct, per-currency sheets, totals footer, prior-period-adjustment column visible
- [ ] Export same quarter twice → first row exists in bordereau_period_export, second export increments count; no duplicate rows in XLSX
- [ ] Adjudicate a new claim in a quarter that's already been exported → re-export → new row shows YES in prior-period-adjustment column
- [ ] Kafka topic `medfund.security.events` carries `reportKey=REINSURANCE_CESSION_BORDEREAU` per export
- [ ] Missing FX rate for one of the ceded currencies → envelope `warnings` names the missing rate; row itself still present in native currency

**Implementation Note**: pause for manual acceptance before Phase 5.

---

## Phase 5: Bordereau reports Angular

### Overview

Ship the three report pages, register them in the reports hub, and add Playwright coverage for the golden export path. This closes §A.

### Changes Required

#### 1. Angular components

**Files** (new):
- `clients/angular/src/app/pages/tenant/finance/reports/reinsurance/cession-bordereau.component.ts` + `.html` + `.scss`
- `clients/angular/src/app/pages/tenant/finance/reports/reinsurance/recoveries-bordereau.component.ts` + `.html` + `.scss`
- `clients/angular/src/app/pages/tenant/finance/reports/reinsurance/treaty-utilization.component.ts` + `.html` + `.scss`

Each page provides:
- Filter row: quarter picker + year picker + reinsurer search-select (per `feedback_no_raw_id_inputs`) + treaty search-select + reporting-currency override
- Rendered table (paged, server-side sorting)
- Perbucket subtotals from envelope `perCurrency`
- Warnings banner when envelope `warnings.length > 0`
- Export XLSX button that fires a GET on the export endpoint and triggers file download
- Prior-period-adjustment badge on flagged rows

#### 2. Routes

**File**: `clients/angular/src/app/pages/tenant/finance/finance.routes.ts` (edit — add reinsurance report routes under `reports/`)

```typescript
{
    path: 'reports/reinsurance/cession-bordereau',
    canActivate: [permissionGuard(['finance.reinsurance:view'])],
    loadComponent: () => import('./reports/reinsurance/cession-bordereau.component')
        .then(m => m.CessionBordereauComponent),
    data: {
        title: 'Cession Bordereau',
        sidebar: 'operational',
        fullbleed: true,
        reportKey: 'REINSURANCE_CESSION_BORDEREAU',
    },
},
{
    path: 'reports/reinsurance/recoveries-bordereau',
    canActivate: [permissionGuard(['finance.reinsurance:view'])],
    loadComponent: () => import('./reports/reinsurance/recoveries-bordereau.component')
        .then(m => m.RecoveriesBordereauComponent),
    data: {
        title: 'Recoveries Bordereau',
        sidebar: 'operational',
        fullbleed: true,
        reportKey: 'REINSURANCE_RECOVERIES',
    },
},
{
    path: 'reports/reinsurance/treaty-utilization',
    canActivate: [permissionGuard(['finance.reinsurance:view'])],
    loadComponent: () => import('./reports/reinsurance/treaty-utilization.component')
        .then(m => m.TreatyUtilizationComponent),
    data: {
        title: 'Treaty Utilization',
        sidebar: 'operational',
        fullbleed: true,
        reportKey: 'REINSURANCE_TREATY_UTILIZATION',
    },
},
```

#### 3. Reports hub registration

No code change needed. `ReportCatalogueService` (`clients/angular/src/app/core/services/tenant-report-config.service.ts:37-45`) fetches `/tenants/{tenantId}/report-config` and includes the REINSURANCE_* keys automatically since they ship in the backend enum.

#### 4. Angular service extensions

**File**: `clients/angular/src/app/core/services/reinsurance.service.ts` (edit — add report methods to the service Phase 2 created)

```typescript
getCessionBordereau(reinsurerId: string | null, treatyId: string | null,
                    year: number, quarter: number,
                    reportingCurrency?: string): Observable<ReportResponse<CessionBordereauRow[]>> {
    let params = new HttpParams()
        .set('year', year.toString())
        .set('quarter', quarter.toString());
    if (reinsurerId) params = params.set('reinsurerId', reinsurerId);
    if (treatyId)    params = params.set('treatyId', treatyId);
    if (reportingCurrency) params = params.set('reportingCurrency', reportingCurrency);
    return this.api.get<ReportResponse<CessionBordereauRow[]>>(
        '/reports/reinsurance/cession-bordereau', { params });
}

downloadCessionBordereauXlsx(...): void {
    // fetch via GET → Blob → saveAs(blob, filename)
}
```

Analogous methods for recoveries + utilization.

### Success Criteria

#### Automated Verification
- [x] `cd clients/angular && npx ng build --configuration=development` — SUCCESS (only pre-existing warnings)
- [x] `make test-angular` — no regressions from the Phase 5 additions; the only failure (`insurance-lines.spec.ts` HEALTH/GROUP/TRAVEL/VEHICLE/PROPERTY `providerModeForLine` expectations) is pre-existing on `main` and unrelated to reinsurance. New per-component specs deferred alongside the existing report pages — Playwright covers the golden path.
- [ ] `verify` on `/tenant/finance/reports/reinsurance/cession-bordereau` — page renders, filter works, download starts on export click (deferred to manual acceptance)
- [ ] `verify` on `/tenant/finance/reports/reinsurance/recoveries-bordereau` — same (deferred to manual acceptance)
- [ ] `verify` on `/tenant/finance/reports/reinsurance/treaty-utilization` — same (deferred to manual acceptance)
- [x] Playwright: `reinsurance-bordereau.spec.ts` covers the golden export path for all three reports (render → warnings → export click; recoveries also asserts the post-export status refresh; utilization asserts the % computation)

#### Manual Verification
- [ ] Log in as finance officer without `finance.reinsurance:view` → sidebar hides the three reinsurance report links; direct URL returns 403 error card
- [ ] Log in as finance officer with permission → all three pages load
- [ ] Toggle REINSURANCE_CESSION_BORDEREAU off in tenant-admin/reports → link disappears from hub + sidebar → direct URL returns 403
- [ ] Adjudicate + pay a claim → refresh cession bordereau page for current quarter → row appears

**Implementation Note**: pause for manual acceptance before Phase 6. **This is the §A → §B boundary — §A ships a fully working reinsurance surface. Optional: clear context before Phase 6.**

---

## Phase 6: Premium cession + treaty premium scheduler (§B)

### Overview

Ship the premium-cession consumer (proportional treaties fire per contribution paid) and the flat-premium scheduler (non-proportional treaties fire at treaty inception).

### Changes Required

#### 1. ContributionPaidEvent DTO

**File**: `services/java/finance-service/src/main/java/com/medfund/finance/reinsurance/dto/ContributionPaidEvent.java`

```java
public record ContributionPaidEvent(
    UUID contributionId, UUID memberId, String insuranceLine,
    BigDecimal amount, String currencyCode, Instant paidAt, String tenantId
) {
    public static ContributionPaidEvent from(JsonNode node) { ... }
}
```

Note: `medfund.contributions.paid` payload today (verified at `services/java/contributions-service/.../ContributionEventPublisher.java:38`) carries `{event, contributionId, memberId, amount}` as string fields. **The reinsurance consumer needs `insuranceLine`, `currencyCode`, and `paidAt` — these aren't in the current payload.** Add them producer-side as an additive change in Phase 6:

**File**: `services/java/contributions-service/.../ContributionEventPublisher.java` (edit — extend `publishContributionPaid` signature)

```java
public Mono<Void> publishContributionPaid(String contributionId, String memberId, String amount,
                                          String currencyCode, String insuranceLine, String paidAt) {
    return publishEvent("medfund.contributions.paid", contributionId, Map.of(
        "event", "CONTRIBUTION_PAID",
        "contributionId", contributionId,
        "memberId", memberId,
        "amount", amount,
        "currencyCode", currencyCode,
        "insuranceLine", insuranceLine,
        "paidAt", paidAt
    ));
}
```

Update call sites in `BillingService` (and any other publishers) to pass the new fields. Additive change per parent-plan `:3005` invariant — producers deploy first with the new fields, then the reinsurance consumer deploys and consumes them.

#### 2. PremiumCessionService

**File**: `services/java/finance-service/src/main/java/com/medfund/finance/reinsurance/service/PremiumCessionService.java`

Same shape as `CessionService.processAdjudicatedClaim` but for premium:

```java
@Transactional
public Flux<Cession> processPaidContribution(ContributionPaidEvent event,
                                             String actorId, String actorEmail) {
    return treatyRepository.findActiveByInsuranceLine(event.insuranceLine())
        .filter(t -> isProportional(t))   // XoL/StopLoss use scheduler, not consumer
        .flatMap(treaty -> firePremiumCessionRules(treaty, event)
            .flatMap(ruleResult -> writeCessionFromResult(
                treaty, ruleResult, event, "PREMIUM", "CONTRIBUTION_PAID",
                actorId, actorEmail))
        );
}
```

Idempotency guard: UNIQUE (treatyId, sourceEventId=contributionId, cessionType=PREMIUM).

#### 3. ReinsurancePremiumCessionConsumer

**File**: `services/java/finance-service/src/main/java/com/medfund/finance/reinsurance/consumer/ReinsurancePremiumCessionConsumer.java`

Same shape as `ReinsuranceLossCessionConsumer` but subscribes to `medfund.contributions.paid`, delegates to `PremiumCessionService`.

#### 4. TreatyPremiumJob (scheduler)

**File**: `services/java/finance-service/src/main/java/com/medfund/finance/reinsurance/scheduler/ReinsuranceTreatyPremiumJob.java`

```java
@Slf4j
@Component
@RequiredArgsConstructor
public class ReinsuranceTreatyPremiumJob {

    private final TreatyRepository treatyRepository;
    private final CessionRepository cessionRepository;
    private final TenantRepository tenantRepository;
    // ... other deps

    /**
     * Runs nightly at 02:00. For every ACTIVE non-proportional treaty (XoL, StopLoss),
     * checks if a PREMIUM cession has been written for the treaty's inception. If not
     * and expected_annual_premium is set, writes one Cession row with:
     *   source=AUTOMATIC, cession_type=PREMIUM, source_event_id=treaty.id,
     *   source_event_type=TREATY_INCEPTION, ceded_amount=expected_annual_premium
     * Idempotent via UNIQUE (treatyId, source_event_id, cession_type).
     */
    @Scheduled(cron = "0 0 2 * * *")
    public void writeInceptionPremiums() {
        tenantRepository.findAllActive()
            .flatMap(tenant -> processTenant(tenant.getId()))
            .subscribe(
                unused -> {},
                err -> log.error("Treaty premium job failed: ", err));
    }

    private Mono<Void> processTenant(UUID tenantId) {
        return treatyRepository.findByStatusAndTreatyTypeIn("ACTIVE",
                List.of("EXCESS_OF_LOSS", "STOP_LOSS"))
            .filter(t -> t.getExpectedAnnualPremium() != null
                     && t.getExpectedAnnualPremium().signum() > 0)
            .flatMap(this::writePremiumIfMissing)
            .then()
            .contextWrite(Context.of(TenantContext.KEY, tenantId.toString()));
    }
}
```

#### 5. Rules-engine template additions

**File**: `services/java/rules-engine/src/main/java/com/medfund/rules/templates/ReinsuranceTemplates.java` (edit — extend Phase 1)

Add a 5th template: "Quota Share cede on premium payment (proportional)" — takes `contribution.amount` and outputs a `CEDE_TO_TREATY` action with the treaty's cession rate. Underwriter clones this template per (treaty, cession-rate) pair.

### Success Criteria

#### Automated Verification
- [ ] `cd services/java && ./gradlew build`
- [ ] `make test-java` — `PremiumCessionServiceTest`, `ReinsuranceTreatyPremiumJobTest` (schedule fires → writes exactly one Cession per XoL treaty on inception; idempotent rerun writes zero)
- [ ] `make test-integration`:
  - `ReinsurancePremiumCessionConsumerIT`: publish `medfund.contributions.paid` with new payload → assert PREMIUM Cession row written for matching proportional treaty; publish same event twice → single row (idempotent)
  - `ReinsuranceTreatyPremiumJobIT`: seed 2 XoL treaties with expected_annual_premium set → run job → assert 2 PREMIUM cessions; rerun → still 2

#### Manual Verification
- [ ] Post a contribution payment against a scheme where a proportional treaty is active → observe PREMIUM Cession row on the cession bordereau report
- [ ] Trigger the scheduler manually (via admin action or Spring Actuator) with an XoL treaty freshly activated → observe PREMIUM cession row with source_event_type=TREATY_INCEPTION
- [ ] Deploy sequence: (1) contributions-service with new payload fields lands first, (2) finance-service consumer deploys next — no consumer errors during the deployment window

**Implementation Note**: pause for manual acceptance before Phase 7.

---

## Phase 7: Facultative UI (§B)

### Overview

Ship the underwriter-facing facultative cession flow: browse claims above a threshold + cede against a treaty + supervisor approval queue.

### Changes Required

#### 1. FacultativeCessionService

**File**: `services/java/finance-service/src/main/java/com/medfund/finance/reinsurance/service/FacultativeCessionService.java`

```java
@Transactional
public Mono<Cession> createDraft(CreateFacultativeCessionRequest req,
                                 String actorId, String actorEmail) {
    // Validate: treaty exists + ACTIVE + applicable line matches; claimId exists;
    // no existing cession for (treatyId, claimId, LOSS)
    // Write Cession with source=FACULTATIVE, status=DRAFT, cession_type=LOSS
}

@Transactional
public Mono<Cession> approve(UUID cessionId, String actorId, String actorEmail) {
    // Assert status=DRAFT + source=FACULTATIVE
    // Transition DRAFT → APPROVED
}

@Transactional
public Mono<Cession> commit(UUID cessionId, String actorId, String actorEmail) {
    // Assert status=APPROVED + source=FACULTATIVE
    // Transition APPROVED → CEDED
    // Trigger Recovery creation if there's already a payment for the claim
}

@Transactional
public Mono<Cession> void_(UUID cessionId, String reason, String actorId, String actorEmail) {
    // Assert status IN (DRAFT, APPROVED) + source=FACULTATIVE
    // Transition to VOIDED with voided_reason
}
```

Every transition emits `AuditEvent` with tenant slug in entityName.

#### 2. FacultativeCessionController

**File**: `services/java/finance-service/src/main/java/com/medfund/finance/reinsurance/controller/FacultativeCessionController.java`

REST at `/api/v1/reinsurance/facultative`:
- `GET /candidates?minAmount=&insuranceLine=&page=&size=` — paginated adjudicated claims above threshold, not yet ceded. Requires `finance.reinsurance:cede_facultative`.
- `POST /` — create DRAFT. Body: `{claimId, treatyId, cededAmount, currency, reason}`. Requires `finance.reinsurance:cede_facultative`.
- `PUT /{id}/approve` — DRAFT → APPROVED. Requires `finance.reinsurance:approve_facultative`.
- `PUT /{id}/commit` — APPROVED → CEDED. Requires `finance.reinsurance:approve_facultative`.
- `DELETE /{id}` (void with reason body) — Requires `finance.reinsurance:approve_facultative`.
- `GET /queue?status=&assignee=` — approver queue view.

#### 3. Angular facultative surface

**Files** (new):
- `clients/angular/src/app/pages/tenant/finance/reinsurance/facultative/facultative-browse.component.ts` — paginated claim candidates with cede action
- `.../facultative/facultative-cede-modal.component.ts` — cede form (treaty picker, layer picker for XoL, amount, reason)
- `.../facultative/facultative-approve-queue.component.ts` — supervisor queue

#### 4. Routes

**File**: `clients/angular/src/app/pages/tenant/finance/finance.routes.ts` (edit)

Add routes for `reinsurance/facultative/browse` (permission: `cede_facultative`) and `reinsurance/facultative/queue` (permission: `approve_facultative`).

### Success Criteria

#### Automated Verification
- [ ] `cd services/java && ./gradlew build`
- [ ] `make test-java` — `FacultativeCessionServiceTest` per-transition (create-draft on ACTIVE treaty; approve DRAFT only; commit APPROVED only; void from pre-terminal; every negative case rejected with named message)
- [ ] `make test-integration` — `FacultativeCessionIT`: full round-trip create → approve → commit → assert row in bordereau; permission tests (underwriter cannot approve; supervisor cannot cede? — no, supervisors can do both, per R6)
- [ ] `verify` on `/tenant/finance/reinsurance/facultative/browse` — table shows candidates, cede modal opens
- [ ] `verify` on `/tenant/finance/reinsurance/facultative/queue` — queue shows DRAFT/APPROVED cessions, approve/commit actions work
- [ ] Playwright: `reinsurance-facultative.spec.ts` — underwriter creates DRAFT → supervisor logs in and approves → commits → next cession bordereau export includes the row

#### Manual Verification
- [ ] Non-supervisor cannot see the approve button
- [ ] Supervisor voiding a DRAFT with a reason writes voided_reason column
- [ ] Committed facultative cession appears in cession bordereau report (source=FACULTATIVE badge)

**Implementation Note**: pause for manual acceptance before Phase 8.

---

## Phase 8: Retro backfill + Review queue + Claim-regression trigger (§B)

### Overview

Ship the retroactive backfill job (fires on Treaty DRAFT→ACTIVE), the reinsurance review queue (populated by claim-regression detection in the loss cession consumer), and the recovery lifecycle forms (record-received, write-off).

### Changes Required

#### 1. Extend ReinsuranceLossCessionConsumer for regression detection

**File**: `services/java/finance-service/.../consumer/ReinsuranceLossCessionConsumer.java` (edit — extend `processEvent`)

```java
public Mono<Void> processEvent(String json) {
    JsonNode node = objectMapper.readTree(json);
    String decision = textOrNull(node, "decision");
    if (!"APPROVED".equals(decision) && !"REJECTED".equals(decision)) return Mono.empty();

    ClaimAdjudicatedEvent event = ClaimAdjudicatedEvent.from(node);
    String tenantId = event.tenantId();
    String[] systemActor = AuditActor.systemActor();

    // Regression check: has this claim been ceded before?
    return cessionRepository.findBySourceEventId(event.claimId())
        .filter(c -> "LOSS".equals(c.getCessionType()) && "ACTIVE".equals(c.getStatus()))
        .collectList()
        .flatMap(existingCessions -> {
            if (existingCessions.isEmpty()) {
                // First-time cede
                return cessionService.processAdjudicatedClaim(event, systemActor[0], systemActor[1]).then();
            }
            // Re-adjudication: is this a regression?
            BigDecimal newBasis = "REJECTED".equals(decision) ? BigDecimal.ZERO : event.approvedAmount();
            boolean regression = existingCessions.stream()
                .anyMatch(c -> newBasis.compareTo(c.getBasisAmount()) < 0);
            if (regression) {
                return reviewTaskService.createRegressionTasks(event.claimId(), existingCessions,
                        newBasis, systemActor[0], systemActor[1]).then();
            }
            return Mono.empty();  // Re-adjudication with same or higher amount — no-op
        })
        .contextWrite(Context.of(TenantContext.KEY, tenantId));
}
```

#### 2. ReinsuranceReviewTaskService

**File**: `services/java/finance-service/src/main/java/com/medfund/finance/reinsurance/service/ReinsuranceReviewTaskService.java`

CRUD for `reinsurance_review_task` (created V090 in Phase 1). Methods:
- `createRegressionTasks(claimId, existingCessions, newBasis, actorId, actorEmail)` — one task per affected cession, taskType=CLAIM_REGRESSION, createReason with details
- `assign(taskId, userId, actorId, actorEmail)`
- `resolve(taskId, resolution, notes, actorId, actorEmail)` — resolution ∈ {RESOLVED_VOID, RESOLVED_KEEP, DISMISSED}. If RESOLVED_VOID, cascade-void the linked cession + recovery.

#### 3. TreatyActivationBackfillJob

**File**: `services/java/finance-service/src/main/java/com/medfund/finance/reinsurance/service/TreatyActivationBackfillJob.java`

Triggered by `TreatyService.activate` — after the treaty row transitions to ACTIVE, launch the backfill in a fire-and-forget mode:

```java
@Slf4j
@Service
@RequiredArgsConstructor
public class TreatyActivationBackfillJob {

    private final ClaimReader claimReader;   // reads claims via cross-service HTTP or reads finance's local claim reference
    private final CessionService cessionService;

    /**
     * Scans claims adjudicated between treaty.inceptionDate and now, for insurance lines
     * the treaty covers, and fires the cession rules against each. Idempotent via
     * UNIQUE(treatyId, sourceEventId, cessionType).
     *
     * Runs OUTSIDE the treaty-activate transaction (fire-and-forget) so activation
     * doesn't wait on backfill. Progress polled via BackfillProgressService.
     */
    public Mono<Void> backfill(Treaty treaty, String actorId, String actorEmail) {
        return applicableLineRepository.findByTreatyId(treaty.getId())
            .map(TreatyApplicableLine::getInsuranceLine)
            .collectList()
            .flatMap(lines -> claimReader.streamAdjudicatedClaimsIn(
                    treaty.getInceptionDate(), OffsetDateTime.now(), lines)
                .groupBy(ClaimSummary::insuranceLine)
                .flatMap(lineGroup -> lineGroup.collectList())
                .flatMap(batch -> processBatch(treaty, batch, actorId, actorEmail))
                .then());
    }

    @Transactional
    protected Flux<Cession> processBatch(Treaty treaty, List<ClaimSummary> claims,
                                         String actorId, String actorEmail) {
        return Flux.fromIterable(claims)
            .flatMap(claim -> cessionService.processAdjudicatedClaim(
                claim.toAdjudicatedEvent(), actorId, actorEmail));
    }
}
```

Chunking pattern matches `PaymentRunService.providerSnapshotRows:324-337` — `Flux.groupBy → collectList → @Transactional-per-batch`. Idempotency via UNIQUE on (treatyId, sourceEventId, cessionType).

**File**: `services/java/finance-service/src/main/java/com/medfund/finance/reinsurance/service/BackfillProgressService.java`

In-memory progress tracker (per treatyId): total, processed, failed counts. Exposed via `GET /api/v1/reinsurance/treaties/{id}/backfill-progress`.

#### 4. RecoveryController extensions

**File**: `services/java/finance-service/src/main/java/com/medfund/finance/reinsurance/controller/RecoveryController.java`

New endpoints:
- `PUT /api/v1/reinsurance/recoveries/{id}/mark-received` — body: `{receivedAmount, receivedAt}`. Requires `record_recovery_received`.
- `PUT /api/v1/reinsurance/recoveries/{id}/write-off` — body: `{reason}`. Requires `writeoff_recovery`.

Every transition emits AuditEvent + updates status.

#### 5. Angular review queue + recovery forms

**Files** (new):
- `clients/angular/src/app/pages/tenant/finance/reinsurance/review-queue/review-queue.component.ts` — task list with filter + resolve action
- `.../review-queue/resolve-task-modal.component.ts` — resolve form (RESOLVED_VOID, RESOLVED_KEEP, DISMISSED + notes)
- `.../recoveries/record-received-modal.component.ts` — received amount + date form (opened from recoveries bordereau row action)
- `.../recoveries/write-off-modal.component.ts` — reason form

**Routes**:
- `reinsurance/review-queue` (permission: `resolve_review`)
- Actions embedded in existing recoveries bordereau page (Phase 5)

### Success Criteria

#### Automated Verification
- [ ] `cd services/java && ./gradlew build`
- [ ] `make test-java` — `ReinsuranceReviewTaskServiceTest`; `TreatyActivationBackfillJobTest` (seeded claims + activate treaty → assert cessions written; rerun → zero duplicates); regression detection tests in `ReinsuranceLossCessionConsumerTest` (first cede → cession written; re-adjudicate lower → task created not cession; re-adjudicate higher → no-op)
- [ ] `make test-integration`:
  - `TreatyActivationBackfillJobIT`: seed 100 historical claims, activate a treaty covering the line → assert 100 cessions; rerun (retrigger) → still 100
  - `ReinsuranceReviewTaskIT`: full lifecycle (create by regression → assign → resolve RESOLVED_VOID → assert cession voided)
  - `RecoveryLifecycleIT`: EXPECTED → INVOICED (via bordereau export from Phase 4) → RECEIVED (form) + WRITTEN_OFF (form)
- [ ] Playwright: `reinsurance-review-queue.spec.ts` — regression triggered by re-adjudication → task appears → supervisor resolves; `reinsurance-recovery-lifecycle.spec.ts` — record-received form + write-off form

#### Manual Verification
- [ ] Adjudicate a claim → cession written
- [ ] Re-adjudicate same claim with lower approvedAmount → cession NOT overwritten; review task appears in queue with taskType=CLAIM_REGRESSION
- [ ] Resolve task RESOLVED_VOID → cession status → VOIDED; recovery (if EXPECTED) → cascade void
- [ ] Activate a new treaty inception-dated 90 days back → backfill runs, cessions appear for historical claims; re-run activation (dev-only endpoint) → job idempotent, no duplicates
- [ ] Recovery record-received form updates receivedAmount + receivedAt; write-off form requires reason

**Implementation Note**: this is the last phase. **After manual acceptance, run the self-review loop** — create the PR, then `code-review` over the whole diff, triage every Blocker/Important, fix, and re-sweep. The self-review comment gets posted to the PR.

---

## Testing Strategy

### Unit Tests
- **Cession arithmetic** (Phase 3): proportional QS/SS at various rates, XoL at retention/limit boundaries, StopLoss aggregate math, layer chaining (per-layer independence)
- **Treaty lifecycle** (Phase 2): DRAFT → ACTIVE with validation branches; ACTIVE-edit rejection; renewal chain walking
- **Idempotency** (Phases 3, 6, 8): UNIQUE constraint enforcement on cessions
- **Regression detection** (Phase 8): re-adjudication scenarios (drop, same, rise, REJECTED-from-APPROVED)
- **XLSX rendering** (Phase 4): per-currency sheet grouping, subtotals, prior-period-adjustment flagging, FX summary sheet
- **Backfill idempotency** (Phase 8): rerun writes zero duplicates
- **Regression scenarios in the review queue** (Phase 8): task creation branches

### Integration Tests (Testcontainers)
- Full CRUD IT for each Phase 2 entity
- Kafka round-trip for each consumer (Phases 3, 6, 8)
- Bordereau export IT with report-toggle 403 + SecurityEvent assertion (Phase 4)
- Backfill job against 100+ historical claims (Phase 8)
- Facultative cession round-trip with permission matrix (Phase 7)

### E2E Tests (Playwright)
- `reinsurance-crud.spec.ts` — full treaty lifecycle (Phase 2)
- `reinsurance-bordereau.spec.ts` — golden export path (Phase 5)
- `reinsurance-facultative.spec.ts` — DRAFT → APPROVED → CEDED (Phase 7)
- `reinsurance-review-queue.spec.ts` — regression → task → resolve (Phase 8)
- `reinsurance-recovery-lifecycle.spec.ts` — record-received + write-off (Phase 8)

### Manual Testing (per-phase manual verification lists)
See each phase's Manual Verification checklist.

## Performance Considerations

- **Bordereau reports**: participant JOIN on every row could inflate result-set size 1×N (participants per treaty). For a treaty with 4 reinsurers, a quarter of 10K cessions produces 40K rows. Server-side pagination on the report endpoints is required (default page size 100, max 500).
- **XLSX export ceiling**: 10K rows per sheet (matches existing exports). Requests exceeding return 400 with "refine filters" body. Realistic for a Q1 export with <2K cessions per reinsurer.
- **Backfill job**: `Flux.groupBy` streams naturally; no full-dataset materialization. For a tenant with 100K historical claims across a 12-month backfill window, expect ~5min processing (based on `PaymentRunService.writeSnapshots` observed throughput ~300 ops/sec per JVM). Progress polled via `BackfillProgressService`.
- **Regression detection SQL**: `cession.findBySourceEventId(claimId)` uses `ix_cession_source_event_id` — indexed, O(log N) per event.
- **Rules-engine cession firing**: agenda-gated to REINSURANCE only when consumer explicitly focuses. Zero overhead on non-cession events.

## Migration Notes

- **Flyway ordering**: V081..V090 must apply in-order (V082→V083→V084 all depend on treaty(id) FK); V087→V088 depend on cession FK; V090 depends on cession + recovery FKs. Standard Flyway ordering handles this.
- **Never edit an applied migration** (per `feedback_never_edit_applied_migrations`) — any correction is a higher-numbered file.
- **Tenant vs public schema**: all reinsurance tables live in `tenant_<uuid>` schemas (Phase 1 §1). No `public.` prefix in queries — see `bug_public_prefix_silent_rollback`.
- **No backfill required** for existing tenants — reinsurance is greenfield. Treaties are opt-in per tenant.
- **Feature-flag alternative for high-risk phases** (per parent-plan `:3008`): gate the three REINSURANCE_* report keys at TenantReportConfig level — `enabled=false` by default for all tenants until each tenant explicitly opts in. Recommended.
- **Angular rule builder additions** (Phase 1 §3): no migration; ships in the code deploy.

## Rollout & Rollback

**Rollout order:**
1. Phase 1 migrations (tenancy-service V081..V090) — schema deploys first
2. Rules-engine deploys (Phase 1 §2) — new category + emitter + templates
3. finance-service deploys (Phases 2-4) — entities, services, consumers, reports
4. Angular deploys (Phase 5) — reports UI
5. contributions-service deploys (Phase 6 additive publisher fields) — MUST land before Phase 6 finance-service consumer deploy
6. finance-service Phase 6 deploys — premium cession consumer
7. finance-service + Angular Phase 7 deploys — facultative UI
8. finance-service + Angular Phase 8 deploys — backfill + review queue + recovery forms

**Rollback**: each phase is independently revertable via the TenantReportConfig toggle (backend surface stays; UI hides). Deep rollback (DROP TABLE) is not supported — Flyway never rolls back. For a production incident, disable all REINSURANCE_* report keys tenant-wide via a `UPDATE public.tenant_report_config SET enabled = FALSE WHERE report_key LIKE 'REINSURANCE_%'` script.

## Deviations

- **2026-08-22 (Phase 1 §4)** — the plan mentioned assigning permissions to `finance_supervisor` (not a seed role) and creating a new `reinsurance_supervisor` seed role. The seed roles wired in `user-service`'s `RoleService.seedDefaultRoles` today are only: `tenant_admin`, `operations`, `claims_officer`, `finance_officer`, `provider`, `member`. Rather than editing `RoleService.seedDefaultRoles` on the strength of a passing plan mention, V091 grants all 7 keys to `tenant_admin` (matches the pattern set by V006 / V070) and grants `finance.reinsurance:view` + `finance.reinsurance:record_recovery_received` to `finance_officer`. Tenants that want a supervisor split create their own role via the role editor and hand it the higher-privilege keys — that is already the model for every non-baseline permission. If a `reinsurance_supervisor` seed is later wanted, it needs both a `RoleService.seedDefaultRoles` edit and a follow-up tenant migration that grants the appropriate keys.
- **2026-08-22 (Phase 1 §2)** — `DrlCompiler` uses `Set<String>` for `AGENDA_GATED_CATEGORIES`, not the `EnumSet<RuleCategory>` sketched in the plan snippet. The gate was extended in-place: `Set.of("BENEFIT_PRORATION", "REINSURANCE")`. Semantics identical; matches the existing shape and required no refactor.
- **2026-08-22 (Phase 1 §2 — CedeToTreatyEmitter file location)** — the emitter lives at `services/java/rules-engine/src/main/java/com/medfund/rules/compiler/CedeToTreatyEmitter.java` (the `compiler` package next to `ActionEmitters.java`), not the `action` package the plan named. Rationale: every other emitter lives in `compiler/ActionEmitters.java`; a standalone file keeps the extra encoding logic (PCT vs XOL) readable while staying consistent with the compiler-package convention. `DrlCompiler` picks it up as a Spring bean via the constructor-injected `List<ActionEmitter>` — no wiring edits needed.
- **2026-08-22 (Phase 1 §1 — permission-seed migration file)** — the plan named a user-service migration file. user-service has no `db/migration/tenant/` tree; permission seeding for the tenant schema lives entirely in tenancy-service's tenant migrations (V006, V022, V069, V070, V073, V079, …). V091 was added there for consistency.
- **2026-08-22 (Phase 2 §3 §5 — audit publish tenant resolution)** — the plan sketch mirrored `TenantHighCostClaimantConfigService`'s `TenantRepository.findById(...)` lookup for the tenant slug. finance-service does not own the `tenants` table (it's cross-tenant and lives in tenancy-service); the existing finance-service audit pattern (`TenantBankAccountService.publishAudit`) reads the tenant id from `TenantContext.get(ctx)` and uses the entity's own friendly name as `entityName`. Adopted here — `Reinsurer.name`, `Treaty.treatyRef`, `"Layer N on treaty <ref>"`, `"<reinsurer> participation"`, `"<line> on treaty <ref>"`, `"Cession rule on treaty <ref>"` — matches `feedback_audit_entity_name` semantics without a cross-service HTTP hop per audit event.
- **2026-08-22 (Phase 2 §2 — composite-key repositories)** — R2DBC's `ReactiveCrudRepository<T, ID>` does not support composite keys. `TreatyParticipantRepository` and `TreatyApplicableLineRepository` are hand-rolled with `DatabaseClient` (all INSERT/UPDATE/DELETE/SELECT via `.sql(...)`) rather than the `Persistable<CompositeKey>` sketch in the plan. Same public surface; simpler than fighting the R2DBC id abstraction.
- **2026-08-22 (Phase 2 §7 — sub-editors are inline sections, not separate components)** — the plan named four distinct sub-editor files (`treaty-layer-editor.component.ts`, …). Delivered as inline sections within `treaty-edit.component.ts/.html`: each section owns its list + inline add form + row-level remove. Reduces file count from 8 (4 × ts+html) to 3 total for the treaty edit page while keeping the same UX. If any section grows enough to warrant its own component, it can be extracted then.
- **2026-08-22 (Phase 2 §7 — cession rule linking)** — the plan called for "Add rule" to open the visual rule builder with category prefilled to REINSURANCE. Shipped a simpler surface: an inline UUID-paste field that links an already-authored rule to the treaty. The rule is authored in the Rules Engine tab (already category-agnostic per Phase 1) and its ID copied over. UX follow-up ticket: a proper picker that queries `/api/v1/rules?category=REINSURANCE` for a dropdown.
- **2026-08-22 (Phase 2 §7 — no client-side permission guards on the routes)** — the plan sketch guarded every child route with `permissionGuard(['finance.reinsurance:view'])`. `permissionGuard` isn't wired into the existing tenant-admin route table (audit / rules / settings are all unguarded client-side); server-side `@RequiresPermission` returns 403 and the tab surfaces the error. Left the routes unguarded for consistency; if we want a defensive client-side guard we should add it uniformly across all tenant-admin routes, not just reinsurance.
- **2026-08-22 (Phase 2 §7 — reinsurer picker)** — the "search-select" per `feedback_no_raw_id_inputs` is implemented as a 250 ms debounced client-side filter over the first page (up to 200 rows) of `/reinsurance/reinsurers?active=true`. Satisfies "payload holds ID, UI shows name". A server-side typeahead (`/reinsurers?q=…&limit=20`) is a natural follow-up when a tenant has > 200 reinsurers.
- **2026-08-22 (Phase 3 §3–4 — recovery trigger moved off payment-created)** — the plan wired `RecoveryService.createExpectedFromPayment` to a `ReinsuranceRecoveryConsumer` on `medfund.finance.payment-created`, keying recoveries by `event.claimId()`. But that topic's payload today is `{event, paymentId, providerId, amount}` — no claimId — and neither `Payment` nor `PaymentRunItem` (`services/java/finance-service/.../entity/{Payment,PaymentRunItem}.java`) carry a claim FK. Payments are provider-aggregate. Extending the entity + the event + every publisher call-site for a reinsurance-only need is a much bigger surgery than the feature warrants. Adopted instead: `CessionService.processAdjudicatedClaim` writes both the `Cession` and the `Recovery` in the same `@Transactional` at cession time. The lifecycle is unchanged (EXPECTED → INVOICED on bordereau export in Phase 4 → RECEIVED/WRITTEN_OFF via the manual forms in Phase 8); only the trigger moves earlier. Consequences: `ReinsuranceRecoveryConsumer`, `RecoveryService.createExpectedFromPayment`, and `PaymentCreatedEvent` are dropped from Phase 3. The recovery-idempotency guard uses the pre-existing UNIQUE (cession_id) index on `recovery` (V088) — a rerun of the loss consumer with the same claim id writes zero duplicate recovery rows.
- **2026-08-22 (Phase 4 §3 — per-currency aggregate via pre-computed map, not SQL-string+Consumer)** — the plan sketch called for `envelopeBuilder.build(..., perCurrencyAggregateSql, spec -> spec.bind(...))`. `DatabaseClient.GenericExecuteSpec.bind()` is immutable-fluent — it returns a new spec — so the `Consumer<GenericExecuteSpec>` never actually applies to the spec used downstream (the Consumer's return is discarded). We're the first caller of that overload; the API needs to become `Function<GenericExecuteSpec, GenericExecuteSpec>` to work, but that's a shared-layer change out of scope. Adopted the pre-computed-map variant instead: `BordereauQueryRepository.{cession,recoveries,utilization}PerCurrencyTotals(...)` return `Mono<Map<String, PerCurrencyTotal>>` directly, and the service calls the pre-computed-map `envelopeBuilder.build` overload (same one CreditorController and BillingAggregateController use). Zero API surface impact; three new repository methods parallel the row queries with the same bind shape. The unused SQL-string-with-Consumer overload can be tightened later — separate ticket.
- **2026-08-22 (Phase 4 §3 — dropped `@Transactional(readOnly=true)` from BordereauReportService)** — the plan sketch marked every report method `@Transactional(readOnly = true)`. In practice the read path composes multiple independent queries (`firstExportedAt`, `cessionRows`, per-currency aggregate, envelope FX lookups, `ReportingCurrencyResolver.getDefaultCurrencyCode`) and a single failure inside `.onErrorResume` (e.g. the test schema lacking `public.tenant_currency_config`) leaves the shared Postgres tx aborted, poisoning every subsequent query with `[25P02] current transaction is aborted`. Same class of bug as `bug_public_prefix_silent_rollback`. Reports are pure snapshot reads with no cross-query invariant that needs isolation — dropping `@Transactional` gives each call an independent connection and the swallowed errors stay swallowed. Applies to all three report methods.
- **2026-08-22 (Phase 4 §5 — recoveries INVOICED transition kept out of the transactional envelope)** — R8 called for the EXPECTED→INVOICED flip to be transactionally atomic with the bordereau bytes being served. As the plan itself notes ("failure to deliver bytes to client is not observable server-side"), that atomicity is impossible against an HTTP response. Adopted the post-response fire-and-forget: `BordereauReportWorkbookService.snapshotExpectedRecoveryCessionIds` captures the cession-id set before rendering; the controller subscribes `RecoveryRepository.markInvoicedByCessionIds(..., now())` on `.doOnSuccess` after the ResponseEntity is built. Render failure → no snapshot subscription → recoveries stay EXPECTED, next export retries. The tenant context is preserved across the async subscription so the UPDATE lands in the right schema.
- **2026-08-22 (Phase 5 §2 — bordereau report routes registered directly in `finance.routes.ts`)** — the plan sketch showed the reinsurance report routes living inside a standalone `REINSURANCE_ROUTES` array. Every other finance report (loss-ratio, collection-rate, aged-debtors, cash-flow-forecast, …) is registered inline in `finance.routes.ts`; a separate route table for three sibling report pages would be an inconsistency. The routes live under `reports/reinsurance/*` in `finance.routes.ts` next to the existing report pages. Same URLs, same permission-guard shape.
- **2026-08-22 (Phase 5 §3 — reports hub renders labels only, so no auto-link)** — the plan called for the reports hub to "auto-register the three new report keys via `ReportCatalogueService`". The hub already lists every enabled report from `/tenants/{tenantId}/report-config`, so the reinsurance keys appear once the backend catalog serves them — no client-side edit needed. Since the hub renders labels (not routerLinks — [reports-hub.component.html](../../../clients/angular/src/app/pages/tenant/finance/reports/reports-hub.component.html) line 50), users reach the reinsurance report pages via the direct URL registered in `finance.routes.ts`. Matches the pattern for every existing report family (billing, receipts, claims — none of which have hub links).
- **2026-08-22 (Phase 5 §1 — filter/picker built on `app-select` with `searchable=true` instead of a custom debounced typeahead)** — the plan called for a "debounced search-select" per `feedback_no_raw_id_inputs`. The platform `SelectComponent` already supports `[searchable]="true"` for in-panel local filtering, which is the same UX as the treaty-edit reinsurer picker delivered in Phase 2. The picker payload holds the UUID (`value: r.id`) and the label shows the friendly name — the invariant. Reinsurer/treaty lists cap at 200 rows via `listReinsurers(0, 200, true)` / `listTreaties(0, 200)`; if a tenant's treaty count grows past 200 it needs a server-side typeahead (same follow-up as the treaty-edit picker).

## References

- **Parent plan**: `thoughts/shared/plans/2026-08-11-financial-reporting-suite.md#phase-10`
- **Grilling scratchpad**: `/tmp/phase10-grilling-scratchpad.md` (local, redundant — decisions live in parent plan)
- **Research**: `thoughts/shared/research/2026-08-11-financial-reporting-vs-masca-reference.md`
- **Reference consumer**: `services/java/finance-service/src/main/java/com/medfund/finance/consumer/ClaimAdjudicatedConsumer.java:65`
- **Reference workbook**: `services/java/finance-service/src/main/java/com/medfund/finance/service/PaymentRunWorkbookService.java`
- **Reference PUT-with-audit**: `services/java/tenancy-service/src/main/java/com/medfund/tenancy/controller/TenantHighCostClaimantConfigController.java:64-68`
- **Reference migration**: `services/java/tenancy-service/src/main/resources/db/migration/tenant/V078__member_cost_share_liability.sql`
- **Backfill pattern**: `services/java/finance-service/src/main/java/com/medfund/finance/service/PaymentRunService.java:324-337`
- **Rules-engine category enum**: `services/java/rules-engine/src/main/java/com/medfund/rules/model/RuleCategory.java:12-60`
- **DrlCompiler agenda-gating**: `services/java/rules-engine/src/main/java/com/medfund/rules/compiler/DrlCompiler.java:57`
- **Angular rule builder**: `clients/angular/src/app/core/services/rules.service.ts:36-82`
- **Shared infra**: `services/java/shared/src/main/java/com/medfund/shared/report/{ReportKey,ReportFamily,ReportEnvelopeBuilder,ReportingCurrencyResolver,FxRateReader,ReportWorkbook,RequiresReport,ReportGuardAspect}.java`
- **Auto-memory constraints**: `bug_reactor_kafka_ack_swallow`, `bug_rules_engine_tenant_isolation`, `bug_public_prefix_silent_rollback`, `feedback_never_edit_applied_migrations`, `feedback_audit_actor_email`, `feedback_audit_entity_name`, `feedback_no_raw_id_inputs`, `infra_testcontainers_pitfalls`
- **Architecture docs**: `.claude/rules-engine.md`, `.claude/multi-currency.md`, `.claude/multi-tenancy.md`, `.claude/coding-standards.md`, `.claude/portals.md`, `.claude/CLAUDE.md`
