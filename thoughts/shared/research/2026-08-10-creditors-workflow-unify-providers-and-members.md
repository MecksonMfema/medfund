---
date: 2026-08-10T13:30:00+02:00
researcher: Methuseli
git_commit: c248073df71e8e42d035addfd5a090e5b39bacba
branch: main
repository: medfund
topic: "Creditors workflow — rename Provider Creditors to just Creditors and surface running balances for both members and providers"
tags: [research, codebase, finance-service, contributions-service, angular, ledger, provider-balance, member-payables, ctc]
status: complete
last_updated: 2026-08-10
last_updated_by: Methuseli
last_updated_note: "Naming resolved — contributions side becomes Debtors, finance side becomes Creditors (see Follow-up 2026-08-10)."
---

# Research: Creditors workflow — rename "Provider Creditors" and unify with members

**Date**: 2026-08-10 13:30 +02:00 · **Researcher**: Methuseli · **Commit**: c248073 · **Branch**: main

## Research Question
Research the creditors workflow. We have to change the **provider creditors** to just **creditors**, then show the financial **running balances for all members and providers** — our creditors.

## Summary

The word "creditor" is currently used for **two opposite things** in InsureFlow. **Decision (2026-08-10):** the contributions-side listing is being renamed to **Debtors**, and the finance-side listing (providers + members owed money for approved claims) takes the sole ownership of the word **Creditors**.

| Where | Route / API | Semantic | Ledger | New label |
|---|---|---|---|---|
| contributions-service | `/tenant/billing/creditors`, `GET /api/v1/billing/balances/creditors` | People who **OWE the tenant** money for contributions (accounting: **debtors**) | `member_running_balance`, `group_running_balance` (per-subject net balance) | **Debtors** |
| finance-service | `/tenant/finance/creditors/provider`, `GET /api/v1/provider-balances/page` | Providers the **tenant OWES** for approved claims (accounting: **creditors**) | `provider_balances` (per-provider 4-col ledger: claimed / approved / paid / outstanding) | **Creditors** (unified — adds members) |

The finance side is where the true accounting "creditor" concept lives. Renaming it to plain **Creditors** (dropping the "Provider" qualifier) is a UI+API rename, but the same list must then include **members** who are owed money for approved claims — the data is already there in a separate ledger:

- `member_payables` table (V069/V071) stores every approved MEMBER-payee claim as a payable row.
- `member_payable_applications` records CTC (contribution-offset) consumption against it.
- `MemberPayableBalanceRepository` already computes outstanding-per-member-per-currency on read.
- `ClaimAdjudicatedConsumer.handleMemberPayee` (`services/java/finance-service/src/main/java/com/medfund/finance/consumer/ClaimAdjudicatedConsumer.java:133`) writes it whenever `payeeType=MEMBER` on an approved claim.
- The Angular route `/tenant/finance/creditors/member` **already exists as a placeholder** (`clients/angular/src/app/pages/tenant/finance/finance.routes.ts:299`) reusing the provider list component while the member-side backend surface catches up.

What's missing:
1. A **controller/service surface** in finance-service that exposes member outstanding balances as a paginated list — analogous to `ProviderBalanceController.searchPaged`. The repository read exists (`MemberPayableBalanceRepository.findOutstandingByCurrency`) but no HTTP endpoint calls it.
2. A **unified "creditors" list DTO** that can carry either PROVIDER or MEMBER rows (subjectType discriminator, provider name / member name).
3. **Naming re-shuffle (settled):** the billing-side page and its API path are renamed **Creditors → Debtors**; the finance-side page drops the "Provider" qualifier and becomes **Creditors** (unified list of providers + members owed money). The existing `finance:view_debtors` permission — currently only wired to Finance → Reports (`clients/angular/src/app/layout/operational-sidebar/operational-nav.ts:147`) — moves to the billing-side Debtors page; a new `finance:view_creditors` permission gates the unified Creditors page. See the "Naming resolution" note under [What this ticket needs decided](#what-this-ticket-needs-decided) for the concrete scope.

The user request implies **not just a rename**: it asks for a unified, single "Creditors" page in Finance that lists **both** providers and members with their running balances. This is a small backend build (member half) plus a rename+merge of the two `/tenant/finance/creditors/*` routes into one list with a subject-type filter, mirroring the pattern the billing creditors page already uses for MEMBER/GROUP tabs.

## Findings

### 1) finance-service — the "provider creditors" ledger (rename target)

**Table** `provider_balances` (`services/java/tenancy-service/src/main/resources/db/migration/tenant/V016__finance_schema.sql:47`)

Columns: `id`, `provider_id`, `total_claimed`, `total_approved`, `total_paid`, `outstanding_balance`, `currency_code`, `last_updated_at`, `created_at`
- UNIQUE `(provider_id, currency_code)`; partial index on `outstanding_balance DESC WHERE outstanding_balance > 0`.
- `outstanding_balance = total_approved - total_paid`, recalculated on every update (`services/java/finance-service/src/main/java/com/medfund/finance/service/ProviderBalanceService.java:101`).

**Write path** — one and only one writer:
- `ClaimAdjudicatedConsumer` consumes `medfund.claims.adjudicated`; when `payeeType != MEMBER` it calls `handleProviderPayee` (`services/java/finance-service/src/main/java/com/medfund/finance/consumer/ClaimAdjudicatedConsumer.java:288`), which turns the decision into `(claimedDelta, approvedDelta, paidDelta)` and calls `ProviderBalanceService.updateBalance` (line 66) with audit fields tracked (`totalClaimed`, `totalApproved`, `totalPaid`, `outstandingBalance` — line 148).

**Read path**:
- `GET /api/v1/provider-balances/page` (paginated, filterable) — `services/java/finance-service/src/main/java/com/medfund/finance/controller/ProviderBalanceController.java:36`
- `GET /api/v1/provider-balances/provider/{providerId}` — line 51
- `GET /api/v1/provider-balances` (unpaginated, only rows with outstanding > 0) — line 30
- SQL is dynamic (`services/java/finance-service/src/main/java/com/medfund/finance/repository/ProviderBalanceQueryRepository.java:39`) with `LEFT JOIN providers pr` for name inline; sort keys: `providerName`, `totalClaimed`, `totalApproved`, `totalPaid`, `outstandingBalance`, `currencyCode`, `lastUpdatedAt`.

**DTOs**:
- Response: `ProviderBalanceResponse` (single balance)
- Row: `ProviderBalanceRow` (paginated, with pre-joined `providerName`; note field `updatedAt` diverges from entity `lastUpdatedAt`)
- Filter params: `ProviderBalanceFilterParams` (currencyCode, q, sortKey, sortDirection, page, size)

**Excel export**: none currently on provider balances — a documented asymmetry vs. the billing-side creditors/bad-debts pages.

### 2) finance-service — the member half already exists (member_payables ledger)

The **member payables** ledger — the piece we need to surface alongside providers — is already implemented in production migration `V069__member_payables.sql` / `V071__payment_run_generation_and_advice_ledger.sql`:

**Tables**
- `member_payables` — one row per approved MEMBER-payee claim (`memberId`, `claimId`, `amount`, `currencyCode`, `status IN ('open','applied','reversed')`, `recordedAt`, `claimNumber`). UNIQUE on `claim_id` for idempotency.
- `member_payable_applications` — bridge rows recording how much of a payable has been consumed by a specific CTC or payout.

**Write path** — same consumer as providers:
- `ClaimAdjudicatedConsumer.handleMemberPayee` (`services/java/finance-service/src/main/java/com/medfund/finance/consumer/ClaimAdjudicatedConsumer.java:133`) — only fires on `isApprovedDecision(decision)` and only if `payeeType=MEMBER`. Inserts one `member_payables` row per claim; UNIQUE-violation → idempotent skip (line 166–169).
- Optional auto-CTC draft (Phase 4, `maybeAutoDraftCtc` line 191) drops a `status='draft'` `ctc_payments` row when the tenant has opted in via `tenant_ctc_auto_config`.

**Read path — the outstanding-per-member aggregation is already written** but has no HTTP surface:
- `MemberPayableBalanceRepository.findOutstandingByMember(memberId)` — per-member per-currency outstanding (`services/java/finance-service/src/main/java/com/medfund/finance/repository/MemberPayableBalanceRepository.java:39`)
- `MemberPayableBalanceRepository.findOutstandingByCurrency(currencyCode)` — all members with positive outstanding in a currency (line 77)
- `MemberPayableBalanceRepository.remainingOn(payableId)` — remaining on a single payable (line 119)

Formula (identical to the SQL):

```
outstanding = SUM(member_payables.amount WHERE status IN ('open','applied'))
            - SUM(member_payable_applications.amount_applied)
```

**No `member_balances` snapshot table.** Design decision documented in `thoughts/shared/plans/2026-08-09-ctc-payments.md` (design decision #2): low volume, no need for historical claimed/approved/paid split — derived aggregate is safe.

**Consequence for the unification**: a "Creditors" list that shows both providers and members will have **mixed column semantics**. Providers have a 4-column journey (claimed → approved → paid → outstanding); members have a single `outstanding` derived from `amount - applied`. Either the UI shows only the columns common to both (subjectType, subjectName, currencyCode, outstanding, lastActivity), or the extra provider-only columns must be nullable/hidden when subjectType=MEMBER.

Related memory: [[project_ctc_is_opt_in]] — CTC is opt-in; the default member-payee route is a cash payout bundled into a `PaymentRun` (unbuilt at time of writing).

### 3) contributions-service — the OTHER "creditors" (billing arrears, misnamed)

This is the collision.

**Tables** (`services/java/tenancy-service/src/main/resources/db/migration/tenant/V010__running_balances.sql`)
- `member_running_balance` — one row per (member_id, currency_code): `balance`, `last_charge_at`, `last_payment_at`, `updated_at`
- `group_running_balance` — identical shape for groups

**Write path** — `BalanceService` (`services/java/contributions-service/src/main/java/com/medfund/contributions/service/BalanceService.java`):
- `applyContributionDebit` (line 67), `applyContributionPaid` (line 106), `applyTransaction` (line 156), `reverseContributionDebit` (line 92), `writeOffBalance` (line 135). All go through `INSERT ... ON CONFLICT ... DO UPDATE` for idempotency and publish `MEMBER_BALANCE_UPDATED` / `GROUP_BALANCE_UPDATED` events on Kafka topics `medfund.balances.member-updated` / `.group-updated` (`services/java/contributions-service/src/main/java/com/medfund/contributions/service/BalanceEventPublisher.java:24`).

**Read path** — `BalanceController` at `/api/v1/billing/balances`:
- `GET /creditors` — currently-billable (status IN active, suspended) subjects owing money — `services/java/contributions-service/src/main/java/com/medfund/contributions/controller/BalanceController.java:74`
- `GET /creditors/export/excel` — line 90
- `GET /bad-debts` — deactivated/terminated subjects still owing — line 108
- `GET /bad-debts/export/excel`
- `GET /aged-balances` — currently-billable with aging classification
- `GET /members/{memberId}` and `GET /groups/{groupId}` — single balance lookup

`CreditorRow` shape (`services/java/contributions-service/src/main/java/com/medfund/contributions/dto/CreditorRow.java`): subjectType (MEMBER/GROUP), subjectId, subjectCode, subjectName, subjectEmail, currencyCode, balance, lastChargeAt, lastPaymentAt, daysSinceLastActivity.

**Grouped members are deliberately excluded** from the MEMBER half of the UNION — their balance rolls up to the group liaison (`services/java/contributions-service/src/main/java/com/medfund/contributions/repository/BalanceQueryRepository.java:141`, `WHERE m.group_id IS NULL`). Cross-reference memory [[feedback_grouped_members_cannot_pay]].

**This is where the misnomer lives.** From the tenant's accounting POV these subjects are **debtors** (they owe us). Calling them "creditors" in the UI is loose — likely stems from the tenant's book-keeping habit of listing everyone with a running balance as a "creditor account". It works as long as it's the only page in the app called "Creditors"; it breaks the moment finance-side Creditors mean the opposite.

### 4) Angular UI — three surfaces, one placeholder

Route table (`clients/angular/src/app/pages/tenant/finance/finance.routes.ts` and `clients/angular/src/app/pages/tenant/billing/billing.routes.ts`):

| Route | Component | Purpose | Status |
|---|---|---|---|
| `/tenant/finance/creditors/provider` | `finance/creditors/creditors-list.component.ts` | Provider payables list | Live |
| `/tenant/finance/creditors/provider/:id` | `finance/creditors/provider-balance-detail.component.ts` | Provider detail with payment + adjustment tabs | Live |
| `/tenant/finance/creditors/member` | reuses `finance/creditors/creditors-list.component.ts` | Member payables list | **Placeholder** — currently renders provider list; awaits member-balance backend (line 299–308) |
| `/tenant/billing/creditors` | `billing/creditors/creditors-list.component.ts` | Members/groups arrears list | Live — the misnamed one |
| `/tenant/billing/bad-debts` | `billing/bad-debts/bad-debts-list.component.ts` | Deactivated/terminated arrears | Live |

Sidebar (`clients/angular/src/app/layout/operational-sidebar/operational-nav.ts`):
- Billing group has "Creditors" (line 81), "Bad Debts" (82), "Charge Preview" (83)
- Finance group has "Provider Creditors" (line 139)

Permission gating: everything above uses `billing:view_creditors` except Bad Debts (`billing:manage_bad_debts`) and the yet-unused `finance:view_debtors` (line 147). Note the permission label reads "View outstanding balances owed by members and groups" (`clients/angular/src/app/core/security/permissions.ts:108`) — it's already describing the billing side; when we split the two concepts, we'll want a separate `finance:view_creditors` permission for the payables side (or reuse `finance:view_debtors`, which is arguably the wrong name too).

Frontend TypeScript models:
- `ProviderBalance`, `ProviderBalanceRow`, `ProviderBalancePageParams` in `clients/angular/src/app/core/services/finance.service.ts:83–423`
- `CreditorRow` (billing side, MEMBER|GROUP) and `BadDebtRow` in `clients/angular/src/app/core/services/balance.service.ts:23–46`

### 5) Provider-detail page — the pattern to replicate for members

The `provider-balance-detail.component.ts` opens a page-per-provider with three sections (`clients/angular/src/app/pages/tenant/finance/creditors/provider-balance-detail.component.ts:41`):

1. Balance summary (totalClaimed / totalApproved / totalPaid / outstanding / lastUpdated)
2. **Payments tab** — history via `FinanceService.getPaymentsByProvider(providerId)`
3. **Adjustments tab** — history via `FinanceService.getAdjustmentsByProvider(providerId)`

`Payment` and `Adjustment` DTOs (`finance.service.ts:56, 100`) already carry both `providerId?` and `memberId?` fields, and `PaymentQueryRepository` / `PaymentAdviceQueryRepository` already sort/filter on `payee_type` — so a member-detail page can be built by mirroring the provider one with `getPaymentsByMember` / `getAdjustmentsByMember` (or a symmetric endpoint) and rendering an equivalent summary from `MemberPayableBalanceRepository`.

## Cross-service flow (adjudication → creditor listings)

```
claims-service → Kafka: medfund.claims.adjudicated
    │
    ▼
finance-service · ClaimAdjudicatedConsumer.processEvent
    │
    ├─ payeeType = PROVIDER (default) ──► handleProviderPayee
    │      │
    │      ▼
    │   ProviderBalanceService.updateBalance
    │      │
    │      ▼
    │   provider_balances (+ audit event)
    │      │
    │      ▼
    │   GET /api/v1/provider-balances/page  ──► /tenant/finance/creditors/provider
    │
    └─ payeeType = MEMBER ─────────────────► handleMemberPayee
           │
           ▼
        member_payables (INSERT, UNIQUE claim_id, + audit)
           │
           ├─ (opt-in) auto-draft CtcPayment
           │
           ▼
        MemberPayableBalanceRepository.findOutstandingByCurrency
           │
           ▼
        [NO HTTP endpoint yet] ─── needed for /tenant/finance/creditors  ◄── UNIFIED SURFACE
```

Contributions-side (parallel, unrelated to claims):

```
BillingService charge/pay → BalanceService.apply{Debit,Paid,Transaction,WriteOff}
    │
    ▼
member_running_balance / group_running_balance
    │  (+ Kafka: medfund.balances.member-updated / .group-updated)
    ▼
GET /api/v1/billing/balances/creditors   ──► /tenant/billing/creditors     (misnamed — arrears)
GET /api/v1/billing/balances/bad-debts   ──► /tenant/billing/bad-debts
GET /api/v1/billing/balances/aged-balances
```

## Architecture doc vs. code

- **`.claude/payments.md` and `.claude/adjudication.md`** describe the provider payout path but do not name the "Creditors" list; the UI label was invented in the Angular layer.
- **No `.claude/*.md` doc distinguishes creditor (payable) from debtor (receivable)** — that's why the same word ended up on both sides.
- **`.claude/multi-currency.md`**'s guidance is honoured — every balance is stored with `currency_code` and never summed cross-currency; both `provider_balances.currency_code` and `member_payables.currency_code` participate as UNIQUE / grouping keys.

Drift to flag when planning:
- The frontend permission catalogue conflates the two: `billing:view_creditors` gates the finance-side "Provider Creditors" nav (`operational-nav.ts:139`) despite the permission label describing the billing side. If we split the pages we should split the permission — `finance:view_creditors` (payables) vs `billing:view_arrears`/`billing:view_debtors` (receivables).

## Code References

Finance-service (provider ledger — rename target):
- `services/java/finance-service/src/main/java/com/medfund/finance/controller/ProviderBalanceController.java:30,36,51` — three endpoints
- `services/java/finance-service/src/main/java/com/medfund/finance/service/ProviderBalanceService.java:66,101` — updateBalance + outstanding recompute
- `services/java/finance-service/src/main/java/com/medfund/finance/repository/ProviderBalanceQueryRepository.java:39` — dynamic search SQL
- `services/java/finance-service/src/main/java/com/medfund/finance/entity/ProviderBalance.java:12` — `@Table("provider_balances")`
- `services/java/finance-service/src/main/java/com/medfund/finance/dto/ProviderBalanceRow.java` — paginated row DTO with `providerName`
- `services/java/finance-service/src/main/java/com/medfund/finance/dto/ProviderBalanceResponse.java`
- `services/java/finance-service/src/main/java/com/medfund/finance/dto/ProviderBalanceFilterParams.java`

Finance-service (member ledger — read side available, HTTP surface missing):
- `services/java/finance-service/src/main/java/com/medfund/finance/consumer/ClaimAdjudicatedConsumer.java:114,121,133,288` — dispatch + branches
- `services/java/finance-service/src/main/java/com/medfund/finance/repository/MemberPayableRepository.java`
- `services/java/finance-service/src/main/java/com/medfund/finance/repository/MemberPayableBalanceRepository.java:39,77,119` — the three ready-to-use aggregate reads
- `services/java/finance-service/src/main/java/com/medfund/finance/entity/MemberPayable.java:21`
- `services/java/finance-service/src/main/java/com/medfund/finance/entity/MemberPayableApplication.java:23`
- `services/java/finance-service/src/main/java/com/medfund/finance/entity/CtcPayment.java:62`
- `services/java/tenancy-service/src/main/resources/db/migration/tenant/V069*.sql`, `V071__payment_run_generation_and_advice_ledger.sql`

Contributions-service (receivables — collision):
- `services/java/contributions-service/src/main/java/com/medfund/contributions/controller/BalanceController.java:74,90,108` — creditors/bad-debts/aged endpoints
- `services/java/contributions-service/src/main/java/com/medfund/contributions/service/BalanceService.java:67,106,135,156,205` — mutations + list reads
- `services/java/contributions-service/src/main/java/com/medfund/contributions/repository/BalanceQueryRepository.java:37,51,131,141` — creditor UNION SQL
- `services/java/contributions-service/src/main/java/com/medfund/contributions/dto/CreditorRow.java`
- `services/java/contributions-service/src/main/java/com/medfund/contributions/dto/BalanceRow.java`
- `services/java/contributions-service/src/main/java/com/medfund/contributions/service/CreditorsExcelService.java:40,55,68`
- `services/java/contributions-service/src/main/java/com/medfund/contributions/service/BadDebtsExcelService.java`
- `services/java/tenancy-service/src/main/resources/db/migration/tenant/V010__running_balances.sql:8`

Angular:
- `clients/angular/src/app/pages/tenant/finance/finance.routes.ts:287,293,299` — three finance routes
- `clients/angular/src/app/pages/tenant/finance/creditors/creditors-list.component.ts` and `.html`
- `clients/angular/src/app/pages/tenant/finance/creditors/provider-balance-detail.component.ts` and `.html`
- `clients/angular/src/app/pages/tenant/billing/billing.routes.ts:248,257`
- `clients/angular/src/app/pages/tenant/billing/creditors/creditors-list.component.ts`
- `clients/angular/src/app/pages/tenant/billing/bad-debts/bad-debts-list.component.ts`
- `clients/angular/src/app/core/services/finance.service.ts:83,404,416,730,742`
- `clients/angular/src/app/core/services/balance.service.ts:23,36,59`
- `clients/angular/src/app/layout/operational-sidebar/operational-nav.ts:81,82,83,139,147`
- `clients/angular/src/app/core/security/permissions.ts:108`

## Architecture Insights

- **Two writer models coexisting is deliberate.** Providers get a pre-aggregated 4-column ledger because the history of claimed vs approved vs paid matters (provider payout runs). Members get an event-log ledger (`member_payables` + `member_payable_applications`) because the volume is low and the derived-on-read aggregate lets CTC applications reconcile per row without touching a snapshot. Any "unified creditors" DTO has to accept that provider rows carry three history columns members cannot; either hide them for MEMBER rows or split the list into two visual sections with a shared filter/currency/search.
- **Idempotency guards on both sides**: `member_payables` UNIQUE on `claim_id` (V069) with catch-and-ignore in `handleMemberPayee` (line 166–169), and `provider_balances` UNIQUE on `(provider_id, currency_code)` with in-place update. Kafka replays are safe.
- **All balance mutations emit audit events** with the four field names as the changed-set — satisfies Critical Rule #8 in `.claude/CLAUDE.md`.
- **Tenant scoping** relies on `TenantContext` in the R2DBC layer; no repository shortcircuits it. Critical Rule #2 is honoured; when adding the new member-listing endpoint, mirror `ProviderBalanceController`'s implicit tenant handling (no explicit `@PreAuthorize`; JWT + TenantContext do the work).
- **Multi-currency** is a per-row grouping key everywhere — a member with USD and ZWL outstandings will show two rows in a unified creditors list, same as providers do today.
- **The `finance:view_debtors` permission is unused for its literal purpose** — currently only guards Finance → Reports (`operational-nav.ts:147`). It's a naming trap: "debtors" is right if the report shows arrears (billing side), wrong if it shows creditor payables (finance side).

## What this ticket needs decided

_(Answered when we move to `create-plan`; these are the forks a plan has to resolve.)_

- **Naming resolution — SETTLED (2026-08-10):** contributions-side is **Debtors**, finance-side is **Creditors**. This is Option B from the earlier drafting; the other options are no longer live. Concrete scope the plan must cover:
  - **Contributions-service (Debtors)**
    - Rename REST base path `/api/v1/billing/balances/creditors` → `/api/v1/billing/balances/debtors` (and `/creditors/export/excel` → `/debtors/export/excel`). Java classes `CreditorRow`, `CreditorsExcelService`, and the `BalanceController.listCreditors` / `exportCreditorsExcel` / `BalanceService.listCreditors` method surfaces rename to `DebtorRow`, `DebtorsExcelService`, `listDebtors`, `exportDebtorsExcel`. `BalanceQueryRepository.findCreditors` / `countCreditors` → `findDebtors` / `countDebtors`.
    - Angular route `/tenant/billing/creditors` → `/tenant/billing/debtors`; folder `clients/angular/src/app/pages/tenant/billing/creditors/` → `.../debtors/`; component and specs renamed in kind; `BalanceService.listCreditors` / `exportCreditorsExcel` on `clients/angular/src/app/core/services/balance.service.ts:59,76` renamed to `listDebtors` / `exportDebtorsExcel`; the `CreditorRow` interface (line 23) renamed to `DebtorRow`.
    - Sidebar entry (`clients/angular/src/app/layout/operational-sidebar/operational-nav.ts:81`) label `"Creditors"` → `"Debtors"`.
    - Excel export filename prefix `creditors-…xlsx` → `debtors-…xlsx` (`CreditorsExcelService.java:55,68`, `BalanceController.java:99`).
    - Copy in `clients/angular/src/app/pages/tenant/billing/creditors/creditors-list.component.html:3-4` and empty-state message ("No outstanding balances") updated to Debtors phrasing.
    - Bad Debts page unchanged (it already carries the correct term).
  - **Finance-service (Creditors, unified)**
    - Rename controller/service/repo/entity/DTO/table from `ProviderBalance*` → the neutral **Creditor** naming. Concretely: `ProviderBalanceController` → `CreditorController` (base path `/api/v1/creditors` — provider-facing endpoints get subject-typed under it), `ProviderBalanceService` → `CreditorService`, `ProviderBalanceQueryRepository` → `CreditorQueryRepository`, `ProviderBalance` entity → `ProviderCreditorBalance` (keeping `provider_id` FK; the "provider" here is the FK column, not the concept), DTOs `ProviderBalanceResponse` / `ProviderBalanceRow` / `ProviderBalanceFilterParams` renamed to their Creditor equivalents.
    - Add a MEMBER half to the unified list: new `MemberCreditorBalance` read model backed by `MemberPayableBalanceRepository.findOutstandingByCurrency` (`services/java/finance-service/src/main/java/com/medfund/finance/repository/MemberPayableBalanceRepository.java:77`) joined to `members` for name/code/email; exposed on the same controller under a subject-type discriminator (see the "Column shape" and "list layout" forks below for the exact DTO shape).
    - Angular finance routes collapse: `/tenant/finance/creditors/provider` + `/tenant/finance/creditors/member` → single `/tenant/finance/creditors` with a subject-type filter (PROVIDER / MEMBER / both), mirroring the billing MEMBER/GROUP tab pattern. Provider detail page moves to `/tenant/finance/creditors/provider/:id`; a symmetric member detail page lives at `/tenant/finance/creditors/member/:id`.
    - Sidebar entry `"Provider Creditors"` (`operational-nav.ts:139`) → `"Creditors"`, route `/tenant/finance/creditors`.
    - Database: **keep** the table name `provider_balances` (the FK column is `provider_id`; renaming a live table on every tenant schema for a UI concept change isn't worth the migration risk). Add a peer read model / view for members if aggregating on read becomes a bottleneck — otherwise stay derived (see the "Ledger for the member creditor read" fork).
  - **Permission catalogue**
    - New `finance:view_creditors` permission — gates the unified finance-side Creditors page (both list and detail). Update `services/java/shared/src/main/java/com/medfund/shared/security/Permissions.java` and `PermissionCatalogue.java`, plus `clients/angular/src/app/core/security/permissions.ts`.
    - Repurpose `finance:view_debtors` — currently gates only Finance → Reports (`operational-nav.ts:147`) — as the gate for the billing-side Debtors page. Update `permissions.ts:108` label ("View outstanding balances owed by members and groups" is already the right meaning; move it under a `finance:view_debtors` / `billing:view_debtors` key — pick one, see below).
    - Decide whether the billing-side gate is `finance:view_debtors` (already exists) or a fresh `billing:view_debtors` (cleaner namespace). Recommend **fresh `billing:view_debtors`**; free `finance:view_debtors` for a possible future finance-owned debtors surface (e.g. member payables that were reversed).
    - Deprecate `billing:view_creditors` — grep-and-replace across ~30 Angular + Java references (routes, `@PreAuthorize`, nav items, permission checks in components).
  - **Backwards-compat / migration**
    - Path renames are **breaking API changes**; ship the new endpoints, keep the old paths as 301 / thin wrappers for one release, then delete. Same treatment for the Angular route (`RedirectComponent` from old path → new).
    - Keycloak role-to-permission mappings need to gain the new permission keys via the tenancy-service's role bootstrap migration (see the `V124__grant_staff_users_to_tenant_roles.sql` pattern).
    - Excel export filename change is a downstream user-visible break — flag in release notes.
- **Column shape for the unified list.** Provider rows have four money columns (`totalClaimed`, `totalApproved`, `totalPaid`, `outstandingBalance`); member rows have one (`outstanding`). Plan needs to pick: (i) single common shape (subjectType, subjectName, currencyCode, outstanding, lastActivity), pushing detail into per-subject drilldowns; (ii) two-tab UI with shared filters but distinct grids; (iii) union DTO with nullable provider-only columns.
- **Ledger for the "member creditor" read.** Add an HTTP endpoint that wraps `MemberPayableBalanceRepository.findOutstandingByCurrency` and joins to `members` for name/email/member_number. Decisions inside that: paginate at SQL level (add LIMIT/OFFSET to the CTE), or leverage a materialized snapshot (`member_balances`)? Design decision #2 in `thoughts/shared/plans/2026-08-09-ctc-payments.md` argues *against* a snapshot; unifying with providers doesn't invalidate that argument, but a large-tenant listing (10k+ members) will need SQL-side paging on the CTE, which is easy.
- **Member detail page.** The finance placeholder route `/tenant/finance/creditors/member` currently reuses `CreditorsListComponent`. Do we build a member-balance detail page (mirroring `provider-balance-detail.component.ts`) showing member payables + applications + CTCs, or defer it? The `Payment` and `Adjustment` DTOs already carry `memberId` so the tab structure can be replicated for free.
- **Permission split.** Should we introduce `finance:view_creditors` (payables side) and leave `billing:view_creditors` on the arrears side, or reuse `finance:view_debtors`? Reusing it is misleading; splitting it is one migration row + one Angular permissions.ts update.
- **Should the merged Creditors page be one paginated list with a PROVIDER/MEMBER toggle (mirroring billing's MEMBER/GROUP tabs), or two lists on one page (accordion / two-column)?** The billing precedent is a `subjectType` tab strip inside `DataTableComponent` — cheap to replicate.

## Gaps between spec and code

_(The delta this ticket actually asks for.)_

| Doc / promise | Code today | Delta |
|---|---|---|
| `/tenant/finance/creditors/member` route promises member liabilities (line 299–308) | Reuses provider list component as placeholder | Wire a `MemberBalanceController` on top of `MemberPayableBalanceRepository`, add `MemberBalanceService` + `MemberBalanceRow` + `MemberBalanceFilterParams`, wire Angular `FinanceService.listMemberBalancesPaged(...)`, replace the reused component with a real member-list component. |
| "Provider Creditors" sidebar label (operational-nav.ts:139) — the very label the user wants dropped | Hardcoded | Rename to "Creditors"; rename the billing sidebar entry (operational-nav.ts:81) from "Creditors" to "Debtors" at the same time. |
| `.claude/CLAUDE.md` framing rule ("prefer line-neutral wording — 'claim', 'policy', 'beneficiary'") | UI copy uses "Provider" and (billing) "Member/Group" as first-class labels | Line-agnostic "Creditor" copy on the finance side satisfies the framing rule; "Debtor" on the billing side is symmetric. |
| `permissions.ts:108` label says the permission covers "members and groups" | Same `billing:view_creditors` permission gates finance-side "Provider Creditors" too | Split into `finance:view_creditors` (unified Creditors page) and `billing:view_debtors` (new — replaces the misnamed `billing:view_creditors`); update Java `PermissionCatalogue.java` in `services/java/shared` and add the Keycloak role mapping migration. |
| Provider list has no Excel export (asymmetric vs. billing creditors/bad-debts which do) | Confirmed missing | Optional: add `/api/v1/provider-balances/export/excel` (and the member analogue) to reach parity. Not strictly in scope for "rename + show balances" but a natural follow-up. |

## Historical Context (from thoughts/shared/)

- `thoughts/shared/plans/2026-08-09-ctc-payments.md` — design decision #2 (why member payables use a derived aggregate, not a snapshot table) and #4 (auto-CTC stays as a draft, never auto-commit). Both bear directly on how a unified "creditors" list handles the member half.
- `thoughts/shared/plans/2026-08-10-audit-path-431-shared-fiber-httpserver.md` — unrelated infra work touched during this session; not relevant.
- `docs/future-work/balance-seeder.md` — outstanding future work on balance seeding for demo/staging (referenced by tests).
- Memories that shape the plan: [[project_ctc_is_opt_in]] (member payables are already the substrate for CTC and cash payouts), [[feedback_grouped_members_cannot_pay]] (grouped members roll up to group liaison — the equivalent question for creditor listings is whether we show individual payables to a grouped member; today they show, because payables live on the individual, not the group), [[feedback_audit_actor_email]] and [[feedback_audit_entity_name]] (whatever new endpoints we add must go through the shared AuditActor helper and set a friendly entityName), [[feedback_stats_serverside]] (any KPI on the creditor page must come from a server-side aggregation endpoint, not client math), [[feedback_no_raw_id_inputs]] (any filter picker for a specific provider/member must be a debounced search-select, not a raw UUID input).

## Related Research

No prior research doc covers the creditors listings specifically. `thoughts/shared/plans/2026-08-09-ctc-payments.md` is the most adjacent artifact and worth reading in full before planning.

## Open Questions

- Do tenants ever need a **combined outstanding total across providers and members** (a KPI on the finance dashboard), or is the per-subject list enough? If yes, we'll need a sum endpoint that aggregates `SUM(outstanding_balance)` from `provider_balances` and the derived member outstanding — trivially two queries, but touches Critical Rule #1 (no cross-currency arithmetic) so must be per currency.
- Is a member's outstanding payable ever the tenant's write-off candidate (analogous to bad debts on the billing side)? Today `member_payables.status='reversed'` handles reversal, but there's no equivalent "unpaid to member, aged out, written off" flow. Out of scope for the current ask but worth flagging.
- Should the finance Creditors page also show pending payment runs / advices per subject (a "you're about to be paid" hint), or keep those in the existing Payment Advices list? Current provider-detail page tabs on Payments + Adjustments; PaymentAdvices live under their own route.

---

## Follow-up Research 2026-08-10 — Naming decision settled

**Trigger:** conversation reached alignment on the semantic canon (see the earlier "What are creditors in the context of this application?" exchange).

**Decision:** the two competing meanings of "creditor" are being separated. Concretely:

- **Contributions-side (people who owe the tenant)** — the `/tenant/billing/creditors` page, the `/api/v1/billing/balances/creditors` API, the `CreditorRow` / `CreditorsExcelService` classes, and the `billing:view_creditors` permission — is renamed **Debtors**. This aligns the label with standard accounting: a party that owes you money is your debtor.
- **Finance-side (parties the tenant owes for approved claims)** — the `/tenant/finance/creditors/provider` page and `provider_balances` ledger — drops the "Provider" qualifier and becomes **Creditors**. The list is expanded to include **members** with outstanding `member_payables` alongside providers, driven by the already-existing `MemberPayableBalanceRepository.findOutstandingByCurrency` aggregate.
- Permission catalogue changes: introduce **`finance:view_creditors`** for the unified finance Creditors page; introduce **`billing:view_debtors`** for the renamed billing Debtors page; deprecate `billing:view_creditors`. Leave `finance:view_debtors` alone (still gating Finance → Reports) or repurpose — a plan-time call.

**Where this shows up in the doc:** the Summary table now carries a "New label" column, the "What this ticket needs decided" fork under Naming resolution has been rewritten as a concrete scope list (no longer three options), and the Gaps table now names the debtor half of the rename. All other sections (Findings, Cross-service flow, Code References, Architecture Insights) remain accurate — they describe the state today, which is what a `create-plan` run needs as its baseline.

**Non-goals of this rename:**
- Database table `provider_balances` is **not** renamed. Its `provider_id` FK column stays. Renaming a live tenant-schema table for a UI-concept change costs more than it buys, and touches every tenant's Flyway history. The Java entity gets a friendlier class name (`ProviderCreditorBalance` was suggested); the table stays.
- The four-column provider ledger (`totalClaimed`, `totalApproved`, `totalPaid`, `outstandingBalance`) is **not** being replicated for members. Members keep the derived-aggregate model documented in `thoughts/shared/plans/2026-08-09-ctc-payments.md` design decision #2. The unified list either exposes only the common columns (subjectType, subjectName, currencyCode, outstanding, lastActivity) with provider-only detail in the drilldown, or uses a nullable-columns union DTO — a plan-time call.
- Bad Debts (billing side) is **not** renamed — the term is already correct.
