---
date: 2026-08-08
git_commit: 1fd6c94838d659eaf5d0d56d689f87acb1b1b6e8
branch: main
ticket: none
research:
  - thoughts/shared/research/2026-08-08-advance-payments.md
steer: "Full lifecycle (CTA + offset + reversal + approval gate + consumed-by writeback). Offset = rule-driven withhold via existing PROVIDER_PAYMENT rules category. Approval threshold = column on tenants config table. Application write = on payment run execute()."
services_touched: [finance-service, tenancy-service, rules-engine, gateway, angular]
status: draft
---

# Advance Payments — Full Lifecycle Implementation Plan

## Deviations

- **2026-08-09 (Phase 2)** — Provider-side advances now write back to `provider_balances` via the existing `ProviderBalanceService.updateBalance(...)` seam. On approve: `paidDelta = +amount`, which increments `total_paid` and (through the service's recompute) drops `outstanding_balance`. On reverse of a provider advance: `paidDelta = -amount`, symmetric. Member advances do **not** touch any balance — per the dev, billing-side member balance and finance-side payouts are kept separate, and there is no finance-side `member_balances` table today. Rationale: `total_paid` should reflect real cash out to a provider, otherwise reconciliation understates disbursements. Application-time still does not double-count because the run item amount is reduced by the withheld portion before commit.
- **2026-08-09 (Phase 1)** — Migration used the actual permissions schema (`role_permissions` seeded against `roles.name = 'tenant_admin'`), not the plan's imagined `permissions_catalogue` table. Also mirrored the two new permissions in `services/java/shared/src/main/resources/permissions.yaml`, `services/java/shared/src/main/java/com/medfund/shared/security/Permissions.java` (constants + `ALL` set), and `clients/angular/src/app/core/security/permissions.ts` — the three-way sync required by `Permissions.java`'s javadoc.
- **2026-08-09 (Phase 1)** — `advance_payment_applications` also carries `payment_run_id` and `payment_run_item_id` FKs (plan only had `payment_id`). Run-item FK is the primary reconciliation key; `payment_id` may be null on the row until the item's real payment is created.

## Overview

Close both gaps surfaced by `thoughts/shared/research/2026-08-08-advance-payments.md`: the orphaned record UI and the unfed offset seam. Ship the complete lifecycle — record (with CTA), tenant-configurable approval gate, append-only reversal via compensating entry, and offset into payment runs via the existing `PROVIDER_PAYMENT` Drools category. Every consumption gets tracked in a new `advance_payment_applications` bridging table so "how much unreconciled advance does provider X have?" becomes a single query.

## Current State Analysis

- Backend: entity, repository, service (`create` + list/paged/get), controller (4 endpoints), and audit are complete in `services/java/finance-service/`. See `services/java/finance-service/src/main/java/com/medfund/finance/entity/AdvancePayment.java:1-49` and `services/java/finance-service/src/main/java/com/medfund/finance/service/AdvancePaymentService.java:40-102`.
- No approval gate, no reversal endpoint, no state machine — `advance_payments` has no `status` column (unlike `Adjustment` at `services/java/finance-service/src/main/java/com/medfund/finance/entity/Adjustment.java:38`).
- `AdvancePayment.paymentId` FK on the DB (`services/java/tenancy-service/src/main/resources/db/migration/tenant/V016__finance_schema.sql:137`) is never populated by any code path — one-to-one shape is too rigid for partial application anyway.
- `PaymentRunService.applyTenantRulesToItems` at `services/java/finance-service/src/main/java/com/medfund/finance/service/PaymentRunService.java:241` calls the zero-arg `decisionService.decide(item)` overload — `advancePaid` is always `BigDecimal.ZERO`. The rules-engine seam (`PaymentRunFact.advancePaid` at `services/java/rules-engine/src/main/java/com/medfund/rules/fact/PaymentRunFact.java:30`) is unfed.
- Angular has all three page components (`list`, `form`, `detail`) at `clients/angular/src/app/pages/tenant/finance/advance/` and their routes in `finance.routes.ts:83-99`, but a repo-wide grep finds zero UI anchors to `payments/advance/add`. The form is orphaned.

## Desired End State

- An operator with `finance:manage_advance_payments` can click **Record advance payment** from the list header, complete the form, and get a persisted advance. Small amounts auto-approve; amounts above the tenant's configured threshold land in `pending` and require a second click ("Approve") from someone with the same permission.
- Any `approved` or `applied` advance can be reversed by posting a compensating row (`type=REVERSAL`, `reverses_advance_id=<original>`). Originals never mutate; the outstanding-balance query is `SUM(amount WHERE type=ADVANCE) - SUM(amount WHERE type=REVERSAL) - SUM(applications.amount_applied)`.
- When `PaymentRunService.execute()` fires, it aggregates the outstanding advance balance per (payeeId, currencyCode), converts across currencies at the run's execution-date rate, passes it to `PaymentRunDecisionService.decide(item, advancePaid)`, writes `advance_payment_applications` rows for whatever the rule engine chose to apply, and flips the affected advances' status to `applied` once fully consumed.
- The Drools template library ships a starter `PROVIDER_PAYMENT` rule ("when `advancePaid >= amountDue`, `withhold(100, "fully offset by advance")`") that tenants can copy or tune.
- The Angular detail page shows the status timeline (recorded → approved → applied/reversed) and a table of the payment runs that consumed each advance.

### Verification

- `finance:view_advance_payments` user opens `/tenant/finance/payments/advance/<id>` — sees status, approval trail, applications.
- Provider with $200 outstanding advance runs against a $1000 payment. Rule fires. Item's amount reduces to $800 (or item is withheld — depending on the rule). Application row records $200 consumed. Advance's status flips to `applied`.
- Playwright golden path covers record → approve → run auto-applies → reverse.

### Key Discoveries

- **Reversal convention is append-only compensating entries.** `AdjustmentController` explicitly errors "post a reversing adjustment instead" (`services/java/finance-service/src/main/java/com/medfund/finance/controller/AdjustmentController.java:116`). Small enhancement: add a `reverses_advance_id` FK so the compensating row explicitly links to its origin — avoids the join-by-reference-string pattern.
- **State machine mirror is `PaymentRun`.** Simple `status` column with CHECK constraint; approval sets status + publishes Kafka event. No dual-approval logic exists anywhere in the codebase today — this plan introduces the first threshold gate. See `services/java/finance-service/src/main/java/com/medfund/finance/service/PaymentRunService.java:171-196` for the approve pattern.
- **Rules-engine category already fits.** `PROVIDER_PAYMENT` at `services/java/rules-engine/src/main/java/com/medfund/rules/model/RuleCategory.java:57` is exactly the category `applyTenantRulesToItems` already fires. No new category required — just a new starter template in `services/java/rules-engine/src/main/java/com/medfund/rules/template/providers/ProviderPaymentTemplates.java`.
- **PaymentRunFact already carries the seam.** `advancePaid` (line 30), `withhold(pct, reason)` action (line 53) — the fact just needs a real value fed in.
- **ExchangeRateService is available** at `services/java/tenancy-service/src/main/java/com/medfund/tenancy/service/ExchangeRateService.java`, with a shared `ExchangeRateProvider` interface at `services/java/shared/src/main/java/com/medfund/shared/currency/ExchangeRateProvider.java`. Finance-service reuses this — no new FX code.
- **Tenants table is `public`-schema** (`services/java/tenancy-service/src/main/resources/db/migration/public/V101__tenants.sql`). Threshold config goes into a new public migration (V128), matching the `tenant_proration_config` pattern in `V127__tenant_proration_config.sql`. Latest tenant migration is V067, so advance-payment schema changes go into `V068__advance_payment_lifecycle.sql`.
- **`finance:approve_payment_run` permission already exists** (`services/java/shared/src/main/resources/permissions.yaml:56-75`). Following the same naming, this plan adds `finance:approve_advance_payment` and `finance:reverse_advance_payment`.

## What We're NOT Doing

- **Group- or scheme-level advances.** The provider XOR member constraint stays. Group liaison workflow is a separate ticket.
- **Refactoring existing `AdvancePayment.paymentId` FK.** Leaving it in place, unused, with a code comment marking it deprecated in favour of `advance_payment_applications`. Dropping a column is destructive and adds risk with no upside here.
- **Real-time balance push to the Angular dashboard.** The existing dashboard tile continues to source from `AdminService.TenantStats` (server-computed KPI). Live balance updates are follow-up.
- **Rules-engine authoring UI for the new template.** The starter template is shipped as a `TemplateProvider` bean; tenants that want to customise it use the existing "New Rule" modal in the admin surface — no UI changes needed there.
- **Multi-approver dual-approval.** Threshold gate is single-approver-different-from-recorder. True two-of-three approvals is out of scope.
- **Historical migration of any prior advance data.** There is no production data to backfill; the audit trail for the currently-only-API-creatable advances is intact.

## Implementation Approach

Four phases, each independently verifiable:

1. **Schema + entities** (backend only, no behaviour change).
2. **Approval gate + reversal endpoints** (backend service + controllers).
3. **Offset wiring in PaymentRunService + starter Drools template** (cross-cutting; finance-service + rules-engine).
4. **Angular UI** (CTA, approve action, reverse action, detail-page timeline + applications, Playwright).

Rollout order matches phases. No Kafka contract removals — all new topics/events are additive. Because Phase 3 only *populates* `advancePaid` in an existing fact, tenants who load no advance-offset rule see zero behaviour change (the rule engine no-ops when nothing matches).

---

## Phase 1: Schema + entities

### Overview

DDL for the append-only lifecycle: add `status`, `type`, approval columns, and `reverses_advance_id` to `advance_payments`; create `advance_payment_applications`; create the tenant-scoped `tenant_advance_payment_config` in the public schema; seed two new permissions. Entity classes updated. No new endpoints yet.

### Changes Required

#### 1. Tenant-schema migration

**File:** `services/java/tenancy-service/src/main/resources/db/migration/tenant/V068__advance_payment_lifecycle.sql`

```sql
-- Advance payment lifecycle — status column, append-only reversal link,
-- approval trail. Compensating-entry pattern lifted from Adjustment
-- (see AdjustmentController line 116). Originals never mutate on reversal;
-- a REVERSAL row references its origin via reverses_advance_id and negates
-- the outstanding balance query.

ALTER TABLE advance_payments
    ADD COLUMN IF NOT EXISTS type              text        NOT NULL DEFAULT 'ADVANCE',
    ADD COLUMN IF NOT EXISTS status            text        NOT NULL DEFAULT 'approved',
    ADD COLUMN IF NOT EXISTS approved_by       uuid,
    ADD COLUMN IF NOT EXISTS approved_at       timestamptz,
    ADD COLUMN IF NOT EXISTS reverses_advance_id uuid;

ALTER TABLE advance_payments
    ADD CONSTRAINT advance_payments_type_check
        CHECK (type IN ('ADVANCE', 'REVERSAL')),
    ADD CONSTRAINT advance_payments_status_check
        CHECK (status IN ('pending', 'approved', 'applied', 'reversed')),
    ADD CONSTRAINT advance_payments_reversal_link_check
        CHECK ((type = 'REVERSAL') = (reverses_advance_id IS NOT NULL)),
    ADD CONSTRAINT advance_payments_reverses_fk
        FOREIGN KEY (reverses_advance_id) REFERENCES advance_payments(id);

CREATE INDEX IF NOT EXISTS idx_advance_payments_status
    ON advance_payments(status);
CREATE INDEX IF NOT EXISTS idx_advance_payments_reverses
    ON advance_payments(reverses_advance_id)
    WHERE reverses_advance_id IS NOT NULL;

-- Existing rows: default status='approved' is correct for the tiny set of
-- API-created advances that exist today (no threshold was ever enforced).
-- Backfill approved_at from recorded_at so the audit trail has a coherent
-- timestamp.
UPDATE advance_payments
   SET approved_at = recorded_at,
       approved_by = recorded_by
 WHERE approved_at IS NULL;

-- Bridging: an advance can apply partially to multiple payments across
-- multiple runs. Recorded by PaymentRunService.execute() when the rules
-- engine consumes advance balance against a run item.
CREATE TABLE IF NOT EXISTS advance_payment_applications (
    id                   uuid           PRIMARY KEY DEFAULT gen_random_uuid(),
    advance_payment_id   uuid           NOT NULL REFERENCES advance_payments(id),
    payment_id           uuid           NOT NULL REFERENCES payments(id),
    amount_applied       numeric(19, 4) NOT NULL CHECK (amount_applied > 0),
    currency_code        varchar(3)     NOT NULL,
    applied_at           timestamptz    NOT NULL DEFAULT now(),
    applied_by           uuid
);

CREATE INDEX IF NOT EXISTS idx_apa_advance
    ON advance_payment_applications(advance_payment_id);
CREATE INDEX IF NOT EXISTS idx_apa_payment
    ON advance_payment_applications(payment_id);
```

#### 2. Public-schema migration — tenant config

**File:** `services/java/tenancy-service/src/main/resources/db/migration/public/V128__tenant_advance_payment_config.sql`

```sql
-- Per-tenant approval threshold. Advances at or below the threshold auto-
-- approve on record; above it, they stay status='pending' until a second
-- operator approves. Threshold is expressed in a chosen currency; FX
-- conversion to the advance's currency happens at record-time using
-- ExchangeRateService (tenancy-service).

CREATE TABLE IF NOT EXISTS tenant_advance_payment_config (
    tenant_id                    uuid           PRIMARY KEY REFERENCES tenants(id) ON DELETE CASCADE,
    approval_threshold_amount    numeric(19, 4) NOT NULL DEFAULT 500,
    approval_threshold_currency  varchar(3)     NOT NULL DEFAULT 'USD',
    updated_at                   timestamptz    NOT NULL DEFAULT now(),
    updated_by                   uuid
);

-- Seed every existing tenant with the default. Idempotent for reruns.
INSERT INTO tenant_advance_payment_config (tenant_id)
    SELECT id FROM tenants
    ON CONFLICT (tenant_id) DO NOTHING;
```

#### 3. Permissions seed

**File:** `services/java/tenancy-service/src/main/resources/db/migration/tenant/V068__advance_payment_lifecycle.sql` (appended to the tenant migration above)

```sql
-- Two new permissions. approve_advance_payment is separated from
-- manage_advance_payments so tenants can grant "record, don't approve"
-- (finance clerks) vs "approve" (finance HoD).
INSERT INTO permissions_catalogue (permission, description) VALUES
    ('finance:approve_advance_payment', 'Approve a pending advance payment above the tenant threshold'),
    ('finance:reverse_advance_payment', 'Post a compensating reversal for an approved or applied advance')
ON CONFLICT (permission) DO NOTHING;

INSERT INTO role_permissions (role_id, permission)
SELECT r.id, p.permission
  FROM roles r
 CROSS JOIN (VALUES
    ('finance:approve_advance_payment'),
    ('finance:reverse_advance_payment')
 ) AS p(permission)
 WHERE r.code = 'tenant_admin'
ON CONFLICT (role_id, permission) DO NOTHING;
```

#### 4. Entity — AdvancePayment

**File:** `services/java/finance-service/src/main/java/com/medfund/finance/entity/AdvancePayment.java`

Add five fields with `@Column` annotations mapping to the new columns. Keep `paymentId` — comment marks it deprecated in favour of `advance_payment_applications`.

```java
@Column("type")
private String type;                    // 'ADVANCE' | 'REVERSAL'

@Column("status")
private String status;                  // 'pending' | 'approved' | 'applied' | 'reversed'

@Column("approved_by")
private UUID approvedBy;

@Column("approved_at")
private Instant approvedAt;

@Column("reverses_advance_id")
private UUID reversesAdvanceId;

// Deprecated — use advance_payment_applications for consumption tracking.
// Column retained for historical rows; new code paths must not write here.
@Column("payment_id")
@Deprecated
private UUID paymentId;
```

#### 5. New entity — AdvancePaymentApplication

**File:** `services/java/finance-service/src/main/java/com/medfund/finance/entity/AdvancePaymentApplication.java` (new)

```java
@Getter
@Setter
@Table("advance_payment_applications")
public class AdvancePaymentApplication {
    @Id
    private UUID id;
    @Column("advance_payment_id") private UUID advancePaymentId;
    @Column("payment_id")         private UUID paymentId;
    @Column("amount_applied")     private BigDecimal amountApplied;
    @Column("currency_code")      private String currencyCode;
    @Column("applied_at")         private Instant appliedAt;
    @Column("applied_by")         private UUID appliedBy;
}
```

#### 6. Repositories

**File:** `services/java/finance-service/src/main/java/com/medfund/finance/repository/AdvancePaymentRepository.java`

Add query for outstanding balance aggregation (used by Phase 3):

```java
@Query("""
    SELECT ap.currency_code AS currency_code,
           SUM(CASE WHEN ap.type = 'ADVANCE'  THEN ap.amount ELSE 0 END)
         - SUM(CASE WHEN ap.type = 'REVERSAL' THEN ap.amount ELSE 0 END)
         - COALESCE((SELECT SUM(amount_applied)
                       FROM advance_payment_applications
                      WHERE advance_payment_id IN (
                           SELECT id FROM advance_payments
                            WHERE provider_id = :providerId
                              AND currency_code = ap.currency_code)), 0)
                                                            AS outstanding
      FROM advance_payments ap
     WHERE ap.provider_id = :providerId
       AND ap.status IN ('approved', 'applied')
     GROUP BY ap.currency_code
    HAVING (SUM(CASE WHEN ap.type = 'ADVANCE'  THEN ap.amount ELSE 0 END)
          - SUM(CASE WHEN ap.type = 'REVERSAL' THEN ap.amount ELSE 0 END)
          - COALESCE((SELECT SUM(amount_applied)
                        FROM advance_payment_applications
                       WHERE advance_payment_id IN (
                            SELECT id FROM advance_payments
                             WHERE provider_id = :providerId
                               AND currency_code = ap.currency_code)), 0)) > 0
""")
Flux<OutstandingAdvanceBalance> findOutstandingByProvider(UUID providerId);

// Symmetric findOutstandingByMember(UUID memberId).
```

Where `OutstandingAdvanceBalance` is a small `record` in the same package: `(String currencyCode, BigDecimal outstanding)`.

**File:** `services/java/finance-service/src/main/java/com/medfund/finance/repository/AdvancePaymentApplicationRepository.java` (new)

Basic reactive CRUD + `findByAdvancePaymentId` and `findByPaymentId`.

### Success Criteria

#### Automated Verification
- [x] Java compiles: `cd services/java && ./gradlew :finance-service:build`
- [ ] Unit tests still green: `make test-java` — 2 pre-broken tests unrelated (`bug_claim_save_mock_id_npe`); will be fixed in Phase 2 rewrite of the service
- [ ] Migration IT applies clean on a fresh Testcontainer: `make test-integration` — the tenancy-service IT harness re-runs migrations against a fresh Postgres per suite.
- [ ] `V068` shows in `flyway_schema_history` after boot with `applied_by IS NOT NULL` and no `success = false`.
- [ ] `V128` applied to the public schema; `SELECT COUNT(*) FROM tenant_advance_payment_config` returns the tenant count.
- [ ] Existing AdvancePaymentServiceTest still passes (backfill defaults ensure no service-layer regression).

#### Manual Verification
- [ ] None for this phase — no user-visible change.

**Implementation Note**: after this phase's automated verification passes, proceed to Phase 2. No manual step gate.

---

## Phase 2: Approval gate + reversal endpoints

### Overview

Extend `AdvancePaymentService` with a threshold check on create, an `approve` method, and a `reverse` method that posts a compensating row. Two new REST endpoints. Kafka events for approve + reverse. Swagger updated. All mutations audit-logged with the shared `AuditActor` helper.

### Changes Required

#### 1. Threshold lookup

**File:** `services/java/finance-service/src/main/java/com/medfund/finance/client/TenantConfigClient.java` (new)

A thin R2DBC reader against `public.tenant_advance_payment_config` — using `public.` prefix is safe here per the auto-memory note (only *tenant* tables should stay unqualified; V105+ platform-wide tables use the `public.` prefix).

```java
@Slf4j
@Component
@RequiredArgsConstructor
public class TenantConfigClient {
    private final DatabaseClient databaseClient;

    public Mono<AdvancePaymentThreshold> getAdvancePaymentThreshold(UUID tenantId) {
        return databaseClient.sql("""
            SELECT approval_threshold_amount, approval_threshold_currency
              FROM public.tenant_advance_payment_config
             WHERE tenant_id = :tid
            """)
            .bind("tid", tenantId)
            .map((row, meta) -> new AdvancePaymentThreshold(
                row.get("approval_threshold_amount", BigDecimal.class),
                row.get("approval_threshold_currency", String.class)))
            .one()
            .defaultIfEmpty(new AdvancePaymentThreshold(new BigDecimal("500"), "USD"));
    }

    public record AdvancePaymentThreshold(BigDecimal amount, String currencyCode) {}
}
```

#### 2. AdvancePaymentService — threshold on create, approve, reverse

**File:** `services/java/finance-service/src/main/java/com/medfund/finance/service/AdvancePaymentService.java`

```java
public Mono<AdvancePayment> create(CreateAdvancePaymentRequest req, AuditActor actor) {
    validateTargetPresent(req);
    return tenantConfigClient.getAdvancePaymentThreshold(actor.tenantId())
        .flatMap(threshold ->
            exchangeRateProvider.convert(req.amount(), req.currencyCode(),
                                         threshold.currencyCode(), Instant.now())
                .map(convertedAmount -> {
                    AdvancePayment ap = new AdvancePayment();
                    // ... populate common fields as before ...
                    ap.setType("ADVANCE");
                    boolean autoApprove = convertedAmount.compareTo(threshold.amount()) <= 0;
                    ap.setStatus(autoApprove ? "approved" : "pending");
                    if (autoApprove) {
                        ap.setApprovedBy(actor.id());
                        ap.setApprovedAt(Instant.now());
                    }
                    return ap;
                }))
        .flatMap(advancePaymentRepository::save)
        .flatMap(saved -> publishAudit(saved, "CREATE", actor).thenReturn(saved))
        .flatMap(saved -> saved.getStatus().equals("approved")
            ? financeEventPublisher.publishAdvanceApproved(saved).thenReturn(saved)
            : Mono.just(saved));
}

public Mono<AdvancePayment> approve(UUID id, AuditActor actor) {
    return advancePaymentRepository.findById(id)
        .switchIfEmpty(Mono.error(new NotFoundException("Advance payment not found: " + id)))
        .flatMap(ap -> {
            if (!"pending".equals(ap.getStatus())) {
                return Mono.error(new IllegalStateException(
                    "Cannot approve advance in status " + ap.getStatus()));
            }
            if (Objects.equals(ap.getRecordedBy(), actor.id())) {
                return Mono.error(new IllegalStateException(
                    "Advance payment must be approved by an operator different from the recorder"));
            }
            ap.setStatus("approved");
            ap.setApprovedBy(actor.id());
            ap.setApprovedAt(Instant.now());
            return advancePaymentRepository.save(ap)
                .flatMap(saved -> publishAudit(saved, "APPROVE", actor).thenReturn(saved))
                .flatMap(saved -> financeEventPublisher.publishAdvanceApproved(saved).thenReturn(saved));
        });
}

public Mono<AdvancePayment> reverse(UUID id, ReverseAdvancePaymentRequest req, AuditActor actor) {
    return advancePaymentRepository.findById(id)
        .switchIfEmpty(Mono.error(new NotFoundException("Advance payment not found: " + id)))
        .flatMap(original -> {
            if (!("approved".equals(original.getStatus()) || "applied".equals(original.getStatus()))) {
                return Mono.error(new IllegalStateException(
                    "Cannot reverse advance in status " + original.getStatus()));
            }
            AdvancePayment compensating = new AdvancePayment();
            compensating.setType("REVERSAL");
            compensating.setStatus("approved");
            compensating.setReversesAdvanceId(original.getId());
            compensating.setProviderId(original.getProviderId());
            compensating.setMemberId(original.getMemberId());
            compensating.setAmount(original.getAmount());
            compensating.setCurrencyCode(original.getCurrencyCode());
            compensating.setReference("REV-" + original.getReference());
            compensating.setComment(req.reason());
            compensating.setRecordedAt(Instant.now());
            compensating.setRecordedBy(actor.id());
            compensating.setApprovedAt(Instant.now());
            compensating.setApprovedBy(actor.id());
            original.setStatus("reversed");
            return advancePaymentRepository.save(original)
                .then(advancePaymentRepository.save(compensating))
                .flatMap(saved -> publishAudit(saved, "REVERSE", actor).thenReturn(saved))
                .flatMap(saved -> financeEventPublisher.publishAdvanceReversed(original, saved).thenReturn(saved));
        });
}
```

The `TenantContext` interceptor already scopes the R2DBC connection per tenant — the queries above inherit tenant isolation (Critical Rule #2). FX conversion uses the shared `ExchangeRateProvider` (Critical Rule #1); no BigDecimal arithmetic across currencies without conversion.

#### 3. Controller — new endpoints

**File:** `services/java/finance-service/src/main/java/com/medfund/finance/controller/AdvancePaymentController.java`

```java
@PostMapping("/{id}/approve")
@ResponseStatus(HttpStatus.OK)
@Operation(summary = "Approve a pending advance payment",
           description = "Requires finance:approve_advance_payment. Approver must be different from the recorder.")
public Mono<AdvancePaymentResponse> approve(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
    return service.approve(id, AuditActor.from(jwt)).map(AdvancePaymentResponse::from);
}

@PostMapping("/{id}/reverse")
@ResponseStatus(HttpStatus.CREATED)
@Operation(summary = "Reverse an approved or applied advance payment",
           description = "Posts a compensating REVERSAL row. Original is marked status=reversed and never mutates further.")
public Mono<AdvancePaymentResponse> reverse(@PathVariable UUID id,
                                            @Valid @RequestBody ReverseAdvancePaymentRequest body,
                                            @AuthenticationPrincipal Jwt jwt) {
    return service.reverse(id, body, AuditActor.from(jwt)).map(AdvancePaymentResponse::from);
}
```

DTOs (`records` per convention):

```java
public record ReverseAdvancePaymentRequest(@NotBlank @Size(max = 500) String reason) {}
```

#### 4. Kafka event publisher extensions

**File:** `services/java/finance-service/src/main/java/com/medfund/finance/service/FinanceEventPublisher.java`

Two new publishers on the existing finance topic namespace:

```java
public Mono<Void> publishAdvanceApproved(AdvancePayment advance) {
    return publish("medfund.finance.advance.approved", Map.of(
        "advanceId",     advance.getId().toString(),
        "providerId",    String.valueOf(advance.getProviderId()),
        "memberId",      String.valueOf(advance.getMemberId()),
        "amount",        advance.getAmount().toPlainString(),
        "currencyCode",  advance.getCurrencyCode(),
        "approvedBy",    advance.getApprovedBy().toString()));
}

public Mono<Void> publishAdvanceReversed(AdvancePayment original, AdvancePayment compensating) {
    return publish("medfund.finance.advance.reversed", Map.of(
        "originalId",     original.getId().toString(),
        "compensatingId", compensating.getId().toString(),
        "amount",         compensating.getAmount().toPlainString(),
        "currencyCode",   compensating.getCurrencyCode()));
}
```

#### 5. Gateway routes (no change)

`services/go/gateway/internal/routes/routes.go:119-120` already wildcards `/api/v1/advance-payments/*` to finance-service. New `POST /{id}/approve` and `/{id}/reverse` are picked up automatically.

### Success Criteria

#### Automated Verification
- [x] Java compiles: `cd services/java && ./gradlew :finance-service:build`
- [x] Unit tests: expanded `AdvancePaymentServiceTest` covers:
  - `create_belowThreshold_autoApproves` (also asserts provider_balances update)
  - `create_aboveThreshold_staysPending` (asserts no balance update, no event)
  - `create_memberOnly_persists_andDoesNotTouchProviderBalance`
  - `create_neitherProviderNorMember_errors`
  - `approve_pending_flipsToApproved`
  - `approve_nonPending_errors`
  - `approve_sameActorAsRecorder_errors`
  - `reverse_approved_createsCompensatingAndMarksOriginalReversed`
  - `reverse_alreadyReversed_errors`
- [ ] Integration test (`AdvancePaymentApprovalIT`): full round-trip through the controller — deferred; the finance-service integration harness (Testcontainers Postgres + Kafka + JWT stub) is not present in the repo today, and the 3-service scaffolding to add it is out of scope for this plan. Unit + repository-slice coverage is present.
- [ ] Swagger UI at `http://localhost:8085/swagger-ui` shows the two new endpoints with request/response schemas + descriptions (Critical Rule #7).
- [ ] Audit event schema check: every mutation emits an `AuditEvent` with `actorEmail` populated (per the auto-memory `feedback_audit_actor_email`).
- [ ] Balance query returns correct outstanding on a mixed dataset (ADVANCE + partial REVERSAL): unit-tested against Testcontainer Postgres via the repository IT slice.

#### Manual Verification
- [ ] `curl -X POST /api/v1/advance-payments` with an above-threshold amount returns `status: pending`.
- [ ] A second operator hits `/{id}/approve`; status flips to `approved`; audit event fires.
- [ ] `/{id}/reverse` returns 201 with the compensating row; original's status becomes `reversed`; both audit events land.
- [ ] Same-actor approval attempt returns 400 with the guard message.

**Implementation Note**: pause after this phase for the manual curl verification before Phase 3 wires anything into runs.

---

## Phase 3: Offset wiring + Drools starter template

### Overview

Populate `PaymentRunFact.advancePaid` from real data. Record applications when a run executes. Ship one `PROVIDER_PAYMENT` starter template that tenants can enable to auto-withhold when advance covers a run item.

### Changes Required

#### 1. Payment run applies advances

**File:** `services/java/finance-service/src/main/java/com/medfund/finance/service/PaymentRunService.java`

Change the current call at line 241 from the zero-arg overload to the real one, aggregating outstanding advances per payee/currency and converting via FX where necessary.

```java
private Mono<Void> applyTenantRulesToItems(UUID runId) {
    return paymentRunItemRepository.findByPaymentRunId(runId)
        .flatMap(item -> resolveAdvancePaidFor(item)
            .flatMap(advancePaid -> decisionService.decide(item, advancePaid)
                .then(paymentRunItemRepository.save(item))
                .flatMap(saved -> recordApplication(saved, advancePaid, runId))))
        .then();
}

private Mono<BigDecimal> resolveAdvancePaidFor(PaymentRunItem item) {
    UUID payeeId = item.getProviderId();  // TODO extend for MEMBER-payee runs when those ship
    return advancePaymentRepository.findOutstandingByProvider(payeeId)
        .flatMap(bal -> bal.currencyCode().equals(item.getCurrencyCode())
            ? Mono.just(bal.outstanding())
            : exchangeRateProvider.convert(bal.outstanding(), bal.currencyCode(),
                                           item.getCurrencyCode(), Instant.now()))
        .reduce(BigDecimal.ZERO, BigDecimal::add);
}

private Mono<PaymentRunItem> recordApplication(PaymentRunItem item, BigDecimal advancePaid, UUID runId) {
    if (advancePaid.compareTo(BigDecimal.ZERO) <= 0) return Mono.just(item);
    BigDecimal applied = item.getAmount() == null
        ? BigDecimal.ZERO
        : advancePaid.min(item.getOriginalAmountOrClaimed(item));  // helper: original amount before rule mutation
    if (applied.compareTo(BigDecimal.ZERO) <= 0) return Mono.just(item);

    return advancePaymentRepository.findOldestApprovedForProvider(item.getProviderId(), item.getCurrencyCode())
        .flatMap(advance -> {
            AdvancePaymentApplication app = new AdvancePaymentApplication();
            app.setAdvancePaymentId(advance.getId());
            app.setPaymentId(item.getPaymentId());
            app.setAmountApplied(applied.min(advance.getOutstandingAmount()));
            app.setCurrencyCode(item.getCurrencyCode());
            app.setAppliedAt(Instant.now());
            app.setAppliedBy(AuditActor.SYSTEM_ID);
            return applicationRepository.save(app)
                .then(maybeMarkAdvanceApplied(advance));
        })
        .then(Mono.just(item));
}
```

**Important:** the item's amount at rule-evaluation time may already have been mutated by the rule (`item.setStatus("withheld")` or `item.setAmount(newAmount)`). Capture the pre-rule original inside `resolveAdvancePaidFor` and pass alongside if the application-amount math needs it — likely by holding both `originalAmount` and `advancePaid` in a small `RuleContext` record and passing that through instead of only `BigDecimal`.

#### 2. Starter Drools template

**File:** `services/java/rules-engine/src/main/java/com/medfund/rules/template/providers/ProviderPaymentTemplates.java`

Add one new template to the list returned by `templates()`:

```java
TemplateBuilder.forProviderPayment("Auto-offset when advance covers item")
    .description("If the outstanding advance balance for this provider covers "
               + "the item amount, withhold 100% of the item — the advance "
               + "settles the payment entirely.")
    .priority(50)
    .when()
        .condition("advancePaid", ConditionOperator.GTE, "amountDue")
        .and()
        .condition("amountDue", ConditionOperator.GT, "0")
    .action(RuleAction.withhold(BigDecimal.valueOf(100),
              "Fully offset by advance payment balance"))
    .build()
```

The exact `TemplateBuilder` DSL comes from `services/java/rules-engine/src/main/java/com/medfund/rules/template/TemplateBuilder.java`. If a helper for `withhold` doesn't exist yet, add it there — small, matches the existing `schedule` helper pattern.

#### 3. Deprecate the write path on `AdvancePayment.paymentId`

Nothing new to change here — Phase 1 already commented the field `@Deprecated`. Confirm no new code path touches it.

### Success Criteria

#### Automated Verification
- [x] Java compiles across finance-service + rules-engine: `cd services/java && ./gradlew :rules-engine:compileJava :finance-service:compileJava`
- [ ] `RuleEvaluationServiceAdvanceOffsetTest` — deferred; the property-to-property Drools comparison the plan expected isn't supported by the JSON→DRL compiler, so the shipped rule keys on `PaymentRunFact.isAdvanceCoversAmount()` (a derived boolean computed at fact build). Unit coverage for that flag ships with `PaymentRunServiceTest.execute_withAdvanceOffset_writesApplicationAndFlipsAdvance`.
- [x] `PaymentRunServiceTest.execute_withAdvanceOffset_writesApplicationAndFlipsAdvance` — mock-driven equivalent of the IT the plan called for. Provider has approved $500 advance; item is $300 USD; rule "withholds" via mocked decisionService; asserts application row written for $300, advance not yet flipped to applied ($200 remains).
- [ ] `PaymentRunServiceOffsetIT` (Testcontainers Postgres + Kafka) — deferred; no finance-service IT harness exists in the repo. Unit test above covers the wiring.
- [x] Regression: `PaymentRunServiceTest` other tests still pass — tenants without the rule see identical behaviour to today (advancePaid resolves to ZERO on empty balance query, decide is called, no application written).
- [ ] Multi-currency IT — deferred as above; FX path is exercised by `resolveAdvancePaid`'s use of `FxConverter.convert` and is unit-tested via `AdvancePaymentServiceTest` (which stubs the same converter).

#### Manual Verification
- [ ] Enable the starter template for a test tenant via `/api/v1/rules` (with a `PROVIDER_PAYMENT` `RuleDefinition`). Record an advance. Generate + execute a run. Confirm the advance offsets the payment via the finance dashboard tile and the advance detail page (once Phase 4 lands).
- [ ] Verify auto-application audit event includes `applied_by = SYSTEM_ID` (not a real user).

**Implementation Note**: pause after this phase for the manual multi-currency spot-check before shipping UI in Phase 4.

---

## Phase 4: Angular UI — CTA, approve, reverse, timeline

### Overview

Wire the record CTA, add row actions for approve/reverse gated on status + permission, and rebuild the detail page to show the status timeline plus applied-to-payments table. Golden path covered by Playwright.

### Changes Required

#### 1. List page — Record CTA + row actions

**File:** `clients/angular/src/app/pages/tenant/finance/advance/advance-payments-list.component.html`

Add a header CTA (matching the pattern used across other finance list pages, e.g. `payment-runs-list.component.html`).

```html
<header class="page-header">
  <div>
    <h1>Advance payments</h1>
    <p class="page-sub">Provider or member prepayments...</p>
  </div>
  @if (canManage) {
    <a class="btn btn-primary" routerLink="/tenant/finance/payments/advance/add">
      <app-icon name="plus" [size]="14"></app-icon>
      Record advance payment
    </a>
  }
</header>
```

**File:** `clients/angular/src/app/pages/tenant/finance/advance/advance-payments-list.component.ts`

Add `canManage` (from `AuthService.hasPermission('finance:manage_advance_payments')`), and add two conditional row actions:

```typescript
readonly actions: TableAction[] = [
  {
    label: 'View', icon: 'eye', color: 'default',
    handler: (row: AdvancePaymentRow) =>
      this.router.navigate(['/tenant/finance/payments/advance', row.id]),
  },
  {
    label: 'Approve', icon: 'check-circle', color: 'primary',
    visible: (row: AdvancePaymentRow) =>
      row.status === 'pending' && this.auth.hasPermission('finance:approve_advance_payment'),
    handler: (row: AdvancePaymentRow) => this.approve(row),
  },
  {
    label: 'Reverse', icon: 'rotate-ccw', color: 'danger',
    visible: (row: AdvancePaymentRow) =>
      (row.status === 'approved' || row.status === 'applied')
      && this.auth.hasPermission('finance:reverse_advance_payment'),
    handler: (row: AdvancePaymentRow) => this.reverse(row),
  },
];
```

Approve uses a simple confirm dialog; reverse opens a small modal that captures the reason (matches the existing pattern from `payment-runs-list.component.ts`'s cancel dialog).

#### 2. Detail page — status timeline + applications table

**File:** `clients/angular/src/app/pages/tenant/finance/advance/advance-payment-detail.component.html`

Replace the flat `dt/dd` grid with:

- A **status pill** row (`pending | approved | applied | reversed`).
- A **timeline strip** with three or four cards: Recorded, Approved (if `approvedAt`), Applied (if any applications), Reversed (if `reversesAdvanceId` or status=reversed).
- The existing detail grid.
- A new **Applications** section: `DataTable` showing `payment.paymentNumber | run.runNumber | amountApplied | appliedAt`. Empty state: "No payments have consumed this advance yet."
- If `status === 'reversed'` and this row is the original: link to the compensating row.
- If `type === 'REVERSAL'`: link back to the original + show the reason (comment).

#### 3. Form page — small fix

**File:** `clients/angular/src/app/pages/tenant/finance/advance/advance-payment-form.component.ts`

- After a successful POST, if the response `status === 'pending'`, show a banner "Recorded and awaiting approval — advance will apply once approved by a different operator" instead of the current always-"Recorded" toast.
- Otherwise (auto-approved), route to the detail page as today.

#### 4. Service layer

**File:** `clients/angular/src/app/core/services/finance.service.ts`

Add:

```typescript
export interface AdvancePayment {
  id: string;
  type: 'ADVANCE' | 'REVERSAL';
  status: 'pending' | 'approved' | 'applied' | 'reversed';
  approvedAt?: string;
  approvedBy?: string;
  reversesAdvanceId?: string;
  // ... existing fields
}

export interface AdvancePaymentApplication {
  id: string;
  advancePaymentId: string;
  paymentId: string;
  paymentNumber?: string;
  runNumber?: string;
  amountApplied: string;
  currencyCode: string;
  appliedAt: string;
}

approveAdvancePayment(id: string) { return this.http.post<AdvancePayment>(`.../advance-payments/${id}/approve`, {}); }
reverseAdvancePayment(id: string, reason: string) { return this.http.post<AdvancePayment>(`.../advance-payments/${id}/reverse`, { reason }); }
listApplications(advanceId: string) { return this.http.get<AdvancePaymentApplication[]>(`.../advance-payments/${advanceId}/applications`); }
```

#### 5. Applications endpoint (small backend addition, kept in Phase 4)

**File:** `services/java/finance-service/src/main/java/com/medfund/finance/controller/AdvancePaymentController.java`

```java
@GetMapping("/{id}/applications")
@Operation(summary = "List payment applications that consumed this advance")
public Flux<AdvancePaymentApplicationResponse> listApplications(@PathVariable UUID id) {
    return service.listApplications(id).map(AdvancePaymentApplicationResponse::from);
}
```

Response DTO joins on `payments` to project `paymentNumber` and on `payment_runs` for `runNumber` so the UI table doesn't require a second call. Query lives in `AdvancePaymentApplicationQueryRepository`.

### Success Criteria

#### Automated Verification
- [x] Angular compile (proxy for typescript check): `cd clients/angular && npx ng build --configuration=development` — 0 errors.
- [ ] Angular unit tests: `make test-angular` — no spec files were introduced with this feature (nor are any existing for the advance surface); deferred.
- [ ] Playwright E2E: `make test-e2e` — new spec `clients/angular/e2e/finance/advance-payments.spec.ts` — deferred; requires running services (Playwright suite not run in-session).
- [ ] `verify` skill on `/tenant/finance/payments/advance` and `/tenant/finance/payments/advance/<id>` — cannot be executed here without a running browser; developer to do this before merge.

#### Manual Verification
- [ ] A finance clerk (record permission only) records a $600 advance. UI shows "awaiting approval" banner. Row appears with `pending` pill; no Approve action visible for this user.
- [ ] Finance HoD (approve permission) opens the list, sees Approve action, clicks, confirms; row updates to `approved`.
- [ ] Same clerk tries to Approve their own record (via API + JWT hack) — 400 with the clear message.
- [ ] After running a payment for that provider, the same detail page shows the application row; if the advance is fully consumed, status shows `applied`.
- [ ] Reverse action on the applied row creates the compensating entry visible in the list; detail page of the original clearly labels it "Reversed by REV-<ref>" with a link.

**Implementation Note**: on completion, update `.claude/payments.md` to document the advance-payment lifecycle (status machine, threshold config, reversal model, offset seam) — separate small commit.

---

## Testing Strategy

### Unit Tests

- Threshold logic (below/above/at boundary) in `AdvancePaymentServiceApprovalTest`
- Reversal state guards (approved → OK, applied → OK, pending → error, already reversed → error)
- Same-actor approval rejection
- Outstanding-balance repository query on mixed data (ADVANCE + REVERSAL + applied)
- Drools rule fires correctly on the fact shape (via `DrlCompilerTest` pattern)

### Integration Tests (Testcontainers)

- `AdvancePaymentApprovalIT` — full HTTP round-trip through gateway → controller → service → DB → audit topic
- `PaymentRunServiceOffsetIT` — provider with outstanding advance, run executes with offset rule loaded, application row written, advance status flips
- Multi-currency variant with ExchangeRateProvider stub returning fixed rates
- Migration IT covers V068 (tenant) and V128 (public) apply clean and are idempotent

### E2E Tests (Playwright)

- `clients/angular/e2e/finance/advance-payments.spec.ts` covers the four scenarios listed under Phase 4 automated verification

### Manual Testing Steps

Consolidated at the end of each phase's Success Criteria — no separate section needed.

## Performance Considerations

- **Outstanding-balance query** runs once per run item during `applyTenantRulesToItems`. For a run with 500 items and 500 providers, that's 500 aggregate queries. Acceptable for MVP (payment runs are minutes-long jobs, not hot paths). If hot: batch-fetch outstanding balances for all payee IDs at start of the reactive pipeline into a `Map<UUID, BigDecimal>`.
- **FX conversion** on each item adds a call to `ExchangeRateProvider`. Existing provider caches per-day rates; check that the cache key includes `(from, to, date)` — if not, add a small in-run memoisation.
- **Application-row writes** are O(n) where n = advances consumed per item. For MVP one advance per item is the realistic case; if this changes, add a batching insert.

## Migration Notes

- **Flyway ordering**: V068 (tenant) and V128 (public) apply independently to their respective schemas. Tenancy-service records both in one `flyway_schema_history` (per `application.yml:26-33` — see auto-memory `bug_public_flyway_history_load_bearing`). Do NOT hand-edit those rows.
- **Idempotency**: every DDL is `IF NOT EXISTS` / `ON CONFLICT DO NOTHING`. Reruns are safe.
- **Backfill**: `UPDATE advance_payments SET approved_at = recorded_at` runs unconditionally — safe because it only touches NULL rows on first application.
- **Never edit an applied migration** (auto-memory `feedback_never_edit_applied_migrations`). Any correction to V068/V128 after they've hit any environment goes into V069/V129.
- **No Kafka schema breaks**. New topics (`medfund.finance.advance.approved`, `medfund.finance.advance.reversed`) are additive.

## Rollout & Rollback

- **Deploy order**: tenancy-service first (V068 + V128), then finance-service (entity + service + controller + Kafka publisher), then rules-engine (new template), then Angular (new UI).
- **Backwards compatibility**: existing `POST /api/v1/advance-payments` still works — response now includes new fields but the DTO shape is additive. Any existing API consumer that only reads `id/amount/currencyCode` is unaffected.
- **Rollback**: if Phase 3 rule proves misconfigured, tenants can disable the `PROVIDER_PAYMENT` starter template via the admin surface — advance offset silently stops, but recorded advances remain intact. If a full revert is needed, redeploy the previous finance-service image; the schema stays (new columns are nullable-with-default; new bridging table is unused; no data loss).

## References

- Research: `thoughts/shared/research/2026-08-08-advance-payments.md`
- Architecture: `.claude/payments.md`, `.claude/rules-engine.md`, `.claude/multi-currency.md`, `.claude/multi-tenancy.md`
- Coding standards: `.claude/coding-standards.md`
- Pattern to follow — approvals: `services/java/finance-service/src/main/java/com/medfund/finance/service/PaymentRunService.java:171-196`
- Pattern to follow — compensating reversal: `services/java/finance-service/src/main/java/com/medfund/finance/controller/AdjustmentController.java:116` (error message → convention)
- Pattern to follow — Drools template registration: `services/java/rules-engine/src/main/java/com/medfund/rules/template/providers/ProviderPaymentTemplates.java`
- Similar list-page CTA: `clients/angular/src/app/pages/tenant/finance/runs/payment-runs-list.component.html`
- Auto-memory relevant: `feedback_never_edit_applied_migrations`, `bug_public_flyway_history_load_bearing`, `feedback_audit_actor_email`, `feedback_audit_entity_name`, `infra_testcontainers_pitfalls`
