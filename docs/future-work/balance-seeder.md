# Balance seeder — migrating opening balances from a legacy system

## Context

Today the ledger derives 100% of state from events posted in our tables: contributions, transactions, and the invoice-snapshot chain. A brand-new group or member always starts at 0 — enforced by `Prior.EMPTY` returning `closingBalance = 0` when `InvoiceSnapshotService.findPrior` sees no prior invoice.

When migrating tenants from an external billing system, some subjects arrive with a non-zero outstanding balance (e.g. "Acme Corp owes $2,000 to us on day 1"). Without a seeding path:

- `group_running_balance` / `member_running_balance` stays at 0 until the first contribution mutation, so aged-debt and creditor reports miss the historical debt.
- The first invoice's `opening_balance = 0` even though the customer actually owes $2,000, so the statement misrepresents the amount owed.
- Every downstream number (arrears escalation thresholds, statements, receipts) is wrong until the operator manually inserts a compensating adjustment — error-prone and undocumented.

Requirements from user (2026-07-04):
- Seed **both groups and individual members**.
- Support all three tenant billing modes: `GROUP_ONLY`, `INDIVIDUAL_ONLY`, `BOTH`.
- Provide provenance — future risk-scoring / audit must be able to answer "where did this opening balance come from and when was it seeded?"

## Approach — Option B from the design discussion (dedicated seed table)

Chosen over the alternatives because:
- Migration is semantically distinct from billing (Option C: genesis-invoice — pollutes the invoice list) and from routine ledger operations (Option A: opening-balance transaction — first statement's opening column reads 0 even when 2000 was seeded, which contradicts accounting intuition).
- A dedicated table gives us provenance metadata (source system, external reference, seeded_by, seeded_at) as first-class columns.
- Corrections have their own explicit path (supersede the seed row) that doesn't tangle with transaction revoke logic.

### 1. Schema — new `balance_seed` table (tenant schema, one Flyway migration)

```sql
CREATE TABLE IF NOT EXISTS balance_seed (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    subject_type    VARCHAR(10) NOT NULL CHECK (subject_type IN ('GROUP','MEMBER')),
    subject_id      UUID        NOT NULL,
    currency_code   CHAR(3)     NOT NULL,
    opening_balance DECIMAL(19,4) NOT NULL,
    seeded_at       TIMESTAMPTZ NOT NULL,
    source_system   VARCHAR(64),                 -- e.g. "OldMemberSuite v3"
    source_reference VARCHAR(128),               -- opaque id from legacy system
    superseded_by   UUID REFERENCES balance_seed(id),
    seeded_by       UUID NOT NULL,               -- operator or SYSTEM
    reason          TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- One active seed per (subject, currency). Superseded rows are kept for audit
-- but excluded from lookups via the superseded_by IS NULL filter below.
CREATE UNIQUE INDEX ux_balance_seed_active_per_subject
    ON balance_seed (subject_type, subject_id, currency_code)
    WHERE superseded_by IS NULL;

CREATE INDEX ix_balance_seed_subject
    ON balance_seed (subject_type, subject_id, currency_code);
```

Reserve Flyway version once seeder work starts — current tenant chain is at V044.

### 2. Seeder service (`BalanceSeederService`, contributions-service)

**Public API — one method:**

```java
public Mono<BalanceSeed> seedOpeningBalance(
        String subjectType, UUID subjectId,
        String currencyCode, BigDecimal openingBalance,
        LocalDate seededAt,
        String sourceSystem, String sourceReference,
        String reason,
        String actorId, String actorEmail);
```

**Steps inside the same `@Transactional`:**

1. Validate `subjectType` in (`GROUP`, `MEMBER`); reject otherwise.
2. Validate currency exists on the tenant's `tenant_currency_config`.
3. Cross-service call to user-service (via existing `UserServiceClient` pattern) to confirm the subject exists and is enrolled/active. Reject with 404 if missing.
4. Optional cross-check against the tenant's `pricingModel` / `membershipModel`:
   - `GROUP_ONLY` tenant → subjectType must be `GROUP` (or, for individual members that are ungrouped in a group-only tenant, apply the same rule as transactions: reject with 422 pointing at the group).
   - `INDIVIDUAL_ONLY` tenant → subjectType `GROUP` rejected.
   - `BOTH` → either allowed, but `MEMBER` subjectType only if `member.group_id IS NULL` (mirrors the payment rule in `feedback_grouped_members_cannot_pay`).
5. Idempotency check: query `balance_seed` for an active row (`superseded_by IS NULL`) matching `(subjectType, subjectId, currencyCode)`.
   - If found and openingBalance matches → return existing (no-op, safe replay).
   - If found and openingBalance differs → require an explicit `--supersede` flag on the request; otherwise 409.
6. Insert the new `balance_seed` row.
7. Call `BalanceService.applyOpeningBalance(subjectType, subjectId, currency, openingBalance)` — new method (Option B extension) that upserts into `member_running_balance` / `group_running_balance` with a `SEED_OPENING_BALANCE` reason on the audit line. Idempotent by design (delta = target − current) or, simpler and cleaner, only fire on the first insert / on a supersede when we can compute the delta reliably.
8. Publish an audit event: `entityType=BalanceSeed`, `action=CREATE`, actor threaded through per `feedback_audit_actor_email`.
9. Publish a Kafka event on a new topic `medfund.contributions.balance-seeded` so downstream services (notification, live-dashboard) can react.

**Supersede flow (correction of a wrong seed):**

- Insert a new `balance_seed` row with the corrected value.
- Set the old row's `superseded_by` to the new row's id.
- Apply the delta (`new.opening_balance − old.opening_balance`) to `group_running_balance` / `member_running_balance` with a `SEED_OPENING_BALANCE_CORRECTION` reason.

Never mutate an existing seed row's `opening_balance` column — the audit trail depends on immutability.

### 3. `InvoiceSnapshotService.findPrior` extension

Today (`InvoiceSnapshotService.java:106-127`) `findPrior` looks up the most-recent prior invoice. Extend it so that when no prior invoice exists, it falls back to the active `balance_seed` row:

```java
private Mono<Prior> findPrior(Invoice invoice, Instant committedAt) {
    return findPriorInvoice(invoice, committedAt)                    // existing query
            .switchIfEmpty(Mono.defer(() -> findSeedAsPrior(invoice))) // new
            .defaultIfEmpty(Prior.EMPTY);
}

private Mono<Prior> findSeedAsPrior(Invoice invoice) {
    String subjectType = invoice.getGroupId() != null ? "GROUP" : "MEMBER";
    UUID subjectId     = invoice.getGroupId() != null ? invoice.getGroupId() : invoice.getMemberId();
    return db.sql("""
            SELECT id, opening_balance, seeded_at
              FROM balance_seed
             WHERE subject_type = :subjectType
               AND subject_id   = :subjectId
               AND currency_code = :currency
               AND superseded_by IS NULL
             LIMIT 1
            """)
            .bind("subjectType", subjectType)
            .bind("subjectId",   subjectId)
            .bind("currency",    invoice.getCurrencyCode())
            .map(row -> new Prior(
                    row.get("id", UUID.class),
                    row.get("opening_balance", BigDecimal.class),
                    row.get("seeded_at", Instant.class)))
            .one();
}
```

**Effect:** the first real invoice for a seeded subject reads `opening = seed.opening_balance`, and `sumWindowedTransactions` uses `seeded_at` as its lower bound (not `null` / unbounded). Only events posted AFTER the seed hit the first invoice's window — the seed itself is the anchor.

### 4. `StatementService.generate` — period statement handling

The period statement's `opening` accumulator (`StatementService.java:170-179`) currently starts at `BigDecimal.ZERO`. Extend it to seed from `balance_seed` when the statement's `periodStart` is on/after the seed date:

- Load the seed at the start of `generate` (one extra query, cached in the tuple).
- If a seed exists and `seed.seeded_at.isBefore(periodStartInstant)` → initialise `opening = seed.opening_balance`.
- If the seed's `seeded_at` falls INSIDE the period, render it as an in-period line labelled "Opening balance (migrated from {source_system})" so the statement still reconciles.

### 5. HTTP API

New controller `BalanceSeederController` in contributions-service:

```
POST /api/v1/balance-seed
  Body: {
    subjectType: "GROUP" | "MEMBER",
    subjectId: uuid,
    currencyCode: "USD",
    openingBalance: "2000.00",
    seededAt: "2026-07-01",   -- optional, defaults to today
    sourceSystem: "OldMemberSuite v3",
    sourceReference: "acct-12345",
    reason: "Data migration from legacy platform (RFC-42)",
    supersede: false            -- required=true when overwriting an existing seed
  }

GET /api/v1/balance-seed?subjectType=GROUP&subjectId={id}&currencyCode=USD
  Returns the active seed row (404 if none).

GET /api/v1/balance-seed/history?subjectType=GROUP&subjectId={id}&currencyCode=USD
  Returns the full lineage (including superseded rows), sorted newest first.
```

Gated by a new permission `billing:seed_balance`. Only tenant-admin + platform-admin should carry it — not day-to-day operators.

Batch import: leave for follow-up. First cut is one seed at a time via the HTTP endpoint. A CSV upload flow is a natural extension that just loops the endpoint.

### 6. Angular (deferred but noted)

Tenant-admin page under `settings/billing/opening-balances` — a table of active seeds with a "Seed opening balance" button that opens a dialog. The dialog uses the same search-select pattern as the transaction form (grouped-member picker filter from `feedback_grouped_members_cannot_pay` applies here too — a grouped member can't have their own seed; seed the group instead).

### 7. Files to add / modify (when the work starts)

**New**
- `services/java/tenancy-service/src/main/resources/db/migration/tenant/V0XX__balance_seed.sql` (reserve next-available V0XX).
- `services/java/contributions-service/src/main/java/com/medfund/contributions/entity/BalanceSeed.java`
- `services/java/contributions-service/src/main/java/com/medfund/contributions/repository/BalanceSeedRepository.java`
- `services/java/contributions-service/src/main/java/com/medfund/contributions/service/BalanceSeederService.java`
- `services/java/contributions-service/src/main/java/com/medfund/contributions/controller/BalanceSeederController.java`
- `services/java/contributions-service/src/main/java/com/medfund/contributions/dto/SeedOpeningBalanceRequest.java`
- Test files matching each of the above.

**Modify**
- `services/java/contributions-service/src/main/java/com/medfund/contributions/service/InvoiceSnapshotService.java` — extend `findPrior` per §3. Small edit; the tests in `InvoiceSnapshotServiceTest` already cover the surrounding SQL and would extend cleanly.
- `services/java/contributions-service/src/main/java/com/medfund/contributions/service/StatementService.java` — extend `generate` opening-balance loop per §4. Tests in `StatementServiceTest` extend cleanly.
- `services/java/contributions-service/src/main/java/com/medfund/contributions/service/BalanceService.java` — add `applyOpeningBalance(subjectType, subjectId, currency, amount)` reusing existing `upsertMember` / `upsertGroup` with reason `SEED_OPENING_BALANCE`.
- `services/java/contributions-service/src/main/java/com/medfund/contributions/client/UserServiceClient.java` — add `getMember(memberId)` / `getGroup(groupId)` if not already present; used for the enrollment / mode-check validation.
- New permission `billing:seed_balance` — Keycloak realm + tenant admin role setup follow existing pattern (see V009 for `billing:revoke_billing`).

### 8. Verification

**Unit tests:**
- `BalanceSeederServiceTest`
  - Seeds a GROUP subject → row inserted, running balance stamped, audit fired.
  - Seeds a MEMBER subject with `group_id IS NULL` → success.
  - Seeds a grouped MEMBER → 422 pointing at the group.
  - Seeds a duplicate (same value) → idempotent no-op.
  - Seeds a duplicate (different value) without `supersede=true` → 409.
  - Seeds a duplicate with `supersede=true` → old row marked `superseded_by`, new row active, running balance delta applied.
  - Cross-service call to user-service returns 404 → seed rejected with 404.
  - Tenant is `INDIVIDUAL_ONLY`, seeding a GROUP → 422.

- `InvoiceSnapshotServiceTest` extension
  - Given a seed row of 2000 and no prior invoices, `stampSnapshot` produces `opening = 2000`, `closing = 2000 + total − payments + adjustments`.
  - Second invoice reads `opening = firstInvoice.closingBalance`; seed is no longer consulted.
  - `sumWindowedTransactions` on the first invoice uses `seeded_at` as the lower bound (not null / unbounded).

- `StatementServiceTest` extension
  - Period statement whose `periodStart > seededAt` initialises `opening = seed.opening_balance`.
  - Period statement whose `periodStart == seededAt` renders the seed as the first in-period line and opening = 0.

**Integration:**
- Flyway IT (in the same pattern as `TenantMigrationFlywayIT`) asserts the new columns land on a fresh tenant schema.
- End-to-end: seed a group with $2000, run `POST /billing/preview` for the current month, verify the preview response reflects the seed as a $2000 pre-existing balance; run `POST /billing/commit`, verify the produced invoice's `opening_balance = 2000` and `closing_balance = 2000 + charges`.

**Manual:**
- Migrate a small batch of legacy groups + individual members. Compare their first statement against the legacy system's closing balance report row-by-row.

## Non-goals

- Batch CSV upload — separate follow-up once the single-seed flow is stable.
- Retroactive backdating past an existing invoice — the plan assumes seeds happen BEFORE any real invoice for that subject. A seed after an invoice needs a different flow (restatement, not seeding); intentionally out of scope.
- Multi-currency subject seeded on multiple currencies at once — supported by the schema (one seed per (subject, currency)) but the HTTP endpoint takes one currency per call; batch multi-currency is a UI-only extension.
