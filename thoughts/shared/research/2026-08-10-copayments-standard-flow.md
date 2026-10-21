---
date: 2026-08-10T22:05:55+02:00
researcher: Methuseli
git_commit: 0a1609d72451938c1e12346b63f7f6595122b8e5
branch: rename-adjustments-to-notes
repository: medfund
topic: "Copayments — existing surface, gaps, and a standard flow for insurance"
tags: [research, codebase, copayment, claims, adjudication, rules-engine, finance, angular]
status: grilled
last_updated: 2026-08-10
last_updated_by: Methuseli
grilled: true
grilled_at: 2026-08-10
decisions: G1–G18 (see "Decisions from grilling")
---

# Research: Copayments — existing surface, gaps, and a standard flow for insurance

**Date**: 2026-08-10T22:05:55+02:00 · **Researcher**: Methuseli · **Commit**: 0a1609d · **Branch**: rename-adjustments-to-notes

> **Status — grilled 2026-08-10.** Every design fork in the "Proposed Standard Flow" below has been
> settled. Superseded text is struck through with the replacement inline; the load-bearing decisions
> are consolidated in **[Decisions from grilling (G1–G18)](#decisions-from-grilling-g1g18)** at the
> bottom of the doc. The plan file (`create-plan`) should read the decisions section first, then use
> the Findings section for cited state-of-the-code. The Findings section was verified against HEAD
> and is unchanged.

## Research Question

Where does copayment live in InsureFlow today, what gaps sit between the existing surface and a
production copayment flow, and — informed by industry standards (US 270/271 + EOB, UK/DE statutory, and
the Zimbabwean AHFOZ-shortfall reality) — what does a standard copayment flow look like that this
platform should implement?

## Summary

There is more copayment scaffolding in the repo than the two architecture docs suggest, but it is
**disconnected** — the pieces do not add up to a working flow.

- **Rules engine** already ships a `CO_PAYMENT` rule category, an `APPLY_COPAY` action, per-tenant JSON
  rule templates, and `ClaimFact.applyPercentageCopay` / `applyFixedCopay` methods that emit
  `RuleResult(type="APPLY_COPAY", adjustedAmount=…)`.
- **Claims service** has a `CoPaymentService` that computes `claimed − tariffAllowed` per line and a
  `Quotation.coPaymentAmount` column. `CoPaymentService` is only wired into `QuotationService.review`
  as a **manual, review-time input** — it is never called during the six/seven-stage adjudication
  pipeline, and its output does not reduce `Claim.approvedAmount`.
- **Rules-engine copay results are ignored by the decision engine.** `AdjudicationDecisionEngine`
  produces a single `approvedAmount` and never subtracts the `APPLY_COPAY` result amount that
  `ClaimFact` recorded in Stage 7.
- **No benefit-level copay configuration.** `SchemeBenefit` carries annual/daily/event limits, age
  gates, and usage mode — but no `copay_amount`, `copay_percentage`, `coinsurance_rate`, `deductible`,
  or `out_of_pocket_max`. All three US cost-share primitives (copay, coinsurance, deductible) and OOP
  max are absent from the data model.
- **No member liability tracking, no EOB.** `MemberPayable` records money the *fund owes the member*
  (reimbursement of an out-of-pocket claim), not the member's copay debt to the fund. There is no
  invoice/statement/EOB emission that itemises the member's cost share.
- **Angular has a `/tenant/finance/copayments` list already** — but it is a filtered view of the
  contributions-service `transactions` table (`transactionType='COPAYMENT'`), i.e. cash receipts
  someone recorded manually. It is not populated by the claims pipeline.

The result: today, "copayment" in InsureFlow is a **manual accounting artefact** (an operations user
types a copay amount at quotation review, or records a receipt in Finance). It is not a computed
consequence of adjudication, and it does not appear on a member statement.

The standard flow the platform should adopt (Section "Proposed Standard Flow" below) has three
concurrent paths — point-of-service quote, adjudication-time computation, and post-adjudication member
statement — and requires benefit-level cost-share configuration, an adjudication decision engine that
consumes `APPLY_COPAY` results, and a member liability ledger. For the Zimbabwe/AHFOZ market the
platform must also model the **shortfall** (billed − tariff) as a distinct kind of member cost,
because that is how the market actually behaves regardless of what the benefit design says.

## Findings

### 1. Rules engine — `CO_PAYMENT` category exists end-to-end but its output is inert

The rules engine ships a full path for authoring copay rules:

- Category enum: `services/java/rules-engine/src/main/java/com/medfund/rules/model/RuleCategory.java:38` —
  `CO_PAYMENT`, described as *"Percentage / fixed co-pays and out-of-pocket caps."*
- Action enum: `services/java/rules-engine/src/main/java/com/medfund/rules/model/ActionType.java:20` —
  `APPLY_COPAY`, described as *"Apply a percentage or fixed-amount co-pay to a claim line."*
- Action value convention: `services/java/rules-engine/src/main/java/com/medfund/rules/model/RuleAction.java:12` —
  `value="10"` → 10 %, `value="FIXED:25"` → 25 currency-units.
- Compiler dispatch: `services/java/rules-engine/src/main/java/com/medfund/rules/compiler/DrlCompiler.java:170`
  includes `APPLY_COPAY` in the supported action switch.
- Emitter: `services/java/rules-engine/src/main/java/com/medfund/rules/compiler/ActionEmitters.java:90-104` —
  `ApplyCopayEmitter` parses the value and calls either `claim.applyFixedCopay(…)` or
  `claim.applyPercentageCopay(…)`.
- Fact methods:
  `services/java/rules-engine/src/main/java/com/medfund/rules/fact/ClaimFact.java:75-84`
  — both methods **compute** a copay amount and add a `RuleResult(type="APPLY_COPAY", adjustedAmount=copay)`.
- Templates ship out of the box:
  `services/java/rules-engine/src/main/java/com/medfund/rules/template/providers/CoPaymentTemplates.java:17-34`
  — `CP01` (20 % copay when `provider.inNetwork == false`) and `CP02` (fixed 25.00 copay when
  `claim.benefitCategory == OPTICAL`).
- Registered:
  `services/java/rules-engine/src/test/java/com/medfund/rules/service/RuleTemplateServiceTest.java:55`.

**But** the results these rules emit are never consumed by the claims-service decision engine.
`AdjudicationPipeline` runs tenant rules in Stage 7
(`services/java/claims-service/src/main/java/com/medfund/claims/service/AdjudicationPipeline.java:828-857`)
and forwards `REJECT` / `FLAG_FOR_REVIEW` / `WARN` / `APPLY_COPAY` results into the stage-result set,
but `AdjudicationDecisionEngine.decide` only branches on hard-stage failure, AI fraud/duplicate
signals, and soft-stage flags — it does not read `APPLY_COPAY` amounts and does not subtract them from
`approvedAmount`
(`services/java/claims-service/src/main/java/com/medfund/claims/service/AdjudicationDecisionEngine.java:36-165`).

The rule fires; the auditor sees the fact in the stage-result JSON; nothing changes on the claim.

### 2. `CoPaymentService` implements shortfall math but is only used by `QuotationService`

`services/java/claims-service/src/main/java/com/medfund/claims/service/CoPaymentService.java:28-77`
computes, per claim line:

```
copay = max(0, claimed - tariffAllowed)
approved = min(claimed, tariffAllowed)
```

and aggregates into `CoPaymentResult(totalApproved, totalCoPayment, lineDetails)`. When no tariff is
found for the code, the whole claimed amount becomes copay
(`CoPaymentService.java:32-35` — *"Tariff not found — full amount is co-payment"*).

This is the **Zimbabwean shortfall** model exactly (billed − AHFOZ rate = member liability). But it is
consumed only from `QuotationService.review`:

- Injection: `services/java/claims-service/src/main/java/com/medfund/claims/service/QuotationService.java:29,33,36`.
- Method: `QuotationService.java:99-125` — `review(id, coveredAmount, coPaymentAmount, notes, …)`
  accepts `coPaymentAmount` as a **manual parameter from the reviewer**, sets it on the entity, and
  audits it. The service does not call `CoPaymentService.calculate` — the reviewer must know the
  number.
- Endpoint: `services/java/claims-service/src/main/java/com/medfund/claims/controller/QuotationController.java:85-88`
  — `@RequestParam BigDecimal coPaymentAmount`.
- Entity storage:
  `services/java/claims-service/src/main/java/com/medfund/claims/entity/Quotation.java:46-47` —
  `co_payment_amount` column.
- DTO: `services/java/claims-service/src/main/java/com/medfund/claims/dto/QuotationResponse.java:20,34`.

So `CoPaymentService.calculate` exists, works, and is never called by any production code path. The
adjudication pipeline does not invoke it. Only the reviewer's manual input reaches a persisted field.

### 3. Adjudication amount fields have no member-share breakdown

The claim entity and pipeline output carry only one net amount, not a cost-share split.

- `Claim.claimedAmount`, `Claim.approvedAmount`, `Claim.paidAmount`
  (`services/java/claims-service/src/main/java/com/medfund/claims/entity/Claim.java:66-74`) — three
  amounts, none of which is copay/coinsurance/deductible.
- `ClaimLine.claimedAmount`, `ClaimLine.approvedAmount`
  (`services/java/claims-service/src/main/java/com/medfund/claims/entity/ClaimLine.java:31-35`) — the
  only per-line amounts. `approvedAmount` is mutated in place by `MODIFIER_ADJUSTMENT` rules but never
  by copay rules.
- `AdjudicationResult` (`services/java/claims-service/src/main/java/com/medfund/claims/dto/AdjudicationResult.java:6-28`)
  — exposes `decision`, `approvedAmount`, `rejectionCode`, `rejectionNotes`, `stageResults`,
  `aiSignals`. There is no `memberShare`, `copay`, `coinsurance`, `deductibleApplied`, `planPaid`, or
  `notCovered` field.
- `ClaimResponse` (`services/java/claims-service/src/main/java/com/medfund/claims/dto/ClaimResponse.java:13-46`)
  mirrors the entity; same absence.

Anything you want to render on an EOB does not exist on the wire.

### 4. Benefit configuration has no cost-share columns

`SchemeBenefit`
(`services/java/contributions-service/src/main/java/com/medfund/contributions/entity/SchemeBenefit.java:14-135`)
today exposes:

- `annualLimit`, `dailyLimit`, `eventLimit`, `currencyCode`
- `waitingPeriodDays`, `minAge`, `maxAge` (V051)
- `cashClaimAllowed` (V051)
- `usageMode` — `RUNNING_BALANCE | ONE_TIME_PER_BENEFICIARY | ONE_TIME_PER_PERIOD | PER_EVENT_COUNTER | NO_TRACKING` (V061)

No column named `copay_amount`, `copay_percentage`, `copay_type`, `coinsurance_rate`, `deductible`,
`annual_deductible`, `out_of_pocket_max`, `applies_to_deductible`, `applies_to_oop_max`, or
`waived_when_*` exists anywhere in the tenant migrations (searched
`services/java/tenancy-service/src/main/resources/db/migration/tenant/`).

Similarly, per-member utilisation lives in `BeneficiaryBenefit`
(`services/java/contributions-service/src/main/java/com/medfund/contributions/entity/BeneficiaryBenefit.java:22-82`)
with `consumedAmount` and `consumedCount` — but no `deductibleMetYtd`, `oopMetYtd`, or `copayCountYtd`
accumulators.

Every industry-standard cost-share primitive — deductible, OOP max, tiered copay, applies-to-deductible
flag — is absent from the data model.

### 5. Member liability & EOB — nothing exists

`MemberPayable`
(`services/java/finance-service/src/main/java/com/medfund/finance/entity/MemberPayable.java:22-49`) is
what fires when a claim adjudicates with `payeeType=MEMBER` — i.e., the member paid a provider
out-of-pocket and the fund owes them a reimbursement
(`services/java/finance-service/src/main/java/com/medfund/finance/consumer/ClaimAdjudicatedConsumer.java:125-222`).
The amount stored is `approvedAmount` — copay is not deducted, because the pipeline never computed it.

There is **no** `MemberInvoice`, `MemberStatement`, `MemberCopayLiability`, or EOB emission anywhere in
the codebase. Nothing tracks "member owes fund X for copay on claim Y".

The Angular "Copayments" page
(`clients/angular/src/app/pages/tenant/finance/finance.routes.ts:334-353`,
`clients/angular/src/app/layout/operational-sidebar/operational-nav.ts:144`) is a filter over
`contributions.transactions` where `transactionType='COPAYMENT'` — cash receipts an operator recorded
manually. It is a **cashbook view**, not a member-liability view.

Permission `finance:manage_copayments`
(`services/java/shared/src/main/java/com/medfund/shared/security/Permissions.java:102`,
`services/java/shared/src/main/java/com/medfund/shared/security/PermissionCatalogue.java:83`,
`clients/angular/src/app/core/security/permissions.ts:143`) gates this manual entry.

### 6. Provider-facing quote — no endpoint

Neither `PreAuthService`
(`services/java/claims-service/src/main/java/com/medfund/claims/service/PreAuthService.java:81-100`)
nor `QuotationService` computes copay before the claim is submitted. There is no equivalent of a 270
eligibility inquiry / 271 response that quotes the member's copay at check-in based on service type +
network + coverage level.

## Cross-service flow (today)

```
Provider ── 837/claim submit ──▶ claims-service (AdjudicationPipeline)
                                         │
                                         ├─ Stage 7: rules engine fires CO_PAYMENT rules
                                         │  └─ RuleResult(APPLY_COPAY, amount) recorded in stageResults
                                         │  └─ NEVER read by DecisionEngine
                                         │
                                         ▼
                                 AdjudicationResult (approvedAmount, decision, stageResults)
                                         │
                                         │  Kafka: claim.adjudicated
                                         ▼
                                 finance-service.ClaimAdjudicatedConsumer
                                         │
                                         ├─ payeeType=PROVIDER → provider_balances += approvedAmount
                                         └─ payeeType=MEMBER   → member_payables  += approvedAmount   (reimbursement, not copay)
```

The `CO_PAYMENT` rule branch and `CoPaymentService.calculate` exist off to the side and are never
executed on the production path.

## Proposed Standard Flow

Combining the three flows used by real payers (POS quote → adjudication-time computation →
post-adjudication member statement), tailored to InsureFlow's multi-tenant, multi-line architecture:

### Phase A — Configuration (tenant admin)

> **Grilled — decision set G2, G8, G11, G14, G15, G16, G17.**

1. **Cost-share primitives.** ~~Extend `SchemeBenefit` (or a sibling `benefit_cost_share`) with…~~
   **Superseded by G2 + G8.** Introduce **three new tenant-schema tables** (all temporal per G15,
   carrying `effective_from` / `effective_to`):
   - **`scheme_cost_share`** — one row per (scheme, policy_year). Holds `deductible`,
     `out_of_pocket_max`, `deductible_scope` (`INDIVIDUAL | FAMILY | EMBEDDED`), `oop_scope`,
     `shortfall_policy` (`RECOVER_FROM_MEMBER` default | `ABSORB_BY_FUND`, per G11), `currency_code`,
     `policy_year` (aligned with `BeneficiaryBenefit.policy_year` per G17). Deductible/OOP amounts
     live here — **not** on benefit_cost_share — because they are cross-benefit pots.
   - **`benefit_cost_share`** — 1:1 nullable with `scheme_benefits`. Holds per-benefit fields only:
     `copay_type` (`FLAT | PERCENT | TIERED`), `copay_amount`, `copay_percentage`, `copay_max`,
     `coinsurance_rate`, `applies_to_deductible` (bool), `applies_to_oop_max` (bool), `basis`
     (`per_visit | per_day | per_admission | per_script`). ~~`network_tier` (FK to a tenant-authored
     `network_tiers` reference table)~~ superseded by G16 — no reference table for MVP; tiered rows
     carry `tier_name` as a string. ~~`waived_when` (JSONB — e.g.
     `["preventive", "emergency_admission"]`)~~ **dropped per G14** — all waivers are rules-engine
     rules with `APPLY_COPAY` amount=0, shipped as templates (`WAIVE_PREVENTIVE`,
     `WAIVE_EMERGENCY_ADMISSION`, `WAIVE_IN_NETWORK_TIER_1`, …). LIFE / FUNERAL leave the whole
     benefit_cost_share row null; pipeline skips computation.
   - **`benefit_cost_share_tier`** — 1:N with `benefit_cost_share` when `copay_type=TIERED`. One row
     per network tier: `tier_name`, `copay_amount`, `copay_percentage`, `copay_max`.
   - **`member_cost_share_accumulator`** — new table for YTD accumulators (see Phase D).
2. **Rules-engine CO_PAYMENT category stays** — it handles conditional overrides ("waive copay if
   claim.isEmergency", "out-of-network → 20 %") that don't fit a static benefit column. **Per G4,
   an APPLY_COPAY rule replaces the benefit-level copay entirely; ties broken by
   `RuleDefinition.priority`. Angular admin surfaces a warning when a rule resolves to amount=0
   (silent waiver).**
3. ~~**Zimbabwe/shortfall config.** Add `shortfall_policy` on the scheme: `RECOVER_FROM_MEMBER`
   (default) / `ABSORB_BY_FUND`. The AHFOZ market default is recovery.~~ **Moved into
   `scheme_cost_share` per G11** — no separate table.

### Phase B — Point-of-service quote (provider portal)

> **Grilled — decision G9.** Endpoint lives on **claims-service** (not gateway), authenticated with
> the existing tenant JWT plus a new permission `claims:request_quote` bound to the Provider role.
> Input keyed by **`memberPolicyNumber`** (not raw UUID — respects
> `feedback_no_raw_id_inputs`); `providerId` is derived from the caller's principal (provider users
> are pinned to their provider record). The endpoint emits a `claims.quote-issued` audit event so
> quotes are traceable. Third-party PMS/EHR API-key integration is deferred (follow-up **F3**).

New endpoint `POST /api/v1/eligibility-quote` that takes ~~`{ memberId, providerId, serviceCategory,
tariffCodes[], billedAmount, dateOfService }`~~ **`{ memberPolicyNumber, serviceCategory,
tariffCodes[], billedAmount, dateOfService }`** (providerId inferred from JWT principal) and returns:

```json
{
  "coverage": "ACTIVE",
  "networkTier": "TIER_1",
  "deductibleRemaining": 0,
  "estimatedAllowed": 320.00,
  "estimatedCopay": 20.00,
  "estimatedCoinsurance": 0.00,
  "estimatedShortfall": 130.00,
  "estimatedPatientResponsibility": 150.00,
  "estimatedPlanPaid": 300.00,
  "oopMaxRemaining": 480.00
}
```

Internally this runs a **read-only** adjudication (eligibility + benefit-limit + tariff + tenant rules)
against the current benefit design and member accumulators, without persisting a claim.

### Phase C — Adjudication-time computation

> **Grilled — decisions G3, G4, G6, G10, G18.** Fields added to `AdjudicationResult` and `ClaimLine`
> are **additive**. Existing `approvedAmount` **retains its current meaning = plan-paid** so
> finance-service's `ClaimAdjudicatedConsumer` keeps working unchanged. Shortfall is a **first-class
> bucket**, never folded into copay. All arithmetic uses `CurrencyConverter` for benefit → claim
> currency conversion, `asOf = claim.dateOfService` (G6). Every rule fire that produced a cost-share
> amount is linked back via existing `rule_execution_log` + a `{ruleId, ruleVersion, field, amount}`
> tuple appended to the Stage 7 stageResults payload (G18).

Fields **added** to `AdjudicationResult` and `ClaimLine` (7 new nullable fields; existing fields
untouched):

```
allowedAmount         = min(claimed, tariffAllowed) after modifiers
deductibleApplied     = min(allowedAmount, member_cost_share_accumulator.deductible_remaining)
copayAmount           = if any APPLY_COPAY rule fired → highest-priority rule's amount
                        else                          → benefit_cost_share.copay_amount (converted to claim currency)
coinsuranceAmount     = (allowedAmount - deductibleApplied - copayAmount) * coinsurance_rate
notCoveredAmount      = allowed portion excluded by benefit/rules (e.g. non-formulary)
shortfallAmount       = max(0, claimed - tariffAllowed)      (AHFOZ delta; first-class bucket per G3)
memberResponsibility  = deductibleApplied + copayAmount + coinsuranceAmount + notCoveredAmount
                        + (shortfallAmount when scheme_cost_share.shortfall_policy = RECOVER_FROM_MEMBER)
```

The **existing** `approvedAmount` field continues to mean **plan-paid** — computed as:
```
approvedAmount (plan-paid) = allowedAmount - memberResponsibility
```
Finance-service's `ClaimAdjudicatedConsumer` (which writes `provider_balances` / `member_payables`
using `approvedAmount`) requires **no change** to its amount handling.

Change required in code:

- New `CostShareCalculator` (claims-service) injected into `AdjudicationDecisionEngine`. Takes the
  claim, the stageResults (for APPLY_COPAY rule fires), `benefit_cost_share`, `scheme_cost_share`,
  the member accumulator row(s), and a `CurrencyConverter` handle. Returns the 7-field breakdown.
- `AdjudicationDecisionEngine.decide` invokes the calculator on the auto-approve branch and sets the
  breakdown on `AdjudicationResult` + per-line values on `ClaimLine`.
- Reuse `CoPaymentService.calculate` for `shortfallAmount` (it already implements
  `claimed - tariffAllowed`); it should be **called from the pipeline**, not just from
  `QuotationService`.
- **Per G10**, `QuotationService.review` also invokes `CostShareCalculator` to pre-fill
  `coPaymentAmount`; the existing `@RequestParam coPaymentAmount` (`QuotationController.java:85`)
  is retained as a **reviewer override**. When the override differs from the computed value the
  audit event records both `computed` and `overridden`; the Angular quotation UI surfaces a warning.
- **Currency-conversion note (G6):** `AdjudicationPipeline.benefitLimitCheck` (`:456-515`) and
  `ProrationService` (`:196-203`) both have latent currency-blindness. This plan does **not** fix
  them (scope creep) — captured as follow-up **F1**.

### Phase D — Post-adjudication persistence & communication

> **Grilled — decisions G5, G7, G8, G12, G13.**

1. **Member liability ledger (G5).** ~~New entity `member_cost_share_liability(id, tenant_id,
   member_id, claim_id, deductible, copay, coinsurance, shortfall, not_covered, total, currency,
   status)`.~~ Two tables in finance-service tenant schema:
   - **`member_cost_share_liability`** — one row per adjudicated claim:
     `(id, tenant_id, member_id, claim_id, deductible, copay, coinsurance, shortfall, not_covered,
     total_owed, total_settled, currency_code, currency_code_original, status, created_at, updated_at)`.
     Two currency fields (per G6) — the converted amount and the original benefit-currency amount.
   - **`member_cost_share_settlement`** — sub-ledger; one row per receipt applied to a liability:
     `(id, liability_id, receipt_transaction_id, amount, currency_code, source, settled_at)`.
     `receipt_transaction_id` FKs to the existing `transactions` row (transactionType renamed to
     `COPAYMENT_RECEIPT` per G13). `source` is `MEMBER_PAYMENT | MEMBER_PAID_PROVIDER | WRITE_OFF | …`.

   Populated by an enhanced `ClaimAdjudicatedConsumer` alongside the existing `provider_balances`
   / `member_payables` writes.

   **Cash-first (`payeeType=MEMBER`) special case per G12:** liability row is still written for
   uniform reads (EOB / statement / audit), but pre-set to **status=SETTLED** with a synthetic
   settlement row `source=MEMBER_PAID_PROVIDER`. `MemberPayable.amount` continues to be
   `approvedAmount` (= plan-paid post-G3), so reimbursement math is naturally correct.

2. **Accumulator updates (G8).** ~~On approval, increment `BeneficiaryBenefit.deductibleMetYtd` and
   `oopMetYtd`.~~ Superseded — BeneficiaryBenefit is the wrong grain (per-benefit, whereas
   deductibles are cross-benefit). Increments land on the **new `member_cost_share_accumulator`**
   table:
   `(id, tenant_id, member_id, dependant_id NULL, scheme_id, policy_year, deductible_met, oop_met,
   copay_count, currency_code)`. Family-aware from day one: under `deductible_scope=FAMILY` the row
   with `dependant_id IS NULL` for the principal is the family pot; INDIVIDUAL uses per-beneficiary
   rows; EMBEDDED writes both. When OOP-max is met, subsequent claims flip copay/coinsurance to zero.
   Concurrent-claim safety enforced by a UNIQUE `(member_id, dependant_id, scheme_id, policy_year)`
   index + optimistic locking.

3. **EOB emission (G7).** New Kafka event `claim.eob-issued` emitted **by claims-service**
   immediately after `claim.adjudicated` succeeds, carrying the full 7-field breakdown +
   CARC/RARC-mapped reason codes (mapping lives in claims-service). **Only** notification-service
   subscribes — it composes the member email/SMS/PDF from existing templates.
   Finance-service ignores this event (it already handles the money side via `claim.adjudicated`).
   Angular member portal renders the EOB under `/member/claims/:id/eob`.

4. **List rename + new liability page (G13).** Rename `finance-service` transaction type
   `COPAYMENT` → `COPAYMENT_RECEIPT` (new Flyway data migration + enum update — never mutate
   applied migrations per `feedback_never_edit_applied_migrations`). Existing
   `/tenant/finance/copayments` page becomes **"Cost-share receipts"**. New page
   **`/tenant/finance/member-liabilities`** reads from `member_cost_share_liability` with drill-down
   into `member_cost_share_settlement`. Permission `finance:manage_copayments` continues to gate
   receipts; add new `finance:view_member_liabilities` for the liability view. Follow-up **F2**
   audits dashboards / exports for the old `'COPAYMENT'` filter string.

### Phase E — Coordination of Benefits (later)

> **Grilled — G1: explicitly OUT OF SCOPE for this plan.** Deferred to follow-up **F4**.

Once single-plan copay is working, add COB support (primary → secondary) using the NAIC methodology
selector (`STANDARD | NON_DUPLICATION | FULL`) as a per-scheme setting. Not required for MVP;
Zimbabwean market rarely runs true COB.

## Architecture doc vs. code

The two architecture docs describe copayment only as a *residual*, not a first-class concept — and
neither reflects the code that already exists.

- `.claude/adjudication.md:99` — Stage 3 (BenefitLimit) *"PARTIAL → Approve up to remaining limit,
  balance as co-payment"*. This is aspirational: `AdjudicationDecisionEngine` today has no PARTIAL
  branch; it produces APPROVED / MANUAL_REVIEW / REJECTED only.
- `.claude/adjudication.md:154-155` — Stage 5c *"If billed amount > tariff amount → pay at tariff
  rate. Excess becomes patient co-payment."* — this is exactly what `CoPaymentService.calculate`
  computes. The doc's design and the class both exist; nothing wires them together.
- `.claude/adjudication.md:207` — the decision matrix has no "member responsibility" output at all.
- `.claude/rules-engine.md:14` lists `CoPayment` as one of the 15 template categories, but the doc has
  **no dedicated section defining its fields/actions/examples** — sections 1-7 cover Eligibility,
  Waiting Period, Benefit Limit, Pre-Auth, Tariff, Clinical, Billing; CoPayment is skipped. The code
  in `CoPaymentTemplates.java` fills that hole with two templates, but they are undocumented in the
  architecture doc.
- Neither doc mentions deductibles, out-of-pocket max, EOB, or member liability. The medical-aid
  vertical the doc claims is "production-ready" (`.claude/adjudication.md:3`) is production-ready for
  provider payment only — the member cost-share half of the ledger is not modelled.

## Code References

- `services/java/rules-engine/src/main/java/com/medfund/rules/model/RuleCategory.java:38` — `CO_PAYMENT` category
- `services/java/rules-engine/src/main/java/com/medfund/rules/model/ActionType.java:20` — `APPLY_COPAY` action
- `services/java/rules-engine/src/main/java/com/medfund/rules/compiler/ActionEmitters.java:90-104` — `ApplyCopayEmitter`
- `services/java/rules-engine/src/main/java/com/medfund/rules/fact/ClaimFact.java:75-84` — `applyPercentageCopay` / `applyFixedCopay`
- `services/java/rules-engine/src/main/java/com/medfund/rules/template/providers/CoPaymentTemplates.java:17-34` — CP01 / CP02 templates
- `services/java/claims-service/src/main/java/com/medfund/claims/service/CoPaymentService.java:28-77` — shortfall math (unused by pipeline)
- `services/java/claims-service/src/main/java/com/medfund/claims/service/QuotationService.java:99-125` — only caller path, but manual
- `services/java/claims-service/src/main/java/com/medfund/claims/controller/QuotationController.java:85-88` — `@RequestParam coPaymentAmount`
- `services/java/claims-service/src/main/java/com/medfund/claims/entity/Quotation.java:46-47` — `co_payment_amount` column
- `services/java/claims-service/src/main/java/com/medfund/claims/service/AdjudicationPipeline.java:828-857` — Stage 7 tenant rules
- `services/java/claims-service/src/main/java/com/medfund/claims/service/AdjudicationDecisionEngine.java:36-165` — decision engine (ignores `APPLY_COPAY`)
- `services/java/claims-service/src/main/java/com/medfund/claims/dto/AdjudicationResult.java:6-28` — no cost-share fields
- `services/java/claims-service/src/main/java/com/medfund/claims/entity/Claim.java:66-74` — `claimedAmount` / `approvedAmount` / `paidAmount` only
- `services/java/contributions-service/src/main/java/com/medfund/contributions/entity/SchemeBenefit.java:14-135` — no copay columns
- `services/java/contributions-service/src/main/java/com/medfund/contributions/entity/BeneficiaryBenefit.java:22-82` — no OOP/deductible accumulators
- `services/java/finance-service/src/main/java/com/medfund/finance/entity/MemberPayable.java:22-49` — reimbursement, not copay debt
- `services/java/finance-service/src/main/java/com/medfund/finance/consumer/ClaimAdjudicatedConsumer.java:125-222` — member payable creation
- `services/java/shared/src/main/java/com/medfund/shared/security/Permissions.java:102` — `finance:manage_copayments`
- `services/java/shared/src/main/java/com/medfund/shared/security/PermissionCatalogue.java:83` — permission label
- `clients/angular/src/app/pages/tenant/finance/finance.routes.ts:334-353` — copayments list & create routes
- `clients/angular/src/app/layout/operational-sidebar/operational-nav.ts:144` — sidebar entry
- `clients/angular/src/app/core/security/permissions.ts:143` — permission binding
- `.claude/adjudication.md:99,154-155,207` — design mentions of "co-payment" as residual
- `.claude/rules-engine.md:14` — `CoPayment` listed but no dedicated section

## Architecture Insights

- **Cost-share is a first-class concept, not a rule.** The existing rules-engine `CO_PAYMENT` category
  is right for *conditional overrides* (network tier, emergency waiver) but wrong for the *base
  design* (PCP visit copay = $20). Base cost-share belongs on the benefit table so it is queryable,
  reportable, and quotable at POS without evaluating rules. Rules layer on top.
- **Multi-line concern.** The current copay pieces are health-oriented (tariff, AHFOZ shortfall). For
  LIFE / DISABILITY / TRAVEL, cost-share concepts differ: LIFE payouts have no member share; TRAVEL
  has "excess" (like motor insurance deductible); FUNERAL is typically zero-share. The proposed
  `benefit_cost_share` should be optional per benefit and the pipeline should skip cost-share
  computation for lines that don't use it — same pattern as `InsuranceLine.isPersonCentric()`.
- **Multi-currency (Critical Rule 1).** Every cost-share amount must carry `currency_code` and be
  compared using the exchange-rate service. The AHFOZ shortfall in particular is often collected in
  USD at POS while the scheme benefits are denominated in ZWL — this cannot be a naive subtraction.
- **Audit (Critical Rule 8).** Every cost-share write on the claim, every accumulator update on
  ~~`BeneficiaryBenefit`~~ **`member_cost_share_accumulator` (G8 introduced this table; accumulators
  no longer live on BeneficiaryBenefit — it's the wrong grain for cross-benefit deductible/OOP
  pots)**, and every liability entry on the new `member_cost_share_liability` must emit an audit
  event through the shared `AuditActor` helper (per `MEMORY.md` — audit `actorEmail` and friendly
  `entityName` are non-negotiable).
- **Tenant scoping (Critical Rule 2).** Cost-share configuration lives in the tenant schema in the
  new `scheme_cost_share` / `benefit_cost_share` / `benefit_cost_share_tier` tables (G2, G8);
  member accumulators live in the tenant schema in the new `member_cost_share_accumulator` table
  (G8); the liability ledger + settlements live in the finance-service tenant schema as
  `member_cost_share_liability` + `member_cost_share_settlement` (G5). No `public.*` prefixes
  (per `MEMORY.md` on silent rollback).
- **Standard EOB fields to model** (from web research): `billed`, `allowed`, `discount`,
  `deductibleApplied`, `copay`, `coinsurance`, `notCovered`, `planPaid`, `patientResponsibility`,
  `carc` (Claim Adjustment Reason Codes), `rarc` (Remark Codes). Rejection reason codes
  R01-R18 in `.claude/adjudication.md:402-421` should map to CARC values for future
  interoperability.
- **270/271 optional.** Full EDI 270/271 is US-specific; InsureFlow does not need the transaction
  format itself, but the *shape* of the 271 EB segments (STC + network + coverage level → cost-share
  amount) is exactly the input the eligibility-quote endpoint needs.

## Historical Context (from thoughts/shared/)

Searched `thoughts/shared/{research,plans,tickets,specs}/` — no prior copayment-specific document
exists. Adjacent work:

- `thoughts/shared/plans/2026-08-10-audit-path-431-shared-fiber-httpserver.md` — unrelated (Fiber buffer size).
- Prior audit-history refactor (per `MEMORY.md` → `feedback_audit_actor_email`,
  `feedback_audit_entity_name`) is directly relevant when wiring the new liability entity — every
  liability row must carry `actorEmail` and a friendly `entityName` (not the UUID).
- `project_ctc_is_opt_in.md` (memory) — CTC (Claims-to-Contributions) is the mechanism that offsets a
  member payable against future contributions. The proposed member-liability ledger would flow the
  opposite direction: recover member cost-share by adding to contribution invoice or expecting a
  separate `COPAYMENT_RECEIPT` transaction.
- `bug_public_prefix_silent_rollback.md` (memory) — reminder that any new tenant-schema table
  referenced in the pipeline must be queried without `public.` prefix.

## Related Research

None — this is the first copayment-focused doc in `thoughts/shared/research/`.

## Open Questions

> **All five resolved during 2026-08-10 grilling.** Each carries the G-number of the decision that
> settled it. See **[Decisions from grilling](#decisions-from-grilling-g1g18)**.

1. ~~**Should the shortfall (`billed − tariff`) be classified as a copay or as a distinct cost-share
   primitive?**~~ **Resolved — G3.** Shortfall is a **first-class bucket** with its own
   `shortfallAmount` field on `AdjudicationResult` / `ClaimLine`. Never folded into `copayAmount`.
   Whether it lands in `memberResponsibility` is controlled by `scheme_cost_share.shortfall_policy`
   (G11).
2. ~~**Where does OOP-max accumulate for family-pooled benefits?**~~ **Resolved — G8.** New
   `member_cost_share_accumulator` table, family-aware from day one via
   `scheme_cost_share.deductible_scope` and `.oop_scope`: `INDIVIDUAL | FAMILY | EMBEDDED`. Under
   FAMILY the row with `dependant_id IS NULL` on the principal member is the family pot. Deferred
   as a follow-up: **not deferred** — in MVP.
3. ~~**Does the medical-aid vertical for Zimbabwe want a "cash-first, reimburse-later" claim
   path?**~~ **Resolved — G12.** Yes; the existing `payeeType=MEMBER` path is retained. The new
   `member_cost_share_liability` row is still written for uniform EOB/audit reads, but pre-set to
   **status=SETTLED** with a synthetic settlement row `source=MEMBER_PAID_PROVIDER`.
   `MemberPayable.amount = planPaid = approvedAmount` (G3 preserved the semantic).
4. ~~**Interaction with the deferred `backdated_enrolment_adjustment` project — do backdated claims
   also need retroactive cost-share recalculation?**~~ **Resolved — G15.** All three cost-share
   tables (`scheme_cost_share`, `benefit_cost_share`, `benefit_cost_share_tier`) are **temporal**
   (`effective_from` / `effective_to`); config edits create new rows rather than mutate.
   `CostShareCalculator` selects the row valid at `claim.dateOfService`. The
   `backdated_enrolment_adjustment` project remains a separate initiative on the contributions side
   — this plan does not depend on it.
5. ~~**AI adjudication auditability (Critical Rule 3).** …the current `AdjudicationDecisionEngine`
   does not link individual rule fires to the final numbers.~~ **Resolved — G18.** Every cost-share
   amount that came from a rule fire is linked back via the existing `rule_execution_log` (rule id
   + version) **plus** a `{ruleId, ruleVersion, field, amount}` tuple appended to the Stage 7
   `stageResults` payload on `AdjudicationResult`. No new mechanism; existing infra extended.

## Decisions from grilling (G1–G18)

Settled during 2026-08-10 grilling. Every fork in Phases A–D is closed; Phase E is deferred.
This section is the authoritative input for `create-plan` — the Findings section above is the
verified state-of-the-code the plan builds against.

| # | Decision | Reason |
|---|----------|--------|
| **G1** | **Scope = Phases A + B + C + D (skip E).** Generic across markets (US / UK / DE / Zim). Fully wired end-to-end. Family accumulators + deductible + OOP-max + tiered copay are IN. | User steer: "make it generic, it should be easy for the system to adapt to other markets. Also the system should be fully wired." COB (Phase E) explicitly deferred as F4. |
| **G2** | **New tables `benefit_cost_share` (1:1 nullable with scheme_benefits) + `benefit_cost_share_tier` (1:N).** LIFE / FUNERAL leave the row null; pipeline skips. | SchemeBenefit already has 14 columns; tiered copay is inherently 1:N and can't fit as one row. JSONB rejected for reportability. |
| **G3** | **Additive fields on `AdjudicationResult` + `ClaimLine`:** `allowedAmount`, `deductibleApplied`, `copayAmount`, `coinsuranceAmount`, `notCoveredAmount`, `shortfallAmount`, `memberResponsibility`. **`approvedAmount` retains meaning = plan-paid.** Shortfall is a first-class bucket, never folded. | Preserves finance-service `ClaimAdjudicatedConsumer` semantics (which reads `approvedAmount` verbatim to write provider_balances / member_payables). Shortfall separate so US markets report 0 and Zim reports the AHFOZ delta cleanly. |
| **G4** | **Rules-engine `APPLY_COPAY` replaces benefit-level copay entirely.** Ties broken by `RuleDefinition.priority`. amount=0 is a valid waiver; Angular admin warns on authoring. | Matches how insurers author overrides ("OON → 20%" means 20%, not stacked). Existing ClaimFact + emitter code reused. |
| **G5** | **Two tables in finance-service tenant schema:** `member_cost_share_liability` (one row per claim; per-bucket columns; `total_owed` + `total_settled`) + `member_cost_share_settlement` (sub-ledger linking to receipt transaction rows). | Per-line rows explode for pharmacy claims; per-bucket 5x amplification with no member-visible benefit. Sub-ledger preserves receipt audit trail per Critical Rule 8. |
| **G6** | **Inject `CurrencyConverter` into `CostShareCalculator`;** convert benefit → claim currency at compute time with `asOf = claim.dateOfService`. Liability ledger stores both converted and original. | Aligns with `.claude/multi-currency.md` service-layer boundary; reuses shared `CurrencyConverter` interface (implementation `ExchangeRateService` in tenancy-service). |
| **G7** | **New Kafka event `claim.eob-issued` from claims-service, subscribed only by notification-service.** CARC/RARC mapping owned by claims-service. | Separation of concerns: finance settles money via existing `claim.adjudicated`; notification informs member via new event. Avoids notification-service leaking business logic. |
| **G8** | **Split scheme-level vs benefit-level cost-share:** `scheme_cost_share(scheme_id, policy_year, deductible, oop_max, deductible_scope, oop_scope, shortfall_policy, currency)` holds cross-benefit amounts; `benefit_cost_share` holds only per-benefit `applies_to_deductible` / `applies_to_oop_max` flags + copay/coinsurance/basis. New `member_cost_share_accumulator` table, family-aware from day one (INDIVIDUAL / FAMILY / EMBEDDED scopes). | Doc's Phase A conflated the deductible **amount** (scheme-wide pot) with the **applies-to** flag (per-benefit). Family accumulators required by G1's "generic across markets" ambition. |
| **G9** | **`POST /api/v1/eligibility-quote` on claims-service.** Auth = existing tenant JWT + new permission `claims:request_quote` bound to Provider role. Input keyed by `memberPolicyNumber` (not UUID — per `feedback_no_raw_id_inputs`). `providerId` derived from principal. Emits `claims.quote-issued` audit event. | Adjudication logic is Java Reactor; reimplementing in Go gateway = duplication. Third-party PMS/API-key path deferred as F3. |
| **G10** | **`QuotationService.review` auto-computes `coPaymentAmount` via `CostShareCalculator`; keeps `@RequestParam coPaymentAmount` as reviewer override.** Audit event records both `computed` and `overridden`; Angular warns when they differ. | Reviewer keeps escape valve for edge cases; consistency default = computed. |
| **G11** | **`shortfall_policy` (RECOVER_FROM_MEMBER default \| ABSORB_BY_FUND) lives on `scheme_cost_share`.** | Settled by fact — G3 made shortfall a first-class bucket; G8 established scheme-level cost-share table; the policy is scheme-wide. |
| **G12** | **Cash-first (`payeeType=MEMBER`) writes a `member_cost_share_liability` row pre-set to status=SETTLED** with a synthetic settlement row `source=MEMBER_PAID_PROVIDER`. `MemberPayable.amount` unchanged = `approvedAmount = planPaid`. | Uniform ledger read path preserves EOB and audit consistency; naturally correct reimbursement math thanks to G3. |
| **G13** | **Rename transactionType `COPAYMENT` → `COPAYMENT_RECEIPT`** (new Flyway data migration + enum update — never mutate applied migrations per `feedback_never_edit_applied_migrations`). Add new page `/tenant/finance/member-liabilities`. Existing `finance:manage_copayments` still gates receipts; add new `finance:view_member_liabilities` for the liability view. | Two things called "copayments" side by side is confusing; receipts and liabilities are distinct concepts. |
| **G14** | **Drop `waived_when` from `benefit_cost_share`.** All waivers via rules-engine `APPLY_COPAY` amount=0. Ship shared templates: `WAIVE_PREVENTIVE`, `WAIVE_EMERGENCY_ADMISSION`, `WAIVE_IN_NETWORK_TIER_1`. | Single waiver evaluation path; no duplicate authoring surface; leverages existing rules-engine work. |
| **G15** | **Temporal cost-share config.** `scheme_cost_share`, `benefit_cost_share`, `benefit_cost_share_tier` all carry `effective_from` / `effective_to`. Edits create new rows, don't mutate. `CostShareCalculator` selects by `claim.dateOfService`. | Contract-as-written-on-DOS; avoids member disputes when tenant admins raise copay retroactively. Backdated enrolment scenarios handled naturally. |
| **G16** | `benefit_cost_share_tier.tier_name` is a **string column** — no `network_tiers` reference table for MVP. | Settled by fact — no existing network-tier infra in the codebase. Reference table deferred as F5. |
| **G17** | `policy_year` on `scheme_cost_share` and `member_cost_share_accumulator` **aligns with existing `BeneficiaryBenefit.policy_year`**. Multi-year handled by inserting new rows per year. | Settled by fact — established grain; no reason to diverge. |
| **G18** | Rule fires linked to cost-share via **existing `rule_execution_log` (rule id + version) + `{ruleId, ruleVersion, field, amount}` tuple appended to Stage 7 `stageResults`.** | Settled by fact — Critical Rule 3 mandates this; existing infra covers it; no new mechanism needed. |

## Follow-ups owed to backlog

Surfaced during grilling; **out of scope for this plan** (recorded so scope creep is explicit).

| # | Follow-up | Where it came from |
|---|-----------|--------------------|
| **F1** | Fix currency-blindness in `AdjudicationPipeline.benefitLimitCheck` (`services/java/claims-service/src/main/java/com/medfund/claims/service/AdjudicationPipeline.java:456-515`) and `ProrationService` cross-currency short-circuit (`services/java/claims-service/src/main/java/com/medfund/claims/service/ProrationService.java:196-203`). Existing latent bug — Stage 3 compares `annual_limit` (benefit currency) to `claim.claimedAmount` (claim currency) without invoking `CurrencyConverter`. | G6 verification. |
| **F2** | Grep dashboards / reports / exports / Elixir live-dashboard queries for the string `'COPAYMENT'` (transaction-type filter) and update to `COPAYMENT_RECEIPT` post-rename. Migration handles data; consumers outside the migration path do not. | G13 rename. |
| **F3** | Third-party PMS / EHR API-key integration for eligibility-quote. Gateway middleware for per-tenant API-key auth + rate limiting; wrapper endpoint that lands at the same claims-service handler as G9. | G9 non-choice — deferred. |
| **F4** | Phase E — Coordination of Benefits (COB). NAIC methodology selector (`STANDARD | NON_DUPLICATION | FULL`) as a per-scheme setting. Zimbabwe market rarely runs true COB, but generic markets (US especially) do. | G1 explicit scope cut. |
| **F5** | `network_tiers` tenant-schema reference table for controlled vocabulary (once tenant admins ask). Migrate `benefit_cost_share_tier.tier_name` to `network_tier_id FK`. | G16 non-choice — deferred. |
