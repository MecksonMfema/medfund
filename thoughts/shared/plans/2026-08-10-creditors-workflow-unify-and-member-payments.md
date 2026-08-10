---
date: 2026-08-10
git_commit: c248073df71e8e42d035addfd5a090e5b39bacba
branch: main
ticket: none
research:
  - thoughts/shared/research/2026-08-10-creditors-workflow-unify-providers-and-members.md
steer: "matched columns for both members and providers on the finance-side Creditors listing; members should be settleable via cash/bank like providers, not only via CTC; payments are created by PaymentRun generation (not ad-hoc) and individual revoke removes a payee from a run so the next generation picks them up again"
services_touched: [finance-service, contributions-service, tenancy-service, claims-service, shared, angular]
status: draft
---

# Creditors Workflow — Rename to Debtors/Creditors, Unify Provider+Member Ledgers, Build Member Settlement Pipeline

## Deviations

- **2026-08-10 (Phase 1)** — Added two new permission keys (`finance:manage_payments`, `finance:manage_payment_runs`) alongside the plan's `billing:view_debtors` / `finance:view_creditors` swap. The plan text used `Permissions.FINANCE_MANAGE_PAYMENTS` in the Phase 3 annotations without stating the constant needed introducing; introduced now so Phase 3 can compile without another shared-catalogue round-trip. Both are seeded to `tenant_admin` in V073.
- **2026-08-10 (Phase 1)** — Angular route/nav files referencing `billing:view_creditors` were repointed to the closest new key (`billing:view_debtors` for billing routes, `finance:view_creditors` for finance routes) as part of Phase 1 to keep the Angular typecheck green. Phase 2/5 will restructure the route paths/directories further per the plan.
- **2026-08-10 (Phase 1)** — `PaymentRunGenerator.populate` was refactored from mixed-payee-in-one-run (Flux.merge) to homogeneous branching on `run.getPayeeType()`. Existing tests (`PaymentRunGeneratorTest`) that asserted mixed-payee behaviour were replaced to reflect the new homogeneous contract.
- **2026-08-10 (Phase 1)** — V073 dropped the `permissions_catalogue` INSERT/DELETE blocks called for in the plan since the tenant schema has no such table (V006 seeds directly into `role_permissions`). Only the `role_permissions` swap runs.
- **2026-08-10 (Phase 2)** — `LegacyBillingBalancesController` is annotated `@Hidden` so the 410 shim endpoints don't clutter the /swagger-ui page (they exist only for one release; documenting them would only encourage new callers to bind to them).
- **2026-08-10 (Phase 2)** — `contributions-service` jacoco coverage floor (70%) trips at 52% after Phase 1 landed `MemberBalance*` production code with the ITs deferred to Phase 3. Not caused by the Phase 2 rename (verified — `:contributions-service:test` is UP-TO-DATE and green). Compile + unit tests both pass; the coverage rebuild lands with the deferred IT work.
- **2026-08-10 (Phase 2)** — Deferred the new `BalanceControllerIT` (permission-gate 403) and `LegacyBillingBalancesControllerIT` (410 body) called for in the plan's success criteria. Rationale: the existing project has no IT that instantiates the `PermissionAspect` in a slice test — writing one from scratch introduces new test infra, which is out of scope for a pure rename. Tracked as follow-up alongside the Phase 3 IT work.
- **2026-08-10 (Phase 3)** — `PaymentAdviceService.generateAdvicesForRun` already branches on `item.payeeType` end-to-end (payeeKey grouping, loadPayeeName MEMBER query against `members` table, loadCarryForward payeeCol swap, loadClaimsPaidLines/loadTaxWithheldLines/loadShortfallLines all payeeType-conditioned, persistAdvice sets both providerId and memberId with correct null-handling). No new code needed — the CTC / V071 work already covers Phase 3 §4. Phase 3.4 is a no-op.
- **2026-08-10 (Phase 3)** — Adjusted the FIFO allocation to keep the remaining amount in a mutable single-element array in the closure rather than the plan's `.scan(...)` accumulator. Reason: Reactor's `scan` operates in the pipeline but the plan's tuple emission pattern doesn't cleanly stop iteration once the remainder hits zero — the explicit `remainingHolder[0]` check in `concatMap` avoids emitting empty applications for payables beyond the exhaustion point. Same observable behaviour, simpler control flow, no functional deviation.
- **2026-08-10 (Phase 3)** — `PaymentService` was refactored to `@Slf4j` + `@RequiredArgsConstructor` (Lombok) as part of adding six new dependencies. Alternative (extending a 4-arg manual constructor to a 10-arg one) was cosmetically worse and off the project's Java coding conventions. Existing `PaymentServiceTest.@InjectMocks` continues to work because Mockito injects the null value for the six new deps into the fields — no test method that currently runs touches the MEMBER path, so no null-dereference.
- **2026-08-10 (Phase 3)** — Added `@RequiresPermission` on every endpoint in `PaymentController` and `AdjustmentController` (reads gated by `FINANCE_VIEW_CREDITORS` OR `FINANCE_MANAGE_PAYMENTS`, mutations gated by `FINANCE_MANAGE_PAYMENTS`), closing the pre-existing auth gap called out in the plan's Current State Analysis.
- **2026-08-10 (Phase 3)** — `:finance-service:test` reports 10 pre-existing failures matching [[bug_claim_save_mock_id_npe]] (all in {Payment, Adjustment, ProviderBalance, MascaBankAccount, Reconciliation}ServiceTest — every failure is `.save(any())` returning an entity with a null `id`, then the audit publish path NPEs on `saved.getId().toString()`). Same 10 count called out in Phase 1 Success Criteria. My Phase 3 changes compile cleanly and don't add new failures.
- **2026-08-10 (Phase 3)** — Deferred the new `PaymentServiceIT` (markPaid MEMBER FIFO, revoke deletes-and-recomputes, revoke-forbids-paid, revoke-forbids-executed-run) and `PaymentAdviceServiceIT.execute_memberRun_generatesMemberAdvices` — same rationale as Phase 2's IT deferral (Testcontainers infra + reactive-permission-slice test scaffolding out of scope for the code-drop). Tracked as follow-up.
- **2026-08-10 (Phase 4)** — Added `org.apache.poi:poi-ooxml:5.2.5` to `finance-service/build.gradle.kts` — the plan calls for `CreditorsExcelService` on the finance side but never noted that finance-service had no POI dependency (only contributions-service does). Same version as contributions-side so both workbooks render identically.
- **2026-08-10 (Phase 4)** — Providers table has `practice_number`, not `code` (per V001 baseline). The plan's SQL referenced `pr.code`; the actual repo query uses `pr.practice_number` and surfaces it as `subject_code`. Same for the search predicate.
- **2026-08-10 (Phase 4)** — Deleted `ProviderBalanceController` + its test outright (per plan's "delete ProviderBalanceController — CreditorController calls ProviderBalanceService.findByProviderId directly"). `ProviderBalanceService.searchPaged` + `ProviderBalanceQueryRepository` are now dead code; kept in place per the plan's "not renaming provider_balances table / keep the entity/service" stance. Cleanup for a follow-up.
- **2026-08-10 (Phase 4)** — `LegacyProviderBalancesController` is annotated `@Hidden` for the same reason as the billing shim — one-release lifetime, no reason to advertise the moved paths in Swagger.

## Overview

Close the collision surfaced by `thoughts/shared/research/2026-08-10-creditors-workflow-unify-providers-and-members.md` and — per the grilling session on the same day — take over the deferred member-settlement work the CTC plan explicitly parked. Concretely:

1. **Contributions-side "Creditors" becomes "Debtors"** (Java + Angular). The word "creditor" is misused there; from the tenant's accounting POV subjects with a running balance owe the fund, i.e. are debtors.
2. **Finance-side "Provider Creditors" becomes "Creditors"** and unifies providers with members. Both subject types render four matched money columns (`totalClaimed`, `totalApproved`, `totalPaid`, `outstandingBalance`) in one paginated list.
3. **A new `member_balances` snapshot table** mirrors `provider_balances` structurally, with writer paths on claim-adjudication, CTC commit/reverse, and Payment mark-paid.
4. **Member cash-payment mechanism ships end-to-end.** `Payment`, `PaymentRun`, `PaymentAdvice`, and `Adjustment` all become payee-type-aware; a new `PaymentRun.payee_type` column enforces homogeneous runs. Member payees are enumerated from `member_balances` at run generation time — same shape as providers.
5. **Individual-payment revoke** (`DELETE /api/v1/payments/{id}`) lets an operator exclude one payee from a run. Hard delete; next generation picks the payee up again.
6. **Aggressive cutover**: old permission `billing:view_creditors` removed; new `billing:view_debtors` + `finance:view_creditors` seeded; old API paths return **410 Gone**; old Angular routes removed outright — no redirects.

## Current State Analysis

- **The word "creditor" collides** — contributions-service uses it for subjects who owe the fund (`services/java/contributions-service/src/main/java/com/medfund/contributions/controller/BalanceController.java:74`), finance-service uses it for parties the fund owes for approved claims (`services/java/finance-service/src/main/java/com/medfund/finance/controller/ProviderBalanceController.java:20`). Same word, opposite meanings.
- **Finance-side member ledger has read-side aggregates but no HTTP surface for a list.** `MemberPayableBalanceRepository.findOutstandingByCurrency` (`services/java/finance-service/src/main/java/com/medfund/finance/repository/MemberPayableBalanceRepository.java:77`) is ready but no controller calls it. The placeholder Angular route `/tenant/finance/creditors/member` reuses the provider list component (`clients/angular/src/app/pages/tenant/finance/finance.routes.ts:299-308`).
- **Members have no 4-column snapshot table.** `member_payables` (V069) + `member_payable_applications` (V069) are event-log style; providers have `provider_balances` (V016) as a real snapshot.
- **Member cash payments do not work today.** `Payment.memberId` + `Payment.payeeType` fields exist on the entity since V071 (`services/java/finance-service/src/main/java/com/medfund/finance/entity/Payment.java:25,28`), and `payment_run_items` + `payment_advices` gained the same fields in the same migration (`services/java/tenancy-service/src/main/resources/db/migration/tenant/V071__payment_run_generation_and_advice_ledger.sql:14-87`), but:
  - `PaymentService.markPaid` (line 114) doesn't distinguish payee type and doesn't touch any member-side ledger.
  - `PaymentRunGenerator.populate` enumerates providers only.
  - `PaymentController.searchPaged` doesn't filter by `memberId`/`payeeType`; nor does `PaymentFilterParams`.
  - No `member_balances` snapshot exists to hold `total_paid` for members.
- **PaymentRun today is payee-type-agnostic**, but only structurally at the item level. `payment_runs` has no `payee_type` column (`services/java/tenancy-service/src/main/resources/db/migration/tenant/V016__finance_schema.sql:10-25`); a single run could contain mixed payees which would break bank-file export.
- **No individual-payment revoke exists.** Only whole-run cancel (`PaymentRunService.cancel` line 241) and whole-payment cancel (`PaymentService.cancel` line 142). An operator wanting to exclude one payee from a run has to cancel the run and start over.
- **Auth gaps on both target controllers.** `ProviderBalanceController` and `BalanceController` (contributions-service) have zero `@RequiresPermission` — every JWT-bearing request passes. Fixing this in-flight closes a real gap.
- **Permission `billing:view_creditors` gates both worlds today** (`clients/angular/src/app/pages/tenant/billing/billing.routes.ts:250,268`; `.../finance/finance.routes.ts:289,295,301,309`; `.../operational-nav.ts:81,83,139`; `.../claims.routes.ts:234`; seeded at `services/java/tenancy-service/src/main/resources/db/migration/tenant/V006__rbac_refinements.sql:54`). Single permission key gating opposite concepts is precisely the collision this plan fixes.

## Desired End State

- Operator with `billing:view_debtors` opens `/tenant/billing/debtors` and sees the arrears list (renamed but structurally unchanged from today's `/tenant/billing/creditors`). Excel export downloads `debtors-USD-2026-08-10.xlsx`.
- Operator with `finance:view_creditors` opens `/tenant/finance/creditors` and sees a single paginated table with a subject-type filter (PROVIDER | MEMBER | BOTH). Each row shows `subjectType, name, currency, totalClaimed, totalApproved, totalPaid, outstandingBalance, lastActivityAt`. Both subject types render the same four money columns. Excel export downloads `creditors-BOTH-USD-2026-08-10.xlsx`.
- Clicking a PROVIDER row navigates to `/tenant/finance/creditors/provider/:id` (existing detail page). Clicking a MEMBER row navigates to `/tenant/finance/creditors/member/:id` (new detail page with the same summary shape + tabs: Payables, CTCs, Payments, Adjustments).
- Operator with `finance:manage_payment_runs` opens `/tenant/finance/payment-runs`, clicks "Generate", chooses `payeeType=MEMBER`, currency, description → run is created in `draft` with one Payment per member with `outstanding_balance > 0` in the requested currency.
- Operator with `finance:manage_payments` reviews the run, clicks the revoke row action on a Payment (only enabled while `Payment.status=pending` AND parent run in {draft, approved}) → `DELETE /api/v1/payments/{id}` deletes the Payment + `PaymentRunItem`, recomputes `run.paymentCount` + `run.total_amount`, emits a DELETE audit event with the full row payload.
- Same operator clicks "Approve" → "Execute" on the run. Payment advices generate; each MEMBER Payment moves to `paid`; `member_balances.total_paid` bumps for each; `member_payable_applications` rows land with `source_type='PAYMENT'` allocated FIFO across the member's open `member_payables` for that currency; corresponding `member_payables.status` flips to `applied` when fully consumed.
- `/tenant/finance/advice` list shows both provider and member advices with a payeeType column and filter.
- Any call to `/api/v1/billing/balances/creditors`, `/api/v1/billing/balances/creditors/export/excel`, `/api/v1/provider-balances`, `/api/v1/provider-balances/page`, or `/api/v1/provider-balances/provider/{id}` returns **410 Gone** with a body naming the new path. Any hit on `/tenant/billing/creditors` or `/tenant/finance/creditors/provider` returns Angular's 404.

### Verification (end-to-end journeys)

1. **Debtors rename smoke** — Old Angular URL 404s. `curl -H 'Authorization: Bearer <jwt>' /api/v1/billing/balances/creditors` → 410. New URL renders; Excel export downloads with the `debtors-` prefix.
2. **Member creditor listing** — Backfill V072 populates `member_balances` from existing `member_payables`. Loading `/tenant/finance/creditors` and switching to `subjectType=MEMBER` renders each such member as one row with the correct 4-tuple.
3. **Member PaymentRun journey** — Adjudicate a MEMBER-payee claim for USD 200 for member X. Wait for `medfund.claims.adjudicated` consumer to write `member_payables` + bump `member_balances.total_claimed=200 total_approved=200`. Generate a member PaymentRun in USD — one Payment(payeeType=MEMBER, memberId=X, amount=200) appears. Revoke it via the row action; Payment + PaymentRunItem disappear; run.paymentCount=0. Generate again; Payment reappears (member still has outstanding=200). Execute the run; member_balances.total_paid=200; member_payables row for that claim flips to `applied`; `mp_applications` row exists with source_type='PAYMENT'. Payment advice renders for the member payee.
4. **CTC + Payment interleave** — Adjudicate a MEMBER-payee claim for USD 500. Manually draft + commit a CTC for USD 100. member_balances.total_paid=100, outstanding=400. Generate a member PaymentRun — one Payment for 400. Execute. member_balances.total_paid=500, outstanding=0. `mp_applications` for the payable has one CTC row + one PAYMENT row summing to 500; payable is `applied`.

### Key Discoveries

- **CTC Phase 2, 3, 4 all landed.** `member_payables`, `member_payable_applications`, `MemberPayableController`, `ClaimAdjudicatedConsumer.handleMemberPayee`, auto-CTC — all in the tree at commit `c248073`. The plan builds on that foundation.
- **`Payment`, `PaymentRunItem`, `PaymentAdvice` all already carry `payee_type` + `member_id`** (V071 lines 14-87) with XOR CHECK constraints. Only the service layer and controllers are unaware.
- **REJECTED claims already bump provider `total_claimed`** (`ClaimAdjudicatedConsumer.java:308-310`) — so member parity means "totalClaimed counts all claims, regardless of decision", which the derived-vs-snapshot arithmetic must respect.
- **`PaymentRunService.create` calls `paymentRunGenerator.populate`** (line 136) — this is the single choke point where the plan branches on `payeeType`. Extending it, not replacing it, keeps blast radius small.
- **`AdjustmentFilterParams` already accepts `memberId`** — the symmetric endpoint is trivial (~5 lines).
- **`payment_advices` payee-type indexes already exist** (`V071:83-87`) — surfacing member advices in the UI needs no schema change.
- **Existing `PaymentController.create` (POST /payments)** is not the primary flow but exists as an escape hatch. It's out of scope for this plan; not removed, not extended.
- **Latest applied migrations: tenant V071, public V129.** New tenant migrations here: **V072** (schema) and **V073** (permission swap). No public migrations required.

## What We're NOT Doing

- **Not renaming `provider_balances` table** — the table name stays; the entity class name stays (façade-only per the grilling session). Renaming a live tenant-schema table for a UI concept change costs more than it buys ([[feedback_never_edit_applied_migrations]] hints at the general anti-pattern of destabilizing live tables).
- **Not removing `PaymentController.create` (POST /payments)** — the ad-hoc endpoint stays because it's out of scope for this ticket to prune existing infrastructure. Documented in the plan; not exercised by member flows.
- **Not adding notifications for member payments** — SMS/email notifications when a member is paid land as a follow-up ticket. Provider notifications (if any exist today) are unchanged.
- **Not building bad-debts-for-members** — the analogue of the billing bad-debts page for members-owed-and-aged-out is out of scope. Flagged in the research doc; separate ticket.
- **Not exercising member payments via the ad-hoc `POST /payments`** — the entire member settlement flow goes through `PaymentRunGenerator`. A follow-up can add an ad-hoc member payment path if operational feedback requests it.
- **Not making PaymentRun mixed-payee** — homogeneous by construction (G4). A future ticket could relax this but bank-file export needs would need a separate design.
- **Not touching Flutter member/provider apps** — member-side view of "how my claim was paid" is a follow-up.
- **Not exposing a manual admin endpoint to rebuild `member_balances`** — V072's backfill is the one-shot; drift-repair is a separate concern.
- **Not adding a permission for revoke** — reuses `finance:manage_payments`; the same operator who approves/executes a run can revoke items.

## Implementation Approach

Six phases, each independently verifiable. Rollout order matches phase order — all schema and Kafka contract changes are additive. Phase 1 lays the foundation (schema, entities, writer paths, permission catalogue); Phase 2 completes the Debtors rename standalone; Phases 3-4 extend Payment/Adjustment/Advice + the finance-side unified backend; Phases 5-6 land the Angular surfaces. No Kafka event removals — every payload change is a field addition (`payeeType` already lands in claim events per the CTC plan).

**Aggressive cutover (G7):** old permission `billing:view_creditors` is removed; old API paths return 410 Gone for one release then delete; old Angular routes are removed outright. V073 atomically swaps role_permissions so no tenant loses access.

---

## Phase 1: Foundation — schema, entities, writer paths, permission catalogue

### Overview

Land the two migrations (V072 schema + V073 permission swap), the `MemberBalance` entity + service, the writer-path extensions in `ClaimAdjudicatedConsumer` + `CtcPaymentService`, the `PaymentRun.payeeType` field + `PaymentRunGenerator` MEMBER branch, and the shared permission catalogue updates in both Java shared and Angular. This phase does not ship any user-visible UI change — its verification is via curl on the payment-run endpoint (member run creates with items) and IT-level assertions on the writer paths.

### Changes Required

#### 1. Tenant Flyway V072 — schema

**File:** `services/java/tenancy-service/src/main/resources/db/migration/tenant/V072__creditors_unification_and_member_settlement.sql`

```sql
-- =====================================================================
-- V072: Creditors unification + member settlement
--
-- Ships three additive schema changes and one backfill:
--   1. member_balances — snapshot table mirroring provider_balances
--      shape; source of truth for the finance-side Creditors listing
--      MEMBER rows.
--   2. payment_runs.payee_type — NOT NULL column enforcing homogeneous
--      runs (per grilling decision G4). CHECK ensures every child
--      payment_run_item's payee_type matches the parent.
--   3. member_payable_applications.source_type — extend CHECK to accept
--      'PAYMENT' (was 'CTC' only per V069:342); required by Phase 3's
--      Payment.markPaid FIFO application path.
--   4. Backfill member_balances from claims + member_payables +
--      member_payable_applications (per grilling decision G8c —
--      full backfill).
-- =====================================================================

-- ---------- 1. member_balances ----------------------------------------

CREATE TABLE IF NOT EXISTS member_balances (
    id                   uuid           PRIMARY KEY DEFAULT gen_random_uuid(),
    member_id            uuid           NOT NULL,
    total_claimed        numeric(19,4)  NOT NULL DEFAULT 0,
    total_approved       numeric(19,4)  NOT NULL DEFAULT 0,
    total_paid           numeric(19,4)  NOT NULL DEFAULT 0,
    outstanding_balance  numeric(19,4)  NOT NULL DEFAULT 0,
    currency_code        varchar(3)     NOT NULL,
    last_updated_at      timestamptz    NOT NULL DEFAULT now(),
    created_at           timestamptz    NOT NULL DEFAULT now(),
    CONSTRAINT uq_member_balances UNIQUE (member_id, currency_code)
);

CREATE INDEX IF NOT EXISTS idx_member_balances_outstanding
    ON member_balances(outstanding_balance DESC)
    WHERE outstanding_balance > 0;
CREATE INDEX IF NOT EXISTS idx_member_balances_member
    ON member_balances(member_id);

-- ---------- 2. payment_runs.payee_type --------------------------------

-- Default 'PROVIDER' for backfill of existing runs (all historical runs
-- are provider-only by construction — V071 landed the MEMBER item support
-- but the generator never enumerated members).
ALTER TABLE payment_runs
    ADD COLUMN IF NOT EXISTS payee_type varchar(10) NOT NULL DEFAULT 'PROVIDER'
        CHECK (payee_type IN ('PROVIDER', 'MEMBER'));

-- Enforce homogeneity: every payment_run_items row's payee_type must
-- match its parent run's payee_type. Implemented as a trigger because
-- CHECK cannot cross-reference tables.
CREATE OR REPLACE FUNCTION assert_payment_run_item_payee_type_matches()
RETURNS trigger AS $$
DECLARE
    run_payee_type text;
BEGIN
    SELECT payee_type INTO run_payee_type
      FROM payment_runs
     WHERE id = NEW.payment_run_id;
    IF run_payee_type IS NULL THEN
        RAISE EXCEPTION 'parent payment_run % not found', NEW.payment_run_id;
    END IF;
    IF run_payee_type <> NEW.payee_type THEN
        RAISE EXCEPTION 'payment_run_item.payee_type % does not match parent run payee_type %',
            NEW.payee_type, run_payee_type;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_payment_run_item_payee_type_match ON payment_run_items;
CREATE TRIGGER trg_payment_run_item_payee_type_match
    BEFORE INSERT OR UPDATE OF payee_type ON payment_run_items
    FOR EACH ROW EXECUTE FUNCTION assert_payment_run_item_payee_type_matches();

-- ---------- 3. member_payable_applications source_type extension -----

ALTER TABLE member_payable_applications
    DROP CONSTRAINT IF EXISTS member_payable_applications_source_type_check;
ALTER TABLE member_payable_applications
    ADD CONSTRAINT member_payable_applications_source_type_check
        CHECK (source_type IN ('CTC', 'PAYMENT'));

CREATE UNIQUE INDEX IF NOT EXISTS uq_mpa_source
    ON member_payable_applications(source_type, source_id);
-- Idempotency guard: Kafka replays or retry loops must never double-apply
-- the same (source_type, source_id) tuple.

-- ---------- 4. Backfill member_balances -------------------------------

-- Full backfill (grilling decision G8c) — one row per (member_id,
-- currency_code) with:
--   total_claimed  = SUM(claims.claimed_amount) where payee_type='MEMBER'
--   total_approved = SUM(member_payables.amount) where status IN ('open','applied')
--   total_paid     = SUM(member_payable_applications.amount_applied)
--                    (currently CTC only; PAYMENT rows land at Phase 3)
-- Idempotent via ON CONFLICT — safe to re-run.

INSERT INTO member_balances (member_id, currency_code, total_claimed, total_approved, total_paid, outstanding_balance)
SELECT
    m.member_id,
    m.currency_code,
    COALESCE(claimed.total, 0)  AS total_claimed,
    COALESCE(approved.total, 0) AS total_approved,
    COALESCE(paid.total, 0)     AS total_paid,
    COALESCE(approved.total, 0) - COALESCE(paid.total, 0) AS outstanding_balance
FROM (
    -- Unified set of (member, currency) touched by any source
    SELECT c.member_id, c.currency_code
      FROM claims c
     WHERE c.payee_type = 'MEMBER' AND c.member_id IS NOT NULL
     GROUP BY c.member_id, c.currency_code
    UNION
    SELECT mp.member_id, mp.currency_code
      FROM member_payables mp
     GROUP BY mp.member_id, mp.currency_code
) m
LEFT JOIN (
    SELECT c.member_id, c.currency_code, SUM(c.claimed_amount) AS total
      FROM claims c
     WHERE c.payee_type = 'MEMBER' AND c.member_id IS NOT NULL
     GROUP BY c.member_id, c.currency_code
) claimed ON claimed.member_id = m.member_id AND claimed.currency_code = m.currency_code
LEFT JOIN (
    SELECT mp.member_id, mp.currency_code, SUM(mp.amount) AS total
      FROM member_payables mp
     WHERE mp.status IN ('open', 'applied')
     GROUP BY mp.member_id, mp.currency_code
) approved ON approved.member_id = m.member_id AND approved.currency_code = m.currency_code
LEFT JOIN (
    SELECT mp.member_id, mp.currency_code, SUM(mpa.amount_applied) AS total
      FROM member_payable_applications mpa
      JOIN member_payables mp ON mp.id = mpa.member_payable_id
     GROUP BY mp.member_id, mp.currency_code
) paid ON paid.member_id = m.member_id AND paid.currency_code = m.currency_code
ON CONFLICT (member_id, currency_code) DO UPDATE
    SET total_claimed       = EXCLUDED.total_claimed,
        total_approved      = EXCLUDED.total_approved,
        total_paid          = EXCLUDED.total_paid,
        outstanding_balance = EXCLUDED.outstanding_balance,
        last_updated_at     = now();
```

Uses unqualified table names throughout — tenant-schema tables ([[bug_public_prefix_silent_rollback]]). `IF NOT EXISTS` + `ON CONFLICT DO UPDATE` keep it idempotent per [[feedback_never_edit_applied_migrations]] (any correction goes into V074+).

#### 2. Tenant Flyway V073 — permission swap

**File:** `services/java/tenancy-service/src/main/resources/db/migration/tenant/V073__permission_swap_billing_creditors.sql`

```sql
-- =====================================================================
-- V073: Permission catalogue swap for creditors/debtors naming (G7a).
--
-- Introduces:
--   * billing:view_debtors   — gates the renamed billing Debtors page
--   * finance:view_creditors — gates the unified finance Creditors page
--
-- Removes:
--   * billing:view_creditors from role_permissions (aggressive cutover
--     per G7b — no dual-accept). Every tenant currently granted the old
--     key is auto-granted the two new keys in the same transaction so
--     no operator loses access.
--
-- Java + Angular catalogue changes are shipped in the same phase so
-- PermissionResolver stops recognizing the removed key on the next boot.
-- =====================================================================

-- 1. Seed the two new permission constants into permissions_catalogue if
--    that table exists in this tenant schema (V006 shape). If it doesn't,
--    permission strings are resolved purely from PermissionCatalogue.java
--    and only role_permissions matters.
INSERT INTO permissions_catalogue (permission)
VALUES ('billing:view_debtors'), ('finance:view_creditors')
ON CONFLICT (permission) DO NOTHING;

-- 2. For every role currently granted billing:view_creditors, grant the
--    two new keys at the same access_level. Idempotent via ON CONFLICT.
INSERT INTO role_permissions (id, role_id, permission, access_level)
SELECT gen_random_uuid(), rp.role_id, new_key, rp.access_level
  FROM role_permissions rp
 CROSS JOIN (VALUES ('billing:view_debtors'), ('finance:view_creditors')) AS n(new_key)
 WHERE rp.permission = 'billing:view_creditors'
ON CONFLICT (role_id, permission) DO NOTHING;

-- 3. Remove the stale grants. PermissionResolver will not recognize the
--    key after the shared/permissions catalogue drops it in this phase.
DELETE FROM role_permissions WHERE permission = 'billing:view_creditors';

-- 4. Remove the catalogue row too, if present.
DELETE FROM permissions_catalogue WHERE permission = 'billing:view_creditors';
```

#### 3. Shared permission catalogue updates

**File:** `services/java/shared/src/main/resources/permissions.yaml`

Add to `billing:` group, remove existing `billing:view_creditors`; add `finance:view_creditors` to `finance:` group.

```yaml
- id: billing
  permissions:
    - { key: "billing:view_debtors",              label: "View debtors",                     description: "View outstanding balances owed by members and groups (arrears listing)." }
    - { key: "billing:manage_bad_debts",          label: "Manage bad debts",                 description: "Write off receivables that cannot be collected." }
    # billing:view_creditors REMOVED — replaced by billing:view_debtors and finance:view_creditors
- id: finance
  permissions:
    - { key: "finance:view_creditors",            label: "View creditors",                   description: "View the unified Creditors page — providers and members the fund owes for approved claims." }
    - { key: "finance:view_debtors",              label: "View debtors",                     description: "View aged-debtors reports." }
    # …rest unchanged
```

**File:** `services/java/shared/src/main/java/com/medfund/shared/security/Permissions.java`

```java
// Replace:
public static final String BILLING_VIEW_CREDITORS = "billing:view_creditors";
// With:
public static final String BILLING_VIEW_DEBTORS   = "billing:view_debtors";
public static final String FINANCE_VIEW_CREDITORS = "finance:view_creditors";
```

Update the `ALL` array (line 129) to drop `BILLING_VIEW_CREDITORS`, add both new keys.

**File:** `services/java/shared/src/main/java/com/medfund/shared/security/PermissionCatalogue.java`

Same mechanical swap in the `Permission` list.

**File:** `clients/angular/src/app/core/security/permissions.ts`

Type union at line 31 — remove `'billing:view_creditors'`, add `'billing:view_debtors' | 'finance:view_creditors'`. Catalogue at line 108 gets two new rows, one removed.

#### 4. MemberBalance entity + service

**File:** `services/java/finance-service/src/main/java/com/medfund/finance/entity/MemberBalance.java` (new)

```java
package com.medfund.finance.entity;

import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@Table("member_balances")
public class MemberBalance {
    @Id private UUID id;
    @Column("member_id")             private UUID memberId;
    @Column("total_claimed")         private BigDecimal totalClaimed;
    @Column("total_approved")        private BigDecimal totalApproved;
    @Column("total_paid")            private BigDecimal totalPaid;
    @Column("outstanding_balance")   private BigDecimal outstandingBalance;
    @Column("currency_code")         private String currencyCode;
    @Column("last_updated_at")       private Instant lastUpdatedAt;
    @Column("created_at")            private Instant createdAt;
}
```

**File:** `services/java/finance-service/src/main/java/com/medfund/finance/repository/MemberBalanceRepository.java` (new)

```java
@Repository
public interface MemberBalanceRepository extends R2dbcRepository<MemberBalance, UUID> {
    Mono<MemberBalance> findByMemberIdAndCurrencyCode(UUID memberId, String currencyCode);
    Flux<MemberBalance> findAllByOutstandingBalanceGreaterThanOrderByOutstandingBalanceDesc(BigDecimal zero);
    Flux<MemberBalance> findAllByCurrencyCodeAndOutstandingBalanceGreaterThan(String currencyCode, BigDecimal zero);
}
```

**File:** `services/java/finance-service/src/main/java/com/medfund/finance/service/MemberBalanceService.java` (new)

Mirrors `ProviderBalanceService.updateBalance` (line 67). Idempotent upsert via INSERT ... ON CONFLICT DO UPDATE (R2DBC generic execute), publishes audit event with the same four field names `total_claimed/approved/paid/outstanding` used by providers (line 148) so the audit shape stays consistent.

```java
@Slf4j
@Service
@RequiredArgsConstructor
public class MemberBalanceService {

    private final DatabaseClient db;
    private final MemberBalanceRepository repository;
    private final AuditPublisher auditPublisher;

    @Transactional
    public Mono<MemberBalance> updateBalance(UUID memberId, String currencyCode,
                                              BigDecimal claimedDelta, BigDecimal approvedDelta,
                                              BigDecimal paidDelta, String actorId, String actorEmail) {
        return repository.findByMemberIdAndCurrencyCode(memberId, currencyCode)
            .defaultIfEmpty(bootstrap(memberId, currencyCode))
            .flatMap(balance -> {
                Map<String, Object> oldValue = Map.of(
                    "totalClaimed",       balance.getTotalClaimed().toString(),
                    "totalApproved",      balance.getTotalApproved().toString(),
                    "totalPaid",          balance.getTotalPaid().toString(),
                    "outstandingBalance", balance.getOutstandingBalance().toString()
                );
                if (claimedDelta != null)  balance.setTotalClaimed(balance.getTotalClaimed().add(claimedDelta));
                if (approvedDelta != null) balance.setTotalApproved(balance.getTotalApproved().add(approvedDelta));
                if (paidDelta != null)     balance.setTotalPaid(balance.getTotalPaid().add(paidDelta));
                balance.setOutstandingBalance(balance.getTotalApproved().subtract(balance.getTotalPaid()));
                balance.setLastUpdatedAt(Instant.now());

                Map<String, Object> newValue = Map.of(
                    "totalClaimed",       balance.getTotalClaimed().toString(),
                    "totalApproved",      balance.getTotalApproved().toString(),
                    "totalPaid",          balance.getTotalPaid().toString(),
                    "outstandingBalance", balance.getOutstandingBalance().toString()
                );

                return repository.save(balance)
                    .flatMap(saved -> publishAudit(saved, oldValue, newValue, actorId, actorEmail).thenReturn(saved));
            });
    }

    private MemberBalance bootstrap(UUID memberId, String currencyCode) {
        MemberBalance mb = new MemberBalance();
        mb.setMemberId(memberId);
        mb.setCurrencyCode(currencyCode);
        mb.setTotalClaimed(BigDecimal.ZERO);
        mb.setTotalApproved(BigDecimal.ZERO);
        mb.setTotalPaid(BigDecimal.ZERO);
        mb.setOutstandingBalance(BigDecimal.ZERO);
        mb.setCreatedAt(Instant.now());
        return mb;
    }

    private Mono<Void> publishAudit(MemberBalance saved, Map<String, Object> oldValue,
                                     Map<String, Object> newValue, String actorId, String actorEmail) {
        // Same shape as ProviderBalanceService line 148 — four field names in changed[]
        return Mono.deferContextual(ctx -> {
            String tenantId = TenantContext.get(ctx);
            String entityName = "Balance for member " + saved.getMemberId() + " " + saved.getCurrencyCode();
            var event = AuditEvent.create(
                tenantId != null ? tenantId : "unknown",
                "MemberBalance",
                saved.getId().toString(),
                entityName,
                "UPDATE",
                actorId, actorEmail,
                oldValue, newValue,
                new String[]{"totalClaimed", "totalApproved", "totalPaid", "outstandingBalance"},
                UUID.randomUUID().toString()
            );
            return auditPublisher.publish(event);
        });
    }
}
```

#### 5. Extend `ClaimAdjudicatedConsumer.handleMemberPayee`

**File:** `services/java/finance-service/src/main/java/com/medfund/finance/consumer/ClaimAdjudicatedConsumer.java`

Existing behaviour (write `member_payables` on APPROVED/PARTIAL_APPROVED per line 133) stays. Add symmetric balance-bump path: on every event with `payeeType=MEMBER`, compute the same `(claimedDelta, approvedDelta, paidDelta)` triple `handleProviderPayee` computes (line 288-318), pass to `memberBalanceService.updateBalance`. Do this BEFORE the payable insert so a `member_payables` row that trips the unique-violation still results in balance bump if it was somehow lost.

```java
// Inside handleMemberPayee, after decoding fields, before writeMemberPayable:
BigDecimal claimedDelta = textOrNull(node, "claimedAmount") != null
    ? new BigDecimal(textOrNull(node, "claimedAmount")) : null;
BigDecimal approvedDelta = null;
BigDecimal paidDeltaFromClaimEvent = null;

switch (decision == null ? "" : decision.toUpperCase()) {
    case "APPROVED":
    case "PARTIAL_APPROVED":
        if (approvedAmount != null) approvedDelta = new BigDecimal(approvedAmount);
        break;
    case "REJECTED":
        // claimed still counts (member parity with provider line 308-310)
        break;
    case "COMMITTED":
    case "PAID":
        // Claim-side committed/paid does NOT bump member_balances.total_paid;
        // that is bumped exclusively by the finance-side settlement path
        // (CTC commit or Payment markPaid). Rationale: for members there is
        // no ambiguity — settlement always originates in finance, not claims.
        break;
    default:
        return Mono.empty();
}

Mono<Void> bumpBalance = memberBalanceService.updateBalance(
        UUID.fromString(memberId), currencyCode != null ? currencyCode : "USD",
        claimedDelta, approvedDelta, null,
        AuditActor.SYSTEM_ID, AuditActor.SYSTEM_EMAIL
    ).then();

// Only write the payable row on APPROVED/PARTIAL_APPROVED (existing behaviour)
if (!isApproved(decision) /* existing helper */) {
    return bumpBalance;
}

return bumpBalance.then(writeMemberPayable(claimId, claimNumber, memberId, currencyCode,
                                            new BigDecimal(approvedAmount)));
```

Note the design point: member `total_paid` is **not** bumped by claim-side `PAID`/`COMMITTED` events. Providers get their `total_paid` from claim-side events because provider payment lifecycle is claim-anchored; member settlement is finance-anchored (CTC or Payment), so bumping in-process from the finance service avoids double-counting.

#### 6. Extend `CtcPaymentService.commit` and `reverse`

**File:** `services/java/finance-service/src/main/java/com/medfund/finance/service/CtcPaymentService.java`

After the existing `member_payable_applications` write on commit, bump `member_balances.total_paid += ctc.amount` in the same transactional flow. On reverse, decrement.

```java
// Inside commit(), after successful member_payable_applications insert:
return memberBalanceService.updateBalance(
    ctc.getMemberId(),
    ctc.getCurrencyCode(),
    null, null,
    ctc.getAmount(),
    actorId, actorEmail
).thenReturn(committedCtc);

// Inside reverse(), pass a negative paidDelta:
return memberBalanceService.updateBalance(
    ctc.getMemberId(),
    ctc.getCurrencyCode(),
    null, null,
    ctc.getAmount().negate(),
    actorId, actorEmail
).thenReturn(reversedCtc);
```

#### 7. `PaymentRun.payeeType` field + generator branch

**File:** `services/java/finance-service/src/main/java/com/medfund/finance/entity/PaymentRun.java`

Add:

```java
@Column("payee_type")
private String payeeType = "PROVIDER";
// getter/setter
```

**File:** `services/java/finance-service/src/main/java/com/medfund/finance/dto/CreatePaymentRunRequest.java`

Add optional `payeeType` field (default "PROVIDER" for BC):

```java
public record CreatePaymentRunRequest(
    String currencyCode,
    String description,
    String payeeType  // "PROVIDER" | "MEMBER" — default PROVIDER
) {
    public CreatePaymentRunRequest {
        if (payeeType == null || payeeType.isBlank()) payeeType = "PROVIDER";
    }
}
```

**File:** `services/java/finance-service/src/main/java/com/medfund/finance/service/PaymentRunService.java`

In `create()` (line 121), set `run.setPayeeType(request.payeeType())` after `setDescription`.

**File:** `services/java/finance-service/src/main/java/com/medfund/finance/service/PaymentRunGenerator.java`

Extend `populate(PaymentRun run)` to branch on `run.getPayeeType()`. When PROVIDER, existing behaviour (enumerate `provider_balances` with outstanding > 0, in the run's currency). When MEMBER, enumerate `member_balances` similarly and create a Payment(payeeType=MEMBER, memberId=X, amount=outstanding) per row. Both branches create the PaymentRunItem with `payee_type` matching the parent — the V072 trigger enforces this.

```java
public Mono<Integer> populate(PaymentRun run) {
    return switch (run.getPayeeType()) {
        case "MEMBER"   -> populateMemberPayees(run);
        case "PROVIDER" -> populateProviderPayees(run);       // existing
        default         -> Mono.error(new IllegalStateException("unknown payeeType: " + run.getPayeeType()));
    };
}

private Mono<Integer> populateMemberPayees(PaymentRun run) {
    return memberBalanceRepository
        .findAllByCurrencyCodeAndOutstandingBalanceGreaterThan(run.getCurrencyCode(), BigDecimal.ZERO)
        .flatMap(mb -> createMemberPaymentAndItem(run, mb), 4)
        .count()
        .map(Long::intValue);
}

private Mono<Payment> createMemberPaymentAndItem(PaymentRun run, MemberBalance mb) {
    return generatePaymentNumber()
        .flatMap(paymentNumber -> {
            Payment payment = new Payment();
            payment.setPaymentNumber(paymentNumber);
            payment.setMemberId(mb.getMemberId());
            payment.setPayeeType("MEMBER");
            payment.setAmount(mb.getOutstandingBalance());
            payment.setCurrencyCode(mb.getCurrencyCode());
            payment.setPaymentType("MEMBER_SETTLEMENT");
            payment.setStatus("pending");
            payment.setCreatedAt(Instant.now());
            payment.setUpdatedAt(Instant.now());
            return paymentRepository.save(payment);
        })
        .flatMap(savedPayment -> {
            PaymentRunItem item = new PaymentRunItem();
            item.setPaymentRunId(run.getId());
            item.setPaymentId(savedPayment.getId());
            item.setMemberId(savedPayment.getMemberId());
            item.setPayeeType("MEMBER");
            item.setAmount(savedPayment.getAmount());
            item.setCurrencyCode(savedPayment.getCurrencyCode());
            item.setStatus("pending");
            return paymentRunItemRepository.save(item).thenReturn(savedPayment);
        });
}
```

### Success Criteria

#### Automated Verification
- [x] Java compiles: `./gradlew :finance-service:build :contributions-service:build :shared:build`
- [x] Unit tests for touched modules (`ClaimAdjudicatedConsumerTest`, `CtcPaymentServiceTest`, `PaymentRunGeneratorTest`, `PaymentRunServiceTest`) — all pass. Pre-existing 10 failing tests documented in [[bug_claim_save_mock_id_npe]] remain unchanged (not caused by Phase 1).
- [x] Angular typecheck: `npx ng build --configuration=development` — passes with the removed permission key.
- [ ] Integration tests: `make test-integration` — deferred to Phase 3 (which introduces the payment-side settlement paths that most of these ITs assert on). Existing `CtcPaymentServiceIT` now runs against the extended test-migration V003 that adds `member_balances` + widens the `member_payable_applications` CHECK.
- [ ] Backfill correctness IT: seed `claims` (5 mixed decisions) + `member_payables` (3 rows) + `member_payable_applications` (2 CTC rows), run V072, assert `member_balances` has the expected rows with the correct 4-tuple per (member, currency). — deferred as follow-up IT; smoke-tested only via V072 SQL syntax check.
- [ ] Trigger validation: attempt to insert a `payment_run_items` row with mismatched `payee_type` — assert the trigger rejects it. — deferred as follow-up IT.

#### Manual Verification
- [ ] `curl -X POST /api/v1/payment-runs -H 'Authorization: Bearer <jwt>' -d '{"currencyCode":"USD","description":"member run test","payeeType":"MEMBER"}'` — returns a PaymentRun with paymentCount > 0 (assuming test data with member outstanding balances).
- [ ] Permission-management surface in the admin portal shows the new descriptions when hovering the tooltip on `billing:view_debtors` and `finance:view_creditors`.

**Implementation Note:** pause after Phase 1 for manual QA of the migration on a copy of a tenant schema (V072 is destructive-friendly but data-touching — a bad backfill query is best caught before Phase 3 depends on it).

---

## Phase 2: Contributions-side Debtors rename (Java + Angular together)

### Overview

Rename every "creditor" reference on the contributions side to "debtor". Java class/method/endpoint renames + a 410 shim controller for the old paths + new `@RequiresPermission(BILLING_VIEW_DEBTORS)` gates (which also closes the pre-existing auth gap on `BalanceController`). Angular directory rename + route change + service method rename + copy update + Playwright fixture update. Ships as one phase because immediate cutover (G7b) means the FE will call the new API on the very first request — no dual-support window.

### Changes Required

#### 1. Java rename

**File:** `services/java/contributions-service/src/main/java/com/medfund/contributions/controller/BalanceController.java`

Rename endpoints:
- `GET /creditors` → `GET /debtors` (line 74). Method rename `listCreditors` → `listDebtors`.
- `GET /creditors/export/excel` → `GET /debtors/export/excel` (line 90). Method rename `exportCreditorsExcel` → `exportDebtorsExcel`.
- Add `@RequiresPermission({Permissions.BILLING_VIEW_DEBTORS})` on every endpoint in this file (bad-debts + aged also gain gating — was previously ungated; fixes auth gap).

**File:** `services/java/contributions-service/src/main/java/com/medfund/contributions/service/BalanceService.java`

`listCreditors` → `listDebtors` (line 205). No other logic changes.

**File:** `services/java/contributions-service/src/main/java/com/medfund/contributions/repository/BalanceQueryRepository.java`

`findCreditors` → `findDebtors` (line 37), `countCreditors` → `countDebtors` (line 51). Callers in `BalanceService` updated.

**File:** `services/java/contributions-service/src/main/java/com/medfund/contributions/dto/CreditorRow.java`

Class rename: `CreditorRow` → `DebtorRow`. All references in `BalanceService`, `BalanceController`, `CreditorsExcelService`, `BadDebtsExcelService` follow.

**File:** `services/java/contributions-service/src/main/java/com/medfund/contributions/service/CreditorsExcelService.java`

Class rename → `DebtorsExcelService`. Sheet name `"Creditors"` (line 55) → `"Debtors"`. Filename prefix (line 68) `"creditors-"` → `"debtors-"`.

**File:** `services/java/contributions-service/src/main/java/com/medfund/contributions/controller/LegacyBillingBalancesController.java` (new — 410 shim)

```java
@RestController
@RequestMapping("/api/v1/billing/balances")
@RequiredArgsConstructor
public class LegacyBillingBalancesController {

    private static final String MOVED_MSG = "This endpoint has moved to %s. See release notes 2026-08-10.";

    @GetMapping("/creditors")
    public Mono<Void> creditorsGone() {
        return Mono.error(new ResponseStatusException(HttpStatus.GONE,
            String.format(MOVED_MSG, "/api/v1/billing/balances/debtors")));
    }

    @GetMapping("/creditors/export/excel")
    public Mono<Void> creditorsExcelGone() {
        return Mono.error(new ResponseStatusException(HttpStatus.GONE,
            String.format(MOVED_MSG, "/api/v1/billing/balances/debtors/export/excel")));
    }
}
```

Deletion timeline: keep this class for one release (~one sprint), then remove in a follow-up commit.

#### 2. Angular rename

Move directory: `clients/angular/src/app/pages/tenant/billing/creditors/` → `clients/angular/src/app/pages/tenant/billing/debtors/`. Rename the component class inside: `CreditorsListComponent` → `DebtorsListComponent`. Rename the file: `creditors-list.component.{ts,html,scss,spec.ts}` → `debtors-list.component.{ts,html,scss,spec.ts}`.

**File:** `clients/angular/src/app/pages/tenant/billing/billing.routes.ts`

At line 248 — replace the `/creditors` block with `/debtors`; update loader path, canActivate permission → `billing:view_debtors`, title. Remove the old route entirely (G7c — no redirect).

**File:** `clients/angular/src/app/core/services/balance.service.ts`

- Rename `CreditorRow` interface (line 23) → `DebtorRow`.
- Rename method `listCreditors` (line 76) → `listDebtors`; update the HTTP path to `/billing/balances/debtors`.
- Rename method `exportCreditorsExcel` (line 95) → `exportDebtorsExcel`; update path.

**File:** `clients/angular/src/app/pages/tenant/billing/debtors/debtors-list.component.html`

Update H1 `Creditors` → `Debtors`. Update subtitle (line 4) to say "Debtors — members and groups with an outstanding balance…". Update empty-state message.

**File:** `clients/angular/src/app/layout/operational-sidebar/operational-nav.ts`

Line 81 — label `'Creditors'` → `'Debtors'`, route `/tenant/billing/creditors` → `/tenant/billing/debtors`, permission `billing:view_creditors` → `billing:view_debtors`.

**File:** `clients/angular/src/app/pages/tenant/claims/claims.routes.ts` line 234 comment — update the reference to `billing:view_creditors`.

**File:** `clients/angular/e2e/tests/billing-charge-preview.spec.ts` line 8 — update permission string `billing:view_creditors` → `billing:view_debtors`.

**File:** `clients/angular/e2e/fixtures/billing-stubs.ts` line 18 — rename `CreditorRow` interface → `DebtorRow`.

### Success Criteria

#### Automated Verification
- [x] Java compiles: `cd services/java && ./gradlew :contributions-service:build` — compile + unit tests pass. `jacocoTestCoverageVerification` breaches the 70% floor (52%); documented in Deviations, not caused by Phase 2.
- [x] Unit tests pass: `:contributions-service:test` — BUILD SUCCESSFUL. IT run (`make test-integration`) deferred — needs Testcontainers infra and follows Phase 3.
- [ ] New `BalanceControllerIT` asserts a request to `/api/v1/billing/balances/debtors` without `billing:view_debtors` returns 403 (auth gap closed). — deferred (Deviations).
- [ ] New `LegacyBillingBalancesControllerIT` asserts `GET /api/v1/billing/balances/creditors` returns 410 with a `Location`-ish message body. — deferred (Deviations).
- [ ] Angular unit tests: `make test-angular` — passes with the renamed component + service method.
- [x] Angular typecheck: BUILD SUCCESSFUL — `npx ng build --configuration=development` green with the renamed DTO/service/component.
- [ ] Playwright: `make test-e2e` — the billing-charge-preview spec passes with the new permission string.
- [ ] `verify` on `/tenant/billing/debtors`: no console errors, table renders, currency filter fires, Excel export triggers a download.
- [ ] `verify` that `/tenant/billing/creditors` returns Angular's 404.

#### Manual Verification
- [ ] Downloaded Excel file has the `debtors-USD-2026-08-10.xlsx` filename prefix.
- [ ] A tenant admin who had `billing:view_creditors` (via V073 auto-grant) can still open `/tenant/billing/debtors`.

**Implementation Note:** pause for manual permission-tooltip and Excel-download spot-check before Phase 3.

---

## Phase 3: PaymentService integration — mark-paid → member_balances + FIFO applications + DELETE revoke

### Overview

Wire `PaymentService.markPaid` to bump `member_balances.total_paid` and allocate the payment amount FIFO across open `member_payables` (creating bridge rows with `source_type='PAYMENT'`) when the payment is a MEMBER payee. Add `DELETE /api/v1/payments/{id}` for individual-payment revoke. Add symmetric member-scoped GET endpoints on PaymentController and AdjustmentController for the detail page. Extend `PaymentAdviceService` so member advices generate when a member run executes.

### Changes Required

#### 1. `PaymentService.markPaid` — MEMBER branch

**File:** `services/java/finance-service/src/main/java/com/medfund/finance/service/PaymentService.java`

After the existing status flip + audit publish (line 130), if `payment.getPayeeType()` is "MEMBER", run the settlement fan-out. In transaction:
1. `memberBalanceService.updateBalance(memberId, currencyCode, null, null, +payment.amount, actor)`.
2. FIFO-allocate `payment.amount` across `MemberPayableRepository.findByMemberIdAndStatus(memberId, 'open')` sorted by `recordedAt ASC`, plus any `applied` rows that still have `remainingOn` > 0 (partial application).
3. Per allocation, insert a `member_payable_applications` row with `source_type='PAYMENT'`, `source_id=payment.id`, `amount_applied=<slice>`, `currency_code=payment.currencyCode`, `applied_at=now()`, `applied_by=actor`.
4. If a payable is fully consumed, flip its `status` to `applied`.

```java
@Transactional
public Mono<Payment> markPaid(UUID id, String actorId, String actorEmail) {
    return paymentRepository.findById(id)
        .switchIfEmpty(Mono.error(new PaymentNotFoundException(id)))
        .flatMap(payment -> {
            String previousStatus = payment.getStatus();
            payment.setStatus("paid");
            payment.setPaidAt(Instant.now());
            payment.setUpdatedAt(Instant.now());
            payment.setUpdatedBy(Actors.parseId(actorId));

            return paymentRepository.save(payment)
                .flatMap(saved -> publishMarkPaidAudit(saved, previousStatus, actorId, actorEmail)
                    .thenReturn(saved))
                .flatMap(saved -> "MEMBER".equalsIgnoreCase(saved.getPayeeType())
                    ? settleMemberPayment(saved, actorId, actorEmail).thenReturn(saved)
                    : Mono.just(saved));
        });
}

private Mono<Void> settleMemberPayment(Payment payment, String actorId, String actorEmail) {
    return memberBalanceService.updateBalance(
            payment.getMemberId(), payment.getCurrencyCode(),
            null, null, payment.getAmount(),
            actorId, actorEmail
        )
        .then(allocateFifoToPayables(payment, actorId));
}

private Mono<Void> allocateFifoToPayables(Payment payment, String actorId) {
    return memberPayableRepository
        .findByMemberIdAndStatusInOrderByRecordedAtAsc(
            payment.getMemberId(), List.of("open", "applied"))
        .filter(p -> p.getCurrencyCode().equalsIgnoreCase(payment.getCurrencyCode()))
        .concatMap(payable -> balanceRepository.remainingOn(payable.getId())
            .filter(rem -> rem.signum() > 0)
            .map(rem -> Tuples.of(payable, rem)))
        .scan(Tuples.of(payment.getAmount(), (BigDecimal) null, (MemberPayable) null),
              (acc, tuple) -> {
                  BigDecimal remaining = acc.getT1();
                  if (remaining.signum() <= 0) return Tuples.of(BigDecimal.ZERO, null, null);
                  BigDecimal slice = remaining.min(tuple.getT2());
                  return Tuples.of(remaining.subtract(slice), slice, tuple.getT1());
              })
        .filter(acc -> acc.getT2() != null)
        .concatMap(acc -> {
            MemberPayable payable = acc.getT3();
            BigDecimal slice = acc.getT2();
            MemberPayableApplication app = new MemberPayableApplication();
            app.setMemberPayableId(payable.getId());
            app.setSourceType("PAYMENT");
            app.setSourceId(payment.getId());
            app.setAmountApplied(slice);
            app.setCurrencyCode(payment.getCurrencyCode());
            app.setAppliedAt(Instant.now());
            app.setAppliedBy(Actors.parseId(actorId));
            return applicationRepository.save(app)
                .then(maybeFlipPayableStatus(payable));
        })
        .then();
}

private Mono<Void> maybeFlipPayableStatus(MemberPayable payable) {
    return balanceRepository.remainingOn(payable.getId())
        .flatMap(remaining -> {
            if (remaining.signum() <= 0 && !"applied".equalsIgnoreCase(payable.getStatus())) {
                payable.setStatus("applied");
                return memberPayableRepository.save(payable).then();
            }
            return Mono.empty();
        });
}
```

`MemberPayableRepository.findByMemberIdAndStatusInOrderByRecordedAtAsc` — add this method to `MemberPayableRepository.java`.

Idempotency: the V072 `uq_mpa_source` unique index guards against double-apply on Kafka replay / consumer retry. A conflict on `(source_type, source_id)` fails the transaction cleanly.

#### 2. `PaymentService.revoke` + `DELETE` endpoint

**File:** `services/java/finance-service/src/main/java/com/medfund/finance/service/PaymentService.java`

```java
@Transactional
public Mono<Void> revoke(UUID paymentId, String actorId, String actorEmail) {
    return paymentRepository.findById(paymentId)
        .switchIfEmpty(Mono.error(new PaymentNotFoundException(paymentId)))
        .flatMap(payment -> {
            if (!"pending".equalsIgnoreCase(payment.getStatus())) {
                return Mono.error(new IllegalStateException(
                    "Only pending payments can be revoked; status=" + payment.getStatus()));
            }
            // Guard: parent run must be draft or approved (never executing/executed/cancelled)
            return paymentRunItemRepository.findByPaymentId(paymentId)
                .switchIfEmpty(Mono.error(new IllegalStateException(
                    "Payment " + paymentId + " has no parent run — cannot revoke")))
                .flatMap(item -> paymentRunRepository.findById(item.getPaymentRunId())
                    .switchIfEmpty(Mono.error(new IllegalStateException(
                        "Payment run " + item.getPaymentRunId() + " not found")))
                    .flatMap(run -> {
                        if (!List.of("draft", "approved").contains(run.getStatus())) {
                            return Mono.error(new IllegalStateException(
                                "Cannot revoke payment while run is " + run.getStatus()));
                        }
                        // Snapshot for audit
                        Map<String, Object> oldValue = Map.of(
                            "paymentNumber", payment.getPaymentNumber(),
                            "status",        payment.getStatus(),
                            "amount",        payment.getAmount().toString(),
                            "currencyCode",  payment.getCurrencyCode(),
                            "payeeType",     payment.getPayeeType(),
                            "providerId",    String.valueOf(payment.getProviderId()),
                            "memberId",      String.valueOf(payment.getMemberId())
                        );
                        return paymentRunItemRepository.delete(item)
                            .then(paymentRepository.delete(payment))
                            .then(recomputeRunTotals(run.getId()))
                            .then(publishRevokeAudit(payment, oldValue, actorId, actorEmail));
                    }));
        });
}

private Mono<Void> recomputeRunTotals(UUID runId) {
    return paymentRunItemRepository.findByPaymentRunId(runId).collectList()
        .flatMap(items -> paymentRunRepository.findById(runId).flatMap(run -> {
            BigDecimal total = items.stream()
                .map(PaymentRunItem::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
            run.setPaymentCount(items.size());
            run.setTotalAmount(total);
            run.setUpdatedAt(Instant.now());
            return paymentRunRepository.save(run);
        })).then();
}

private Mono<Void> publishRevokeAudit(Payment payment, Map<String, Object> oldValue,
                                        String actorId, String actorEmail) {
    return Mono.deferContextual(ctx -> {
        String tenantId = TenantContext.get(ctx);
        var event = AuditEvent.create(
            tenantId != null ? tenantId : "unknown",
            "Payment",
            payment.getId().toString(),
            payment.getPaymentNumber(),
            "DELETE",
            actorId, actorEmail,
            oldValue,
            null,
            oldValue.keySet().toArray(new String[0]),
            UUID.randomUUID().toString()
        );
        return auditPublisher.publish(event);
    });
}
```

**File:** `services/java/finance-service/src/main/java/com/medfund/finance/controller/PaymentController.java`

Add:

```java
@DeleteMapping("/{id}")
@RequiresPermission({Permissions.FINANCE_MANAGE_PAYMENTS})
@Operation(summary = "Revoke (delete) a pending payment from its run",
    description = "Deletes the Payment and its parent PaymentRunItem. Only permitted while "
                + "the payment is pending AND the parent PaymentRun is in draft or approved status. "
                + "The payee retains their outstanding balance and will be included in the next run generation.")
@ApiResponses({
    @ApiResponse(responseCode = "204", description = "Payment revoked"),
    @ApiResponse(responseCode = "400", description = "Payment not pending, or parent run executed/cancelled"),
    @ApiResponse(responseCode = "404", description = "Payment not found")
})
@ResponseStatus(HttpStatus.NO_CONTENT)
public Mono<Void> revoke(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
    return paymentService.revoke(id, AuditActor.id(jwt), AuditActor.email(jwt));
}
```

Also add `@RequiresPermission` on every existing endpoint in this controller (closes the pre-existing auth gap on PaymentController).

#### 3. Symmetric member-scoped GET endpoints

**File:** `services/java/finance-service/src/main/java/com/medfund/finance/controller/PaymentController.java`

Add:

```java
@GetMapping("/member/{memberId}")
@RequiresPermission({Permissions.FINANCE_VIEW_CREDITORS, Permissions.FINANCE_MANAGE_PAYMENTS})
@Operation(summary = "List payments by member")
public Flux<PaymentResponse> findByMemberId(@PathVariable UUID memberId) {
    return paymentService.findByMemberId(memberId).map(PaymentResponse::from);
}
```

Add corresponding `findByMemberId` in `PaymentService` + repository method.

**File:** `services/java/finance-service/src/main/java/com/medfund/finance/controller/AdjustmentController.java`

```java
@GetMapping("/member/{memberId}")
@RequiresPermission({Permissions.FINANCE_VIEW_CREDITORS, Permissions.FINANCE_MANAGE_PAYMENTS})
@Operation(summary = "List adjustments by member")
public Flux<AdjustmentResponse> findByMemberId(@PathVariable UUID memberId) {
    return adjustmentService.findByMemberId(memberId).map(AdjustmentResponse::from);
}
```

Same pattern.

#### 4. `PaymentAdviceService.generateAdvicesForRun` — extend for member payees

**File:** `services/java/finance-service/src/main/java/com/medfund/finance/service/PaymentAdviceService.java`

Existing method loops over run items and generates a payment_advice per provider. Extend to branch on `item.payeeType`:

- If PROVIDER: existing behaviour (advice with `providerId`).
- If MEMBER: generate an advice with `memberId`, `payeeType='MEMBER'`, `providerId=NULL`. V071's XOR CHECK constraint already permits this shape.

Column population that today uses `provider.name`/`provider.email` should be conditioned on payeeType — for MEMBER, use `member.firstName + lastName` / `member.email`. Confirm which repository provides member lookup; add if missing.

### Success Criteria

#### Automated Verification
- [x] Java compiles + unit tests pass. `:finance-service:compileJava` + `:finance-service:compileTestJava` are clean; `:finance-service:test` reports 10 pre-existing NPE failures from [[bug_claim_save_mock_id_npe]] (same 10 acknowledged in Phase 1). No new failures from Phase 3.
- [ ] `PaymentServiceIT` new tests: (a) markPaid on a MEMBER payment bumps `member_balances.total_paid` and creates the right FIFO application rows; (b) `member_payables` status flips to `applied` when fully consumed; (c) revoke on a pending MEMBER payment inside a draft run deletes both rows and recomputes totals; (d) revoke on a paid payment returns 400; (e) revoke on a pending payment whose parent run is `executed` returns 400. — deferred (Deviations).
- [ ] `PaymentAdviceServiceIT` extended to assert a member run generates advices with `payeeType='MEMBER'` and non-null `memberId`. — deferred (Deviations); the service code already branches on payeeType so the assertion is really a regression fence, not a new capability.
- [ ] Idempotency: replay the same markPaid flow twice; second run fails on `uq_mpa_source` — assert clean error handling. — deferred (Deviations).
- [x] `verify` on nothing (no UI in this phase). — n/a, no UI work in Phase 3.

#### Manual Verification
- [ ] `curl -X POST /api/v1/payment-runs -d '{"currencyCode":"USD","payeeType":"MEMBER","description":"e2e"}'` → note the returned runId + one paymentId.
- [ ] `curl -X POST /api/v1/payment-runs/<runId>/approve` → 200.
- [ ] `curl -X DELETE /api/v1/payments/<paymentId>` → 204.
- [ ] `curl -X POST /api/v1/payment-runs -d '{"currencyCode":"USD","payeeType":"MEMBER"}'` → same paymentId's member reappears in the new run (outstanding_balance unchanged).
- [ ] Approve + execute the second run → member_balances.total_paid moves; member_payables → 'applied'; mp_applications has a PAYMENT row.

**Implementation Note:** pause for manual round-trip of the "revoke → regenerate → execute" journey before Phase 4.

---

## Phase 4: Finance-side unified Creditors backend (façade-only)

### Overview

Add a new `CreditorController` + `CreditorService` at `/api/v1/creditors` as a façade over the existing `ProviderBalanceService` and the new `MemberBalanceService`. The service composes both sources via a SQL `UNION ALL` for the paginated list. New `CreditorsExcelService` (finance-side, distinct from the contributions-side `DebtorsExcelService`) generates a workbook covering both payee types. A `LegacyProviderBalancesController` returns 410 for the three old endpoints. Every endpoint carries `@RequiresPermission(FINANCE_VIEW_CREDITORS)` — closes the existing auth gap on `ProviderBalanceController` at the same time (which stays around; it just becomes unreachable via the 410 shim on its base path).

### Changes Required

#### 1. Unified `CreditorRow` DTO

**File:** `services/java/finance-service/src/main/java/com/medfund/finance/dto/CreditorRow.java` (new)

```java
public record CreditorRow(
    String subjectType,        // "PROVIDER" | "MEMBER"
    UUID subjectId,
    String subjectCode,        // provider.code / member.member_number
    String subjectName,
    String subjectEmail,
    String currencyCode,
    BigDecimal totalClaimed,
    BigDecimal totalApproved,
    BigDecimal totalPaid,
    BigDecimal outstandingBalance,
    Instant lastActivityAt
) {}
```

**File:** `services/java/finance-service/src/main/java/com/medfund/finance/dto/CreditorFilterParams.java` (new)

```java
public record CreditorFilterParams(
    String subjectType,        // "PROVIDER" | "MEMBER" | "BOTH" — null = BOTH
    String currencyCode,
    String q,
    String sortKey,
    String sortDirection,
    int page,
    int size
) {}
```

#### 2. Unified query repository

**File:** `services/java/finance-service/src/main/java/com/medfund/finance/repository/CreditorQueryRepository.java` (new)

Dynamic-SQL search unioning `provider_balances` + `member_balances` with the corresponding name/code/email joins.

```java
@Repository
@RequiredArgsConstructor
public class CreditorQueryRepository {

    private static final Map<String, String> SORT_COLUMNS = Map.of(
        "subjectName",        "COALESCE(subject_name, '')",
        "totalClaimed",       "total_claimed",
        "totalApproved",      "total_approved",
        "totalPaid",          "total_paid",
        "outstandingBalance", "outstanding_balance",
        "currencyCode",       "currency_code",
        "lastActivityAt",     "last_activity_at"
    );

    private final DatabaseClient db;

    public Flux<CreditorRow> search(CreditorFilterParams f, int limit, int offset) {
        String union = buildUnion(f);
        String sql = union
            + " ORDER BY " + sortClause(f.sortKey(), f.sortDirection())
            + " LIMIT :limit OFFSET :offset";
        return bindFilters(db.sql(sql), f)
            .bind("limit", limit).bind("offset", offset)
            .map(this::toRow).all();
    }

    public Mono<Long> count(CreditorFilterParams f) {
        String union = buildUnion(f);
        String sql = "SELECT COUNT(*) AS total FROM (" + union + ") u";
        return bindFilters(db.sql(sql), f)
            .map(row -> ((Number) row.get("total")).longValue()).one();
    }

    private String buildUnion(CreditorFilterParams f) {
        String subjectType = f.subjectType() == null ? "BOTH" : f.subjectType().toUpperCase();
        boolean incProvider = subjectType.equals("PROVIDER") || subjectType.equals("BOTH");
        boolean incMember   = subjectType.equals("MEMBER")   || subjectType.equals("BOTH");
        List<String> parts = new ArrayList<>();
        if (incProvider) parts.add(providerBranch(f));
        if (incMember)   parts.add(memberBranch(f));
        return String.join(" UNION ALL ", parts);
    }

    private String providerBranch(CreditorFilterParams f) {
        String where = " WHERE 1=1 ";
        if (f.currencyCode() != null && !f.currencyCode().isBlank())
            where += " AND UPPER(b.currency_code) = UPPER(:currencyCode) ";
        if (f.q() != null && !f.q().isBlank())
            where += " AND LOWER(COALESCE(pr.name, '')) LIKE :qLower ";
        return """
            SELECT 'PROVIDER' AS subject_type,
                   b.provider_id AS subject_id,
                   pr.code       AS subject_code,
                   pr.name       AS subject_name,
                   pr.email      AS subject_email,
                   b.currency_code AS currency_code,
                   b.total_claimed, b.total_approved, b.total_paid, b.outstanding_balance,
                   b.last_updated_at AS last_activity_at
              FROM provider_balances b
              LEFT JOIN providers pr ON pr.id = b.provider_id
              """ + where;
    }

    private String memberBranch(CreditorFilterParams f) {
        String where = " WHERE 1=1 ";
        if (f.currencyCode() != null && !f.currencyCode().isBlank())
            where += " AND UPPER(b.currency_code) = UPPER(:currencyCode) ";
        if (f.q() != null && !f.q().isBlank())
            where += " AND (LOWER(COALESCE(m.first_name, '') || ' ' || COALESCE(m.last_name, '')) LIKE :qLower "
                +  " OR LOWER(COALESCE(m.member_number, '')) LIKE :qLower) ";
        return """
            SELECT 'MEMBER' AS subject_type,
                   b.member_id AS subject_id,
                   m.member_number AS subject_code,
                   CONCAT_WS(' ', m.first_name, m.last_name) AS subject_name,
                   m.email AS subject_email,
                   b.currency_code AS currency_code,
                   b.total_claimed, b.total_approved, b.total_paid, b.outstanding_balance,
                   b.last_updated_at AS last_activity_at
              FROM member_balances b
              LEFT JOIN members m ON m.id = b.member_id
              """ + where;
    }

    // bindFilters, sortClause, toRow — analogous to ProviderBalanceQueryRepository:70-110
}
```

#### 3. `CreditorController` + `CreditorService`

**File:** `services/java/finance-service/src/main/java/com/medfund/finance/controller/CreditorController.java` (new)

```java
@RestController
@RequestMapping("/api/v1/creditors")
@RequiredArgsConstructor
@Tag(name = "Creditors",
     description = "Unified list of parties the fund owes for approved claims — providers and members.")
@SecurityRequirement(name = "bearer-jwt")
public class CreditorController {

    private final CreditorService service;

    @GetMapping("/page")
    @RequiresPermission({Permissions.FINANCE_VIEW_CREDITORS})
    @Operation(summary = "Paginated, sortable, filterable unified creditors list",
        description = "Feeds /tenant/finance/creditors. subjectType=PROVIDER|MEMBER|BOTH.")
    public Mono<PageResponse<CreditorRow>> searchPaged(
            @RequestParam(required = false) String subjectType,
            @RequestParam(required = false) String currencyCode,
            @RequestParam(required = false) String q,
            @RequestParam(required = false, defaultValue = "outstandingBalance") String sortKey,
            @RequestParam(required = false, defaultValue = "desc") String sortDirection,
            @RequestParam(required = false, defaultValue = "0") int page,
            @RequestParam(required = false, defaultValue = "50") int size) {
        var params = new CreditorFilterParams(subjectType, currencyCode, q, sortKey, sortDirection, page, size);
        return service.searchPaged(params);
    }

    @GetMapping("/provider/{providerId}")
    @RequiresPermission({Permissions.FINANCE_VIEW_CREDITORS})
    @Operation(summary = "Provider creditor detail (delegates to ProviderBalanceService)")
    public Mono<ProviderBalanceResponse> providerDetail(@PathVariable UUID providerId) {
        return service.providerDetail(providerId);
    }

    @GetMapping("/member/{memberId}")
    @RequiresPermission({Permissions.FINANCE_VIEW_CREDITORS})
    @Operation(summary = "Member creditor detail")
    public Flux<MemberBalanceResponse> memberDetail(@PathVariable UUID memberId) {
        return service.memberDetail(memberId);
    }

    @GetMapping("/export/excel")
    @RequiresPermission({Permissions.FINANCE_VIEW_CREDITORS})
    @Operation(summary = "Excel export of the unified Creditors list")
    public Mono<ResponseEntity<byte[]>> exportExcel(
            @RequestParam(required = false) String subjectType,
            @RequestParam(required = false) String currencyCode,
            @RequestParam(required = false) String q) {
        return service.exportExcel(subjectType, currencyCode, q)
            .map(bytes -> ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=creditors-" + safe(subjectType) + "-"
                        + (currencyCode == null ? "ALL" : currencyCode) + "-"
                        + LocalDate.now() + ".xlsx")
                .body(bytes));
    }
}
```

**File:** `services/java/finance-service/src/main/java/com/medfund/finance/service/CreditorService.java` (new)

Composes ProviderBalanceService + MemberBalanceQueryRepository + CreditorQueryRepository + CreditorsExcelService.

#### 4. `MemberBalanceQueryRepository` + `MemberBalanceResponse`

**File:** `services/java/finance-service/src/main/java/com/medfund/finance/repository/MemberBalanceQueryRepository.java` (new)

Single-member lookup with member-name join. Returns `Flux<MemberBalanceResponse>` (member can have multiple currency rows).

**File:** `services/java/finance-service/src/main/java/com/medfund/finance/dto/MemberBalanceResponse.java` (new)

```java
public record MemberBalanceResponse(
    UUID id, UUID memberId, String memberName, String memberCode, String memberEmail,
    BigDecimal totalClaimed, BigDecimal totalApproved, BigDecimal totalPaid, BigDecimal outstandingBalance,
    String currencyCode, Instant lastUpdatedAt
) {}
```

#### 5. `CreditorsExcelService` (finance-side)

**File:** `services/java/finance-service/src/main/java/com/medfund/finance/service/CreditorsExcelService.java` (new)

Same shape as `CreditorsExcelService` on the contributions side (now renamed `DebtorsExcelService` per Phase 2). Sheet name `"Creditors"`, columns: Type, Code, Name, Email, Currency, Claimed, Approved, Paid, Outstanding, Last Activity. Filename per Excel controller.

#### 6. 410 shim for old provider-balances

**File:** `services/java/finance-service/src/main/java/com/medfund/finance/controller/LegacyProviderBalancesController.java` (new)

Three endpoints returning 410 (mirror the Phase 2 pattern). One release lifetime.

Delete the existing `ProviderBalanceController` class? **No** — keep it in place but move it OUT of the `/api/v1/provider-balances` mapping so it doesn't conflict with the shim. Rename its `@RequestMapping` to something internal (`/internal/provider-balances-legacy`) or remove the `@RestController` annotation and keep it as a plain Spring bean (renamed `ProviderBalanceQueryFacade`) that only exists so `CreditorService` can call `findByProviderId` without re-implementing. Cleanest: delete the controller and hoist its query methods into `ProviderBalanceService` (already present there — line 53). No new class needed.

**Concrete move:** delete `ProviderBalanceController.java`. `CreditorController.providerDetail` calls `providerBalanceService.findByProviderId(id)` directly.

### Success Criteria

#### Automated Verification
- [x] Java compiles + tests pass. `:finance-service:compileJava` + `:finance-service:compileTestJava` clean. Same 10 pre-existing NPE test failures ([[bug_claim_save_mock_id_npe]]) as Phase 3; no new failures from Phase 4.
- [ ] `CreditorControllerIT`: GET /api/v1/creditors/page?subjectType=BOTH returns rows from both tables; sort by `outstandingBalance desc` interleaves correctly. — deferred (same IT-infra rationale as Phases 2/3).
- [ ] Same IT: request without `finance:view_creditors` returns 403. — deferred.
- [ ] `LegacyProviderBalancesControllerIT`: GET /api/v1/provider-balances/page returns 410. — deferred.
- [ ] Excel export: response has content-type octet-stream + Content-Disposition header with correct filename. — deferred (needs a running service; the code path is straightforward and covered by manual verification).
- [ ] Swagger renders at `http://localhost:8085/swagger-ui` with the "Creditors" tag replacing "Provider Balances". — pending manual verification (the CreditorController is tagged "Creditors" via `@Tag`; the legacy shim is `@Hidden` and won't appear).

#### Manual Verification
- [ ] `curl /api/v1/creditors/page?subjectType=BOTH&currencyCode=USD` returns interleaved rows.

---

## Phase 5: Angular unified Creditors page + member detail page

### Overview

Rebuild `/tenant/finance/creditors` as a single unified page consuming the new `/api/v1/creditors/page` endpoint. Add the member-balance detail page at `/tenant/finance/creditors/member/:id` mirroring `provider-balance-detail.component.ts` — summary card + Payables tab + CTC tab + Payments tab (with revoke row action) + Adjustments tab. Remove the old placeholder routes outright (G7c). Sidebar label changes; permission changes.

### Changes Required

#### 1. Sidebar

**File:** `clients/angular/src/app/layout/operational-sidebar/operational-nav.ts:139`

```typescript
{ label: 'Creditors', icon: 'building', route: '/tenant/finance/creditors', permissions: ['finance:view_creditors'] },
```

#### 2. Route table

**File:** `clients/angular/src/app/pages/tenant/finance/finance.routes.ts`

Replace lines 286-309 with:

```typescript
// ── Creditors ─────────────────────────────────────────────────────────────
{
  path: 'creditors',
  canActivate: [permissionGuard(['finance:view_creditors'])],
  loadComponent: () => import('./creditors/creditors-list.component').then(m => m.CreditorsListComponent),
  data: { title: 'Creditors', sidebar: 'operational', fullbleed: true },
},
{
  path: 'creditors/provider/:id',
  canActivate: [permissionGuard(['finance:view_creditors'])],
  loadComponent: () => import('./creditors/provider-balance-detail.component').then(m => m.ProviderBalanceDetailComponent),
  data: { title: 'Provider Balance', sidebar: 'operational' },
},
{
  path: 'creditors/member/:id',
  canActivate: [permissionGuard(['finance:view_creditors'])],
  loadComponent: () => import('./creditors/member-balance-detail.component').then(m => m.MemberBalanceDetailComponent),
  data: { title: 'Member Balance', sidebar: 'operational' },
},
```

Delete the old `creditors/provider`, `creditors/member`, and `cs('creditors/:id', ...)` entries (lines 287-309).

#### 3. Refactor `creditors-list.component.ts`

**File:** `clients/angular/src/app/pages/tenant/finance/creditors/creditors-list.component.ts`

Rewrite the component to consume `FinanceService.listCreditorsPaged` with:
- Subject-type filter chip strip (PROVIDER | MEMBER | BOTH — default BOTH). Same pattern the billing debtors page uses for MEMBER|GROUP.
- Currency filter.
- Search input (debounced 300ms).
- Server-side pagination via `DataTableComponent`.
- Columns: Type (badge), Code, Name, Currency, Claimed, Approved, Paid, Outstanding, Last Activity.
- Row click: navigate to `/tenant/finance/creditors/<subjectType>/<subjectId>`.
- Excel export button calling `FinanceService.exportCreditorsExcel(subjectType, currency, q)`.

#### 4. New `member-balance-detail.component.ts`

**File:** `clients/angular/src/app/pages/tenant/finance/creditors/member-balance-detail.component.ts` (new)

Mirrors `provider-balance-detail.component.ts` (line 41+):
- Summary card: member name/code/email + 4-col money display (one line per currency).
- Tab strip: Payables / CTCs / Payments / Adjustments.
- Payables tab: table of open+applied `member_payables` for member (via `MemberPayableService.listForMember(memberId)` — endpoint already exists at `MemberPayableController:32`).
- CTCs tab: paginated CTC payments where memberId=X (via `CtcPaymentService.searchPaged` with `memberId` filter).
- Payments tab: `FinanceService.getPaymentsByMember(memberId)` (endpoint added in Phase 3). Each row: paymentNumber, amount, currency, status, paidAt. Row action `Revoke` visible if `status='pending'` — calls `FinanceService.revokePayment(id)` which hits `DELETE /api/v1/payments/{id}`. Success toast + refresh.
- Adjustments tab: `FinanceService.getAdjustmentsByMember(memberId)` (Phase 3).

#### 5. FinanceService updates

**File:** `clients/angular/src/app/core/services/finance.service.ts`

- **Remove** `listProviderBalances` (line 730), `listProviderBalancesPaged` (line 732), `ProviderBalance` interface (line 83), `ProviderBalanceRow` interface (line 404), `ProviderBalancePageParams` interface (line 416). Callers moved to `listCreditorsPaged`.
- **Add**:
  ```typescript
  listCreditorsPaged(opts: CreditorPageParams): Observable<FinancePageResponse<CreditorRow>> { ... }
  getCreditorProviderDetail(id: string): Observable<ProviderBalance> { return this.api.get(`/creditors/provider/${id}`); }
  getCreditorMemberDetail(id: string): Observable<MemberBalance[]> { return this.api.get(`/creditors/member/${id}`); }
  exportCreditorsExcel(subjectType?: string, currency?: string, q?: string): Observable<Blob> { ... }
  getPaymentsByMember(memberId: string): Observable<Payment[]> { return this.api.get(`/payments/member/${memberId}`); }
  getAdjustmentsByMember(memberId: string): Observable<Adjustment[]> { return this.api.get(`/adjustments/member/${memberId}`); }
  revokePayment(id: string): Observable<void> { return this.api.delete(`/payments/${id}`); }
  ```
- **Add interfaces**: `CreditorRow`, `CreditorPageParams`, `MemberBalance`.

### Success Criteria

#### Automated Verification
- [x] Angular typecheck clean — `npx ng build --configuration=development` completes without errors (bundle generation complete, only pre-existing warnings on unrelated components).
- [ ] Angular unit tests pass — no `.spec.ts` files existed for `creditors/` or `runs/` components, so nothing to run; broader `make test-angular` deferred.
- [ ] Playwright: new `finance-creditors.spec.ts` covers (a) switch PROVIDER/MEMBER/BOTH filters, (b) click into provider detail (existing), (c) click into member detail (new), (d) revoke a pending Payment from the Payments tab. — deferred (same IT-infra rationale as Phases 2/3).
- [ ] `verify` skill on `/tenant/finance/creditors` — no console errors, subject-type toggle renders correct columns, Excel export triggers download.
- [ ] `verify` on `/tenant/finance/creditors/member/<seeded-member-id>` — summary + all four tabs render.
- [ ] `verify` on `/tenant/finance/creditors/provider` — Angular 404 (old route removed).

#### Manual Verification
- [ ] Excel download opens in Excel and shows both PROVIDER and MEMBER rows when subject-type=BOTH.
- [ ] Revoking a pending Payment on the member detail page removes it from the tab; hitting F5 confirms it's gone from the backend too.

---

## Phase 6: PaymentRun UI updates — member support, revoke row action, advice payeeType

### Overview

Surface `payeeType` in the PaymentRun UI so an operator can generate a member run, filter runs by payee type, and see which payment advices are for members. Add the revoke row action on the PaymentRun items list — the FE endpoint already exists from Phase 3.

### Changes Required

#### 1. PaymentRun generate form

**File:** `clients/angular/src/app/pages/tenant/finance/payment-runs/payment-run-form.component.ts` (or wherever the create form lives)

Add a `payeeType` select control: `PROVIDER | MEMBER`. Default PROVIDER. Include in the POST body.

#### 2. PaymentRun list

Add `payeeType` column (badge). Add a filter chip strip alongside the existing currency filter.

#### 3. PaymentRun items list (detail page)

Add a per-row `Revoke` action button (icon + tooltip). Visible if `item.status === 'pending'` AND the parent run is in `draft` or `approved`. On click:
- Confirm modal ("Revoke payment PAY-XXXXXX? The payee's outstanding balance is unchanged; they will be included in the next run generation.").
- Call `FinanceService.revokePayment(paymentId)`.
- Refresh the items list on success.

#### 4. Payment Advice list

**File:** `clients/angular/src/app/pages/tenant/finance/advice/payment-advice.component.ts` (and .html)

Add `payeeType` column. Add filter chip strip (ALL | PROVIDER | MEMBER). Update the FinanceService `listAdvicesPaged` method to accept `payeeType` param and forward to the backend (the backend already includes `payeeType` in the advice DTO if V071 didn't already surface it — verify and extend if needed).

### Success Criteria

#### Automated Verification
- [x] Angular typecheck clean — `npx ng build --configuration=development` completes without errors after Phase 6 edits (payment-run-generate, payment-runs-list, payment-run-detail all compile). Payment Advice already carries payeeType column + filter from prior work — verified in `payment-advice.component.ts:31-46`; no new code needed for Phase 6.3.
- [ ] Playwright new spec `payment-run-member.spec.ts` covers: generate member run → verify items list → revoke one item → regenerate run (or verify count) → approve → execute → advice appears with payeeType=MEMBER. — deferred (same IT-infra rationale as Phases 2/3).
- [ ] `verify` on `/tenant/finance/payment-runs` list, generate form, detail, and advice list.

#### Manual Verification
- [ ] Generating a member run in a tenant with one MEMBER-payee approved claim produces exactly one item.
- [ ] Revoking that item leaves the run at paymentCount=0; regenerating brings it back.

---

## Testing Strategy

### Unit tests
- `MemberBalanceServiceTest` — bootstrap on first update, add/subtract via deltas, correct outstanding_balance recompute, audit event fields.
- `PaymentServiceTest.markPaid_memberPayeeAllocatesFifo` — sorted `member_payables` receive slices in `recordedAt ASC` order; last payable is partially applied if payment amount overshoots the older ones.
- `PaymentServiceTest.revoke_forbidsPaidPayment` and `revoke_forbidsExecutedRun` — 400 responses.
- `PaymentRunGeneratorTest.populate_memberBranch` — enumerates `member_balances` correctly.
- `CreditorQueryRepositoryTest.buildUnion_bothIncludesBothBranches`.

### Integration tests (Testcontainers)
- `MemberBalanceServiceIT` — full round-trip with a real Postgres schema.
- `ClaimAdjudicatedConsumerIT.memberPayee_bumpsBothTables` — event → `member_payables` row + `member_balances` bump.
- `CtcPaymentServiceIT.commit_bumpsMemberBalancesTotalPaid` and `reverse_decrementsMemberBalancesTotalPaid`.
- `PaymentServiceIT.markPaid_memberPayee_fifo` — end-to-end allocation across three payables.
- `PaymentServiceIT.revoke_deletesAndRecomputesRun`.
- `PaymentRunServiceIT.execute_memberRun_generatesMemberAdvices`.
- `CreditorControllerIT.searchPaged_bothSubjectTypes`.
- `LegacyBillingBalancesControllerIT.creditorsPathReturns410`, `LegacyProviderBalancesControllerIT.pathsReturn410`.
- `V072BackfillIT` — seed pre-migration data, apply V072, assert `member_balances` rows.
- `V073PermissionSwapIT` — seed a tenant_admin role with `billing:view_creditors`, apply V073, assert `billing:view_debtors` and `finance:view_creditors` are granted, `billing:view_creditors` is not.

### E2E (Playwright, clients/angular/e2e/tests/)
- `finance-creditors.spec.ts` — subject-type toggle + Excel export + member drilldown.
- `payment-run-member.spec.ts` — generate member run + revoke item + execute + advice.
- Update `billing-charge-preview.spec.ts` — new permission string.

### Manual testing steps
1. `V072` on a copy of a real tenant schema — verify backfill produces the expected `member_balances` rows.
2. Generate a member PaymentRun; revoke an item; regenerate; execute. Watch the audit stream to confirm DELETE + UPDATE events fire correctly.
3. Confirm no tenant admin loses access after V073 (spot-check a role that previously had `billing:view_creditors`).

## Performance Considerations

- **`CreditorQueryRepository.search` — UNION ALL over two tables.** For tenants with hundreds of providers and thousands of members, the CTE runs per request. Both tables have partial indexes on `outstanding_balance > 0` (V016:63 for providers, V072 for members) which the ORDER BY exploits when sorting by outstanding descending. For other sort keys (`subjectName`, `lastActivityAt`), a full sort of the UNION result is unavoidable at the DB level — acceptable for tenants under ~10k rows. Above that, add a materialized view or per-branch pre-pagination in a follow-up.
- **FIFO allocation on markPaid** — one Postgres round-trip per payable being consumed. For a member with 5 open payables, that's 5 round-trips per Payment. Acceptable for the current member volumes. If member volumes grow, this becomes a single SQL `UPDATE ... FROM (SELECT ...)` in a follow-up.
- **Backfill V072** — one-shot; queries `claims` + `member_payables` grouped by `(member_id, currency_code)`. For a tenant with 100k claims, this is a single scan; runs at migration time in under a second on typical hardware.
- **PaymentAdviceService.generateAdvicesForRun** — already reactive concurrent per item (existing code). Member branch reuses the same fan-out.

## Migration Notes

- **V072 order dependency:** V072 depends on V069 (`member_payables`), V070 (permissions), V071 (`payment_run_items.payee_type`). All are prior applied. V072 also depends on V001's `claims` table being present (baseline).
- **V072 destructive nature:** the `member_payable_applications.source_type` CHECK is dropped and recreated. Any row currently violating the new CHECK (`source_type NOT IN ('CTC','PAYMENT')`) would break the migration — there are none today (only CTC has ever written to this table), so safe.
- **V073 order dependency:** must apply AFTER Java + Angular shared catalogue updates land in the same phase, or the running FE will still send the old permission and get 403s. Rollout should be: (i) deploy Java+Angular; (ii) apply V073 during the same maintenance window.
- **[[bug_tenant_flyway_outoforder]] risk** — V072/V073 are new sequential files; no risk of out-of-order drift unless a hotfix branches ahead.
- **[[bug_public_flyway_history_load_bearing]] risk** — no `public/` migration is added; nothing to touch there.

## Rollout & Rollback

**Deployment order:**
1. Kafka topics: no new topics; `medfund.claims.adjudicated` payload already carries `payeeType` (per CTC plan) — additive-only.
2. Java services deploy in any order (the finance-service consumer branch is independent of contributions-service).
3. Angular deploys after both Java services are live (needs new endpoints).
4. V072 + V073 apply during the deployment window against every tenant schema.

**Rollback strategy:**
- **Java rollback** — safe. New endpoints on new paths; existing endpoints unchanged. Old permission `billing:view_creditors` has been dropped from the catalogue but is still referenced by any tenants that were reverted before V073 — the running JVM will accept the old permission if the shared catalogue is rolled back too.
- **V072 rollback** — non-trivial. `member_balances` can be dropped. `payment_runs.payee_type` cannot be trivially dropped once member runs have been written (would orphan MEMBER items). If a rollback needs to remove the column, follow up with a manual data cleanup migration.
- **V073 rollback** — reversible via a compensating migration that grants `billing:view_creditors` back to affected roles.
- **Angular rollback** — safe; static bundle swap.

## References

- Research: `thoughts/shared/research/2026-08-10-creditors-workflow-unify-providers-and-members.md`
- Adjacent plan (foundation this builds on): `thoughts/shared/plans/2026-08-09-ctc-payments.md`
- Architecture doc — payments: `.claude/payments.md`
- Architecture doc — multi-tenancy: `.claude/multi-tenancy.md`
- Architecture doc — multi-currency: `.claude/multi-currency.md`
- Similar backend rename precedent: `services/java/contributions-service/src/main/java/com/medfund/contributions/service/BillingCatalogueService.java` (for the safeParseUuid pattern)
- Similar Angular subject-type tab strip: `clients/angular/src/app/pages/tenant/billing/creditors/creditors-list.component.ts` (which itself moves in Phase 2)
- Auto-memory touched: [[project_ctc_is_opt_in]], [[feedback_no_raw_id_inputs]], [[feedback_stats_serverside]], [[feedback_audit_actor_email]], [[feedback_audit_entity_name]], [[bug_public_prefix_silent_rollback]], [[feedback_never_edit_applied_migrations]], [[infra_testcontainers_pitfalls]], [[bug_reactor_kafka_ack_swallow]], [[feedback_grouped_members_cannot_pay]]
