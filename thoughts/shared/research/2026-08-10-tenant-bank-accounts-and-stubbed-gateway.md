---
date: 2026-08-10T22:15:00+02:00
researcher: Methuseli
git_commit: 0a1609d72451938c1e12346b63f7f6595122b8e5
branch: rename-adjustments-to-notes
repository: medfund
topic: "Tenant-configured bank accounts, retirement of MASCA platform banks, and a stubbed payment-gateway workflow"
tags: [research, codebase, finance-service, tenancy-service, payment-gateway, angular, multi-tenancy, masca]
status: complete + grilled 2026-08-10
last_updated: 2026-08-10
last_updated_by: Methuseli
grilling: 2026-08-10 — 10 decisions settled (G1–G9 + G3b); all 17 load-bearing claims re-verified against the codebase before grilling; see "Grilling decisions" section
---

# Research: Tenant-configured bank accounts and a stubbed payment-gateway workflow

**Date**: 2026-08-10T22:15:00+02:00 · **Researcher**: Methuseli · **Commit**: 0a1609d7 · **Branch**: rename-adjustments-to-notes

> **Grilled 2026-08-10** — 10 decisions (G1–G9 + G3b) are now settled at the end of this document under **Grilling decisions**. The Summary, Architecture Insights, and Open Questions sections below have been updated in-line with strike-through on superseded text. Next step: `create-plan` against this grilled doc.

## Research Question

Research how banks are currently wired end-to-end in InsureFlow, remove the "platform banks / MASCA" framing, and re-shape the code so that **each tenant configures their own bank accounts in the tenant-admin portal**. When the payment gateway is later built, tenants receive payments **into** those accounts and send money **out of** them to payees. In the interim, stub the bank workflow through the existing Go payment-gateway service.

## Summary

The "MASCA bank accounts" surface exists **only in the finance-service** — a single CRUD entity (`MascaBankAccount`), a REST controller, a page in the *operational* Angular sidebar, and one gateway proxy line. Despite the "Platform-side" wording on the controller (`MascaBankAccountController.java:24`) and the sidebar label "Platform Banks" (`operational-nav.ts:143`), the table already lives in the **tenant schema** (V016), so the data-plane is already per-tenant — only the branding, the entity name, the URL, and the page location are legacy MASCA leakage.

There are **zero downstream consumers** of `MascaBankAccount` in Java: `BankReconciliation`, `Payment`, `PaymentRun`, `PaymentAdvice`, and `PaymentRunItem` all lack any `bank_account_id` field, and `MascaBankAccountRepository.findNominatedForCurrency` has no callers. The nomination flag is orphaned. Removing the entity today breaks only the Angular admin page and the two gateway proxy lines.

The Go `services/go/payment-gateway/` is a ~200-line demo shell (Fiber + in-memory ledger + `MockProvider` that always succeeds). It does not consume any Kafka topic, does not persist, does not fan out webhooks, and does not know about the finance-service's bank-account model. `internal/config/config.go` declares Paynow / Stripe / DB env vars but `cmd/main.go` never calls `config.Load()`. There is no outbound (payout) endpoint even though the `Transaction.Direction` field supports it.

`.claude/payments.md` (the intended architecture) speaks of a `payment_config` table for provider credentials and a `public.payment_transactions` ledger — **but no tenant bank-account model**. `.claude/multi-tenancy.md` and `.claude/portals.md` never enumerate bank accounts as a tenant-scoped resource, and there is no `admin.banks:*` permission in the catalogue. `.claude/architecture.md:92` and `.claude/coding-standards.md:590` still normatively reference the `MascaBankAccount` entity by its legacy name.

**Refactor shape (for the plan):** ~~rename `masca_bank_accounts` → `tenant_bank_accounts` (or `bank_accounts`), move the Angular surface from `/tenant/finance/banks/masca` into a new **Bank Accounts** tab in the tenant-admin settings shell, add a permission (`admin.bank_accounts:manage` or reuse `finance:manage_banks`), enforce it server-side (`@PreAuthorize` on the finance controller — today unenforced), and wire `BankReconciliation` + `Payment` (source-account on the run header, destination-account inferred from payee) with new nullable FKs. Stub the settlement pathway by having `PaymentRunService.execute` call the Go payment-gateway's `/api/v1/pay/initiate` with `direction=outbound` for each item, and have the mock-provider return success without moving real money.~~

**Refactor shape (grilled 2026-08-10 — settled):** Create a fresh `tenant_bank_accounts` table in V075 (G1) — `INSERT-SELECT` rows from `masca_bank_accounts`, add `label VARCHAR(120) NOT NULL` (backfilled `bank_name || ' ' || currency_code`) and `notes TEXT NULL` (G7), drop the old table. Move the Angular surface out of the operational sidebar entirely and into a new `bank-accounts` tab in the tenant-admin settings shell at `/admin/settings` (G2); delete `pages/tenant/finance/banks/` and the operational-nav entry. Introduce `admin.bank_accounts:manage` (G3), retire `finance:manage_banks` (V075 drops it — cascade drops existing grants; tenant admins reassign the new perm manually, G3b), enforce server-side with `@PreAuthorize` on the new controller. Add `source_bank_account_id UUID NOT NULL REFERENCES tenant_bank_accounts(id)` on `payment_runs` (G5), backfill from the nominated account per currency (fallback: any active account for the currency; if none exists, migration fails loudly), UI picker in the payment-run generate form. Settlement seam is **async Kafka** (G4): extend `services/go/payment-gateway/` with a Reactor-Kafka consumer for `medfund.payments.run.executed`; consumer loops items, calls `MockProvider.Initiate` with `direction=outbound` and the source `bankAccountId`, records ledger rows, publishes `medfund.payments.gateway.settled` per item; finance-service consumes that back to flip `Payment.status → paid` and stamp `paid_at`. `BankReconciliation` gains **no** bank-account FK in this plan (G6 — deferred). Payee bank-account modelling on providers/members deferred (Open Question 6). Doc updates land in the same PR (G8): `.claude/architecture.md`, `.claude/coding-standards.md`, `.claude/multi-tenancy.md`, `.claude/portals.md`, `.claude/payments.md`, `docs/medfund-platform-manual.md`. Playwright spec added in-plan (G9): `clients/angular/e2e/tenant-admin-bank-accounts.spec.ts` covering list, create, edit, nominate (only-one-per-currency), delete. DTO validation drift aligned to the DB during rewrite (`account_number` max 50, `swift_code` max 50) — settled by fact, not by preference.

## Findings

### Finance-service — the whole bank surface lives here

**Entity, repository, service, controller (all in `services/java/finance-service/`)**

- `MascaBankAccount` entity — `entity/MascaBankAccount.java:14-49`. `@Table("masca_bank_accounts")`. Fields: `bankName, accountNumber, branchCode, swiftCode, accountName, currencyCode, nominated (is_nominated), active (is_active), createdAt, updatedAt`. Lombok `@Getter @Setter` (compliant with entity convention in [.claude/coding-standards.md](.claude/coding-standards.md)).
- Repository — `repository/MascaBankAccountRepository.java:10-31`. Reactive R2DBC. `findNominatedForCurrency(String)` at line 20-21 is **defined but has zero Java callers** anywhere in the repo (verified by grep).
- Service — `service/MascaBankAccountService.java:28-120`. Emits audit events with `entityType="MascaBankAccount"`, `entityName=a.getAccountName()`, and hard-codes `changedFields=new String[]{}` (never populated). Nomination flip triggers `clearNominationsForCurrencyExcept(...)` before save.
- Controller — `controller/MascaBankAccountController.java:22-67`. Base path `/api/v1/masca-bank-accounts`. Standard 5-verb CRUD. **No `@PreAuthorize` anywhere.** Swagger tag reads `"MASCA Bank Accounts — Platform-side bank accounts that receive payouts and statement credits."` — this "Platform-side" wording is misleading (see next bullet).
- DTOs — `dto/MascaBankAccountResponse.java:8-36`, `dto/UpsertMascaBankAccountRequest.java:6-15`. Validation drift with the DB: `accountNumber` DTO max 100 vs DB `VARCHAR(50)`; `swiftCode` DTO max 20 vs DB `VARCHAR(50)`.
- Security — `finance-service/config/SecurityConfig.java:14-26` only asserts `.anyExchange().authenticated()`. Any authenticated JWT hits every finance endpoint, including `MascaBankAccountController` and `ReconciliationController`. Permission enforcement is currently **Angular-side only** (`finance.routes.ts:143` gates the banks page with `finance:manage_banks`).

**Tenant scoping — the table already IS per-tenant.** Both `masca_bank_accounts` (`V016__finance_schema.sql:177-195`) and `bank_reconciliations` (`V016__finance_schema.sql:93-108`) are created by the **tenant** Flyway stream (`services/java/tenancy-service/src/main/resources/db/migration/tenant/V016__finance_schema.sql`). Neither table has a `tenant_id` column — isolation is by Postgres schema search_path set by the tenant-context filter, which is the normal per-tenant pattern. The V016 comment itself says (`V016__finance_schema.sql:174-176`):

> *"The tenant's own bank accounts used for outbound disbursements. Distinct from provider / member bank details (those live elsewhere). Only one nominated account per currency at a time."*

So the SQL is honest ("tenant's own"), while the controller `@Tag` and the Angular sidebar mislabel the same data as "platform". This is the core cognitive dissonance the refactor removes.

**Bank reconciliation has NO bank identity today.** `BankReconciliation` (`entity/BankReconciliation.java:14-92`) carries only `referenceNumber`, `statementAmount`, `systemAmount`, `difference`, `currencyCode`, `status`, `notes`, `statementDate` — **no `bank_account_id`, no FK, no soft ref.** `ReconciliationService.create` computes system-amount via `PaymentRepository.sumPaidUpTo(currency, statementDate)` (`service/ReconciliationService.java:67-103`, `repository/PaymentRepository.java:42-45`) — the sum is **currency-wide and cumulative** to the cutoff date, not per bank account. The `reference_number` column is `UNIQUE` tenant-wide (`V016__finance_schema.sql:95`), so the same statement reference cannot appear on two different bank accounts.

**Payment has no bank identity either.** `Payment` (`entity/Payment.java:14-61`) has `payment_number, provider_id, member_id, payee_type, amount, currency_code, payment_type, status, payment_method (free-form string), reference (free-form string), paid_at`. `CreatePaymentRequest` (`dto/CreatePaymentRequest.java:9-16`) does not accept a bank-account id.

**No settlement / bank-file / ACH / SWIFT egress code exists.** `PaymentService.markPaid` (`service/PaymentService.java:128-157`) flips status to `"paid"`, stamps `paid_at`, emits `medfund.payments.committed`, and calls `settleMemberPayment` for MEMBER-payee rows — nothing calls the payment-gateway. The `PaymentRunService.execute` publishes `medfund.payments.run.executed` and nothing else in the Go tree consumes that topic (see the Go section below and [thoughts/shared/research/2026-08-09-payment-run-vs-payments.md:230](thoughts/shared/research/2026-08-09-payment-run-vs-payments.md)).

**Tests around the bank surface (all Mockito, no live DB):**
- `service/MascaBankAccountServiceTest.java` — 5 tests: nomination-clearing on create, non-clearing when not nominated, nomination flip on update, delete-existing, delete-missing.
- `service/ReconciliationServiceTest.java` — 9 tests covering create (matched/unmatched/omitted-system-amount), state transitions, idempotence.
- `controller/ReconciliationControllerTest.java` — 2 `@WebFluxTest` tests. **No `MascaBankAccountControllerTest`.**
- `MascaBankAccountServiceTest` is listed as a pre-existing failure in three plans ([thoughts/shared/plans/2026-08-09-ctc-payments.md:247](thoughts/shared/plans/2026-08-09-ctc-payments.md), [thoughts/shared/plans/2026-08-10-creditors-workflow-unify-and-member-payments.md:28](thoughts/shared/plans/2026-08-10-creditors-workflow-unify-and-member-payments.md), [thoughts/shared/plans/2026-08-10-adjustment-to-note-rename-with-advice-integration.md:383](thoughts/shared/plans/2026-08-10-adjustment-to-note-rename-with-advice-integration.md)) — the [[bug_claim_save_mock_id_npe]] pattern from 2026-06-24. Any refactor must either fix or rewrite these tests.

### Angular — one operational page, no tenant-admin surface

**The banks page today** — `clients/angular/src/app/pages/tenant/finance/banks/masca-banks.component.ts` (137 lines) + `.html`. Standalone Angular 19 component. Field list matches the DTO. Delete uses `confirm(...)`. List/edit/delete calls go through `FinanceService.{list,create,update,delete}MascaBankAccount` (`core/services/finance.service.ts:842-847`) which hit `/masca-bank-accounts`.

**Routes** — `pages/tenant/finance/finance.routes.ts:139-146`:
```
{ path: 'banks',       redirectTo: '/tenant/finance/banks/masca', pathMatch: 'full' }
{ path: 'banks/edit',  redirectTo: '/tenant/finance/banks/masca', pathMatch: 'full' }
{ path: 'banks/masca', component: MascaBanksComponent, canActivate: [permissionGuard(['finance:manage_banks'])], title: 'Platform Bank Accounts' }
```

**Sidebar** — `layout/operational-sidebar/operational-nav.ts:143` (Finance group): label `"Platform Banks"`, route `/tenant/finance/banks/masca`, permissions `['finance:manage_banks']`. **Not registered in the tenant-admin sidebar.**

**Permission** — `core/security/permissions.ts:41` declares `finance:manage_banks` and `permissions.ts:134` catalogues it as `"Manage bank accounts — Configure tenant bank accounts and routing"` (already tenant-scoped in name; the label is refactor-friendly).

**Tenant-admin settings shell** — `pages/tenant-admin/settings/settings.component.ts:51,76-85` — TabId union + tabs array. Current tabs: `general | branding | insurance-lines | currencies | billing | proration | email-templates | roles`. Externalised tabs (proration, roles, currencies, billing) live in sibling directories with an `app-tenant-*-tab` selector, are imported into `settings.component.ts` and added to both the `tabs[]` array and an `@if (activeTab === '...')` block in `settings.component.html`. A **Bank Accounts** tab would slot in exactly the same way, e.g. `pages/tenant-admin/settings/bank-accounts/bank-accounts-tab.component.{ts,html,scss}`.

**Reconciliation & payment-run UIs have no bank picker.** `reconciliations-list.component.html:15-26` filters by status only; `reconciliation-form.component.html:24-53` captures `referenceNumber, statementDate, statementAmount, currencyCode, notes` — no dropdown or field for bank account. `payment-run-generate.component.html:22-45` captures payee-type, currency, description — no source-account picker. `PaymentRun` and `Payment` DTOs (`finance.service.ts:8-76`) have no `bankAccountId` field.

**e2e stubs** — grep of `clients/angular/e2e/` for `masca|BankAccount|bank_account|manage_banks` returns **zero hits**. The banks admin has never been exercised through Playwright; `billing-stubs.ts` doesn't include it.

### Go — payment-gateway is a scaffold; API gateway proxies the finance surface

**`services/go/payment-gateway/` inventory** (module `github.com/medfund/payment-gateway`, port 3004):
- `cmd/main.go:1-39` — Fiber app; boots `payment.NewMockProvider()` + `payment.NewLedger()`; reads `PORT` env directly. **Does not import `internal/config`.**
- `internal/config/config.go:5-30` — declares `PaynowIntegrationID/Key, StripeSecretKey, WebhookSecret, DatabaseURL`. Orphaned; `config.Load()` is never called by main.
- `internal/payment/provider.go:5-54` — `Status` enum, `InitiateRequest/Response`, `Transaction` (with `Direction: inbound|outbound` field), `Provider` interface (`Initiate/CheckStatus/VerifyWebhook`). **No `InitiatePayout` method** despite `.claude/payments.md` promising one.
- `internal/payment/mock_provider.go:8-28` — always returns `StatusCompleted`, always verifies webhooks true.
- `internal/payment/ledger.go:10-88` — `sync.RWMutex`-guarded in-memory `map[string]*Transaction`; wiped on restart.
- `internal/handler/handler.go:8-73` — 4 endpoints:
  - `POST /api/v1/pay/initiate` — hardcodes `direction="inbound"` (`handler.go:36`); no outbound path.
  - `GET  /api/v1/pay/transactions` — tenant-scoped via `X-Tenant-ID` header.
  - `GET  /api/v1/pay/transactions/:id`.
  - `POST /api/v1/pay/webhook` — verifies signature only; **body is not parsed** (`handler.go:63` comment: `// Process webhook payload — provider-specific`).

**Tests** — all unit tests, mock-provider-based. `handler_test.go` covers the happy paths + idempotency + 404 + tenant listing. Nothing exercises a real provider or Kafka.

**API gateway proxying (`services/go/gateway/internal/routes/routes.go`)** — the finance section (lines 107-128) forwards everything to `FinanceServiceURL` (default `http://localhost:8085`), including:
- `/api/v1/masca-bank-accounts` (`routes.go:121-122`)
- `/api/v1/reconciliations/*` (`routes.go:119`)
- `/api/v1/payments/*`, `/api/v1/payment-runs/*` (`routes.go:107-108`)
- `/api/v1/creditors[/*]`, `/api/v1/notes[/*]`, `/api/v1/payment-advices/*`

And the Go payment-gateway sits at `/api/v1/pay/*` → `PaymentServiceURL` (default `http://localhost:3004`) — `routes.go:146`.

**Kafka topics** (`grep -rn 'medfund\.' services/go/`) — the only payment-flavoured topics are `medfund.payments.advice.generated` (finance → notification-service) and `medfund.notifications.advice.sent` (notification-service → finance-service status). Both are advice **notifications**, not money movement. There is no `medfund.payments.run.executed` consumer in Go — that topic is published by finance-service but nothing listens for it.

### Architecture docs vs code

- **`.claude/payments.md`** is a target spec with a rich payment-provider model but **no tenant bank-account model**. The `payment_config` table (per-tenant, `payments.md:571-589`) stores encrypted provider credentials, methods, currencies, payout schedule / hold-days / thresholds — but no `bank_accounts` table is described. Recipient bank-account verification is mentioned once (`payments.md:425`) without a schema. Tenant-admin routes are `/admin/payments/config` and `/admin/payments/payout-settings` (`payments.md:610-614`) — no `/admin/banks`.
- **`.claude/multi-tenancy.md`** never uses the word "bank". The tenant-scoped resource list (`multi-tenancy.md:186-221`) does not include bank accounts. Line 129 contains an unrelated MASCA leak: `defmodule MascaWeb.TenantPlug do`.
- **`.claude/multi-currency.md`** never mentions bank accounts. `tenant_currency_config` (`multi-currency.md:52-64`) has no link to a bank-account table.
- **`.claude/architecture.md:89,92`** — Finance-service description: *"Bank reconciliation, MASCA bank account management"* and enumerates `MascaBankAccount` in the entity list. This is the canonical stale reference.
- **`.claude/coding-standards.md:590`** — the audit-entity-name convention table lists `MascaBankAccount → getAccountName()` as normative.
- **`.claude/portals.md`** — tenant-admin route table (lines 86-108) lists `/admin/currencies`, `/admin/billing`, `/admin/rules`, `/admin/schemes`, `/admin/audit`, etc. **No `/admin/banks` and no `admin.banks:*` permission** anywhere in the RBAC catalog (`portals.md:574-576`).
- **`docs/medfund-platform-manual.md:1426`** — user-facing manual describes `MascaBankAccountController — banking integration` as a first-class finance controller.

## Cross-service flow (current state)

Money-in (contribution or claim payout) — today there is **no code path that touches a bank account**:

1. Angular records a `Transaction` via `POST /api/v1/transactions` → contributions-service.
2. Contributions-service posts a row in `transactions`, emits `medfund.contributions.transaction-recorded`.
3. File-service renders a receipt PDF; notification-service emails it.

Money-out (provider or member payout batch):

1. Angular creates a `PaymentRun` via `POST /api/v1/payment-runs` → finance-service.
2. `PaymentRunGenerator` auto-fills items from `provider_balances` + open `member_payables`.
3. `PaymentRunService.execute` publishes `medfund.payments.run.executed` (topic name from [thoughts/shared/research/2026-08-09-payment-run-vs-payments.md:161](thoughts/shared/research/2026-08-09-payment-run-vs-payments.md)).
4. **No consumer**. `services/go/payment-gateway/` never sees this event. `services/go/notification-service/internal/notification/service.go:71-72` has a stub case for `PAYMENT_RUN_EXECUTED` that logs `"would notify providers"` and returns.
5. `PaymentAdviceService.generateAdvicesForRun` writes `payment_advices` rows; notification-service emails each advice to the payee via `medfund.payments.advice.generated`.

Bank reconciliation:

1. Operator uploads a statement reference + amount via `POST /api/v1/reconciliations` (currency-wide, no bank chosen).
2. `ReconciliationService` sums `payments.amount` for that currency up to `statementDate` and derives matched / unmatched.
3. No per-payment linkage is written. No bank-account foreign key is stored.

## Architecture doc vs code

| Design (`.claude/*.md`) | Code today | Drift |
|---|---|---|
| `payments.md` names `MascaBankAccount` implicitly via `architecture.md:92` and `coding-standards.md:590`; `payments.md` itself defines no bank-account table | Entity + CRUD exist, table `masca_bank_accounts` (V016) is tenant-schema | Legacy name persists in the arch docs; the SQL comment already calls it "tenant's own bank accounts" |
| `payments.md:301-355` says a Payment Gateway Service does `InitiatePayout()` and updates `payout_transactions` | The `Provider` interface has only `Initiate/CheckStatus/VerifyWebhook`; no `InitiatePayout`; `Transaction.Direction=outbound` is dead code | Aspirational — gateway needs `InitiatePayout` added, or `Initiate` needs a `direction` parameter |
| `payments.md:606` `/tenant/finance/reconciliations` matches statements to platform transactions | Reconciliation is currency-wide; no bank-account linkage; no CSV/OFX import | Matches at a coarse level; no per-bank slicing |
| `multi-tenancy.md:186-221` enumerates tenant-scoped resources; **omits bank accounts** | Bank accounts are already tenant-scoped in the schema | Doc has a gap; refactor should add "Bank Accounts" to the tenant-admin catalog |
| `portals.md` has no `/admin/banks` and no `admin.banks:*` permission | Angular has `finance:manage_banks` used only for the operational-sidebar banks page | Permission exists but neither the doc nor the tenant-admin portal registers a page for it |
| `multi-currency.md:160-164` says provider balances are per-currency and rates are locked at commit time | Same true for `MascaBankAccount.currency_code` (one account per currency, one nominated per currency) | Consistent; a rename can reuse the same "one account per currency" invariant |
| `payments.md:425` "First payout to a new bank account triggers a micro-deposit verification" | No bank-account model on payees at all | Big gap; out of scope for this refactor but flag |
| `payments.md:571-589` `payment_config` with encrypted provider credentials (AES-256-GCM, per-tenant KMS key) | Not implemented in code | Big gap; out of scope for this refactor but the stub can align with the interface |

## Code References

- `services/java/tenancy-service/src/main/resources/db/migration/tenant/V016__finance_schema.sql:173-195` — `masca_bank_accounts` DDL (tenant schema) with unique `(account_number, currency_code)` and partial unique `WHERE is_nominated=TRUE`.
- `services/java/tenancy-service/src/main/resources/db/migration/tenant/V016__finance_schema.sql:92-111` — `bank_reconciliations` DDL (no bank-account FK).
- `services/java/tenancy-service/src/main/resources/db/migration/tenant/V008__billing_catalogues.sql:1`, `V010__running_balances.sql:2,5`, `V069__ctc_lifecycle.sql:5` — Flyway-locked comments naming MASCA; per [[feedback_never_edit_applied_migrations]] these must **not** be edited in place.
- `services/java/tenancy-service/src/main/resources/db/migration/tenant/V006__rbac_refinements.sql:59` — seeds the `finance:manage_banks` permission row.
- `services/java/finance-service/src/main/java/com/medfund/finance/entity/MascaBankAccount.java:14-49` — entity.
- `services/java/finance-service/src/main/java/com/medfund/finance/repository/MascaBankAccountRepository.java:10-31` — repo; `findNominatedForCurrency` unused.
- `services/java/finance-service/src/main/java/com/medfund/finance/service/MascaBankAccountService.java:28-120` — service; audit uses `entityType="MascaBankAccount"`, empty `changedFields`.
- `services/java/finance-service/src/main/java/com/medfund/finance/controller/MascaBankAccountController.java:22-67` — 5-verb CRUD; no `@PreAuthorize`.
- `services/java/finance-service/src/main/java/com/medfund/finance/config/SecurityConfig.java:14-26` — `.anyExchange().authenticated()` only; permissions unenforced server-side.
- `services/java/finance-service/src/main/java/com/medfund/finance/dto/UpsertMascaBankAccountRequest.java:6-15` — DTO; `accountNumber` max 100 vs DB 50; `swiftCode` max 20 vs DB 50.
- `services/java/finance-service/src/test/java/com/medfund/finance/service/MascaBankAccountServiceTest.java` — 5 unit tests; listed as pre-existing failure in [[bug_claim_save_mock_id_npe]].
- `services/go/gateway/internal/routes/routes.go:107-128,146` — every finance + payment-gateway proxy line.
- `services/go/payment-gateway/cmd/main.go:1-39` — Fiber bootstrap (does not use `internal/config`).
- `services/go/payment-gateway/internal/payment/provider.go:14-54` — `InitiateRequest`, `Transaction.Direction`, `Provider` interface (no `InitiatePayout`).
- `services/go/payment-gateway/internal/payment/mock_provider.go:8-28` — always succeeds.
- `services/go/payment-gateway/internal/handler/handler.go:8-73` — 4 endpoints; hardcodes `direction="inbound"` at line 36; unparsed webhook at line 63.
- `services/go/notification-service/internal/notification/service.go:71-72` — stub for `PAYMENT_RUN_EXECUTED` that logs `"would notify providers"` and does nothing.
- `clients/angular/src/app/pages/tenant/finance/banks/masca-banks.component.ts:1-137` — the operational banks page.
- `clients/angular/src/app/pages/tenant/finance/finance.routes.ts:139-146` — route + two redirects to `banks/masca`.
- `clients/angular/src/app/core/services/finance.service.ts:229-253,842-847` — `MascaBankAccount` interface, `UpsertMascaBankAccountPayload`, 5 CRUD methods.
- `clients/angular/src/app/core/security/permissions.ts:41,134` — `finance:manage_banks` declaration + catalogue.
- `clients/angular/src/app/layout/operational-sidebar/operational-nav.ts:143` — "Platform Banks" sidebar entry.
- `clients/angular/src/app/pages/tenant-admin/settings/settings.component.ts:51,76-85` — TabId union + tab registry (where a new "Bank Accounts" tab slots in).
- `.claude/architecture.md:89,92` — "MASCA bank account management" + `MascaBankAccount` in the finance-service entity list.
- `.claude/coding-standards.md:590` — audit-entity-name convention row for `MascaBankAccount`.
- `.claude/payments.md:571-589,606,610-614` — `payment_config` table, reconciliation route, tenant-admin payments routes; **no bank-account table**.
- `.claude/multi-tenancy.md:129` — Elixir `MascaWeb.TenantPlug` legacy leak.
- `.claude/portals.md:86-108,574-576` — tenant-admin route table + RBAC catalogue; **no bank surface**.
- `docs/medfund-platform-manual.md:1426` — user manual references `MascaBankAccountController`.

## Architecture Insights

- **Tenant scoping is already correct at the data layer** — the SQL comment at `V016__finance_schema.sql:174-176` explicitly says "the tenant's own bank accounts". The problem is naming, permissions, portal location, and enforcement — not the schema-per-tenant model. Rule 2 (tenant scoping) is satisfied by construction.
- **Currency invariant is already correct** — `masca_bank_accounts` has `currency_code VARCHAR(3) NOT NULL` and a partial unique index enforcing one nominated account per currency (`V016__finance_schema.sql:194-195`). This matches the currency-first pattern of `provider_balances` and `tenant_currency_config`, and honours Rule 1 (never mix currencies).
- **Rule 4 (data protection) gap** — bank account numbers and SWIFT codes are stored in cleartext today. `.claude/payments.md:591` establishes an encryption-at-rest pattern for `payment_config.provider_credentials` (AES-256-GCM with per-tenant KMS keys). Any tenant-bank-account rewrite should either apply the same pattern or explicitly defer with a follow-up ticket.
- **Rule 7 (Swagger) is satisfied but the tag is wrong** — the existing controller has `@Tag`, `@Operation`, and `@SecurityRequirement`. The rewrite needs to swap the tag text from "MASCA / Platform-side" to "Tenant Bank Accounts / The tenant's own accounts".
- **Rule 8 (audit) is partially satisfied but weak** — `MascaBankAccountService.publishAudit` (`service/MascaBankAccountService.java:102-120`) emits with `entityType, entityName, actorId, actorEmail` per [[feedback_audit_actor_email]] and [[feedback_audit_entity_name]]. But `changedFields` is hard-coded to empty (`service/MascaBankAccountService.java:115`) — the rewrite should compute a real changed-fields set. `entityType` needs renaming from `"MascaBankAccount"` to whatever the new class name is.
- **Rule 9 (security events) is unmet** — since the controller has no `@PreAuthorize`, no `PERMISSION_DENIED` security event fires when a non-admin tries to hit these endpoints. Enforcing the permission server-side automatically fixes this (the existing `PermissionEnforcementFilter` emits the event).
- **Reactor-Kafka ack-on-error pattern** — the plan will produce or consume Kafka topics (e.g. an outbound `medfund.payments.gateway.initiated` if we later split the seam). Per [[bug_reactor_kafka_ack_swallow]], use `.doOnSuccess` for offset ack, not `.doOnTerminate`.
- ~~**Migration idempotency** — the rename `masca_bank_accounts → tenant_bank_accounts` is a table rename in a new higher-numbered tenant migration (V076+). Per [[feedback_never_edit_applied_migrations]], V016 stays untouched; the new migration uses `ALTER TABLE IF EXISTS masca_bank_accounts RENAME TO tenant_bank_accounts;` and matching index renames. Keep the column names `is_nominated` / `is_active` as-is (renaming risks tooling drift and offers no user value) — only rename the Java identifiers.~~
- **Migration shape (grilled 2026-08-10):** V075 (next free after V074) does a **fresh-table** dance rather than an in-place rename (G1). Ordering: (1) `CREATE TABLE tenant_bank_accounts` with the existing 11 columns plus `label VARCHAR(120) NOT NULL` and `notes TEXT NULL`, and matching partial-unique index on `(currency_code) WHERE is_nominated = TRUE`; (2) `INSERT INTO tenant_bank_accounts (…) SELECT …, bank_name || ' ' || currency_code AS label, NULL AS notes FROM masca_bank_accounts;`; (3) `ALTER TABLE payment_runs ADD COLUMN source_bank_account_id UUID NULL REFERENCES tenant_bank_accounts(id);`; (4) backfill `UPDATE payment_runs SET source_bank_account_id = (SELECT id FROM tenant_bank_accounts WHERE currency_code = payment_runs.currency_code AND is_nominated = TRUE LIMIT 1)`; fallback pass for currencies with no nominated account (`WHERE is_active = TRUE LIMIT 1`); if any run still has `NULL` after both passes, the migration `RAISE`s so the tenant sees the failure; (5) `ALTER TABLE payment_runs ALTER COLUMN source_bank_account_id SET NOT NULL`; (6) `DELETE FROM role_permissions WHERE permission = 'finance:manage_banks'; DELETE FROM permissions WHERE key = 'finance:manage_banks'; INSERT INTO permissions (key) VALUES ('admin.bank_accounts:manage');`; (7) `DROP TABLE masca_bank_accounts`. Per [[feedback_never_edit_applied_migrations]], V016 stays untouched. Column names `is_nominated` / `is_active` carry over verbatim — only the table name and Java identifiers change.
- **Per [[bug_tenant_flyway_outoforder.md]] and [[bug_public_flyway_history_load_bearing.md]]** — the tenancy-service's dev Flyway records both public/ and tenant/ migrations in one `public.flyway_schema_history`. Any new tenant migration is picked up per-schema on next boot; nothing in `public.` needs cleaning.
- **The Go payment-gateway stub is already 90% shaped for what we need.** Its `Provider` interface, `MockProvider`, and `Ledger` cover the "receive a request, record it, return success" contract. The gap: (a) no outbound direction on the HTTP handler, (b) no consumer of finance-service Kafka events. ~~The refactor can either (i) have finance-service call the gateway synchronously in `PaymentRunService.execute`, or (ii) add a Kafka consumer to `payment-gateway` that listens to `medfund.payments.run.executed`. Rule 6 (services communicate via Kafka for side effects) favours option (ii) — spec it as "consume `medfund.payments.run.executed`, call `MockProvider.Initiate` per item, record in the ledger, emit `medfund.payments.gateway.settled`".~~ **Grilled 2026-08-10 (G4): option (ii) — async Kafka.** Spec: extend `payment-gateway` with a Reactor-Kafka consumer on `medfund.payments.run.executed` (offset ack via `.doOnSuccess`, never `.doOnTerminate` per [[bug_reactor_kafka_ack_swallow]]); consumer iterates run items, calls `MockProvider.Initiate` with `Direction="outbound"` and the run's `sourceBankAccountId` (G5) + payee identity, records ledger rows tenant-keyed by `X-Tenant-ID` propagated as a Kafka header, then publishes `medfund.payments.gateway.settled` per item. Finance-service adds a matching consumer that flips `Payment.status → paid` and stamps `paid_at`. Also extend the `Provider` interface / `handler.go:36` seam to accept `direction` from the request so the sync REST endpoint stops hard-coding `"inbound"`.

## Historical Context (from thoughts/shared/)

- [thoughts/shared/research/2026-08-09-payment-run-vs-payments.md](thoughts/shared/research/2026-08-09-payment-run-vs-payments.md) — the baseline paper on PaymentRun/Payment/PaymentAdvice taxonomy. Open question #2 (line 230): "Who drives money out to the payment gateway?… Not investigated in this pass." **This research directly answers that.**
- [thoughts/shared/plans/2026-08-10-creditors-workflow-unify-and-member-payments.md](thoughts/shared/plans/2026-08-10-creditors-workflow-unify-and-member-payments.md) — line 56 states the design rationale for homogeneous PaymentRuns is bank-file export; line 97 explicitly defers bank-file export design. The tenant-bank-accounts refactor here is the enabler for the future bank-file work.
- [thoughts/shared/plans/2026-08-10-adjustment-to-note-rename-with-advice-integration.md](thoughts/shared/plans/2026-08-10-adjustment-to-note-rename-with-advice-integration.md) — folded `debit_notes`/`credit_notes` into unified `notes` table with `direction`. Established the naming precedent for the current refactor (rename legacy MASCA/finance concepts to line-neutral names).
- [thoughts/shared/plans/2026-08-09-ctc-payments.md](thoughts/shared/plans/2026-08-09-ctc-payments.md) and [thoughts/shared/research/2026-08-09-ctc-payments.md](thoughts/shared/research/2026-08-09-ctc-payments.md) — established the "V016 comment names MASCA — locked, do not edit" pattern per [[feedback_never_edit_applied_migrations]].
- [thoughts/shared/plans/2026-08-10-audit-path-431-shared-fiber-httpserver.md](thoughts/shared/plans/2026-08-10-audit-path-431-shared-fiber-httpserver.md) — refactored `services/go/payment-gateway` to use `httpserver.New`; the current `cmd/main.go` is already post-refactor. Any changes to the gateway boot flow should stay compatible with `AppName: "MedFund Payment Gateway"`.

## Related Research

- [thoughts/shared/research/2026-08-10-debit-and-credit-notes-in-insurance.md](thoughts/shared/research/2026-08-10-debit-and-credit-notes-in-insurance.md) — parallel "line-neutral naming" study.
- [thoughts/shared/research/2026-08-08-advance-payments.md](thoughts/shared/research/2026-08-08-advance-payments.md) — the `advance_payments.payment_id` nullable FK is described as "a placeholder for a future reconciliation link" (line 30); relevant if reconciliation gains a `bank_account_id`.

## Open Questions

*All nine questions settled by the grilling session on 2026-08-10. Original questions kept struck-through; resolutions numbered G1–G9 (with G3b for the dependent sub-decision) live in the Grilling decisions section below.*

1. ~~**Rename vs replace.** Do we rename `masca_bank_accounts → tenant_bank_accounts` (retain data, minimal churn, one migration to `ALTER TABLE RENAME`) or introduce a fresh table with a different schema (e.g. adding `provider_bank_ref` for the future payment-gateway wiring, tagging accounts as `PRIMARY | OPERATIONAL | ESCROW`)? The plan needs to pick.~~ → **Settled G1: fresh table** `tenant_bank_accounts` in V075; `INSERT-SELECT` from `masca_bank_accounts`; `DROP` old. Speculative columns (`account_type`, `provider_bank_ref`) deferred — see G7.
2. ~~**Server-side permission enforcement.** Do we add `@PreAuthorize("hasAuthority('finance:manage_banks')")` (reuse the existing perm) or introduce a new `admin.bank_accounts:manage` in the `admin.*` namespace (matches `.claude/portals.md` naming and puts the page under tenant-admin)? Either works; the plan needs to pick.~~ → **Settled G3: introduce `admin.bank_accounts:manage`** and retire `finance:manage_banks` — bank accounts belong to the admin surface, not finance. Sub-decision G3b: V075 drops the old permission (cascade drops role grants); tenant admins reassign the new permission manually. `@PreAuthorize("hasAuthority('admin.bank_accounts:manage')")` on the new controller.
3. ~~**Where the page lives.** Move the surface to the tenant-admin settings shell as a new tab (`/admin/settings#bank-accounts`) and drop the operational-sidebar link, or keep both and let finance clerks view (read-only) while only admins can edit? Two-tab visibility complicates the guard.~~ → **Settled G2: tenant-admin settings tab only.** New `bank-accounts-tab` component in `pages/tenant-admin/settings/`; added to the `TabId` union + `tabs[]` in `settings.component.ts:51,76-85`. Operational-sidebar entry (`operational-nav.ts:143`) removed. `pages/tenant/finance/banks/` folder deleted along with the two legacy redirect routes in `finance.routes.ts:139-146`.
4. ~~**`BankReconciliation` back-reference.** Add a nullable `bank_account_id` on `bank_reconciliations` now (so the operator picks the account when uploading a statement) or defer to a later ticket? Deferring keeps this plan tight; adding it now paves the path for per-bank-account matching without a second migration.~~ → **Settled G6: defer.** Reconciliation stays currency-wide in this plan. Follow-up ticket needed if per-account reconciliation becomes a real requirement.
5. ~~**Payment source-account on PaymentRun.** Add a nullable `source_bank_account_id` on `payment_runs` (the account we're paying **from**) and default it to the nominated account for the run's currency? Or leave that for the future settlement work? Adding it now aligns the header with the future stub.~~ → **Settled G5: add now, `NOT NULL`.** V075 adds `source_bank_account_id UUID NOT NULL REFERENCES tenant_bank_accounts(id)`. Backfill: nominated per currency → any active per currency → migration fails loudly. Angular `payment-run-generate.component` gets a debounced search-select picker (per [[feedback_no_raw_id_inputs]]). Kafka payload `medfund.payments.run.executed` carries the id.
6. ~~**Payee bank-account modelling.** Do we add a `bank_account_id` on `providers` / `members`, or defer entirely to `.claude/payments.md`'s future "recipient verification"? Almost certainly defer — providers/members don't have bank details today and adding them multiplies the scope.~~ → **Deferred** (unchallenged in grilling; the research doc's own recommendation stands). Follow-up: pair with `.claude/payments.md:425` recipient-verification work.
7. ~~**Stub integration seam.** Sync (finance-service `WebClient` → payment-gateway) or async (Kafka `medfund.payments.run.executed` consumer in payment-gateway)? Rule 6 favours Kafka. What does the stub emit back (`medfund.payments.gateway.settled`)? Does finance-service consume it to flip `Payment.status` to `paid`?~~ → **Settled G4: async Kafka.** `payment-gateway` consumes `medfund.payments.run.executed`, calls `MockProvider.Initiate` with `Direction="outbound"` and `sourceBankAccountId` (G5) per item, records ledger rows, emits `medfund.payments.gateway.settled` per item. Finance-service consumes that back to flip `Payment.status → paid` and stamp `paid_at`. Offset ack via `.doOnSuccess` per [[bug_reactor_kafka_ack_swallow]]. `handler.go:36` also stops hard-coding `direction="inbound"` (accepts it from the request).
8. ~~**Doc updates.** Do we edit `.claude/architecture.md:92`, `.claude/coding-standards.md:590`, `.claude/multi-tenancy.md:129`, `.claude/portals.md`, `.claude/payments.md`, and `docs/medfund-platform-manual.md:1426` in the same PR, or split doc updates from code? Editing docs in the same PR keeps the drift table honest.~~ → **Settled G8: same PR.** Dedicated doc phase in the plan updates all six documents alongside the code change.
9. ~~**e2e coverage.** Add a Playwright spec for tenant-admin bank-account CRUD? Per [[project_e2e_gaps_billing]] gaps are already known; this would be a good candidate to close early.~~ → **Settled G9: add now.** New spec `clients/angular/e2e/tenant-admin-bank-accounts.spec.ts` covers list, create, edit, nominate (verifying the one-per-currency invariant), delete.

## Grilling decisions

Settled 2026-08-10 · researcher: Methuseli · numbered G1–G9 (with G3b) to avoid collision with the source doc's own numbering. All 17 load-bearing claims in the Findings section re-verified against the codebase before grilling (see verification log in the grilling transcript); no premises moved.

| # | Decision | Choice |
|---|---|---|
| G1 | Rename vs replace `masca_bank_accounts` | **Fresh table** `tenant_bank_accounts` in V075; `INSERT-SELECT` from `masca_bank_accounts`; `DROP` old |
| G2 | Where the bank-accounts UI lives | **Tenant-admin settings tab only** at `/admin/settings` (new `bank-accounts` tab); operational sidebar entry + `pages/tenant/finance/banks/` folder deleted |
| G3 | Permission naming | **Introduce `admin.bank_accounts:manage`**; retire `finance:manage_banks` |
| G3b | Role-grant migration | **Drop old grants** (cascade via `DELETE FROM permissions`); tenant admins reassign the new permission manually |
| G4 | Settlement seam | **Async Kafka**: `payment-gateway` consumes `medfund.payments.run.executed`, emits `medfund.payments.gateway.settled` per item; finance-service consumes it to flip `Payment.status → paid` |
| G5 | `source_bank_account_id` on `payment_runs` | **Add now, NOT NULL**; backfill from nominated-per-currency, fallback to any active per currency, else fail loudly; picker in UI; id in Kafka payload |
| G6 | `bank_account_id` on `bank_reconciliations` | **Defer** — reconciliation stays currency-wide in this plan |
| G7 | New table columns | **Minimal**: add `label VARCHAR(120) NOT NULL` (backfilled `bank_name \|\| ' ' \|\| currency_code`) and `notes TEXT NULL`; encryption / `account_type` / `provider_bank_ref` all deferred |
| G8 | Doc updates | **Same PR**: `.claude/architecture.md`, `.claude/coding-standards.md`, `.claude/multi-tenancy.md`, `.claude/portals.md`, `.claude/payments.md`, `docs/medfund-platform-manual.md` |
| G9 | Playwright e2e | **Add in-plan**: `clients/angular/e2e/tenant-admin-bank-accounts.spec.ts` — list / create / edit / nominate (one-per-currency) / delete |

### Settled by fact (not put to the user as a choice)

- **Migration ordering.** `CREATE tenant_bank_accounts` → `INSERT-SELECT from masca_bank_accounts` → `ALTER payment_runs ADD source_bank_account_id NULL` → backfill (nominated → any-active → RAISE) → `ALTER … SET NOT NULL` → drop old permission + insert new → `DROP TABLE masca_bank_accounts`. Detailed in the Architecture Insights "Migration shape (grilled 2026-08-10)" bullet above.
- **DTO validation drift** in `dto/UpsertMascaBankAccountRequest.java:6-15` — `accountNumber` DTO max 100 vs DB 50, `swiftCode` DTO max 20 vs DB 50 — align both to DB values (50) during the rewrite. No user choice required.
- **Open Question 6** (payee bank-accounts on `providers`/`members`) — deferred, per the research doc's own recommendation. Not challenged in grilling.
- **Verification log.** All 17 load-bearing claims in the Findings section — including `findNominatedForCurrency` has zero callers, `MascaBankAccountController` has no `@PreAuthorize`, `SecurityConfig` only asserts `.anyExchange().authenticated()`, `handler.go:36` hard-codes `direction="inbound"`, no consumer of `medfund.payments.run.executed` exists anywhere outside the finance-service producer, latest tenant migration is V074 — all verified against the current tree at commit `0a1609d7`.

### Follow-ups (out of scope for this plan)

- `bank_account_id` on `bank_reconciliations` + per-account `sumPaidUpToForBankAccount` query — needed for real per-account reconciliation.
- Payee bank-account modelling on `providers` / `members` — pair with `.claude/payments.md:425` recipient-verification.
- AES-256-GCM encryption of `account_number` + `swift_code` at rest (per-tenant KMS key, matches `.claude/payments.md:591` `payment_config.provider_credentials` pattern) — requires KMS infra not yet in the repo.
- `account_type` enum (PRIMARY / OPERATIONAL / ESCROW) and `provider_bank_ref` external-id column — add when there's a consumer.
- Real payment-provider integration (Paynow, Stripe) replacing `MockProvider` — the current plan only ships the stub seam.

### Owed back to spec / docs authors

The following normative statements in the platform docs are wrong or stale and should be corrected in the same PR (G8):

- `.claude/architecture.md:89,92` — replace "MASCA bank account management" / `MascaBankAccount` entity references with "tenant bank-account management" / `TenantBankAccount`.
- `.claude/coding-standards.md:590` — audit-entity-name convention row: `MascaBankAccount → getAccountName()` becomes `TenantBankAccount → getLabel()` (using the new `label` field as the friendly entity name per [[feedback_audit_entity_name]]).
- `.claude/multi-tenancy.md:129` — remove the `MascaWeb.TenantPlug` example; use a line-neutral module name.
- `.claude/multi-tenancy.md:186-221` — add "Bank Accounts" to the tenant-scoped resource enumeration.
- `.claude/portals.md:86-108` — add `/admin/settings` bank-accounts tab entry (or `/admin/bank-accounts` if the URL surface is preferred over the tab hash).
- `.claude/portals.md:574-576` — add `admin.bank_accounts:manage` to the RBAC catalogue; remove `finance:manage_banks`.
- `.claude/payments.md:571-589` — add a tenant bank-account model section (or explicitly note it's a separate concern from `payment_config`).
- `docs/medfund-platform-manual.md:1426` — replace `MascaBankAccountController` reference with the new controller.
