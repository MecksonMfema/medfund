---
date: 2026-08-09T09:43:52+02:00
researcher: Methuseli
git_commit: 117d24e07b8239534826dd4484dfa5b7adeb1e69
branch: main
repository: medfund
topic: "CTC (Claims-to-Contributions) payments"
tags: [research, codebase, finance-service, contributions-service, claims-service, angular, permissions, audit, kafka]
status: complete
last_updated: 2026-08-09
last_updated_by: Methuseli
last_updated_note: Added Follow-up on whether CTC appears in the billing ledger, and Follow-up on member-as-payee balance tracking (finance-service gap).
---

# Research: CTC (Claims-to-Contributions) Payments

**Date**: 2026-08-09 09:43 +02:00 · **Researcher**: Methuseli · **Commit**: `117d24e0` · **Branch**: main

## Research Question
For CTC payments — what does the feature do today, how is it wired end-to-end (backend, UI, cross-service), and what gaps exist between the intended behaviour and what actually ships?

## Domain framing

**CTC = Claims-to-Contributions.** The claimant is themselves a member of the fund. Instead of paying the member out for an approved claim, the fund transfers the payout across to offset that same member's contribution bill (member paying their own premiums with money the fund owes them). It is a bookkeeping transfer between the claim-payout ledger and the contributions-receivable ledger for the same person.

Two names in the code today are stale/wrong and should be treated as terminology drift wherever they appear:
- **"cost-to-company"** (in `.claude/architecture.md:90`, `docs/medfund-platform-manual.md:1004+`, and the platform-manual "training/certification" gloss)
- **"cash-to-cardholder"** (in the `@Tag` on `CtcPaymentController` and the V016 migration comment)
- **"cost-to-cure"** (in every `permissions.ts` / `PermissionCatalogue.java` description string, and in `billing.routes.ts:237`)

## Summary

CTC has a **complete-looking scaffold** — entity + table (V016), DTOs, service, controller, paginated search repo, two Angular entry points, three permissions, audit emission — but the **core money-moving mechanic is not implemented**. `CtcPaymentService.commit()` only flips `committed=false → true`, saves, and audits. It does not:

- call contributions-service to post an offset transaction against the member/group bill,
- mark the originating claim payout as satisfied,
- publish any Kafka event,
- send any notification to the member/group.

The `contribution_id` FK on `ctc_payments` is stored but never actioned. What lives in the repo today is a two-role approval workflow (claims operator creates → finance operator confirms) over an inert ledger row. The actual transfer between ledgers is the missing piece.

## Findings

### Backend — `services/java/finance-service`

**Entity** — `services/java/finance-service/src/main/java/com/medfund/finance/entity/CtcPayment.java`
- Fields: `id UUID`, `groupId UUID?`, `memberId UUID?`, `amount BigDecimal`, `currencyCode String`, `contributionId UUID?`, `committed Boolean=false`, `createdAt Instant`, `createdBy UUID`.

**Table** — `services/java/tenancy-service/src/main/resources/db/migration/tenant/V016__finance_schema.sql:153-172`
- `CHECK (group_id IS NOT NULL OR member_id IS NOT NULL)` — enforces at least one; DTO layer enforces XOR (see below).
- `NUMERIC(19,4)` amount, ISO currency VARCHAR(3).
- **No FK constraints** on `group_id`, `member_id`, or `contribution_id` (soft references).
- Indexes on `group_id`, `member_id`, `committed`.
- Migration comment at V016:154-155 says "Cash-to-cardholder transfers … legacy MASCA contribution-side payment" — **stale wording**.
- No subsequent migration alters the table.

**DTOs** — `services/java/finance-service/src/main/java/com/medfund/finance/dto/CtcPaymentDtos.java`, `CtcPaymentRow.java`, `CtcPaymentFilterParams.java`
- `CreateCtcPaymentRequest`: `groupId?`, `memberId?`, `@NotNull @DecimalMin("0.01") amount`, `@NotBlank @Size(max=3) currencyCode`, `contributionId?`.
- The **XOR** between groupId and memberId is enforced in the service layer (`CtcPaymentService.java:62-64`), not the DTO — request-body validation permits both null; only after `create` runs does it error.
- `CtcPaymentRow` includes `groupName`, `memberName`, `memberNumber` from joins so the paginated list renders names, not UUIDs (aligns with [[feedback_no_raw_id_inputs]]).

**Service** — `services/java/finance-service/src/main/java/com/medfund/finance/service/CtcPaymentService.java` (118 lines, read in full)

The commit path is the load-bearing one and is worth quoting literally — this is the entirety of what "committing" a CTC does:

```java
// CtcPaymentService.java:76-86
public Mono<CtcPayment> commit(UUID id, String actor, String actorEmail) {
    return repository.findById(id)
        .switchIfEmpty(Mono.error(new IllegalArgumentException("CTC payment not found: " + id)))
        .flatMap(existing -> {
            if (Boolean.TRUE.equals(existing.getCommitted())) return Mono.just(existing);
            Map<String, Object> before = snapshot(existing);
            existing.setCommitted(true);
            return repository.save(existing)
                .flatMap(saved -> publishAudit("UPDATE", saved, before, snapshot(saved), actor, actorEmail).thenReturn(saved));
        });
}
```

No branch of that method calls contributions-service, claims-service, `FinanceEventPublisher`, or any ledger table other than `ctc_payments` itself. The commit is idempotent (already-committed returns as-is with no save, no audit — `:80`).

Audit entity name at `:101` is `"CTC " + amount.toPlainString() + " " + currencyCode` (e.g. `"CTC 500.00 USD"`) — matches the composition rule in [[feedback_audit_entity_name]] and `coding-standards.md:589`.

`actorEmail` is threaded through from the controller via `AuditActor` — aligns with [[feedback_audit_actor_email]].

**Controller** — `services/java/finance-service/src/main/java/com/medfund/finance/controller/CtcPaymentController.java`
- Base path `/api/v1/ctc-payments`.
- Five endpoints: `GET /`, `GET /page`, `GET /{id}`, `POST /`, `POST /{id}/commit`.
- `@Tag` at `:28` reads *"Cash-to-cardholder transfers — group or member level."* — **stale**; should be Claims-to-Contributions.
- **No `@PreAuthorize` anywhere** on the controller — permissions must be enforced by the gateway / method-security elsewhere, or aren't being enforced server-side at all (see Architecture Insights).

**Repositories** — `CtcPaymentRepository.java`, `CtcPaymentQueryRepository.java`
- Allowed sort keys: `amount`, `currencyCode`, `committed`, `memberName`, `groupName`, `createdAt`; unknown → `created_at DESC` with `id ASC` tiebreak.
- Full-text `q` filter matches on member first+last, member_number, group name.

**Tests** — `services/java/finance-service/src/test/java/com/medfund/finance/service/CtcPaymentServiceTest.java`
- 7 unit tests (Mockito): group-only create, member-only create, both-null errors (XOR guard), commit flips, commit idempotent no-save, paged wraps rows, paged clamps size/page.
- **No integration tests**, no Testcontainers coverage. Guards from [[infra_testcontainers_pitfalls]] not exercised for CTC.

**Kafka** — `services/java/finance-service/src/main/java/com/medfund/finance/service/FinanceEventPublisher.java` publishes for `Payment`, `PaymentRun`, `AdvancePayment`, `Adjustment` — **not for CtcPayment**. `CtcPaymentService` does not inject `FinanceEventPublisher`.

**Cross-service clients** — `services/java/finance-service/src/main/java/com/medfund/finance/client/` contains only `TenantConfigClient` and `FxConverter`. No contributions-service client, no claims-service client.

### Angular UI — `clients/angular`

CTC has **two front-end entry points** — a Claims-module set and a Finance-module set — plus a read-only Billing view. All three hit the same `/ctc-payments` endpoints; the split is by role/permission and UX default.

**A. Claims module** — `clients/angular/src/app/pages/tenant/claims/ctc/`

| Route | Component | Guard permission | Notes |
|---|---|---|---|
| `/tenant/claims/ctc/pending` | `CtcListComponent` | `claims:view_ctc_payments` | route data `{committed:false}` |
| `/tenant/claims/ctc/committed` | `CtcListComponent` | `claims:view_ctc_payments` | route data `{committed:true}` |
| `/tenant/claims/ctc/add` | `CtcAddComponent` | `claims:commit_ctc_payment` | manual create |
| `/tenant/claims/ctc/auto` | `CtcAutoComponent` | `claims:view_ctc_payments` | **placeholder** — HTML admits backend not shipped (`ctc-auto.component.html:16-25`) |

Route entries at `clients/angular/src/app/pages/tenant/claims/claims.routes.ts:262-286`.

- `CtcListComponent` uses the **paginated** endpoint `GET /ctc-payments/page`, with server-side search/sort. Rows carry pre-joined `memberName`/`groupName`/`memberNumber`. Commit button per row is gated on `claims:commit_ctc_payment` — matches the row action guard pattern.
- `CtcAddComponent` posts `CreateCtcPaymentPayload` to `/ctc-payments` and redirects to `/tenant/claims/ctc/pending`.
- `CtcAutoComponent` is a shell — describes a planned auto-allocation flow triggered by a `contribution.committed` Kafka event, but the backend hook does not exist.

**B. Finance module** — `clients/angular/src/app/pages/tenant/finance/ctc/`

| Route | Component | Guard permission |
|---|---|---|
| `/tenant/finance/payments/ctc` | `CtcPaymentsListComponent` | `finance:manage_ctc_payments` |
| `/tenant/finance/payments/ctc/add` | `CtcPaymentFormComponent` | `finance:manage_ctc_payments` |
| `/tenant/finance/payments/ctc/:id` | `CtcPaymentDetailComponent` | `finance:manage_ctc_payments` |

Route entries at `clients/angular/src/app/pages/tenant/finance/finance.routes.ts:101-117`.

- `CtcPaymentsListComponent` hits the **unpaginated** `GET /ctc-payments` — loads all rows into memory. Displays "first 8 chars of ID as code" for the target instead of joined names.
- `CtcPaymentFormComponent` uses `currency.listMaster(true)` (master list) instead of the claims-side `listForTenant()`.
- `CtcPaymentDetailComponent` uses a browser `confirm()` for the commit action, not the shared confirm service.

**C. Billing preset** — `clients/angular/src/app/pages/tenant/billing/billing.routes.ts:232-242`
- `/tenant/billing/transactions/ctc` renders the generic `TransactionsListComponent` with `presetTransactionType: 'CTC'` — this reads billing transaction ledger rows tagged `CTC`, not the `ctc_payments` table. Description string uses "cost-to-cure" wording (stale).

**Nav** — `clients/angular/src/app/layout/operational-sidebar/operational-nav.ts:138` exposes **only** the Finance-side link ("CTC Payments" under Finance). Claims operators reach CTC via in-page links, not the sidebar.

**Angular service** — `clients/angular/src/app/core/services/finance.service.ts:608-630` — five methods: `listCtcPayments(committed?)`, `listCtcPaymentsPaged(opts)`, `getCtcPayment(id)`, `createCtcPayment(body)`, `commitCtcPayment(id)`. DTOs match the backend at `finance.service.ts:208-387`.

**Permissions constants** — `clients/angular/src/app/core/security/permissions.ts:86-87, 121`
- `claims:view_ctc_payments`, `claims:commit_ctc_payment`, `finance:manage_ctc_payments`.
- All three description strings say "cost-to-cure" (stale).

**Tests** — `clients/angular/src/app/pages/tenant/claims/ctc/ctc-list.component.spec.ts` guards the server-side pagination contract (route-data `committed` filter, re-issue on page/search/sort, hydrate from `FinancePageResponse` envelope). Prevents regression to client-side aggregation ([[feedback_stats_serverside]]).

### Cross-service plumbing

- **Gateway** — `services/go/gateway/internal/routes/routes.go:121-122`: both `/api/v1/ctc-payments` and `/api/v1/ctc-payments/*` proxy to `cfg.FinanceServiceURL`. Only the gateway's global JWT + tenant middleware applies; no CTC-specific auth layer.
- **contributions-service** — no CTC references anywhere in `services/java/contributions-service/`. There is a generic `POST /api/v1/transactions` endpoint that could accept an offset transaction, but finance-service never calls it.
- **claims-service** — no CTC references anywhere in `services/java/claims-service/`. Claims have no "paid via CTC" state.
- **audit-service** (Go) — no CTC-specific handling; consumes generic `medfund.audit.events` stream and stores CtcPayment audit events like any other.
- **notification-service** (Go) — no CTC templates or events.
- **rules-engine** — `services/java/rules-engine/src/main/java/com/medfund/rules/model/RuleCategory.java:56` — the `PROVIDER_PAYMENT` category comment mentions CTC ("Provider payment runs — schedule, advance payments, holdbacks, CTC payments."), but no rule facts or evaluators reference CTC and `CtcPaymentService` makes no rules-engine calls.
- **Flutter** — no CTC in `clients/flutter/` (member-facing mobile app doesn't expose CTC).
- **Python AI service, Elixir dashboards** — no CTC integration.

## Cross-service flow (intended vs actual)

**Intended flow** (my inference from domain framing + `contribution_id` FK + `CtcAutoComponent` placeholder copy):

```
approved claim  →  create CTC (pending, links contributionId)
              →  commit CTC
              →  debit claim payout ledger
              →  credit member/group contribution balance in contributions-service
              →  publish medfund.finance.ctc_committed  →  notification
```

**Actual flow today:**

```
approved claim  →  create CTC row (finance DB)
              →  commit CTC (finance DB only: committed=true)
              →  audit event → generic audit stream
              →  END
```

No producer for a CTC-committed Kafka event exists in `finance-service`. No consumer for it exists in `contributions-service`, `notification-service`, or `audit-service`. The contribution balance is not affected.

## Architecture doc vs. code (the drift the code has to close)

| Doc | Line | What it says | State |
|---|---|---|---|
| `.claude/architecture.md` | :90 | `CTC (cost-to-company) payments` | **Wrong**: not cost-to-company; is Claims-to-Contributions |
| `.claude/architecture.md` | :92 | Lists `CtcPayment` as a Finance-service entity | Correct in structure, wrong in labelling |
| `.claude/payments.md` | — | No mention of CTC anywhere | **Gap**: payments-doc should describe the CTC transfer flow |
| `.claude/coding-standards.md` | :589 | `CtcPayment` audit name composed as `"CTC <amount> <currency>"` | ✅ code matches |
| `docs/medfund-platform-manual.md` | :1004-1015 (F39) | Defines CTC as "(training/certification) payment" | **Wrong**: contradicts Claims-to-Contributions meaning |
| `docs/medfund-platform-manual.md` | :1427 | Lists `CtcPaymentController` in controller appendix | Correct |
| `V016__finance_schema.sql` | :154-155 | Table comment: "Cash-to-cardholder transfers … legacy MASCA" | **Wrong wording**; per [[feedback_never_edit_applied_migrations]] the comment can't be edited in place — a follow-up doc/migration would need to correct in-repo docs only |
| `CtcPaymentController.java` | :28 | `@Tag` "Cash-to-cardholder transfers — group or member level." | **Wrong**: change to Claims-to-Contributions |
| `PermissionCatalogue.java` (backend) and `permissions.ts` (frontend) | (all three CTC entries) | All descriptions use "cost-to-cure" | **Wrong**: change to Claims-to-Contributions |
| `billing.routes.ts` | :237 | Description: "Cost-to-cure transactions: clinical-cost transfers between schemes." | **Wrong** |

The larger drift is the *behavioural* one: the platform manual (F39) and the Auto-CTC placeholder both describe a flow that debits a claim payout and credits a member's contribution balance, but the code only writes/reads its own table.

## Code References

- `services/java/finance-service/src/main/java/com/medfund/finance/service/CtcPaymentService.java:61-86` — full create + commit paths; note absence of any cross-service or event calls
- `services/java/finance-service/src/main/java/com/medfund/finance/service/CtcPaymentService.java:98-117` — audit publish (entity name = `"CTC <amount> <currency>"`)
- `services/java/finance-service/src/main/java/com/medfund/finance/controller/CtcPaymentController.java:26-83` — 5 endpoints, no `@PreAuthorize`, stale `@Tag`
- `services/java/finance-service/src/main/java/com/medfund/finance/entity/CtcPayment.java` — entity
- `services/java/finance-service/src/main/java/com/medfund/finance/dto/CtcPaymentDtos.java` — request/response records
- `services/java/finance-service/src/main/java/com/medfund/finance/dto/CtcPaymentRow.java` — paginated row with joined names
- `services/java/finance-service/src/main/java/com/medfund/finance/repository/CtcPaymentQueryRepository.java:33-40` — allowed sort keys
- `services/java/finance-service/src/main/java/com/medfund/finance/service/FinanceEventPublisher.java` — publishes for other entities, **not** CtcPayment
- `services/java/finance-service/src/test/java/com/medfund/finance/service/CtcPaymentServiceTest.java` — 7 unit tests only; no IT
- `services/java/tenancy-service/src/main/resources/db/migration/tenant/V016__finance_schema.sql:153-172` — DDL
- `services/java/shared/src/main/java/com/medfund/shared/security/PermissionCatalogue.java` — 3 CTC permission entries with stale descriptions
- `services/java/rules-engine/src/main/java/com/medfund/rules/model/RuleCategory.java:56` — `PROVIDER_PAYMENT` doc mentions CTC
- `services/go/gateway/internal/routes/routes.go:121-122` — proxy to finance-service
- `clients/angular/src/app/pages/tenant/claims/claims.routes.ts:262-286` — claims-side CTC routes
- `clients/angular/src/app/pages/tenant/finance/finance.routes.ts:101-117` — finance-side CTC routes
- `clients/angular/src/app/pages/tenant/billing/billing.routes.ts:232-242` — billing CTC preset (read-only ledger view)
- `clients/angular/src/app/layout/operational-sidebar/operational-nav.ts:138` — sidebar entry (Finance only)
- `clients/angular/src/app/core/services/finance.service.ts:208-387, 608-630` — DTOs + 5 methods
- `clients/angular/src/app/core/security/permissions.ts:86-87, 121` — 3 CTC permission constants
- `clients/angular/src/app/pages/tenant/claims/ctc/ctc-auto.component.html:16-25` — placeholder callout admitting the backend hook doesn't exist
- `clients/angular/src/app/pages/tenant/claims/ctc/ctc-list.component.spec.ts` — pagination contract guard
- `clients/angular/e2e/tests/billing-transaction-presets.spec.ts:15` — asserts billing preset forwards `transactionType=CTC`
- `clients/angular/e2e/fixtures/billing-stubs.ts:803` — billing transaction type stub `{code:'CTC', label:'CTC payment', sign:'+'}`
- `.claude/architecture.md:90`, `.claude/coding-standards.md:589`, `docs/medfund-platform-manual.md:1004-1015, 1427`

## Architecture Insights

- **CTC violates Critical Rule #6 (Kafka events for side-effects) by omission.** The intended behaviour requires contributions-service to react to a CTC commit; there is no such event. The current implementation is silent by design because it doesn't actually do the transfer.
- **CTC violates Critical Rule #7 (Swagger completeness) softly.** Endpoints have Swagger annotations, but the `@Tag` description is misleading enough that a consumer would misunderstand the feature. Description accuracy matters as much as presence.
- **CTC honours Critical Rule #8 (audit-log every mutation)** correctly — create + commit both audit, with a composed human-readable entity name.
- **Server-side authorization is not enforced on the CTC controller.** All five endpoints omit `@PreAuthorize`; whatever gating happens is at the gateway and/or in the UI's route guards. Given the Angular guards for the two roles are different (`claims:view_ctc_payments` vs `finance:manage_ctc_payments`), the server should enforce these too or the split is only UI-deep.
- **The claims-side vs finance-side UI split is thin.** Both roles can create AND commit CTC via the same `/ctc-payments` API; the UX differences (paginated vs single-load, joined names vs raw IDs, tenant-currencies vs master-currencies, browser `confirm()` vs shared confirm service) look more like drift than deliberate separation. If it stays as two entry points, the finance-side list should also use the paginated endpoint and joined names.
- **Rules-engine mentions CTC in a comment but never sees it.** `PROVIDER_PAYMENT.RuleCategory` gestures at CTC governance (auto-approve thresholds, holdbacks), but `CtcPaymentService` makes zero rules calls. Any tenant configuring a CTC rule today would find it inert.
- **`contribution_id` on `ctc_payments` is stored and never actioned.** It's the natural link to close the loop with contributions-service, but nothing reads it.
- **Multi-currency (Critical Rule #1)** — CTC records `currencyCode` per row but there's no code that ever sums or compares CTCs across currencies, so the rule isn't violated. If the missing commit-transfer is implemented, an FX conversion path against the target contribution's currency will be required.

## Historical Context (from thoughts/shared/)

- `thoughts/shared/research/2026-08-08-advance-payments.md` — sibling research on advance payments; useful pattern reference because advance payments *do* publish Kafka events (`FinanceEventPublisher.publishAdvanceApproved/Applied/Reversed`) that CTC currently lacks. Any plan to close the CTC gap should look at the advance-payment event contract as a template.

There are no dedicated prior research docs, plans, or tickets for CTC. This is the first pass.

## Related Research

- [[project_e2e_gaps_billing]] — CTC has no E2E coverage of the commit flow; only the billing preset route is asserted. Worth adding CTC to the deferred E2E list.
- [[bug_public_prefix_silent_rollback]], [[bug_tenant_flyway_outoforder]] — any future CTC-touching migration should follow the tenant-schema rules these memories describe.

## Open Questions

1. **Is the missing ledger transfer intentional (deferred) or an oversight?** F39 in the platform manual gives it as a shipped feature; the code says otherwise. Are there tickets/decisions somewhere describing when this closes out?
2. **Who is the actual user for CTC today?** With no balance impact, creating and committing a CTC is a paper trail with no consequence. Is anyone using it in a running tenant?
3. **Should the two UI entry points stay?** If both roles need to see CTC, the finance-side list should adopt the paginated endpoint + joined names for consistency with claims-side (and with [[feedback_no_raw_id_inputs]]).
4. **Should the auto-CTC flow (from `contribution.committed` Kafka topic) ship in the same iteration as the balance-transfer, or later as a separate feature?**
5. **When the transfer ships, does it move money by (a) posting a contribution transaction via HTTP to contributions-service, (b) publishing `medfund.finance.ctc_committed` for contributions-service to consume, or (c) both?** Advance payments use the event route ([[project_e2e_gaps_billing]] mentions related bits) and that seems the more consistent choice.

## Follow-up Research 2026-08-09 — Does CTC appear in the billing ledger?

**Short answer: no.** Not today, in three independent ways:

1. **CTC is a separate table, not a `transactions` row.** `ctc_payments` (V016, finance-service) is its own ledger. The billing/statement ledger is `transactions` (contributions-service). Nothing in the code writes a row into `transactions` when a CTC commits — `CtcPaymentService.commit` at `services/java/finance-service/src/main/java/com/medfund/finance/service/CtcPaymentService.java:76-86` touches only `ctc_payments` (see Summary above).

2. **The `transaction_types` catalogue has no `CTC` row seeded.** V008 line 3 comment lists CTC as one of the types, but the `INSERT INTO transaction_types` statement immediately below (V008:154-160) never actually includes it — the comment is aspirational and stale.
   - V008 seeds: `ORDINARY, ADJUSTMENT, CREDIT, DEBIT, REVERSAL` (`services/java/tenancy-service/src/main/resources/db/migration/tenant/V008__billing_catalogues.sql:154-160`)
   - V041 renames/deletes: `ORDINARY→PAYMENT`, `REVERSAL→PAYMENT_REVERSAL`, drops `ADJUSTMENT`, adds `REFUND, WRITE_OFF, LATE_ENROLMENT_CHARGE, LATE_TERMINATION_CREDIT, SCHEME_UPGRADE_ARREARS, SCHEME_DOWNGRADE_REBATE` (`V041__transaction_types_and_reason.sql:101-123`)
   - V048 adds: `GROUP_CHANGE_ARREARS, GROUP_CHANGE_REBATE, CURRENCY_CHANGE_ADJUSTMENT` (`V048__member_operations.sql:133-137`)
   - **Final seeded set has no `CTC` code.** The transactions form's type picker (which reads `GET /billing-catalogue/transaction-types`) won't offer CTC in a real tenant.

3. **`TransactionService.record` doesn't validate the type against the catalogue.** `services/java/contributions-service/src/main/java/com/medfund/contributions/service/TransactionService.java:92-125, 147-166` accepts any string in `transactionType` and stores it as-is. So a caller *could* post `transactionType: "CTC"` and it would land in the ledger — but no caller does that today (no code path anywhere writes `"CTC"` into the transactions table).

**The two visible clues that hint the opposite are misleading:**

- **The Billing preset route** `/tenant/billing/transactions/ctc` (`clients/angular/src/app/pages/tenant/billing/billing.routes.ts:232-242`) filters `transactions WHERE LOWER(transaction_type) = LOWER('CTC')`. In a real tenant that list is **always empty** — no seeded catalogue entry, no writer.
- **The E2E stub** at `clients/angular/e2e/fixtures/billing-stubs.ts:803` fabricates `{code:'CTC', label:'CTC payment', sign:'+'}`, which is why `billing-transaction-presets.spec.ts:15` passes — the test exercises the route→query-param forwarding plumbing, not real data.

**What this means:** the "does CTC show up on the statement" question is unanswered by the code — the CTC pending/committed rows live only in the finance-service admin views (`/tenant/finance/payments/ctc`, `/tenant/claims/ctc/*`). A member reading their statement, or an operator scanning the billing transactions ledger, will see **no CTC entry at all**, even after commit. This is consistent with the earlier finding that commit does no cross-service posting — but it also means the billing preset route is dead scaffolding right now.

**References for the follow-up:**
- `services/java/tenancy-service/src/main/resources/db/migration/tenant/V008__billing_catalogues.sql:154-160` — original seed (no CTC)
- `services/java/tenancy-service/src/main/resources/db/migration/tenant/V041__transaction_types_and_reason.sql:101-123` — renames + additions
- `services/java/tenancy-service/src/main/resources/db/migration/tenant/V048__member_operations.sql:133-137` — three more additions
- `services/java/contributions-service/src/main/java/com/medfund/contributions/service/TransactionService.java:92-166` — record path, no catalogue validation
- `services/java/finance-service/src/main/java/com/medfund/finance/service/CtcPaymentService.java:76-86` — commit does not touch `transactions`
- `clients/angular/src/app/pages/tenant/billing/billing.routes.ts:232-242` — the preset route that filters for `CTC` (always empty in prod)
- `clients/angular/e2e/fixtures/billing-stubs.ts:803` — E2E stub that fakes the catalogue row

## Follow-up Research 2026-08-09 — Member-as-payee balance (finance-service gap)

**Prompted by:** "When a member is a payee, how is this balance tracked? Providers have a financial balance."

**Short answer: there is no first-class member-as-payee balance in finance-service today.** The provider side is symmetric and complete; the member-payee side is a gap the recent claim-level payee-routing commit hasn't closed.

### The four balance concepts in the code

| Subject | Direction | Storage | Shape |
|---|---|---|---|
| **Provider** owed by fund (unpaid claims) | fund → provider | `provider_balances` in finance-service | Snapshot table: `total_claimed`, `total_approved`, `total_paid`, `outstanding_balance` per `provider_id + currency_code` — `services/java/finance-service/src/main/java/com/medfund/finance/entity/ProviderBalance.java:12-42` |
| **Member/Group** owes fund (contributions) | member → fund | `member_running_balance` / `group_running_balance` in contributions-service | Derived running balance from posted `transactions` rows; exposed via `GET /balance/member/{id}` and `GET /balance/group/{id}` — `services/java/contributions-service/src/main/java/com/medfund/contributions/controller/BalanceController.java:60-71` |
| **Member** with outstanding advance | fund → member (drawdown pool) | Derived query over `advance_payments` + `advance_payment_applications` | On-demand SQL aggregate, not a snapshot — `services/java/finance-service/src/main/java/com/medfund/finance/repository/AdvancePaymentBalanceRepository.java:70-94` |
| **Member** owed by fund (approved claim, `payee_type=MEMBER`) | fund → member | **Nowhere** | — |

### What commit `e7b8fb3` did and didn't do

The recent "Separate service provider from payee routing on claims" commit added `claims.payee_type` at `services/java/tenancy-service/src/main/resources/db/migration/tenant/V066__claims_payee_type.sql:21` with values `PROVIDER | MEMBER`, plus a CHECK constraint (V066:35-36) that MEMBER-payee claims can still carry a provider for adjudication. In `services/java/claims-service/src/main/java/com/medfund/claims/service/ClaimService.java:352-375` the `resolvePayeeType` guard enforces that PROVIDER-payee claims must have `providerId`, and MEMBER-payee is a valid alternative. `ClaimResponse`, `ClaimRow`, and `SubmitClaimRequest` all surface the new field.

**What the commit did not touch:** the finance-service payment side. `Payment` and `PaymentRunItem` still carry only `provider_id` (`services/java/finance-service/src/main/java/com/medfund/finance/entity/Payment.java:22-23`, `PaymentRunItem.java:24-25`) — no `member_id` column, no `payee_type` discriminator. So today:

- Claims can be marked "pay the member" ✅
- There is no `member_payee_balances` snapshot analogous to `provider_balances` ❌
- The payment run can't emit a `PaymentRunItem` addressed to a member ❌

### Why this matters for CTC

CTC only makes semantic sense when the member is themselves the payee — that is the entire "instead of paying them, credit their contribution bill" premise. The end-to-end mechanic requires:

1. Claim adjudicated with `payee_type=MEMBER` → produces a **member-payable amount** somewhere (either a snapshot `member_payee_balances` row, or a derived aggregate query mirroring `AdvancePaymentBalance`).
2. Committing a CTC → **debits** that member-payable balance and **credits** the same member's contribution ledger in contributions-service via a new transaction type (`CTC_OFFSET` with sign `-`, seeded into `transaction_types`).

Neither leg exists today. Claim-side payee routing has landed; finance-side payout structure and CTC bookkeeping have not. Any plan to ship the CTC transfer needs to treat the missing member-payee balance as a prerequisite — otherwise the CTC has no member-payable amount to net against.

### Additions to Open Questions

- **How should member-payee balance be modelled?** Snapshot table like `provider_balances` (consistent with the existing pattern; needs an updater on claim commit / payment run), or a derived on-demand aggregate like `AdvancePaymentBalance` (cheaper to keep consistent; heavier to query)? Precedent in the repo goes both ways.
- **Does `Payment` / `PaymentRunItem` need to become polymorphic** (`payee_type + payee_id` replacing `provider_id`), or should there be a parallel `MemberPayment` / `MemberPaymentRunItem` structure? Polymorphism is the smaller schema change but touches every existing query.
- **Should the CTC feature ship before or after the member-payee balance work?** CTC is inert without it; the payee balance is useful independently (out-of-pocket reimbursement runs are a real feature).
