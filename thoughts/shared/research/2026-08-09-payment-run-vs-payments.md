---
date: 2026-08-09T13:06:03+02:00
researcher: Methuseli
git_commit: 117d24e07b8239534826dd4484dfa5b7adeb1e69
branch: main
repository: medfund
topic: "How does the payment run work, does it include member payments, and how do payments relate to the payment run?"
tags: [research, codebase, finance-service, contributions-service, payment-run, ctc, member-payables, advance-payments]
status: complete
last_updated: 2026-08-09
last_updated_by: Methuseli
last_updated_note: "Clarified CTC domain rule — CTC is opt-in; default member-payee route is a cash payout like a provider."
---

# Research: Payment Runs — what they are, what they contain, and how member payments relate

**Date**: 2026-08-09T13:06:03+02:00 · **Researcher**: Methuseli · **Commit**: `117d24e` · **Branch**: `main`

## Research Question
How does the payment run work? Does it include member payments? How do payments relate to the payment run?

## Summary

- **A payment run is a strictly *outbound*, *provider-only* batch.** `payment_run_items` has a `provider_id` column and no `member_id` — the batch's job is to bundle provider payouts, apply tenant `PROVIDER_PAYMENT` / `RECONCILIATION` rules (including advance-payment withholds), and finalize a total.
- **Payments that live inside a run**: rows in the `payments` table (one per provider payout), attached via `payment_run_items(payment_run_id, payment_id, provider_id, amount)`. Each item can be reduced by withhold rules and drawn down against outstanding advances FIFO via `advance_payment_applications`.
- **Member contribution receipts (money flowing *in* from members/groups) are not part of payment runs at all.** They live in `contributions-service` as one-at-a-time `transactions` rows; there is no receipt-run / collection-run counterpart on the inbound side. Finance-service consumes zero contribution topics.
- **Member-payee claim payouts (money flowing *out* to a member because they were the claimant) are also not in a payment run today.** When a claim adjudicates with `payeeType=MEMBER`, `ClaimAdjudicatedConsumer` writes a `member_payables` row. `MemberPayable` explicitly notes a future "member-payment-run" concept but it isn't built.
- **Domain rule — CTC is opt-in, not the default member-payee path.** A member-payee claim should by default be paid out in cash *like a provider payout* (i.e. bundled into a `PaymentRun`). CTC (Claims-to-Contributions) is only the settlement route when the member **explicitly requests** their payout be offset against outstanding contributions. So the current codebase covers roughly half of the intended member-payee flow: the CTC-opt-in path has scaffolding (`ctc_payments`, `member_payables`); the default cash-payout path has none — no `member_id` on `payment_run_items`, no `Payment.member_id`, no member-payee `PaymentType` value.
- **CTC payments run beside — not inside — payment runs.** No `payment_run_id` FK on `ctc_payments`. The intent (per `.claude/payments.md` and `thoughts/shared/research/2026-08-09-ctc-payments.md`) is that `commit` posts a `CTC_OFFSET` transaction into the contributions ledger; today the commit only flips `committed=true` and audits — the ledger transfer is not yet wired.
- **The three "outbound" flows are parallel, not nested**: (1) provider payouts → `PaymentRun` batch, (2) member reimbursements → `MemberPayable` + `CtcPayment` offset, (3) advance drawdowns → `AdvancePaymentApplication` attached to run items.

## Findings

### PaymentRun entity and lifecycle

- Entity: `services/java/finance-service/src/main/java/com/medfund/finance/entity/PaymentRun.java:13-112` (table `payment_runs`).
- Fields: `runNumber` (auto `RUN-<6-digit-random>`, uniqueness-checked at `PaymentRunService:448-452`), `status`, `totalAmount`, `currencyCode`, `paymentCount`, `description`, `executedAt`, `executedBy`, `carriedInAmount` / `carriedOutAmount` / `settlementDate` (V067 carry-forward snapshots, `PaymentRun:42-54`), `createdAt`, `updatedAt`, `createdBy`.
- Status string values (no enum, plain `String`): `draft` → optional `approved` → `executing` → `executed`; `cancelled` from any pre-executed state. Blocked once `executed` (`PaymentRunService.cancel` at `services/java/finance-service/src/main/java/com/medfund/finance/service/PaymentRunService.java:224-227`).
- All queries tenant-scoped via `TenantContext.get(ctx)` (`PaymentRunService.java:131,176,204,232`).

### What a run contains

- **Provider payouts only**, joined via `payment_run_items`:
  - Entity: `services/java/finance-service/src/main/java/com/medfund/finance/entity/PaymentRunItem.java:12-62` — columns `payment_run_id`, `payment_id`, `provider_id`, `amount`, `currency_code`, `status`. **No `member_id`.**
  - Item status: `pending` (default) → `scheduled` (when a `SCHEDULE_PAYMENT_RUN` rule fires, `PaymentRunFactBuilder:127`) → `paid` (once settled; `snapshotSettlementDate` SQL at `PaymentRunService:419-443`). Amounts can be reduced by `WITHHOLD_PAYMENT` rules (`PaymentRunFactBuilder:133-134`).
- **The `payments` table** (`services/java/finance-service/src/main/java/com/medfund/finance/entity/Payment.java:13`) — one row per provider payout: `provider_id`, `amount`, `payment_type` (free-form string, values in-use are `claim_payment` / `provider_payment`), `status` (`pending` → `paid` | `cancelled`), `payment_method`, `reference`, `paid_at`.
- **`AdvancePaymentApplication`** (`services/java/finance-service/src/main/java/com/medfund/finance/entity/AdvancePaymentApplication.java:22`) — audit trail of advance drawdown against a specific run item. Written FIFO in `PaymentRunService.drawDownAdvancesFifo:328-352` when a `PROVIDER_PAYMENT` rule withholds. Advances flip from `approved` → `applied` when the balance is fully consumed.
- **Not in a run** (no `payment_run_id` FK anywhere on these tables):
  - `member_payables` (`services/java/finance-service/src/main/java/com/medfund/finance/entity/MemberPayable.java:22`) — status `open` | `applied` | `reversed`.
  - `ctc_payments` (`services/java/finance-service/src/main/java/com/medfund/finance/entity/CtcPayment.java:17`) — `type` = `CTC` | `REVERSAL`; status `draft` → `committed` → `reversed`.
  - `advance_payments` (only their *applications* live in the run).

### How a run is created

- **Only trigger today: manual REST `POST /api/v1/payment-runs`** — `PaymentRunController:86` → `PaymentRunService.create:114-144`.
- Body is currency + optional description (`clients/angular/src/app/pages/tenant/finance/runs/payment-run-generate.component.ts:10-68`).
- Header row only — creation does **not** populate items. Item generation happens later, at `execute()` time (see below).
- Publishes `medfund.payments.run.created` (`FinanceEventPublisher:54-64`).
- **No Kafka consumer creates runs.** No scheduled creator either — the only scheduled job is `PaymentRunExecutor.execute` (`services/java/finance-service/src/main/java/com/medfund/finance/job/PaymentRunExecutor.java:29-40`) which auto-*executes* draft runs older than `autoExecuteAfterHours` (default 24).

### What execute/approve/cancel actually do

- **Approve** (`PaymentRunService.approve:191-216`, `POST /api/v1/payment-runs/{id}/approve`): `draft` → `approved`; publishes `medfund.payments.run.approved` (`FinanceEventPublisher:67-74`). Optional — `execute()` also accepts drafts (`PaymentRunController:99` comment).
- **Execute** (`PaymentRunService.execute:147-188`, `POST /api/v1/payment-runs/{id}/execute`):
  1. Move to `executing`.
  2. `applyTenantRulesToItems()` (`PaymentRunService:248-254`) — runs `PROVIDER_PAYMENT` and `RECONCILIATION` rules from `scheduled_job_configs` via `PaymentRunDecisionService:17-21`.
  3. Rules can flip item status to `scheduled` and reduce item amount via withhold; withhold amounts are captured as advance drawdowns FIFO (`drawDownAdvancesFifo:328-352`).
  4. Recompute run total from item amounts (`recomputeRunTotal:381-391`).
  5. V067: snapshot `carriedOutAmount` (sum of non-paid items, `snapshotCarryOut:399-408`) and `settlementDate` (MAX(payment.paid_at) if all items paid, else null, `snapshotSettlementDate:419-443`).
  6. Move to `executed`, set `executedAt`/`executedBy`.
- **Cancel** (`PaymentRunService.cancel:219-245`, `POST /api/v1/payment-runs/{id}/cancel`): allowed in `draft` | `approved` | `executing`; blocked in `executed` (comment at `PaymentRunController:110`: "Once executed, posting a reversing run is the path"). Idempotent on `cancelled`.
- **No direct call to `services/go/payment-gateway`.** The finance-service publishes events (`medfund.payments.run.executed` at `FinanceEventPublisher:76-83`, per-item `medfund.finance.advance.applied` at `:119-129`); actual money movement is downstream, not in this service.

### Inbound flow — where member contribution receipts live

- Entity: `services/java/contributions-service/src/main/java/com/medfund/contributions/entity/Transaction.java` (table `transactions`). Fields include `transaction_number`, XOR-nullable `group_id` / `member_id`, `amount`, `currency_code`, `transaction_type`, `payment_method`, `status`, `reference`, `reason`.
- `TransactionService.record()` at `services/java/contributions-service/src/main/java/com/medfund/contributions/service/TransactionService.java:91-234`:
  - Validates XOR of `groupId` / `memberId`.
  - Rejects a `memberId` that has a `group_id` set (`rejectIfMemberIsGrouped:163-175`) — enforcing `feedback_grouped_members_cannot_pay`. Comment at line 119 cites the rule.
  - CTC offsets bypass this via a separate `recordFromCtcOffset()` path (lines 127–154) — internal ledger movement, not payer-initiated.
  - Applies to the member/group running balance via `BalanceService.applyTransaction` (line 325 area).
  - Publishes `medfund.contributions.transaction-recorded` (`ContributionEventPublisher.java:156`) with a `TransactionRecordedPayload`, but only for receipt-eligible types (`isReceiptEligible:271`; PAYMENT variants).
- **One-at-a-time only.** There is no `contribution_run` / `receipt_run` / `collection_run` table in contributions-service. The inbound side has no batch container mirroring `PaymentRun`.

### Member-payee claim payouts (the "member payment" that is genuinely outbound)

- Written by `services/java/finance-service/src/main/java/com/medfund/finance/consumer/ClaimAdjudicatedConsumer.java:37-49` (the sole subscriber on `medfund.claims.adjudicated`):
  - `payeeType=PROVIDER` (default for legacy events with empty `payeeType`) → updates `provider_balances` via `ProviderBalanceService`.
  - `payeeType=MEMBER` → inserts a `member_payables` row.
- Kafka reliability: uses `.doOnSuccess` for offset ack (line 100) — deliberately not `.doOnTerminate`, per `bug_reactor_kafka_ack_swallow`.
- The MEMBER-payee amount never enters a payment run today. `MemberPayable.java:17` and `MemberPayableApplication.java:16` both leave TODO-shaped comments referencing "later, by member-payment-run features" — a planned but unbuilt concept.

**Two intended settlement paths for a MEMBER-payee claim:**

1. **Cash payout (the default, per domain rule).** The member is paid out just like a provider — the amount should end up as a line item in a `PaymentRun` and settle through the normal payout pipeline. **This path has zero scaffolding today:**
   - `payment_run_items.provider_id` is non-null; there is no `payee_type` / `member_id` column.
   - `payments.provider_id` is likewise the only payee column; `payment_type` string values in use (`claim_payment`, `provider_payment`) do not distinguish a member payee.
   - No consumer of `ClaimAdjudicatedConsumer`'s member-payee branch drafts a `Payment` row — it stops at `member_payables`.
2. **CTC offset (opt-in only).** The member explicitly requests their claim payout be credited against outstanding contributions instead of being paid out. This route uses `CtcPayment` to draw down a `MemberPayable` and (once wired) post a `CTC_OFFSET` transaction on the contributions ledger.

The gap: current code treats `member_payables` as if CTC were the *only* exit, when domain-wise it's the exception. The "later, by member-payment-run features" comment is what closes path 1.

### CTC as the (opt-in) member-payout channel

- Entity: `services/java/finance-service/src/main/java/com/medfund/finance/entity/CtcPayment.java:17` — target is XOR `groupId | memberId`; type `CTC` | `REVERSAL`; status `draft` → `committed` → `reversed`. `committed` boolean is a back-compat mirror of `status` (lines 37-40) kept for one release.
- Controller `services/java/finance-service/src/main/java/com/medfund/finance/controller/CtcPaymentController.java:29` exposes list/page/get/create/commit/reverse endpoints.
- **Commit today is inert on the ledger side** (see `thoughts/shared/research/2026-08-09-ctc-payments.md`): it flips `committed=true` and audits, but no Kafka event, no cross-service call to contributions-service, no offset transaction posted. The plan `thoughts/shared/plans/2026-08-09-ctc-payments.md` is drafted to close this by publishing `medfund.finance.ctc.committed` and having contributions-service post `CTC_OFFSET`.
- No relationship to `PaymentRun` — no FK, no shared batch.

## Cross-service flow

**Provider payout (inside a run):**
```
ClaimEvent → medfund.claims.adjudicated
  → finance-service ClaimAdjudicatedConsumer (payeeType=PROVIDER)
      → ProviderBalanceService updates provider_balances

Finance clerk → POST /api/v1/payment-runs                        (draft header, no items)
Finance clerk → POST /api/v1/payment-runs/{id}/approve           (optional gate)
Scheduler OR clerk → POST /api/v1/payment-runs/{id}/execute
   ├─ applyTenantRulesToItems() — PROVIDER_PAYMENT / RECONCILIATION rules
   │    ├─ Withhold amount X against outstanding advances FIFO
   │    │    └─ Insert advance_payment_applications (item ↔ advance)
   │    │    └─ Publish medfund.finance.advance.applied
   │    └─ Flip PaymentRunItem.status = 'scheduled', reduce amount
   ├─ recomputeRunTotal + carry-forward snapshots (V067)
   └─ Publish medfund.payments.run.executed
```

**Member-payee claim (outside any run):**
```
ClaimEvent → medfund.claims.adjudicated
  → finance-service ClaimAdjudicatedConsumer (payeeType=MEMBER)
      → INSERT member_payables (status=open)
         ├─ DEFAULT PATH (cash payout, like a provider):
         │    → SHOULD land as a PaymentRun line item and settle in the normal pipeline
         │    → TODAY: no scaffolding — payment_run_items has no member_id, Payment has no member_id.
         │      The member_payables row sits open with no exit unless CTC is invoked.
         └─ OPT-IN PATH (CTC — member requested contribution offset):
              → Manual (or auto-drafted) CtcPayment offsets it
                  → POST /api/v1/ctc-payments/{id}/commit
                      → TODAY: sets committed=true, audits. No event. No offset transaction. Inert.
                      → PLANNED: publish medfund.finance.ctc.committed
                                 → contributions-service posts CTC_OFFSET
```

**Member contribution receipt (fully inbound, never in a run):**
```
Member/liaison payment → POST /api/v1/transactions (contributions-service)
  → TransactionService.record() writes transactions row (with grouped-member guard)
  → BalanceService applies to running balance
  → Publish medfund.contributions.transaction-recorded (receipt-eligible types only)
     → notification-service sends email/SMS receipt
  (finance-service consumes ZERO contributions topics)
```

## Architecture doc vs. code

- **`.claude/payments.md` "Outbound — Payouts" section (lines 301-363)** describes a workflow where the finance clerk *"selects claims to include"* in a payment run. In the code today, `POST /api/v1/payment-runs` only creates a header (currency + description); there is no claims-selection UI at `/finance/runs/generate` (`clients/angular/src/app/pages/tenant/finance/runs/payment-run-generate.component.ts:10-68`) and the item-population step lives in `PaymentRunService.execute` via rules. **Drift**: doc describes a UX not shipped.
- **Doc references `/finance/payouts/*` routes**; the Angular app uses `/finance/runs/*` (`clients/angular/src/app/pages/tenant/finance/finance.routes.ts:26-346`). **Naming drift** — doc is stale.
- **Doc talks about a `Payment Gateway Service` performing `InitiatePayout()` on execute.** Code in `finance-service` publishes `medfund.payments.run.executed` but does not call `services/go/payment-gateway` synchronously. The downstream orchestration is either unbuilt or lives elsewhere. **Behavioural drift**: doc implies stronger integration than exists.
- **CTC is described as a "settled payment flow"** across `.claude/payments.md` and `.claude/architecture.md:85-93`; the code has scaffolding but the commit is a no-op on the ledger (see prior research `thoughts/shared/research/2026-08-09-ctc-payments.md`). **Behavioural drift** — plan `thoughts/shared/plans/2026-08-09-ctc-payments.md` is the intended fix.

## Code References

**Payment run (outbound bundle):**
- `services/java/finance-service/src/main/java/com/medfund/finance/entity/PaymentRun.java:13-112` — entity + V067 carry-forward fields
- `services/java/finance-service/src/main/java/com/medfund/finance/entity/PaymentRunItem.java:12-62` — `provider_id` only, no `member_id`
- `services/java/finance-service/src/main/java/com/medfund/finance/service/PaymentRunService.java:114-144` — `create` (header only)
- `services/java/finance-service/src/main/java/com/medfund/finance/service/PaymentRunService.java:147-188` — `execute` (rules, withholds, snapshots)
- `services/java/finance-service/src/main/java/com/medfund/finance/service/PaymentRunService.java:328-352` — `drawDownAdvancesFifo`
- `services/java/finance-service/src/main/java/com/medfund/finance/service/PaymentRunService.java:399-443` — V067 snapshots
- `services/java/finance-service/src/main/java/com/medfund/finance/service/PaymentRunDecisionService.java:17-21` — tenant rules on runs
- `services/java/finance-service/src/main/java/com/medfund/finance/service/PaymentRunFactBuilder.java:127-134` — rule-driven mutations
- `services/java/finance-service/src/main/java/com/medfund/finance/controller/PaymentRunController.java:26-114` — REST surface
- `services/java/finance-service/src/main/java/com/medfund/finance/job/PaymentRunExecutor.java:29-40` — scheduled auto-execute (creator is manual only)

**Payments (line items in a run) & advance drawdowns:**
- `services/java/finance-service/src/main/java/com/medfund/finance/entity/Payment.java:13-40`
- `services/java/finance-service/src/main/java/com/medfund/finance/entity/AdvancePayment.java:17-59`
- `services/java/finance-service/src/main/java/com/medfund/finance/entity/AdvancePaymentApplication.java:22-37`
- `services/java/finance-service/src/main/java/com/medfund/finance/controller/PaymentController.java:26` — `/api/v1/payments` (supports `paymentRunId` filter — V067)

**Member payables and CTC (outbound, but NOT in a run):**
- `services/java/finance-service/src/main/java/com/medfund/finance/consumer/ClaimAdjudicatedConsumer.java:37-49,92-120` — payeeType dispatch
- `services/java/finance-service/src/main/java/com/medfund/finance/entity/MemberPayable.java:17,22-42` — future member-payment-run comment
- `services/java/finance-service/src/main/java/com/medfund/finance/entity/CtcPayment.java:17-55`
- `services/java/finance-service/src/main/java/com/medfund/finance/controller/CtcPaymentController.java:29-101`

**Contributions receipts (inbound):**
- `services/java/contributions-service/src/main/java/com/medfund/contributions/entity/Transaction.java` — `transactions` table
- `services/java/contributions-service/src/main/java/com/medfund/contributions/service/TransactionService.java:91-234` — `record()` + `rejectIfMemberIsGrouped:163-175` + `recordFromCtcOffset:127-154`
- `services/java/contributions-service/src/main/java/com/medfund/contributions/service/ContributionEventPublisher.java:156,271` — `medfund.contributions.transaction-recorded` (finance-service does NOT consume it)

**Angular UI:**
- `clients/angular/src/app/pages/tenant/finance/finance.routes.ts:26-346`
- `clients/angular/src/app/pages/tenant/finance/runs/payment-run-detail.component.ts:29-190` — shows V067 fields + payments scoped by `paymentRunId`
- `clients/angular/src/app/pages/tenant/finance/runs/payment-run-generate.component.ts:10-68` — currency + description form (no claims-selection)
- `clients/angular/src/app/core/services/finance.service.ts:515-561,614-637` — payment-run / payment / CTC HTTP methods

**Architecture docs:**
- `.claude/payments.md:301-484` — outbound flow (drift noted above)
- `.claude/architecture.md:85-93` — finance-service scope + CTC listed as first-class entity

## Architecture Insights

- **Noun taxonomy is worth internalising.** *Payment run* = batch header. *Payment* = one provider payout (line item). *PaymentRunItem* = the join carrying the withheld/scheduled state. *AdvancePayment* = pre-authorization the provider can draw against. *AdvancePaymentApplication* = the drawdown record attached to a run item. *MemberPayable* = amount owed to a claimant when the payee is the member themselves. *CtcPayment* = offset entry that resolves a member payable by crediting the member's contributions instead of paying cash.
- **Inbound ≠ outbound.** Nothing in `contributions-service` is a "payment" in the finance-service sense. If someone says "member payment," ask: incoming premium (contributions-service `Transaction`) or outgoing claim reimbursement (finance-service `MemberPayable`/`CtcPayment`)? These are separate services with separate tables and separate ledgers.
- **Rule 6 (Kafka-only inter-service side-effects) is respected.** Finance-service and contributions-service only interact via events. Finance-service publishes 4 topics on the run lifecycle (`FinanceEventPublisher.java`); it consumes exactly one topic (`medfund.claims.adjudicated`).
- **Rule 2 (tenant-scoping) is respected** across all payment-run reads/writes via `TenantContext.get(ctx)`.
- **Rule 1 (currency) is respected on withholds** — `PaymentRunService.execute` uses `FxConverter` to normalize aggregate advance balances into item currency before feeding the rules engine.
- **Rule 8 (audit on every mutation)** — `PaymentRunService` publishes audit events on `create`/`approve`/`execute`/`cancel` alongside Kafka. `ClaimAdjudicatedConsumer` audits member-payable writes.
- **Missing piece the code openly flags**: `MemberPayable`/`MemberPayableApplication` javadocs say "later, by member-payment-run features" — this is the honest scaffolding stub for the second batch container that would mirror `PaymentRun` for member-payee flows.

## Historical Context (from thoughts/shared/)

- `thoughts/shared/research/2026-08-09-ctc-payments.md` — Detailed analysis of the CTC scaffold gap: commit inert, no ledger transfer, no Kafka event, member-payable balance missing. The follow-up plan below is the intended fix.
- `thoughts/shared/plans/2026-08-09-ctc-payments.md` — 5-phase draft plan to wire CTC commit end-to-end.
- `thoughts/shared/research/2026-08-08-advance-payments.md` — Prior research on the advance-payment orphan (form CTA missing, offset seam fed ZERO to rules). Fixed in commit `117d24e` — the current codebase already shows V068 status/type/approval/reversal columns and functional FIFO drawdown.
- `thoughts/shared/plans/2026-08-08-advance-payments-full-lifecycle.md` — The plan that shipped in `117d24e`; useful as a template for the parallel member-payment-run buildout when it comes.

## Related Research

- `thoughts/shared/research/2026-08-09-ctc-payments.md` — direct dependency for anything that touches member-payee payouts.
- `thoughts/shared/research/2026-08-09-contribution-statement-pdf-divergence.md` — adjacent contribution-side context.

## Open Questions

1. **Where does item population actually happen for a `PaymentRun`?** `create()` writes only a header; items appear by the time `execute()` runs. Is there a claim-payment ingestion path (Kafka consumer? scheduled job?) that inserts `payment_run_items` between create and execute? The trail was not fully mapped in this pass — worth a follow-up read of `PaymentRunService.applyTenantRulesToItems` and how `pending` `payment_run_items` originate. If items are only populated inside `execute()` itself, then the create-then-execute UX in `.claude/payments.md` becomes even more misleading.
2. **Who drives money out to the payment gateway?** `services/go/payment-gateway` exists as a service but finance-service publishes events rather than calling it. Is there a consumer of `medfund.payments.run.executed` in `payment-gateway/`, `notification-service/`, or elsewhere that initiates the actual bank movement? Not investigated in this pass.
3. **When the planned "member-payment-run" concept lands**, will it reuse `PaymentRun` with a new `payee_type` column, or introduce a parallel table? The scaffolding comments in `MemberPayable`/`MemberPayableApplication` don't say. Reusing `PaymentRun` (adding `payee_type` + nullable `member_id` on `payment_run_items` / `payments`) is the smaller change and matches the domain framing that a member cash payout should look like a provider payout.
4. **Where does the "CTC opt-in" decision get captured?** If CTC is opt-in per member/per claim, there needs to be a signal at claim creation, adjudication, or member profile. Not investigated — worth a follow-up read to see if there's a `member.ctc_preferred` flag, a per-claim `settlement_preference`, or if it's purely a manual finance-desk decision today.

## Follow-up 2026-08-09 — CTC is opt-in

**Domain clarification from stakeholder**: CTC is only invoked when a member *explicitly requests* their claim payout be offset against contributions. The default settlement route for a MEMBER-payee claim is a cash payout — treated the same as a provider payout, bundled into a `PaymentRun`. The research above has been revised to reflect this:

- Summary now names two intended paths (cash payout as default, CTC as opt-in).
- "Member-payee claim payouts" section now enumerates both paths and calls out that the default cash-payout path has zero scaffolding today.
- The Kafka flow diagram now shows both branches and marks the default branch as an open exit.
- A fourth open question tracks where the opt-in signal gets captured.

This reframes the "member-payment-run" gap: it's not a nice-to-have on top of CTC — it's the *default* path that hasn't been built, while the exception path (CTC) is the one with the (still inert) scaffolding.
