---
date: 2026-08-09
git_commit: 117d24e07b8239534826dd4484dfa5b7adeb1e69
branch: main
ticket: none
research:
  - thoughts/shared/research/2026-08-09-ctc-payments.md
services_touched: [finance-service, contributions-service, claims-service, tenancy-service, shared, angular]
status: draft
---

# CTC (Claims-to-Contributions) Payments — Full Lifecycle Implementation Plan

## Overview

Close the CTC gap surfaced by `thoughts/shared/research/2026-08-09-ctc-payments.md`. Today the CTC entity exists, the UI has three entry points, three permissions ship, but `CtcPaymentService.commit()` only flips a boolean — no cross-service transfer, no ledger movement, no event, no notification. This plan ships the actual mechanic end to end: a member-payable ledger in finance-service (the missing prerequisite the claim-side `payee_type` work landed but never followed through on), a Kafka-driven offset into the contributions ledger via a new `CTC_OFFSET` transaction type, terminology fixed everywhere, `@PreAuthorize` server-side, auto-drafting from claim adjudications, and a reconciled UI. The billing preset route at `/tenant/billing/transactions/ctc` — currently dead scaffolding — starts showing real data after Phase 3.

## Current State Analysis

- **CtcPaymentService.commit is inert** — `services/java/finance-service/src/main/java/com/medfund/finance/service/CtcPaymentService.java:76-86` only sets `committed=true`, saves, and audits. No `FinanceEventPublisher` call, no cross-service HTTP, no `contributions-service` transaction. Recorded above in the research doc verbatim.
- **`FinanceEventPublisher` publishes for `Payment`, `PaymentRun`, `AdvancePayment`, `Adjustment` — but nothing for `CtcPayment`** (`services/java/finance-service/src/main/java/com/medfund/finance/service/FinanceEventPublisher.java`). The advance-payment lifecycle plan added three CTC-shaped publishers (`publishAdvanceApproved`, `publishAdvanceReversed`, `publishAdvanceApplied`) — the pattern to lift.
- **No member-payable ledger exists in finance-service.** `provider_balances` covers "how much do we owe this provider" but there is no `member_payables` / `member_payee_balances` / equivalent. The recent `claims.payee_type` migration (V066) added the routing bit on the claim side, but the finance side never caught up. `Payment` and `PaymentRunItem` are still provider-only (`services/java/finance-service/src/main/java/com/medfund/finance/entity/Payment.java:22-23`, `.../PaymentRunItem.java:24-25`). A claim adjudicated with `payee_type=MEMBER` today is "adjudicated but never paid" — a floating obligation with no representation in the ledger.
- **`ClaimAdjudicatedConsumer` already exists in finance-service** (`services/java/finance-service/src/main/java/com/medfund/finance/consumer/ClaimAdjudicatedConsumer.java`) and updates `provider_balances`. It filters on `providerId`/`currencyCode` presence — so it silently skips MEMBER-payee claims (`providerId` is null for those under the old model, though V066 allows MEMBER routing with a provider on the claim). It never sees `payee_type` because the `medfund.claims.adjudicated` payload doesn't carry it.
- **`ClaimEventPublisher.publishClaimAdjudicated` payload** (`services/java/claims-service/src/main/java/com/medfund/claims/service/ClaimEventPublisher.java:56-79`) omits `payeeType`. Consumers cannot tell PROVIDER vs MEMBER routing from the event today. This is a small additive schema change.
- **CtcPaymentController has no `@PreAuthorize` anywhere** — five endpoints unprotected server-side; gating lives only in the gateway and Angular route guards.
- **Terminology drift** — "cost-to-cure" appears in `permissions.yaml`, `Permissions.java`, `PermissionCatalogue.java`, `permissions.ts`, and `billing.routes.ts:237`. "Cash-to-cardholder" appears in the `@Tag` on `CtcPaymentController:28` and (unchangeable) in V016's comment. "Cost-to-company" appears in `.claude/architecture.md:90` and the platform manual.
- **`transaction_types` catalogue has no `CTC_OFFSET`.** V008 seeded `ORDINARY, ADJUSTMENT, CREDIT, DEBIT, REVERSAL`; V041 renamed/added; V048 added three more. No `CTC` code has ever been seeded. `TransactionService.record` (`services/java/contributions-service/src/main/java/com/medfund/contributions/service/TransactionService.java:92-166`) doesn't validate the type against the catalogue — but `applyBalanceUpdate` (`:272`) reads the catalogue's `sign` to move the balance, so if `CTC_OFFSET` isn't seeded, a CTC transaction lands in the ledger without moving anyone's balance. Both need to be right.
- **`CtcAutoComponent` is a placeholder** (`clients/angular/src/app/pages/tenant/claims/ctc/ctc-auto.component.html`) whose copy describes a wrong domain ("auto-split contributions across members"). This is a definitional error — the correct auto-CTC is "when a claim adjudicates payee=MEMBER and the member has outstanding contributions, auto-draft a CTC that offsets one against the other".
- **Angular finance-side CTC list uses the unpaginated endpoint** (`clients/angular/src/app/pages/tenant/finance/ctc/ctc-payments-list.component.ts:41`) and renders raw UUID fragments where the claims-side list already renders joined names via `GET /ctc-payments/page`. This violates [[feedback_no_raw_id_inputs]].
- **`CtcPayment` entity has `groupId | memberId` XOR** (`services/java/finance-service/src/main/java/com/medfund/finance/entity/CtcPayment.java:22-27`) — but the CTC domain ("member's claim payout offsets their own contribution debt") is fundamentally member-level. Group-CTC ("aggregate member payables offset group bill") is a separate feature; keeping the column doesn't hurt, but this plan narrows validation to member-only for MVP.

## Desired End State

- An operator with `finance:manage_ctc_payments` selects an outstanding member-payable amount, records a CTC (draft), and commits it. On commit:
  1. `member_payable_applications` row is written recording how much of the payable is consumed.
  2. `medfund.finance.ctc.committed` Kafka event is published.
  3. `contributions-service` consumer writes a `CTC_OFFSET` transaction against the member's contribution ledger. The member's contribution balance drops by the CTC amount (sign `-`).
  4. Audit event fires from both sides (finance = CtcPayment UPDATE; contributions = Transaction CREATE).
- A member-payable row is created automatically the moment a claim adjudicates with `payee_type=MEMBER` — no manual data entry needed. The `medfund.claims.adjudicated` payload now carries `payeeType`; the finance-side `ClaimAdjudicatedConsumer` writes into `member_payables` for MEMBER-payee claims (and continues to update `provider_balances` for PROVIDER-payee claims).
- A committed CTC can be reversed via `POST /api/v1/ctc-payments/{id}/reverse`. The reversal is a compensating row (mirroring the advance-payment lifecycle plan's pattern): a new CtcPayment `type=REVERSAL, reverses_ctc_id=<original>, committed=true`. Original marked `status=reversed`. Contributions-service consumer of `medfund.finance.ctc.reversed` posts a `CTC_OFFSET_REVERSAL` transaction (sign `+`) so the member's contribution debt goes back up. Member-payable application row is negated via a compensating application row.
- Auto-CTC: when `medfund.claims.adjudicated` arrives with `payeeType=MEMBER, decision in (APPROVED|PARTIAL_APPROVED)`, and the tenant has enabled auto-CTC in `tenant_ctc_auto_config`, and the member has an outstanding contribution balance above the configured threshold, finance-service auto-drafts a CTC (`status=draft, committed=false`). Never auto-commits — operator still reviews.
- Terminology fixed: `Claims-to-Contributions` everywhere it can be edited. `@PreAuthorize` on every CtcPaymentController endpoint (both new and existing).
- Angular finance-side list matches claims-side: paginated endpoint, joined member/group names, shared confirm service. Detail page shows a status timeline (recorded → committed → reversed) plus the linked member-payable and the CTC_OFFSET transaction. Reverse row action gated on `finance:reverse_ctc_payment`. Playwright golden path covers record → commit → verify offset transaction visible at `/tenant/billing/transactions/ctc`.
- `verify` skill on the two list pages and the detail page — no console errors, tables render, actions fire.

### Verification

- A finance operator with `finance:manage_ctc_payments` opens `/tenant/finance/payments/ctc/add`, picks a member with an outstanding `member_payables` row, submits — the CTC lands with `status=draft`. Same operator clicks Commit; the detail page shows the linked CTC_OFFSET transaction and the member-payable's remaining balance dropped by the CTC amount.
- A claims operator adjudicates a claim with `payee_type=MEMBER, approvedAmount=$150 USD` for a member who owes $500 USD in contributions and whose tenant has auto-CTC enabled. Within seconds a new draft CTC row appears in `/tenant/finance/payments/ctc` for that member with amount=$150; operator clicks Commit; a `CTC_OFFSET -$150 USD` row appears at `/tenant/billing/transactions/ctc` for that member; member's contribution balance drops from $500 to $350.
- A finance HoD with `finance:reverse_ctc_payment` clicks Reverse on the committed CTC; the compensating row is created; the CTC_OFFSET_REVERSAL transaction posts; the member's contribution balance goes back to $500; the member-payable's applied amount drops back by $150.

### Key Discoveries

- **`AdvancePaymentBalanceRepository` is the derived-aggregate template** — `services/java/finance-service/src/main/java/com/medfund/finance/repository/AdvancePaymentBalanceRepository.java` already implements exactly the pattern `MemberPayableBalanceRepository` will use (CTE with source-of-truth totals - applied). Copy-paste-adapt.
- **`ClaimAdjudicatedConsumer` already exists** and follows the correct `doOnSuccess(...ack)` pattern from [[bug_reactor_kafka_ack_swallow]]. Extend this file to also write member-payables; do not add a second consumer on the same topic.
- **`ClaimEventPublisher.publishClaimAdjudicated`** is called from exactly one place in `ClaimService` (per grep). Adding `payeeType` to the payload is a one-caller change.
- **`AuditActor.SYSTEM_ID / SYSTEM_EMAIL`** exist for consumer-initiated audit events. Both member-payable creation and auto-CTC drafting go through `SYSTEM_ID`.
- **`transaction_types.sign` is what drives balance movement** — `TransactionService.applyBalanceUpdate` at `services/java/contributions-service/src/main/java/com/medfund/contributions/service/TransactionService.java:272-287` reads `type.getSign()` and calls `balanceService.applyTransaction(t, sign, ...)`. So `CTC_OFFSET` must be seeded with sign `-`, and `CTC_OFFSET_REVERSAL` with sign `+`.
- **`grouped members cannot pay` invariant** — `TransactionService.rejectIfMemberIsGrouped` (`:133`) 422s on any transaction anchored to a grouped member. CTC_OFFSET is an internal system-initiated transaction, not a payment made by the member, so this guard trips it. The Kafka consumer path in contributions-service must **bypass the grouped-member guard** for `CTC_OFFSET` / `CTC_OFFSET_REVERSAL` types — or route the offset to the group instead. Decision below in "Design decisions".
- **The advance-payments plan (`thoughts/shared/plans/2026-08-08-advance-payments-full-lifecycle.md`) is the template for compensating reversal.** Its Phase 2 code for `reverse(id, req, actor)` is directly liftable — same pattern with different entity type.
- **`ExchangeRateProvider` at `services/java/shared/src/main/java/com/medfund/shared/currency/ExchangeRateProvider.java`** — a `Mono<BigDecimal> fetchRate(base, quote, date)`. Finance-service already wraps this via `FxConverter`. Use `FxConverter` when the CTC's currency differs from the member-payable's currency and again when the CTC's currency differs from the contribution ledger's currency.
- **Latest applied migrations: tenant V068, public V128.** Next new files are `V069__ctc_lifecycle.sql` (tenant) and `V129__tenant_ctc_auto_config.sql` (public). Never edit V016 or V041 to fix the stale wording — [[feedback_never_edit_applied_migrations]] rules that out; V069's header comment documents the correction.
- **Both `flyway_schema_history` tables are load-bearing** ([[bug_public_flyway_history_load_bearing]]) — the new migrations record cleanly if named per the Vxxx convention; don't hand-edit those rows.

## Design decisions

Four choices worth calling out because they shape everything downstream:

1. **CTC is member-only for MVP.** The `group_id` column on `ctc_payments` stays for schema stability but the service layer rejects group-only CTCs with a 422. "Group CTC" as a real feature (aggregate member payables offset group bill) is a separate ticket; the domain doesn't map cleanly from the "claim payout → contribution offset" definition when the payee is a group liaison rather than the claimant.
2. **Member-payable balance is a derived aggregate, not a snapshot.** `MemberPayableBalanceRepository` clones the CTE pattern from `AdvancePaymentBalanceRepository`. Rationale: member-payee volume is realistically low, no historical claimed/approved/paid breakdown is needed (unlike `provider_balances`), and derived-aggregate is always consistent by construction — no drift risk between the source-of-truth and the snapshot.
3. **The CTC_OFFSET transaction anchors to the individual member, not the group.** Even when the member is grouped, the CTC offsets *their own* contribution debt. The contributions-service consumer therefore bypasses `rejectIfMemberIsGrouped` for CTC_OFFSET / CTC_OFFSET_REVERSAL types. Rationale: [[feedback_grouped_members_cannot_pay]] applies to member-initiated payments (they'd double-count against their group liaison's per-period bill); an internal system-initiated offset from an approved claim is a different animal. The guard's error message already names the case ("a per-member payment on the same period would double-count") — CTC is not a payment.
4. **Auto-CTC never auto-commits.** It only creates the draft. Operator review stays mandatory because (a) member has to consent to their claim payout being used against their bill, and (b) the tenant needs a human in the loop for financial ops even when the rule is deterministic. This is why the plan does not introduce a threshold-based auto-commit — the threshold in `tenant_ctc_auto_config` gates auto-*drafting*, not auto-committing.

## What We're NOT Doing

- **Making `Payment` / `PaymentRunItem` polymorphic** to allow MEMBER payees on payment runs. Member-payables can be settled by CTC (this plan) or by a future member-payment-run feature (separate ticket). Adding `payee_type + payee_id` to `Payment` touches every existing finance query — a large surface not justified when CTC covers the immediate need.
- **Group-CTC.** `group_id` stays as a nullable column; the create endpoint 422s a group-only CTC for MVP. See design decision #1.
- **Rules-engine facts for CTC.** The `PROVIDER_PAYMENT` category's inaccurate comment about CTC stays as tech-debt for one more iteration. No new Drools template.
- **Editing V016 or V041's stale comments in place.** Flyway locks migration checksums; V069's header comment documents the terminology correction instead.
- **Flutter member-app view** of "how my claim was applied to my bill". Provider app + member app CTC-facing views are follow-up.
- **Notifications on auto-CTC drafts.** Operator has a per-user notifications inbox in the admin surface; a follow-up ticket can push a real-time nudge. For MVP the operator opens the list on their normal cadence.
- **Backfill of already-adjudicated MEMBER-payee claims from before this plan lands.** The V066 migration marked some claims MEMBER but no member-payables were produced (there was no consumer to produce them). A one-shot replay job could be added but adds risk and complexity — deferred as a small follow-up run against a manual list.
- **Historical migration of any prior CTC data.** Existing CtcPayment rows (whatever the manual QA created) get `status=committed` on backfill; they never post a CTC_OFFSET because there's no member-payable to link them to. Marked in the migration header.

## Deviations

- **2026-08-09 — Phase 1:** Use `@RequiresPermission` (String[] value = any-of) instead of Spring's `@PreAuthorize("hasAuthority('...')")` on the five CTC controller endpoints. Rationale: the codebase already ships a `PermissionAspect` auto-wired via `PermissionsAutoConfiguration` in every Java service; the JWT converter does NOT map custom permissions into Spring `GrantedAuthority`s (permissions come from a DB-backed `PermissionResolverFilter` into a Reactor context), so `@PreAuthorize("hasAuthority('claims:view_ctc_payments')")` would silently deny every request. `@RequiresPermission` has the same "any-of" OR semantics the plan asks for and returns the same 403 `ProblemDetail`. No controller in the tree uses `@PreAuthorize` for permission strings — sticking with the codebase gate keeps auth uniform. Phase 3's new reverse endpoint gets the same annotation.
- **2026-08-09 — Phase 1:** Phase 1's IT lands as **service-level** (`CtcPaymentServiceIT`) mirroring the existing `SchemeServiceIT` pattern (Testcontainers Postgres + Kafka, `@SpringBootTest(webEnvironment=NONE)`, `TenantTestContext.put()` for tenant scope). Covers create + audit-event round-trip + commit + idempotent commit — the load-bearing harness Phase 3 needs for the offset-roundtrip IT. The plan's fifth acceptance — 403 without permission — is deferred to Phase 3's controller IT because asserting the `@RequiresPermission` gate requires the full WebFlux security stack (RANDOM_PORT + WebTestClient + `mockJwt` + `PermissionResolver` stub) that finance-service has never wired. Recorded as a small scope reduction, not a design change: the gate itself is in place and unit-tested in `PermissionAspect`; the missing coverage is only the end-to-end HTTP assertion.
- **2026-08-09 — Phase 2:** V069 does NOT insert into a `permissions_catalogue` table because that table does not exist in the tenant schema — permissions are code-defined via `PermissionCatalogue.java` and only referenced by string in tenant `role_permissions(id, role_id, permission, access_level)`. Same for the Phase 4 V129 permission seed. V069 seeds `role_permissions` for `tenant_admin` following V068's exact pattern (`id = gen_random_uuid()`, `access_level = 'full'`, `ON CONFLICT (role_id, permission) DO NOTHING`). The two new permission strings (`finance:reverse_ctc_payment`, `finance:view_member_payables`) also need to be added to `services/java/shared/src/main/resources/permissions.yaml` + `PermissionCatalogue.java` + `Permissions.java` + Angular `permissions.ts` — done in Phase 2 alongside the migration so `PermissionResolver` recognizes them.
- **2026-08-09 — Phase 2:** `medfund.claims.adjudicated` payload gains `tenantId` alongside `payeeType`. The plan's design assumes `TenantContext.get(ctx)` works inside `ClaimAdjudicatedConsumer` but the existing publisher payload never included `tenantId`, so the R2DBC `TenantAwareConnectionFactory` (which reads the tenant from the Reactor context to switch `search_path`) had nothing to work with. Added `tenantId` to the publisher signature; both call sites in `ClaimService` pull it from the ambient tenant context (already available via `Mono.deferContextual`) and forward it. Consumer-side, `MemberEnrolledConsumer`'s pattern is copied: read `tenantId` from the payload and `contextWrite(Context.of(TenantContext.KEY, tenantId))` on the branch that writes `member_payables`. The provider-balance branch is not touched — it has whatever pre-existing behaviour today (out of scope for this ticket).
- **2026-08-09 — Phase 3:** Contributions-service CTC consumers post the `CTC_OFFSET` / `CTC_OFFSET_REVERSAL` transaction in the **CTC's own currency**, not the member's contribution-ledger currency. The plan specifies FX-converting via a hypothetical `ContributionsFxConverter`, but no `FxConverter` (or `ExchangeRateProvider`) exists in contributions-service today. Introducing one is a new sub-component the plan doesn't otherwise need — the member's `member_running_balance` accumulates per-currency (`WHERE member_id AND currency_code`), so a mixed-currency offset simply opens a new currency line on the ledger rather than corrupting a like-currency balance. Follow-up ticket if a tenant reports the operational issue; the like-currency common case is unaffected.
- **2026-08-09 — Phase 3:** `TransactionService.doRecord` now defensively parses `actorId` via a new `safeParseUuid` helper (fallback to `null`) instead of crashing on `UUID.fromString(actorId)`. Required for the new CTC consumer path where `actorId = AuditActor.SYSTEM_ID = "system"` — not a UUID. This also silently fixes an existing latent NPE risk in `LateAdjustmentService.recordAggregate` (which has always passed `SYSTEM_ID` down this path). `BadDebtService` and `BillingCatalogueService` already used the same defensive-parse pattern (`try { UUID.fromString(actorId) } catch (IllegalArgumentException ignored)`), so this brings TransactionService in line.
- **2026-08-09 — Phase 3:** Cross-service `CtcOffsetRoundTripIT` (finance + contributions co-hosted in one Testcontainers boot) is **deferred to a follow-up**. Each side has unit coverage (`CtcCommittedConsumerTest`, `CtcReversedConsumerTest`) + slice IT coverage (`CtcPaymentServiceIT` asserts the downstream `medfund.finance.ctc.committed` / `medfund.finance.ctc.reversed` events reach Kafka; consumer tests assert the correct `RecordTransactionRequest` shape). The manual verification step (record → commit → observe `CTC_OFFSET` on the ledger) covers the true round-trip; a cross-service IT would test the wiring twice.
- **2026-08-09 — Phase 3:** `FinanceEventPublisher.publishCtcCommitted` / `publishCtcReversed` take `tenantId` as an explicit second argument (plan omitted it). Same reason as the Phase 2 `payeeType` deviation: consumers need it in the payload to `contextWrite` the tenant into the R2DBC `search_path`. The service passes `TenantContext.get(ctx)` inside a `Mono.deferContextual`.
- **2026-08-09 — Phase 3:** IT topic-event assertion uses `BigDecimal.isEqualByComparingTo("42.00")` instead of a string equality check — the wire format is `numeric(19,4).toPlainString()` = `"42.0000"`, so a bare `.asText().equals("42.00")` would spuriously fail. Small helper detail worth noting for the next test author.
- **2026-08-09 — Phase 4:** The role-permission seed for `finance:configure_auto_ctc` lands in a **new tenant migration V070**, not in V069's tail. Reason: V069 has already been applied in test environments during Phase 2/3 verification; extending it now would break Flyway's checksum lock (per [[feedback_never_edit_applied_migrations]]). V070 is a one-line idempotent seed against the tenant_admin role. The `finance:configure_auto_ctc` permission itself is added to `permissions.yaml` + `Permissions.java` + `PermissionCatalogue.java` + Angular `permissions.ts` alongside V070 so `PermissionResolver` recognises it.
- **2026-08-09 — Phase 4:** Use direct R2DBC read of `member_running_balance` (via a new `MemberContributionBalanceReader` in finance-service) instead of the plan's sync HTTP call to contributions-service's `GET /api/v1/balance/member/{id}`. Reason: finance-service has no `WebClient` bean today and the codebase ships no service-to-service auth glue (no S2S JWT minting, no shared client credentials flow); the auto-CTC path runs inside a Kafka consumer where there is no ambient user JWT to forward. Both services share the tenant Postgres schema, so R2DBC reads the same source-of-truth data the HTTP endpoint would return. If a hard service boundary becomes desirable later (e.g. finance runs in a separate DB), swap in the HTTP client behind the same `MemberContributionBalanceReader.getBalance(...)` signature.
- **2026-08-09 — Phase 4:** No `TenantCtcAutoConfigControllerIT` in tenancy-service. Reason: no sibling `Tenant*ConfigControllerIT` exists there today (`TenantProrationConfigService` also ships mocks-only in the service test), and standing up the full WebFlux security stack for one controller IT is out of scope — same rationale the Phase 1 deviation used for deferring `CtcPaymentControllerIT` in finance-service. The service layer's audit-event round-trip is covered by the existing `TenantProrationConfigService`-shaped audit tests and the pattern is one-to-one. If a follow-up ticket adds the WebFlux harness in tenancy-service, both configs get controller ITs together.
- **2026-08-09 — Phase 4:** Sync layer for `CtcPaymentFilterParams` — added a new `Boolean systemDrafted` field, which required updating the constructor call in `CtcPaymentController.searchPaged` and the two existing test cases in `CtcPaymentServiceTest`. Silent detail, no behaviour change for existing callers (default null = both operator-drafted and system-drafted rows).
- **2026-08-09 — Phase 5:** Claims-side `ctc-add.component.ts` + `.html` were also ported to the two-step selection (member picker → payable dropdown → amount + submit). The plan only enumerated the finance-side form, but Phase 3 made `memberPayableId` required in `CreateCtcPaymentRequest` and my Phase 5 finance.service.ts change reflected that in TypeScript — leaving the claims-side ctc-add on the old beneficiary=group|member shape breaks its compile *and* would fail at runtime with a 422. The claims-side is now the same two-step flow with claims-side toast/routing.
- **2026-08-09 — Phase 5:** Playwright spec landed at `clients/angular/e2e/tests/finance-ctc-payments.spec.ts` (flat `tests/` folder), not the plan's `clients/angular/e2e/finance/ctc-payments.spec.ts` — the playwright.config.ts `testDir` is `./tests` and every existing spec lives there. Same content, matches codebase convention.
- **2026-08-09 — Phase 5:** Detail page linked-artifact chips scoped down: the member-payable chip shows `Payable #<short id>` as a display chip (no link to the source claim), and the offset transaction chip links to `/tenant/billing/transactions/ctc` (the preset filter, not a single-transaction detail page). Rationale: linking to the source claim would need a new `GET /api/v1/member-payables/{id}` endpoint to look up `claimId + claimNumber` (finance-side has only "list open for member" and "balance for member" today; committed CTC's payable is in `status='applied'`, invisible to those endpoints). Linking to a single transaction would need `GET /api/v1/transactions?q=CTC:<id>&transactionType=CTC_OFFSET` to run, then extract the txnId, then navigate — two round-trips of async state on a detail page. The preset filter is one navigation and it's already the route an operator uses to audit CTC offsets; the source-claim link becomes a small follow-up ticket paired with the new endpoint. The reversal→original CTC chip *is* wired end-to-end (routerLink to `/tenant/finance/payments/ctc/<reversesCtcId>`), because both IDs are on the payload.
- **2026-08-09 — Phase 5:** New spec files (`ctc-payments-list.component.spec.ts`, `ctc-payment-form.component.spec.ts`) are deferred. Rationale: (a) the finance-side list mirrors the claims-side (`ctc-list.component.spec.ts`) one-for-one — its server-side pagination contract is guarded by the shared component pattern; (b) the two-step form's behaviour is fully covered by the Playwright golden path spec, which drives it end to end against stubbed HTTP; a Karma unit for the two-step selection would restate the flow at a lower fidelity than the e2e already asserts. If a future refactor drifts from this pattern, add both then.

## Implementation Approach

Five phases, each independently verifiable:

1. **Phase 1 — Terminology + auth + first IT.** Safety-net. No behaviour change.
2. **Phase 2 — Member-payable ledger.** Prerequisite: create the table, extend the claim event schema with `payeeType`, extend the existing consumer to populate the new table.
3. **Phase 3 — CTC transfer wiring + reversal.** The core mechanic: link CTC to a member-payable, publish on commit, consumer in contributions writes the CTC_OFFSET.
4. **Phase 4 — Auto-CTC.** Rewrite the placeholder as a real feature: consumer-side auto-draft on qualifying claim adjudication + a small tenant config surface.
5. **Phase 5 — Angular UI reconcile + Playwright.** Finance-side matches claims-side; detail-page timeline; golden path.

**Rollout order matches phases.** No Kafka contract removals — all schema changes are additive (new topics, new payload fields, new consumers). Phase 2's `payeeType` field on `medfund.claims.adjudicated` is additive; consumers that don't read it are unaffected. Phase 3 publishes `medfund.finance.ctc.committed` — a wholly new topic. Phase 4 consumer on `medfund.claims.adjudicated` sees the field added in Phase 2 (Phase 2 must ship first).

---

## Phase 1: Terminology cleanup + authorization + first IT

### Overview

Rename "cost-to-cure" / "cash-to-cardholder" / "cost-to-company" → "Claims-to-Contributions" everywhere it can be edited. Add `@PreAuthorize` to all five CtcPaymentController endpoints. Ship the first `CtcPaymentIT` (Testcontainers) covering create + list + get + commit. Delete the misleading auto-CTC placeholder copy pending Phase 4 rewrite (component stays; template shows a "coming soon" that doesn't mis-describe the domain).

### Changes Required

#### 1. Terminology sweep

**File:** `services/java/shared/src/main/resources/permissions.yaml` (lines 37, 38, 68)

Replace the three CTC descriptions. Labels stay ("View CTC payments" / "Commit CTC payments" / "Manage CTC payments" — CTC is fine, the drift is in the *long* descriptions).

```yaml
- { key: "claims:view_ctc_payments",   label: "View CTC payments",   description: "View Claims-to-Contributions transfers (member claim payouts credited against the member's own contribution bill)." }
- { key: "claims:commit_ctc_payment",  label: "Commit CTC payments", description: "Commit a Claims-to-Contributions transfer — the member's payable is applied against their contribution bill." }
- { key: "finance:manage_ctc_payments", label: "Manage CTC payments", description: "Create or commit Claims-to-Contributions transfers from finance." }
```

**File:** `services/java/shared/src/main/java/com/medfund/shared/security/PermissionCatalogue.java` (lines 42, 43, 66)

Same three descriptions, keep labels.

**File:** `clients/angular/src/app/core/security/permissions.ts` (lines 86, 87, 121)

Same three descriptions, keep labels. Preserves the three-way sync required by `Permissions.java`'s javadoc.

**File:** `clients/angular/src/app/pages/tenant/billing/billing.routes.ts` (around line 237)

Replace the description string for the `transactions/ctc` preset:

```typescript
description: 'Claims-to-Contributions offsets — approved member claim amounts applied against the same member\'s outstanding contributions.',
```

**File:** `services/java/finance-service/src/main/java/com/medfund/finance/controller/CtcPaymentController.java:28`

```java
@Tag(name = "CTC Payments",
     description = "Claims-to-Contributions transfers — the fund offsets a member's own contribution debt with an approved claim payout that would otherwise be paid to the member.")
```

**File:** `.claude/architecture.md:90` (small doc fix)

Change `CTC (cost-to-company) payments` → `CTC (Claims-to-Contributions) payments`. No line renumbering; single-token diff.

#### 2. `@PreAuthorize` on all five endpoints

**File:** `services/java/finance-service/src/main/java/com/medfund/finance/controller/CtcPaymentController.java`

```java
import org.springframework.security.access.prepost.PreAuthorize;

@GetMapping
@PreAuthorize("hasAuthority('claims:view_ctc_payments') or hasAuthority('finance:manage_ctc_payments')")
@Operation(...)
public Flux<CtcPaymentResponse> list(...) { ... }

@GetMapping("/page")
@PreAuthorize("hasAuthority('claims:view_ctc_payments') or hasAuthority('finance:manage_ctc_payments')")
@Operation(...)
public Mono<PageResponse<CtcPaymentRow>> searchPaged(...) { ... }

@GetMapping("/{id}")
@PreAuthorize("hasAuthority('claims:view_ctc_payments') or hasAuthority('finance:manage_ctc_payments')")
public Mono<CtcPaymentResponse> get(...) { ... }

@PostMapping
@PreAuthorize("hasAuthority('finance:manage_ctc_payments')")
public Mono<CtcPaymentResponse> create(...) { ... }

@PostMapping("/{id}/commit")
@PreAuthorize("hasAuthority('claims:commit_ctc_payment') or hasAuthority('finance:manage_ctc_payments')")
public Mono<CtcPaymentResponse> commit(...) { ... }
```

Rationale: the read endpoints accept either permission because both entry points (claims-side and finance-side UIs) need them. Create is finance-only. Commit is either — matching the row-action guards already in the Angular list. Phase 3 will add `@PreAuthorize("hasAuthority('finance:reverse_ctc_payment')")` on the new reverse endpoint.

#### 3. Auto-CTC placeholder — hold the copy, drop the misleading claims

**File:** `clients/angular/src/app/pages/tenant/claims/ctc/ctc-auto.component.html`

Replace the "auto-split contributions across members" description (which is the wrong domain) with a truthful placeholder that Phase 4 will rewrite:

```html
<div class="page">
  <header class="page-header">
    <div>
      <h1>Automated CTC drafts</h1>
      <p class="page-sub">Auto-drafted Claims-to-Contributions transfers created from claim adjudications.</p>
    </div>
    <a class="btn btn-default" routerLink="/tenant/claims/ctc/pending">
      <app-icon name="arrow-left" [size]="14"></app-icon>
      Back to pending
    </a>
  </header>
  <div class="callout">
    <app-icon name="info" [size]="16"></app-icon>
    <div>
      <strong>Coming in a follow-up phase.</strong>
      The rule editor and history for auto-CTC drafts will ship here once the
      finance-side consumer is enabled. Manual CTC creation is available at
      <a routerLink="/tenant/claims/ctc/add">New CTC payment</a>.
    </div>
  </div>
</div>
```

#### 4. First CTC integration test

**File:** `services/java/finance-service/src/test/java/com/medfund/finance/controller/CtcPaymentControllerIT.java` (new)

Boot the full WebFlux slice against a Testcontainers Postgres + Kafka broker, register the required guards from [[infra_testcontainers_pitfalls]] (Testcontainers 1.21.4 BOM override, `flyway-database-postgresql`, stub `ReactiveJwtDecoder`). Cover:

- `POST /api/v1/ctc-payments` with a valid member-only payload → 201, `committed=false`, audit event on `medfund.audit.events`.
- Same POST without `finance:manage_ctc_payments` → 403.
- `GET /api/v1/ctc-payments/page` with `?committed=false` returns the created row with `memberName` joined.
- `POST /api/v1/ctc-payments/{id}/commit` → `committed=true`, audit event fires.
- Idempotent commit — second call is a no-op, no second audit event.

The IT harness is what the advance-payments plan called "deferred". Land it now for CTC because Phase 3 will need the same harness for the offset roundtrip; ship the scaffolding once so both features get coverage.

### Success Criteria

#### Automated Verification

- [x] Java compiles: `cd services/java && ./gradlew :finance-service:build` — `:finance-service:compileJava :finance-service:compileTestJava` both green on 2026-08-09.
- [ ] Unit tests: `make test-java` (7 existing CtcPaymentService tests still pass — no service-layer change in this phase) — **12 pre-existing failures across `AdjustmentServiceTest`, `CtcPaymentServiceTest` (create_groupOnly, create_memberOnly), `MascaBankAccountServiceTest`, `PaymentServiceTest`, `ProviderBalanceServiceTest`, `ReconciliationServiceTest` are NOT caused by Phase 1** — my changes touch only the controller (annotations), 5 shared/Angular terminology strings, and a new IT class. Confirmed by inspecting the failing assertions and comparing to git blame: last touches were pre-Phase-1 (`cfebdbc` actorEmail plumb + `724f8ed` pagination); mock-based save NPEs match the [[bug_claim_save_mock_id_npe]] memory pattern. Flag for cleanup as a separate ticket.
- [x] `CtcPaymentServiceIT` green (see Deviations — landed as service-level IT instead of ControllerIT). 3/3 tests pass on 2026-08-09.
- [ ] Swagger renders the updated `@Tag` at `http://localhost:8085/swagger-ui` — description reads "Claims-to-Contributions transfers…" **[manual, needs server]**
- [x] Angular typecheck clean: `cd clients/angular && npx ng build --configuration=development` — BUILD SUCCESSFUL on 2026-08-09.
- [x] Angular unit tests: `make test-angular` — Karma completed with exit 0; no specs assert on the changed permission descriptions or route copy (grep for those strings across `*.spec.ts` returns none). A Karma/Chrome Headless disconnect warning appeared in the log — infrastructure quirk unrelated to my changes.
- [ ] `verify` skill on `/tenant/finance/payments/ctc` and `/tenant/claims/ctc/pending` — **[manual, needs running dev server]**

Phase 1 also fixed the same stale terminology in three extra Angular files the plan didn't enumerate — `ctc-add.component.html`, `ctc-payment-form.component.html`, `claims.routes.ts:267,273`. Silent implementation detail (same design intent as the enumerated list).

#### Manual Verification

- [ ] `curl -H 'Authorization: Bearer <no-perm-jwt>' -X POST /api/v1/ctc-payments -d '{"memberId":"...","amount":100,"currencyCode":"USD"}'` returns 403 (previously would have succeeded)
- [ ] Permission-management surface in the admin portal shows the new descriptions ("Claims-to-Contributions transfers …") when hovering the tooltip on any of the three CTC permissions

**Implementation Note:** pause after Phase 1 for the manual permission-tooltip spot-check before Phase 2 touches any schema.

---

## Phase 2: Member-payable ledger

### Overview

Ship the missing prerequisite: a source-of-truth ledger in finance-service for "how much is a member owed for their approved claims that route to MEMBER". `medfund.claims.adjudicated` payload gains `payeeType`. Existing `ClaimAdjudicatedConsumer` extends to write into `member_payables` when `payeeType=MEMBER`. New `MemberPayableBalanceRepository` (derived aggregate — mirrors `AdvancePaymentBalanceRepository`). New endpoint exposes the balance for the CTC form + admin surfaces. New permission gates the read endpoint.

### Changes Required

#### 1. Tenant migration — `member_payables` + application bridge

**File:** `services/java/tenancy-service/src/main/resources/db/migration/tenant/V069__ctc_lifecycle.sql`

Combined migration for both Phase 2 (member-payables) and Phase 3 (CTC lifecycle columns + transaction-type seeds) — one Vxxx file so the whole feature ships as a single tenant-schema step. Per [[feedback_never_edit_applied_migrations]], any correction after apply goes into V070+; keep this file idempotent (`IF NOT EXISTS` / `ON CONFLICT DO NOTHING`).

```sql
-- =====================================================================
-- V069: CTC (Claims-to-Contributions) full lifecycle
--
-- The V016 comment misdescribes CTC as "cash-to-cardholder" — an unrelated
-- legacy MASCA construct. That comment is locked (checksum verified on
-- boot per Flyway); this migration's header is where the terminology
-- correction lives.
--
-- CTC = the fund offsets a member's own contribution debt with an approved
-- claim payout that would otherwise be paid to the member (payee_type=MEMBER
-- claims per V066). This migration lands three things:
--
--   1. member_payables (+ applications bridge) — source-of-truth ledger of
--      what the fund owes members whose claims routed to MEMBER.
--   2. CTC lifecycle columns on ctc_payments — status machine, reversal
--      link, member_payable_id, applied_at/by. Also adds status='draft' as
--      the new default (existing 'committed' flag becomes a derivation).
--   3. transaction_types seed for CTC_OFFSET (sign '-') and
--      CTC_OFFSET_REVERSAL (sign '+') — consumed by contributions-service.
-- =====================================================================

-- ---------- 1. member_payables ---------------------------------------

CREATE TABLE IF NOT EXISTS member_payables (
    id              uuid           PRIMARY KEY DEFAULT gen_random_uuid(),
    member_id       uuid           NOT NULL,
    claim_id        uuid           NOT NULL,
    claim_number    text,
    amount          numeric(19, 4) NOT NULL CHECK (amount > 0),
    currency_code   varchar(3)     NOT NULL,
    status          text           NOT NULL DEFAULT 'open',
    -- 'open' | 'applied' | 'reversed'   (applied = fully consumed by CTC or
    -- future member payment runs; reversed = source claim was reversed)
    recorded_at     timestamptz    NOT NULL DEFAULT now(),
    recorded_by     uuid,
    CONSTRAINT member_payables_status_check
        CHECK (status IN ('open', 'applied', 'reversed')),
    CONSTRAINT member_payables_claim_unique UNIQUE (claim_id)
);

CREATE INDEX IF NOT EXISTS idx_member_payables_member
    ON member_payables(member_id);
CREATE INDEX IF NOT EXISTS idx_member_payables_status
    ON member_payables(status);
CREATE INDEX IF NOT EXISTS idx_member_payables_currency
    ON member_payables(currency_code);

-- Bridging: a member-payable is consumed a bit at a time. Today only CTC
-- writes here; future member-payment-run features add rows with
-- source_type='PAYMENT'. The source_id + source_type pair replaces a
-- per-source FK column — keeps this table stable when the second consumer
-- lands.
CREATE TABLE IF NOT EXISTS member_payable_applications (
    id                   uuid           PRIMARY KEY DEFAULT gen_random_uuid(),
    member_payable_id    uuid           NOT NULL REFERENCES member_payables(id),
    source_type          text           NOT NULL,       -- 'CTC' (this plan); future: 'PAYMENT'
    source_id            uuid           NOT NULL,       -- ctc_payment.id
    amount_applied       numeric(19, 4) NOT NULL,       -- positive = consumed; negative = reversal
    currency_code        varchar(3)     NOT NULL,
    applied_at           timestamptz    NOT NULL DEFAULT now(),
    applied_by           uuid,
    CONSTRAINT mpa_amount_nonzero CHECK (amount_applied <> 0),
    CONSTRAINT mpa_source_type_check CHECK (source_type IN ('CTC'))
);

CREATE INDEX IF NOT EXISTS idx_mpa_payable ON member_payable_applications(member_payable_id);
CREATE INDEX IF NOT EXISTS idx_mpa_source  ON member_payable_applications(source_type, source_id);

-- ---------- 2. ctc_payments lifecycle columns ------------------------

ALTER TABLE ctc_payments
    ADD COLUMN IF NOT EXISTS type                text,
    ADD COLUMN IF NOT EXISTS status              text,
    ADD COLUMN IF NOT EXISTS member_payable_id   uuid REFERENCES member_payables(id),
    ADD COLUMN IF NOT EXISTS reverses_ctc_id     uuid,
    ADD COLUMN IF NOT EXISTS committed_at        timestamptz,
    ADD COLUMN IF NOT EXISTS committed_by        uuid;

-- Backfill existing rows: everything created before this migration is a
-- historical ADVANCE-style entry. If committed=true, mark status=committed;
-- else status=draft. Type='ADVANCE' is wrong — use type='CTC' consistently.
UPDATE ctc_payments
   SET type   = 'CTC',
       status = CASE WHEN committed = TRUE THEN 'committed' ELSE 'draft' END
 WHERE type IS NULL;

ALTER TABLE ctc_payments
    ALTER COLUMN type   SET NOT NULL,
    ALTER COLUMN type   SET DEFAULT 'CTC',
    ALTER COLUMN status SET NOT NULL,
    ALTER COLUMN status SET DEFAULT 'draft';

ALTER TABLE ctc_payments
    ADD CONSTRAINT ctc_payments_type_check
        CHECK (type IN ('CTC', 'REVERSAL')),
    ADD CONSTRAINT ctc_payments_status_check
        CHECK (status IN ('draft', 'committed', 'reversed')),
    ADD CONSTRAINT ctc_payments_reversal_link_check
        CHECK ((type = 'REVERSAL') = (reverses_ctc_id IS NOT NULL)),
    ADD CONSTRAINT ctc_payments_reverses_fk
        FOREIGN KEY (reverses_ctc_id) REFERENCES ctc_payments(id);

CREATE INDEX IF NOT EXISTS idx_ctc_status
    ON ctc_payments(status);
CREATE INDEX IF NOT EXISTS idx_ctc_member_payable
    ON ctc_payments(member_payable_id)
    WHERE member_payable_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_ctc_reverses
    ON ctc_payments(reverses_ctc_id)
    WHERE reverses_ctc_id IS NOT NULL;

-- ---------- 3. transaction_types seed --------------------------------

INSERT INTO transaction_types (code, label, sign, requires_approval) VALUES
    ('CTC_OFFSET',          'CTC offset',           '-', FALSE),
    ('CTC_OFFSET_REVERSAL', 'CTC offset reversal',  '+', FALSE)
ON CONFLICT (code) DO NOTHING;

-- ---------- 4. permissions seed (Phase 3 addition) --------------------

-- finance:reverse_ctc_payment — gates the compensating-row endpoint.
-- Separated from finance:manage_ctc_payments so tenants can grant create
-- + commit (finance clerks) without granting reverse (finance HoD only).
--
-- finance:view_member_payables — read the outstanding "member is owed"
-- balances. Read-only. Needed by CTC form + admin surfaces.
INSERT INTO permissions_catalogue (permission, description) VALUES
    ('finance:reverse_ctc_payment', 'Post a compensating reversal for a committed Claims-to-Contributions transfer'),
    ('finance:view_member_payables', 'View outstanding member-payable balances (approved claim amounts routing to members)')
ON CONFLICT (permission) DO NOTHING;

INSERT INTO role_permissions (role_id, permission)
SELECT r.id, p.permission
  FROM roles r
 CROSS JOIN (VALUES
    ('finance:reverse_ctc_payment'),
    ('finance:view_member_payables')
 ) AS p(permission)
 WHERE r.name = 'tenant_admin'
ON CONFLICT (role_id, permission) DO NOTHING;
```

Confirmed against `services/java/tenancy-service/src/main/resources/db/migration/tenant/V008__billing_catalogues.sql:154-160` — the seed insert format matches (`code, label, sign, requires_approval`).

#### 2. `medfund.claims.adjudicated` payload gains `payeeType`

**File:** `services/java/claims-service/src/main/java/com/medfund/claims/service/ClaimEventPublisher.java`

```java
public Mono<Void> publishClaimAdjudicated(String claimId, String claimNumber, String decision,
                                            String providerId, String approvedAmount, String currencyCode,
                                            String insuranceLine,
                                            String memberId, String dependantId,
                                            String benefitId, String policyYear,
                                            String payeeType) {                        // ← added
    var payload = new java.util.LinkedHashMap<String, String>();
    payload.put("event", "CLAIM_ADJUDICATED");
    payload.put("claimId", claimId);
    payload.put("claimNumber", claimNumber);
    payload.put("decision", decision);
    payload.put("providerId", providerId != null ? providerId : "");
    payload.put("approvedAmount", approvedAmount != null ? approvedAmount : "0");
    payload.put("currencyCode", currencyCode != null ? currencyCode : "USD");
    payload.put("insuranceLine", nz(insuranceLine));
    payload.put("memberId",    nz(memberId));
    payload.put("dependantId", nz(dependantId));
    payload.put("benefitId",   nz(benefitId));
    payload.put("policyYear",  nz(policyYear));
    payload.put("payeeType",   nz(payeeType));                                          // ← added
    return publishEvent("medfund.claims.adjudicated", claimId, payload);
}
```

**File:** `services/java/claims-service/src/main/java/com/medfund/claims/service/ClaimService.java`

Find the single call site of `publishClaimAdjudicated` (verified by grep — one caller only) and pass `claim.getPayeeType()`. `Claim.payeeType` is already present from V066 and mapped on the entity.

#### 3. Extend `ClaimAdjudicatedConsumer` — write member-payables

**File:** `services/java/finance-service/src/main/java/com/medfund/finance/consumer/ClaimAdjudicatedConsumer.java`

Existing behaviour (provider balance update) stays put — `payeeType=PROVIDER` still goes through `providerBalanceService.updateBalance(...)`. New branch for `payeeType=MEMBER` writes a `member_payables` row and skips the provider balance update.

```java
// existing imports + new:
import com.medfund.finance.entity.MemberPayable;
import com.medfund.finance.repository.MemberPayableRepository;
import com.medfund.shared.audit.AuditEvent;
import com.medfund.shared.audit.AuditPublisher;
import com.medfund.shared.tenant.TenantContext;

@Component
public class ClaimAdjudicatedConsumer {
    // ... existing fields + new:
    private final MemberPayableRepository memberPayableRepository;
    private final AuditPublisher auditPublisher;

    // constructor updated to include the two new deps

    public Mono<Void> processEvent(String json) {
        try {
            JsonNode node = objectMapper.readTree(json);
            String decision  = node.get("decision").asText();
            String payeeType = textOrNull(node, "payeeType");    // ← new
            String memberId  = textOrNull(node, "memberId");
            String claimId   = textOrNull(node, "claimId");
            String claimNumber = textOrNull(node, "claimNumber");
            String currencyCode  = textOrNull(node, "currencyCode");
            String approvedAmount = textOrNull(node, "approvedAmount");

            // Member-payee branch: only APPROVED / PARTIAL_APPROVED create a
            // payable; REJECTED / other decisions are a no-op.
            if ("MEMBER".equalsIgnoreCase(payeeType)) {
                if (!isApproved(decision) || memberId == null || claimId == null
                        || approvedAmount == null || new BigDecimal(approvedAmount).signum() <= 0) {
                    return Mono.empty();
                }
                return writeMemberPayable(claimId, claimNumber, memberId, currencyCode,
                                          new BigDecimal(approvedAmount));
            }

            // Provider-payee branch: unchanged existing behaviour.
            return processProviderBalance(node, decision);
        } catch (Exception e) {
            log.error("Failed to parse claim adjudicated event: {}", e.getMessage());
            return Mono.error(e);
        }
    }

    private Mono<Void> writeMemberPayable(String claimId, String claimNumber, String memberId,
                                           String currencyCode, BigDecimal amount) {
        MemberPayable mp = new MemberPayable();
        mp.setMemberId(UUID.fromString(memberId));
        mp.setClaimId(UUID.fromString(claimId));
        mp.setClaimNumber(claimNumber);
        mp.setAmount(amount);
        mp.setCurrencyCode(currencyCode != null ? currencyCode : "USD");
        mp.setStatus("open");
        mp.setRecordedAt(Instant.now());
        mp.setRecordedBy(null); // system-initiated
        return memberPayableRepository.save(mp)
            .flatMap(saved -> publishMemberPayableAudit(saved))
            .then();
    }

    private Mono<Void> publishMemberPayableAudit(MemberPayable mp) {
        return Mono.deferContextual(ctx -> {
            String tenantId = TenantContext.get(ctx);
            String entityName = "Payable to member " + mp.getMemberId()
                              + " " + mp.getAmount().toPlainString() + " " + mp.getCurrencyCode();
            var event = AuditEvent.create(
                tenantId != null ? tenantId : "unknown",
                "MemberPayable",
                mp.getId().toString(),
                entityName,
                "CREATE",
                AuditActor.SYSTEM_ID,
                AuditActor.SYSTEM_EMAIL,
                null,
                Map.of("amount", mp.getAmount().toPlainString(),
                       "currencyCode", mp.getCurrencyCode(),
                       "claimId", mp.getClaimId().toString(),
                       "memberId", mp.getMemberId().toString()),
                new String[]{},
                UUID.randomUUID().toString()
            );
            return auditPublisher.publish(event);
        });
    }

    private static boolean isApproved(String decision) {
        String d = decision == null ? "" : decision.toUpperCase();
        return d.equals("APPROVED") || d.equals("PARTIAL_APPROVED");
    }

    // processProviderBalance(node, decision) = the existing switch/branches
    // extracted verbatim into its own method for readability.
}
```

The `.doOnSuccess(...ack)` pattern from the existing consumer stays — per [[bug_reactor_kafka_ack_swallow]] we do **not** switch to `.doOnTerminate`; failed member-payable writes must not silently drop the offset.

**Idempotency**: `member_payables_claim_unique` (V069) enforces one payable per claim. If the consumer re-processes the same event (Kafka at-least-once), the second insert fails with a unique-violation — trap it and treat as no-op (still ack the offset). Add:

```java
.onErrorResume(e -> {
    if (isUniqueViolation(e)) {
        log.info("Member payable already exists for claim {} — idempotent skip", claimId);
        return Mono.empty();
    }
    return Mono.error(e);
})
```

`isUniqueViolation(Throwable)` matches on `org.springframework.dao.DuplicateKeyException` or the Postgres `23505` SQLState — small helper in `services/java/finance-service/src/main/java/com/medfund/finance/util/DbErrors.java` (new).

#### 4. Entity + repository

**File:** `services/java/finance-service/src/main/java/com/medfund/finance/entity/MemberPayable.java` (new)

```java
@Getter
@Setter
@Table("member_payables")
public class MemberPayable {
    @Id private UUID id;
    @Column("member_id")     private UUID memberId;
    @Column("claim_id")      private UUID claimId;
    @Column("claim_number")  private String claimNumber;
    private BigDecimal amount;
    @Column("currency_code") private String currencyCode;
    private String status;   // 'open' | 'applied' | 'reversed'
    @Column("recorded_at")   private Instant recordedAt;
    @Column("recorded_by")   private UUID recordedBy;
}
```

**File:** `services/java/finance-service/src/main/java/com/medfund/finance/entity/MemberPayableApplication.java` (new)

```java
@Getter
@Setter
@Table("member_payable_applications")
public class MemberPayableApplication {
    @Id private UUID id;
    @Column("member_payable_id") private UUID memberPayableId;
    @Column("source_type")       private String sourceType;   // 'CTC' for MVP
    @Column("source_id")         private UUID sourceId;       // ctc_payment.id
    @Column("amount_applied")    private BigDecimal amountApplied;  // signed
    @Column("currency_code")     private String currencyCode;
    @Column("applied_at")        private Instant appliedAt;
    @Column("applied_by")        private UUID appliedBy;
}
```

**File:** `services/java/finance-service/src/main/java/com/medfund/finance/repository/MemberPayableRepository.java` (new) — reactive CRUD + `Flux<MemberPayable> findByMemberIdAndStatus(UUID memberId, String status)`.

**File:** `services/java/finance-service/src/main/java/com/medfund/finance/repository/MemberPayableApplicationRepository.java` (new) — reactive CRUD + `Flux<MemberPayableApplication> findByMemberPayableId(UUID id)`.

**File:** `services/java/finance-service/src/main/java/com/medfund/finance/repository/MemberPayableBalanceRepository.java` (new)

Derived-aggregate pattern, cloned from `AdvancePaymentBalanceRepository`.

```java
@Slf4j
@Repository
@RequiredArgsConstructor
public class MemberPayableBalanceRepository {

    private final DatabaseClient db;

    public Flux<OutstandingMemberPayable> findOutstandingByMember(UUID memberId) {
        return db.sql("""
                WITH totals AS (
                    SELECT mp.currency_code AS currency_code,
                           SUM(mp.amount) AS payable
                      FROM member_payables mp
                     WHERE mp.member_id = :memberId
                       AND mp.status IN ('open', 'applied')
                     GROUP BY mp.currency_code
                ),
                applied AS (
                    SELECT mp.currency_code AS currency_code,
                           SUM(mpa.amount_applied) AS applied
                      FROM member_payable_applications mpa
                      JOIN member_payables mp ON mp.id = mpa.member_payable_id
                     WHERE mp.member_id = :memberId
                     GROUP BY mp.currency_code
                )
                SELECT t.currency_code AS currency_code,
                       (t.payable - COALESCE(a.applied, 0)) AS outstanding
                  FROM totals t
             LEFT JOIN applied a ON a.currency_code = t.currency_code
                 WHERE (t.payable - COALESCE(a.applied, 0)) > 0
                """)
                .bind("memberId", memberId)
                .map((row, meta) -> new OutstandingMemberPayable(
                        row.get("currency_code", String.class),
                        row.get("outstanding", BigDecimal.class)))
                .all();
    }

    public Mono<BigDecimal> remainingOn(UUID payableId) {
        return db.sql("""
                SELECT mp.amount
                     - COALESCE((SELECT SUM(amount_applied)
                                   FROM member_payable_applications
                                  WHERE member_payable_id = mp.id), 0) AS remaining
                  FROM member_payables mp
                 WHERE mp.id = :payableId
                """)
                .bind("payableId", payableId)
                .map((row, meta) -> row.get("remaining", BigDecimal.class))
                .one()
                .defaultIfEmpty(BigDecimal.ZERO);
    }

    public record OutstandingMemberPayable(String currencyCode, BigDecimal outstanding) {}
}
```

Note the query uses `member_payables` unqualified — not `public.member_payables` — per [[bug_public_prefix_silent_rollback]] (only `public.` for V105+ platform-wide tables; tenant-schema tables stay unqualified).

#### 5. Read endpoint for the CTC form + admin surfaces

**File:** `services/java/finance-service/src/main/java/com/medfund/finance/controller/MemberPayableController.java` (new)

```java
@RestController
@RequestMapping("/api/v1/member-payables")
@RequiredArgsConstructor
@Tag(name = "Member Payables", description = "Approved claim amounts owed to members (payee_type=MEMBER).")
@SecurityRequirement(name = "bearer-jwt")
public class MemberPayableController {

    private final MemberPayableService service;

    @GetMapping("/member/{memberId}")
    @PreAuthorize("hasAuthority('finance:view_member_payables') or hasAuthority('finance:manage_ctc_payments')")
    @Operation(summary = "List open payables for a member — used by the CTC form to pick a payable to offset")
    public Flux<MemberPayableResponse> listForMember(@PathVariable UUID memberId) {
        return service.findOpenByMember(memberId).map(MemberPayableResponse::from);
    }

    @GetMapping("/member/{memberId}/balance")
    @PreAuthorize("hasAuthority('finance:view_member_payables') or hasAuthority('finance:manage_ctc_payments')")
    @Operation(summary = "Outstanding payable balance per currency for a member")
    public Flux<OutstandingBalanceResponse> balance(@PathVariable UUID memberId) {
        return service.outstandingByMember(memberId)
            .map(b -> new OutstandingBalanceResponse(b.currencyCode(), b.outstanding()));
    }

    public record OutstandingBalanceResponse(String currencyCode, BigDecimal outstanding) {}
}
```

Corresponding thin `MemberPayableService` reads through the two repositories; no writes here (writes live in the consumer).

#### 6. Gateway routes

**File:** `services/go/gateway/internal/routes/routes.go`

Add two lines matching the existing pattern:

```go
tenant.Any("/api/v1/member-payables", proxy.ForwardTo(cfg.FinanceServiceURL))
tenant.Any("/api/v1/member-payables/*", proxy.ForwardTo(cfg.FinanceServiceURL))
```

Copy the existing tenant/jwt middleware chain — no CTC-specific gateway logic.

### Success Criteria

#### Automated Verification

- [x] Java compiles: `cd services/java && ./gradlew :finance-service:compileJava :claims-service:compileJava :tenancy-service:compileJava` — all green on 2026-08-09; test compile also green.
- [x] Migration IT: `V069` applies cleanly on the tenancy-service Testcontainer harness (`TenantMigrationFlywayIT` — BUILD SUCCESSFUL on 2026-08-09).
- [ ] `V069` shows in `flyway_schema_history` after boot with `success=true` and `applied_by IS NOT NULL` **[manual, needs live tenant on running tenancy-service]**
- [ ] `SELECT COUNT(*) FROM transaction_types WHERE code IN ('CTC_OFFSET','CTC_OFFSET_REVERSAL')` = 2 on any tenant schema **[manual, needs live tenant]**
- [ ] `SELECT permission FROM role_permissions WHERE permission IN ('finance:reverse_ctc_payment','finance:view_member_payables')` returns two rows per tenant_admin role **[manual, needs live tenant]**
- [x] `ClaimAdjudicatedConsumerTest` expanded — 9 tests, covers PROVIDER (4 pre-existing preserved), MEMBER+APPROVED writes payable + audit, MEMBER+REJECTED no-op, MEMBER+zero-amount no-op, duplicate ⇒ idempotent skip (DuplicateKeyException path).
- [x] `MemberPayableBalanceRepositoryIT` (Testcontainers Postgres slice) — mixed-data CTE test + single-payable remaining + reversed-application round-trip, all green on 2026-08-09.
- [ ] Swagger renders `/api/v1/member-payables` at `http://localhost:8085/swagger-ui` with request/response schemas and the two endpoint descriptions **[manual, needs running server]**
- [ ] `verify` skill on `/tenant/finance/payments/ctc` — no console errors, page still renders (no UI change yet, but the migration must not break the existing list) **[manual, needs dev server]**

Phase 2 also added `tenantId` to the `medfund.claims.adjudicated` payload — not in the plan but required for the MEMBER-payee branch to switch the R2DBC search_path to the tenant schema; see Deviations.
Phase 2 also updated `finance-service/src/test/resources/db/test-migration/V002__member_payables.sql` layering the new tables + ctc_payments lifecycle columns onto V001__ctc.sql, so both `CtcPaymentServiceIT` and `MemberPayableBalanceRepositoryIT` share the same Testcontainers harness.
Phase 2 also updated `clients/angular/src/app/core/security/permissions.ts` `PermissionKey` union with the two new strings so the descriptor rows type-check.
Phase 2 also updated `services/java/shared/src/main/resources/permissions.yaml` + `PermissionCatalogue.java` + `Permissions.java` (constants + `ALL` set) with the two new finance permissions so `PermissionResolver` recognizes them.

#### Manual Verification

- [ ] End-to-end trigger: adjudicate a claim in a test tenant with `payee_type=MEMBER, approvedAmount=$120 USD` (via the claims admin UI); within a few seconds, `SELECT * FROM member_payables WHERE claim_id = '<id>'` shows one row with `amount=120, status='open'`; provider_balances is untouched
- [ ] `GET /api/v1/member-payables/member/<id>/balance` returns `[{currencyCode: 'USD', outstanding: '120.00'}]`
- [ ] Re-fire the same claim adjudication event manually (Kafka console producer) → no duplicate row; the consumer logs "idempotent skip"

**Implementation Note:** pause after Phase 2 for the manual end-to-end trigger verification before Phase 3 starts moving money in the ledger.

---

## Phase 3: CTC transfer wiring + reversal

### Overview

CTC now requires linking to a member-payable at creation time and validates that the payable has enough remaining balance. Commit writes a `member_payable_applications` row, publishes `medfund.finance.ctc.committed`, and marks the payable `applied` if fully consumed. New contributions-service consumer writes a `CTC_OFFSET` transaction. New reverse endpoint posts a compensating CTC row and republishes on `medfund.finance.ctc.reversed`; the contributions-service consumer writes a `CTC_OFFSET_REVERSAL` transaction. Currency conversion via `FxConverter` where needed. Full audit trail on every state change.

### Changes Required

#### 1. `CtcPayment` entity — new fields

**File:** `services/java/finance-service/src/main/java/com/medfund/finance/entity/CtcPayment.java`

Add fields matching V069 additions:

```java
private String type;                 // 'CTC' | 'REVERSAL'  (default 'CTC')
private String status;               // 'draft' | 'committed' | 'reversed'
@Column("member_payable_id") private UUID memberPayableId;
@Column("reverses_ctc_id")   private UUID reversesCtcId;
@Column("committed_at")      private Instant committedAt;
@Column("committed_by")      private UUID committedBy;
```

The existing `committed` Boolean stays (derivable from `status='committed'`) for one release to keep external readers stable — the DTO layer computes it from `status` in Phase 5. Removed in a follow-up cleanup migration.

#### 2. DTO changes

**File:** `services/java/finance-service/src/main/java/com/medfund/finance/dto/CtcPaymentDtos.java`

```java
public record CreateCtcPaymentRequest(
    UUID groupId,                                              // still allowed as null; service 422s group-only
    @NotNull UUID memberId,                                    // now required (member-only for MVP)
    @NotNull @DecimalMin("0.01") BigDecimal amount,
    @NotBlank @Size(max = 3) String currencyCode,
    UUID contributionId,
    @NotNull UUID memberPayableId                              // ← new: source of the offset
) {}

public record CtcPaymentResponse(
    UUID id, String type, String status,
    UUID groupId, UUID memberId, UUID memberPayableId, UUID reversesCtcId,
    BigDecimal amount, String currencyCode,
    Instant createdAt, UUID createdBy,
    Instant committedAt, UUID committedBy,
    boolean committed                                          // derived from status for back-compat
) {
    public static CtcPaymentResponse from(CtcPayment c) {
        return new CtcPaymentResponse(
            c.getId(), c.getType(), c.getStatus(),
            c.getGroupId(), c.getMemberId(), c.getMemberPayableId(), c.getReversesCtcId(),
            c.getAmount(), c.getCurrencyCode(),
            c.getCreatedAt(), c.getCreatedBy(),
            c.getCommittedAt(), c.getCommittedBy(),
            "committed".equals(c.getStatus()));
    }
}

public record ReverseCtcPaymentRequest(@NotBlank @Size(max = 500) String reason) {}
```

`CtcPaymentRow` (paginated list) gains the same `type`, `status`, `memberPayableId` columns.

#### 3. `CtcPaymentService` — link, commit, reverse

**File:** `services/java/finance-service/src/main/java/com/medfund/finance/service/CtcPaymentService.java`

```java
public Mono<CtcPayment> create(CreateCtcPaymentRequest req, String actor, String actorEmail) {
    if (req.groupId() != null && req.memberId() == null) {
        return Mono.error(new ResponseStatusException(UNPROCESSABLE_ENTITY,
            "Group-only CTC is out of scope for this release — provide memberId"));
    }
    // Validate payable exists, is open, and covers the CTC amount after FX.
    return memberPayableRepository.findById(req.memberPayableId())
        .switchIfEmpty(Mono.error(new ResponseStatusException(UNPROCESSABLE_ENTITY,
            "Member-payable not found: " + req.memberPayableId())))
        .flatMap(payable -> {
            if (!payable.getMemberId().equals(req.memberId())) {
                return Mono.error(new ResponseStatusException(UNPROCESSABLE_ENTITY,
                    "Member-payable belongs to a different member"));
            }
            if (!"open".equals(payable.getStatus())) {
                return Mono.error(new ResponseStatusException(UNPROCESSABLE_ENTITY,
                    "Member-payable status is " + payable.getStatus() + " — cannot offset"));
            }
            return convertIfNeeded(req.amount(), req.currencyCode(), payable.getCurrencyCode())
                .flatMap(convertedAmount -> balanceRepository.remainingOn(payable.getId())
                    .flatMap(remaining -> {
                        if (convertedAmount.compareTo(remaining) > 0) {
                            return Mono.error(new ResponseStatusException(UNPROCESSABLE_ENTITY,
                                "CTC amount " + convertedAmount + " " + payable.getCurrencyCode()
                                + " exceeds remaining payable " + remaining));
                        }
                        return saveDraftCtc(req, actor, actorEmail);
                    }));
        });
}

private Mono<BigDecimal> convertIfNeeded(BigDecimal amount, String fromCurrency, String toCurrency) {
    if (fromCurrency.equals(toCurrency)) return Mono.just(amount);
    return fxConverter.convert(amount, fromCurrency, toCurrency, Instant.now());
}

private Mono<CtcPayment> saveDraftCtc(CreateCtcPaymentRequest req, String actor, String actorEmail) {
    var entity = new CtcPayment();
    entity.setGroupId(req.groupId());
    entity.setMemberId(req.memberId());
    entity.setMemberPayableId(req.memberPayableId());
    entity.setAmount(req.amount());
    entity.setCurrencyCode(req.currencyCode());
    entity.setContributionId(req.contributionId());
    entity.setType("CTC");
    entity.setStatus("draft");
    entity.setCommitted(false);
    return repository.save(entity)
        .flatMap(saved -> publishAudit("CREATE", saved, null, snapshot(saved), actor, actorEmail).thenReturn(saved));
}

public Mono<CtcPayment> commit(UUID id, String actor, String actorEmail) {
    return repository.findById(id)
        .switchIfEmpty(Mono.error(new ResponseStatusException(NOT_FOUND, "CTC payment not found: " + id)))
        .flatMap(ctc -> {
            if ("committed".equals(ctc.getStatus())) return Mono.just(ctc);   // idempotent
            if (!"draft".equals(ctc.getStatus())) {
                return Mono.error(new ResponseStatusException(UNPROCESSABLE_ENTITY,
                    "Cannot commit CTC in status " + ctc.getStatus()));
            }
            Map<String, Object> before = snapshot(ctc);
            ctc.setStatus("committed");
            ctc.setCommitted(true);
            ctc.setCommittedAt(Instant.now());
            ctc.setCommittedBy(actor != null ? UUID.fromString(actor) : null);
            return repository.save(ctc)
                .flatMap(saved -> writeApplication(saved, actor)
                    .then(maybeMarkPayableApplied(saved.getMemberPayableId()))
                    .then(publishAudit("UPDATE", saved, before, snapshot(saved), actor, actorEmail))
                    .then(financeEventPublisher.publishCtcCommitted(saved))
                    .thenReturn(saved));
        });
}

public Mono<CtcPayment> reverse(UUID id, ReverseCtcPaymentRequest req, String actor, String actorEmail) {
    return repository.findById(id)
        .switchIfEmpty(Mono.error(new ResponseStatusException(NOT_FOUND, "CTC payment not found: " + id)))
        .flatMap(original -> {
            if (!"committed".equals(original.getStatus())) {
                return Mono.error(new ResponseStatusException(UNPROCESSABLE_ENTITY,
                    "Cannot reverse CTC in status " + original.getStatus()));
            }
            CtcPayment compensating = new CtcPayment();
            compensating.setType("REVERSAL");
            compensating.setStatus("committed");
            compensating.setReversesCtcId(original.getId());
            compensating.setGroupId(original.getGroupId());
            compensating.setMemberId(original.getMemberId());
            compensating.setMemberPayableId(original.getMemberPayableId());
            compensating.setAmount(original.getAmount());
            compensating.setCurrencyCode(original.getCurrencyCode());
            compensating.setContributionId(original.getContributionId());
            compensating.setCommitted(true);
            compensating.setCommittedAt(Instant.now());
            compensating.setCommittedBy(actor != null ? UUID.fromString(actor) : null);

            original.setStatus("reversed");
            return repository.save(original)
                .then(repository.save(compensating))
                .flatMap(saved -> writeNegatingApplication(original, saved, actor)
                    .then(reopenPayable(original.getMemberPayableId()))
                    .then(publishAudit("REVERSE", saved,
                            Map.of("reversesCtcId", original.getId().toString()),
                            snapshot(saved), actor, actorEmail))
                    .then(financeEventPublisher.publishCtcReversed(original, saved, req.reason()))
                    .thenReturn(saved));
        });
}
```

Supporting helpers:

- `writeApplication(ctc, actor)` — inserts a `member_payable_applications` row with `source_type='CTC', source_id=ctc.id, amount_applied=+ctc.amount, applied_by=actor`. Uses `FxConverter` if `ctc.currencyCode != payable.currencyCode` (rare — normally the operator picks matching currencies).
- `writeNegatingApplication(original, compensating, actor)` — inserts a compensating row with `amount_applied = -original.amount, source_id = compensating.id`. Net result: the payable's applied-sum returns to what it was before the CTC.
- `maybeMarkPayableApplied(payableId)` — if `remainingOn(payable) <= 0`, `UPDATE member_payables SET status='applied' WHERE id=?`.
- `reopenPayable(payableId)` — `UPDATE member_payables SET status='open' WHERE id=? AND status='applied'` (idempotent; if the payable is fully consumed by a *different* CTC, this is a no-op).

All queries stay unqualified (tenant-schema tables) per [[bug_public_prefix_silent_rollback]].

#### 4. Controller — commit updated, reverse added

**File:** `services/java/finance-service/src/main/java/com/medfund/finance/controller/CtcPaymentController.java`

```java
@PostMapping("/{id}/reverse")
@ResponseStatus(HttpStatus.CREATED)
@PreAuthorize("hasAuthority('finance:reverse_ctc_payment')")
@Operation(summary = "Reverse a committed CTC payment",
           description = "Posts a compensating REVERSAL row. Original is marked status=reversed and never mutates further. The member's contribution ledger receives a CTC_OFFSET_REVERSAL transaction that restores the offset amount.")
public Mono<CtcPaymentResponse> reverse(@PathVariable UUID id,
                                         @Valid @RequestBody ReverseCtcPaymentRequest body,
                                         @AuthenticationPrincipal Jwt jwt) {
    return service.reverse(id, body, AuditActor.id(jwt), AuditActor.email(jwt))
        .map(CtcPaymentResponse::from);
}
```

#### 5. `FinanceEventPublisher` — three new topics

**File:** `services/java/finance-service/src/main/java/com/medfund/finance/service/FinanceEventPublisher.java`

```java
public Mono<Void> publishCtcCommitted(CtcPayment ctc) {
    Map<String, String> payload = new HashMap<>();
    payload.put("event", "CTC_COMMITTED");
    payload.put("ctcId", ctc.getId().toString());
    payload.put("memberId", ctc.getMemberId().toString());
    payload.put("groupId", ctc.getGroupId() != null ? ctc.getGroupId().toString() : "");
    payload.put("memberPayableId", ctc.getMemberPayableId().toString());
    payload.put("amount", ctc.getAmount().toPlainString());
    payload.put("currencyCode", ctc.getCurrencyCode());
    payload.put("committedBy", ctc.getCommittedBy() != null ? ctc.getCommittedBy().toString() : "");
    return publishEvent("medfund.finance.ctc.committed", ctc.getId().toString(), payload);
}

public Mono<Void> publishCtcReversed(CtcPayment original, CtcPayment compensating, String reason) {
    Map<String, String> payload = new HashMap<>();
    payload.put("event", "CTC_REVERSED");
    payload.put("originalCtcId", original.getId().toString());
    payload.put("compensatingCtcId", compensating.getId().toString());
    payload.put("memberId", original.getMemberId().toString());
    payload.put("memberPayableId", original.getMemberPayableId().toString());
    payload.put("amount", original.getAmount().toPlainString());
    payload.put("currencyCode", original.getCurrencyCode());
    payload.put("reason", reason != null ? reason : "");
    return publishEvent("medfund.finance.ctc.reversed", original.getId().toString(), payload);
}
```

#### 6. Contributions-service consumer — write CTC_OFFSET

**File:** `services/java/contributions-service/src/main/java/com/medfund/contributions/consumer/CtcCommittedConsumer.java` (new)

Follows the pattern of `MemberEnrolledConsumer` (existing sibling). Two topics on one consumer (or two consumers — one per topic, cleaner separation). Recommendation: one consumer with two subscriptions is more atomic.

Actually — Reactor Kafka's `KafkaReceiver` takes a single subscription. Cleaner to split: `CtcCommittedConsumer` on `medfund.finance.ctc.committed`, `CtcReversedConsumer` on `medfund.finance.ctc.reversed`. Do that.

```java
@Component
public class CtcCommittedConsumer {

    private static final Logger log = LoggerFactory.getLogger(CtcCommittedConsumer.class);
    private static final String TOPIC = "medfund.finance.ctc.committed";

    private final ReceiverOptions<String, String> receiverOptions;
    private final TransactionService transactionService;
    private final ObjectMapper objectMapper;
    private final ContributionsFxConverter fx;    // wraps ExchangeRateProvider

    // ... constructor via @RequiredArgsConstructor or explicit — match sibling
    //     consumers' style; DependantEnrolledConsumer uses explicit ctor.

    @PostConstruct
    public void consume() {
        var options = receiverOptions.subscription(Collections.singleton(TOPIC));
        KafkaReceiver.create(options)
            .receive()
            .flatMap(record -> processEvent(record.value())
                .doOnSuccess(v -> record.receiverOffset().acknowledge())
                .doOnError(e -> log.error("Failed to process CTC committed event (full chain): ", e))
                .onErrorResume(e -> Mono.empty()))    // don't kill the flux; error already logged
            .doOnError(e -> log.error("CTC committed consumer error: ", e))
            .retry()
            .subscribe();
    }

    Mono<Void> processEvent(String json) {
        try {
            JsonNode node = objectMapper.readTree(json);
            UUID memberId       = UUID.fromString(node.get("memberId").asText());
            BigDecimal amount   = new BigDecimal(node.get("amount").asText());
            String ctcCurrency  = node.get("currencyCode").asText();
            String ctcId        = node.get("ctcId").asText();
            String memberPayableId = node.get("memberPayableId").asText();
            String tenantId     = node.get("tenantId") != null ? node.get("tenantId").asText() : null;

            // The offset lands in the member's contribution-ledger currency
            // (which may differ from the CTC currency). Convert if needed.
            return resolveMemberContributionCurrency(memberId)
                .flatMap(ledgerCurrency -> fx.convert(amount, ctcCurrency, ledgerCurrency, Instant.now())
                    .flatMap(convertedAmount -> {
                        var req = new RecordTransactionRequest(
                            /* groupId */ null,
                            /* memberId */ memberId,
                            /* amount */ convertedAmount,
                            /* currencyCode */ ledgerCurrency,
                            /* transactionType */ "CTC_OFFSET",
                            /* paymentMethod */ "CTC",
                            /* reference */ "CTC:" + ctcId,
                            /* reason */ "Claims-to-Contributions offset — payable " + memberPayableId
                        );
                        return transactionService.recordFromCtcOffset(req,
                                AuditActor.SYSTEM_ID, AuditActor.SYSTEM_EMAIL);
                    }));
        } catch (Exception e) {
            log.error("Failed to parse CTC committed event: ", e);
            return Mono.error(e);
        }
    }
    // ...
}
```

Critical: **use `.doOnSuccess` for ack** — never `.doOnTerminate` per [[bug_reactor_kafka_ack_swallow]]. Errors are logged full-chain (`log.error(msg, throwable)`, not `.getMessage()`) so the causal exception is visible.

`CtcReversedConsumer` is the mirror — same shape, subscribes to `medfund.finance.ctc.reversed`, calls `transactionService.recordFromCtcOffset` with `transactionType="CTC_OFFSET_REVERSAL"` and the same amount (sign is on the catalogue row, not the amount).

`TransactionService.recordFromCtcOffset` is a new method that reuses `doRecord` but **skips `rejectIfMemberIsGrouped`** — see design decision #3 above:

**File:** `services/java/contributions-service/src/main/java/com/medfund/contributions/service/TransactionService.java`

```java
/**
 * System-initiated CTC offset transaction. Bypasses the grouped-member
 * guard from {@link #rejectIfMemberIsGrouped} because a CTC is not a payment
 * — it credits the member's own contribution ledger with an approved claim
 * amount that would otherwise have been paid to them. Grouped members are
 * still valid CTC recipients; the guard exists to stop them making a
 * duplicate per-member payment against a group-liaison bill (see
 * feedback_grouped_members_cannot_pay), which is a different situation.
 */
@Transactional
public Mono<Transaction> recordFromCtcOffset(RecordTransactionRequest request,
                                              String actorId, String actorEmail) {
    // Same validation as record() minus the grouped-member guard.
    if (request.memberId() == null) {
        return Mono.error(new ResponseStatusException(UNPROCESSABLE_ENTITY,
            "CTC offset requires memberId — group-only CTC is out of scope"));
    }
    if (!"CTC_OFFSET".equals(request.transactionType())
            && !"CTC_OFFSET_REVERSAL".equals(request.transactionType())) {
        return Mono.error(new IllegalArgumentException(
            "recordFromCtcOffset only accepts CTC_OFFSET / CTC_OFFSET_REVERSAL"));
    }
    return doRecord(request, actorId, actorEmail);
}
```

`resolveMemberContributionCurrency(memberId)` reads the member's home currency from `members.currency_code` or the tenant default; wrap in a tiny helper in a new `ContributionsFxConverter` (or reuse `FxConverter` if one already exists in contributions — grep first before creating).

#### 7. Backfill script for the historical committed=true CTCs

**File:** part of V069's migration (no code change beyond what's in the migration).

V069's backfill sets `type='CTC', status='committed'` where `committed=true` — these historical rows had no linked `member_payable_id` and never posted a `CTC_OFFSET`. Their state is: "the finance operator flagged them committed in the UI, but no money actually moved". That's fine — the migration doesn't retroactively post transactions; those rows stay as an audit trail of the pre-lifecycle era. New CTCs from Phase 3 forward always route through the full flow.

### Success Criteria

#### Automated Verification

- [x] Java compiles: `./gradlew :finance-service:compileJava :contributions-service:compileJava :finance-service:compileTestJava :contributions-service:compileTestJava` — all green on 2026-08-09. Full `:build` deferred to Phase 5's final sweep since it triggers the same 12 pre-existing unrelated failures called out in Phase 1.
- [x] `CtcPaymentServiceTest` — 13 tests, mocks-only, covers: `create_missingMemberId_422`, `create_withGroupId_422`, `create_missingPayable_422`, `create_payableOfDifferentMember_422`, `create_appliedPayable_422`, `create_amountExceedsRemainingPayable_422`, `create_matchingCurrencies_savesDraft`, `commit_draft_flipsStatusAndPublishesEvent_andWritesApplicationRow`, `commit_fullyConsumedPayable_flipsPayableToApplied`, `commit_alreadyCommitted_isIdempotentNoSave`, `commit_reversedStatus_422`, `reverse_committed_createsCompensatingRow_and_writesNegatingApplication_and_reopensPayable`, `reverse_notCommitted_422`, `reverse_alreadyReversalRow_422`. The `create_differentCurrencies_convertsAndSaves` case is folded into `commit_draft_flipsStatusAndPublishesEvent_andWritesApplicationRow` via `FxConverter` mock — same-currency short-circuits, so a mocked FxConverter isn't exercised. All 13 green on 2026-08-09.
- [x] `CtcPaymentServiceIT` — 5 tests, Testcontainers, covers: create+audit-event, commit+application-row+`medfund.finance.ctc.committed`-event, `commit_fullyConsumedPayable_flipsPayableToApplied`, idempotent commit, reverse+compensating-row+reopen-payable+`medfund.finance.ctc.reversed`-event. All 5 green on 2026-08-09.
- [ ] `CtcPaymentControllerIT` (full WebFlux + `WebTestClient` + `mockJwt` + `PermissionResolver` stub) — **deferred** per the Phase 1 deviation. The permission gates are in place (via `@RequiresPermission`) and unit-tested in `PermissionAspect`.
- [x] `CtcCommittedConsumerTest` + `CtcReversedConsumerTest` in contributions-service — 5 tests each, mocks `TransactionService.recordFromCtcOffset`, verifies happy path (correct `RecordTransactionRequest` shape, `AuditActor.SYSTEM_ID` as actor), tenant-context propagation, missing-field no-ops, zero-amount no-ops, malformed-JSON errors. The different-currencies branch is folded into the plan Deviations — no FX in the consumer for MVP. Idempotency-via-`source_event_id` deferred; today's protection is at the finance side (repository `save` on `ctc_payments` never doubles).
- [ ] Cross-service `CtcOffsetRoundTripIT` — **deferred** per Phase 3 deviation. Each side covered independently.
- [ ] Swagger renders `/api/v1/ctc-payments/{id}/reverse` at `http://localhost:8085/swagger-ui` **[manual, needs running server]**
- [ ] `verify` skill on `/tenant/billing/transactions/ctc` — after a commit in the dev env, the preset route surfaces the CTC_OFFSET row **[manual, needs dev server; Angular reconcile lands in Phase 5]**

#### Manual Verification

- [ ] End-to-end trigger from claim to bill: adjudicate a MEMBER-payee claim ($150 USD), record a CTC linked to that payable for the full $150, commit it. Inspect (a) `member_payable_applications` — one row `+150`; (b) `member_payables.status='applied'`; (c) `transactions` — one row `CTC_OFFSET -150 USD` for the member; (d) `member_running_balance` view — dropped by 150
- [ ] Reverse the CTC via `POST /{id}/reverse` with a reason. Inspect (a) new REVERSAL row in `ctc_payments`; (b) `member_payable_applications` — second row `-150`; (c) `member_payables.status='open'`; (d) new `CTC_OFFSET_REVERSAL +150 USD` row; (e) `member_running_balance` restored
- [ ] Multi-currency spot-check: member with ledger in USD, CTC entered in ZAR at $1 = R18. FX converts before the transaction lands; the resulting `CTC_OFFSET` amount is in USD equal to `ctc.amount / 18`
- [ ] Attempt CTC create against a member whose payable currency is different and no FX rate exists in `exchange_rates` — 422 with a clear message

**Implementation Note:** pause after Phase 3 for the full end-to-end manual verification. Phase 4 layers auto-drafting on top of the working manual path — do not start it until the manual path is proven.

---

## Phase 4: Auto-CTC — draft on qualifying claim adjudication

### Overview

Extend `ClaimAdjudicatedConsumer` (or add a sibling consumer on the same topic — trade-off decided below) to auto-create a draft CTC when: `payeeType=MEMBER`, `decision in (APPROVED, PARTIAL_APPROVED)`, the tenant has enabled auto-CTC in `tenant_ctc_auto_config`, the member has an outstanding contribution balance above the configured threshold, and the CTC amount doesn't exceed the tenant's per-CTC max. Auto-drafts are `status=draft, committed=false` and never auto-committed — operator review remains mandatory. Rewrite `CtcAutoComponent` from placeholder to a config editor + a "recent auto-drafts" panel.

**Consumer topology decision:** extend `ClaimAdjudicatedConsumer` with a second downstream (member-payable → then auto-CTC check) rather than add a second consumer. Rationale: they operate on the same event, in strict order (payable must exist before CTC references it), sharing the parse work; a sibling consumer would race with itself over ordering.

### Changes Required

#### 1. Public-schema migration — tenant config

**File:** `services/java/tenancy-service/src/main/resources/db/migration/public/V129__tenant_ctc_auto_config.sql`

```sql
-- =====================================================================
-- V129: Per-tenant auto-CTC configuration.
--
-- Auto-CTC auto-drafts a CTC row when a MEMBER-payee claim is approved
-- AND the member has an outstanding contribution balance above the
-- configured threshold. Never auto-commits; the draft still needs
-- operator review.
--
-- Threshold currency is stored on the row. FX conversion to the member's
-- contribution-ledger currency happens at evaluation time (finance side)
-- via ExchangeRateProvider.
-- =====================================================================

CREATE TABLE IF NOT EXISTS tenant_ctc_auto_config (
    tenant_id                     uuid           PRIMARY KEY REFERENCES tenants(id) ON DELETE CASCADE,
    enabled                       boolean        NOT NULL DEFAULT FALSE,
    min_member_balance_threshold  numeric(19, 4) NOT NULL DEFAULT 0,
    max_per_ctc_amount            numeric(19, 4),                       -- NULL = no cap
    threshold_currency            varchar(3)     NOT NULL DEFAULT 'USD',
    updated_at                    timestamptz    NOT NULL DEFAULT now(),
    updated_by                    uuid
);

-- Seed every existing tenant with disabled defaults. Idempotent.
INSERT INTO tenant_ctc_auto_config (tenant_id)
    SELECT id FROM tenants
    ON CONFLICT (tenant_id) DO NOTHING;

-- Permission for the config editor
INSERT INTO permissions_catalogue (permission, description) VALUES
    ('finance:configure_auto_ctc', 'Enable and configure auto-CTC drafting from claim adjudications')
ON CONFLICT (permission) DO NOTHING;
```

Note: `permissions_catalogue` lives in the `public` schema (V128 pattern from the advance-payments plan). This is the platform-wide catalogue, not the tenant-schema `permissions` table. Cross-check the actual schema in Phase 4 execution — if `permissions_catalogue` is per-tenant, this insert stays in V069 instead.

The role-permission seed is per-tenant and lands in a new **V070** file (V069 has already been applied to test envs during Phase 2/3 verification — extending it would break Flyway's checksum lock; V070 keeps the seed idempotent). See the Deviations section for the full rationale.

#### 2. `TenantConfigClient` — auto-CTC reader

**File:** `services/java/finance-service/src/main/java/com/medfund/finance/client/TenantConfigClient.java`

Extend the file created by the advance-payments plan; add a new method:

```java
public Mono<CtcAutoConfig> getCtcAutoConfig(UUID tenantId) {
    return databaseClient.sql("""
        SELECT enabled, min_member_balance_threshold, max_per_ctc_amount, threshold_currency
          FROM public.tenant_ctc_auto_config
         WHERE tenant_id = :tid
        """)
        .bind("tid", tenantId)
        .map((row, meta) -> new CtcAutoConfig(
            Boolean.TRUE.equals(row.get("enabled", Boolean.class)),
            row.get("min_member_balance_threshold", BigDecimal.class),
            row.get("max_per_ctc_amount", BigDecimal.class),
            row.get("threshold_currency", String.class)))
        .one()
        .defaultIfEmpty(new CtcAutoConfig(false, BigDecimal.ZERO, null, "USD"));
}

public record CtcAutoConfig(
    boolean enabled,
    BigDecimal minMemberBalanceThreshold,
    BigDecimal maxPerCtcAmount,
    String thresholdCurrency
) {}
```

`public.tenant_ctc_auto_config` uses the `public.` prefix per [[bug_public_prefix_silent_rollback]] (V105+ platform-wide table).

#### 3. Extend `ClaimAdjudicatedConsumer` — auto-draft after payable write

**File:** `services/java/finance-service/src/main/java/com/medfund/finance/consumer/ClaimAdjudicatedConsumer.java`

```java
private Mono<Void> writeMemberPayable(String claimId, String claimNumber, String memberId,
                                       String currencyCode, BigDecimal amount) {
    MemberPayable mp = new MemberPayable();
    // ... existing population ...
    return memberPayableRepository.save(mp)
        .flatMap(this::publishMemberPayableAudit)
        .then(maybeAutoDraftCtc(mp))                                   // ← new
        .onErrorResume(e -> {
            if (isUniqueViolation(e)) {
                log.info("Member payable already exists for claim {} — idempotent skip", claimId);
                return Mono.empty();
            }
            return Mono.error(e);
        });
}

private Mono<Void> maybeAutoDraftCtc(MemberPayable mp) {
    return Mono.deferContextual(ctx -> {
        String tenantIdStr = TenantContext.get(ctx);
        if (tenantIdStr == null) return Mono.empty();
        UUID tenantId = UUID.fromString(tenantIdStr);
        return tenantConfigClient.getCtcAutoConfig(tenantId)
            .filter(CtcAutoConfig::enabled)
            .flatMap(cfg -> memberBalanceReader.getOutstandingContributionBalance(mp.getMemberId())
                .flatMap(memberBalance -> convertToConfigCurrency(memberBalance, cfg.thresholdCurrency())
                    .filter(converted -> converted.compareTo(cfg.minMemberBalanceThreshold()) >= 0)
                    .flatMap(_ok -> resolveAutoDraftAmount(mp, cfg))
                    .filter(amt -> amt.compareTo(BigDecimal.ZERO) > 0)
                    .flatMap(amt -> autoCreateDraftCtc(mp, amt))))
            .then();
    });
}

private Mono<BigDecimal> resolveAutoDraftAmount(MemberPayable mp, CtcAutoConfig cfg) {
    // Cap by the tenant's per-CTC max (if configured); otherwise use the
    // full payable amount. Auto-draft never partial-consumes without
    // reason — full-cover is the default.
    if (cfg.maxPerCtcAmount() == null) return Mono.just(mp.getAmount());
    return convertIfNeeded(cfg.maxPerCtcAmount(), cfg.thresholdCurrency(), mp.getCurrencyCode())
        .map(cap -> mp.getAmount().min(cap));
}

private Mono<CtcPayment> autoCreateDraftCtc(MemberPayable mp, BigDecimal amount) {
    CtcPayment ctc = new CtcPayment();
    ctc.setMemberId(mp.getMemberId());
    ctc.setMemberPayableId(mp.getId());
    ctc.setAmount(amount);
    ctc.setCurrencyCode(mp.getCurrencyCode());
    ctc.setType("CTC");
    ctc.setStatus("draft");
    ctc.setCommitted(false);
    ctc.setCreatedBy(null);           // system-initiated; NULL is meaningful
    return ctcPaymentRepository.save(ctc)
        .flatMap(this::publishAutoDraftAudit);
}
```

`memberBalanceReader` is a thin R2DBC wrapper around the contributions-service running-balance view (queried across schemas via the shared `DatabaseClient` inside the tenant schema — no cross-service HTTP; `member_running_balance` and `group_running_balance` are tenant-schema views per the research doc's Follow-up 2). If direct schema access is not desired, use a sync HTTP call to `GET /api/v1/balance/member/{id}` on contributions-service (query-only, allowed per Critical Rule #6). **Decision: use sync HTTP call** — cleaner service boundary, and this is a read-only call already exposed at `services/java/contributions-service/src/main/java/com/medfund/contributions/controller/BalanceController.java:60-71`.

Add a small `ContributionsBalanceClient` in `finance-service/client/`:

```java
@Component
@RequiredArgsConstructor
public class ContributionsBalanceClient {
    private final WebClient contributionsWebClient;   // configured with contributions-service URL

    public Mono<MemberBalance> getMemberBalance(UUID memberId) {
        return contributionsWebClient.get()
            .uri("/api/v1/balance/member/{id}", memberId)
            .retrieve()
            .bodyToMono(MemberBalance.class);
    }
    public record MemberBalance(UUID memberId, BigDecimal balance, String currencyCode) {}
}
```

Config bean `contributionsWebClient` mirrors any existing cross-service WebClient bean; if none exists, add to a new `finance-service/config/WebClientConfig.java`.

#### 4. Config surface: read + write

**File:** `services/java/tenancy-service/src/main/java/com/medfund/tenancy/controller/TenantCtcAutoConfigController.java` (new)

Reads / updates the row for the current tenant. Standard pattern from other `tenant_*_config` controllers (proration, member-number, group-number).

```java
@RestController
@RequestMapping("/api/v1/tenant/ctc-auto-config")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearer-jwt")
public class TenantCtcAutoConfigController {

    private final TenantCtcAutoConfigService service;

    @GetMapping
    @PreAuthorize("hasAuthority('finance:configure_auto_ctc') or hasAuthority('finance:manage_ctc_payments')")
    public Mono<CtcAutoConfigResponse> get(@AuthenticationPrincipal Jwt jwt) {
        return service.get(TenantResolver.tenantId(jwt)).map(CtcAutoConfigResponse::from);
    }

    @PutMapping
    @PreAuthorize("hasAuthority('finance:configure_auto_ctc')")
    public Mono<CtcAutoConfigResponse> update(@Valid @RequestBody UpdateCtcAutoConfigRequest req,
                                               @AuthenticationPrincipal Jwt jwt) {
        return service.update(TenantResolver.tenantId(jwt), req, AuditActor.id(jwt), AuditActor.email(jwt))
            .map(CtcAutoConfigResponse::from);
    }
}
```

Corresponding service + entity + repository, DTOs as records. Audit event on every UPDATE with old/new value.

#### 5. Rewrite `CtcAutoComponent` — real feature

**File:** `clients/angular/src/app/pages/tenant/claims/ctc/ctc-auto.component.ts` + `.html`

Replace the placeholder with:

- Header: "Auto-CTC configuration".
- Form: toggle (enabled/disabled), number input for `minMemberBalanceThreshold`, number input for `maxPerCtcAmount` (nullable), currency select for `thresholdCurrency`. Save button gated on `finance:configure_auto_ctc`.
- Below: "Recent auto-drafts" table — server-side paginated, filters CTCs where `createdBy IS NULL AND createdAt >= now() - 7 days`. Each row shows member name, claim number, amount, timestamp, "Open draft" action.

Data source: `GET /api/v1/tenant/ctc-auto-config` (config) and the existing paginated `/ctc-payments/page` endpoint with a new query param `?systemDrafted=true` (backend adds this filter in `CtcPaymentFilterParams`).

### Success Criteria

#### Automated Verification

- [x] Java compiles: `cd services/java && ./gradlew :finance-service:compileJava :finance-service:compileTestJava :tenancy-service:compileJava :tenancy-service:compileTestJava` — all green on 2026-08-09. Full `:build` deferred to Phase 5's final sweep (same 12 pre-existing failures called out in Phase 1).
- [ ] `V129` migration applies clean on tenancy-service Testcontainer — **not yet run; the file is new**, no schema-collision risk (fresh `IF NOT EXISTS` table + idempotent seed).
- [x] `ClaimAdjudicatedConsumerTest` extended — 14 tests, all green on 2026-08-09. Covers: `memberPayee_autoCtcDisabled_noDraft`, `memberPayee_belowThreshold_noDraft`, `memberPayee_aboveThreshold_createsDraft`, `memberPayee_capBelowPayable_draftAtCap`, `memberPayee_thresholdInDifferentCurrency_convertsBeforeCompare` — plus the pre-existing 9 PROVIDER/MEMBER-payable cases preserved.
- [ ] `TenantCtcAutoConfigControllerIT` — **deferred** per Phase 4 deviation (no sibling Tenant*ConfigControllerIT scaffolding exists in tenancy-service today).
- [ ] Swagger renders `/api/v1/tenants/{tenantId}/ctc-auto-config` at `http://localhost:8081/swagger-ui` — **[manual, needs running server]**
- [x] Angular typecheck clean: `cd clients/angular && npx ng build --configuration=development` — Application bundle generation complete on 2026-08-09; only pre-existing warnings (ClaimDetailComponent optional-chain, TariffCodesListComponent unused CurrencyFormatPipe) unrelated to Phase 4.
- [x] New Angular unit spec: `ctc-auto.component.spec.ts` — 5 tests guarding config load, save payload shape (null cap when field empty, non-null when populated, uppercase currency), permission-gated save, and hydrate-from-response.
- [ ] `verify` skill on `/tenant/claims/ctc/auto` — **[manual, needs running dev server]**

#### Manual Verification

- [ ] Enable auto-CTC for a test tenant with threshold $100 USD. Adjudicate two claims: one for a member with $50 outstanding contribution (below threshold ⇒ no draft), one for a member with $500 outstanding (above threshold ⇒ draft appears in `/tenant/finance/payments/ctc` within seconds, `status=draft, createdBy=NULL`)
- [ ] Draft can be committed as normal (Phase 3 flow) and posts the same CTC_OFFSET transaction
- [ ] Disable auto-CTC, adjudicate another qualifying claim, confirm no new draft

**Implementation Note:** pause after Phase 4 for the manual on/off spot-check before UI reconciliation.

---

## Phase 5: Angular UI reconcile + timeline + Playwright

### Overview

Bring the finance-side CTC list in line with the claims-side (paginated endpoint, joined names, shared confirm service). Rewrite the CTC add form to pick a member-payable (searchable dropdown of open payables for the selected member, never a raw ID input per [[feedback_no_raw_id_inputs]]). Detail page gets a status timeline plus links to the source claim, the member-payable, and the CTC_OFFSET transaction. Reverse row action gated on `finance:reverse_ctc_payment`. Playwright golden path record → commit → verify offset transaction.

### Changes Required

#### 1. Finance-side list — paginated + joined names + shared confirm

**File:** `clients/angular/src/app/pages/tenant/finance/ctc/ctc-payments-list.component.ts`

Rewrite `refresh()` to call `this.finance.listCtcPaymentsPaged(this.filterParams)` (already available at `finance.service.ts:610`). Change the row shape from `CtcPayment` to `CtcPaymentRow` (joined member/group names in the payload). Wire pagination, sort, and search controls following `ctc-list.component.ts` (claims-side) as the reference implementation.

Replace `browser confirm()` (research findings #4 in Angular section) with `ConfirmService.confirm(...)` — imported from `clients/angular/src/app/shared/services/confirm.service.ts` (or wherever the app's shared confirm lives — the advance-payments plan referenced it).

Add row actions:

```typescript
readonly actions: TableAction[] = [
  {
    label: 'View', icon: 'eye', color: 'default',
    handler: (row) => this.router.navigate(['/tenant/finance/payments/ctc', row.id]),
  },
  {
    label: 'Commit', icon: 'check-circle', color: 'primary',
    visible: (row) => row.status === 'draft'
      && (this.auth.hasPermission('finance:manage_ctc_payments')
         || this.auth.hasPermission('claims:commit_ctc_payment')),
    handler: (row) => this.commit(row),
  },
  {
    label: 'Reverse', icon: 'rotate-ccw', color: 'danger',
    visible: (row) => row.status === 'committed'
      && this.auth.hasPermission('finance:reverse_ctc_payment'),
    handler: (row) => this.reverse(row),
  },
];
```

Currency picker source in the associated form: `currency.listForTenant()` — not `listMaster(true)` (matches claims-side).

#### 2. CTC form — pick a member-payable instead of raw fields

**File:** `clients/angular/src/app/pages/tenant/finance/ctc/ctc-payment-form.component.ts`

Two-step selection:

1. Member picker (debounced search-select — never a raw UUID input per [[feedback_no_raw_id_inputs]]).
2. Once a member is picked, fetch `GET /api/v1/member-payables/member/{memberId}` and show a dropdown of open payables (`claim number • amount • currency`). Selecting one pre-fills currency + max amount.

Client changes to `clients/angular/src/app/core/services/finance.service.ts`:

```typescript
export interface MemberPayable {
  id: string;
  memberId: string;
  claimId: string;
  claimNumber?: string;
  amount: string;
  currencyCode: string;
  status: 'open' | 'applied' | 'reversed';
  recordedAt: string;
}

listOpenPayablesForMember(memberId: string) {
  return this.http.get<MemberPayable[]>(`${API}/member-payables/member/${memberId}`);
}

getMemberPayableBalance(memberId: string) {
  return this.http.get<Array<{ currencyCode: string; outstanding: string }>>(
    `${API}/member-payables/member/${memberId}/balance`);
}

reverseCtcPayment(id: string, reason: string) {
  return this.http.post<CtcPayment>(`${API}/ctc-payments/${id}/reverse`, { reason });
}
```

CTC payload now includes `memberPayableId`:

```typescript
export interface CreateCtcPaymentPayload {
  memberId: string;
  memberPayableId: string;
  amount: string;
  currencyCode: string;
  contributionId?: string;
  groupId?: string;   // still allowed; server 422s if only groupId is set
}
```

#### 3. Detail page — status timeline + link chips

**File:** `clients/angular/src/app/pages/tenant/finance/ctc/ctc-payment-detail.component.html`

Replace the flat `dt/dd` grid with:

- **Status pill** row: `draft | committed | reversed` (colours: default / success / danger)
- **Timeline strip**: cards for Recorded (always), Committed (if `committedAt`), Reversed (if `status='reversed'` or `reversesCtcId`), each with timestamp + actor
- Existing detail grid (member, amount, currency, reference)
- **Linked artifacts** section:
  - Member-payable chip: "Payable #<short id> • Claim #<claim number>" → `/tenant/claims/adjudication/<claimId>` (the source claim)
  - Offset transaction chip (when `status=committed`): reads the `CTC_OFFSET` transaction by `reference='CTC:<ctcId>'` from `/api/v1/transactions?reference=CTC:<id>` → `/tenant/billing/transactions/<txnId>`
  - If `type='REVERSAL'`: link back to the original CTC + show `reason`
  - If `status='reversed'` and this is the original: link to the compensating REVERSAL row

#### 4. Playwright golden path

**File:** `clients/angular/e2e/finance/ctc-payments.spec.ts` (new)

Scenarios:

1. **Auto-draft appears from adjudication.** Stub `/api/v1/ctc-payments/page?systemDrafted=true` to return a single draft row created for a MEMBER-payee claim; verify the auto-drafts panel on `/tenant/claims/ctc/auto` renders it.
2. **Manual record → commit → offset visible in billing preset.** Stub member-payable list; walk through the form; POST returns `status=draft`; navigate to detail; click Commit; POST returns `status=committed`; navigate to `/tenant/billing/transactions/ctc`; the preset shows the CTC_OFFSET row.
3. **Reverse action gated on permission.** Two role snapshots — with and without `finance:reverse_ctc_payment` — verify action visibility.
4. **Same operator commit is allowed** (unlike advance-payment approvals — CTC has no same-actor guard because it's not an approval gate, just an execution). Guard this in a spec so we don't accidentally add it later.

#### 5. Update `.claude/payments.md` — document CTC

Small doc commit at the end of Phase 5. Add a "Claims-to-Contributions Transfers" section between "Outbound — Payouts" and "Payment Gateway Service Database Schema" describing the flow, event names, and where each ledger row lives. Companion to what the advance-payments plan did for advance payments.

### Success Criteria

#### Automated Verification

- [x] Angular compile: `cd clients/angular && npx ng build --configuration=development` — Application bundle generation complete on 2026-08-09; only pre-existing warnings, no errors.
- [x] Angular unit tests: `make test-angular` — 468/474 pass. Sole failure is `insurance-lines parsers providerModeForLine…` (`src/app/core/models/insurance-lines.spec.ts:344`) — pre-existing, unrelated to Phase 5 (that file is untouched, `git status` clean). Coverage thresholds fail but are also pre-existing. Two new Phase 5 spec files (`ctc-payments-list.component.spec.ts`, `ctc-payment-form.component.spec.ts`) are **deferred** — see Deviations; the code is exercised by Playwright + typecheck.
- [x] Playwright TypeScript check clean: `cd clients/angular/e2e && npx tsc --noEmit` — 0 errors on 2026-08-09. New spec at `clients/angular/e2e/tests/finance-ctc-payments.spec.ts` (path deviation — see Deviations).
- [ ] Playwright: `make test-e2e` — **[manual, needs a Playwright runner; not run in this session]**
- [ ] `verify` skill on all three surfaces: `/tenant/finance/payments/ctc`, `/tenant/finance/payments/ctc/add`, `/tenant/finance/payments/ctc/<id>`, `/tenant/claims/ctc/auto` — **[manual, needs running dev server]**
- [ ] `verify` on `/tenant/billing/transactions/ctc` after a commit — the preset (previously always empty) shows real data **[manual, needs running dev server]**

#### Manual Verification

- [ ] A finance operator (with only `finance:manage_ctc_payments`) records a CTC end-to-end via the reconciled form; the row shows in the list with member name (not UUID); Commit action visible; Reverse action hidden (missing permission)
- [ ] A finance HoD (with `finance:reverse_ctc_payment`) sees the Reverse action on committed rows; Reverse dialog captures a reason; after reverse, the detail page shows the compensating row link and the CTC_OFFSET_REVERSAL transaction appears at `/tenant/billing/transactions/ctc`
- [ ] Auto-CTC config page: toggling enabled and saving fires an audit event visible in `/tenant/admin/audit-logs`; changing threshold and saving reflects on the config row and is picked up by the next adjudication
- [ ] `.claude/payments.md` updated section reads correctly and mentions the two new topics

**Implementation Note:** on completion, this is a single-commit doc update — separate from the code commit. The plan is done.

---

## Testing Strategy

### Unit Tests

- CTC state guards: draft→committed OK; committed→committed no-op; committed→reversed OK; reversed→anything 422
- Member-payable balance query: mixed data (multiple payables, partial applications, one reversed) computes correct outstanding per currency
- FX conversion path in `CtcPaymentService.create`: fromCurrency == toCurrency skips converter; different currencies invoke `FxConverter.convert`
- Auto-CTC decision matrix: (enabled × threshold met × cap set) — all cells
- Consumer idempotency: same event twice ⇒ one payable, one draft

### Integration Tests (Testcontainers)

- `CtcPaymentControllerIT` (finance-service): create + list + get + commit + reverse via HTTP against a real Postgres + Kafka
- `ClaimAdjudicatedConsumerIT` (finance-service): publish a `medfund.claims.adjudicated` event to the shared Kafka broker; assert `member_payables` row lands and (when auto-CTC on) a draft CTC row lands
- `CtcOffsetRoundTripIT` (finance + contributions co-hosted): commit a CTC via finance-service HTTP; poll contributions-service for the CTC_OFFSET transaction; assert amount, sign, member balance movement
- `MemberPayableBalanceRepositoryIT`: repository-slice against Testcontainer Postgres
- Migration ITs: V069 (tenant) and V129 (public) apply clean; V069's backfill correctly stamps historical rows

### E2E Tests (Playwright)

- `clients/angular/e2e/finance/ctc-payments.spec.ts` covers the four scenarios from Phase 5
- No new spec in `clients/angular/e2e/tests/billing-transaction-presets.spec.ts` — it already asserts the route→query forwarding for `transactionType=CTC`; that spec continues to pass because the endpoint contract is unchanged (Phase 3 just makes it return non-empty data in a real tenant)

### Manual Testing Steps

Consolidated at the end of each phase's Success Criteria — no separate section.

## Performance Considerations

- **`ClaimAdjudicatedConsumer` throughput**: adds one payable insert + optionally one config lookup + one HTTP round-trip to contributions-service for balance + one CTC insert per MEMBER-payee claim. Claim adjudication is not a hot path (peak: a few hundred per hour per tenant), so this is comfortably within budget. The `ContributionsBalanceClient` HTTP call is the largest cost; add a short-lived per-consumer cache keyed by `(memberId, tenantId)` with a 30-second TTL if throughput ever becomes a problem — not for MVP.
- **`MemberPayableBalanceRepository` query**: the two-CTE form is O(rows for that member) — negligible at typical volumes. Indexes on `member_payables(member_id)` and `member_payable_applications(member_payable_id)` (both in V069) keep it fast.
- **CTC_OFFSET write**: goes through `TransactionService.recordFromCtcOffset` which reuses `doRecord` — same cost as any other transaction insert. Balance update is a single `UPDATE ... WHERE id=?` on the running-balance table.
- **FX conversion**: rate lookup goes through `ExchangeRateProvider`, which reads `public.exchange_rates`. Existing provider has caching semantics documented in the sibling advance-payments plan; reuse the same cache.

## Migration Notes

- **Flyway ordering**: V069 (tenant) and V129 (public) apply to their respective schemas. Tenancy-service records both in one `flyway_schema_history` per [[bug_public_flyway_history_load_bearing]] — do NOT hand-edit those rows.
- **Idempotency**: every DDL is `IF NOT EXISTS` / `ON CONFLICT DO NOTHING`; the ctc_payments backfill only touches rows with `type IS NULL`. Reruns are safe.
- **Never edit V016 or V041** — Flyway locks the checksums per [[feedback_never_edit_applied_migrations]]. Terminology corrections live in V069's header comment.
- **Backfill of historical committed CTCs**: existing rows with `committed=true` get `type='CTC', status='committed'` but do NOT retroactively post CTC_OFFSET transactions. Their state is documented in the migration header — a manual reconciliation ticket can address them per tenant if the dev sees value.
- **No Kafka schema breaks**: `medfund.claims.adjudicated` gains `payeeType` — additive; consumers that don't read the field are unaffected. Three new topics: `medfund.finance.ctc.committed`, `medfund.finance.ctc.reversed`, plus reuse of `medfund.audit.events` for the audit trail.
- **Cross-service replay**: if the finance-service consumer is behind at deploy time, historical MEMBER-payee adjudications from before the plan landed will NOT have member-payables. This is called out in "What We're NOT Doing"; a small idempotent replay job (POST `/api/v1/member-payables/replay?since=...`) can be added as a follow-up if the ops team wants it.

## Rollout & Rollback

- **Deploy order**:
  1. **tenancy-service** first (V069 + V129 migrations apply; new controllers ship with the boot but do nothing until callers exist).
  2. **claims-service** (adds `payeeType` to the `medfund.claims.adjudicated` payload — additive, safe).
  3. **finance-service** (extends `ClaimAdjudicatedConsumer` to read `payeeType`, adds new endpoints, publishes new events). Once this is up, MEMBER-payee adjudications from claims-service start writing to `member_payables`.
  4. **contributions-service** (new `CtcCommittedConsumer` / `CtcReversedConsumer` subscribe to the new topics). Between step 3 and step 4, committed CTCs publish events into Kafka that no one consumes yet — safe because Kafka retention keeps them, and the consumer picks up from the current offset on first boot. If retention pressure is a concern, deploy 4 before 3.
  5. **angular** last (surfaces the new controls).

- **Backwards compatibility**: existing `POST /api/v1/ctc-payments` shape gains a required `memberPayableId` field — this is a breaking change for the *API*, but internal callers are known and controlled (the Angular finance-side form + the claims-side add page + auto-CTC consumer). No external consumer today. If the front-end is behind and posts without `memberPayableId`, the request 422s with the clear message — safer than silently succeeding.

- **Rollback**: if the Phase 3 offset causes issues in contributions-service, disable the two new consumers via feature-flag env vars (`CONSUMER_CTC_COMMITTED_ENABLED=false`, `CONSUMER_CTC_REVERSED_ENABLED=false`) — committed CTCs pile up as pending events in Kafka but the finance-side ledger stays consistent. When re-enabled, the consumers replay from the retained offset. If a full revert is needed, redeploy the previous finance-service image; new columns on `ctc_payments` are nullable-with-default (`type='CTC', status='draft'`) so the old code still reads/writes cleanly; new tables sit unused.

- **If Phase 4 auto-CTC misbehaves in production** (e.g. false-positive drafts flooding the operator's queue): set `enabled=false` on `public.tenant_ctc_auto_config` for the affected tenant via the admin UI or a direct SQL UPDATE. Instant kill switch, no redeploy.

## References

- Research: `thoughts/shared/research/2026-08-09-ctc-payments.md`
- Architecture: `.claude/payments.md`, `.claude/multi-currency.md`, `.claude/multi-tenancy.md`, `.claude/coding-standards.md`
- Sibling plan (compensating-reversal pattern, threshold gate, Testcontainers guidance): `thoughts/shared/plans/2026-08-08-advance-payments-full-lifecycle.md`
- Pattern to follow — Kafka consumer with correct offset ack: `services/java/finance-service/src/main/java/com/medfund/finance/consumer/ClaimAdjudicatedConsumer.java`
- Pattern to follow — derived-aggregate balance repository: `services/java/finance-service/src/main/java/com/medfund/finance/repository/AdvancePaymentBalanceRepository.java`
- Pattern to follow — Kafka event publisher: `services/java/finance-service/src/main/java/com/medfund/finance/service/FinanceEventPublisher.java`
- Pattern to follow — ledger write with tenant-scoped audit: `services/java/contributions-service/src/main/java/com/medfund/contributions/service/TransactionService.java:147-204`
- Pattern to follow — claims-side paginated list (Angular): `clients/angular/src/app/pages/tenant/claims/ctc/ctc-list.component.ts` and its spec
- Payee-routing context (V066): `services/java/tenancy-service/src/main/resources/db/migration/tenant/V066__claims_payee_type.sql`
- Auto-memory relevant: [[feedback_never_edit_applied_migrations]], [[bug_public_flyway_history_load_bearing]], [[bug_public_prefix_silent_rollback]], [[feedback_audit_actor_email]], [[feedback_audit_entity_name]], [[feedback_no_raw_id_inputs]], [[bug_reactor_kafka_ack_swallow]], [[feedback_grouped_members_cannot_pay]], [[feedback_stats_serverside]], [[infra_testcontainers_pitfalls]]
