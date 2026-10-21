---
date: 2026-09-05
git_commit: 15a1c25d993f7c765dcf628530b39e163da2289c
branch: rename-adjustments-to-notes
ticket: thoughts/shared/plans/2026-08-11-financial-reporting-suite.md   # parent-plan Phase 18 grilled section
research: none — grill covered it via Explore subagents; findings live at thoughts/shared/notes/2026-09-05-phase18-executive-kpi-grill.md
steer: "Do not use any masca reference but we need all the reports in the masca reports fully implemented and excel exports. If possible research the web and check other possible insurance reports. The tenant should be able to turn on and off the reports that they need in the tenant settings." (parent-plan steer, unchanged)
services_touched: [shared, contributions-service, claims-service, finance-service, angular]
status: draft
parent_phase: 18
parent_plan: thoughts/shared/plans/2026-08-11-financial-reporting-suite.md
grilled_decisions: K1..K18 (see parent-plan Phase 18 § Decisions Log lines 4466-4562)
---

# Executive KPI Dashboards Implementation Plan

## Overview

Implement the 5 executive KPI tiles the parent-plan Phase 18 grill settled: `LOSS_RATIO_KPI` (incurred / earned per K2/K3), `EXPENSE_RATIO` (commission-only, UI-labelled "Acquisition Ratio" per K4/K5), `COMBINED_RATIO` (mixed-basis sum per K6), `CLAIMS_FREQUENCY` (claim count / policy-months-in-force per K1), `AVERAGE_SEVERITY` (paid / claim count per K1). Each tile carries a composite scalar in the tenant's reporting currency, a per-currency native breakdown, a 12-month sparkline, and a click-through to the underlying detail report. Backend adds three new aggregate endpoints (K7/K8/K9), a `KpiComposerService` that fans out to them via `CrossServiceCallHelper`, and 5 `ScheduledReportShapeAdapter` implementations so Phase 17's scheduled-email path can deliver monthly board packs.

## Current State Analysis

- **ReportKey enum has the 3 outlined dashboard keys but at wrong cadence.** `services/java/shared/src/main/java/com/medfund/shared/report/ReportKey.java:136-139` defines `COMBINED_RATIO`, `LOSS_RATIO_KPI`, `EXPENSE_RATIO` as `ReportFamily.DASHBOARD, cadenced=false, periodShape=null`. K11 flips these 5 (with 2 new keys) to `cadenced=true, PREVIOUS_COMPLETE_PERIOD`.
- **`CLAIMS_FREQUENCY_SEVERITY` already exists as a Phase 4 detail report** at `ReportKey.java:72` (`ReportFamily.CLAIMS_FINANCIAL, cadenced=false, null`) and is wired at `services/java/claims-service/src/main/java/com/medfund/claims/controller/ClaimsReportController.java:573-609` + `ClaimsFrequencySeverityIT.java`. This is the drill-through target for the new `CLAIMS_FREQUENCY` and `AVERAGE_SEVERITY` KPI tiles per K15.
- **Phase 5 loss-ratio cross-service composer** at `services/java/finance-service/src/main/java/com/medfund/finance/controller/CrossServiceReportController.java:73-137` shows the canonical envelope shape for cross-service composers: hand-built (not via `ReportEnvelopeBuilder`) so peer-warnings from `CrossServiceCallHelper` are preserved (G37 / invariant #7). Adapter for scheduled dispatch lives at `services/java/finance-service/src/main/java/com/medfund/finance/report/schedule/adapter/LossRatioAdapter.java` — one of 13 sibling adapters at that package. Uses `@RequiresPermission(Permissions.FINANCE_VIEW_SUBLEDGER)`.
- **`ClaimsAggregateController` exists** at `services/java/claims-service/src/main/java/com/medfund/claims/controller/ClaimsAggregateController.java:50-89` returning per-(dimension, currency, claimed, approved, paid) via `adjudicated_at`. **No reserve-movement or IBNR columns.** Reserve history lives at `services/java/tenancy-service/src/main/resources/db/migration/tenant/V139__claim_reserve_history.sql:7-20`.
- **Contributions has no `Aggregate` controller** — only `PremiumReportController.java` (six routes: upr-movement/register/new-business + XLSX exports) and `EarningScheduleController.java`. `BillingAggregateController` sits in the non-premium package. K8 adds a new `PremiumAggregateController` mirroring `BillingAggregateController` shape. Earning-schedule closed rows populate `earning_at_period_end` via nightly `PremiumEarningExecutor` (`services/java/contributions-service/src/main/java/com/medfund/contributions/premium/scheduler/PremiumEarningExecutor.java:20-31`).
- **Finance producer package has no aggregate controller** — only `CommissionReportController` (statement + clawback-register), `ProducerController`, `CommissionAdjustmentController`, etc. K7 adds `CommissionAggregateController` under `services/java/finance-service/src/main/java/com/medfund/finance/producer/controller/`. Ledger source is `commission_transaction` with `status='PAID'` + `paid_at` populated on run execution (`services/java/finance-service/src/main/java/com/medfund/finance/producer/entity/CommissionTransaction.java:38-100`; internal repo method `CommissionTransactionRepository.aggregateForPayout()` at `services/java/finance-service/src/main/java/com/medfund/finance/producer/repository/CommissionTransactionRepository.java:62-79`).
- **Phase 14 IBNR result lands in `report_job.result_json`** as scalar `ibnr_total` + array `per_cohort_ultimate` (`services/python/ai-service/app/actuarial/chain_ladder.py:54-67`); on-demand run only, no schedule; retention OPERATIONAL_90D (Phase 15 §14).
- **Phase 17 orchestrator + adapter package structure**: `services/java/finance-service/src/main/java/com/medfund/finance/report/schedule/{ScheduledReportProbe,ScheduledReportOrchestrator,ScheduledReportShapeAdapter}.java` + 13 adapters under `adapter/*Adapter.java` (LossRatio, CommissionStatement, CashFlowForecast13w, etc.). Adapters return `Mono<byte[]>` from `render(ScheduledFireContext)`.
- **Angular chart primitives** live under `clients/angular/src/app/shared/components/charts/` — `app-line-chart`, `app-area-chart`, `app-bar-chart`, `app-grouped-bar-chart`, `app-pie-chart`, `app-waterfall-chart`. Used by Phase 8 cash-flow-forecast + Phase 9 platform-analytics. **No `app-sparkline`** — Phase 18 §A adds it (K15).
- **Angular reports hub** at `clients/angular/src/app/pages/tenant/finance/reports/` has 20+ family folders (actuarial, aged-debtors, balance-history, billing, cash-flow-forecast, claims, collection-rate, commission, compliance, ifrs17, loss-ratio, policy-lifecycle, underwriting, etc.). **No `kpi/` folder** — Phase 18 §A adds it (K16). `finance.routes.ts` binds each folder to a lazy-loaded route.
- **Permission constant naming**: `services/java/shared/src/main/java/com/medfund/shared/security/Permissions.java` defines `FINANCE_VIEW = "finance:view"` (base) + `FINANCE_VIEW_SUBLEDGER = "finance:view_subledger"` (Phase 5 cross-service composer gate). K17's aspirational `finance:reports:view` string doesn't exist as a constant — sub-plan uses `FINANCE_VIEW_SUBLEDGER` per Phase 5 precedent to match the cross-service composer role.

## Desired End State

A finance-officer with `FINANCE_VIEW_SUBLEDGER` permission logs in, opens `/tenant/finance/reports`, clicks the new `Executive KPIs` card in the Dashboard family, and lands at `/tenant/finance/reports/kpi`. Five tiles render (or fewer if some KPI keys are toggled off in `/tenant/admin/settings/reports`), each showing:
- The KPI label + basis-note info-icon (COMBINED_RATIO carries the mixed-basis tooltip per K6).
- Composite ratio scalar in the tenant's default reporting currency (or overridden per K12).
- A 12-month sparkline via new `app-sparkline` component (extendable to 24 months via dropdown per K14).
- Per-currency native ratios in a compact row.
- Period footer + trend arrow.

Three filter chips (line-of-business + scheme + producer per K13) refine every tile in-place. Clicking a tile navigates to the corresponding detail report per K15's map. A tenant-admin at `/tenant/admin/settings/reports` can toggle any KPI on/off; disabling cascades to Phase 17's scheduled deliveries per S9. A tenant-admin at `/tenant/admin/settings/report-schedules` (Phase 17) can schedule any of the 5 KPIs for monthly board-pack email delivery (K11 whitelist widening); the schedule fires via Phase 17's probe → orchestrator → dispatcher path, rendering the same XLSX the tile's "Export XLSX" button produces.

### Verification

```bash
# Backend
cd services/java && ./gradlew :shared:build :contributions-service:build :claims-service:build :finance-service:build
make test-java
make test-integration                                       # Testcontainers

# Frontend
make test-angular
make test-e2e                                               # kpi-dashboard.spec.ts + kpi-scheduled-email.spec.ts

# Manual acceptance
make infra && make tenancy user contributions finance claims gateway notification web
# Log in as finance officer → /tenant/finance/reports/kpi → 5 tiles render
# Toggle EXPENSE_RATIO off in admin → tile disappears + tenant-admin schedule modal warns of 1 scheduled delivery affected
# Schedule LOSS_RATIO_KPI for monthly delivery → wait for probe fire → verify email at mailpit
```

### Key Discoveries

- **`CLAIMS_FREQUENCY_SEVERITY` reuse pattern** — K1 answered "add two new keys `CLAIMS_FREQUENCY` + `AVERAGE_SEVERITY`". The existing `CLAIMS_FREQUENCY_SEVERITY` (Phase 4) is a detail report; the two new keys are DASHBOARD tiles. Both new tiles drill into the existing Phase 4 report per K15. **No collision.**
- **Cross-service composer envelope must be hand-built** — `CrossServiceReportController.java:83-99` doesn't use `ReportEnvelopeBuilder` because the builder's best-effort FX pass overwrites peer-warnings from `CrossServiceCallHelper`. `KpiComposerService` follows the same pattern.
- **`CrossServiceCallHelper` is the peer-fanout primitive** — Phase 3+5+8 all use it with `.timeout(2s) + .retry(1) + .onErrorResume(...)` + envelope `warnings` capture per invariant #7.
- **`FxRateReader.convert` fails loud; `.findRate` is best-effort** — per G28. Composite scalar (K12) uses `.convert`; per-currency envelope (K12) uses `.findRate`.
- **Redis is on the finance-service classpath already** — Phase 8/9 use it for cache. Existing beans + config pattern followed in K10.
- **Small-denominator threshold `1000` per K13** — hardcoded as `KpiComposerService.SMALL_DENOMINATOR_THRESHOLD`; a future Phase 18.5 makes it tenant-configurable.
- **Chart-animations-off is mandatory** — parent-plan Phase 8 §2a proved that dev-mode `[animations]="true"` degrades cash-flow-forecast to 7-36s per interaction. `app-sparkline` ships with `[animations]="false"` from day one.

## What We're NOT Doing

Explicit non-goals for this sub-plan:

- **Solvency ratio / ROE / retention KPIs** — K1 deferred these to Phase 18.5 (need accounting ledger + capital-model service that don't exist).
- **Full operating-expense ratio** (acquisition + admin + investment) — K4 deferred to Phase 18.5 alongside a general-ledger integration; v1 is commission-only, UI-labelled "Acquisition Ratio".
- **Acquisition-vs-servicing commission classifier** — K7 deferred; v1 sums all PAID commission transactions.
- **`kpi_snapshot` materialized-warm-path table** — K10 deferred; v1 uses on-demand + Redis cache.
- **Per-tenant configurable IFRS-17-vs-NAIC combined-ratio basis toggle** — K5/K6 deferred; v1 uses NAIC (expense on written, loss on earned).
- **Additive-basis combined ratio widget** — K6 deferred; v1 sums the two ratios with a mixed-basis footnote.
- **Weekly / daily granularity** — K14 fixed granularity at MONTHLY only.
- **Dedicated `/tenant/executive` portal** — K16 rejected; KPI dashboard lives under `/tenant/finance/reports/kpi`.
- **Per-tenant configurable small-denominator noise threshold** — K13 deferred; v1 hardcodes `1000`.
- **Regulator-vs-KPI parity check** (does our loss ratio match what IPEC/CMS sees) — separate concern; Phase 16 owns regulatory templates.
- **`kpi-dashboard` embedded on `/tenant/admin/home`** — K16 rejected.
- **`.claude/kpi.md` architecture doc** — Phase 18 "Owed back" bullet flagged as optional; this sub-plan doesn't include it (a future doc-hygiene pass can).
- **Auto-poll refresh** — K11 rejected 60s polling; page fetches on load + filter-change only.

## Implementation Approach

Bottom-up: enum flip first (§0/Phase 1), then the three peer aggregate endpoints (§0/Phase 2/3/4 in parallel), then the composer that fans out to them (§A/Phase 5), then trend (§A/Phase 6), then Angular (§A/Phase 7), then the Phase 17 scheduled-delivery wiring (§B/Phase 8). Each aggregate endpoint is independently verifiable via IT; the composer degrades gracefully when a peer is missing (envelope warnings per invariant #7), so Phase 5 can land against 2/3 peers if one aggregate slips.

**No Flyway migrations** — the three new aggregate endpoints are SELECT-only over existing tables (Phase 11 `commission_transaction`, Phase 12 `earning_schedule`, Phase 14 `claim_reserve_history`, Phase 15 `report_job`). Verified against the tenancy tenant migration set which ended at V168 as of 2026-09-05. Sub-plan §0 explicitly calls this out so a reviewer looking for a migration knows it's absent by design (F18-8).

**Kafka contracts** — no new topics. Phase 8 reuses Phase 17's `medfund.notification.report-delivery` + `-failed` via new adapter registrations. No producer-consumer ordering concern.

**Rollout order across the 8 phases**: Phase 1 first (shared enum add). Phases 2/3/4 can land in any order (independent services). Phase 5 depends on all three. Phase 6 extends 5. Phase 7 needs 5+6 for meaningful UI. Phase 8 needs 7 (same XLSX renderer for scheduled attachment). Deploy order for prod = build order.

---

## Phase 1: `ReportKey` enum flip + 2 new keys

### Overview

Add `CLAIMS_FREQUENCY` and `AVERAGE_SEVERITY` to `ReportKey` in the DASHBOARD family (K1). Flip all 5 KPI keys (`COMBINED_RATIO`, `LOSS_RATIO_KPI`, `EXPENSE_RATIO`, `CLAIMS_FREQUENCY`, `AVERAGE_SEVERITY`) to `cadenced=true, ReportPeriodShape.PREVIOUS_COMPLETE_PERIOD` (K11). This unlocks Phase 17's scheduled-email UI whitelist widening in Phase 8.

### Changes Required

#### 1. Extend the ReportKey enum

**File**: `services/java/shared/src/main/java/com/medfund/shared/report/ReportKey.java`

**Changes**: Rewrite lines 136-139 from 3 non-cadenced entries to 5 cadenced entries.

```java
// ── Executive KPI (Phase 18) ─────────────────────────────────────────────
// K11: all 5 keys are cadenced=true, PREVIOUS_COMPLETE_PERIOD so Phase 17
// scheduled-email delivery can send monthly board packs. K1: CLAIMS_FREQUENCY
// and AVERAGE_SEVERITY are the two additions; they are distinct from the
// Phase 4 CLAIMS_FREQUENCY_SEVERITY detail report (which stays in
// ReportFamily.CLAIMS_FINANCIAL and is the drill-through target per K15).
COMBINED_RATIO              ("Combined ratio",                          ReportFamily.DASHBOARD,        true,  ReportPeriodShape.PREVIOUS_COMPLETE_PERIOD),
LOSS_RATIO_KPI              ("Loss ratio (KPI)",                        ReportFamily.DASHBOARD,        true,  ReportPeriodShape.PREVIOUS_COMPLETE_PERIOD),
EXPENSE_RATIO               ("Acquisition ratio",                       ReportFamily.DASHBOARD,        true,  ReportPeriodShape.PREVIOUS_COMPLETE_PERIOD),
CLAIMS_FREQUENCY            ("Claims frequency (KPI)",                  ReportFamily.DASHBOARD,        true,  ReportPeriodShape.PREVIOUS_COMPLETE_PERIOD),
AVERAGE_SEVERITY            ("Average severity (KPI)",                  ReportFamily.DASHBOARD,        true,  ReportPeriodShape.PREVIOUS_COMPLETE_PERIOD),
```

Note the K4 label change: `EXPENSE_RATIO` displays as `"Acquisition ratio"` (the code key stays `EXPENSE_RATIO` — never rename an enum value per the class Javadoc line 11-12).

#### 2. Update the ReportKey test

**File**: `services/java/shared/src/test/java/com/medfund/shared/report/ReportKeyTest.java`

**Changes**: Extend the cadenced-invariant test to cover the 5 new/flipped keys. Every `cadenced=true` value must have non-null `periodShape` (the existing test in the file already enforces this globally; new keys inherit).

Add a specific assertion group:

```java
@Test
void dashboardKpiKeys_areCadencedPreviousCompletePeriod() {
    Set<ReportKey> kpiKeys = Set.of(
            ReportKey.COMBINED_RATIO,
            ReportKey.LOSS_RATIO_KPI,
            ReportKey.EXPENSE_RATIO,
            ReportKey.CLAIMS_FREQUENCY,
            ReportKey.AVERAGE_SEVERITY);
    for (ReportKey key : kpiKeys) {
        assertThat(key.getFamily()).isEqualTo(ReportFamily.DASHBOARD);
        assertThat(key.isCadenced()).isTrue();
        assertThat(key.getPeriodShape()).isEqualTo(ReportPeriodShape.PREVIOUS_COMPLETE_PERIOD);
    }
}

@Test
void expenseRatioKey_labelReadsAsAcquisitionRatio() {
    // K4: display label ≠ enum name; execs should never read "expense ratio"
    // when what we compute is commission-only.
    assertThat(ReportKey.EXPENSE_RATIO.getLabel()).isEqualTo("Acquisition ratio");
}
```

#### 3. Angular `ReportKey` type mirror

**File**: `clients/angular/src/app/core/services/report-catalogue.service.ts`

**Changes**: If the file has a hard-coded list of report keys (typical Phase-0-pattern), add the two new entries and update the 3 label + cadence flags. If it fetches from `/api/v1/report-catalogue`, no change needed (the backend enum is source of truth). Verify shape at implement time.

### Success Criteria

#### Automated Verification

- [x] `cd services/java && ./gradlew :shared:build` compiles clean (jacoco 70% gate is pre-existing at 62% on HEAD — unrelated to this phase)
- [x] `cd services/java && ./gradlew :shared:test` — new `ReportKeyTest.dashboardKpiKeys_areCadencedPreviousCompletePeriod` + `expenseRatioKey_labelReadsAsAcquisitionRatio` + `claimsFrequencyDetailReportKey_stillExistsSeparately` pass; existing global-invariant tests (every cadenced key has periodShape) still pass
- [x] `cd services/java && ./gradlew build` — dependent services (contributions/claims/finance/tenancy/user) recompile without break since the enum is additive + one label change
- [ ] `make test-angular` — no regression if `report-catalogue.service.ts` fetches from backend; new/updated entries render in `/tenant/admin/settings/reports` grid without console errors
- [ ] `verify` on `/tenant/admin/settings/reports` — grid shows 5 KPI entries under a `Dashboard` group; each toggle togglable; label reads "Acquisition ratio" for EXPENSE_RATIO

#### Manual Verification

- [ ] Toggle any KPI key off → `tenant_report_config` row created with `enabled=FALSE` (verify via `SELECT * FROM public.tenant_report_config WHERE report_key IN ('COMBINED_RATIO','LOSS_RATIO_KPI','EXPENSE_RATIO','CLAIMS_FREQUENCY','AVERAGE_SEVERITY')`) — no cascade fires yet since no schedule rows exist for these keys (Phase 8 wires the whitelist)

**Implementation Note**: Pause for human confirmation before Phase 2 kicks off. This is small but the label change is user-visible.

---

## Phase 2: `contributions-service` `/aggregate/premium-earned`

### Overview

New lean aggregate endpoint on contributions-service returning per-(currency, scheme, insurance_line) earned-premium totals for a period, sourced from Phase 12's `earning_schedule.earned_at_period_end` closed rows. Powers the LOSS_RATIO_KPI denominator (K3/K8).

### Changes Required

#### 1. New DTO

**File**: `services/java/contributions-service/src/main/java/com/medfund/contributions/premium/dto/PremiumEarnedAggregateRow.java`

```java
package com.medfund.contributions.premium.dto;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One row of the /aggregate/premium-earned feed — a group-by result from
 * SUM(earning_schedule.earned_at_period_end) over the requested period. Rows
 * stay native-currency (never converted) per parent-plan G25; the KPI
 * composer performs any reporting-currency conversion downstream via
 * FxRateReader.convert. schemeId / schemeName are nullable when the query
 * does not group by scheme (dimension = TENANT or LINE).
 */
public record PremiumEarnedAggregateRow(
        UUID schemeId,
        String schemeName,
        String insuranceLine,      // 'HEALTH', 'LIFE', ... (nullable when dimension excludes line)
        String currencyCode,       // ISO-4217, always present
        BigDecimal earnedPremium,  // sum in native currency
        long rowCount              // count of contributing earning_schedule rows
) {}
```

#### 2. New query repository

**File**: `services/java/contributions-service/src/main/java/com/medfund/contributions/premium/repository/PremiumAggregateQueryRepository.java`

```java
package com.medfund.contributions.premium.repository;

import com.medfund.contributions.premium.dto.PremiumEarnedAggregateRow;
import lombok.RequiredArgsConstructor;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;

import java.time.LocalDate;
import java.util.UUID;

/**
 * K8 aggregate query. Uses SUM over earning_schedule.earned_at_period_end for
 * fully-closed periods (period_end < today and earned_at_period_end IS NOT
 * NULL). Nightly PremiumEarningExecutor guarantees earned_at_period_end is
 * populated on closure — periods that haven't closed contribute 0 rows and
 * the KPI page displays a "reflects fully-closed periods only" warning.
 *
 * <p>Dimension enum drives the GROUP BY: TENANT groups by currency only;
 * LINE adds insurance_line; SCHEME adds a policy-enrichment JOIN mirroring
 * PremiumReportQueryRepository:211-249's seven-way UNION-ALL discriminator
 * unpacking on (policy_id, policy_source).
 */
@Repository
@RequiredArgsConstructor
public class PremiumAggregateQueryRepository {

    private final DatabaseClient databaseClient;

    public enum Dimension { TENANT, LINE, SCHEME }

    public Flux<PremiumEarnedAggregateRow> earnedPremium(
            LocalDate periodStart,
            LocalDate periodEnd,
            Dimension dimension,
            String insuranceLineFilter,       // nullable
            UUID schemeIdFilter) {            // nullable

        String selectSchemeCols = dimension == Dimension.SCHEME
                ? ", pe.scheme_id AS scheme_id, s.name AS scheme_name"
                : ", CAST(NULL AS UUID) AS scheme_id, CAST(NULL AS VARCHAR) AS scheme_name";
        String selectLineCol = dimension != Dimension.TENANT
                ? ", es.insurance_line AS insurance_line"
                : ", CAST(NULL AS VARCHAR) AS insurance_line";
        String groupBy = switch (dimension) {
            case TENANT -> "GROUP BY es.currency_code";
            case LINE   -> "GROUP BY es.currency_code, es.insurance_line";
            case SCHEME -> "GROUP BY es.currency_code, es.insurance_line, pe.scheme_id, s.name";
        };
        String policyEnrichmentJoin = dimension == Dimension.SCHEME
                ? """
                   LEFT JOIN (
                       -- 7-way UNION-ALL policy enrichment matching
                       -- PremiumReportQueryRepository:211-249 to unpack
                       -- (policy_id, policy_source) into scheme_id.
                       %s
                   ) pe ON pe.policy_id = es.policy_id AND pe.policy_source = es.policy_source
                   LEFT JOIN schemes s ON s.id = pe.scheme_id
                   """.formatted(POLICY_ENRICHMENT_CTE)
                : "";

        StringBuilder sql = new StringBuilder("""
                SELECT es.currency_code AS currency_code
                     , SUM(es.earned_at_period_end) AS earned_premium
                     , COUNT(*)                     AS row_count
                """).append(selectSchemeCols).append(selectLineCol)
                .append("\nFROM earning_schedule es\n")
                .append(policyEnrichmentJoin)
                .append("""
                        WHERE es.period_end >= :periodStart
                          AND es.period_end <  :periodEnd
                          AND es.earned_at_period_end IS NOT NULL
                        """);
        if (insuranceLineFilter != null) {
            sql.append("  AND es.insurance_line = :insuranceLine\n");
        }
        if (schemeIdFilter != null && dimension == Dimension.SCHEME) {
            sql.append("  AND pe.scheme_id = :schemeId\n");
        }
        sql.append(groupBy);

        DatabaseClient.GenericExecuteSpec spec = databaseClient.sql(sql.toString())
                .bind("periodStart", periodStart)
                .bind("periodEnd",   periodEnd);
        if (insuranceLineFilter != null) spec = spec.bind("insuranceLine", insuranceLineFilter);
        if (schemeIdFilter != null && dimension == Dimension.SCHEME) spec = spec.bind("schemeId", schemeIdFilter);

        return spec.map((row, meta) -> new PremiumEarnedAggregateRow(
                        row.get("scheme_id", UUID.class),
                        row.get("scheme_name", String.class),
                        row.get("insurance_line", String.class),
                        row.get("currency_code", String.class),
                        row.get("earned_premium", java.math.BigDecimal.class),
                        row.get("row_count", Long.class)))
                .all();
    }

    // Same 7-way UNION-ALL that PremiumReportQueryRepository.java:211-249
    // uses to unpack (policy_id, policy_source) — extracted here as a
    // constant so both queries share the shape. Implementer to lift into
    // a shared package if a third consumer appears.
    private static final String POLICY_ENRICHMENT_CTE = """
        SELECT id AS policy_id, 'LIFE_POLICY'      AS policy_source, scheme_id FROM life_policies
        UNION ALL
        SELECT id AS policy_id, 'FUNERAL_POLICY'   AS policy_source, scheme_id FROM funeral_policies
        UNION ALL
        SELECT id AS policy_id, 'DISABILITY_POLICY' AS policy_source, scheme_id FROM disability_policies
        UNION ALL
        SELECT id AS policy_id, 'TRAVEL_POLICY'    AS policy_source, scheme_id FROM travel_policies
        UNION ALL
        SELECT id AS policy_id, 'VEHICLE_POLICY'   AS policy_source, scheme_id FROM vehicle_policies
        UNION ALL
        SELECT id AS policy_id, 'PROPERTY_POLICY'  AS policy_source, scheme_id FROM property_policies
        UNION ALL
        SELECT c.id AS policy_id, 'CONTRIBUTION'   AS policy_source, m.scheme_id
          FROM contributions c JOIN members m ON m.id = c.member_id
        """;
}
```

#### 3. New service

**File**: `services/java/contributions-service/src/main/java/com/medfund/contributions/premium/service/PremiumAggregateService.java`

```java
package com.medfund.contributions.premium.service;

import com.medfund.contributions.premium.dto.PremiumEarnedAggregateRow;
import com.medfund.contributions.premium.repository.PremiumAggregateQueryRepository;
import com.medfund.contributions.premium.repository.PremiumAggregateQueryRepository.Dimension;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class PremiumAggregateService {

    private final PremiumAggregateQueryRepository queryRepository;

    public Mono<List<PremiumEarnedAggregateRow>> earnedPremium(
            LocalDate periodStart,
            LocalDate periodEnd,
            Dimension dimension,
            String insuranceLine,
            UUID schemeId) {
        return queryRepository.earnedPremium(periodStart, periodEnd, dimension, insuranceLine, schemeId)
                .collectList()
                .doOnNext(rows -> log.debug("earned-premium aggregate: {} rows for {}..{} dim={}",
                        rows.size(), periodStart, periodEnd, dimension));
    }
}
```

#### 4. New controller

**File**: `services/java/contributions-service/src/main/java/com/medfund/contributions/premium/controller/PremiumAggregateController.java`

```java
package com.medfund.contributions.premium.controller;

import com.medfund.contributions.premium.dto.PremiumEarnedAggregateRow;
import com.medfund.contributions.premium.repository.PremiumAggregateQueryRepository.Dimension;
import com.medfund.contributions.premium.service.PremiumAggregateService;
import com.medfund.shared.security.Permissions;
import com.medfund.shared.security.RequiresPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Phase 18 (K8): earned-premium aggregate feed for the Executive KPI
 * composer in finance-service. Ungated by @RequiresReport per parent-plan
 * invariant #2 exception for cross-service data feeds (mirrors
 * BillingAggregateController + ClaimsAggregateController). Toggle enforcement
 * lives on the consumer side (finance-service ExecutiveKpiController).
 */
@RestController
@RequestMapping("/api/v1/reports/aggregate")
@RequiredArgsConstructor
@Tag(name = "Premium aggregates",
        description = "Cross-service aggregate feeds for downstream composers. Earned-premium "
                    + "sums come from Phase-12 earning_schedule closed rows.")
@SecurityRequirement(name = "bearer-jwt")
public class PremiumAggregateController {

    private final PremiumAggregateService service;

    @GetMapping("/premium-earned")
    @RequiresPermission(Permissions.CONTRIBUTIONS_READ_AGGREGATE)
    @Operation(summary = "Earned premium per (currency[, line][, scheme]) for a period",
            description = "SUM(earning_schedule.earned_at_period_end) for fully-closed periods "
                        + "in [periodStart, periodEnd). Native per-currency; no conversion. "
                        + "Nightly PremiumEarningExecutor guarantees closed periods are populated — "
                        + "unclosed periods contribute zero rows.")
    public Mono<List<PremiumEarnedAggregateRow>> earnedPremium(
            @RequestParam String periodStart,
            @RequestParam String periodEnd,
            @RequestParam(defaultValue = "TENANT") Dimension dimension,
            @RequestParam(required = false) String insuranceLine,
            @RequestParam(required = false) UUID schemeId) {
        return service.earnedPremium(
                LocalDate.parse(periodStart),
                LocalDate.parse(periodEnd),
                dimension,
                insuranceLine,
                schemeId);
    }
}
```

Add a matching `Permissions.CONTRIBUTIONS_READ_AGGREGATE` constant to `services/java/shared/src/main/java/com/medfund/shared/security/Permissions.java` if not present (Phase 2/3/4 all need this; naming aligned with `FINANCE_VIEW_SUBLEDGER`):

```java
public static final String CONTRIBUTIONS_READ_AGGREGATE = "contributions:read_aggregate";
public static final String CLAIMS_READ_AGGREGATE        = "claims:read_aggregate";
```

Grant these to the `finance-service` M2M service-account role in Keycloak realm config (out-of-band or via `infra/keycloak/realm-config.json` if that's how the repo bootstraps).

#### 5. Unit + IT tests

**File**: `services/java/contributions-service/src/test/java/com/medfund/contributions/premium/controller/PremiumAggregateControllerTest.java`

WebFluxTest slice covering: default dimension = TENANT, LINE grouping, SCHEME grouping, insuranceLine filter, schemeId filter, empty result when period has no closed rows.

**File**: `services/java/contributions-service/src/test/java/com/medfund/contributions/premium/integration/PremiumAggregateIT.java`

Extends `AbstractIntegrationTest` (Postgres + Kafka Testcontainers). Seeds 2-currency (USD + ZWL) × 2-line (HEALTH + LIFE) earning_schedule fixture with closed periods overlapping the query window; asserts group-by shapes.

`infra_testcontainers_pitfalls` guards apply (Testcontainers 1.21.4 BOM override, flyway-database-postgresql, stub ReactiveJwtDecoder).

### Success Criteria

#### Automated Verification

- [x] `cd services/java && ./gradlew :contributions-service:build` compiles clean
- [x] `cd services/java && ./gradlew :contributions-service:test --tests PremiumAggregateControllerTest` — 6 cases pass (TENANT default, LINE grouping, SCHEME grouping, schemeId filter, empty result, 400 on missing periodStart)
- [x] `./gradlew :contributions-service:test --tests PremiumAggregateIT` — 6 cases pass (TENANT groups by currency only, LINE adds insurance_line, LINE filter, SCHEME resolves scheme_id+name via 7-way policy_enrichment CTE, SCHEME filter, empty-period returns no rows)
- [ ] Swagger renders new endpoint at `http://localhost:8084/swagger-ui`
- [ ] `curl` from another service (using the M2M token pattern) returns the expected shape

#### Manual Verification

- [ ] For a tenant with 3 months of closed earning-schedule data, `?periodStart=2026-06-01&periodEnd=2026-09-01&dimension=TENANT` returns rows summing to the expected billed premium × earning-fraction

**Implementation Note**: Pause for confirmation before Phase 3.

---

## Phase 3: `claims-service` `/aggregate/claims-incurred`

### Deviations

- **2026-09-05 — Period clock is `claims.adjudicated_at`, not `c.paid_at`.** The plan SQL originally named `c.paid_at` for the window filter and `claim_details cd.paid_amount` for the sum. Neither exists on the tenant schema:
  - `claims` has `paid_amount` as a direct column (foreign-written by finance-service's payment-run flow — see `ClaimsReportQueryRepository.FUNNEL` line 54-58).
  - There is no `claim_details` table.
  - Every existing cross-service claims aggregate (`aggregate()` / `aggregateMonthly()`) uses `adjudicated_at` as its period clock via `ClaimsReportQueryRepository.CLAIMS_PERIOD`.
  Adapting the SQL to `WHERE c.adjudicated_at >= :periodStart AND c.adjudicated_at < :periodEnd` and `SUM(c.paid_amount)` preserves the K9 design (paid + reserve movement) while matching what the schema actually looks like. Called out in the controller Javadoc and repository class Javadoc.

### Overview

New aggregate endpoint on `ClaimsAggregateController` (existing controller — extend) returning per-(currency, scheme) paid + reserve-movement totals for a period. Powers the LOSS_RATIO_KPI numerator sub-total (K2/K9). IBNR is added downstream by the KPI composer from `report_job.result_json`.

### Changes Required

#### 1. New DTO

**File**: `services/java/claims-service/src/main/java/com/medfund/claims/dto/ClaimsIncurredAggregateRow.java`

```java
package com.medfund.claims.dto;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Phase 18 (K9) aggregate row. reserveMovement = reserveBalanceEnd -
 * reserveBalanceStart per period boundary. subtotalIncurredExIbnr =
 * totalPaid + reserveMovement — the "case-reserve basis" incurred figure.
 * IBNR is added downstream by KpiComposerService.
 */
public record ClaimsIncurredAggregateRow(
        UUID schemeId,
        String schemeName,
        String insuranceLine,       // nullable when dimension excludes line
        String currencyCode,        // ISO-4217
        BigDecimal totalPaid,
        BigDecimal reserveBalanceStart,
        BigDecimal reserveBalanceEnd,
        BigDecimal reserveMovement,           // end - start
        BigDecimal subtotalIncurredExIbnr,    // totalPaid + reserveMovement
        long claimCount                       // for CLAIMS_FREQUENCY / AVERAGE_SEVERITY
) {}
```

Note the `claimCount` column — it powers both `CLAIMS_FREQUENCY` (count / policy-months) and `AVERAGE_SEVERITY` (paid / count). One endpoint feeds three KPIs.

#### 2. Extend the query repository

**File**: `services/java/claims-service/src/main/java/com/medfund/claims/repository/ClaimsAggregateQueryRepository.java`

Add a method `claimsIncurred(periodStart, periodEnd, dimension, insuranceLine, schemeId)`. The reserve-balance-at-date subquery is the load-bearing bit — use `DISTINCT ON (claim_id) ... ORDER BY claim_id, effective_at DESC` for each period boundary:

```java
public Flux<ClaimsIncurredAggregateRow> claimsIncurred(
        LocalDate periodStart,
        LocalDate periodEnd,
        Dimension dimension,
        String insuranceLineFilter,
        UUID schemeIdFilter) {

    // Reserve balance at time T = SUM over (latest reserved_amount per
    // claim_id where effective_at <= T). PostgreSQL DISTINCT ON is the
    // idiomatic way to grab the latest row per group.
    String sql = """
            WITH reserve_balance_at_start AS (
                SELECT claim_id, reserved_amount
                  FROM (
                      SELECT DISTINCT ON (claim_id)
                             claim_id, reserved_amount, effective_at
                        FROM claim_reserve_history
                       WHERE effective_at <= :periodStart
                       ORDER BY claim_id, effective_at DESC
                  ) latest
            ),
            reserve_balance_at_end AS (
                SELECT claim_id, reserved_amount
                  FROM (
                      SELECT DISTINCT ON (claim_id)
                             claim_id, reserved_amount, effective_at
                        FROM claim_reserve_history
                       WHERE effective_at < :periodEnd
                       ORDER BY claim_id, effective_at DESC
                  ) latest
            ),
            paid_in_period AS (
                SELECT c.id AS claim_id
                     , c.currency_code
                     , c.insurance_line
                     , c.scheme_id
                     , COALESCE(SUM(cd.paid_amount), 0) AS paid_amount
                  FROM claims c
                  LEFT JOIN claim_details cd ON cd.claim_id = c.id
                 WHERE c.paid_at >= :periodStart
                   AND c.paid_at <  :periodEnd
                 GROUP BY c.id, c.currency_code, c.insurance_line, c.scheme_id
            )
            SELECT p.currency_code
                 , p.insurance_line
                 , %s
                 , SUM(p.paid_amount)                                            AS total_paid
                 , COALESCE(SUM(rs.reserved_amount), 0)                          AS reserve_balance_start
                 , COALESCE(SUM(re.reserved_amount), 0)                          AS reserve_balance_end
                 , COALESCE(SUM(re.reserved_amount), 0) - COALESCE(SUM(rs.reserved_amount), 0) AS reserve_movement
                 , SUM(p.paid_amount) + COALESCE(SUM(re.reserved_amount), 0) - COALESCE(SUM(rs.reserved_amount), 0) AS subtotal_incurred_ex_ibnr
                 , COUNT(DISTINCT p.claim_id)                                     AS claim_count
              FROM paid_in_period p
              LEFT JOIN reserve_balance_at_start rs ON rs.claim_id = p.claim_id
              LEFT JOIN reserve_balance_at_end   re ON re.claim_id = p.claim_id
              %s
             %s
            """.formatted(schemeSelectCols, insuranceLineFilterClause, groupByClause);
    // Bindings + row-mapping as per Phase-2 pattern.
    // ...
}
```

Full implementation follows the Phase 2 pattern; abbreviated here for the plan.

#### 3. Add the route to `ClaimsAggregateController`

**File**: `services/java/claims-service/src/main/java/com/medfund/claims/controller/ClaimsAggregateController.java`

Add a new `@GetMapping("/claims-incurred")` method. Extends the existing controller (don't create a new one — the class is already the aggregate hub for claims-service).

```java
@GetMapping("/claims-incurred")
@RequiresPermission(Permissions.CLAIMS_READ_AGGREGATE)
@Operation(summary = "Incurred claims per (currency[, line][, scheme]) — paid + Δreserve for the period",
        description = "K9: totalPaid + (reserveBalanceEnd - reserveBalanceStart). IBNR is NOT included; "
                    + "the KPI composer adds it from the latest report_job IBNR run. "
                    + "reserveBalance(T) uses DISTINCT ON (claim_id) with effective_at <= T on claim_reserve_history.")
public Mono<List<ClaimsIncurredAggregateRow>> claimsIncurred(
        @RequestParam String periodStart,
        @RequestParam String periodEnd,
        @RequestParam(defaultValue = "TENANT") ClaimsAggregateQueryRepository.Dimension dimension,
        @RequestParam(required = false) String insuranceLine,
        @RequestParam(required = false) UUID schemeId) {
    return service.claimsIncurred(
            LocalDate.parse(periodStart),
            LocalDate.parse(periodEnd),
            dimension,
            insuranceLine,
            schemeId);
}
```

#### 4. Extend `ClaimsAggregateService`

Add a `claimsIncurred(...)` method mirroring the existing `claimsAggregate(...)` shape.

#### 5. Unit + IT tests

**File**: `services/java/claims-service/src/test/java/com/medfund/claims/controller/ClaimsAggregateControllerTest.java` (extend existing)

Cases: default dimension, LINE, SCHEME, insuranceLine filter, schemeId filter, zero-reserve claims (paid-only), reserve-without-paid, cross-period reserve movement.

**File**: `services/java/claims-service/src/test/java/com/medfund/claims/integration/ClaimsIncurredIT.java`

Seeds `claims` + `claim_details` + `claim_reserve_history` with 5 claims across 2 currencies + 2 lines + varying reserve trajectories. Asserts reserve-balance-at-boundary correctness (DISTINCT ON semantics) and subtotalIncurredExIbnr arithmetic.

### Success Criteria

#### Automated Verification

- [x] `cd services/java && ./gradlew :claims-service:build` compiles clean
- [x] `./gradlew :claims-service:test --tests ClaimsAggregateControllerTest` — 3 new /claims-incurred cases pass (default TENANT dimension, LINE + insuranceLine filter, SCHEME + schemeId filter, 400 on missing params) plus 3 pre-existing pass
- [x] `./gradlew :claims-service:test --tests ClaimsIncurredIT` — 7 cases pass (TENANT paid+reserveMovement per currency, LINE splitting, insuranceLine filter, SCHEME resolution, schemeId filter, DISTINCT ON latest-before-boundary regression guard, out-of-window claim exclusion)
- [ ] Swagger renders new endpoint at `http://localhost:8083/swagger-ui`
- [ ] Cross-service curl from another JVM (M2M token) returns expected shape

#### Manual Verification

- [ ] For a tenant with 20 known claims (10 paid, 10 outstanding with reserves), `dimension=TENANT` returns `totalPaid` matching the ledger + `reserveMovement` matching the reserve history delta
- [ ] Test with `periodStart = periodEnd` — should return zero rows (empty half-open interval)

**Implementation Note**: Pause for confirmation before Phase 4.

---

## Phase 4: `finance-service` `/aggregate/commissions`

### Overview

New aggregate endpoint on a new `CommissionAggregateController` under `producer/controller/` returning per-(currency, producer, insurance_line) sums of PAID commissions for a period. Powers the EXPENSE_RATIO ("Acquisition Ratio") numerator (K4/K7).

### Changes Required

#### 1. New DTO

**File**: `services/java/finance-service/src/main/java/com/medfund/finance/producer/dto/CommissionAggregateRow.java`

```java
package com.medfund.finance.producer.dto;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Phase 18 (K7) aggregate row. K7 explicitly deferred acquisition-vs-servicing
 * classifier — this row sums all PAID commission regardless of type. UI note
 * on the KPI page: "Includes all paid commission; new-business/trail split
 * in a future release."
 */
public record CommissionAggregateRow(
        UUID producerId,           // nullable when dimension excludes producer
        String producerName,       // nullable when dimension excludes producer
        String insuranceLine,      // nullable when dimension excludes line
        String currencyCode,       // ISO-4217
        BigDecimal totalPaid,      // native amount
        long rowCount              // count of PAID commission_transaction rows
) {}
```

#### 2. Extend the repository

**File**: `services/java/finance-service/src/main/java/com/medfund/finance/producer/repository/CommissionTransactionRepository.java`

Add:

```java
public enum AggregateDimension { TENANT, LINE, PRODUCER, LINE_AND_PRODUCER }

public Flux<CommissionAggregateRow> aggregatePaid(
        LocalDate periodStart,
        LocalDate periodEnd,
        AggregateDimension dimension,
        String insuranceLineFilter,
        UUID producerIdFilter);
```

Implementation follows Phase 2/3 pattern — dynamic `SELECT ... GROUP BY` composed per dimension. LEFT JOIN `producers` when producer_name is needed. Filter clause `status='PAID' AND paid_at >= :start AND paid_at < :end`.

Note the naming: the existing internal method is `aggregateForPayout()` (returns `ProducerCommissionSummary` for the PaymentRunGenerator). Keep it; the new method serves a different consumer (KPI composer) with a different row shape.

#### 3. New service

**File**: `services/java/finance-service/src/main/java/com/medfund/finance/producer/service/CommissionAggregateService.java`

```java
@Slf4j
@Service
@RequiredArgsConstructor
public class CommissionAggregateService {

    private final CommissionTransactionRepository repository;

    public Mono<List<CommissionAggregateRow>> aggregatePaid(
            LocalDate periodStart,
            LocalDate periodEnd,
            AggregateDimension dimension,
            String insuranceLine,
            UUID producerId) {
        return repository.aggregatePaid(periodStart, periodEnd, dimension, insuranceLine, producerId)
                .collectList();
    }
}
```

#### 4. New controller

**File**: `services/java/finance-service/src/main/java/com/medfund/finance/producer/controller/CommissionAggregateController.java`

```java
@RestController
@RequestMapping("/api/v1/reports/aggregate")
@RequiredArgsConstructor
@Tag(name = "Commission aggregates")
@SecurityRequirement(name = "bearer-jwt")
public class CommissionAggregateController {

    private final CommissionAggregateService service;

    @GetMapping("/commissions")
    @RequiresPermission(Permissions.FINANCE_VIEW_SUBLEDGER)
    @Operation(summary = "Paid commission per (currency[, line][, producer]) for a period",
            description = "K7 aggregate. Sums commission_transaction WHERE status='PAID' AND "
                        + "paid_at in [periodStart, periodEnd). Native per-currency; no conversion. "
                        + "Acquisition-vs-servicing classifier deferred to Phase 18.5.")
    public Mono<List<CommissionAggregateRow>> commissions(
            @RequestParam String periodStart,
            @RequestParam String periodEnd,
            @RequestParam(defaultValue = "TENANT") AggregateDimension dimension,
            @RequestParam(required = false) String insuranceLine,
            @RequestParam(required = false) UUID producerId) {
        return service.aggregatePaid(
                LocalDate.parse(periodStart),
                LocalDate.parse(periodEnd),
                dimension,
                insuranceLine,
                producerId);
    }
}
```

Note the permission: `FINANCE_VIEW_SUBLEDGER` (existing constant, used by Phase 5 cross-service reports) — this endpoint is a local composer feed within finance-service so it uses the finance permission not a cross-service `CLAIMS_READ_AGGREGATE`-style constant.

#### 5. Tests

**File**: `services/java/finance-service/src/test/java/com/medfund/finance/producer/controller/CommissionAggregateControllerTest.java`
**File**: `services/java/finance-service/src/test/java/com/medfund/finance/producer/integration/CommissionAggregateIT.java`

IT seeds 10 `commission_transaction` rows across 2 currencies + 2 producers + status mix (PAID + ACCRUED + REVERSED); asserts only PAID rows counted.

### Success Criteria

#### Automated Verification

- [x] `cd services/java && ./gradlew :finance-service:build` compiles clean
- [x] `./gradlew :finance-service:test --tests CommissionAggregateControllerTest` — 5 cases pass (TENANT default, LINE+filter, PRODUCER+producerId, LINE_AND_PRODUCER, 400 on missing params)
- [x] `./gradlew :finance-service:test --tests CommissionAggregateIT` — 6 cases pass (TENANT excludes non-PAID + out-of-window, LINE grouping, LINE filter, PRODUCER resolves id+name, producerId filter, LINE_AND_PRODUCER groups across both)
- [ ] Swagger renders new endpoint at `http://localhost:8085/swagger-ui`

#### Manual Verification

- [ ] For a tenant with a completed payment-run, the aggregate returns paid commission matching the payment-run's commission section

**Implementation Note**: Phases 2/3/4 are peers. If landing serially, pause between each. If parallelizing across dev sessions, they can each land independently — the composer (Phase 5) fails gracefully with envelope warnings if a peer is missing.

---

## Phase 5: `KpiComposerService` + `ExecutiveKpiController` + Redis cache + IBNR lookup

### Deviations

- **2026-09-05 — IBNR lookup is inline-only; no `ReportJobPayloadStore` MinIO fallback.** The plan named a `ReportJobPayloadStore` with an `s3://payload_ref` fallback path. Two facts on the ground: (a) the `ReportJob` entity has no `payload_ref` column (`services/java/finance-service/src/main/java/com/medfund/finance/report/entity/ReportJob.java` — verified 2026-09-05); (b) no `ReportJobPayloadStore` bean exists (the `MinIOPayloadStore` in `shared/kafka/` handles oversize Kafka payloads for a different use case). Chain-ladder IBNR results are small (a few floats) and fit inline in `result_json`. `IbnrLookupService` reads only inline; if a future actuarial method produces oversize IBNR the migration adds the column and the MinIO branch is easy to add. Recorded in the `IbnrLookupService` Javadoc.
- **2026-09-05 — Reused existing `ReportJobRepository.findFirstByTenantIdAndReportKeyAndStatusAndCompletedAtGreaterThanEqualOrderByCompletedAtDesc` rather than a new `findLatestCompletedInWindow`.** The Phase-15 method already gives us "latest completed at or after floor" — I compute `floor = periodEnd.minusDays(90)` and get the exact 90-day-window semantics without a new repository method. Live dashboard use makes an upper bound moot (`periodEnd` is always ≤ now).
- **2026-09-05 — Extended existing `ContributionsClient` + `ClaimsClient` instead of creating parallel `PremiumEarnedClient` / `ClaimsIncurredClient`.** The two files already implement the tenant-header + Jackson-decode + `Mono.deferContextual` pattern for the Phase-3/5 aggregate hops. Adding two methods there keeps the pattern in one place; the plan's `PremiumEarnedClient` / `ClaimsIncurredClient` would have been near-duplicates. `CommissionAggregateClient` is not created either — finance-local, so the composer injects `CommissionAggregateService` directly (no HTTP hop needed).
- **2026-09-05 — Composer resilient to Redis cache errors.** The `cachedOrCompute` path wraps both `.get(...)` and `.set(...)` in `onErrorResume` so a Redis outage falls through to compute and returns the result rather than 500ing the whole dashboard. Doubles as the "run ITs without a Redis container" enabler.
- **2026-09-05 — EXPENSE_RATIO insuranceLine filter ignored on the billing denominator.** `BillingAggregateRow` doesn't carry an `insuranceLine` column (only `schemeId`, `schemeName`, `currencyCode`, `totalBilled`). When the K13 `insuranceLine` filter is set on an EXPENSE_RATIO call, the composer appends a warning noting the denominator sums across all lines. A per-line billing aggregate is the natural Phase-18.5 follow-up.
- **2026-09-05 — Batch `/dashboard` endpoint enforces the `@RequiresReport` gate imperatively via `ReportEnablementReader.isEnabled(tenantId, key)` (not the annotation).** Reason: Spring AOP does not intercept self-invocation, so `composer.dashboard()` calling `composer.lossRatio()` would bypass the annotation aspect. Explicit check fires 403 if any of the 5 KPIs is disabled — matches K17 intent.

### Overview

The composer heart of Phase 18. New service in finance-service that:
- Resolves reporting currency + accepts K13's 3 filter chips
- Fans out to 4 peer aggregates via `CrossServiceCallHelper` per invariant #7 (envelope warnings on peer failure)
- Reads latest IBNR from `report_job.result_json` per K9
- Assembles per-currency native ratios + reporting-currency composite scalar per K12
- Caches results in Redis 15-min TTL per K10
- Exposes 5 individual endpoints + 1 batch endpoint

### Changes Required

#### 1. New DTOs

**File**: `services/java/finance-service/src/main/java/com/medfund/finance/kpi/dto/KpiValue.java`

```java
package com.medfund.finance.kpi.dto;

import java.math.BigDecimal;

/** K12: per-currency native ratio + numerator + denominator. */
public record KpiValue(
        BigDecimal ratio,          // dimensionless, up to 4 decimal places
        BigDecimal numerator,      // native amount
        BigDecimal denominator,    // native amount
        String currencyCode        // native currency, "COMPOSITE" for the reporting-currency scalar
) {}
```

**File**: `services/java/finance-service/src/main/java/com/medfund/finance/kpi/dto/KpiReportData.java`

```java
package com.medfund.finance.kpi.dto;

import java.math.BigDecimal;

/** K6/K12: composite scalar in reporting currency + basis note. */
public record KpiReportData(
        BigDecimal compositeRatio,        // numerator/denominator each converted to reportingCurrency, then divided
        BigDecimal compositeNumerator,    // in reportingCurrency
        BigDecimal compositeDenominator,  // in reportingCurrency
        String basisNote                  // K6: "MIXED_LOSS_EARNED_EXPENSE_WRITTEN" for COMBINED_RATIO; null otherwise
) {}
```

**File**: `services/java/finance-service/src/main/java/com/medfund/finance/kpi/dto/KpiDashboardResponse.java`

```java
package com.medfund.finance.kpi.dto;

import com.medfund.shared.report.ReportResponse;

import java.util.Map;

/** Batch endpoint response: envelopes keyed by ReportKey.name(). */
public record KpiDashboardResponse(
        Map<String, ReportResponse<KpiReportData>> tiles
) {}
```

#### 2. Peer clients

**File**: `services/java/finance-service/src/main/java/com/medfund/finance/kpi/client/PremiumEarnedClient.java`

WebClient wrapper for `contributions-service`'s `/aggregate/premium-earned`. Uses `CrossServiceCallHelper` for timeout + retry + warning-capture.

```java
@Slf4j
@Component
@RequiredArgsConstructor
public class PremiumEarnedClient {

    private final WebClient contributionsWebClient;   // configured with contributions base URL + M2M
    private final CrossServiceCallHelper crossServiceHelper;

    public Mono<List<PremiumEarnedAggregateRow>> earnedPremium(
            LocalDate periodStart, LocalDate periodEnd,
            Dimension dimension, String insuranceLine, UUID schemeId,
            List<String> warnings) {
        String uri = UriComponentsBuilder.fromPath("/api/v1/reports/aggregate/premium-earned")
                .queryParam("periodStart", periodStart)
                .queryParam("periodEnd",   periodEnd)
                .queryParam("dimension",   dimension.name())
                .queryParamIfPresent("insuranceLine", Optional.ofNullable(insuranceLine))
                .queryParamIfPresent("schemeId", Optional.ofNullable(schemeId))
                .toUriString();
        return crossServiceHelper.getList(contributionsWebClient, uri, PremiumEarnedAggregateRow.class,
                "contributions-service /aggregate/premium-earned", warnings);
    }
}
```

Mirror this for `ClaimsIncurredClient` + `CommissionAggregateClient` + `BillingAggregateClient` (reuse existing billing client if present from Phase 3/5).

#### 3. IBNR reader

**File**: `services/java/finance-service/src/main/java/com/medfund/finance/kpi/service/IbnrLookupService.java`

```java
@Slf4j
@Service
@RequiredArgsConstructor
public class IbnrLookupService {

    private final ReportJobRepository reportJobRepository;
    private final ReportJobPayloadStore payloadStore;    // Phase 15 helper for MinIO fallback
    private final ObjectMapper objectMapper;

    /**
     * K9: fetch the latest committed IBNR total for a period. Returns
     * empty when no completed run exists in the 90-day window ending at
     * periodEnd — the caller (KpiComposerService) surfaces a warning.
     */
    public Mono<Optional<BigDecimal>> latestIbnrTotal(
            UUID tenantId,
            LocalDate periodEnd,
            String insuranceLineFilter,
            List<String> warnings) {
        return reportJobRepository.findLatestCompletedInWindow(
                        tenantId,
                        ReportKey.IBNR_TRIANGLE.name(),
                        periodEnd.minusDays(90),
                        periodEnd)
                .flatMap(job -> parseIbnrTotal(job, insuranceLineFilter))
                .map(Optional::of)
                .defaultIfEmpty(Optional.empty())
                .doOnNext(opt -> {
                    if (opt.isEmpty()) {
                        String line = insuranceLineFilter != null ? insuranceLineFilter : "all lines";
                        warnings.add("IBNR run pending or older than 90 days for (line=" + line
                                + ", asOf=" + periodEnd + ") — displaying paid + Δreserve only");
                    }
                });
    }

    private Mono<BigDecimal> parseIbnrTotal(ReportJob job, String insuranceLineFilter) {
        // Handle both result_json (inline) and payload_ref (MinIO fallback per Phase 15 §14)
        Mono<JsonNode> payload = job.getResultJson() != null
                ? Mono.fromCallable(() -> objectMapper.readTree(job.getResultJson()))
                : payloadStore.fetch(job.getPayloadRef())
                        .map(bytes -> {
                            try { return objectMapper.readTree(bytes); }
                            catch (IOException e) { throw new UncheckedIOException(e); }
                        });
        return payload.map(root -> {
            if (insuranceLineFilter == null) {
                // Full ibnr_total scalar per chain_ladder.py:54-67
                JsonNode t = root.get("ibnr_total");
                return t != null && !t.isNull() ? new BigDecimal(t.asText()) : BigDecimal.ZERO;
            }
            // per_cohort_ultimate is a list of {insurance_line, ultimate, paid_to_date, ibnr}
            JsonNode arr = root.get("per_cohort_ultimate");
            if (arr == null || !arr.isArray()) return BigDecimal.ZERO;
            BigDecimal sum = BigDecimal.ZERO;
            for (JsonNode row : arr) {
                if (insuranceLineFilter.equalsIgnoreCase(row.path("insurance_line").asText())) {
                    JsonNode ibnr = row.get("ibnr");
                    if (ibnr != null && !ibnr.isNull()) sum = sum.add(new BigDecimal(ibnr.asText()));
                }
            }
            return sum;
        });
    }
}
```

Add `ReportJobRepository.findLatestCompletedInWindow(tenantId, reportKey, windowStart, windowEnd)` if not present (Phase 15 has `findByJobId`; extend).

#### 4. Composer service

**File**: `services/java/finance-service/src/main/java/com/medfund/finance/kpi/service/KpiComposerService.java`

The core class. Roughly 200 lines. Key structure:

```java
@Slf4j
@Service
@RequiredArgsConstructor
public class KpiComposerService {

    private static final BigDecimal SMALL_DENOMINATOR_THRESHOLD = new BigDecimal("1000");
    private static final Duration CACHE_TTL = Duration.ofMinutes(15);

    private final PremiumEarnedClient premiumEarnedClient;
    private final ClaimsIncurredClient claimsIncurredClient;
    private final CommissionAggregateClient commissionAggregateClient;
    private final BillingAggregateClient billingAggregateClient;
    private final IbnrLookupService ibnrLookupService;
    private final FxRateReader fxRateReader;
    private final ReportingCurrencyResolver currencyResolver;
    private final ReactiveRedisTemplate<String, KpiReportData> kpiCache;   // K10
    private final ObjectMapper objectMapper;

    // ── Individual KPI endpoints ──────────────────────────────────────────

    public Mono<ReportResponse<KpiReportData>> lossRatio(KpiRequest req) {
        return cached("LOSS_RATIO_KPI", req, () -> computeLossRatio(req));
    }

    public Mono<ReportResponse<KpiReportData>> expenseRatio(KpiRequest req) {
        return cached("EXPENSE_RATIO", req, () -> computeExpenseRatio(req));
    }

    public Mono<ReportResponse<KpiReportData>> combinedRatio(KpiRequest req) {
        // K6: combined = LR(incurred/earned) + ER(commission/written)
        return Mono.zip(computeLossRatio(req), computeExpenseRatio(req))
                .map(t -> combineWithMixedBasis(t.getT1(), t.getT2(), req))
                .transform(m -> cachedMono("COMBINED_RATIO", req, m));
    }

    public Mono<ReportResponse<KpiReportData>> claimsFrequency(KpiRequest req) { ... }
    public Mono<ReportResponse<KpiReportData>> averageSeverity(KpiRequest req) { ... }

    // ── Batch dashboard endpoint ──────────────────────────────────────────

    public Mono<KpiDashboardResponse> dashboard(KpiRequest req) {
        return Mono.zip(
                lossRatio(req),
                expenseRatio(req),
                combinedRatio(req),
                claimsFrequency(req),
                averageSeverity(req)
        ).map(t -> new KpiDashboardResponse(Map.of(
                "LOSS_RATIO_KPI",   t.getT1(),
                "EXPENSE_RATIO",    t.getT2(),
                "COMBINED_RATIO",   t.getT3(),
                "CLAIMS_FREQUENCY", t.getT4(),
                "AVERAGE_SEVERITY", t.getT5())));
    }

    // ── Compute — the load-bearing arithmetic ─────────────────────────────

    private Mono<ReportResponse<KpiReportData>> computeLossRatio(KpiRequest req) {
        List<String> warnings = new ArrayList<>();
        return Mono.zip(
                currencyResolver.resolve(req.tenantId(), req.reportingCurrency()),
                claimsIncurredClient.claimsIncurred(req.periodStart(), req.periodEnd(),
                        Dimension.LINE, req.insuranceLine(), req.schemeId(), warnings),
                premiumEarnedClient.earnedPremium(req.periodStart(), req.periodEnd(),
                        PremiumDimension.LINE, req.insuranceLine(), req.schemeId(), warnings),
                ibnrLookupService.latestIbnrTotal(req.tenantId(), req.periodEnd(),
                        req.insuranceLine(), warnings)
        ).flatMap(t -> {
            String reportingCurrency = t.getT1();
            List<ClaimsIncurredAggregateRow> incurredRows = t.getT2();
            List<PremiumEarnedAggregateRow>  earnedRows   = t.getT3();
            Optional<BigDecimal>             ibnr         = t.getT4();

            // Per-currency native ratios
            Map<String, KpiValue> perCurrency = new LinkedHashMap<>();
            for (String currency : union(currencies(incurredRows), currencies(earnedRows))) {
                BigDecimal numerator   = sum(incurredRows, r -> r.currencyCode().equals(currency),
                                             ClaimsIncurredAggregateRow::subtotalIncurredExIbnr);
                // IBNR is not per-currency in Phase 14's output — attribute to reporting currency below
                BigDecimal denominator = sum(earnedRows,  r -> r.currencyCode().equals(currency),
                                             PremiumEarnedAggregateRow::earnedPremium);
                perCurrency.put(currency, new KpiValue(
                        safeDivide(numerator, denominator), numerator, denominator, currency));
            }

            // Composite (K12: fail-loud composite via FxRateReader.convert)
            LocalDate asOf = req.periodEnd().minusDays(1);
            BigDecimal compositeNum = convertAndSum(perCurrency, KpiValue::numerator,
                    reportingCurrency, asOf, req.tenantId());
            BigDecimal compositeDen = convertAndSum(perCurrency, KpiValue::denominator,
                    reportingCurrency, asOf, req.tenantId());
            // Add IBNR (assumed already in reporting currency per Phase 14 convention;
            // sub-plan-time verification: check chain_ladder.py output currency contract)
            compositeNum = compositeNum.add(ibnr.orElse(BigDecimal.ZERO));

            if (compositeDen.compareTo(SMALL_DENOMINATOR_THRESHOLD) < 0) {
                warnings.add("Denominator " + compositeDen + " " + reportingCurrency
                        + " below noise threshold — ratio may be unreliable at this slice");
            }

            KpiReportData data = new KpiReportData(
                    safeDivide(compositeNum, compositeDen),
                    compositeNum, compositeDen, null /* basisNote for LOSS_RATIO_KPI = null */);

            return Mono.just(new ReportResponse<>(
                    ReportKey.LOSS_RATIO_KPI.name(),
                    new ReportPeriod(req.periodStart(), req.periodEnd(), PeriodGrain.MONTHLY),
                    reportingCurrency, data, perCurrency,
                    /* fxRates: best-effort populated */ bestEffortFxMap(perCurrency.keySet(), reportingCurrency, asOf, req.tenantId(), warnings),
                    List.copyOf(warnings),
                    OffsetDateTime.now()));
        });
    }

    // computeExpenseRatio, computeCombinedRatio, computeClaimsFrequency,
    // computeAverageSeverity follow the same shape with different peer clients.

    // ── K10 cache helpers ─────────────────────────────────────────────────

    private Mono<ReportResponse<KpiReportData>> cached(
            String reportKey, KpiRequest req, Supplier<Mono<ReportResponse<KpiReportData>>> supplier) {
        String key = cacheKey(reportKey, req);
        return kpiCache.opsForValue().get(key)
                .map(cached -> reconstitute(cached, reportKey, req))
                .switchIfEmpty(supplier.get().flatMap(resp ->
                        kpiCache.opsForValue().set(key, resp.data(), CACHE_TTL).thenReturn(resp)));
    }

    private static String cacheKey(String reportKey, KpiRequest req) {
        return "kpi:" + req.tenantId() + ":" + reportKey + ":"
                + req.periodStart() + ":" + req.periodEnd() + ":"
                + Objects.toString(req.reportingCurrency(), "DEFAULT") + ":"
                + Objects.toString(req.insuranceLine(),    "ALL")     + ":"
                + Objects.toString(req.schemeId(),         "ALL")     + ":"
                + Objects.toString(req.producerId(),       "ALL");
    }

    // ...utility methods: safeDivide (ZERO on denominator=0),
    // convertAndSum (fail-loud FxRateReader.convert on missing rate),
    // bestEffortFxMap (best-effort FxRateReader.findRate),
    // combineWithMixedBasis (K6 sum + basisNote population).
}
```

#### 5. Request DTO

**File**: `services/java/finance-service/src/main/java/com/medfund/finance/kpi/dto/KpiRequest.java`

```java
public record KpiRequest(
        UUID tenantId,
        LocalDate periodStart,
        LocalDate periodEnd,
        String reportingCurrency,   // nullable → tenant default
        String insuranceLine,        // K13 filter chip
        UUID schemeId,               // K13 filter chip
        UUID producerId              // K13 filter chip
) {}
```

#### 6. Controller

**File**: `services/java/finance-service/src/main/java/com/medfund/finance/kpi/controller/ExecutiveKpiController.java`

```java
@RestController
@RequestMapping("/api/v1/reports/kpi")
@RequiredArgsConstructor
@Tag(name = "Executive KPI dashboard",
        description = "K16: individual KPI endpoints + batch dashboard endpoint. "
                    + "Fanning out via CrossServiceCallHelper (invariant #7). "
                    + "K11: cadenced=true; scheduled dispatch via Phase 17.")
@SecurityRequirement(name = "bearer-jwt")
public class ExecutiveKpiController {

    private final KpiComposerService composer;

    @GetMapping("/loss-ratio")
    @RequiresPermission(Permissions.FINANCE_VIEW_SUBLEDGER)
    @RequiresReport(ReportKey.LOSS_RATIO_KPI)
    public Mono<ReportResponse<KpiReportData>> lossRatio(
            @RequestParam String periodStart,
            @RequestParam String periodEnd,
            @RequestParam(required = false) String reportingCurrency,
            @RequestParam(required = false) String insuranceLine,
            @RequestParam(required = false) UUID schemeId,
            @RequestParam(required = false) UUID producerId) {
        return Mono.deferContextual(ctx -> composer.lossRatio(new KpiRequest(
                UUID.fromString(TenantContext.get(ctx)),
                LocalDate.parse(periodStart),
                LocalDate.parse(periodEnd),
                reportingCurrency, insuranceLine, schemeId, producerId)));
    }

    // /expense-ratio, /combined-ratio, /claims-frequency, /average-severity — same shape

    @GetMapping("/dashboard")
    @RequiresPermission(Permissions.FINANCE_VIEW_SUBLEDGER)
    // Batch endpoint: composer 403s the whole payload if any of the 5 keys
    // is @RequiresReport-disabled per K17 recommendation.
    @Operation(summary = "Batch: all 5 KPI envelopes in one round-trip")
    public Mono<KpiDashboardResponse> dashboard(...) {
        // Each individual composer call re-checks the @RequiresReport gate
        // via the shared aspect; if any is disabled, the aspect throws
        // ReportDisabledException which the global handler maps to 403.
        return Mono.deferContextual(ctx -> composer.dashboard(new KpiRequest(...)));
    }
}
```

#### 7. Redis config

**File**: `services/java/finance-service/src/main/java/com/medfund/finance/kpi/config/KpiCacheConfig.java`

Standard Spring reactive Redis config for `ReactiveRedisTemplate<String, KpiReportData>` with Jackson serializer. Reuse existing `ReactiveRedisConnectionFactory` bean.

```java
@Configuration
public class KpiCacheConfig {

    @Bean
    public ReactiveRedisTemplate<String, KpiReportData> kpiCache(
            ReactiveRedisConnectionFactory factory, ObjectMapper mapper) {
        Jackson2JsonRedisSerializer<KpiReportData> valueSerializer =
                new Jackson2JsonRedisSerializer<>(mapper, KpiReportData.class);
        RedisSerializationContext<String, KpiReportData> context =
                RedisSerializationContext.<String, KpiReportData>newSerializationContext(new StringRedisSerializer())
                        .value(valueSerializer)
                        .build();
        return new ReactiveRedisTemplate<>(factory, context);
    }
}
```

#### 8. Tests

**File**: `services/java/finance-service/src/test/java/com/medfund/finance/kpi/service/KpiComposerServiceTest.java`

Mockito-driven; mocks the 4 peer clients + IBNR service + FxRateReader + Redis cache. Covers:
- Loss-ratio happy path: 2 currencies, IBNR present, composite scalar correct
- Loss-ratio with missing IBNR: warning populated, composite excludes IBNR term
- Loss-ratio with missing FX for one currency: composite fails loud (`ReportGenerationException`)
- Expense-ratio, combined-ratio, claims-frequency, average-severity happy paths
- Small-denominator warning triggers at threshold boundary
- Cache hit path bypasses composer
- Cache miss populates then serves

**File**: `services/java/finance-service/src/test/java/com/medfund/finance/kpi/integration/ExecutiveKpiControllerIT.java`

`@AutoConfigureWebTestClient` extending `AbstractIntegrationTest`. Uses MockWebServer for the 4 peer services (contributions + claims + finance-billing + finance-commission are all local so use `@MockBean` for their clients rather than MockWebServer where cleaner). Seeds `report_job` with a completed IBNR row. Hits each of the 5 individual endpoints + batch. Uses `ReportRetrofitAssertions` from shared testFixtures for 403-when-disabled + envelope-shape assertions.

Note per parent Phase 8 §2c precedent: contributions/claims peers are cross-service — use MockWebServer for those (or `@MockBean` on the client). Finance-local (billing + commission) can be spring-context beans.

### Success Criteria

#### Automated Verification

- [x] `cd services/java && ./gradlew :finance-service:compileJava` compiles clean
- [x] `./gradlew :finance-service:test --tests KpiComposerServiceTest` — 13 cases pass (LR single-currency happy path, LR missing IBNR + warning, LR multi-currency FX conversion, ER insuranceLine warning, combined-ratio mixed-basis, claims-frequency dimensionless, average-severity, small-denominator warning, cache hit, cache miss populates + serves, peer failure + envelope warnings, dashboard 403 on disabled key, dashboard all-enabled)
- [x] `./gradlew :finance-service:test --tests ExecutiveKpiControllerTest` — 7 cases pass (route + query-param → KpiRequest mapping for each of the 5 endpoints + batch + 400-on-missing-params)
- [x] `./gradlew :finance-service:test --tests ExecutiveKpiControllerIT` — 8 cases pass (5 endpoints + batch + dashboard 403 gate + claims peer-down warning propagation). MockWebServer stubs on `services.contributions.base-url` + `services.claims.base-url`; finance-local dependencies as `@MockBean` per plan §5 IT guidance
- [ ] Redis integration: cache-hit metric visible on `/actuator/metrics/redis.commands`; second call for same (tenant, key, period, filters) returns identical response without fanning out (verify via peer mock call-count assertion)
- [ ] Swagger renders 5 individual + 1 batch endpoint at `http://localhost:8085/swagger-ui`

#### Manual Verification

- [ ] For a live tenant with real data: `curl -H 'Authorization: Bearer <jwt>' 'http://localhost:8085/api/v1/reports/kpi/loss-ratio?periodStart=2026-06-01&periodEnd=2026-09-01'` returns a shape matching the DTO with `perCurrency` populated
- [ ] Kill contributions-service; hit `/api/v1/reports/kpi/loss-ratio` — response comes back with envelope `warnings` naming the peer failure, `data.compositeRatio` is null or zero, no 500
- [ ] Bring contributions back; second call returns real data (retry succeeded)
- [ ] Toggle `LOSS_RATIO_KPI` off in `/tenant/admin/settings/reports`; curl returns 403

**Implementation Note**: Pause for confirmation before Phase 6.

---

## Phase 6: Trend endpoints per KPI

### Deviations

- **2026-09-05 — Composer skips cache-write when peer-failure warnings are present.** A peer-down compute returns zero-ratio + a `"call failed"` (or `"compute failed"`) warning; caching that entry would serve stale zeros for the whole 15-minute TTL after the peer recovers. `maybeWriteCache` in `KpiComposerService` inspects `warnings` and short-circuits the write when it finds either substring. Small-denominator + IBNR-missing warnings are legitimate steady-state conditions and continue to cache. This is a production improvement, not just a test enabler — it also fixed test-order-sensitivity in the IT (an earlier `claimsPeerDown` run was poisoning `lossRatio_composes` via the shared Redis cache on the dev box).
- **2026-09-05 — IT mocks `ReactiveRedisTemplate<String, KpiReportData>` via `@MockBean`.** The dev box runs Redis at 6380 and IT re-runs left poisoned entries. Since Phase 5's composer test already covers the real cache-hit / cache-miss / peer-failure-no-write paths with mocked Redis, the IT's job is just the HTTP + WebClient + JSON-decode chain — mocking the template gives every IT test a clean cache without spinning up a Redis Testcontainer.
- **2026-09-05 — Trend endpoint gates imperatively via `ReportEnablementReader` in the controller (not `@RequiresReport`).** `@RequiresReport` needs a compile-time enum literal but trend takes the KPI key as a path variable. The controller runs `reportEnablementReader.isEnabled(tenantId, rk)` inside `Mono.deferContextual` and maps disabled → 403. Same pattern as Phase 5's dashboard batch endpoint gate cascade.
- **2026-09-05 — Trend buckets use half-open `[periodStart, periodEnd)` boundaries.** The plan wrote `periodEnd = lastMonthEnd.minusMonths(i).withDayOfMonth(lastMonthEnd.minusMonths(i).lengthOfMonth())` (closed, inclusive last-day-of-month). Every existing aggregate SQL predicate in Phases 2/3/4 uses half-open ranges (`< :periodEnd`), so composer trend buckets stay half-open to reuse those predicates — bucket N is `[anchor.minusMonths(N), anchor.minusMonths(N-1))` where `anchor = today.withDayOfMonth(1)`.

### Overview

Extend `ExecutiveKpiController` + `KpiComposerService` with a trend endpoint per KPI, returning 12 or 24 monthly buckets per K14. Each element is a K10 cache lookup keyed by (tenant, key, periodStart, periodEnd, currencies, filters).

### Changes Required

#### 1. New DTO

**File**: `services/java/finance-service/src/main/java/com/medfund/finance/kpi/dto/KpiTrendPoint.java`

```java
public record KpiTrendPoint(
        LocalDate periodStart,
        LocalDate periodEnd,
        KpiReportData composite,
        Map<String, KpiValue> perCurrency,
        List<String> warnings           // e.g. "IBNR pending for this bucket"
) {}
```

#### 2. Composer method

Add to `KpiComposerService`:

```java
public Mono<List<KpiTrendPoint>> trend(String reportKey, KpiRequest req, int windowMonths) {
    // Compute the 12 (or 24) monthly windows ending at the previous month-end
    List<KpiRequest> buckets = generateMonthlyBuckets(req, windowMonths);

    // Fan out — each bucket is a cache lookup + compute-on-miss
    return Flux.fromIterable(buckets)
            .concatMap(bucketReq -> computeSingleKpi(reportKey, bucketReq)
                    .map(resp -> new KpiTrendPoint(
                            bucketReq.periodStart(), bucketReq.periodEnd(),
                            resp.data(), resp.perCurrency(), resp.warnings())))
            .collectList();
}

private static List<KpiRequest> generateMonthlyBuckets(KpiRequest baseReq, int windowMonths) {
    // Bucket N (N=1..windowMonths, N=1 is most-recent) ends at the
    // previous month-end and starts at that month's 1st.
    LocalDate now = LocalDate.now();
    LocalDate lastMonthEnd = now.withDayOfMonth(1).minusDays(1);
    List<KpiRequest> buckets = new ArrayList<>(windowMonths);
    for (int i = 0; i < windowMonths; i++) {
        LocalDate periodEnd   = lastMonthEnd.minusMonths(i).withDayOfMonth(lastMonthEnd.minusMonths(i).lengthOfMonth());
        LocalDate periodStart = periodEnd.withDayOfMonth(1);
        buckets.add(new KpiRequest(
                baseReq.tenantId(), periodStart, periodEnd,
                baseReq.reportingCurrency(),
                baseReq.insuranceLine(), baseReq.schemeId(), baseReq.producerId()));
    }
    Collections.reverse(buckets);  // oldest first for chart rendering
    return buckets;
}
```

Refactor Phase 5's per-KPI methods so `computeSingleKpi(reportKey, req)` dispatches to the right compute path — a small switch on `reportKey`.

#### 3. Controller endpoint

Add to `ExecutiveKpiController`:

```java
@GetMapping("/{key}/trend")
@RequiresPermission(Permissions.FINANCE_VIEW_SUBLEDGER)
@Operation(summary = "12-24 monthly trend for the given KPI",
        description = "K14: default 12 months rolling; dropdown extends to 24. "
                    + "Each element carries composite + perCurrency + warnings.")
public Mono<List<KpiTrendPoint>> trend(
        @PathVariable String key,
        @RequestParam(defaultValue = "12") int windowMonths,
        @RequestParam(required = false) String reportingCurrency,
        @RequestParam(required = false) String insuranceLine,
        @RequestParam(required = false) UUID schemeId,
        @RequestParam(required = false) UUID producerId) {
    if (windowMonths != 12 && windowMonths != 24) {
        return Mono.error(new IllegalArgumentException("windowMonths must be 12 or 24"));
    }
    ReportKey rk = ReportKey.parse(key).filter(k -> k.getFamily() == ReportFamily.DASHBOARD)
            .orElseThrow(() -> new IllegalArgumentException("Unknown KPI key: " + key));
    // Requires-report gate on trend uses the parsed key
    // (annotation can't take a runtime value; enforce imperatively)
    return Mono.deferContextual(ctx -> composer.trend(
            rk.name(),
            new KpiRequest(UUID.fromString(TenantContext.get(ctx)),
                    LocalDate.now().withDayOfMonth(1).minusMonths(windowMonths),
                    LocalDate.now().withDayOfMonth(1).minusDays(1),
                    reportingCurrency, insuranceLine, schemeId, producerId),
            windowMonths));
}
```

Imperative gate check on `rk.name()` via `ReportEnablementReader.isEnabled(...)` (Phase 0 helper) — mirrors the annotation but at runtime since the key is a path-variable.

#### 4. Tests

Extend `KpiComposerServiceTest` with trend cases (12 buckets, 24 buckets, young-tenant returns empty buckets with warnings, cache warms across buckets). Extend `ExecutiveKpiControllerIT` with a trend-shape assertion.

### Success Criteria

#### Automated Verification

- [x] `./gradlew :finance-service:test --tests KpiComposerServiceTest` — 5 new trend cases pass (12 buckets oldest-first with correct boundaries, 24 buckets, invalid windowMonths errors, non-dashboard key errors, cached-bucket skips fanout). 18 total in the suite.
- [x] `./gradlew :finance-service:test --tests ExecutiveKpiControllerTest` — 6 new trend cases pass (default 12, windowMonths=24, invalid window → 400, unknown key → 400, non-dashboard key → 400, disabled → 403). 13 total.
- [x] `./gradlew :finance-service:test --tests ExecutiveKpiControllerIT` — 3 new trend IT cases pass (12 buckets round-trip, invalid window 400, disabled 403). 11 total (all Phase 5 + Phase 6 cases green).

#### Manual Verification

- [ ] `curl 'http://localhost:8085/api/v1/reports/kpi/LOSS_RATIO_KPI/trend?windowMonths=12'` returns 12 elements ordered oldest→newest
- [ ] `windowMonths=24` returns 24
- [ ] `windowMonths=17` returns 400

**Implementation Note**: Pause for confirmation before Phase 7.

---

## Phase 7: Angular `KpiDashboardComponent` + `app-sparkline` + tile grid + filter chips + drill-through

### Deviations

- **Drill-through route for LOSS_RATIO_KPI is `/tenant/finance/reports/billing-vs-claims`, not `/loss-ratio`.** The actual route wired in `finance.routes.ts:571` for the loss-ratio report is `billing-vs-claims` — the plan's `/loss-ratio` path does not exist. Same for claims frequency + severity: the wired route is `/tenant/finance/reports/claims-frequency-severity` (single kebab segment), not the plan's `claims/frequency-severity`.
- **COMBINED_RATIO tile has no drill target.** The plan suggested it should self-anchor to the KPI page. Instead, `hasDrillTarget` returns false and the tile is rendered non-clickable (no cursor pointer, no keyboard focus). A composite of two different bases (loss on earned premium, expense on written premium) has no meaningful single detail page, and self-navigation is a no-op that costs a router event without visual feedback.
- **Filter chip components (`app-line-select`, `app-scheme-select`, `app-producer-select`) do not exist.** The recon confirmed only a generic `SelectComponent`. Substituted with (a) `SelectComponent` for insurance line (fixed enum) + reporting currency (tenant-configured), and (b) the debounced `searchSchemes`/`searchProducers` inline pattern from `commission-statement.component.ts` for scheme + producer (satisfies memory `feedback_no_raw_id_inputs`).
- **No platform-wide tooltip module.** The basis-note `ⓘ` uses the native `title` attribute, which every browser renders on hover — good enough for a single sentence disclosure. Adding a tooltip library for one tile would inflate the KPI chunk beyond the 200KB target for no user-visible improvement.
- **Sparkline uses ngx-charts' default curve (monotoneX-like), not an explicit d3-shape import.** ngx-charts abstracts curve handling; the codebase's other line charts (`line-chart.component.ts`) never import `d3-shape` either. Skipping the explicit `curve` input keeps the sparkline chunk lean and matches existing conventions.
- **Trend-direction arrow is derived client-side from the first and last non-null trend values with a 1% epsilon.** The composer does not emit a direction signal in the trend response; deriving it in the tile keeps the server pure and lets the UI stay coherent even if the trend has gaps.
- **Sidebar entry uses `reportKey: 'COMBINED_RATIO'`.** The batch endpoint 403s only when every KPI is disabled, but the sidebar's per-entry gate is single-key. Stamping COMBINED_RATIO means the "Executive KPIs" nav item hides only when combined ratio specifically is off — an acceptable proxy given that a tenant disabling combined-ratio has effectively opted out of the batch view.
- **`REPORT_ROUTES` in `reports-hub.component.ts` maps all 5 KPI keys to the same `/tenant/finance/reports/kpi` path.** The hub's family grouping bubbles them under the shared `DASHBOARD` `ReportFamily` (established Phase 1), so no additional "family card" is needed — each KPI card in the hub already deep-links to the batch page.
- **KPI specs require `provideNoopAnimations()`.** ngx-charts sets synthetic `@animationState` props internally regardless of the parent chart's `[animations]="false"` input, so any TestBed spec that mounts a chart-consuming component throws `NG05105` without an animations provider. Documented for Phase 8's Playwright specs and any future chart specs.

### Overview

The user-visible slice. Build the compact `app-sparkline` chart primitive per K15 and the `KpiDashboardComponent` at `/tenant/finance/reports/kpi` per K16 with 5 tile cards + 3 filter chips + click-through drill navigation.

### Changes Required

#### 1. `app-sparkline` component

**File**: `clients/angular/src/app/shared/components/charts/sparkline/sparkline.component.ts`
**File**: `clients/angular/src/app/shared/components/charts/sparkline/sparkline.component.html`
**File**: `clients/angular/src/app/shared/components/charts/sparkline/sparkline.component.scss`

Standalone Angular component wrapping `ngx-charts-line-chart` in compact mode.

```typescript
import { Component, Input, ChangeDetectionStrategy } from '@angular/core';
import { NgxChartsModule } from '@swimlane/ngx-charts';
import { CommonModule } from '@angular/common';

@Component({
  selector: 'app-sparkline',
  standalone: true,
  imports: [CommonModule, NgxChartsModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="sparkline-wrap" [style.height.px]="height">
      <ngx-charts-line-chart
        [results]="[{ name: seriesName, series: data }]"
        [xAxis]="false"
        [yAxis]="false"
        [showXAxisLabel]="false"
        [showYAxisLabel]="false"
        [legend]="false"
        [autoScale]="true"
        [curve]="curveMonotoneX"
        [animations]="false"                <!-- K15 / Phase 8 §2a precedent: MUST be false -->
        [scheme]="scheme">
      </ngx-charts-line-chart>
    </div>
  `,
  styleUrls: ['./sparkline.component.scss']
})
export class SparklineComponent {
  @Input() data: { name: string; value: number }[] = [];
  @Input() seriesName = 'Trend';
  @Input() height = 60;
  @Input() scheme: any = { domain: ['#4f46e5'] };
  readonly curveMonotoneX = curveMonotoneX;   // imported from d3-shape
}
```

Test file: `sparkline.component.spec.ts` — mounts with 12 points, asserts render, asserts `[animations]="false"` in template.

#### 2. Angular service

**File**: `clients/angular/src/app/core/services/executive-kpi.service.ts`

```typescript
@Injectable({ providedIn: 'root' })
export class ExecutiveKpiService {

  constructor(private http: HttpClient) {}

  private base = '/api/v1/reports/kpi';

  dashboard(filters: KpiFilters): Observable<KpiDashboardResponse> {
    return this.http.get<KpiDashboardResponse>(`${this.base}/dashboard`,
            { params: buildParams(filters) });
  }

  trend(key: KpiKey, filters: KpiFilters, windowMonths: 12 | 24 = 12): Observable<KpiTrendPoint[]> {
    return this.http.get<KpiTrendPoint[]>(`${this.base}/${key}/trend`,
            { params: buildParams({ ...filters, windowMonths }) });
  }
}

export type KpiKey = 'LOSS_RATIO_KPI' | 'EXPENSE_RATIO' | 'COMBINED_RATIO' | 'CLAIMS_FREQUENCY' | 'AVERAGE_SEVERITY';

export interface KpiFilters {
  insuranceLine?: string;
  schemeId?: string;
  producerId?: string;
  reportingCurrency?: string;
}
```

Type-mirror the Java DTOs: `KpiValue`, `KpiReportData`, `KpiDashboardResponse`, `KpiTrendPoint` — matches the `report-envelope.ts` pattern established Phase 1.

#### 3. Filter chips components

Reuse existing `app-line-select`, `app-scheme-select`, `app-producer-select` if present; otherwise wire debounced search-select per memory `feedback_no_raw_id_inputs`. Emit a single `KpiFilters` object upstream on any chip change.

#### 4. `KpiTileComponent`

**File**: `clients/angular/src/app/pages/tenant/finance/reports/kpi/kpi-tile.component.ts`

```typescript
@Component({
  selector: 'app-kpi-tile',
  standalone: true,
  imports: [CommonModule, RouterModule, SparklineComponent, TooltipModule],
  template: `
    <div class="kpi-tile" (click)="onTileClick()" [class.disabled]="loading">
      <header>
        <h3>{{ label }}</h3>
        <span *ngIf="basisNote" class="basis-note" [tooltip]="basisTooltip">ⓘ</span>
        <span class="trend-arrow" [class.up]="trendDirection === 'up'"
                                  [class.down]="trendDirection === 'down'"
                                  [class.flat]="trendDirection === 'flat'"></span>
      </header>
      <div class="composite">
        {{ composite ? (composite | percent:'1.1-1') : '—' }}
      </div>
      <app-sparkline [data]="sparklineData" [height]="60"></app-sparkline>
      <div class="per-currency">
        <span *ngFor="let entry of perCurrency | keyvalue" class="chip">
          {{ entry.key }} {{ entry.value.ratio | percent:'1.0-1' }}
        </span>
      </div>
      <footer>Period: {{ periodLabel }}</footer>
      <div *ngIf="warnings.length" class="warnings">
        <span *ngFor="let w of warnings" class="warning">⚠ {{ w }}</span>
      </div>
    </div>
  `,
  styleUrls: ['./kpi-tile.component.scss']
})
export class KpiTileComponent {
  @Input() key!: KpiKey;
  @Input() label!: string;
  @Input() composite: number | null = null;
  @Input() basisNote?: string;
  @Input() perCurrency: Record<string, KpiValue> = {};
  @Input() sparklineData: { name: string; value: number }[] = [];
  @Input() warnings: string[] = [];
  @Input() periodLabel = '';
  @Input() trendDirection: 'up' | 'down' | 'flat' = 'flat';
  @Input() loading = false;

  readonly drillMap: Record<KpiKey, string> = {
    LOSS_RATIO_KPI:    '/tenant/finance/reports/loss-ratio',
    EXPENSE_RATIO:     '/tenant/finance/reports/commission/statement',
    COMBINED_RATIO:    '/tenant/finance/reports/kpi',       // anchor to top per K15
    CLAIMS_FREQUENCY:  '/tenant/finance/reports/claims/frequency-severity',
    AVERAGE_SEVERITY:  '/tenant/finance/reports/claims/frequency-severity',
  };

  get basisTooltip(): string {
    if (this.basisNote === 'MIXED_LOSS_EARNED_EXPENSE_WRITTEN') {
      return 'Mixed basis — loss on earned premium, expense on written premium (NAIC convention).';
    }
    return '';
  }

  constructor(private router: Router) {}

  onTileClick() {
    if (this.loading) return;
    this.router.navigateByUrl(this.drillMap[this.key]);
  }
}
```

#### 5. `KpiDashboardComponent`

**File**: `clients/angular/src/app/pages/tenant/finance/reports/kpi/kpi-dashboard.component.ts`

```typescript
@Component({
  selector: 'app-kpi-dashboard',
  standalone: true,
  imports: [CommonModule, ReactiveFormsModule,
            KpiTileComponent, LineChipComponent, SchemeSelectComponent, ProducerSelectComponent],
  template: `
    <section class="page-header">
      <h1>Executive KPIs</h1>
      <div class="filters">
        <app-line-chip [(ngModel)]="filters.insuranceLine"
                        (ngModelChange)="onFilterChange()"></app-line-chip>
        <app-scheme-select [(ngModel)]="filters.schemeId"
                            (ngModelChange)="onFilterChange()"></app-scheme-select>
        <app-producer-select [(ngModel)]="filters.producerId"
                             (ngModelChange)="onFilterChange()"></app-producer-select>
      </div>
    </section>

    <section class="tile-grid">
      <app-kpi-tile *ngFor="let k of KPI_KEYS"
                    [key]="k"
                    [label]="tileLabels[k]"
                    [composite]="tiles[k]?.data?.compositeRatio ?? null"
                    [basisNote]="tiles[k]?.data?.basisNote"
                    [perCurrency]="tiles[k]?.perCurrency ?? {}"
                    [sparklineData]="sparklines[k] ?? []"
                    [warnings]="tiles[k]?.warnings ?? []"
                    [periodLabel]="periodLabel"
                    [loading]="loading"></app-kpi-tile>
    </section>
  `
})
export class KpiDashboardComponent implements OnInit {
  readonly KPI_KEYS: KpiKey[] = ['LOSS_RATIO_KPI', 'EXPENSE_RATIO', 'COMBINED_RATIO',
                                  'CLAIMS_FREQUENCY', 'AVERAGE_SEVERITY'];
  readonly tileLabels: Record<KpiKey, string> = {
    LOSS_RATIO_KPI:    'Loss ratio',
    EXPENSE_RATIO:     'Acquisition ratio',    // K4 UI label
    COMBINED_RATIO:    'Combined ratio',
    CLAIMS_FREQUENCY:  'Claims frequency',
    AVERAGE_SEVERITY:  'Average severity',
  };

  filters: KpiFilters = {};
  tiles: Partial<Record<KpiKey, ReportResponse<KpiReportData>>> = {};
  sparklines: Partial<Record<KpiKey, { name: string; value: number }[]>> = {};
  loading = false;
  periodLabel = '';

  constructor(private kpiService: ExecutiveKpiService) {}

  ngOnInit() { this.refresh(); }

  onFilterChange = () => this.refresh();

  refresh() {
    this.loading = true;
    // Batch dashboard + parallel trend fetches
    forkJoin({
      dashboard: this.kpiService.dashboard(this.filters),
      trends: forkJoin(
        this.KPI_KEYS.reduce((acc, k) => ({
          ...acc,
          [k]: this.kpiService.trend(k, this.filters, 12)
        }), {} as Record<KpiKey, Observable<KpiTrendPoint[]>>))
    }).subscribe({
      next: ({ dashboard, trends }) => {
        this.tiles = dashboard.tiles;
        for (const k of this.KPI_KEYS) {
          this.sparklines[k] = trends[k].map(pt => ({
            name: pt.periodStart,
            value: pt.composite.compositeRatio ?? 0
          }));
        }
        const first = Object.values(dashboard.tiles).find(Boolean);
        this.periodLabel = first
            ? `${first.period.periodStart} – ${first.period.periodEnd}`
            : '';
        this.loading = false;
      },
      error: () => { this.loading = false; }
    });
  }
}
```

#### 6. Route + sidebar entry

**File**: `clients/angular/src/app/pages/tenant/finance/finance.routes.ts`

Add:

```typescript
{
  path: 'reports/kpi',
  canActivate: [permissionGuard(['finance:view_subledger'])],
  loadComponent: () =>
      import('./reports/kpi/kpi-dashboard.component').then(m => m.KpiDashboardComponent),
  data: {
    title: 'Executive KPIs',
    sidebar: 'operational',
    reportKey: 'COMBINED_RATIO'   // batch page — one entry covers the 5; individual tile toggle hides tiles
  },
},
```

Add matching sidebar entry to `operational-nav.ts` under Finance > Reports.

#### 7. Reports Hub card

**File**: `clients/angular/src/app/pages/tenant/finance/reports/reports-hub.component.ts`

Add a `DASHBOARD` family group + card linking to `/tenant/finance/reports/kpi` if any of the 5 KPI keys is enabled for the tenant. Filter the card visibility client-side against `TenantReportConfigService`.

#### 8. Component specs

**File**: `clients/angular/src/app/pages/tenant/finance/reports/kpi/kpi-dashboard.component.spec.ts`
**File**: `clients/angular/src/app/pages/tenant/finance/reports/kpi/kpi-tile.component.spec.ts`
**File**: `clients/angular/src/app/shared/components/charts/sparkline/sparkline.component.spec.ts`

Mount, assert render with mocked service, assert filter-change triggers refresh, assert tile click navigates to drill target, assert basis-note tooltip visible for COMBINED_RATIO.

### Success Criteria

#### Automated Verification

- [x] `cd clients/angular && npx ng build --configuration=development` — clean build (no new warnings vs Phase-1 baseline)
- [x] `make test-angular` — 19 new specs pass (sparkline × 2, kpi-tile × 8, kpi-dashboard × 9); 2 pre-existing baseline failures untouched (`insurance-lines.spec providerModeForLine`, `ReportSchedulesPageComponent saveCard`)
- [ ] `verify` on `/tenant/finance/reports/kpi` — 5 tiles render with mock data via MSW or dev-mode empty state without console errors; filter chips render; sparkline visible; click on tile navigates to Phase 5 loss-ratio detail
- [ ] `verify` on `/tenant/finance/reports` — Dashboard family card visible + links to `/kpi`
- [x] Bundle-size: `reports/kpi` chunk 56 KB raw dev-mode (well under 200 KB gzipped target)

#### Manual Verification

- [ ] Log in as finance officer with a real tenant → 5 tiles render with real numbers
- [ ] Change insurance-line chip → tiles re-render with filtered numbers
- [ ] Change reporting-currency (via a currency select if wired, else via URL param) → composite scalar shifts
- [ ] Hover the COMBINED_RATIO ⓘ icon → tooltip shows the mixed-basis note
- [ ] Click LOSS_RATIO_KPI tile → land on `/tenant/finance/reports/loss-ratio`
- [ ] Toggle EXPENSE_RATIO off in `/tenant/admin/settings/reports` → EXPENSE_RATIO tile disappears; other 4 remain

**Implementation Note**: Pause for confirmation before Phase 8. Phase 7 is the visible slice; the human check matters here.

---

## Phase 8: `ScheduledReportShapeAdapter` × 5 + `KpiWorkbookService` + Phase 17 UI whitelist + Playwright

### Deviations

- **Angular whitelist doesn't need an explicit `CADENCED_KEYS_IN_SCOPE` extension.** The Phase 17 UI at `report-schedules-page.component.ts:226` filters candidates by `c.cadenced && !scheduledKeys.has(c.reportKey)` — a comment in-line notes "we rely on the fact that the POST endpoint rejects non-whitelisted keys with 400." The 5 KPI keys already have `cadenced=true` from Phase 1, so widening `ScheduledReportEligibility.WHITELIST` in `shared/` alone is enough. tenancy-service picks up the widened set via the shared constant without any tenancy-service edits.
- **The scheduled-email Playwright spec is mocked-API, not live-stack.** The plan sketches a probe→orchestrator→SMTP→mailpit round-trip; the repo's e2e infrastructure is fully mocked (see `fixtures/api-mocks.ts` header), and every existing Phase 17 spec (`scheduled-report-happy-path.spec.ts`, etc.) explicitly notes this deviation from its own plan. Following the established convention keeps the e2e budget tight and defers real-mailpit verification to the manual runbook item. `kpi-scheduled-email.spec.ts` asserts (a) the 5 KPI keys appear in the "Add a schedule" candidates (whitelist widening surfaces via UI) and (b) the POST body shape matches for `LOSS_RATIO_KPI`.
- **The `KpiWorkbookService` uses a private `dispatchSingle(key, req)` switch rather than reusing `KpiComposerService.dispatchSingle`.** The composer's method is package-private (`private`) — exposing it would leak the composer's internal dispatch surface. Duplicating a 5-arm switch in the workbook is cheaper than widening the composer's API.
- **XLSX MediaType constant is per-controller, not centralised.** The plan speaks about an `XLSX` constant; the codebase pattern is to declare it as a `private static final MediaType XLSX = MediaType.parseMediaType(...)` inside each controller that needs it (see `CommissionReportController:65`). Followed that pattern in `ExecutiveKpiController` rather than introducing a shared constant.
- **KPI workbook is single-sheet-per-KPI, not per-currency-sheet.** The plan sketch and the loss-ratio precedent (`LossRatioExcelService`) both use one sheet with per-currency rows in a table — the KPI workbook mirrors this, adding a second "Trend (12 months)" sheet for board-pack context. No per-currency sheet split (would inflate the file with 8 near-empty tabs for a multi-line tenant).
- **`KpiWorkbookService.formatRatio` branches on `ReportKey`** — AVERAGE_SEVERITY renders as a plain money amount (paid / claim count) with 2 decimals, while the other 4 render as percentages with 1 decimal. Composer stores every ratio as a plain `BigDecimal`, so this format-time discrimination is where the "severity is a currency amount, not a proportion" distinction gets applied.
- **Export endpoint gate uses imperative `ReportEnablementReader.isEnabled(...)`** — the path variable can't feed a compile-time `@RequiresReport` annotation. Same pattern the trend endpoint already uses (Phase 6 deviation). 403 short-circuits before both workbook build and audit publish, verified by `ExecutiveKpiControllerTest.exportExcel_disabledKey_returns403_andSkipsWorkbookAndAudit`.
- **DATA_ACCESS security event carries plan-required fields.** The audit payload includes `periodStart`, `periodEnd`, `reportingCurrency`, `insuranceLine`, `schemeId`, `producerId`, and `source: MANUAL_EXPORT` — enough for downstream forensics to reconstruct the exact export shape without a JSON payload dump.
- **The plan's Playwright suggestion `page.getByText('Loss ratio').click()` would have matched multiple elements** (the tile heading + the per-currency chip label if it read "Loss ratio USD" — plus the sidebar label). Scoped the selector to `getByRole('heading', { name: 'Loss ratio' })` so the drill-through assertion binds to the tile card.
- **No new server-side tenancy-service enforcement test added for the widened whitelist.** The existing `TenantReportScheduleServiceTest.reportKey_not_eligible` test uses a fixture key from the excluded set; widening the whitelist doesn't invalidate it. The KPI IT (`ExecutiveKpiControllerIT.exportExcel_*`) provides end-to-end coverage that the KPI keys flow through eligibility + gate + audit + response.
- **`AdapterDelegationTest` covers 5 new KPI adapters via a shared `assertKpiAdapter(...)` helper**, keeping per-KPI test bodies to one line each. Mirrors how the existing `commissionAdapter_...` / `lossRatioAdapter_...` / `cessionBordereauAdapter_...` cases already share fixture data.

### Overview

Wire the 5 KPI keys into Phase 17's scheduled-email pipeline per K11. Build the XLSX renderer used by both the scheduled dispatch and an on-demand "Export XLSX" button on the KPI page. Extend the Phase 17 tenant-admin UI whitelist to accept the 5 new cadenced keys.

### Changes Required

#### 1. `KpiWorkbookService`

**File**: `services/java/finance-service/src/main/java/com/medfund/finance/kpi/service/KpiWorkbookService.java`

Uses shared `ReportWorkbook` builder (Phase 0). One workbook per fire; single sheet layout:

```
Row 1: [Title: <KPI label> — <periodStart> to <periodEnd>]
Row 2: [Reporting currency: <XXX>]
Row 3: [Composite ratio: <NNN.N%>]  [Numerator (<XXX>): <amount>]  [Denominator (<XXX>): <amount>]
Row 4: [Basis: <basisNote or "Single basis">]
Row 6+: [Per-currency breakdown table]
Row (last): [Warnings, if any]
```

For trend-inclusive workbooks (scheduled monthly board pack): add a second sheet "Trend (12 months)" with a table of the 12 monthly composite ratios.

```java
@Slf4j
@Service
@RequiredArgsConstructor
public class KpiWorkbookService {

    private final KpiComposerService composer;

    public Mono<byte[]> workbook(String reportKey, KpiRequest req) {
        return Mono.zip(composer.computeSingleKpi(reportKey, req),
                        composer.trend(reportKey, req, 12))
                .map(t -> render(reportKey, t.getT1(), t.getT2()));
    }

    private byte[] render(String reportKey, ReportResponse<KpiReportData> latest,
                          List<KpiTrendPoint> trend) {
        return ReportWorkbook.of("KPI - " + reportKey)
                .sheet("Summary")
                    .titleRow(ReportKey.parse(reportKey).orElseThrow().getLabel())
                    .metaRow("Period", latest.period().periodStart() + " to " + latest.period().periodEnd())
                    .metaRow("Reporting currency", latest.reportingCurrency())
                    .metaRow("Composite ratio", formatPct(latest.data().compositeRatio()))
                    .metaRow("Numerator", latest.data().compositeNumerator() + " " + latest.reportingCurrency())
                    .metaRow("Denominator", latest.data().compositeDenominator() + " " + latest.reportingCurrency())
                    .metaRow("Basis", nvl(latest.data().basisNote(), "Single basis"))
                    .blankRow()
                    .table("Per-currency breakdown")
                        .header("Currency", "Ratio", "Numerator", "Denominator")
                        .rows(latest.perCurrency(), (curr, val) ->
                                new Object[] { curr, formatPct(val.ratio()), val.numerator(), val.denominator() })
                    .warningsRow(latest.warnings())
                .sheet("Trend (12 months)")
                    .table()
                        .header("Period start", "Period end", "Composite ratio", "Warnings")
                        .rows(trend, pt -> new Object[] {
                                pt.periodStart(),
                                pt.periodEnd(),
                                formatPct(pt.composite().compositeRatio()),
                                String.join("; ", pt.warnings())
                        })
                .build();
    }
}
```

Adapts shape from existing per-family workbook services (e.g. `NotesExcelService`, `LossRatioExcelService`).

#### 2. Add "Export XLSX" endpoint to `ExecutiveKpiController`

```java
@GetMapping("/{key}/export/excel")
@RequiresPermission(Permissions.FINANCE_VIEW_SUBLEDGER)
public Mono<ResponseEntity<byte[]>> exportExcel(
        @PathVariable String key,
        @RequestParam String periodStart,
        @RequestParam String periodEnd,
        @RequestParam(required = false) String reportingCurrency,
        @RequestParam(required = false) String insuranceLine,
        @RequestParam(required = false) UUID schemeId,
        @RequestParam(required = false) UUID producerId,
        @AuthenticationPrincipal Jwt jwt) {
    ReportKey rk = ReportKey.parse(key).filter(k -> k.getFamily() == ReportFamily.DASHBOARD)
            .orElseThrow(() -> new IllegalArgumentException("Unknown KPI key: " + key));
    return Mono.deferContextual(ctx -> {
        UUID tenantId = UUID.fromString(TenantContext.get(ctx));
        KpiRequest req = new KpiRequest(tenantId,
                LocalDate.parse(periodStart), LocalDate.parse(periodEnd),
                reportingCurrency, insuranceLine, schemeId, producerId);
        Map<String, Object> details = Map.of(
                "periodStart", periodStart, "periodEnd", periodEnd,
                "reportingCurrency", nvl(reportingCurrency, ""),
                "source", "MANUAL_EXPORT");
        return workbookService.workbook(rk.name(), req)
                .flatMap(bytes -> securityEventPublisher.publishDataAccess(
                        tenantId.toString(), AuditActor.id(jwt), AuditActor.email(jwt),
                        rk.name(), details).thenReturn(bytes))
                .map(bytes -> ResponseEntity.ok()
                        .contentType(XLSX)
                        .header(HttpHeaders.CONTENT_DISPOSITION,
                                "attachment; filename=\"" + rk.name().toLowerCase() + "-"
                                        + periodStart + "-to-" + periodEnd + ".xlsx\"")
                        .body(bytes));
    });
}
```

Also add an "Export XLSX" button to `KpiDashboardComponent` (per-tile action).

#### 3. Five adapter classes

**File**: `services/java/finance-service/src/main/java/com/medfund/finance/report/schedule/adapter/LossRatioKpiAdapter.java`

Follows the shape of the existing 13 adapters at `services/java/finance-service/src/main/java/com/medfund/finance/report/schedule/adapter/`. Reference: `LossRatioAdapter.java`.

```java
@Component
@RequiredArgsConstructor
public class LossRatioKpiAdapter implements ScheduledReportShapeAdapter {

    private final KpiWorkbookService workbookService;

    @Override public ReportKey key() { return ReportKey.LOSS_RATIO_KPI; }
    @Override public ReportPeriodShape periodShape() { return ReportPeriodShape.PREVIOUS_COMPLETE_PERIOD; }

    @Override public Mono<byte[]> render(ScheduledFireContext ctx) {
        // Scheduled fire has no filter chips per K13 (schedule scope is
        // tenant-wide); reportingCurrency comes from tenant default via
        // ctx.reportingCurrency().
        KpiRequest req = new KpiRequest(ctx.tenantId(),
                ctx.periodStart(), ctx.periodEnd(),
                ctx.reportingCurrency(),
                null, null, null);
        return workbookService.workbook(ReportKey.LOSS_RATIO_KPI.name(), req);
    }
}
```

Repeat for `ExpenseRatioAdapter`, `CombinedRatioAdapter`, `ClaimsFrequencyAdapter`, `AverageSeverityAdapter` — same shape, different KPI key.

#### 4. Phase 17 UI whitelist extension

**File**: `clients/angular/src/app/pages/tenant-admin/settings/report-schedules/report-schedules-page.component.ts` (or wherever Phase 17 lands the whitelist)

Locate the whitelist array — likely a `CADENCED_KEYS_IN_SCOPE` constant naming the 13 keys from Phase 17 S1. Add the 5 KPI keys:

```typescript
const CADENCED_KEYS_IN_SCOPE: KpiKey[] = [
  // ...existing 13 from Phase 17 S1...
  'COMBINED_RATIO',
  'LOSS_RATIO_KPI',
  'EXPENSE_RATIO',
  'CLAIMS_FREQUENCY',
  'AVERAGE_SEVERITY',
];
```

Verify placement at implement time — the Phase 17 sub-plan at `thoughts/shared/plans/2026-08-31-scheduled-email-delivery.md` §C is the reference. If the whitelist is enforced server-side (in tenancy-service's `TenantReportScheduleController` per Phase 17 S4/S12), extend that constant too.

Add a Deviations note to the Phase 17 sub-plan pointing at this widening (parent-plan Phase 18 § Owed back already flagged this).

#### 5. Playwright specs

**File**: `clients/angular/e2e/kpi-dashboard.spec.ts`

```typescript
test.describe('KPI dashboard', () => {
  test('renders 5 tiles + drill navigation', async ({ page }) => {
    await loginAsFinanceOfficer(page);
    await page.goto('/tenant/finance/reports/kpi');
    await expect(page.getByText('Executive KPIs')).toBeVisible();
    for (const label of ['Loss ratio', 'Acquisition ratio', 'Combined ratio',
                          'Claims frequency', 'Average severity']) {
      await expect(page.getByText(label, { exact: true })).toBeVisible();
    }
    await page.getByText('Loss ratio', { exact: true }).click();
    await expect(page).toHaveURL(/\/reports\/loss-ratio$/);
  });

  test('filter chip re-fetches tiles', async ({ page }) => {
    await loginAsFinanceOfficer(page);
    await page.goto('/tenant/finance/reports/kpi');
    // ...select insurance-line = HEALTH, assert tile numbers change
  });

  test('mixed-basis tooltip on combined ratio', async ({ page }) => {
    await loginAsFinanceOfficer(page);
    await page.goto('/tenant/finance/reports/kpi');
    await page.getByRole('button', { name: /combined ratio basis/i }).hover();
    await expect(page.getByText(/mixed basis.*NAIC/i)).toBeVisible();
  });
});
```

**File**: `clients/angular/e2e/kpi-scheduled-email.spec.ts`

```typescript
test('scheduled KPI delivery lands at mailpit', async ({ page, request }) => {
  await loginAsTenantAdmin(page);
  await page.goto('/tenant/admin/settings/report-schedules');
  // Create schedule for LOSS_RATIO_KPI, monthly, 08:00 tenant TZ, recipient a@test
  // ...form fill

  // Fire via Phase 17's test-only "force fire" endpoint
  await request.post('/api/v1/reports/scheduled/force-fire', {
    data: { reportKey: 'LOSS_RATIO_KPI' }, headers: { /* admin auth */ }
  });

  // Poll mailpit
  const inbox = await pollMailpit(request, 'a@test', { subject: /Loss ratio.*Monthly/ });
  expect(inbox.messages).toHaveLength(1);
  expect(inbox.messages[0].attachments[0].filename).toMatch(/loss_ratio_kpi_.*\.xlsx/);
});
```

### Success Criteria

#### Automated Verification

- [x] `cd services/java && ./gradlew :finance-service:compileJava` compiles clean (main + Phase 8 sources)
- [x] `cd services/java && ./gradlew :finance-service:test --tests KpiWorkbookServiceTest --tests AdapterDelegationTest --tests ExecutiveKpiControllerTest` — 8 workbook + 11 adapter + 17 controller cases pass
- [x] `./gradlew :finance-service:test --tests ExecutiveKpiControllerIT` — 13 cases pass (includes new `exportExcel_returnsXlsxBytes` + `exportExcel_disabledKey_returns403`). Pre-existing 26 IT files with `AmlSummaryRawDataProvider` bean-wiring gaps unchanged (not from this phase's work).
- [x] `make test-angular` — 24 KPI/sparkline specs pass (18 from Phase 7 + 2 new tile export cases + 3 new dashboard export cases + 1 net-new after `exportExcel` service mock). Full suite: 733 pass / 1 pre-existing failure (`ReportSchedulesPageComponent saveCard`). No `report-schedules-page.component.spec.ts` extension needed — see Deviations.
- [ ] `make test-e2e` — new `kpi-dashboard.spec.ts` (3 cases) + `kpi-scheduled-email.spec.ts` (1 case) typecheck clean (`npx tsc --noEmit -p e2e/tsconfig.json`); Playwright run requires the auto-started Angular dev server + Keycloak stub, deferred to manual verification.
- [ ] `verify` on `/tenant/admin/settings/report-schedules` — dropdown lists the 5 KPI keys + user can create a schedule + save

#### Manual Verification

- [ ] Schedule EXPENSE_RATIO for weekly delivery (via Phase 17 UI); wait for probe fire; verify email arrives at mailpit with XLSX attachment
- [ ] Open the XLSX in Excel: two sheets ("Summary" + "Trend (12 months)"), sheet 1 has period + reporting currency + composite ratio + basis + per-currency table + warnings; sheet 2 has 12-month monthly trend
- [ ] Disable EXPENSE_RATIO in `/tenant/admin/settings/reports` → schedule cascade-disables per Phase 17 S9 → subsequent probe fires do NOT deliver
- [ ] Re-enable EXPENSE_RATIO → schedule stays disabled (K11 note: cascade is asymmetric — admin must re-enable each schedule); confirm this in the UI
- [ ] Manual `POST /api/v1/reports/scheduled/{jobId}/rerun` on a failed KPI job — enqueues fresh job with same params + audits invoking user

**Implementation Note**: Final phase. Pause for full user acceptance walkthrough. Once green, this sub-plan is complete and the parent-plan `phases_status["18"]` moves from `grilled` to `landed`.

---

## Testing Strategy

### Unit Tests
- **`ReportKeyTest`** — cadenced/periodShape invariant on the 5 KPI keys; EXPENSE_RATIO label reads "Acquisition ratio"
- **`PremiumAggregateServiceTest` / `ClaimsAggregateServiceTest` / `CommissionAggregateServiceTest`** — dimension GROUP BY correctness, filter combinations, empty-result handling
- **`KpiComposerServiceTest`** — 10+ cases covering happy path, missing IBNR, missing FX (fail-loud composite), small-denominator warning, cache hit/miss, mixed-basis combined ratio, per-KPI compute paths
- **`IbnrLookupServiceTest`** — result_json vs payload_ref MinIO fallback, insuranceLine filter over `per_cohort_ultimate` array, empty-window returns empty Optional + warning
- **`KpiWorkbookServiceTest`** — POI byte-inspection: sheet count, header cells, row values, warnings section presence
- **Angular component specs** — `SparklineComponent`, `KpiTileComponent`, `KpiDashboardComponent` mount + interaction

### Integration Tests (Testcontainers slices)
- **`PremiumAggregateIT`** — seed 2-currency × 2-line earning-schedule fixture; assert group-by shapes
- **`ClaimsIncurredIT`** — seed claims + reserve history; assert DISTINCT ON boundary correctness + subtotalIncurredExIbnr arithmetic
- **`CommissionAggregateIT`** — seed multi-status commission fixture; assert only PAID counted
- **`ExecutiveKpiControllerIT`** — full happy path + peer-failure fallback (mock contributions client to 500 → envelope warnings + partial data) + 403-when-disabled per `ReportRetrofitAssertions` + XLSX export + SecurityEvent Kafka round-trip
- `infra_testcontainers_pitfalls` guards apply to every new IT (Testcontainers 1.21.4 BOM, flyway-database-postgresql, ReactiveJwtDecoder stub)

### E2E Tests (Playwright, `clients/angular/e2e/`)
- **`kpi-dashboard.spec.ts`** — golden path (5 tiles render + drill), filter chip re-fetch, mixed-basis tooltip
- **`kpi-scheduled-email.spec.ts`** — schedule creation → force-fire → mailpit assertion

### Manual Testing Steps
1. Log in as finance officer on a real tenant with data → 5 tiles render with real numbers
2. Confirm composite scalar for LOSS_RATIO_KPI matches independent hand-calculation for a known month
3. Confirm the "IBNR pending" warning fires when the tenant hasn't run an IBNR job
4. Multi-currency tenant: per-currency breakdown row shows two ratios; composite differs from each individually
5. Toggle each KPI on/off in admin — tile appears/disappears + admin schedule modal shows correct affected-count on disable
6. Schedule a KPI monthly → wait for probe fire → verify email lands at mailpit with valid XLSX attachment

## Performance Considerations

- **Cache hit is the critical path.** K10's Redis TTL is 15 min. Trend endpoint = 12 individual composer calls; on cold cache, 12 fanouts each with 4 hops = worst case ~30s on slow peer network. Mitigate by: (a) `KpiComposerService.trend(...)` uses `concatMap` (bounded parallelism); (b) each bucket writes back to cache so second render is <100ms; (c) an async "warm cache" endpoint could be added Phase 18.5 for tenant-switch scenarios.
- **Peer aggregate SQL** must stay server-side (never `.collectList().map(sum)` in Java) — the aggregates are the whole point. Repository queries do the GROUP BY.
- **DISTINCT ON reserve subquery** (Phase 3) is expensive on large `claim_reserve_history` tables. Ensure the existing index `(claim_id, effective_at DESC)` from V139 covers it; add `EXPLAIN ANALYZE` check to `ClaimsIncurredIT`.
- **KPI batch endpoint** fans out to 4 peers × 5 KPIs = up to 20 concurrent calls; use `Mono.zip` (not `flatMapMerge`) so backpressure works. Redis MGET could batch cache lookups (Phase 18.5 optimization).
- **Angular bundle** — sparkline component + tile component together ~20KB gzipped; well under the 200KB reports-hub target. Verify at Phase 7 build.
- **XLSX generation** — KPI workbook is tiny (~5KB), no streaming needed. `KpiWorkbookService` uses in-memory POI.

## Migration Notes

- **No Flyway migrations** in this sub-plan. All queries are SELECT over existing Phase 11/12/14/15 tables (F18-8). Reviewer looking for `V169__*.sql` will find nothing — that's by design. Verified against latest tenant migration `V168__report_job_schedule_columns.sql` on 2026-09-05.
- **Never edit an applied migration** — sub-plan doesn't touch any prior migration file (per `feedback_never_edit_applied_migrations` memory).
- **No `public.` prefixes on tenant tables** — every SQL query in Phases 2-5 references `earning_schedule`, `claim_reserve_history`, `claims`, `claim_details`, `commission_transaction` unqualified (per `bug_public_prefix_silent_rollback` memory).
- **`report_job` is a tenant-schema table** (Phase 15) — `IbnrLookupService` queries it unqualified.
- **Keycloak realm additions**: two new permission strings (`CONTRIBUTIONS_READ_AGGREGATE`, `CLAIMS_READ_AGGREGATE`) need to be granted to the finance-service M2M service-account role. If `infra/keycloak/realm-config.json` is version-controlled, add them there in Phase 2/3; otherwise document as a manual realm-admin step.

## Rollout & Rollback

### Deploy order

1. **Phase 1 (enum) first** — additive change; every dependent service picks it up on next redeploy. Old services with the old enum will still function (backwards-compatible: new keys are unknown to them but they don't reference them).
2. **Phases 2/3/4 in any order** — three independent aggregate endpoints. Each ships without changing existing behavior. No consumers yet.
3. **Phase 5 (composer)** — safe to deploy after any subset of 2/3/4 have shipped; missing peers → envelope warnings + partial data per invariant #7.
4. **Phase 6 (trend)** — extends Phase 5; safe.
5. **Phase 7 (Angular)** — deploys once backend is live. Any KPI whose backend key is toggled off in `tenant_report_config` (or whose gate returns 403) → tile hides via the standard sidebar/hub filter (Phase 0 §7). Empty state acceptable.
6. **Phase 8 (scheduled adapters + whitelist)** — the Phase-17 orchestrator picks up new adapters at bean-scan time on redeploy. The UI whitelist extension deploys with the Angular bundle. Order: adapter deploy first, whitelist next (a tenant creating a schedule the orchestrator can't yet handle would get failure email — unlikely but avoidable).

### Rollback

- **Any phase is independently revertable.** Reverting Phase 8 disables scheduled delivery but keeps the on-demand dashboard alive.
- **Reverting Phase 7** leaves backend endpoints in place — a finance officer curl'ing them still works.
- **Reverting Phase 5** hides the whole dashboard (Angular gets 404s on the KPI endpoints); toggle the 5 keys off in tenant-admin as a quick-hide before rolling back.
- **Reverting Phase 1** would flip the 5 keys back to `cadenced=false`, which would silently drop them from Phase 17's UI whitelist — but no schedules would exist for these keys pre-Phase-8, so no rollout data loss.

### Feature-flag alternative

For a high-risk KPI (e.g. LOSS_RATIO_KPI with IBNR dependency), disable it in `tenant_report_config` by default at Phase 1 seed time; enable per-tenant after Phase 5-7 gets a canary run. Adds a small seeding migration in Phase 1 (a public INSERT setting `enabled=FALSE` for LOSS_RATIO_KPI across all tenants) — offered as an option, not the default.

## References

- Parent plan: `thoughts/shared/plans/2026-08-11-financial-reporting-suite.md` § Phase 18 (lines 4444-4637)
- Grilling scratchpad: `thoughts/shared/notes/2026-09-05-phase18-executive-kpi-grill.md`
- Architecture: `.claude/multi-currency.md` (§ Reporting), `.claude/portals.md` (finance-officer role), `.claude/CLAUDE.md` (9 Critical Rules)
- Related implementations to model after:
  - Cross-service composer envelope: `services/java/finance-service/src/main/java/com/medfund/finance/controller/CrossServiceReportController.java:73-137`
  - Aggregate endpoint pattern: `services/java/contributions-service/src/main/java/com/medfund/contributions/controller/BillingAggregateController.java:50-66` + `services/java/claims-service/src/main/java/com/medfund/claims/controller/ClaimsAggregateController.java:50-89`
  - Scheduled adapter pattern: `services/java/finance-service/src/main/java/com/medfund/finance/report/schedule/adapter/LossRatioAdapter.java` + 12 siblings
  - XLSX helper: `services/java/shared/src/main/java/com/medfund/shared/report/ReportWorkbook.java` + `services/java/finance-service/src/main/java/com/medfund/finance/service/LossRatioExcelService.java`
  - Angular chart primitives: `clients/angular/src/app/shared/components/charts/line-chart/line-chart.component.ts`
  - Phase 17 whitelist reference: `thoughts/shared/plans/2026-08-31-scheduled-email-delivery.md` §C
- Related auto-memory entries: `feedback_stats_serverside`, `feedback_no_raw_id_inputs`, `infra_testcontainers_pitfalls`, `bug_reactor_kafka_ack_swallow`, `feedback_audit_actor_email`, `feedback_audit_entity_name`, `feedback_never_edit_applied_migrations`, `bug_public_prefix_silent_rollback`
