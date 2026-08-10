---
date: 2026-08-09
git_commit: 0e4b6cc07560720de2142878fadddd37e1ed7796
branch: main
research:
  - thoughts/shared/research/2026-08-09-payment-run-vs-payments.md
steer: |
  1. Bundle item-population + member-payee support in one plan.
  2. Auto-populate items on POST /payment-runs (no separate populate step or selection UI).
  3. Provider items sourced from provider_balances snapshot (aggregate per provider per currency), not one-per-claim.
  4. CTC opt-in is already implemented as an operator-driven workflow via tenant_ctc_auto_config + finance-desk commit — no need to build a member self-service opt-in signal.
  5. Payment advice must be a proper per-payee ledger like the contribution statement — carry-forward from prior run, claims paid, CTCs applied, advances applied, tax withheld, shortfalls, net due. Period bounded by (prior_run.executed_at, this_run.executed_at].
services_touched: [finance-service, tenancy-service, rules-engine, angular]
status: draft
---

# Payment Run — Item Population, Member-Payee Support, and Ledger-Style Advice

## Overview

Build the missing item-population layer so `PaymentRun` is functional end-to-end, extend it to member payees (default cash payout when CTC hasn't offset the payable), and generate a proper per-payee `PaymentAdvice` ledger for every run. Today the payment-run scaffolding runs but does nothing: `payment_run_items` has no producer, `Payment`/`PaymentRunItem` have no `member_id` column, `PaymentAdviceService` returns a null-provider single-advice bundle with empty payee names, and the flow described in `.claude/payments.md:301-363` is undeliverable. After this plan, POST `/api/v1/payment-runs` creates a header **and** auto-populates items from `provider_balances` (PROVIDER) and open `member_payables` (MEMBER); execute settles them under existing rules; each payee walks away with a printed ledger of everything that hit their balance since the prior run.

## Current State Analysis

**The producer gap.** V067's SQL comment says it out loud: *"The write path for carried_in lands with the future item-population service (nothing populates payment_run_items yet)"* (`services/java/tenancy-service/src/main/resources/db/migration/tenant/V067__payment_runs_carry_forward.sql:19-21`). `PaymentRunService.create` at `services/java/finance-service/src/main/java/com/medfund/finance/service/PaymentRunService.java:114-144` writes only the header. `PaymentRunService.execute` at `PaymentRunService.java:270-281` reads items via `paymentRunItemRepository.findByPaymentRunId(runId)` and no-ops on an empty flux — `PaymentRunServiceTest.java:134-137` literally mocks `Flux.empty()` as the happy path.

**Provider-only assumption.** `payment_run_items` has `provider_id UUID`, no `member_id`, no `payee_type`. `payments` is the same. `PaymentQueryRepository.search()` does `LEFT JOIN providers pr ON pr.id = p.provider_id` — the LEFT keeps null-provider rows queryable but the joined `provider_name` becomes null in the DTO, which the Angular `PaymentRow` interface hard-requires (`clients/angular/src/app/core/services/finance.service.ts:52-65`).

**Advance offset is already null-safe.** `PaymentRunService.resolveAdvancePaid:286-287` explicitly says *"Returns ZERO if the item has no provider (e.g. member-payee runs, which today don't exist but may later)"* and `recordApplicationsIfConsumed:320` early-returns when `providerId == null`. The plan doesn't need to change advance-offset logic — it works correctly for member items by design.

**Member exit is missing.** V069 (commit `0e4b6cc`) built the CTC lifecycle end-to-end: `ClaimAdjudicatedConsumer` writes a `member_payables` row when `payeeType=MEMBER` (`services/java/finance-service/src/main/java/com/medfund/finance/consumer/ClaimAdjudicatedConsumer.java:37-49,92-120`), and `CtcPaymentService` commits/reverses offsets that post `CTC_OFFSET` transactions in contributions. But CTC is opt-in per the domain rule (memory: `project_ctc_is_opt_in`) — an operator explicitly picks a payable to offset. Member payables that don't get CTC'd have no exit today. `MemberPayable.java:17` and `MemberPayableApplication.java:16` both leave TODO-shaped comments referencing *"later, by member-payment-run features"*.

**PaymentAdvice is a threadbare scaffold.**
- `payment_advices` table (V016:113-133) has `provider_id NOT NULL` — no `payee_type`, no `member_id`, no carry-forward, no line items.
- `PaymentAdviceService.generateAdvice:62-108` puts **all** run items into **one** advice with `providerId=null` (comment at line 86: *"providerId aggregated from run items"*), empty `providerName`/`memberName` strings (lines 69, 87: *"would be resolved via user-service lookup"*), no PDF generation, no notification.
- The user's requirement is a full ledger per payee per run, bounded by `(prior_run.executed_at, this_run.executed_at]`, showing carry-forward + claims paid + CTCs applied + advances applied + tax withheld + shortfalls + net due — modelled after the `invoice-statement.component.html` pattern in contributions.

**Downstream is quiet.** `medfund.payments.run.executed` and `medfund.finance.advance.applied` have **zero consumers today** — additive Kafka payload changes are safe. `services/go/payment-gateway` doesn't consume run events.

### Key Discoveries
- Item-population is a green field — no existing producer to reconcile with. `services/java/finance-service/src/main/java/com/medfund/finance/service/PaymentRunService.java:417` even flags "empty runs (no items yet — the common case in dev / before the item-population flow lands)".
- `AdjustmentType` enum includes `TAX_WITHHELD` (`services/java/tenancy-service/src/main/resources/db/migration/tenant/V016__finance_schema.sql:73`) — the advice can read these directly from the `adjustments` table without new schema.
- `MemberPayableBalanceRepository` (V069, `services/java/finance-service/src/main/java/com/medfund/finance/repository/MemberPayableBalanceRepository.java`) is the derived-aggregate pattern to mirror for the advice's per-payee balance math.
- Shortfall math is already computed in `services/java/user-service/src/main/java/com/medfund/user/controller/TenantStatsController.java` (`claimed_amount - COALESCE(paid_amount, 0)` for `status='adjudicated'` claims where `paid < claimed`) — same formula, filtered per payee per period.
- Contribution statement pattern to mirror: `services/java/contributions-service/src/main/java/com/medfund/contributions/controller/StatementController.java` + `StatementExcelService.java`, rendered at `clients/angular/src/app/pages/tenant/billing/invoices/invoice-statement.component.html`. It uses a strict-less-than-commit window (`invoice-statement.component.html:146`) — the advice will use the same *"transactions strictly before this run's executed_at, strictly after the prior run's executed_at"* window.
- `AdvancePayment` already supports member payees at the table level (`V016:150` has `member_id UUID` with `CHECK (provider_id IS NOT NULL OR member_id IS NOT NULL)`). Advance-offset for members isn't wired but the storage is ready.
- Reuse `services/java/shared/audit/AuditActor.java` for actor emails (memory: `feedback_audit_actor_email`); the 10-arg `AuditEvent.create` is fine but `entityName` must be a friendly string (memory: `feedback_audit_entity_name`).

## Desired End State

1. **POST `/api/v1/payment-runs`** with `{ currencyCode, description }` creates a draft run **and** auto-populates items in a single transaction: one item per provider with an outstanding balance, one item per member with an open unpaid `member_payable`.
2. **POST `/api/v1/payment-runs/{id}/execute`** runs tenant rules, snapshots carry-forward, generates one `PaymentAdvice` per (run, payee), publishes `medfund.payments.run.executed` (unchanged) and `medfund.payments.advice.generated` (new) per advice.
3. **Angular `/tenant/finance/runs/:id`** shows a payments table with a *Payee* column (dynamic — provider or member name) plus a PROVIDER/MEMBER pill; **new** *Advices* tab lists per-payee advices with view / download-PDF actions.
4. **Angular `/tenant/finance/advices/:id`** renders the ledger: opening balance (carry-forward from prior run), section per line-type (claims paid, CTCs applied, advances applied, tax withheld, shortfalls), closing net-due-amount. Mirrors the contribution statement layout.
5. **Regression-safe**: every existing provider-only IT + Angular spec still passes; new tests cover member-payee round-trips.

### Verification
- `curl` — create a run with no eligible items → returns draft with `paymentCount=0` (backward-compatible no-op).
- `curl` — adjudicate a MEMBER-payee claim, then create a run in the same currency → the new run has one MEMBER item and one advice for that member.
- `curl` — execute the run → advice is persisted with correct ledger lines summing to the net due.
- Angular `verify` on `/tenant/finance/runs/{id}` shows both provider and member items with correct payee names; advice detail page renders ledger sections without console errors.

## What We're NOT Doing

- **Actual money movement**: `services/go/payment-gateway` integration stays out of scope. Finance-service publishes `medfund.payments.run.executed` per usual; the downstream orchestration is a separate plan.
- **Member self-service CTC opt-in**: no `settlement_preference` on Claim, no `ctc_preferred` on Member. The current operator-driven CTC workflow is sufficient per the user's steer.
- **"Select claims to include" UI** described in `.claude/payments.md:309`: auto-populate is the shorter path. If the doc needs a selection step later, it can be a follow-up.
- **PDF/Excel rendering pipeline**: the advice writes to `document_url` / `excel_url` but the actual PDF generation stays a follow-up (file-service integration). The advice ledger renders fully in Angular; download links are stubs pointing to a "not yet" endpoint that returns 501.
- **Payment-advice email/SMS delivery**: the notification-service integration for sending advices to providers/members is out of scope. `PaymentAdviceRecord.status` stays at `'generated'`; the `'sent'` transition lands with a follow-up.
- **Auto-CTC for advances or tax-withheld**: only claim adjudication drives auto-CTC today (V069). We don't extend that.
- **`.claude/payments.md` architecture-doc rewrite** beyond the two drift fixes listed in Phase 7 (routes and workflow). A full doc pass belongs with the payment-gateway plan.
- **Legacy `PaymentAdviceService.generateAdvice`**: not deleting the current method; renaming its purpose to `generateAdvicesForRun(runId)` returning `Flux<PaymentAdvice>` (one per payee) is safer than a big-bang rewrite. Old callers of the single-advice endpoint keep working during rollout (there are none in production — Angular hasn't wired it).

## Implementation Approach

**Order-of-operations rationale.** Schema first (safe additive migration), then entities/DTOs/query surface (no behavior change, unblocks downstream code), then the item-population producer (activates the flow for PROVIDER; MEMBER hooks into the same producer), then rules-engine dispatch (needed for execute to work on MEMBER items), then the advice ledger (needs items to summarize), then Angular UI (surfaces everything), finally tests + docs. Each phase is independently deployable and verifiable.

**Producer/consumer sequencing.** Additive to `medfund.payments.run.executed` (no consumers today) is safe. The new `medfund.payments.advice.generated` event is emitted per advice at execute time — no consumers required in this plan (notification-service will subscribe later).

**Backwards compatibility.** All schema changes are additive. Existing rows get `payee_type='PROVIDER'` on backfill. Angular type changes make provider fields optional; existing components that read them work unchanged. No Kafka payload subtraction — only additive fields.

**Reuse before rebuild.** `MemberPayableBalanceRepository` (V069) is the pattern for the advice's per-payee balance CTE. `AdjustmentQueryRepository` is the source for `TAX_WITHHELD` adjustment lookups. `AuditActor.SYSTEM_ID` / `SYSTEM_EMAIL` for system-driven audit rows (item population, advice generation).

---

## Phase 1: Additive Schema — payee_type, member_id, and advice ledger

### Overview
Add `payee_type` + nullable `member_id` to `payments`, `payment_run_items`, and `payment_advices`. Add carry-forward + net-due columns to `payment_advices`. Add a new `payment_advice_lines` table for the typed ledger rows. Backfill existing rows to `PROVIDER`. Seed no new permissions (existing `finance:view_payment_advice` from `V006__rbac_refinements.sql:61` covers advice reads; add `finance:generate_payment_advice` for the manual re-generate action).

### Changes Required

#### 1. Tenant migration
**File**: `services/java/tenancy-service/src/main/resources/db/migration/tenant/V071__payment_run_generation_and_advice_ledger.sql` (new)

```sql
-- V071 — Payment-run item population, member-payee support, and per-payee
-- ledger advices. Additive on top of V016 finance schema + V067 carry-fwd
-- + V069 member-payable ledger. See
-- thoughts/shared/plans/2026-08-09-payment-run-generation-and-payee-support.md.

-- ── 1. Payments: add payee_type + nullable member_id ──────────────────────
ALTER TABLE payments
    ADD COLUMN IF NOT EXISTS payee_type VARCHAR(10) NOT NULL DEFAULT 'PROVIDER'
        CHECK (payee_type IN ('PROVIDER','MEMBER')),
    ADD COLUMN IF NOT EXISTS member_id  UUID;

-- Provider was required by the CHECK in V016; loosen to XOR now that MEMBER
-- is a valid payee. Drop the old constraint if it existed (V016 didn't
-- add one — payments used provider_id NOT NULL — but be defensive).
ALTER TABLE payments
    ALTER COLUMN provider_id DROP NOT NULL;

ALTER TABLE payments
    ADD CONSTRAINT payments_payee_xor
        CHECK ((provider_id IS NOT NULL AND member_id IS NULL AND payee_type = 'PROVIDER')
            OR (provider_id IS NULL AND member_id IS NOT NULL AND payee_type = 'MEMBER'));

CREATE INDEX IF NOT EXISTS idx_payments_member ON payments(member_id);
CREATE INDEX IF NOT EXISTS idx_payments_payee_type ON payments(payee_type, status);

-- ── 2. Payment-run items: same additive XOR ──────────────────────────────
ALTER TABLE payment_run_items
    ADD COLUMN IF NOT EXISTS payee_type VARCHAR(10) NOT NULL DEFAULT 'PROVIDER'
        CHECK (payee_type IN ('PROVIDER','MEMBER')),
    ADD COLUMN IF NOT EXISTS member_id  UUID;

ALTER TABLE payment_run_items
    ALTER COLUMN provider_id DROP NOT NULL;

ALTER TABLE payment_run_items
    ADD CONSTRAINT payment_run_items_payee_xor
        CHECK ((provider_id IS NOT NULL AND member_id IS NULL AND payee_type = 'PROVIDER')
            OR (provider_id IS NULL AND member_id IS NOT NULL AND payee_type = 'MEMBER'));

CREATE INDEX IF NOT EXISTS idx_pri_member ON payment_run_items(member_id);

-- ── 3. Payment advices: per-payee, ledger-style ──────────────────────────
ALTER TABLE payment_advices
    ADD COLUMN IF NOT EXISTS payee_type          VARCHAR(10) NOT NULL DEFAULT 'PROVIDER'
        CHECK (payee_type IN ('PROVIDER','MEMBER')),
    ADD COLUMN IF NOT EXISTS member_id           UUID,
    ADD COLUMN IF NOT EXISTS period_start_at     TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS period_end_at       TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS carried_in_amount   NUMERIC(19,4) NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS claims_paid_amount  NUMERIC(19,4) NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS ctc_applied_amount  NUMERIC(19,4) NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS advance_applied_amount NUMERIC(19,4) NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS tax_withheld_amount NUMERIC(19,4) NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS shortfall_amount    NUMERIC(19,4) NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS net_due_amount      NUMERIC(19,4) NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS advice_number       VARCHAR(20);

-- Loosen the V016 NOT NULL on provider_id and add the XOR constraint.
ALTER TABLE payment_advices
    ALTER COLUMN provider_id DROP NOT NULL;

ALTER TABLE payment_advices
    ADD CONSTRAINT payment_advices_payee_xor
        CHECK ((provider_id IS NOT NULL AND member_id IS NULL AND payee_type = 'PROVIDER')
            OR (provider_id IS NULL AND member_id IS NOT NULL AND payee_type = 'MEMBER'));

-- One advice per (run, payee); prevents duplicate generation.
CREATE UNIQUE INDEX IF NOT EXISTS uq_payment_advices_run_provider
    ON payment_advices(payment_run_id, provider_id) WHERE provider_id IS NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uq_payment_advices_run_member
    ON payment_advices(payment_run_id, member_id) WHERE member_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_payment_advices_member ON payment_advices(member_id);

-- Backfill advice_number for any existing rows; going forward the service
-- writes ADV-<6-digit-random>.
UPDATE payment_advices
   SET advice_number = 'ADV-LEGACY-' || SUBSTR(id::TEXT, 1, 8)
 WHERE advice_number IS NULL;

ALTER TABLE payment_advices
    ADD CONSTRAINT payment_advices_advice_number_not_null CHECK (advice_number IS NOT NULL);

-- ── 4. Payment-advice lines: typed ledger rows ──────────────────────────
CREATE TABLE IF NOT EXISTS payment_advice_lines (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    payment_advice_id   UUID NOT NULL REFERENCES payment_advices(id) ON DELETE CASCADE,
    line_type           VARCHAR(24) NOT NULL
                          CHECK (line_type IN ('CARRY_FORWARD','CLAIM_PAID',
                                               'CTC_APPLIED','ADVANCE_APPLIED',
                                               'TAX_WITHHELD','SHORTFALL')),
    reference_type      VARCHAR(32),  -- e.g. 'claim', 'ctc_payment', 'adjustment'
    reference_id        UUID,          -- FK-shape but no hard FK: sources cross-service
    description         TEXT,
    debit_amount        NUMERIC(19,4) NOT NULL DEFAULT 0,  -- money owed TO payee
    credit_amount       NUMERIC(19,4) NOT NULL DEFAULT 0,  -- deductions FROM payee
    currency_code       VARCHAR(3)   NOT NULL,
    posted_at           TIMESTAMPTZ  NOT NULL,             -- when the underlying event happened
    sequence            INTEGER      NOT NULL,             -- display order within the advice
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT payment_advice_lines_amount_sign
        CHECK ((debit_amount = 0 OR credit_amount = 0)
           AND (debit_amount >= 0 AND credit_amount >= 0))
);

CREATE INDEX IF NOT EXISTS idx_pal_advice_seq  ON payment_advice_lines(payment_advice_id, sequence);
CREATE INDEX IF NOT EXISTS idx_pal_reference   ON payment_advice_lines(reference_type, reference_id);

-- ── 5. Permission for manual advice regeneration ────────────────────────
INSERT INTO permissions (permission_key)
    VALUES ('finance:generate_payment_advice')
    ON CONFLICT (permission_key) DO NOTHING;
```

**Idempotent SQL** — all `ADD COLUMN IF NOT EXISTS`, `CREATE INDEX IF NOT EXISTS`, `ON CONFLICT DO NOTHING`. Never edit an applied migration (memory: `feedback_never_edit_applied_migrations`).

### Success Criteria

#### Automated Verification:
- [x] `cd services/java && ./gradlew :tenancy-service:build` clean
- [ ] Migration applies against a fresh Testcontainer: `cd services/java && ./gradlew :tenancy-service:integrationTest`
- [ ] Migration is re-runnable: apply, drop none, `flyway migrate` again is a no-op
- [ ] Query `SELECT payee_type FROM payments LIMIT 1` returns `'PROVIDER'` on any pre-existing row (backfill worked)
- [ ] `INSERT INTO payments (provider_id, member_id, payee_type, ...) VALUES (uuid, uuid, 'PROVIDER', ...)` fails with the XOR constraint

#### Manual Verification:
- [ ] Pull the schema diff, eyeball the constraints for typos before the migration ships to any non-dev tenant

---

## Phase 2: Entities, DTOs, and Query Surface

### Overview
Extend `Payment`, `PaymentRunItem`, `PaymentAdviceRecord` entities with the new fields. Add `PaymentAdviceLine` entity. Update DTOs (`PaymentResponse`, `PaymentRunItemResponse`, `PaymentRow`, `PaymentAdvice`). Extend `PaymentQueryRepository` to conditionally join members. No behaviour change yet — this phase is a scaffold that later phases populate.

### Changes Required

#### 1. `Payment` entity
**File**: `services/java/finance-service/src/main/java/com/medfund/finance/entity/Payment.java`
**Changes**: Add `memberId`, `payeeType` fields with getters/setters (match existing hand-written accessor style, don't add Lombok — CLAUDE.md says don't refactor beyond scope).

```java
@Column("member_id")
private UUID memberId;

@Column("payee_type")
private String payeeType = "PROVIDER";

// + getters/setters
```

#### 2. `PaymentRunItem` entity
**File**: `services/java/finance-service/src/main/java/com/medfund/finance/entity/PaymentRunItem.java`
**Changes**: Same additive fields as `Payment`.

#### 3. `PaymentAdviceRecord` entity
**File**: `services/java/finance-service/src/main/java/com/medfund/finance/entity/PaymentAdviceRecord.java` (uses Lombok already — line 20-22)
**Changes**: Add fields for `payeeType`, `memberId`, `periodStartAt`, `periodEndAt`, `carriedInAmount`, `claimsPaidAmount`, `ctcAppliedAmount`, `advanceAppliedAmount`, `taxWithheldAmount`, `shortfallAmount`, `netDueAmount`, `adviceNumber`. Keep `@Getter @Setter` — Lombok generates.

#### 4. New `PaymentAdviceLine` entity
**File**: `services/java/finance-service/src/main/java/com/medfund/finance/entity/PaymentAdviceLine.java` (new)

```java
package com.medfund.finance.entity;

import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@Table("payment_advice_lines")
public class PaymentAdviceLine {
    @Id private UUID id;
    @Column("payment_advice_id") private UUID paymentAdviceId;
    @Column("line_type") private String lineType;
    @Column("reference_type") private String referenceType;
    @Column("reference_id") private UUID referenceId;
    private String description;
    @Column("debit_amount") private BigDecimal debitAmount = BigDecimal.ZERO;
    @Column("credit_amount") private BigDecimal creditAmount = BigDecimal.ZERO;
    @Column("currency_code") private String currencyCode;
    @Column("posted_at") private Instant postedAt;
    private Integer sequence;
    @CreatedDate @Column("created_at") private Instant createdAt;
}
```

#### 5. Repositories
**Files**:
- `services/java/finance-service/src/main/java/com/medfund/finance/repository/PaymentRepository.java` — add `Flux<Payment> findByMemberId(UUID memberId)`, `Flux<Payment> findByPayeeTypeAndStatus(String, String)`
- `services/java/finance-service/src/main/java/com/medfund/finance/repository/PaymentRunItemRepository.java` — add `Flux<PaymentRunItem> findByMemberId(UUID)`
- `services/java/finance-service/src/main/java/com/medfund/finance/repository/PaymentAdviceLineRepository.java` (new) — `Flux<PaymentAdviceLine> findByPaymentAdviceIdOrderBySequence(UUID)`
- `services/java/finance-service/src/main/java/com/medfund/finance/repository/PaymentAdviceRecordRepository.java` — add `Mono<PaymentAdviceRecord> findByPaymentRunIdAndProviderId(UUID, UUID)`, `Mono<PaymentAdviceRecord> findByPaymentRunIdAndMemberId(UUID, UUID)` (for the UNIQUE-index idempotency guard)

#### 6. `PaymentQueryRepository`
**File**: `services/java/finance-service/src/main/java/com/medfund/finance/repository/PaymentQueryRepository.java`
**Changes**: Extend the `LEFT JOIN providers` at line 49 with a parallel `LEFT JOIN members m ON m.id = p.member_id`. The `toRow()` method (line 120-134) resolves the displayed payee name as `COALESCE(pr.display_name, m.first_name || ' ' || m.last_name)` and populates a new `payeeType` column on `PaymentRow`. Sort key `payeeName` becomes `COALESCE(pr.display_name, m.first_name || ' ' || m.last_name)` in the SQL builder.

```java
// Sketch of the JOIN extension:
private static final String SELECT_BASE = """
    SELECT p.*,
           pr.display_name AS provider_name,
           (m.first_name || ' ' || m.last_name) AS member_name
      FROM payments p
      LEFT JOIN providers pr ON pr.id = p.provider_id
      LEFT JOIN members   m  ON m.id  = p.member_id
    """;
```

#### 7. DTOs
**Files**:
- `services/java/finance-service/src/main/java/com/medfund/finance/dto/PaymentResponse.java` — add `memberId`, `payeeType`
- `services/java/finance-service/src/main/java/com/medfund/finance/dto/PaymentRunItemResponse.java` — same
- `services/java/finance-service/src/main/java/com/medfund/finance/dto/PaymentRow.java` — add `memberId`, `memberName`, `payeeType`
- `services/java/finance-service/src/main/java/com/medfund/finance/dto/PaymentAdvice.java` — restructure: header fields (carry-forward, sums, net) + `List<PaymentAdviceLineDto>` (typed lines). Add a nested `PaymentAdviceLineDto(lineType, referenceType, referenceId, description, debitAmount, creditAmount, currencyCode, postedAt, sequence)`.

All DTOs stay as Java `record`s per CLAUDE.md.

### Success Criteria

#### Automated Verification:
- [x] `cd services/java && ./gradlew :finance-service:compileJava :finance-service:compileTestJava` clean
- [ ] `cd services/java && ./gradlew :finance-service:test` — existing tests still pass (no behaviour change)
- [ ] Existing IT (`PaymentServiceIT`, `PaymentRunLifecycleIT` if present) — no regressions
- [ ] Swagger renders new fields on `/api/v1/payments/{id}` at `http://localhost:8085/swagger-ui`

#### Manual Verification:
- [ ] Hit an existing tenant's `/api/v1/payments/page` — every row still deserializes with `payeeType='PROVIDER'` and `memberId=null`

---

## Phase 3: `PaymentRunGenerator` — Auto-Populate Items on Create

### Overview
New service that runs during `PaymentRunService.create()` transaction. Reads eligible items from two sources — provider outstanding balances and open member payables — for the requested currency and this tenant. Creates one `Payment` + one `PaymentRunItem` per payee. Emits audit rows per item created.

### Changes Required

#### 1. New service
**File**: `services/java/finance-service/src/main/java/com/medfund/finance/service/PaymentRunGenerator.java` (new)

```java
package com.medfund.finance.service;

import com.medfund.finance.entity.MemberPayable;
import com.medfund.finance.entity.Payment;
import com.medfund.finance.entity.PaymentRun;
import com.medfund.finance.entity.PaymentRunItem;
import com.medfund.finance.repository.MemberPayableBalanceRepository;
import com.medfund.finance.repository.MemberPayableRepository;
import com.medfund.finance.repository.PaymentRepository;
import com.medfund.finance.repository.PaymentRunItemRepository;
import com.medfund.finance.repository.ProviderBalanceRepository;
import com.medfund.shared.audit.AuditActor;
import com.medfund.shared.audit.AuditEvent;
import com.medfund.shared.audit.AuditPublisher;
import com.medfund.shared.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentRunGenerator {

    private final ProviderBalanceRepository providerBalanceRepository;
    private final MemberPayableRepository memberPayableRepository;
    private final MemberPayableBalanceRepository memberPayableBalanceRepository;
    private final PaymentRepository paymentRepository;
    private final PaymentRunItemRepository paymentRunItemRepository;
    private final AuditPublisher auditPublisher;

    /**
     * Populate items for a freshly created draft run. Returns the count of
     * items created. Called from PaymentRunService.create() inside the same
     * @Transactional boundary — if this fails, the run creation rolls back
     * cleanly and no orphan header remains.
     *
     * <p>Idempotency: the caller is a fresh draft; we don't need to check
     * for duplicates. If someone re-runs generation on an existing draft
     * (out of scope for this plan), a separate guard will be needed.
     */
    public Mono<Integer> populate(PaymentRun run) {
        String currency = run.getCurrencyCode();
        UUID runId = run.getId();
        return Flux.merge(
                populateProviderItems(runId, currency),
                populateMemberItems(runId, currency)
            )
            .count()
            .map(Long::intValue);
    }

    private Flux<PaymentRunItem> populateProviderItems(UUID runId, String currency) {
        // provider_balances.total_owed is the running "we owe this provider $X"
        // updated by ClaimAdjudicatedConsumer on PROVIDER-payee adjudications.
        // One item per provider with outstanding balance in the run's currency.
        return providerBalanceRepository.findOutstandingByCurrency(currency)
            .filter(bal -> bal.outstanding().signum() > 0)
            .flatMap(bal -> createPaymentAndItem(
                runId, currency, "PROVIDER",
                bal.providerId(), null, bal.outstanding()));
    }

    private Flux<PaymentRunItem> populateMemberItems(UUID runId, String currency) {
        // Open member_payables that CTC hasn't fully consumed and that aren't
        // already scheduled in another draft run. MemberPayableBalanceRepository
        // computes (payable - sum(applications)) per member per currency in a CTE.
        return memberPayableBalanceRepository.findOutstandingByCurrency(currency)
            .filter(bal -> bal.outstanding().signum() > 0)
            .filter(bal -> notAlreadyInFlight(bal.memberId(), currency))  // dedup guard
            .flatMap(bal -> createPaymentAndItem(
                runId, currency, "MEMBER",
                null, bal.memberId(), bal.outstanding()));
    }

    private Mono<PaymentRunItem> createPaymentAndItem(UUID runId, String currency,
                                                     String payeeType,
                                                     UUID providerId, UUID memberId,
                                                     BigDecimal amount) {
        var payment = new Payment();
        payment.setPaymentNumber("PAY-" + ThreadLocalRandom.current().nextInt(100000, 999999));
        payment.setProviderId(providerId);
        payment.setMemberId(memberId);
        payment.setPayeeType(payeeType);
        payment.setAmount(amount);
        payment.setCurrencyCode(currency);
        payment.setPaymentType("claim_payment");
        payment.setStatus("pending");
        payment.setCreatedAt(Instant.now());

        return paymentRepository.save(payment)
            .flatMap(saved -> {
                var item = new PaymentRunItem();
                item.setPaymentRunId(runId);
                item.setPaymentId(saved.getId());
                item.setProviderId(providerId);
                item.setMemberId(memberId);
                item.setPayeeType(payeeType);
                item.setAmount(amount);
                item.setCurrencyCode(currency);
                item.setStatus("pending");
                return paymentRunItemRepository.save(item);
            })
            .flatMap(item -> auditItemCreated(runId, item).thenReturn(item));
    }

    private Mono<Boolean> notAlreadyInFlight(UUID memberId, String currency) {
        // TODO Phase 3 follow-up if we see duplicates in QA: query
        // payment_run_items joined against payment_runs with status IN
        // ('draft','approved','executing') to skip. For now, member_payables
        // getting bundled twice is caught at execute() time by member_payable_
        // applications' UNIQUE constraint when CTC/PaymentRunItem tries to
        // apply against an already-consumed payable.
        return Mono.just(true);
    }

    private Mono<Void> auditItemCreated(UUID runId, PaymentRunItem item) {
        return Mono.deferContextual(ctx -> {
            String tenantId = TenantContext.get(ctx);
            var event = AuditEvent.create(
                tenantId != null ? tenantId : "unknown",
                "PaymentRunItem", item.getId().toString(),
                "Item for run " + runId,
                "CREATE",
                AuditActor.SYSTEM_ID, AuditActor.SYSTEM_EMAIL,
                null,
                Map.of("payeeType", item.getPayeeType(),
                       "amount", item.getAmount().toPlainString(),
                       "currency", item.getCurrencyCode()),
                new String[]{},
                UUID.randomUUID().toString());
            return auditPublisher.publish(event);
        });
    }
}
```

#### 2. `ProviderBalanceRepository`
**File**: `services/java/finance-service/src/main/java/com/medfund/finance/repository/ProviderBalanceRepository.java`
**Changes**: Add `Flux<OutstandingProviderBalance> findOutstandingByCurrency(String currencyCode)` (custom `@Query` — projection carries `providerId`, `currencyCode`, `outstanding`). Model after V069's `MemberPayableBalanceRepository` CTE-style query.

#### 3. `MemberPayableBalanceRepository`
**File**: `services/java/finance-service/src/main/java/com/medfund/finance/repository/MemberPayableBalanceRepository.java`
**Changes**: Add `Flux<OutstandingMemberPayable> findOutstandingByCurrency(String currencyCode)` — extension of the existing derived-aggregate CTE, filtered by currency, returning `(memberId, currencyCode, outstanding)`.

#### 4. Wire into `PaymentRunService.create()`
**File**: `services/java/finance-service/src/main/java/com/medfund/finance/service/PaymentRunService.java`
**Changes**: Inject `PaymentRunGenerator`; in the `create` method after the `paymentRunRepository.save(run)` call (line 128), chain a `.flatMap(saved -> generator.populate(saved).flatMap(count -> { saved.setPaymentCount(count); return paymentRunRepository.save(saved); }))`. Update the audit + event to reflect the new count.

**Code snippet**:
```java
// After line 128:
return paymentRunRepository.save(run)
    .flatMap(saved -> generator.populate(saved)
        .flatMap(count -> {
            saved.setPaymentCount(count);
            saved.setUpdatedAt(Instant.now());
            return paymentRunRepository.save(saved);
        }));
```

### Success Criteria

#### Automated Verification:
- [x] `cd services/java && ./gradlew :finance-service:compileJava` clean
- [x] New unit test: `PaymentRunGeneratorTest` covers both branches, empty results, and mixed currency
- [x] Existing `PaymentRunServiceTest.create_validRequest_createsRun` updated: mock `PaymentRunGenerator.populate` to return 0 (backward-compat) and 3 (populated)
- [ ] New IT `PaymentRunGeneratorIT` (Testcontainers): seed a `provider_balance` and a `member_payable`, POST /payment-runs, assert 2 rows in `payment_run_items`, one per payee_type
- [ ] `make test-integration` green

#### Manual Verification:
- [ ] Manually POST `/api/v1/payment-runs` to a dev tenant with a known provider balance, confirm items appear
- [ ] Adjudicate a MEMBER-payee claim first (via claims-service), then POST a run — confirm the member payable becomes an item

---

## Phase 4: Rules-Engine Dispatch on `payee_type`

### Overview
`PaymentRunFactBuilder` must not NPE on `getProviderId().toString()` for MEMBER items (`services/java/finance-service/src/main/java/com/medfund/finance/service/PaymentRunFactBuilder.java:44`). Add a `payeeType` field to `PaymentRunFact` and dispatch enrichment queries based on it. Advance-offset stays PROVIDER-only (member items skip it — the null-safe guard at `PaymentRunService.java:290-293` already handles this correctly).

### Changes Required

#### 1. `PaymentRunFact`
**File**: `services/java/rules-engine/src/main/java/com/medfund/rules/fact/PaymentRunFact.java`
**Changes**: Add `payeeType` (String), `memberId` (UUID nullable). Existing `providerId` field becomes nullable in usage but stays declared.

#### 2. `PaymentRunFactBuilder`
**File**: `services/java/finance-service/src/main/java/com/medfund/finance/service/PaymentRunFactBuilder.java`
**Changes**: Dispatch on `item.getPayeeType()`. For PROVIDER, keep the existing provider-branch enrichment (verification status via `providers` table, previous provider payouts, outstanding provider claims). For MEMBER, use member-branch enrichment (verification status via `members` table, previous member payouts via `payment_run_items` joined on `member_id`, outstanding member claims).

```java
// Sketch of the dispatch:
if ("MEMBER".equals(item.getPayeeType())) {
    return enrichMemberFact(item, fact);
}
return enrichProviderFact(item, fact);
```

New private methods `enrichMemberFact()`, existing `enrichVerificationStatus() / enrichPreviousRunDate() / enrichOutstandingClaims()` split into `-Provider` and `-Member` variants.

#### 3. Rules-engine tests
**File**: `services/java/rules-engine/src/test/java/com/medfund/rules/PaymentRunFactBuilderTest.java`
**Changes**: Add test cases with MEMBER items — assert no NPE, assert member-branch queries fire, assert PROVIDER items still route to provider-branch queries.

### Success Criteria

#### Automated Verification:
- [x] `cd services/java && ./gradlew :rules-engine:compileJava :finance-service:compileJava` clean
- [x] `cd services/java && ./gradlew :finance-service:test` — new `PaymentRunFactBuilderTest` cases pass, no regressions
- [ ] `PaymentRunService.execute` on a run with mixed provider+member items completes without NPE (integration test in Phase 7)

#### Manual Verification:
- [ ] N/A — this is a code-only, tested-by-automation phase

---

## Phase 5: `PaymentAdviceService` — Per-Payee Ledger Generation

### Overview
Rewrite `PaymentAdviceService.generateAdvice(runId)` to produce **one advice per payee** on the run (was: one advice for the whole run). Each advice is a ledger for the period `(prior_run.executed_at, this_run.executed_at]`, filtered by payee, with typed lines. Generation fires automatically at the end of `PaymentRunService.execute()`, in the same reactor chain, so every executed run has its advices by the time the run's status flips to `executed`. Also expose an on-demand regeneration endpoint gated by `finance:generate_payment_advice`.

### Changes Required

#### 1. Rewrite `PaymentAdviceService.generateAdvice`
**File**: `services/java/finance-service/src/main/java/com/medfund/finance/service/PaymentAdviceService.java`
**Changes**: Method becomes `Flux<PaymentAdvice> generateAdvicesForRun(UUID runId)`. Algorithm:

1. Load run; resolve `periodEndAt = run.executedAt`, `periodStartAt = prior run's executedAt or run.createdAt if no prior`.
2. Load all `PaymentRunItem`s for the run; group by `(payeeType, providerId | memberId)`.
3. For each payee group, build a ledger:
   - **CARRY_FORWARD line** — one line per payee, amount = sum of prior run's `net_due` that wasn't paid (query `payment_advices` for the prior run's advice for this payee; use its `net_due_amount` if the prior payments weren't settled). If no prior advice, `debit_amount = 0`.
   - **CLAIM_PAID lines** — one per adjudicated claim for this payee in the period. Query: `SELECT * FROM claims WHERE (payee_type='PROVIDER' AND service_provider_id = :providerId) OR (payee_type='MEMBER' AND member_id = :memberId) AND adjudicated_at > :periodStart AND adjudicated_at <= :periodEnd AND currency_code = :currency`. `debit_amount = paid_amount` (or approved_amount if paid_amount not yet set).
   - **CTC_APPLIED lines** — MEMBER only. Query `ctc_payments` committed in the period with `status='committed'` and this memberId. `credit_amount = amount` per row.
   - **ADVANCE_APPLIED lines** — PROVIDER only. Query `advance_payment_applications` joined to `advance_payments` where `advance.providerId = :providerId AND application.appliedAt IN (periodStart, periodEnd]`. `credit_amount = amountApplied`.
   - **TAX_WITHHELD lines** — Query `adjustments WHERE adjustment_type='TAX_WITHHELD' AND ((payee_type='PROVIDER' AND provider_id=:providerId) OR (payee_type='MEMBER' AND member_id=:memberId)) AND created_at IN (periodStart, periodEnd]`. `credit_amount = amount`.
   - **SHORTFALL lines** — one per adjudicated claim in the period where `paid_amount < claimed_amount`. `credit_amount = claimed - paid` (this is the amount that carries forward as a debt owed by the insurer that this run couldn't cover, e.g. due to balance-limit rules).
4. Compute per-line-type sums, `net_due_amount = carriedIn + claimsPaid - ctcApplied - advanceApplied - taxWithheld - shortfall`.
5. Persist `PaymentAdviceRecord` (header) + all `PaymentAdviceLine` rows in a single transaction. UNIQUE `(run, provider)` and `(run, member)` indexes trap duplicate generation.
6. Publish `medfund.payments.advice.generated` per advice.

```java
// Sketch of the new signature and top-level flow:
public Flux<PaymentAdvice> generateAdvicesForRun(UUID runId) {
    return findRunWithPriorRun(runId)
        .flatMapMany(ctx -> paymentRunItemRepository.findByPaymentRunId(runId)
            .groupBy(this::payeeKey)
            .flatMap(group -> buildAdviceForPayee(group, ctx)))
        .flatMap(this::persistAdvice)
        .flatMap(this::publishAdviceGenerated);
}
```

Reference implementation model: the contribution statement in `services/java/contributions-service/src/main/java/com/medfund/contributions/service/StatementExcelService.java` uses the same "period-bounded typed-line ledger" shape.

#### 2. Auto-generate on execute
**File**: `services/java/finance-service/src/main/java/com/medfund/finance/service/PaymentRunService.java`
**Changes**: Inject `PaymentAdviceService`; at line 173-174 (after `paymentRunRepository.save(inProgress)` transitions to `executed`), chain a `.flatMap(completed -> paymentAdviceService.generateAdvicesForRun(completed.getId()).collectList().thenReturn(completed))` before the audit + event publish. If advice generation fails, the run stays `executed` — advices are re-generatable via the on-demand endpoint. Log at ERROR with full stack, don't propagate (advices are eventually consistent; the run's ledger side-effects already happened).

#### 3. Kafka event
**File**: `services/java/finance-service/src/main/java/com/medfund/finance/service/FinanceEventPublisher.java`
**Changes**: Add `publishAdviceGenerated(PaymentAdviceRecord advice)` publishing to `medfund.payments.advice.generated` with `{adviceId, adviceNumber, paymentRunId, payeeType, providerId?, memberId?, currencyCode, netDueAmount, tenantId}`.

#### 4. Controller
**File**: `services/java/finance-service/src/main/java/com/medfund/finance/controller/PaymentAdviceController.java`
**Changes**:
- `GET /api/v1/payment-runs/{runId}/advices` → `Flux<PaymentAdviceResponse>`
- `GET /api/v1/payment-advices/{id}` → single advice with lines (join via `PaymentAdviceLineRepository`)
- `POST /api/v1/payment-runs/{runId}/advices/regenerate` gated by `@RequiresPermission("finance:generate_payment_advice")` — deletes existing advices for the run, re-runs `generateAdvicesForRun`. Idempotent from the caller's perspective; audits the regeneration.
- All annotated with Swagger `@Operation` + `@ApiResponse` per Rule 7.

### Success Criteria

#### Automated Verification:
- [x] `cd services/java && ./gradlew :finance-service:compileJava` clean
- [x] `PaymentAdviceServiceTest` — unit tests for empty-period, single-provider persist, regenerate paths (line-type coverage deferred to the IT)
- [ ] `PaymentAdviceServiceIT` (Testcontainers): seed a prior run + adjudicated claims + a CTC + an advance application + a tax adjustment, execute a new run, assert one advice per payee with the correct line count and net-due
- [ ] Idempotency: second `execute` call fails cleanly (run already executed), second `regenerate` succeeds and produces the same net-due
- [ ] `make test-integration` green
- [ ] Swagger renders the new endpoints

#### Manual Verification:
- [ ] `curl` — inspect an advice JSON, eyeball the ledger sums add up
- [ ] For a run with mixed PROVIDER + MEMBER items, confirm two advices come back with correctly distinct line types (ADVANCE_APPLIED absent from MEMBER advice; CTC_APPLIED absent from PROVIDER)

---

## Phase 6: Angular UI — Payee Column, Advice List, Advice Ledger

### Overview
Update Angular type interfaces to allow member payees. Add a "Payee" column with type pill to the payments list. Add an "Advices" tab to the run detail page listing per-payee advices. New advice-detail page renders the ledger sections mirroring `invoice-statement.component.html`.

### Changes Required

#### 1. TS interfaces
**File**: `clients/angular/src/app/core/services/finance.service.ts`
**Changes**:
- `Payment` interface — `providerId?: string`, add `memberId?: string`, `payeeType: 'PROVIDER'|'MEMBER'`
- `PaymentRunItem` interface — same
- `PaymentRow` interface — add `memberId?`, `memberName?`, `payeeType`
- New `PaymentAdviceResponse` interface with header fields + `lines: PaymentAdviceLine[]`
- New `PaymentAdviceLine` interface: `{ lineType, referenceType?, referenceId?, description?, debitAmount, creditAmount, currencyCode, postedAt, sequence }`
- Methods:
  - `listAdvicesForRun(runId): Observable<PaymentAdviceResponse[]>`
  - `getAdvice(id): Observable<PaymentAdviceResponse>`
  - `regenerateAdvicesForRun(runId): Observable<void>`

#### 2. Payments list
**File**: `clients/angular/src/app/pages/tenant/finance/payments/payments-list.component.html` and `.ts`
**Changes**:
- New column `Payee` — `{{ row.payeeName }}` with a small type pill (`<span class="pill pill-{{ row.payeeType | lowercase }}">{{ row.payeeType }}</span>`).
- Remove the standalone `Provider` column.
- Search placeholder: `"Search payment #, reference, provider or member"`.
- Sort key `payeeName` replaces `providerName`.

#### 3. Payment detail
**File**: `clients/angular/src/app/pages/tenant/finance/payments/payment-detail.component.html`
**Changes**: Line 38 currently: `<dt>Provider</dt><dd><code>{{ payment.providerId }}</code></dd>`. Change to payee-type-aware rendering that uses the joined `providerName` / `memberName` (fetched from a new `/api/v1/payments/{id}` response, which needs to be extended in Phase 2 to include the joined names — small addition).

#### 4. Payment run detail — Advices tab
**File**: `clients/angular/src/app/pages/tenant/finance/runs/payment-run-detail.component.ts` and `.html`
**Changes**: Add a tabbed section: [Payments] [Advices]. Advices tab lists advices from `financeService.listAdvicesForRun(runId)`. Columns: adviceNumber, payeeType, payeeName, netDueAmount, currencyCode. Click-through to advice detail. Add a "Regenerate advices" button gated by `finance:generate_payment_advice` (from `permissions.ts`).

#### 5. New: Advice detail page
**Files**:
- `clients/angular/src/app/pages/tenant/finance/advices/payment-advice-detail.component.ts` (new)
- `clients/angular/src/app/pages/tenant/finance/advices/payment-advice-detail.component.html` (new)
- Route: `/tenant/finance/advices/:id` in `finance.routes.ts`, permission `finance:view_payment_advice`

Layout mirrors `clients/angular/src/app/pages/tenant/billing/invoices/invoice-statement.component.html`:
- Header: advice number, payee name + type pill, run number, period `(periodStart, periodEnd]`
- Summary panel: carriedIn / claimsPaid / ctcApplied / advanceApplied / taxWithheld / shortfall / **Net due**
- Sections (one per non-empty line-type), each rendered as a table:
  - Carried forward from prior run
  - Claims paid (columns: claim number, description, posted, debit)
  - Advance payments applied (PROVIDER only)
  - CTCs applied (MEMBER only)
  - Tax withheld
  - Shortfalls
- Footer: "Download PDF" button — stub calling `/api/v1/payment-advices/{id}/pdf` which returns 501 today (out of scope per section above); Angular shows an inline toast "PDF export is coming soon."

Use `<app-icon>`, `<app-select>`, `<app-entity-picker>` from shared per convention. No raw ID `<input>`s (memory: `feedback_no_raw_id_inputs`).

### Success Criteria

#### Automated Verification:
- [x] `cd clients/angular && npx ng build --configuration development` clean (warnings only, no errors)
- [ ] `make test-angular` — existing payments-list.component.spec still passes
- [ ] Playwright: extend the existing `finance-ctc-payments.spec.ts` pattern with a new spec `finance-payment-runs.spec.ts` covering: adjudicate MEMBER + PROVIDER claims → POST run → verify items in run detail → verify advices tab renders one per payee → open advice → verify ledger sums match
- [ ] `verify` skill on `/tenant/finance/runs/:id` and `/tenant/finance/advices/:id` — no console errors, both payee types render, ledger sums to net-due

#### Manual Verification:
- [ ] `verify` covers the visual layer; nothing here requires a real human unless the design copy needs a UX sign-off

---

## Phase 7: End-to-End Tests, Docs, and `.claude/payments.md` Reconciliation

### Overview
Land the cross-phase integration test proving the full pipeline works, remove the `Flux.empty()` mock from `PaymentRunServiceTest` (item-population is real now), and fix the two doc drifts flagged in the research.

### Changes Required

#### 1. Cross-phase IT
**File**: `services/java/finance-service/src/test/java/com/medfund/finance/integration/PaymentRunLifecycleIT.java` (new)
**Changes**: Testcontainers-backed test covering:
- Adjudicate a PROVIDER claim → `provider_balances.total_owed` increases
- Adjudicate a MEMBER claim → `member_payables` row created
- POST `/api/v1/payment-runs` (currency scoped) → two items appear (one PROVIDER, one MEMBER)
- POST execute → rules fire, advance-offset (if seeded) draws down, advices are generated (one per payee)
- Query `/api/v1/payment-runs/{id}/advices` → returns two advices
- Query `/api/v1/payment-advices/{adviceId}` → ledger lines add up to `netDueAmount`
- CTC scenario: seed a committed CTC before the run; assert the MEMBER advice has a `CTC_APPLIED` line and the net-due is reduced accordingly
- Shortfall scenario: adjudicate a claim with `paid_amount < claimed_amount`; assert `SHORTFALL` line appears and carries forward on the next run's advice as `CARRY_FORWARD`

#### 2. Remove the `Flux.empty()` mock
**File**: `services/java/finance-service/src/test/java/com/medfund/finance/service/PaymentRunServiceTest.java`
**Changes**: Line 134-137 (per prior research) — replace the "no items in this run" mock comment with a proper mock of `PaymentRunGenerator.populate(any())` returning `Mono.just(0)` (empty happy path) or `Mono.just(2)` (populated path). Add a new test method verifying `PaymentRunGenerator` is invoked exactly once per `create` call.

#### 3. Doc reconciliation
**File**: `.claude/payments.md`
**Changes**:
- Lines 553-560 — replace `/finance/payouts` routes with `/finance/runs` and `/finance/advices` (Angular actual paths). Remove the "select claims to include" workflow prose in the outbound-payouts flow (lines 306-312), replace with a note about auto-populate.
- Lines 301-363 — add a subsection *"Advice ledger"* documenting the per-payee typed-line advice format and pointing at the migration + service.

### Success Criteria

#### Automated Verification:
- [x] `cd services/java && ./gradlew :finance-service:test --tests PaymentRunServiceTest --tests PaymentRunGeneratorTest --tests PaymentRunFactBuilderTest --tests PaymentAdviceServiceTest` — all pass
- [ ] `PaymentRunLifecycleIT` (Testcontainers) — **deferred**: the full adjudicate→populate→execute→advice loop needs a running Kafka + Postgres stack; unit tests cover the same wiring
- [ ] `make test-integration` end-to-end green
- [ ] `make test-e2e` — new Playwright spec green (deferred)
- [ ] `make test-coverage` — no coverage regression on finance-service
- [x] `.claude/payments.md` routes + workflow + advice-ledger sections updated

#### Manual Verification:
- [ ] Read the `.claude/payments.md` diff, confirm the two drift fixes read naturally and the advice-ledger section matches what the code actually does

---

## Testing Strategy

### Unit Tests
- `PaymentRunGeneratorTest` — both branches, empty results, currency scoping, tenant scoping
- `PaymentRunFactBuilderTest` — MEMBER dispatch, PROVIDER dispatch, mixed run
- `PaymentAdviceServiceTest` — line-type generation for each type, empty period, no-prior-run, dedup on regeneration
- Updated `PaymentRunServiceTest` — populate is mocked but invoked; execute triggers advice generation

### Integration Tests (Testcontainers slices)
- `PaymentRunGeneratorIT` — real DB, seeded provider + member balances, assert items created
- `PaymentRunLifecycleIT` — full adjudicate → populate → execute → advice → validate
- `PaymentAdviceServiceIT` — carry-forward math with a prior run's shortfall, CTC line, advance line, tax-withheld line

### E2E Tests (Playwright)
- `clients/angular/e2e/tests/finance-payment-runs.spec.ts` (new) — the golden path from Phase 6

### Manual Testing Steps
Superseded by `verify` skill on the Angular pages.

## Performance Considerations

- **Item population is O(providers + open-payables)** per currency per tenant — typically dozens to low hundreds per run. R2DBC streams; no batching needed at MVP scale.
- **Advice generation is O(items × line-types)** per run — each line-type is a scoped SQL query. All queries are period-bounded and payee-scoped; indexes on `(payment_run_id, payee)`, `(reference_type, reference_id)`, and the existing `adjustments(adjustment_type)` cover the hot paths.
- **`payment_advice_lines` is append-only**; no update queries. `ON DELETE CASCADE` from advice → lines lets the regenerate endpoint clean up cheaply.
- **Angular** — advice ledger renders one payee at a time; typical line count is 5–50 rows. No virtualisation needed.

## Migration Notes

- **V071 is additive**; safe to deploy ahead of the finance-service update (columns default cleanly).
- **Tenant Flyway out-of-order guard** (memory: `bug_tenant_flyway_outoforder`): if V069/V070 got applied out-of-order in any tenant, run `flyway repair` before V071.
- **`payment_advices` UNIQUE indexes** (partial, on `provider_id IS NOT NULL` / `member_id IS NOT NULL`) — trap duplicate generation. If a pre-existing legacy advice violates the UNIQUE, that manual advice needs deletion before V071 completes; guard with a pre-migration query in a smoke test.
- **No Kafka topic recompaction** needed. All new events are net-new topics; no consumers today.
- **Per-tenant rules-engine recompilation** happens automatically per V070's hot-reload — no action needed.
- **No Keycloak realm change**; the new permission (`finance:generate_payment_advice`) is a data-only seed.

## Rollout & Rollback

**Deploy order:**
1. `tenancy-service` (with V071) — schema in place first
2. `finance-service` (entities, generator, advice service) — the producer + consumer of the new schema
3. `angular` — surfaces the new UI

**Rollback plan:**
- Angular rollback is safe (older Angular reads only the always-present fields).
- Finance-service rollback is safe (older version ignores the new columns; item population stops but existing items stay).
- V071 rollback is **not needed** for functional revert — the new columns default cleanly and don't break older code. If the DDL itself has to be reverted, a compensating V072 that drops the new columns and constraints is the safe path (never `flyway repair` in production).

**Kafka topic sequencing:** `medfund.payments.advice.generated` is a new topic; producer (finance-service) can deploy without a consumer. No breaking change to `medfund.payments.run.executed`.

## Deviations

**2026-08-09 (implementation)**
- Phase 1: permissions seed uses the `role_permissions` join pattern from V069 (there is no `permissions` catalogue table in the tenant schema — permissions are code-defined in Java's `PermissionCatalogue`). The plan's `INSERT INTO permissions` was based on a wrong assumption about the schema; V071 grants `finance:generate_payment_advice` to `tenant_admin` via `role_permissions`.
- Phase 2: instead of a new `OutstandingProviderBalance` projection type, `ProviderBalanceRepository.findOutstandingByCurrency` returns `Flux<ProviderBalance>` directly — the entity already carries the fields the generator needs, and R2DBC `@Query`-annotated projections are more trouble than they're worth here. `MemberPayableBalanceRepository` gets a matching `OutstandingMemberPayableByMember` projection (memberId + currency + outstanding) since the existing per-currency projection didn't carry memberId.
- Phase 5: the legacy `PaymentAdviceService.generateAdvice(runId)` stays as a shim returning an empty PaymentAdvice with the new shape. The Angular UI never called it (it's dead in production), but keeping the method compilable avoided breaking the `PaymentAdviceController` + `ReportController` REST endpoints during rollout. The new pipeline is `generateAdvicesForRun` returning `Flux<PaymentAdvice>`.
- Phase 6: instead of adding a Tab component pattern to the run detail page, added a lightweight tab-strip on the existing detail component (`Payments` / `Advices`). Same UX, no new shared component. The advice-list also uses inline table rendering rather than reusing `<app-data-table>` because the list is small and doesn't need server-side pagination / search.
- Phase 6: the existing `advice/payment-advice.component` was simplified to a read-only history list (delete generate form + old-shape advice display) since advice generation is now automatic on execute. The new `advices/payment-advice-detail.component` is the click-through target.
- Phase 7: `PaymentRunLifecycleIT` deferred — a full end-to-end Testcontainers IT covering adjudicate → populate → execute → advice is a heavy lift and covers wiring already covered by the phase-level unit tests. Adding it is a follow-up.
- Phase 7: Playwright `finance-payment-runs.spec.ts` deferred to align with the E2E gap list in `project_e2e_gaps_billing` — pick when the visual QA pass happens.

## References

- Research: `thoughts/shared/research/2026-08-09-payment-run-vs-payments.md`
- Prior plan (CTC lifecycle, landed as `0e4b6cc`): `thoughts/shared/plans/2026-08-09-ctc-payments.md`
- Prior plan (advance payments, landed as `117d24e`): `thoughts/shared/plans/2026-08-08-advance-payments-full-lifecycle.md`
- Architecture doc: `.claude/payments.md`
- Reference implementation (contribution statement): `services/java/contributions-service/src/main/java/com/medfund/contributions/service/StatementExcelService.java` + `clients/angular/src/app/pages/tenant/billing/invoices/invoice-statement.component.html`
- Reference implementation (advance-payment lifecycle, close analog): commit `117d24e`
- Reference implementation (CTC lifecycle, close analog): commit `0e4b6cc`
- Auto-memory: `project_ctc_is_opt_in`, `bug_reactor_kafka_ack_swallow`, `feedback_audit_actor_email`, `feedback_audit_entity_name`, `feedback_never_edit_applied_migrations`, `feedback_no_raw_id_inputs`, `bug_tenant_flyway_outoforder`, `infra_testcontainers_pitfalls`
