---
date: 2026-08-10T00:00:00+02:00
researcher: Methuseli
git_commit: c248073df71e8e42d035addfd5a090e5b39bacba
branch: main
repository: medfund
topic: "Debit and credit notes in the context of insurance — what the domain expects vs. what InsureFlow implements"
tags: [research, codebase, finance-service, contributions-service, angular, notes, adjustments]
status: complete
last_updated: 2026-08-10
last_updated_by: Methuseli
last_updated_note: "Follow-up — settled naming direction (Option 1: rename Adjustment → Note with a `direction` column; retire the current memo-only debit/credit note tables) and required notes to render on `PaymentAdvice` in the correct period. Then a grilling pass on 2026-08-10 resolved 16 remaining decision forks (G1-G16, see `## Grilling decisions` section — includes G9a and G12 settled by fact) and corrected 5 factual overstatements in the follow-up section (see strike-through edits)."
---

# Research: Debit and credit notes in the context of insurance

**Date**: 2026-08-10 · **Researcher**: Methuseli · **Commit**: c248073 · **Branch**: main

## Research Question
What are "debit notes" and "credit notes" in an insurance context, and how does the InsureFlow codebase model them today across the Java finance/contributions services, the tenant SQL, and the Angular finance portal?

## Summary

**Domain meaning (insurance).** A debit note *increases* what a counterparty owes; a credit note *decreases* it or refunds. In insurance the two show up in three separate money-flows: (1) **premium/contribution** (mid-term endorsements, backdated enrolment arrears, refunds), (2) **claim / provider payout** (over-payment recovery, tax withholding, goodwill), and (3) **reinsurance / inter-company settlements**. Each note is normally addressed *to* a specific payee (member, employer group, provider, reinsurer), *for* a specific underlying document (policy, invoice, claim, payment run), and moves a real ledger balance.

**What InsureFlow actually has.** Two near-identical `debit_notes` / `credit_notes` tables in the tenant schema (finance-service, V016), a `NotesController` with GET + POST endpoints, and one Angular list-and-create screen. The SQL comment scopes them narrowly: *"audit-trail entries for manual finance adjustments outside the adjustments table (e.g. one-off bank-fee write-offs, goodwill credits."* — [`V016__finance_schema.sql:198-199`](https://github.com/MecksonMfema/medfund/blob/c248073df71e8e42d035addfd5a090e5b39bacba/services/java/tenancy-service/src/main/resources/db/migration/tenant/V016__finance_schema.sql#L198-L199). They deliberately are *not* the domain-full "insurance debit/credit note" — they carry no payee, no linked document, no status/lifecycle, no reversal, and no approval gate. Every other movement (premium arrears, TAX_WITHHELD, CTC offset, advance payments, provider balance movement) is modelled elsewhere — as **Adjustments**, **Transactions**, or **PaymentAdvice** line-items — none of which use the notes tables.

The upshot: what the code calls "notes" is really a manual bank-fee / goodwill sticky-note. The genuinely insurance-shaped debit/credit-note flows (endorsement arrears, provider over-payment recovery) live under other names, and the `.claude/*.md` docs promise a richer notes feature (granular per-note permissions, dedicated management pages) that the code has not built.

## Findings

### 1. What "debit note" and "credit note" mean in insurance (domain background)

Insurance uses the terms with a very specific direction convention:

- **Debit note** → the issuer is telling the recipient "you owe more." Common uses:
  - **Endorsement premium**: mid-term policy change increases the sum insured or extends cover → additional premium is billed via a debit note.
  - **Backdated enrolment / cover start**: the member/employer owes the missed cycles → a debit note posts the arrears. In InsureFlow this is exactly the "backdated enrolment → arrears adjustment" pattern captured in memory (`[[project_backdated_enrolment_adjustment]]`).
  - **Provider over-payment recovery**: an audit finds a claim was paid twice or above tariff → a debit note is raised against the provider to be offset against the next payment run.
  - **Reinsurance recovery / premium**: the cedant bills the reinsurer (or vice-versa).

- **Credit note** → the issuer is telling the recipient "you owe less" or "here's a refund." Common uses:
  - **Cancellation / mid-term downgrade**: return of unearned premium.
  - **Refund of overpayment**: member overpaid contribution → refund posted via credit note.
  - **Provider write-off / goodwill**: waiving a shortfall or a duplicate charge.
  - **Rebate**: e.g. scheme downgrade rebate for a backdated group change.

Two structural expectations follow from this domain reading, both of which matter for the code review below:

1. A note is **always addressed to a payee** (member, group, provider, reinsurer) and **references an underlying document** (invoice, claim, payment-run item, policy endorsement).
2. A note is a **posting** — it moves a real ledger balance and appears as a line item on a statement or payment advice. It is not just a memo.

Neither of these expectations is captured by the InsureFlow `debit_notes` / `credit_notes` tables.

### 2. Java finance-service — the actual `DebitNote` / `CreditNote` stack

**Entities.** `DebitNote` and `CreditNote` are byte-for-byte parallel, both very thin:

- `services/java/finance-service/src/main/java/com/medfund/finance/entity/DebitNote.java:20-39`
- `services/java/finance-service/src/main/java/com/medfund/finance/entity/CreditNote.java:20-39`

Fields on each: `id`, `amount` (BigDecimal 19,4), `currencyCode` (3 chars), `reference`, `taskId` (UUID → support ticket), `notes` (free text), `createdAt`, `createdBy`. **No `payee_type`, no `payee_id`, no `provider_id`, no `member_id`, no `invoice_id`, no `claim_id`, no `payment_run_id`, no `status`, no `approved_by`, no `reversed_by`, no `direction` flag.** The direction is implied entirely by which table you write to.

**Migration.** [`services/java/tenancy-service/src/main/resources/db/migration/tenant/V016__finance_schema.sql:197-220`](https://github.com/MecksonMfema/medfund/blob/c248073df71e8e42d035addfd5a090e5b39bacba/services/java/tenancy-service/src/main/resources/db/migration/tenant/V016__finance_schema.sql#L197-L220). The comment scopes them: `"one-off bank-fee write-offs, goodwill credits"`.

**Controller.** [`services/java/finance-service/src/main/java/com/medfund/finance/controller/NotesController.java`](https://github.com/MecksonMfema/medfund/blob/c248073df71e8e42d035addfd5a090e5b39bacba/services/java/finance-service/src/main/java/com/medfund/finance/controller/NotesController.java):
- `GET /api/v1/debit-notes` and `/page` — list + server-side paginated (line 46-63)
- `POST /api/v1/debit-notes` — create (line 65-79)
- `GET /api/v1/credit-notes` and `/page` (line 81-98)
- `POST /api/v1/credit-notes` (line 114-128)
- **No PUT/PATCH/DELETE.** Notes are append-only once written.
- Swagger `@Tag`, `@Operation`, `@SecurityRequirement(name="bearer-jwt")` annotations are present — the endpoints satisfy Critical Rule #7 (OpenAPI completeness).
- Audit is emitted on create via `publishAudit(...)` at line 138-158 — satisfies Critical Rule #8. `AuditActor.id(jwt)` and `AuditActor.email(jwt)` are used per [[feedback_audit_actor_email]].

**Two concrete implementation defects worth naming:**

- **`created_by` is never populated** by either POST handler (line 69-74 and 118-123 — you can see `entity.setAmount(...)` etc. but no `entity.setCreatedBy(...)`). The column exists in the schema and on the entity but stays NULL. The audit event *does* record `actorId`, so the actor is not lost — but the note row itself has no author trace. Compare with `AdjustmentController` which threads the JWT into the service layer (`adjustmentService.create(request, AuditActor.id(jwt), AuditActor.email(jwt))`).
- **`entityName` in the audit event is set to the `reference` field** (line 76, 125), which is free-form and optional. Per [[feedback_audit_entity_name]] this should be a friendly-text label — if `reference` is null the audit event's `entityName` is null too.

**Query surface.** `NoteQueryRepository` (dynamic SQL, both tables via a `NoteTable` enum) supports pagination, currency filter, and full-text `q` search on `reference` + `notes`. Sort keys: `amount`, `currencyCode`, `reference`, `createdAt`. No sort or filter by any payee dimension because there is no payee column.

**Nothing else in finance-service reads or writes these tables.** No Kafka consumer creates notes, no scheduled job produces them, no other service references the entities. `ClaimAdjudicatedConsumer` and `PaymentAdviceStatusConsumer` are unrelated. So the *only* way a debit or credit note appears today is a human clicking "New Debit Note" / "New Credit Note" in the finance portal.

### 3. Contributions-service — where premium adjustments actually live (and do *not* touch notes)

`InvoiceSnapshotService` was the only contributions-side hit for the words "debit note". It does **not** produce a note — the reference is a single explanatory comment:

- [`services/java/contributions-service/src/main/java/com/medfund/contributions/service/InvoiceSnapshotService.java:31-34`](https://github.com/MecksonMfema/medfund/blob/c248073df71e8e42d035addfd5a090e5b39bacba/services/java/contributions-service/src/main/java/com/medfund/contributions/service/InvoiceSnapshotService.java#L31-L34) — comment classifies sign='+' transactions as *"adjustment / debit note / loaded premium"*.

The service reads the `transactions` table windowed on `[prior.committed_at, this.committed_at)` and stamps `opening_balance`, `payments_in_window`, `adjustments_in_window`, `closing_balance` onto the invoice at commit time (`stampSnapshot`, line 62-90). It is *transaction-neutral* — it sums whatever adjustments exist regardless of origin.

**Where premium/contribution debit-and-credit-note-shaped movements actually live:** as **transactions with typed sign** in the contributions-service ledger:

- `SchemeChangedConsumer` / `GroupChangedConsumer` auto-post typed transactions on backdated scheme or group changes:
  - `SCHEME_UPGRADE_ARREARS` (sign='+') — the debit-note flow: member owes more.
  - `SCHEME_DOWNGRADE_REBATE` (sign='-') — the credit-note flow: member owed less.
- (`SchemeChangedConsumer.java:102-103`, `GroupChangedConsumer.java:102-103`, `SchemeChangeService.java:88-89`.)
- `ArrearsNoticePublisher` publishes `medfund.contributions.arrears-notice` when contributions fall behind (`ArrearsNoticePublisher.java:41`).

None of these flows produce a `debit_notes` or `credit_notes` row. They post `transactions` with `transaction_types.sign` and let `InvoiceSnapshotService` fold them into the next invoice snapshot — the arrears / rebate then appears on the member's statement as a line item.

### 4. Angular finance portal — the notes UI

**One component, two modes.**

- [`clients/angular/src/app/pages/tenant/finance/notes/notes-list.component.ts`](https://github.com/MecksonMfema/medfund/blob/c248073df71e8e42d035addfd5a090e5b39bacba/clients/angular/src/app/pages/tenant/finance/notes/notes-list.component.ts) — list + inline create form; branches on route data `mode: 'debit' | 'credit'` (lines 1-163).
- No detail page, no edit, no delete.
- Routes: [`finance.routes.ts:180-190`](https://github.com/MecksonMfema/medfund/blob/c248073df71e8e42d035addfd5a090e5b39bacba/clients/angular/src/app/pages/tenant/finance/finance.routes.ts#L180-L190) → `/tenant/finance/debit-notes`, `/tenant/finance/credit-notes`.
- Service methods: `finance.service.ts:846` (`createDebitNote`), `:851` (`createCreditNote`).
- **No e2e specs cover notes** (Playwright fixtures reference a `DEBIT_CREDIT` transaction-type label at `billing-stubs.ts:804`, but no request or flow mocks) — this is a natural addition to the deferred list in [[project_e2e_gaps_billing]].
- **No member, provider, or admin surface links to notes.** They live in the finance section only.

**Permission gating.**

- Angular guards both routes with a single permission: [`finance:post_adjustments`](https://github.com/MecksonMfema/medfund/blob/c248073df71e8e42d035addfd5a090e5b39bacba/clients/angular/src/app/pages/tenant/finance/finance.routes.ts#L181) — the same permission used for `/finance/adjustments`, `/finance/group-adjustments-report`, and other post-time actions.
- Permissions catalogue: [`permissions.ts:39,129`](https://github.com/MecksonMfema/medfund/blob/c248073df71e8e42d035addfd5a090e5b39bacba/clients/angular/src/app/core/auth/permissions.ts#L39) — key `finance:post_adjustments`, label "Post adjustments".

This is a code-vs-doc drift; see next section.

### 5. Prior InsureFlow research on adjacent concepts

Nothing in `thoughts/shared/` targets debit/credit notes directly, but three prior docs establish the ledger patterns the code has adopted for everything *around* notes:

- `thoughts/shared/research/2026-08-08-advance-payments.md` — Advance payments are half-connected; the offset seam in payment runs is unfed.
- `thoughts/shared/plans/2026-08-08-advance-payments-full-lifecycle.md` — Introduces the **append-only compensating-entry** reversal pattern (`reverses_advance_id` FK, DB constraint `((type='REVERSAL') = (reverses_advance_id IS NOT NULL))`), status lifecycle `pending → approved → applied → reversed`, and an approval gate.
- `thoughts/shared/plans/2026-08-09-ctc-payments.md` — Same compensating-entry pattern for CTC offset (`CTC_OFFSET` / `CTC_OFFSET_REVERSAL` transaction types with typed sign).
- `thoughts/shared/research/2026-08-10-creditors-workflow-unify-providers-and-members.md` — Establishes the dual-ledger model: `provider_balances` (snapshot) vs `member_payables` (event-log); notes that `Adjustment` DTOs already carry `providerId?` / `memberId?` and filter on `payee_type`.

The `Adjustment` entity has everything the note entities lack: `status` (pending/approved/applied/reversed), `payee_type`, per-payee FKs, an approval permission (`finance.adjustments:approve` per docs), and a `TAX_WITHHELD` type that feeds `PaymentAdvice`. Debit/credit notes were built as a *deliberately simpler* sibling to `Adjustment` for cases where the finance clerk just wants a memo entry.

## Cross-service flow

For the notes tables themselves there is **no cross-service flow**. Create is synchronous: Angular → `POST /api/v1/{debit,credit}-notes` on finance-service → R2DBC insert → audit event to Kafka topic `medfund.finance.audit-events` (via `AuditPublisher`).

For the *insurance-shaped* debit/credit-note behaviour, the actual flow lives elsewhere:

- **Backdated scheme upgrade (debit-note-shaped).** `medfund.tenancy.scheme-changed` → `SchemeChangedConsumer` (contributions-service, `SchemeChangedConsumer.java:102`) → `SchemeChangeService.applyBackdatedArrears` posts a `SCHEME_UPGRADE_ARREARS` transaction (sign='+') → next `InvoiceSnapshotService.stampSnapshot` folds it into `adjustments_in_window` → `medfund.contributions.invoice-issued` → file-service PDF + notification-service email.
- **Backdated scheme downgrade (credit-note-shaped).** Same path with `SCHEME_DOWNGRADE_REBATE` (sign='-').
- **Provider tax withholding (credit-note-shaped, on the provider's advice).** `Adjustment` row of type `TAX_WITHHELD` → `PaymentAdviceGenerator` picks it up as a `TAX_WITHHELD` line reducing `net_due_amount` (per `.claude/payments.md:380-395`).

None of these flows write to `debit_notes` / `credit_notes`.

## Architecture doc vs. code

Three concrete drifts between `.claude/*.md` and the code:

1. **Permission granularity.** [`portals.md:511-517`](https://github.com/MecksonMfema/medfund/blob/c248073df71e8e42d035addfd5a090e5b39bacba/.claude/portals.md#L511-L517) specifies six granular permissions:

   ```
   finance.adjustments:read | :write | :approve
   finance.debit_notes:read | :write
   finance.credit_notes:read | :write
   ```

   The Angular code uses a single flat permission `finance:post_adjustments` for all of adjustments, debit notes, credit notes, and group-adjustments reports (`finance.routes.ts:151,157,163,175,181,187,229,230`). There is no `:approve` permission and no separate read/write split. This is the same doc-vs-code shape as the `.claude/adjudication.md` drift patterns — architectural intent, no code yet.

2. **Note lifecycle.** `portals.md:151-152` promises "Issue and manage" pages for debit and credit notes. The code has an in-line create form on a single list component and no manage/detail surface. There is no issuance workflow (draft → issue → deliver), no approval, and no reversal path — even though the adjacent `Adjustment` and `AdvancePayment` entities do have status lifecycles.

3. **Payee dimension.** `architecture.md:85-93` lists `DebitNote` and `CreditNote` alongside `Payment`, `PaymentRun`, `ProviderBalance` — implying they belong to the same ledger domain. The other three entities all carry a payee; the note entities do not. This means today's notes cannot appear on a provider statement, a member payment advice, or a bank reconciliation — they exist only on their own list page.

## Code References

- `services/java/finance-service/src/main/java/com/medfund/finance/entity/DebitNote.java:20-39` — 8-field entity, no payee, no status.
- `services/java/finance-service/src/main/java/com/medfund/finance/entity/CreditNote.java:20-39` — mirror of DebitNote.
- `services/java/finance-service/src/main/java/com/medfund/finance/controller/NotesController.java:65-79` — POST debit note; `createdBy` never set; audit `entityName` uses free-form `reference`.
- `services/java/finance-service/src/main/java/com/medfund/finance/controller/NotesController.java:114-128` — POST credit note; same defects.
- `services/java/finance-service/src/main/java/com/medfund/finance/controller/NotesController.java:138-158` — audit emission (correctly threads `AuditActor.id`/`email`).
- `services/java/finance-service/src/main/java/com/medfund/finance/repository/NoteQueryRepository.java:30-108` — dynamic-SQL paged search on both tables via `NoteTable` enum.
- `services/java/tenancy-service/src/main/resources/db/migration/tenant/V016__finance_schema.sql:197-220` — tables and their scoping comment.
- `services/java/contributions-service/src/main/java/com/medfund/contributions/service/InvoiceSnapshotService.java:31-34` — the only mention of "debit note" in contributions, and only as documentation.
- `clients/angular/src/app/pages/tenant/finance/notes/notes-list.component.ts:1-163` — single component, both modes, create + list.
- `clients/angular/src/app/pages/tenant/finance/finance.routes.ts:180-190` — routes and single-permission gate.
- `clients/angular/src/app/core/services/finance.service.ts:846,851` — `createDebitNote` / `createCreditNote` client methods.
- `.claude/architecture.md:85-93` — architecture-doc entity list including DebitNote/CreditNote.
- `.claude/portals.md:148-152, 511-517` — the promised UI routes and granular per-note permissions.
- `.claude/payments.md:380-395` — where TAX_WITHHELD (the true credit-note-shaped flow for providers) actually posts.

## Architecture Insights

- **Notes ≠ ledger movements.** The `debit_notes` / `credit_notes` tables sit *outside* the ledger. They don't affect `provider_balances`, `member_payables`, `PaymentAdvice`, or any invoice's `closing_balance`. Treat them as memo/sticky-note storage — nothing more.
- **The insurance-shaped debit/credit-note behaviour is already implemented under other names.** For premium: typed `transactions` with `sign`. For provider payouts: `Adjustment` rows (esp. `TAX_WITHHELD`) folded into `PaymentAdvice`. For member offset: CTC + `member_payables` (planned) using compensating-entry reversals. If someone asks "where are our credit notes?", the honest answer is *"depends which flow — arrears/rebate transactions for premium, adjustments for provider payouts, member_payables for member offset."*
- **The compensating-entry (append-only, `reverses_*_id`) pattern is now house style** across advances, CTC, and adjustments — but *not* applied to notes. If notes ever grow a lifecycle, they should adopt the same shape rather than getting mutable columns.
- **Critical Rule #1 (currencies)**: entities store `currency_code` alongside `amount` (BigDecimal 19,4) — no arithmetic on mixed-currency amounts is possible on the note side.
- **Critical Rule #2 (tenant scoping)**: notes rely on the per-request `TenantContext` and the schema-per-tenant model — no explicit `tenant_id` column on the row, which is consistent with the tenant-scoped schema convention elsewhere in this service.
- **Critical Rule #7 (Swagger)**: `NotesController` is fully annotated.
- **Critical Rule #8 (audit)**: create events publish, but the on-row `created_by` never gets populated and `entityName` is derived from a nullable free-form field — two small but real hygiene issues per [[feedback_audit_entity_name]] / [[feedback_audit_actor_email]].

## Historical Context (from thoughts/shared/)

- `thoughts/shared/research/2026-08-08-advance-payments.md` — advance-payment CRUD exists but is orphaned from the payment-run offset seam.
- `thoughts/shared/plans/2026-08-08-advance-payments-full-lifecycle.md` — codifies the append-only reversal + approval-gate pattern that notes would adopt if they ever grow a real lifecycle.
- `thoughts/shared/plans/2026-08-09-ctc-payments.md` — introduces `member_payables` and CTC_OFFSET/REVERSAL types with `transaction_types.sign`, the credit-note-shaped movement on the member side.
- `thoughts/shared/research/2026-08-10-creditors-workflow-unify-providers-and-members.md` — documents the dual creditor ledgers (provider_balances vs member_payables) that any richer note feature would need to link into.

## Related Research

- `thoughts/shared/research/2026-08-08-advance-payments.md`
- `thoughts/shared/research/2026-08-10-creditors-workflow-unify-providers-and-members.md`

## Open Questions

1. **Is the current scope intentional?** The SQL comment ("bank-fee write-offs, goodwill credits") reads as deliberate — a small memo table separate from `Adjustment`. If yes, the docs promising `finance.debit_notes:read/write/approve` and dedicated management pages should be scaled back. If no, the notes tables need payee + document links + lifecycle to become real insurance notes.
2. **Should endorsement-driven premium adjustments (backdated enrolment arrears) actually write a debit-note row for member visibility**, in addition to the `SCHEME_UPGRADE_ARREARS` transaction? Right now they're only visible as an "Adjustments" line on the invoice PDF — no explicit "Debit Note #12345" artefact members can search or reference. **[G10, 2026-08-10]** — deferred; not in this plan. Contributions arrears stay as `SCHEME_UPGRADE_ARREARS` / `SCHEME_DOWNGRADE_REBATE` transactions on the contributions-service ledger. Cross-service mirroring to a Note row is a follow-up ticket.
3. **Provider over-payment recovery** has no dedicated flow anywhere (there's no ClaimReversal / OverpaymentRecovery entity). A "provider debit note" that offsets against the next `PaymentAdvice` would fit naturally into the existing advice-line-type table but is not built.
4. **Should the two `created_by`-not-populated and `entityName`-from-nullable-reference defects** in `NotesController` be fixed independently of any larger notes rework? Both are cheap and align with existing memories.

## Follow-up Research 2026-08-10

### Naming-convention correction

The original synthesis under-weighted the domain-vocabulary argument. In insurance/accounting practice, a **debit note** and a **credit note** *are* the industry-standard artifacts a finance clerk raises when they post a money-moving adjustment — the direction lives in the name of the document itself. "Adjustment" is generic software vocabulary that a domain user would never say out loud.

That reverses the earlier tentative framing ("Adjustments = ledger; Notes = memo — a legitimate distinction, just poorly named"). Under the naming-convention lens, the current split is not a legitimate distinction — it's a **naming misfire**: the entity that actually moves money got the software-generic name (`Adjustment`), and the entity name that *should* mean "money-moving posting" (`Note`) got attached to a memo table with no ledger effect. A finance clerk asked to "raise a debit note against provider X" would today have to use the Adjustments screen, not the Debit Notes screen — because the Adjustments screen is the one that moves the balance.

### Decision — Option 1: rename `Adjustment` → `Note` with a `direction` column

Fold the two concerns into one ledger primitive under the domain name:

- **One table `notes`** (replaces `adjustments` and retires the standalone `debit_notes` / `credit_notes` tables).
- **`direction ∈ {DEBIT, CREDIT}`** — the sole thing that distinguishes a debit note from a credit note at the schema level. UI splits the two lists by filtering on this column.
- **`type`** carries the *reason*: `TAX_WITHHELD`, `WRITE_OFF`, `GOODWILL`, `ENDORSEMENT_PREMIUM`, `PREMIUM_REFUND`, `PROVIDER_OVERPAYMENT_RECOVERY`, `MEMO`, etc. `MEMO` (or amount=0 memos on a payee) is the seam through which the current standalone-notes use-case survives — a finance clerk still gets to record a bank-fee write-off, they just do it as a `direction=DEBIT, type=WRITE_OFF` note rather than a bare row.
- ~~**Keep the existing Adjustment shape** for everything else: `payee_type` + `providerId?` / `memberId?`, `status ∈ {pending, approved, applied, reversed}`, `approved_by`, `reverses_note_id` (compensating-entry pattern per [[advance-payments-full-lifecycle]] and [[ctc-payments]]), currency + BigDecimal amount, linked-document FKs (invoice, claim, payment-run).~~ **[Corrected by grilling 2026-08-10]** Preserve what `Adjustment` actually has today: `providerId?` / `memberId?` with payee inferred from which is non-null (there is **no** `payee_type` column), `status` (values `{pending, approved, applied, cancelled}` today per `V016:77-78`), `approved_by`, `approved_at`, `amount`, `currency_code`, `reason` (full shape at `V016:67-85`). **The rename ADDS several columns that don't exist today**: `direction` (G1), `posted_at` for the advice window (G2), `reverses_note_id` FK + `type IN {ORIGINAL, REVERSAL}` compensating-entry pattern (G3), and `note_type` replacing `adjustment_type` with a new value set (G9). The `status` set becomes `{pending, approved, applied, reversed}` — drops `cancelled`, adds `reversed` (G3). Linked-document FKs (invoice, claim, payment-run) do NOT exist on Adjustment today and are NOT added in phase 1.
- **Payment advice generation gains generic note lines, not just a TAX_WITHHELD rename.** Every note posted against a payee (provider or member) in the advice's period window must appear on that payee's `PaymentAdvice` so the recipient sees *why* their balance moved. Specifics under "Advice integration" below.
- **Retire the current `debit_notes` / `credit_notes` tables** in the same migration. They are almost empty in most tenants (per the V016 comment they were seeded for goodwill credits and bank fees only); their rows fold in as `direction=…, type='MEMO'` or `type='WRITE_OFF'`. If a tenant has non-trivial usage, the migration backfills a rowwise INSERT into `notes` before dropping.
- **Permissions consolidate** onto the note vocabulary: `finance.notes:read`, `finance.notes:write`, `finance.notes:approve` — replacing the currently split-in-docs, unified-in-code (`finance:post_adjustments`) permission set. The docs-vs-code drift in `.claude/portals.md:511-517` gets resolved by this rename rather than by widening the permission enum.
- **UI naming follows the domain**: `/tenant/finance/debit-notes` and `/tenant/finance/credit-notes` remain the user-facing routes (they're already there — see `finance.routes.ts:180-190`), but the underlying component switches from the memo-only `NotesListComponent` to whatever component the Adjustments screen currently uses, filtered by `direction`. The current `/tenant/finance/adjustments` route either becomes a redirect to a combined "All Notes" screen or is retired.

### Advice integration — notes must land on `PaymentAdvice` in the correct period

Under Option 1 the notes table is not just a source for TAX_WITHHELD — it is the *general-purpose* ledger of money-moving adjustments against a payee. Every note therefore has to appear on the payee's `PaymentAdvice` for the period the note was posted in, otherwise the balance movement will not reconcile from the provider's / member's point of view.

- **Period semantics.** `PaymentAdvice` is bounded by `(prior_run.executed_at, this_run.executed_at]` per [`.claude/payments.md:380-395`](https://github.com/MecksonMfema/medfund/blob/c248073df71e8e42d035addfd5a090e5b39bacba/.claude/payments.md#L380-L395). Notes join on the same half-open interval, using a **`posted_at`** column on `notes` (add during the rename migration — do **not** use `created_at`, which is a technical insert timestamp; a note may be drafted, approved, and posted-effective on different dates). If `posted_at` is absent on migrated rows, backfill from `created_at`.
- **Advice line-type set gains two rows** in the table at `.claude/payments.md:380-395`:

  | Line type | Direction on advice | Source |
  |---|---|---|
  | `NOTE_CREDIT` | Credit (reduces `net_due_amount`) | `notes` where `direction='DEBIT'` (payee owes us — offsets what we pay them), payee matches, `posted_at ∈ window`, `note_type NOT IN ('MEMO','TAX_WITHHELD')` |
  | `NOTE_DEBIT` | Debit (increases `net_due_amount`) | `notes` where `direction='CREDIT'` (we owe payee more — added to what we pay them), payee matches, `posted_at ∈ window`, `note_type NOT IN ('MEMO','TAX_WITHHELD')` |

  Filter exclusions ([G4], [G8]): MEMO notes are payee-less (bank fees / goodwill without a target payee) so they never match any payee's advice. TAX_WITHHELD keeps its own specialised advice line rather than folding into `NOTE_CREDIT`. Both PROVIDER and MEMBER-payee advices source notes symmetrically.

  The advice-side direction is the **inverse** of the note direction because the advice ledger tracks *what we owe the payee* while the note direction is issued from *our* books. This inversion is the classic accounting confusion and needs to be a named test scenario in the plan (see below).
- **`TAX_WITHHELD` stays specialised.** It remains a separate advice line rather than folding into the generic `NOTE_CREDIT` bucket — it has its own tax-reporting semantics (withholding certificates, statutory rates) that a plain note does not. Query becomes `notes where direction='CREDIT' AND type='TAX_WITHHELD'`; the advice line stays `TAX_WITHHELD`.
- **`net_due_amount` formula updates** from:

  `carried_in + claims_paid − ctc_applied − advance_applied − tax_withheld − shortfall`

  to:

  `carried_in + claims_paid + note_debits − ctc_applied − advance_applied − tax_withheld − shortfall − note_credits`

- **Late-arriving notes.** A note whose `posted_at` falls in a *closed* period (before the last executed run) must not silently corrupt the historic advice — same rule as [[bug_public_flyway_history_load_bearing]]-style immutability. Options: (a) reject the post, forcing an adjustment in the current period; or (b) allow the post and generate a "back-period correction" line on the *next* advice with a pointer to the prior run. Option (b) mirrors `InvoiceSnapshotService`'s stamp-once design for invoice snapshots and is more forgiving of real-world clerical delay. This is a genuine fork for the plan.
- **Advice detail UI** at `/tenant/finance/advices/:id` (per `.claude/payments.md:589-598`) gains the two note buckets in its per-payee ledger view. Each line links back to the underlying note (`/tenant/finance/debit-notes/:id` or `/tenant/finance/credit-notes/:id`) so the payee (or the operator explaining it to them) can drill in.
- **Test scenarios that must be pinned** in the plan:
  1. A `direction=DEBIT, type=PROVIDER_OVERPAYMENT_RECOVERY` note posted mid-window appears on the provider's next advice as a `NOTE_CREDIT` line reducing `net_due_amount`.
  2. A `direction=CREDIT, type=GOODWILL` note posted mid-window appears as a `NOTE_DEBIT` line increasing `net_due_amount`.
  3. A `type=MEMO` note with no payee (the true bank-fee case from V016) does **not** appear on any advice — memo-only notes are excluded from the advice query.
  4. A note reversal (`reverses_note_id`) posted after the original's advice has generated appears on the current-period advice as an opposite-direction line, leaving the historic advice unchanged.
  5. `TAX_WITHHELD` notes render as `TAX_WITHHELD` advice lines, not `NOTE_CREDIT` — the two must not double-count.

### Blast radius (rough)

Everything below moves; nothing below is optional if we take Option 1:

- **finance-service Java**: `Adjustment` entity → `Note`; `AdjustmentRepository`, `AdjustmentQueryRepository`, `AdjustmentService`, `AdjustmentController` all rename; DTOs (`CreateAdjustmentRequest` → `CreateNoteRequest` — collides with the existing `NoteDtos.CreateNoteRequest`, so both DTOs collapse). Delete `DebitNote`, `CreditNote`, `DebitNoteRepository`, `CreditNoteRepository`, `NoteQueryRepository`, `NotesController`.
- **payment_advices join and generator**: any repository/query that reads `adjustments where type='TAX_WITHHELD'` (see `.claude/payments.md:380-395`) switches to `notes where direction='CREDIT' and type='TAX_WITHHELD'`. Additionally, `PaymentAdviceGenerator` gains two new line-type computations (`NOTE_DEBIT`, `NOTE_CREDIT`) sourced from all remaining payee-bound notes with `posted_at` in the advice window — and the `net_due_amount` formula updates to include both new buckets with opposite signs. The advice line-type table on the DB side (`payment_advice_lines` or equivalent) gains the two new line-type enum values.
- **Kafka event schemas**: any adjustment-related audit / domain events (`entityType='Adjustment'`) migrate to `entityType='Note'`. Consumer side does not read on the entity-type string for behaviour, but downstream logs / audit search do.
- **Tenant migration**: `Vxxx__rename_adjustments_to_notes.sql` — rename `adjustments` → `notes`, add `direction` (backfilled from current `type` sign convention), migrate the two standalone tables' rows in as memos, drop the two tables.
- **Angular**: `finance.service.ts` (`createDebitNote` / `createCreditNote` change target endpoints), `NotesListComponent` swap for the Adjustments-style component, permission constants renamed (`finance:post_adjustments` → `finance:post_notes` or split into `read/write/approve`). Advice detail component at `/tenant/finance/advices/:id` renders the two new note-line buckets and links each line back to its underlying note.
- **Docs**: `.claude/architecture.md:85-93`, `.claude/portals.md:148-152,511-517`, `.claude/payments.md:380-395` all update naming and permission columns.
- **Tests**: unit and integration tests for AdjustmentController / AdjustmentService / PaymentAdviceGenerator, plus any Playwright e2e that references adjustments (though [[project_e2e_gaps_billing]] notes e2e is thin here already).

## Grilling decisions (2026-08-10)

A grilling pass before `create-plan` resolved 12 remaining decision forks. Each is numbered `G#` to distinguish from the Open Questions numbering above.

### G1 — Direction backfill for existing `adjustment_type` values

`IN_PAYMENT` + `NON_CASH_IN` → `direction='CREDIT'`. `PAYOUT` + `NON_CASH_OUT` + `TAX_WITHHELD` → `direction='DEBIT'`.

**Why**: Preserves the existing TAX_WITHHELD-as-advice-CREDIT invariant at `PaymentAdviceService.java:346-347`. "In" from the fund's perspective = we owe payee more = advice DEBIT = note CREDIT.

### G2 — Late-arriving notes

Accept the post at its real `posted_at`. The next `PaymentAdvice` picks up any note whose `posted_at ≤ this_run.executed_at` and which was not already stamped onto a prior advice, as a `NOTE_DEBIT`/`NOTE_CREDIT` line with a new `back_period_run_id` nullable column pointing to the run whose advice would have carried it otherwise. Historic advice never mutates.

**Why**: Mirrors `InvoiceSnapshotService`'s stamp-once design and matches the compensating-entry style already used for advances/CTC. Keeps audit trail (PDFs, sent emails, reconciliations) intact.

### G3 — Reversal semantics

Drop `cancelled` from the status set. Add `reverses_note_id UUID` FK. Adopt the house compensating-entry pattern used by `AdvancePayment` and `CtcPayment`. New status set: `{pending, approved, applied, reversed}`. Add a `type` column with values `{ORIGINAL, REVERSAL}` and a CHECK binding `type='REVERSAL' ⟺ reverses_note_id IS NOT NULL`.

**Why**: Keeps the finance ledger append-only. Preserves reversal history. Symmetric across the finance ledger.

### G4 — Fate of the V016 `debit_notes` / `credit_notes` tables

Drop both tables. Migrate their rows into `notes` as `note_type='MEMO'` with `provider_id=NULL, member_id=NULL, direction=` DEBIT for debit_notes rows / CREDIT for credit_notes rows, `status='applied'`, `type='ORIGINAL'`. Relax the existing `V016:84` CHECK to `(provider_id IS NOT NULL OR member_id IS NOT NULL OR note_type='MEMO')`.

**Why**: One ledger, one screen. Resolves the naming collision the whole plan exists to fix.

### G5 — Kafka audit-event `entityType` transition

Cut over in one deploy — finance-service starts emitting `entityType='Note'` immediately. A one-time SQL backfill on the audit-events table updates historic `entityType='Adjustment'` (and `'DebitNote'`, `'CreditNote'`) rows to `'Note'`, preserving the original in a new `original_entity_type` metadata column. No dual-emit window.

**Why**: Audit events are read-mostly; no consumer joins on entityType in a hot code path. One-shot backfill avoids permanent naming drift.

### G6 — Permission granularity

Split into three permissions: `finance.notes:read`, `finance.notes:write`, `finance.notes:approve`. Keep the existing `finance:post_adjustments` in the catalogue for one release as a **compatibility mapping** — anyone holding it automatically gets all three new permissions via the permission resolver. Second release removes `finance:post_adjustments` entirely.

**Why**: Matches `.claude/portals.md:511-517`. Resolves the docs-vs-code drift. Enables clerk-vs-HoD separation of duties. Compat window means no tenant role reassignments on cutover day.

### G7 — Angular UI shape

Three sidebar entries: Debit Notes (`/tenant/finance/debit-notes`, filter `direction='DEBIT'`), Credit Notes (`/tenant/finance/credit-notes`, filter `direction='CREDIT'`), All Notes (`/tenant/finance/notes`, no direction filter). All three point at a renamed `NotesListComponent` (repurposed from the current `AdjustmentsListComponent`, which already has all the columns and filters we need). The current `/tenant/finance/adjustments` and its subroutes become 301 redirects for one release, then delete. The current memo-only `NotesListComponent` is deleted (replaced by the ledger version).

**Why**: Preserves the direction-first mental model finance clerks already have from paper notes. Adds a combined view for cross-direction reporting. Matches `.claude/portals.md:151-152`.

### G8 — MEMBER-payee advice inclusion

`NOTE_DEBIT` / `NOTE_CREDIT` lines apply to both PROVIDER and MEMBER `PaymentAdvice`s, sourced per-payee. A `direction=DEBIT, note_type=GOODWILL, member_id=X` note appears on member X's next advice reducing their net due; a `direction=CREDIT, note_type=PREMIUM_REFUND, member_id=X` increases it.

**Why**: Symmetric with how CTC_APPLIED (MEMBER-only) and ADVANCE_APPLIED (PROVIDER-only) already source per-payee-type. Delivers the plan's own promise ("every note posted against a payee must appear on that payee's PaymentAdvice") fully in phase 1.

### G9 — `note_type` enum shape

Hard swap the enum. New CHECK: `{TAX_WITHHELD, WRITE_OFF, GOODWILL, ENDORSEMENT_PREMIUM, PREMIUM_REFUND, PROVIDER_OVERPAYMENT_RECOVERY, MEMO}`. Rename column `adjustment_type` → `note_type`.

**Why**: Cleaner domain vocabulary; aligns with the naming-convention argument that drove the whole plan.

### G9a — Old→new `note_type` mapping (settled by fact, not preference)

Grep across `services/java/` shows the 4 non-TAX values (`IN_PAYMENT`, `PAYOUT`, `NON_CASH_IN`, `NON_CASH_OUT`) have **zero production callers** — only mention is the CHECK constraint at `V016:73`. Only `TAX_WITHHELD` is actively used (`PaymentAdviceService.java:328,344`, `AdjustmentServiceTest.java:238`). Existing controller/service tests set `adjustmentType="credit"` (`AdjustmentControllerTest.java:65,86`, `AdjustmentServiceTest.java:100,287`), a value that isn't even in the current CHECK — they must hit a mocked repo. They get rewritten in the rename phase regardless.

**Consequence**: Backfill is trivial (`TAX_WITHHELD → TAX_WITHHELD`). Any stray non-TAX row causes the ALTER CHECK to fail loudly on migration, which is the correct behaviour for a greenfield build.

### G10 — Contributions-side arrears

Out of scope for this plan. Contributions arrears stay as `SCHEME_UPGRADE_ARREARS` / `SCHEME_DOWNGRADE_REBATE` transactions on the contributions-service ledger. The `notes` table stays finance-service-side only.

**Why**: Bounds phase 1. Doesn't introduce cross-service ledger duplication. Any future "arrears also appear on the Notes list" becomes a follow-up ticket that consumes a Kafka event and mirrors a Note.

### G11 — Payee dimension

Preserve provider XOR member (plus MEMO payee-less per G4). No `group_id` or `reinsurer_id` columns in phase 1.

**Why**: Matches every other finance-service ledger table (payments, payment_advices, member_payables). Group / reinsurer widening becomes a follow-up when those flows actually need to be built.

### G12 — PDF/email templates (settled by fact, not preference)

Grep across `services/go/`, `services/python/`, and `clients/flutter/` shows **zero** hardcoded references to advice line-type names (`CARRY_FORWARD`, `TAX_WITHHELD`, `CTC_APPLIED`, `ADVANCE_APPLIED`). PDF and email consumers iterate the payload array and render whatever line types arrive. Adding `NOTE_DEBIT` / `NOTE_CREDIT` requires no changes outside Java.

**Consequence**: No Go/Python/Flutter phase in this plan.

### G13 — Which note statuses feed the advice

Only `status='applied'`. `pending`, `approved`, and `reversed` are excluded. This is also a **latent-bug fix** riding in with the rename — the existing TAX_WITHHELD query at `PaymentAdviceService.java:326-333` filters neither status nor cancellation, so it currently counts pending/approved/cancelled rows too.

**Query implication**: `notes WHERE status='applied' AND note_type NOT IN ('MEMO','TAX_WITHHELD') AND posted_at ∈ window AND currency_code = :advice_currency AND ((provider_id=:payeeId AND :payeeType='PROVIDER') OR (member_id=:payeeId AND :payeeType='MEMBER'))`. The specialised TAX_WITHHELD sub-query gets the same `status='applied'` guard.

### G14 — Multi-currency handling on advices

Per-currency bucketing. A USD note only feeds a USD advice; a ZWL note only feeds a ZWL advice. No FX conversion in the advice generator. Preserves today's behaviour (`PaymentAdviceService.java:329-330` exact currency match) and respects Critical Rule #1 the safest way (never mix currencies in arithmetic). `PaymentRun` is per-currency anyway (`.claude/payments.md:309`), so a payee with USD + ZWL notes gets two separate advices, one per run per currency.

### G15 — Reversal impact on the advice

Symmetric append-only. A REVERSAL row is just another note; it appears on the payee's advice as an opposite-direction line via the normal advice-lines query, dated at its own `posted_at`. The original's advice line stays as-is on its historic advice. Full audit trail — the payee reader sees both the posting and its undo.

Matches CTC reversal precedent (`.claude/payments.md:463`). If original + reversal both fall in the same advice window, both appear (a NOTE_DEBIT of $100 immediately followed by NOTE_CREDIT of $100 — visible and correct). Late-arriving mechanism from G2 handles the timing edge case.

### G16 — `note_number` generation

Direction-prefixed random with retry-on-collision (preserving today's `AdjustmentService.java:181-184` retry logic):

- `direction='DEBIT'` → `DN-XXXXXX`
- `direction='CREDIT'` → `CN-XXXXXX`
- `note_type='MEMO'` → `MEMO-XXXXXX`

Migration renumbers existing `ADJ-XXXXXX` rows per direction (after G1's backfill sets each row's direction). No collisions because `DN-`/`CN-`/`MEMO-` prefixes are all new. Prefix gives at-a-glance identification in emails, spreadsheets, and phone calls (a clerk saying "credit note CN-482913" is unambiguous).

## Handoff

This decision is now captured in the research doc. To turn it into work, clear context and run:

```
create-plan thoughts/shared/research/2026-08-10-debit-and-credit-notes-in-insurance.md \
            "implement Option 1 per the Grilling decisions section (G1-G12) already in the doc; no further scope debate needed — the decision forks are settled"
```

Non-trivial migration — the rename touches the tenant schema (Flyway), the Kafka audit-event stream, the payment-advice generator, and the Angular finance surface. Worth staging as multiple phases in the plan.
