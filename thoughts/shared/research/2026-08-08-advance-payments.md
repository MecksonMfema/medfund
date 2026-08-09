---
date: 2026-08-08T19:41:28+02:00
researcher: Methuseli
git_commit: 1fd6c94838d659eaf5d0d56d689f87acb1b1b6e8
branch: main
repository: medfund
topic: "Advance payments — what exists end-to-end and where the wiring is incomplete"
tags: [research, codebase, finance-service, angular, rules-engine, payment-runs]
status: complete
last_updated: 2026-08-08
last_updated_by: Methuseli
last_updated_note: "Corrected UI trigger claim — the record-advance form component and its route exist but nothing in the app navigates to them; the create endpoint is effectively unreachable via UI."
---

# Research: Advance payments across InsureFlow

**Date**: 2026-08-08T19:41:28+02:00 · **Researcher**: Methuseli · **Commit**: `1fd6c94` · **Branch**: `main`

## Research Question

What does the "advance payments" feature look like end-to-end in InsureFlow — data model, service, controller, UI, permissions, events, and how (or whether) an advance actually offsets a real payment when a run is generated?

## Summary

Advance payments are **half-connected**: the backend has full CRUD-lite (entity → repository → service → controller → audit), and the Angular tree has all three page components (list, form, detail) with routes registered, **but there is no UI path to the record form** — the list page has no "Record" button, no sidebar entry links to `/payments/advance/add`, and a repo-wide grep finds zero anchors to that route. The form component and its route exist as orphaned code. Consequences: **the only way to record an advance today is a direct URL type-in or a manual `POST /api/v1/advance-payments`** (e.g. via API client / gateway proxy). No operator using the app normally can create one.

The second gap sits downstream: even if an advance is recorded, it's **not plumbed into the payment-run offset flow**. The rules-engine `PaymentRunFact` carries an `advancePaid` field, and `PaymentRunDecisionService.decide(item, advancePaid)` accepts it, but the single production call site (`PaymentRunService.applyTenantRulesToItems`, line 241) uses the zero-arg overload that hard-codes `advancePaid = BigDecimal.ZERO`. When a payment run is generated for the same provider/member, the run has no idea the advance exists — no aggregation, no withhold, no offset.

Additional notes:
- The model is **append-only**: no update, delete, cancel, or reversal endpoints exist. The `paymentId` FK on `advance_payments` is nullable and unpopulated by any known write path — it's a placeholder for a future reconciliation link.
- Audit publishing is wired (create → `AuditPublisher`) but no domain Kafka topic is emitted; only audit events go out.
- Architecture docs (`.claude/payments.md`, `.claude/architecture.md`) name the concept but do **not** define its lifecycle, offset semantics, or reversal rules. The current implementation is one clerk-facing recording surface, not a settled workflow.

## Findings

### Backend — finance-service

**Entity + persistence**
- `AdvancePayment` entity: `services/java/finance-service/src/main/java/com/medfund/finance/entity/AdvancePayment.java:1-49` — R2DBC-mapped to `advance_payments`. Fields: `id`, `paymentId` (nullable FK), `providerId` or `memberId` (one required by DB check), `amount`, `currencyCode`, `paymentMethod`, `reference`, `comment`, `recordedAt`, `recordedBy`.
- Migration: `services/java/tenancy-service/src/main/resources/db/migration/tenant/V016__finance_schema.sql:135` — table + `CHECK (provider_id IS NOT NULL OR member_id IS NOT NULL)` at the DB layer, plus FK `payment_id → payments(id) ON DELETE SET NULL` (line 137) and indexes on `provider_id`/`member_id` (lines 150-151).
- Repositories: `AdvancePaymentRepository` (basic CRUD + `findByProviderId`/`findByMemberId`) and `AdvancePaymentQueryRepository` (paged search with provider/member name joins and free-text filter on `reference`/`comment`/names). Both under `services/java/finance-service/src/main/java/com/medfund/finance/repository/`.
- Permission seed: `services/java/tenancy-service/src/main/resources/db/migration/tenant/V006__rbac_refinements.sql:57` — declares both `finance:view_advance_payments` and `finance:manage_advance_payments`.

**Service**
- `AdvancePaymentService` at `services/java/finance-service/src/main/java/com/medfund/finance/service/AdvancePaymentService.java`.
  - `create(request, actor)` line 63 — validates target present, persists, publishes audit event via `AuditPublisher`. Audit event `publishAudit()` lines 79-102 captures `after`: amount, currencyCode, providerId, memberId, reference.
  - `searchPaged(params)` line 40, plus `findById`, `findByProvider`, `findByMember`, `findAll` for reads.
  - **No update, cancel, refund, or reverse method.** Append-only in both DB (no updated_at column) and API.

**Controller**
- `AdvancePaymentController` at `services/java/finance-service/src/main/java/com/medfund/finance/controller/AdvancePaymentController.java`:
  - `GET /api/v1/advance-payments` line 34 — full list, filter by `providerId`/`memberId`
  - `GET /api/v1/advance-payments/page` line 43 — paged search (feeds the Angular list)
  - `GET /api/v1/advance-payments/{id}` line 61
  - `POST /api/v1/advance-payments` line 67 → 201
- DTOs (same package): `CreateAdvancePaymentRequest`, `AdvancePaymentResponse`, `AdvancePaymentRow`, `AdvancePaymentFilterParams`.
- No security annotations on controller methods — enforcement is at the gateway.

**Tests**
- `services/java/finance-service/src/test/java/com/medfund/finance/service/AdvancePaymentServiceTest.java`:
  - `create_providerOnly_persistsAndAudits` line 39
  - `create_memberOnly_persists` line 68
  - `create_neitherProviderNorMember_errors` line 87 — enforces the DB check at service layer
  - Delegation tests for `findByProvider`/`findByMember`
- **No test covers offset behaviour**, because the offset code path isn't wired (see gap below).

### Gateway — Go

- `services/go/gateway/internal/routes/routes.go:119-120` — proxies `/api/v1/advance-payments` and `/api/v1/advance-payments/*` to `FinanceServiceURL`. No custom logic; pure pass-through.
- **No advance-payment references in Go audit/notification/payment-gateway services, Elixir umbrella, Python AI service, or Flutter client.** Feature is Java + Angular only.

### Rules engine — the offset seam that isn't connected

- `services/java/rules-engine/src/main/java/com/medfund/rules/fact/PaymentRunFact.java:30` — declares `private BigDecimal advancePaid;` as a fact input. Drools rules can key on it (e.g. "if `advancePaid >= amountDue` then withhold").
- `services/java/finance-service/src/main/java/com/medfund/finance/service/PaymentRunFactBuilder.java:36` — `build(item, advancePaid)` accepts and copies the value to the fact at line 58.
- `services/java/finance-service/src/main/java/com/medfund/finance/service/PaymentRunDecisionService.java`:
  - Line 40: `decide(item, advancePaid)` — the real overload.
  - Line 65: `decide(item)` — no-arg overload that calls `decide(item, BigDecimal.ZERO)`.
- **Call site is the zero-arg one:** `services/java/finance-service/src/main/java/com/medfund/finance/service/PaymentRunService.java:241` — `decisionService.decide(item)` inside `applyTenantRulesToItems`. There is no code path that aggregates a provider's or member's outstanding advance-payment balance and passes it in.

**Effect:** Advance payments live in a parallel ledger. Recording one produces an auditable row and (if the paged list is exported) a report, but the money never affects a subsequent `PaymentRun` unless someone manually adjusts the run item. The docs treat advance-vs-run offset as a policy decision that would be expressed as tenant Drools rules on `PaymentRunFact.advancePaid` — but the value fed to those rules is always zero today.

### Angular UI — clients/angular

**Routes** (`clients/angular/src/app/pages/tenant/finance/finance.routes.ts:83-99`)
- `/tenant/finance/payments/advance` → `AdvancePaymentsListComponent` (guard: `finance:view_advance_payments`)
- `/tenant/finance/payments/advance/add` → `AdvancePaymentFormComponent` (guard: `finance:manage_advance_payments`)
- `/tenant/finance/payments/advance/:id` → `AdvancePaymentDetailComponent` (guard: `finance:view_advance_payments`)

**Components** — under `clients/angular/src/app/pages/tenant/finance/advance/`:
- `advance-payments-list.component.ts` — server-paginated `DataTable` (Provider, Member, Amount, Currency, Method, Reference, Recorded). Search + sort + pagination all delegated to the backend. **No "Record advance payment" button in the header** (`advance-payments-list.component.html:1-9`) — the header is title + subtitle only, no CTA.
- `advance-payment-form.component.ts` — **orphaned component**. Segmented toggle between provider and member payee (each uses `EntityPickerComponent`), amount, currency (`SelectComponent` fed by `CurrencyService.listMaster`), method, reference, optional comment. Submit calls `FinanceService.createAdvancePayment`. The route at `finance.routes.ts:89-93` resolves it, but a repo-wide grep for `payments/advance/add` returns only that route definition — nothing anchors, `routerLink`s, or navigates to it. Reachable only by pasting the URL.
- `advance-payment-detail.component.ts` — read-only `dt/dd` grid. Shows the `paymentId` FK if present — currently always null. No edit or cancel actions.

**No UI trigger for create.** The three pieces (list button, sidebar sub-item, dashboard shortcut) that normally launch a form are all absent. The `finance:manage_advance_payments` permission gates a route that no signed-in user can reach through the app UI.

**Service layer** (`clients/angular/src/app/core/services/finance.service.ts:386-620`)
- Types: `AdvancePayment` (line 387-399), `AdvancePaymentRow` (297-311, with joined names), `AdvancePaymentPageParams` (313-322), `CreateAdvancePaymentPayload` (401-409).
- Methods: `listAdvancePayments`, `listAdvancePaymentsPaged`, `getAdvancePayment`, `createAdvancePayment`. No update/delete methods — mirrors backend append-only shape.

**Permissions** (`clients/angular/src/app/core/security/permissions.ts:34,116-117`)
- `finance:view_advance_payments` — "View provider prepayments"
- `finance:manage_advance_payments` — "Create, edit, or cancel provider prepayments" (label mentions edit/cancel but no such UI or endpoints exist)

**Other UI surfaces**
- Sidebar: `clients/angular/src/app/layout/operational-sidebar/operational-nav.ts:137` — Finance section, unconditional (no feature flag).
- Dashboard: `clients/angular/src/app/pages/tenant/dashboard/dashboard.component.html:444-451` + `.ts:40` — stat card showing `paymentsAdvanceCount` and `paymentsAdvanceAmount`, sourced from `AdminService.TenantStats` (`clients/angular/src/app/core/services/admin.service.ts:155-156`). Server-computed KPI, not a client aggregation.
- **No mentions** in claims, contributions, member-detail, or provider pages. The concept is isolated to its own three pages plus one dashboard tile.

### Events & audit

- Every `create` publishes an `AuditEvent` (`AdvancePaymentService.publishAudit` line 79-102) with tenant, entity type `AdvancePayment`, action `CREATE`, actor id/email, and before/after payloads. Uses `getReference()` as the friendly `entityName` per the standard set in coding-standards.md.
- **No domain Kafka topic** is published — there is no `medfund.finance.advance.created` event or equivalent. `FinanceEventPublisher` publishes only `PaymentRun*` lifecycle events.
- Nothing else in the platform subscribes to advance-payment state changes.

## Cross-service flow

Recording an advance today (only reachable by direct URL / API — see UI section):

```
[no in-app UI trigger]
  ↳ user pastes /tenant/finance/payments/advance/add  → AdvancePaymentFormComponent
  ↳ OR direct POST /api/v1/advance-payments (API client, curl, etc.)
    → Gateway proxy (services/go/gateway/internal/routes/routes.go:119)
      → Java AdvancePaymentController.create (finance-service:8085)
        → AdvancePaymentService.create
          → AdvancePaymentRepository.save  (INSERT into advance_payments)
          → AuditPublisher.publish         (→ Kafka audit topic → audit-service)
        ← 201 AdvancePaymentResponse
```

Generating a payment run **does not** consult `advance_payments`. The run building path (`PaymentRunService`) reads pending claim payables, builds items, then runs rules with `advancePaid = ZERO`. No SQL joins to `advance_payments`, no aggregation query, no offset writeback.

## Architecture doc vs. code

`.claude/architecture.md:87,92` lists advance payments as a first-class Finance Service capability, grouped with `ProviderBalance`, `Adjustment`, `DebitNote`, `CreditNote`.

`.claude/payments.md` and `.claude/adjudication.md` do **not** define an advance-payment lifecycle, offset ordering against claim settlements, reversal rules, or approval workflow.

`.claude/rules-engine.md` describes a `ProviderPayment` template category, but no `AdvancePaymentOffset` category and no starter template. The `PaymentRunFact.advancePaid` field is engine surface area with no default rules and no populated data.

**Drift call-outs:**
1. Docs treat advance payments as a settled entity. Code implements them as a recording surface with no consumer downstream.
2. The `finance:manage_advance_payments` permission label promises edit and cancel actions that don't exist in either the backend or UI.
3. `AdvancePayment.paymentId` FK on the DB (`V016__finance_schema.sql:137`) implies a reconciliation link between an advance and the later real payment; no code path in the repo writes to it.
4. **The form UI is orphaned.** `AdvancePaymentFormComponent` and its route are shipped but no anchor, button, or sidebar link points to `/tenant/finance/payments/advance/add`. This is either an in-flight slice that stopped short of wiring the CTA, or a component intentionally hidden pending a governance decision — the git log will disambiguate.

## Code References

- `services/java/finance-service/src/main/java/com/medfund/finance/entity/AdvancePayment.java:1-49` — entity
- `services/java/finance-service/src/main/java/com/medfund/finance/service/AdvancePaymentService.java:40,63,79` — searchPaged, create, publishAudit
- `services/java/finance-service/src/main/java/com/medfund/finance/controller/AdvancePaymentController.java:34,43,61,67` — endpoints
- `services/java/finance-service/src/main/java/com/medfund/finance/service/PaymentRunDecisionService.java:40,65` — real vs zero-arg decide overload
- `services/java/finance-service/src/main/java/com/medfund/finance/service/PaymentRunService.java:241` — the call site that hard-wires ZERO
- `services/java/finance-service/src/main/java/com/medfund/finance/service/PaymentRunFactBuilder.java:36,58` — advancePaid → fact
- `services/java/rules-engine/src/main/java/com/medfund/rules/fact/PaymentRunFact.java:30` — the fact field
- `services/java/tenancy-service/src/main/resources/db/migration/tenant/V016__finance_schema.sql:135-151` — table + FK + indexes
- `services/java/tenancy-service/src/main/resources/db/migration/tenant/V006__rbac_refinements.sql:57` — permission seed
- `services/go/gateway/internal/routes/routes.go:119-120` — gateway proxy
- `clients/angular/src/app/pages/tenant/finance/finance.routes.ts:83-99` — routes
- `clients/angular/src/app/pages/tenant/finance/advance/` — list, form, detail components
- `clients/angular/src/app/core/services/finance.service.ts:386-620` — types + methods
- `clients/angular/src/app/core/security/permissions.ts:116-117` — permission keys
- `clients/angular/src/app/layout/operational-sidebar/operational-nav.ts:137` — sidebar entry
- `clients/angular/src/app/pages/tenant/dashboard/dashboard.component.html:444-451` — dashboard tile
- `.claude/architecture.md:87,92` — doc reference

## Architecture Insights

- **Critical Rule #1 (currency):** The recording surface stores `currencyCode` per advance but no conversion happens on offset — because no offset happens at all. When the offset feature is wired, cross-currency advance vs claim currency will need the exchange-rate service to compare. Today it's technically compliant only because the arithmetic doesn't occur.
- **Critical Rule #2 (tenant scoping):** All access is via the R2DBC repositories under the tenant-scoped connection, and the paged endpoint filters happen at the SQL layer. No cross-tenant leak surface observed.
- **Critical Rule #5 (rules-engine):** The offset **should** live as tenant Drools rules on `PaymentRunFact.advancePaid`. That plumbing is half-built: fact field ✅, decide overload ✅, template category ❌, populated call site ❌.
- **Critical Rule #7 (Swagger):** Confirm all four endpoints have OpenAPI annotations — not verified in this pass.
- **Critical Rule #8 (audit):** Create audit fires with proper `AuditActor` + friendly `entityName` (`getReference()`). Good. But because there's no update/delete, that's the entire audit surface.
- **Append-only design choice:** The DB has no `updated_at` and no `status` column. If corrections are needed, the current implicit answer is "post a compensating negative advance," which no code enforces. This will bite when the offset flow lands — a reversed advance today has no way to be marked reversed.

## Historical Context (from thoughts/shared/)

None. No prior research doc in `thoughts/shared/research/` mentions advance payments.

## Open Questions

1. **Wire the record CTA — or hide the route:** Is the missing "Record advance payment" button a delivery gap that should ship (button in `advance-payments-list.component.html` header linking to `payments/advance/add`), or was the form intentionally suppressed pending offset/governance? Deleting the orphan or wiring it are both reasonable next steps.
2. **Offset semantics:** When a payment run pays a provider who has outstanding advances, should the advance auto-reduce the run item (rule-driven `withhold`), or should it stay as a parallel provider balance that shows up in reconciliation reports?
3. **Reversal:** Is the intent to add reversal endpoints, or to keep the append-only model and require compensating entries?
4. **Consumption tracking:** Should `AdvancePayment.paymentId` become the "consumed by" back-reference, or is that FK repurposable for a different link?
5. **Cross-currency:** If a USD advance is offset against a ZWL claim run item, at what date's exchange rate?
6. **Multiple payees per advance:** Current model is exactly one of provider XOR member. Any need for a group / scheme-level advance?
7. **Approval gate:** Should recording an advance above a threshold require dual approval (mirroring PaymentRun's approve → execute flow)?
