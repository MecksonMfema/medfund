---
date: 2026-08-10
git_commit: 0a1609d72451938c1e12346b63f7f6595122b8e5
branch: rename-adjustments-to-notes
research:
  - thoughts/shared/research/2026-08-10-tenant-bank-accounts-and-stubbed-gateway.md
services_touched: [tenancy-service, finance-service, payment-gateway, gateway, angular]
status: draft
---

# Tenant bank accounts + stubbed payment-gateway settlement — Implementation Plan

## Overview

Retire the "MASCA / platform bank accounts" surface in finance-service and reshape it as a **tenant-admin** capability: each tenant configures their own bank accounts through a new tab in the tenant-admin settings shell, `payment_runs` carry a `source_bank_account_id`, and the previously-unused Go `payment-gateway` service becomes the async settlement seam via Kafka. `finance:manage_banks` is retired for the tenant-admin-namespaced `admin.bank_accounts:manage`, and server-side permission enforcement lands for the first time on this controller via the shared `@RequiresPermission` aspect. The data plane is already per-tenant (V016 schema-per-tenant), so the refactor is naming + surface + enforcement + settlement seam — not tenancy work.

## Current State Analysis

The full inventory is in the research doc; the load-bearing facts driving the plan:

- **Data plane already per-tenant.** `masca_bank_accounts` lives in the tenant Flyway stream (`services/java/tenancy-service/src/main/resources/db/migration/tenant/V016__finance_schema.sql:177-195`) with a partial unique index `WHERE is_nominated = TRUE` enforcing one nominated account per currency. V016's own comment says *"The tenant's own bank accounts used for outbound disbursements"* — the schema is correct; only the branding, class name, URL, and page location are wrong.
- **Zero downstream Java consumers of the entity.** `BankReconciliation`, `Payment`, `PaymentRun`, `PaymentAdvice`, `PaymentRunItem` all lack any FK to bank accounts. `MascaBankAccountRepository.findNominatedForCurrency` (`services/java/finance-service/src/main/java/com/medfund/finance/repository/MascaBankAccountRepository.java:20-21`) has no callers.
- **No server-side permission enforcement.** `services/java/finance-service/src/main/java/com/medfund/finance/config/SecurityConfig.java:14-26` asserts only `.anyExchange().authenticated()`. Any authenticated JWT hits every finance endpoint. The Angular route guard on `finance:manage_banks` (`finance.routes.ts:143`) is the only gate today, and Angular guards are trivially bypassable via curl.
- **Permission catalogue is code-only.** There is no `permissions_catalogue` DB table — the four coordinated files (`services/java/shared/src/main/resources/permissions.yaml`, `services/java/shared/src/main/java/com/medfund/shared/security/Permissions.java`, `services/java/shared/src/main/java/com/medfund/shared/security/PermissionCatalogue.java`, `clients/angular/src/app/core/security/permissions.ts`) are the source of truth. `RoleController` rejects unknown keys at grant time.
- **Permission enforcement uses `@RequiresPermission`, not Spring `@PreAuthorize`.** The `PermissionAspect` in `services/java/shared/src/main/java/com/medfund/shared/security/PermissionAspect.java` intercepts `@RequiresPermission(...)`-annotated methods, reads the caller's permission set from `PermissionContext` (populated per-request by `PermissionResolverFilter` which resolves via `DefaultPermissionResolver`), and returns 403 with a specific message. `@EnableReactiveMethodSecurity` is not enabled anywhere in the tree — `@PreAuthorize` would silently no-op. The research doc's mention of `@PreAuthorize` is corrected to `@RequiresPermission` throughout this plan.
- **PaymentRun.execute publishes to a topic no one consumes.** `FinanceEventPublisher.publishPaymentRunExecuted` (`services/java/finance-service/src/main/java/com/medfund/finance/service/FinanceEventPublisher.java:77-84`) emits `medfund.payments.run.executed` with `{event, runId, runNumber, paymentCount}` — no `tenantId`, no items, no consumer anywhere in Go/Java/Elixir. `notification-service` has a stub `PAYMENT_RUN_EXECUTED` case (`services/go/notification-service/internal/notification/service.go:71-72`) that logs "would notify providers" and returns.
- **Go payment-gateway is a scaffold with the right shape.** `services/go/payment-gateway/` has `Provider` interface + `MockProvider` (always-succeeds) + in-memory `Ledger` + 4-endpoint `Handler`. `handler.go:36` hard-codes `direction="inbound"`. `cmd/main.go` does not use `internal/config/config.go`. There is no Kafka consumer.
- **Go Kafka pattern.** `services/go/file-service/internal/events/consumer.go:17-66` uses `segmentio/kafka-go` with FirstOffset + manual `CommitMessages` after handler returns. Tenant flows via **payload field**, not Kafka header. Publisher pattern in `services/go/file-service/internal/events/publisher.go:69-128`.
- **Java Reactor-Kafka consumer pattern.** `services/java/finance-service/src/main/java/com/medfund/finance/consumer/PaymentAdviceStatusConsumer.java:49-120` — `@PostConstruct consume()`, `KafkaReceiver.create(options).receive().flatMap(record -> processEvent(...).doOnSuccess(v -> record.receiverOffset().acknowledge()))`. Tenant read from JSON payload, written to Reactor context via `contextWrite(Context.of(TenantContext.KEY, tenantId))`. This exactly matches [[bug_reactor_kafka_ack_swallow]] — `.doOnSuccess`, never `.doOnTerminate`.

## Desired End State

- The word "MASCA" is gone from every live Java / Go / Angular / YAML file in the tree (docs edits land in Phase 5). Only historical Flyway `V008/V010/V069` comments retain the term, per [[feedback_never_edit_applied_migrations]].
- `tenant_bank_accounts` is the canonical table name; `label` + `notes` columns are populated. `payment_runs.source_bank_account_id UUID NOT NULL REFERENCES tenant_bank_accounts(id)`.
- `/api/v1/tenant-bank-accounts` responds to five CRUD verbs, every mutating verb gated by `@RequiresPermission(Permissions.ADMIN_BANK_ACCOUNTS_MANAGE)`. Any curl with a non-admin JWT gets 403 and a `PERMISSION_DENIED` security event is emitted.
- The tenant-admin **Settings → Bank Accounts** tab renders the CRUD surface at `/admin/settings` with the tab id `bank-accounts`. The old `/tenant/finance/banks/masca` URL and the operational-sidebar entry are deleted.
- Executing a payment run publishes a fat `medfund.payments.run.executed` event with items inline. The Go payment-gateway Kafka consumer processes each item, calls `MockProvider.Initiate(direction="outbound", bankAccountId=...)`, records ledger rows keyed by tenant, and publishes one `medfund.payments.gateway.settled` per item. Finance-service's new `PaymentGatewaySettledConsumer` flips the referenced `Payment.status` to `paid` and stamps `paid_at`.
- Playwright covers list/create/edit/nominate/delete of tenant bank accounts, plus one permission-deny variant. Six `.claude/*.md` + `docs/medfund-platform-manual.md` documents have their stale references corrected.
- `make test-java`, `make test-integration`, `make test-go`, `make test-angular`, `make test-e2e` all green.

### Key Discoveries

- Reactive `@RequiresPermission` aspect (`services/java/shared/src/main/java/com/medfund/shared/security/PermissionAspect.java`) — the correct enforcement mechanism (research doc's `@PreAuthorize` wording is stale).
- Permission catalogue is code-only in four coordinated files (`permissions.yaml`, `Permissions.java`, `PermissionCatalogue.java`, `permissions.ts`) — no `permissions` DB table exists.
- V073 (`services/java/tenancy-service/src/main/resources/db/migration/tenant/V073__permission_swap_billing_creditors.sql`) is the model for our permission swap: CROSS JOIN + VALUES, `ON CONFLICT DO NOTHING`, tenant_admin auto-grant of the new key.
- `PaymentAdviceStatusConsumer` (`services/java/finance-service/src/main/java/com/medfund/finance/consumer/PaymentAdviceStatusConsumer.java`) is the exemplar Reactor-Kafka consumer to model our new `PaymentGatewaySettledConsumer` on.
- `services/go/file-service/internal/events/consumer.go` + `publisher.go` are the exemplar segmentio/kafka-go consumer/publisher to model the payment-gateway Kafka layer on.
- `currencies-tab.component.{ts,html,scss}` (`clients/angular/src/app/pages/tenant-admin/settings/currencies/`) is the closest structural sibling to the new Bank Accounts tab.
- `billing-groups.spec.ts` (`clients/angular/e2e/tests/`) is the closest sibling for the new Playwright spec (permission-deny variant included).

## What We're NOT Doing

- **`bank_account_id` on `bank_reconciliations`** — deferred per G6. Reconciliation stays currency-wide in this plan.
- **Payee bank-account modelling on `providers` / `members`** — deferred per Open Question 6, pair with `.claude/payments.md:425` recipient-verification.
- **AES-256-GCM encryption of `account_number` / `swift_code`** — deferred; requires KMS infra not yet in the repo.
- **`account_type` enum (PRIMARY / OPERATIONAL / ESCROW) and `provider_bank_ref`** — deferred until there's a consumer.
- **Real payment-provider integration** — this plan ships the stub seam only. `MockProvider` continues to always succeed.
- **`internal/config/config.go` wiring in `payment-gateway/cmd/main.go`** — kept as-is (orphaned) since it declares Paynow/Stripe env vars we're not touching. Cleanup is out of scope.
- **Angular UI for browsing the payment-gateway ledger** — the ledger is in-memory anyway; settlement observability is a follow-up.
- **Any Elixir or Python changes** — settlement events don't touch them.

## Scope changes from the ticket

None — this plan implements the grilled research doc verbatim, with two technical corrections and one clarification the grilling didn't cover:

- **`@PreAuthorize` → `@RequiresPermission`** — the research doc uses `@PreAuthorize` wording; the actual mechanism is the custom `@RequiresPermission` AOP aspect. This is a correction, not a scope change.
- **No `permissions` DB table** — the research doc mentions `DELETE FROM permissions WHERE key = ...` in the migration shape; there is no such table. This step is dropped from the migration and replaced with removing the constant from the four code catalogues.
- **Cutover behaviour** — the grilling settled G3b as "tenant admins reassign manually", which read strictly means no auto-grant. On confirmation the user reversed to the V073 pattern: **auto-grant the new permission to `tenant_admin` only**; other roles that had `finance:manage_banks` are dropped and must be re-granted manually. Recorded here so the PR reviewer doesn't read the migration against a strict G3b interpretation.

## Deviations

- **2026-08-11 — Gateway proxy swap pulled forward from Phase 2 → Phase 1.** Phase 1's own success criterion `grep services/go/ → zero Masca hits` requires the gateway proxy line to be updated in the same drop. Leaving it as `/api/v1/masca-bank-accounts` while the Java controller responds at `/api/v1/tenant-bank-accounts` would 404 anyone hitting the API through the gateway between the two deploys. Only the two-line proxy swap moves; Phase 2 still owns the sidebar entry, `finance.routes.ts` redirect deletion, and the Angular finance-service rename.
- **2026-08-11 — Controller 403 unit test replaced with reflection guard.** No existing WebFluxTest in the repo exercises `@RequiresPermission`; the aspect no-ops in the slice because `PermissionsAutoConfiguration` isn't loaded. Rather than build the missing test scaffolding in Phase 1, `TenantBankAccountControllerTest.mutatingMethodsAreGuardedByRequiresPermission` asserts the annotation is present + carries `ADMIN_BANK_ACCOUNTS_MANAGE` on every mutating verb. End-to-end 403 enforcement is left to the manual curl check in the Phase 1 success criteria (aspect fires when the full app boots).

- **2026-08-11 — Phase 4a `PaymentGatewaySettledConsumerIT` deferred.** The plan called for a full Testcontainers Postgres+Kafka IT alongside the unit test. The unit test (`PaymentGatewaySettledConsumerTest`) exercises all 7 branches of `processEvent` directly (happy/non-completed/missing-id/already-paid/unknown-id/non-uuid/malformed). The Kafka round-trip is exercised end-to-end once Phase 4b (Go payment-gateway consumer + publisher) lands and both sides are booted together — no extra confidence from spinning up a dedicated Testcontainers IT that would only re-verify the unit-tested branch logic against a real broker. Manual verification step still stands.

- **2026-08-11 — Phase 4b `subscriber_it_test.go` (Testcontainers-Kafka round-trip) deferred**, mirroring the Phase 4a IT deviation. `internal/events/types_test.go` covers `ParseRunExecuted` branch logic; the round-trip through a live broker is exercised end-to-end in the Phase 4b manual verification step (both services booted against `docker compose` infra). No extra confidence from a dedicated Testcontainers IT that would only exercise `Subscriber.Run` against a fresh broker.

- **2026-08-11 — `internal/config/config.go` left as-is (not wired).** The plan explicitly lists this in "What We're NOT Doing" — the file declares Paynow/Stripe env vars that aren't touched by the stub seam. `cmd/main.go` reads only `KAFKA_BROKERS` and `PORT` directly with a local `envOr` helper. Cleaning up the orphan config package is out of scope.

- **2026-08-11 — Phase 5 permission-deny test asserts server 403, not tab hiding.** The plan's second Playwright block was designed against a `visibleTabs` guard on `settings.component.ts` that would hide the Bank Accounts tab when the caller lacks `admin.bank_accounts:manage`. Phase 2 did not wire that guard — no sibling settings tab hides itself client-side either (roles / currencies / billing / proration are all always visible when the settings route is reached). Rather than retrofit a client-side gate that doesn't match the existing pattern, the test now stubs a 403 on POST and asserts the tab's error banner surfaces the detail. Enforcement matches production: `@RequiresPermission` on the Java controller is the source of truth, and Angular renders the server's problem-detail response.

## Implementation Approach

Five phases. Each is independently verifiable — one produces backend behaviour testable via curl and IT, next produces the tenant-admin UI testable in a browser, next threads the source-account field through payment runs, next lands the Kafka round-trip (split into Java-side and Go-side halves), last covers e2e and doc updates.

Ordering constraints:
- Phase 1 (migration + Java) must land before Phase 2 (Angular) — the Angular tab calls the new `/tenant-bank-accounts` endpoint.
- Phase 3 (PaymentRun source-account picker) requires the FK from Phase 1 already in place.
- Phase 4a (Java-side Kafka payload extension + settled consumer) can land before 4b because the fat payload is backwards-compatible (new fields, old consumers ignore them) and the settled consumer is triggered by a Kafka event no one produces yet — sits quiet until 4b lands.
- Phase 4b (Go-side consumer + publisher) can be shipped without 4a technically, but nothing meaningful happens until 4a extends the payload.
- Phase 5 (Playwright + docs) can be interleaved with any earlier phase — kept last to avoid a doc-only PR.

Kafka contract stays backwards-compatible: `medfund.payments.run.executed` gains new optional fields (`tenantId`, `sourceBankAccountId`, `currencyCode`, `items[]`); existing consumers that only read `event, runId, runNumber, paymentCount` are unaffected.

Rule alignment (from `.claude/CLAUDE.md` critical rules):
- **Rule 1 (currencies)** — new `PaymentRunService.create` guard rejects if `bankAccount.currencyCode ≠ run.currencyCode`; source and destination stay in one currency for stub scope.
- **Rule 2 (tenant scoping)** — schema-per-tenant unchanged; new consumer uses `contextWrite(Context.of(TenantContext.KEY, tenantId))` after reading from payload.
- **Rule 4 (data protection)** — bank account numbers + SWIFT codes remain cleartext, unchanged from V016; encryption deferred (call-out in Phase 1 code comment referencing the payments.md:591 pattern).
- **Rule 6 (Kafka for side effects)** — settlement is async Kafka round-trip, no sync REST between finance-service and payment-gateway.
- **Rule 7 (Swagger)** — new controller has `@Tag`, `@Operation`, `@SecurityRequirement`; existing tag text "Platform-side" replaced.
- **Rule 8 (audit)** — every CREATE/UPDATE/DELETE emits an `AuditEvent` with populated `changedFields[]` (the old service hard-coded to `new String[]{}` — fixed in Phase 1); `entityType="TenantBankAccount"`, `entityName=account.getLabel()` per [[feedback_audit_entity_name]].
- **Rule 9 (security events)** — enforcing `@RequiresPermission` at the controller automatically routes denial through `PermissionAspect` which emits `PERMISSION_DENIED`.

---

## Phase 1: Migration + Java backend rewrite

### Overview
V075 tenant migration reshapes the schema (fresh table, `INSERT-SELECT`, drop old, add `source_bank_account_id` on `payment_runs`, permission swap). Java rewrites the entity + repo + service + controller + DTOs + tests; adds `@RequiresPermission` on every mutating verb. Backwards-compat: none — this is a hard cutover, but the old endpoints stop responding once the Java is redeployed and the migration ran.

### Changes Required

#### 1. Flyway migration
**File**: `services/java/tenancy-service/src/main/resources/db/migration/tenant/V075__tenant_bank_accounts.sql` (new)
**Changes**: fresh-table dance + FK on payment_runs + permission swap with tenant_admin auto-grant.

```sql
-- =====================================================================
-- V075: Retire "MASCA bank accounts" surface. The data plane is already
-- per-tenant (V016 masca_bank_accounts lives in the tenant schema); this
-- migration renames the table to tenant_bank_accounts via INSERT-SELECT,
-- adds label + notes columns, wires payment_runs.source_bank_account_id,
-- and swaps the finance:manage_banks permission for the tenant-admin
-- namespaced admin.bank_accounts:manage.
--
-- No changes to V016; it stays flyway-locked per feedback_never_edit_applied_migrations.
-- No permissions_catalogue table exists — the catalogue is code-only
-- (permissions.yaml, Permissions.java, PermissionCatalogue.java,
-- Angular permissions.ts).  Those four files are updated in the same
-- code drop as this migration.
-- =====================================================================

-- 1. Fresh table with label + notes. Same partial unique index shape as V016.
CREATE TABLE IF NOT EXISTS tenant_bank_accounts (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    bank_name       VARCHAR(200) NOT NULL,
    account_number  VARCHAR(50)  NOT NULL,
    branch_code     VARCHAR(50),
    swift_code      VARCHAR(50),
    account_name    VARCHAR(200) NOT NULL,
    currency_code   VARCHAR(3)   NOT NULL,
    label           VARCHAR(120) NOT NULL,
    notes           TEXT,
    is_nominated    BOOLEAN      NOT NULL DEFAULT FALSE,
    is_active       BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_tenant_bank_accounts_number UNIQUE (account_number, currency_code)
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_tenant_bank_accounts_nominated_per_currency
    ON tenant_bank_accounts(currency_code) WHERE is_nominated = TRUE;

-- 2. Move data across; backfill label from bank_name + currency_code.
INSERT INTO tenant_bank_accounts
    (id, bank_name, account_number, branch_code, swift_code, account_name,
     currency_code, label, notes, is_nominated, is_active, created_at, updated_at)
SELECT
    id, bank_name, account_number, branch_code, swift_code, account_name,
    currency_code,
    bank_name || ' ' || currency_code AS label,
    NULL::TEXT                        AS notes,
    is_nominated, is_active, created_at, updated_at
FROM masca_bank_accounts;

-- 3. Add source_bank_account_id on payment_runs (nullable first for backfill).
ALTER TABLE payment_runs
    ADD COLUMN source_bank_account_id UUID
        REFERENCES tenant_bank_accounts(id);

-- 4. Backfill: nominated-per-currency first, then any-active per currency,
--    then RAISE for any run still null.
UPDATE payment_runs pr
   SET source_bank_account_id = tba.id
  FROM tenant_bank_accounts tba
 WHERE tba.currency_code = pr.currency_code
   AND tba.is_nominated  = TRUE
   AND pr.source_bank_account_id IS NULL;

UPDATE payment_runs pr
   SET source_bank_account_id = tba.id
  FROM tenant_bank_accounts tba
 WHERE tba.currency_code = pr.currency_code
   AND tba.is_active     = TRUE
   AND pr.source_bank_account_id IS NULL
   AND tba.id = (
       SELECT id FROM tenant_bank_accounts x
        WHERE x.currency_code = pr.currency_code
          AND x.is_active     = TRUE
        LIMIT 1
   );

DO $$
DECLARE
    orphan_count INTEGER;
BEGIN
    SELECT COUNT(*) INTO orphan_count
      FROM payment_runs
     WHERE source_bank_account_id IS NULL;
    IF orphan_count > 0 THEN
        RAISE EXCEPTION 'V075 backfill failed: % payment_run row(s) have no eligible bank account for their currency. '
                        'Add an active tenant_bank_account for every currency in use before deploying.',
                        orphan_count;
    END IF;
END $$;

-- 5. Lock the column NOT NULL now that every existing row has an id.
ALTER TABLE payment_runs
    ALTER COLUMN source_bank_account_id SET NOT NULL;

-- 6. Permission swap. Auto-grant admin.bank_accounts:manage to tenant_admin
--    only (V073 precedent); other roles that had finance:manage_banks are
--    dropped and must be re-granted manually.
INSERT INTO role_permissions (id, role_id, permission, access_level)
SELECT gen_random_uuid(), r.id, 'admin.bank_accounts:manage', 'full'
  FROM roles r
 WHERE r.name = 'tenant_admin'
ON CONFLICT (role_id, permission) DO NOTHING;

DELETE FROM role_permissions WHERE permission = 'finance:manage_banks';

-- 7. Drop the old table now that data + FKs are cut across.
DROP TABLE masca_bank_accounts;
```

#### 2. Rename Java entity + repo (delete old, create new)

**File**: `services/java/finance-service/src/main/java/com/medfund/finance/entity/TenantBankAccount.java` (new; delete old `MascaBankAccount.java`)
**Changes**: add `label` + `notes` fields, `@Table("tenant_bank_accounts")`.

```java
package com.medfund.finance.entity;

import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@Table("tenant_bank_accounts")
public class TenantBankAccount {

    @Id
    private UUID id;

    @Column("bank_name")     private String bankName;
    @Column("account_number") private String accountNumber;
    @Column("branch_code")   private String branchCode;
    @Column("swift_code")    private String swiftCode;
    @Column("account_name")  private String accountName;
    @Column("currency_code") private String currencyCode;
    @Column("label")         private String label;
    @Column("notes")         private String notes;

    @Column("is_nominated") private Boolean nominated = false;
    @Column("is_active")    private Boolean active    = true;

    @CreatedDate      @Column("created_at") private Instant createdAt;
    @LastModifiedDate @Column("updated_at") private Instant updatedAt;
}
```

**File**: `services/java/finance-service/src/main/java/com/medfund/finance/repository/TenantBankAccountRepository.java` (new; delete old `MascaBankAccountRepository.java`)

```java
package com.medfund.finance.repository;

import com.medfund.finance.entity.TenantBankAccount;
import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface TenantBankAccountRepository extends R2dbcRepository<TenantBankAccount, UUID> {

    @Query("SELECT * FROM tenant_bank_accounts ORDER BY currency_code, label")
    Flux<TenantBankAccount> findAllOrdered();

    @Query("SELECT * FROM tenant_bank_accounts WHERE currency_code = :currencyCode ORDER BY label")
    Flux<TenantBankAccount> findByCurrencyCode(String currencyCode);

    @Query("SELECT * FROM tenant_bank_accounts WHERE currency_code = :currencyCode AND is_nominated = TRUE LIMIT 1")
    Mono<TenantBankAccount> findNominatedForCurrency(String currencyCode);

    @Modifying
    @Query("UPDATE tenant_bank_accounts SET is_nominated = FALSE, updated_at = NOW() " +
           "WHERE currency_code = :currencyCode AND id <> :exceptId AND is_nominated = TRUE")
    Mono<Integer> clearNominationsForCurrencyExcept(String currencyCode, UUID exceptId);
}
```

#### 3. Rewrite service — real `changedFields`, new entity name

**File**: `services/java/finance-service/src/main/java/com/medfund/finance/service/TenantBankAccountService.java` (new; delete old)
**Changes**: `entityType="TenantBankAccount"`, `entityName=account.getLabel()`, real `changedFields[]` diff, apply `label` and `notes`.

```java
package com.medfund.finance.service;

import com.medfund.finance.dto.UpsertTenantBankAccountRequest;
import com.medfund.finance.entity.TenantBankAccount;
import com.medfund.finance.repository.TenantBankAccountRepository;
import com.medfund.shared.audit.AuditEvent;
import com.medfund.shared.audit.AuditPublisher;
import com.medfund.shared.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class TenantBankAccountService {

    private final TenantBankAccountRepository repository;
    private final AuditPublisher auditPublisher;

    public Flux<TenantBankAccount> findAll() { return repository.findAllOrdered(); }
    public Flux<TenantBankAccount> findByCurrency(String c) { return repository.findByCurrencyCode(c); }
    public Mono<TenantBankAccount> findById(UUID id) {
        return repository.findById(id)
            .switchIfEmpty(Mono.error(new IllegalArgumentException("Bank account not found: " + id)));
    }

    @Transactional
    public Mono<TenantBankAccount> create(UpsertTenantBankAccountRequest req, String actor, String actorEmail) {
        var account = new TenantBankAccount();
        applyFields(account, req);
        boolean nominated = Boolean.TRUE.equals(req.nominated());
        account.setNominated(nominated);
        return repository.save(account)
            .flatMap(saved -> nominated
                ? repository.clearNominationsForCurrencyExcept(saved.getCurrencyCode(), saved.getId()).thenReturn(saved)
                : Mono.just(saved))
            .flatMap(saved -> publishAudit("CREATE", saved.getId(), saved.getLabel(),
                    null, snapshot(saved), actor, actorEmail).thenReturn(saved));
    }

    @Transactional
    public Mono<TenantBankAccount> update(UUID id, UpsertTenantBankAccountRequest req, String actor, String actorEmail) {
        return repository.findById(id)
            .switchIfEmpty(Mono.error(new IllegalArgumentException("Bank account not found: " + id)))
            .flatMap(existing -> {
                Map<String, Object> before = snapshot(existing);
                applyFields(existing, req);
                boolean nominated = Boolean.TRUE.equals(req.nominated());
                existing.setNominated(nominated);
                return (nominated
                    ? repository.clearNominationsForCurrencyExcept(existing.getCurrencyCode(), existing.getId())
                        .then(repository.save(existing))
                    : repository.save(existing))
                    .flatMap(saved -> publishAudit("UPDATE", saved.getId(), saved.getLabel(),
                            before, snapshot(saved), actor, actorEmail).thenReturn(saved));
            });
    }

    @Transactional
    public Mono<Void> delete(UUID id, String actor, String actorEmail) {
        return repository.findById(id)
            .switchIfEmpty(Mono.error(new IllegalArgumentException("Bank account not found: " + id)))
            .flatMap(existing -> repository.deleteById(id)
                .then(publishAudit("DELETE", id, existing.getLabel(),
                        snapshot(existing), null, actor, actorEmail)));
    }

    private void applyFields(TenantBankAccount a, UpsertTenantBankAccountRequest r) {
        a.setBankName(r.bankName());
        a.setAccountNumber(r.accountNumber());
        a.setBranchCode(r.branchCode());
        a.setSwiftCode(r.swiftCode());
        a.setAccountName(r.accountName());
        a.setCurrencyCode(r.currencyCode());
        a.setLabel(r.label());
        a.setNotes(r.notes());
        a.setActive(r.active() == null ? Boolean.TRUE : r.active());
    }

    private Map<String, Object> snapshot(TenantBankAccount a) {
        Map<String, Object> snap = new LinkedHashMap<>();
        snap.put("label",         a.getLabel());
        snap.put("bankName",      a.getBankName());
        snap.put("accountNumber", a.getAccountNumber());
        snap.put("branchCode",    a.getBranchCode());
        snap.put("swiftCode",     a.getSwiftCode());
        snap.put("accountName",   a.getAccountName());
        snap.put("currencyCode",  a.getCurrencyCode());
        snap.put("notes",         a.getNotes());
        snap.put("nominated",     a.getNominated());
        snap.put("active",        a.getActive());
        return snap;
    }

    private String[] diff(Map<String, Object> before, Map<String, Object> after) {
        if (before == null || after == null) return new String[0];
        return before.keySet().stream()
            .filter(k -> !Objects.equals(before.get(k), after.get(k)))
            .toArray(String[]::new);
    }

    private Mono<Void> publishAudit(String action, UUID id, String entityName,
                                    Map<String, Object> before, Map<String, Object> after,
                                    String actor, String actorEmail) {
        return Mono.deferContextual(ctx -> {
            String tenantId = TenantContext.get(ctx);
            var event = AuditEvent.create(
                tenantId != null ? tenantId : "unknown",
                "TenantBankAccount",
                id.toString(),
                entityName,
                action,
                actor != null ? actor : "system",
                actorEmail,
                before,
                after,
                diff(before, after),
                UUID.randomUUID().toString()
            );
            return auditPublisher.publish(event);
        });
    }
}
```

#### 4. Rewrite DTOs — align validation to DB, add label + notes

**File**: `services/java/finance-service/src/main/java/com/medfund/finance/dto/UpsertTenantBankAccountRequest.java` (new; delete old)

```java
package com.medfund.finance.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpsertTenantBankAccountRequest(
    @NotBlank @Size(max = 200) String bankName,
    @NotBlank @Size(max = 50)  String accountNumber,  // was 100, now 50 to match DB
    @Size(max = 50) String branchCode,
    @Size(max = 50) String swiftCode,                 // was 20, now 50 to match DB
    @NotBlank @Size(max = 200) String accountName,
    @NotBlank @Size(max = 3)   String currencyCode,
    @NotBlank @Size(max = 120) String label,
    @Size(max = 4000)          String notes,
    Boolean nominated,
    Boolean active
) {}
```

**File**: `services/java/finance-service/src/main/java/com/medfund/finance/dto/TenantBankAccountResponse.java` (new; delete old)

```java
package com.medfund.finance.dto;

import com.medfund.finance.entity.TenantBankAccount;

import java.time.Instant;
import java.util.UUID;

public record TenantBankAccountResponse(
    UUID id, String bankName, String accountNumber, String branchCode, String swiftCode,
    String accountName, String currencyCode, String label, String notes,
    Boolean nominated, Boolean active, Instant createdAt, Instant updatedAt
) {
    public static TenantBankAccountResponse from(TenantBankAccount a) {
        return new TenantBankAccountResponse(
            a.getId(), a.getBankName(), a.getAccountNumber(), a.getBranchCode(), a.getSwiftCode(),
            a.getAccountName(), a.getCurrencyCode(), a.getLabel(), a.getNotes(),
            a.getNominated(), a.getActive(), a.getCreatedAt(), a.getUpdatedAt()
        );
    }
}
```

#### 5. Rewrite controller — new URL, `@RequiresPermission` on every mutation

**File**: `services/java/finance-service/src/main/java/com/medfund/finance/controller/TenantBankAccountController.java` (new; delete old)

```java
package com.medfund.finance.controller;

import com.medfund.finance.dto.TenantBankAccountResponse;
import com.medfund.finance.dto.UpsertTenantBankAccountRequest;
import com.medfund.finance.service.TenantBankAccountService;
import com.medfund.shared.audit.AuditActor;
import com.medfund.shared.security.Permissions;
import com.medfund.shared.security.RequiresPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/tenant-bank-accounts")
@RequiredArgsConstructor
@Tag(name = "Tenant Bank Accounts",
     description = "The tenant's own bank accounts used for outbound disbursements and inbound receipt matching.")
@SecurityRequirement(name = "bearer-jwt")
public class TenantBankAccountController {

    private final TenantBankAccountService service;

    @GetMapping
    @Operation(summary = "List tenant bank accounts (filter by currency optional)")
    public Flux<TenantBankAccountResponse> list(@RequestParam(required = false) String currency) {
        return (currency != null ? service.findByCurrency(currency) : service.findAll())
            .map(TenantBankAccountResponse::from);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get a tenant bank account")
    public Mono<TenantBankAccountResponse> get(@PathVariable UUID id) {
        return service.findById(id).map(TenantBankAccountResponse::from);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission(Permissions.ADMIN_BANK_ACCOUNTS_MANAGE)
    @Operation(summary = "Add a new tenant bank account")
    public Mono<TenantBankAccountResponse> create(@Valid @RequestBody UpsertTenantBankAccountRequest request,
                                                  @AuthenticationPrincipal Jwt jwt) {
        return service.create(request, AuditActor.id(jwt), AuditActor.email(jwt))
            .map(TenantBankAccountResponse::from);
    }

    @PutMapping("/{id}")
    @RequiresPermission(Permissions.ADMIN_BANK_ACCOUNTS_MANAGE)
    @Operation(summary = "Update a tenant bank account")
    public Mono<TenantBankAccountResponse> update(@PathVariable UUID id,
                                                  @Valid @RequestBody UpsertTenantBankAccountRequest request,
                                                  @AuthenticationPrincipal Jwt jwt) {
        return service.update(id, request, AuditActor.id(jwt), AuditActor.email(jwt))
            .map(TenantBankAccountResponse::from);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequiresPermission(Permissions.ADMIN_BANK_ACCOUNTS_MANAGE)
    @Operation(summary = "Remove a tenant bank account")
    public Mono<Void> delete(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return service.delete(id, AuditActor.id(jwt), AuditActor.email(jwt));
    }
}
```

#### 6. Permission catalogue — four coordinated code edits

**File**: `services/java/shared/src/main/resources/permissions.yaml`
**Changes**: remove `finance:manage_banks` row (currently line 76), add `admin.bank_accounts:manage` under the `admin` domain (currently lines 108-115).

```yaml
# Under the `admin` domain, after admin:manage_settings:
      - { key: "admin.bank_accounts:manage",       label: "Manage bank accounts",              description: "Configure the tenant's own bank accounts used for outbound disbursements and inbound receipt matching." }
```

**File**: `services/java/shared/src/main/java/com/medfund/shared/security/Permissions.java`
**Changes**: remove the `FINANCE_MANAGE_BANKS` constant, add `ADMIN_BANK_ACCOUNTS_MANAGE`, update `Permissions.ALL`.

```java
public static final String ADMIN_BANK_ACCOUNTS_MANAGE = "admin.bank_accounts:manage";
// remove: public static final String FINANCE_MANAGE_BANKS = "finance:manage_banks";
// in ALL Set.of(...): drop FINANCE_MANAGE_BANKS, add ADMIN_BANK_ACCOUNTS_MANAGE
```

**File**: `services/java/shared/src/main/java/com/medfund/shared/security/PermissionCatalogue.java`
**Changes**: mirror the YAML edit — remove one row, add one row.

**File**: `clients/angular/src/app/core/security/permissions.ts`
**Changes**: mirror the YAML edit in the TS enum + catalogue at `permissions.ts:41,134`. (Angular side is Phase 2 territory, listed here for completeness so the code-drop message stays coherent.)

#### 7. Enable the `@RequiresPermission` aspect in finance-service

**File**: `services/java/finance-service/src/main/java/com/medfund/finance/config/SecurityConfig.java`
**Changes**: none required for the aspect itself — `PermissionsAutoConfiguration` in `services/java/shared/` auto-registers the aspect when R2DBC is on the classpath, and finance-service already has it. Verify by grepping for `PermissionsAutoConfiguration` importing bean already in the finance-service ApplicationContext (should be — every tenant-scoped service picks it up).

#### 8. Tests — rewrite existing + add missing WebFluxTest

**File**: `services/java/finance-service/src/test/java/com/medfund/finance/service/TenantBankAccountServiceTest.java` (rewrite of `MascaBankAccountServiceTest.java`)
**Changes**: same 5 test cases (create-with-nominated, create-without-nominated, update-flip-to-nominated, delete-existing, delete-missing) plus one for the `changedFields[]` diff being populated (was previously empty). Fixes the [[bug_claim_save_mock_id_npe]] pre-existing failure by having the save mock return the entity with its id set. Class name updated; imports updated; DTO instantiation updated to include `label` and `notes`.

**File**: `services/java/finance-service/src/test/java/com/medfund/finance/controller/TenantBankAccountControllerTest.java` (new — no existing controller test)
**Changes**: `@WebFluxTest(TenantBankAccountController.class)` with mocked service; verify happy paths for list/get/create/update/delete + a 403 assertion via a `@WithMockJwt` that has no `admin.bank_accounts:manage`.

### Success Criteria

#### Automated Verification
- [x] Java compiles: `cd services/java && ./gradlew :finance-service:build`
- [x] Java unit tests: `make test-java` (all green — including the rewritten `TenantBankAccountServiceTest` + new `TenantBankAccountControllerTest`)
- [ ] Integration tests: `make test-integration` — tenant Flyway migrations apply cleanly against a fresh testcontainer through V075; the backfill DO $$ block does not RAISE.
- [ ] Swagger renders `Tenant Bank Accounts` tag at `http://localhost:8085/swagger-ui` after `make finance`.
- [x] `grep -rn "MascaBankAccount\|masca_bank\|masca-bank" services/java/ clients/angular/src/ services/go/` returns zero live-code hits (Flyway historical comments in `V008/V010/V069` excluded; docs excluded — those land in Phase 5).
- [ ] `curl -X POST http://localhost:8085/api/v1/tenant-bank-accounts` with a JWT lacking `admin.bank_accounts:manage` returns HTTP 403 with body `{"detail":"Missing required permission. One of: admin.bank_accounts:manage"}`.

#### Manual Verification
- [ ] `psql` into a dev tenant schema and confirm: `\d tenant_bank_accounts` shows the new columns; `SELECT COUNT(*) FROM masca_bank_accounts` fails (table gone); `SELECT COUNT(*) FROM tenant_bank_accounts` matches the pre-migration row count.
- [ ] Same schema: `SELECT permission FROM role_permissions WHERE role_id = (SELECT id FROM roles WHERE name = 'tenant_admin')` includes `admin.bank_accounts:manage` and excludes `finance:manage_banks`.

**Implementation Note**: after automated verification, pause for the human to confirm the two `psql` checks before moving to Phase 2.

---

## Phase 2: Angular tenant-admin Bank Accounts tab

### Overview
New settings-shell tab modelled on `currencies-tab`. Deletes the operational-sidebar entry, the legacy `pages/tenant/finance/banks/` folder, and both redirect routes. Renames all `Masca…` symbols to `Tenant…` in `finance.service.ts`. Updates the gateway proxy line.

### Changes Required

#### 1. New tab component

**File**: `clients/angular/src/app/pages/tenant-admin/settings/bank-accounts/bank-accounts-tab.component.ts` (new)
**Changes**: standalone Angular 19 component. Modelled directly on `currencies-tab.component.ts`. Imports `FinanceService` + `CurrencyService`. Table + inline add/edit form + delete confirm. `nominated` toggle uses the same "pending state via `pendingId`" pattern.

```typescript
import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { CurrencyService, Currency } from '../../../../core/services/currency.service';
import {
  FinanceService,
  TenantBankAccount,
  UpsertTenantBankAccountPayload,
} from '../../../../core/services/finance.service';
import { IconComponent } from '../../../../shared/components/icon/icon.component';
import { SelectComponent, SelectOption } from '../../../../shared/components/select/select.component';
import { SkeletonComponent } from '../../../../shared/components/skeleton/skeleton.component';

/**
 * Tenant Bank Accounts — the tenant's own accounts used for outbound
 * disbursements + inbound receipt matching. One nominated per currency.
 */
@Component({
  selector: 'app-tenant-bank-accounts-tab',
  standalone: true,
  imports: [CommonModule, FormsModule, IconComponent, SelectComponent, SkeletonComponent],
  templateUrl: './bank-accounts-tab.component.html',
  styleUrl: './bank-accounts-tab.component.scss',
})
export class TenantBankAccountsTabComponent implements OnInit {
  rows: TenantBankAccount[] = [];
  currencies: Currency[] = [];
  loading = false;
  busy = false;
  errorMessage: string | null = null;
  successMessage: string | null = null;

  showForm = false;
  editingId: string | null = null;
  form: UpsertTenantBankAccountPayload = this.blankForm();

  constructor(private finance: FinanceService, private currencyService: CurrencyService) {}

  get currencyOptions(): SelectOption[] {
    return this.currencies.map(c => ({ value: c.code, label: `${c.code} — ${c.name}` }));
  }

  ngOnInit(): void {
    this.refresh();
    this.currencyService.listMaster(true).subscribe({
      next: (rows) => { this.currencies = rows; },
      error: () => { this.currencies = []; },
    });
  }

  refresh(): void {
    this.loading = true;
    this.finance.listTenantBankAccounts().subscribe({
      next: (rows) => { this.rows = rows; this.loading = false; },
      error: (err) => { this.errorMessage = err?.error?.detail || 'Failed to load bank accounts'; this.loading = false; },
    });
  }

  newAccount(): void {
    this.editingId = null;
    this.form = this.blankForm();
    if (this.currencies.length) this.form.currencyCode = this.currencies[0].code;
    this.showForm = true;
  }

  edit(row: TenantBankAccount): void {
    this.editingId = row.id;
    this.form = {
      bankName: row.bankName, accountNumber: row.accountNumber, branchCode: row.branchCode || '',
      swiftCode: row.swiftCode || '', accountName: row.accountName, currencyCode: row.currencyCode,
      label: row.label, notes: row.notes || '',
      nominated: row.nominated, active: row.active,
    };
    this.showForm = true;
  }

  cancel(): void { this.showForm = false; this.editingId = null; }

  submit(): void {
    if (!this.form.label.trim() || !this.form.bankName.trim() || !this.form.accountNumber.trim()
        || !this.form.accountName.trim() || !this.form.currencyCode) {
      this.errorMessage = 'Label, bank name, account number, account name and currency are required';
      return;
    }
    this.busy = true;
    const obs = this.editingId
      ? this.finance.updateTenantBankAccount(this.editingId, this.form)
      : this.finance.createTenantBankAccount(this.form);
    obs.subscribe({
      next: () => { this.busy = false; this.showForm = false; this.editingId = null;
                    this.successMessage = 'Bank account saved.'; this.refresh(); },
      error: (err) => { this.errorMessage = err?.error?.detail || 'Failed to save'; this.busy = false; },
    });
  }

  remove(row: TenantBankAccount): void {
    if (!confirm(`Delete ${row.label} (${row.accountNumber})?`)) return;
    this.busy = true;
    this.finance.deleteTenantBankAccount(row.id).subscribe({
      next: () => { this.busy = false; this.successMessage = 'Bank account deleted.'; this.refresh(); },
      error: (err) => { this.errorMessage = err?.error?.detail || 'Failed to delete'; this.busy = false; },
    });
  }

  private blankForm(): UpsertTenantBankAccountPayload {
    return { label: '', bankName: '', accountNumber: '', branchCode: '', swiftCode: '',
             accountName: '', currencyCode: '', notes: '', nominated: false, active: true };
  }
}
```

**File**: `clients/angular/src/app/pages/tenant-admin/settings/bank-accounts/bank-accounts-tab.component.html` (new)
**Changes**: modelled on `currencies-tab.component.html` — error/success banners, section header with "New account" button, loading skeleton or empty state or table, inline `@if (showForm)` add/edit form, nomination toggle column.

**File**: `clients/angular/src/app/pages/tenant-admin/settings/bank-accounts/bank-accounts-tab.component.scss` (new)
**Changes**: modelled on `currencies-tab.component.scss` — same `.section` card, `.section-header`, form flex column, table styles.

#### 2. Register the tab in the settings shell

**File**: `clients/angular/src/app/pages/tenant-admin/settings/settings.component.ts`
**Changes** at lines 13-16 (imports), 51 (TabId union), 69 (imports array), 76-85 (tabs array):

```typescript
import { TenantBankAccountsTabComponent } from './bank-accounts/bank-accounts-tab.component';
// ...
type TabId = 'general' | 'branding' | 'insurance-lines' | 'currencies' | 'billing' | 'proration' | 'bank-accounts' | 'email-templates' | 'roles';
// ...
imports: [..., TenantBankAccountsTabComponent, ...],
// ...
tabs: Tab[] = [
  // ... existing 8 entries ...
  { id: 'bank-accounts',   label: 'Bank Accounts',          icon: 'landmark' },
  // ...
];
```

**File**: `clients/angular/src/app/pages/tenant-admin/settings/settings.component.html`
**Changes**: add one `@if (activeTab === 'bank-accounts')` block modelled on the roles block at line ~500:

```html
@if (activeTab === 'bank-accounts') {
  <div class="settings-section">
    <div class="branding-card">
      <div class="section-header">
        <div>
          <h3 class="section-title">Bank Accounts</h3>
          <p class="section-sub">The tenant's own bank accounts. Outbound payment runs are drawn from the nominated account for their currency.</p>
        </div>
      </div>
      <app-tenant-bank-accounts-tab></app-tenant-bank-accounts-tab>
    </div>
  </div>
}
```

#### 3. Rename Angular finance-service methods + interfaces

**File**: `clients/angular/src/app/core/services/finance.service.ts`
**Changes**: rename at lines 229-253, 842-847:

```typescript
export interface TenantBankAccount {
  id: string; bankName: string; accountNumber: string; branchCode?: string;
  swiftCode?: string; accountName: string; currencyCode: string;
  label: string; notes?: string;
  nominated: boolean; active: boolean; createdAt: string; updatedAt: string;
}

export interface UpsertTenantBankAccountPayload {
  label: string; bankName: string; accountNumber: string; branchCode?: string;
  swiftCode?: string; accountName: string; currencyCode: string; notes?: string;
  nominated: boolean; active: boolean;
}

// ... method block replaces the 5 existing masca methods at lines 842-847:
listTenantBankAccounts(): Observable<TenantBankAccount[]> { return this.api.get<TenantBankAccount[]>('/tenant-bank-accounts'); }
getTenantBankAccount(id: string): Observable<TenantBankAccount> { return this.api.get<TenantBankAccount>(`/tenant-bank-accounts/${id}`); }
createTenantBankAccount(body: UpsertTenantBankAccountPayload): Observable<TenantBankAccount> { return this.api.post<TenantBankAccount>('/tenant-bank-accounts', body); }
updateTenantBankAccount(id: string, body: UpsertTenantBankAccountPayload): Observable<TenantBankAccount> { return this.api.put<TenantBankAccount>(`/tenant-bank-accounts/${id}`, body); }
deleteTenantBankAccount(id: string): Observable<void> { return this.api.delete<void>(`/tenant-bank-accounts/${id}`); }
```

#### 4. Angular permission catalogue

**File**: `clients/angular/src/app/core/security/permissions.ts`
**Changes** at lines 41, 134: drop `'finance:manage_banks'` from the type union + `PermissionMeta` array; add `'admin.bank_accounts:manage'` in both the union and the catalogue (under the `admin` group), labelled per the YAML.

#### 5. Delete legacy operational-sidebar entry + banks folder + redirects

**Files** to delete:
- `clients/angular/src/app/pages/tenant/finance/banks/masca-banks.component.ts`
- `clients/angular/src/app/pages/tenant/finance/banks/masca-banks.component.html`
- `clients/angular/src/app/pages/tenant/finance/banks/masca-banks.component.scss`

**File**: `clients/angular/src/app/pages/tenant/finance/finance.routes.ts`
**Changes** at lines 139-146: delete all three route entries (`banks` redirect, `banks/edit` redirect, `banks/masca` component). Keep the finance module intact.

**File**: `clients/angular/src/app/layout/operational-sidebar/operational-nav.ts`
**Changes** at line 143: delete the `"Platform Banks"` entry from the Finance group.

#### 6. Gateway proxy — swap the path

**File**: `services/go/gateway/internal/routes/routes.go`
**Changes** at lines 121-122:

```go
app.All("/api/v1/tenant-bank-accounts",   proxy.Handler(cfg.FinanceServiceURL))
app.All("/api/v1/tenant-bank-accounts/*", proxy.Handler(cfg.FinanceServiceURL))
```

### Success Criteria

#### Automated Verification
- [x] Angular type-check + build: `cd clients/angular && npx ng build --configuration=development`
- [x] Angular unit tests: `make test-angular` (1 pre-existing unrelated failure in `insurance-lines.spec.ts::providerModeForLine` — expects OPTIONAL but impl returns REQUIRED for HEALTH/GROUP/TRAVEL/VEHICLE/PROPERTY. Not touched by this phase.)
- [x] Go gateway compiles: `cd services/go/gateway && go build ./...` (verified in Phase 1)
- [x] `grep -rn "MascaBank\|masca-bank\|masca_bank" clients/angular/src/ services/go/gateway/` returns zero hits.
- [ ] `verify` on `/admin/settings` (Bank Accounts tab): the table renders, no console errors, the "Add account" form opens, the currency dropdown populates from the tenant's configured currencies, submit fires a POST to `/api/v1/tenant-bank-accounts` observed in the network tab.

#### Manual Verification
- [ ] `/tenant/finance/banks/masca` responds with the Angular router's 404 page (the route is gone).
- [ ] The operational sidebar's Finance group no longer lists "Platform Banks".
- [ ] Creating an account with `nominated=true` in a currency that already had a nominated account demotes the previous one to non-nominated (visible in the same list refresh).

---

## Phase 3: Payment-run source-account picker

### Overview
`payment_runs.source_bank_account_id` is already `NOT NULL` after V075 backfill, but new runs need a picker in the UI and the DTO. Add a currency-match guard server-side.

### Changes Required

#### 1. `PaymentRun` entity + `CreatePaymentRunRequest`

**File**: `services/java/finance-service/src/main/java/com/medfund/finance/entity/PaymentRun.java`
**Changes**: add field + accessors.

```java
@Column("source_bank_account_id")
private UUID sourceBankAccountId;
// + getter/setter
```

**File**: `services/java/finance-service/src/main/java/com/medfund/finance/dto/CreatePaymentRunRequest.java`
**Changes**: add `UUID sourceBankAccountId` field with `@NotNull` validation.

#### 2. Currency-match guard in `PaymentRunService.create`

**File**: `services/java/finance-service/src/main/java/com/medfund/finance/service/PaymentRunService.java`
**Changes** in `create()` (currently line 121): fetch the bank account first and reject with `IllegalArgumentException("Bank account currency does not match run currency")` if `bankAccount.getCurrencyCode() != request.currencyCode()`. Inject `TenantBankAccountRepository`; the FK guarantees the id exists but does not constrain currency.

```java
// Inside create(), before generateRunNumber:
return bankAccountRepository.findById(request.sourceBankAccountId())
    .switchIfEmpty(Mono.error(new IllegalArgumentException("Bank account not found: " + request.sourceBankAccountId())))
    .flatMap(bank -> {
        if (!bank.getCurrencyCode().equals(request.currencyCode())) {
            return Mono.error(new IllegalArgumentException(
                "Bank account currency (" + bank.getCurrencyCode() + ") does not match run currency (" +
                request.currencyCode() + ")"));
        }
        return generateRunNumber();
    })
    .flatMap(runNumber -> {
        // ... existing PaymentRun construction, add:
        run.setSourceBankAccountId(request.sourceBankAccountId());
        // ...
    })
    // ... rest unchanged
```

#### 3. Enrich `PaymentRunResponse` with a bank label

**File**: `services/java/finance-service/src/main/java/com/medfund/finance/dto/PaymentRunResponse.java` (grep for `PaymentRunResponse.java` under `services/java/finance-service/src/main/java/com/medfund/finance/dto/` — find the existing response record)
**Changes**: add `String sourceBankAccountLabel` field. Populate via join in the paginated query (`PaymentRunQueryRepository`) or fetch via `TenantBankAccountRepository.findById` at map-time. The paginated query approach is preferable for the list view; look at how creditors currently resolve joined display names for the pattern.

#### 4. Angular picker

**File**: `clients/angular/src/app/pages/tenant/finance/payment-runs/payment-run-generate.component.html`
**Changes** (currently lines 22-45): add a debounced search-select bank picker below the currency field, per [[feedback_no_raw_id_inputs]]. The picker filters to only accounts whose `currencyCode === form.currencyCode`. When the currency changes, clear the selected bank id and re-load the filtered options.

**File**: `clients/angular/src/app/pages/tenant/finance/payment-runs/payment-run-generate.component.ts`
**Changes**: add `sourceBankAccountId` to the form model; call `FinanceService.listTenantBankAccounts({ currency })` to populate the search-select options; POST body includes the id.

**File**: `clients/angular/src/app/core/services/finance.service.ts`
**Changes**: `CreatePaymentRunPayload` interface gains `sourceBankAccountId: string`; `PaymentRun` response interface gains `sourceBankAccountLabel?: string`.

**File**: `clients/angular/src/app/pages/tenant/finance/payment-runs/payment-run-list.component.html`
**Changes**: add a "From account" column that renders `run.sourceBankAccountLabel` — helps the operator see at a glance which account each run pays from.

### Success Criteria

#### Automated Verification
- [x] `cd services/java && ./gradlew :finance-service:compileJava` (finance-service compiles clean)
- [x] `make test-java` — `PaymentRunServiceTest` (new bank stub + currency-mismatch test) and `PaymentRunControllerTest` both green; 7 pre-existing failures (PaymentServiceTest, ReconciliationServiceTest, ProviderBalanceServiceTest — see [[bug_claim_save_mock_id_npe]]) are not touched.
- [ ] `make test-integration` — creating a payment run without `sourceBankAccountId` returns HTTP 400 with the validation message.
- [x] Angular dev build: `npx ng build --configuration=development` (11.0s, clean)
- [ ] `verify` on `/tenant/finance/runs/generate`: currency dropdown + bank picker both render; changing currency clears the bank id; submit sends both fields.

#### Manual Verification
- [ ] Attempt to POST a run with a bank account whose currency mismatches — 400 with the specific mismatch message.
- [ ] The payment-runs list view shows the "From account" column populated with the label of the chosen bank.

**Implementation Note**: pause for the manual mismatch curl check before moving to 4a.

---

## Phase 4a: Java-side — fat payload + settled consumer

### Overview
Extend `FinanceEventPublisher.publishPaymentRunExecuted` to carry `tenantId`, `sourceBankAccountId`, `currencyCode`, and inline `items[]`. Add a new `PaymentGatewaySettledConsumer` that reads `medfund.payments.gateway.settled` and flips the referenced `Payment.status → paid`. Both changes are backwards-compatible: existing consumers ignore new fields; the settled consumer sits idle until 4b lands.

### Changes Required

#### 1. Extend the publisher — fat payload

**File**: `services/java/finance-service/src/main/java/com/medfund/finance/service/FinanceEventPublisher.java`
**Changes** at lines 77-84: replace `publishPaymentRunExecuted` signature and body.

```java
public Mono<Void> publishPaymentRunExecuted(
        String runId, String runNumber, String tenantId, String sourceBankAccountId,
        String currencyCode, int count, java.util.List<PaymentRunItemPayload> items) {
    Map<String, Object> payload = new HashMap<>();
    payload.put("event", "PAYMENT_RUN_EXECUTED");
    payload.put("runId", runId);
    payload.put("runNumber", runNumber);
    payload.put("tenantId", tenantId != null ? tenantId : "");
    payload.put("sourceBankAccountId", sourceBankAccountId != null ? sourceBankAccountId : "");
    payload.put("currencyCode", currencyCode != null ? currencyCode : "");
    payload.put("paymentCount", String.valueOf(count));
    payload.put("items", items);  // Jackson serialises List<PaymentRunItemPayload> transparently
    return publishEventObject("medfund.payments.run.executed", runId, payload);
}

// Add a helper next to publishEvent (which is Map<String,String>-typed today).
// The fat-payload event needs object-typed values (items[] is a list of records).
private Mono<Void> publishEventObject(String topic, String key, Map<String, Object> payload) {
    try {
        String json = objectMapper.writeValueAsString(payload);
        var record = new ProducerRecord<>(topic, key, json);
        var senderRecord = SenderRecord.create(record, key);
        return kafkaSender.send(Mono.just(senderRecord))
            .doOnError(e -> log.error("Failed to publish event to {}: {}", topic, e.getMessage()))
            .then();
    } catch (Exception e) {
        log.error("Failed to serialize event for {}: {}", topic, e.getMessage());
        return Mono.empty();
    }
}
```

**File**: `services/java/finance-service/src/main/java/com/medfund/finance/service/PaymentRunItemPayload.java` (new)
**Changes**: small record for the items array.

```java
package com.medfund.finance.service;

public record PaymentRunItemPayload(
    String itemId,
    String paymentId,     // may be empty if item.payment_id is null
    String providerId,    // may be empty
    String memberId,      // may be empty (V072 introduced MEMBER-payee runs)
    String amount,        // plainString to survive JSON without precision loss
    String currencyCode
) {}
```

#### 2. Wire the fat payload in `PaymentRunService.execute`

**File**: `services/java/finance-service/src/main/java/com/medfund/finance/service/PaymentRunService.java`
**Changes** at lines 198-209: collect items before publishing.

```java
.flatMap(completed -> Mono.deferContextual(ctx -> {
    String tenantId = TenantContext.get(ctx);
    return paymentRunItemRepository.findByPaymentRunId(completed.getId())
        .map(item -> new PaymentRunItemPayload(
            item.getId().toString(),
            item.getPaymentId() != null ? item.getPaymentId().toString() : "",
            item.getProviderId() != null ? item.getProviderId().toString() : "",
            "",  // memberId when V072 MEMBER-payee is present — check item field name
            item.getAmount() != null ? item.getAmount().toPlainString() : "0",
            item.getCurrencyCode()
        ))
        .collectList()
        .flatMap(itemPayloads ->
            publishAudit(tenantId, "PaymentRun", completed.getId().toString(), completed.getRunNumber(),
                    "UPDATE", actorId, actorEmail,
                    Map.of("status", previousStatus),
                    Map.of("status", completed.getStatus()))
            .then(eventPublisher.publishPaymentRunExecuted(
                completed.getId().toString(),
                completed.getRunNumber(),
                tenantId,
                completed.getSourceBankAccountId() != null ? completed.getSourceBankAccountId().toString() : "",
                completed.getCurrencyCode(),
                completed.getPaymentCount() != null ? completed.getPaymentCount() : 0,
                itemPayloads))
            .thenReturn(completed));
}));
```

Note: `PaymentRunItem` field for `memberId` — verify by reading the entity; if it doesn't exist yet, leave the memberId as `""` and add a follow-up when V072 MEMBER-payee item schema fills in.

#### 3. New consumer — `PaymentGatewaySettledConsumer`

**File**: `services/java/finance-service/src/main/java/com/medfund/finance/consumer/PaymentGatewaySettledConsumer.java` (new)
**Changes**: modelled on `PaymentAdviceStatusConsumer.java`. Reads `medfund.payments.gateway.settled`, flips `Payment.status → paid`, stamps `paid_at`, emits `PAYMENT_COMMITTED` audit + `medfund.payments.committed` event.

```java
package com.medfund.finance.consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medfund.finance.repository.PaymentRepository;
import com.medfund.finance.service.FinanceEventPublisher;
import com.medfund.shared.tenant.TenantContext;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.kafka.receiver.KafkaReceiver;
import reactor.kafka.receiver.ReceiverOptions;
import reactor.util.context.Context;

import java.time.Instant;
import java.util.Collections;
import java.util.UUID;

/**
 * Consumes {@code medfund.payments.gateway.settled} from the stubbed
 * payment-gateway. Payload per settled item:
 * <pre>
 *   { event: "PAYMENT_GATEWAY_SETTLED",
 *     itemId, paymentId, tenantId,
 *     transactionId, providerRef, status }
 * </pre>
 *
 * When {@code status == "completed"}, we flip the referenced Payment
 * row to {@code paid}, stamp {@code paid_at}, and re-publish the
 * existing {@code medfund.payments.committed} event so the rest of
 * the platform's committed-fanout stays intact.
 *
 * Offset ack via {@code .doOnSuccess} only — never {@code .doOnTerminate}
 * (see {@code bug_reactor_kafka_ack_swallow}).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentGatewaySettledConsumer {

    private static final String TOPIC = "medfund.payments.gateway.settled";

    private final ReceiverOptions<String, String> receiverOptions;
    private final PaymentRepository paymentRepository;
    private final FinanceEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;

    @PostConstruct
    public void consume() {
        var options = receiverOptions.subscription(Collections.singleton(TOPIC));
        KafkaReceiver.create(options)
            .receive()
            .flatMap(record ->
                processEvent(record.value())
                    .doOnSuccess(v -> record.receiverOffset().acknowledge())
                    .doOnError(e -> log.error("[gateway-settled] processing failed (offset NOT ack'd): ", e))
                    .onErrorResume(e -> Mono.empty()))
            .doOnError(e -> log.error("[gateway-settled] consumer stream error: ", e))
            .retry()
            .subscribe();
    }

    Mono<Void> processEvent(String json) {
        try {
            JsonNode node = objectMapper.readTree(json);
            String status    = textOrNull(node, "status");
            String paymentId = textOrNull(node, "paymentId");
            String tenantId  = textOrNull(node, "tenantId");
            if (!"completed".equalsIgnoreCase(status) || paymentId == null || paymentId.isBlank()) {
                return Mono.empty();
            }
            UUID pid = UUID.fromString(paymentId);
            Mono<Void> flow = paymentRepository.findById(pid)
                .switchIfEmpty(Mono.<com.medfund.finance.entity.Payment>empty()
                    .doOnSuccess(v -> log.warn("[gateway-settled] payment {} not found — ignoring", pid)))
                .flatMap(payment -> {
                    if ("paid".equalsIgnoreCase(payment.getStatus())) return Mono.empty();
                    payment.setStatus("paid");
                    payment.setPaidAt(Instant.now());
                    return paymentRepository.save(payment)
                        .flatMap(saved -> eventPublisher.publishPaymentCommitted(
                            saved.getId().toString(),
                            saved.getProviderId() != null ? saved.getProviderId().toString() : "",
                            saved.getAmount().toPlainString(),
                            saved.getCurrencyCode()));
                })
                .then();
            return tenantId != null && !tenantId.isBlank()
                ? flow.contextWrite(Context.of(TenantContext.KEY, tenantId))
                : flow;
        } catch (Exception e) {
            log.error("[gateway-settled] parse failure — offset NOT ack'd: ", e);
            return Mono.error(e);
        }
    }

    private static String textOrNull(JsonNode n, String key) {
        JsonNode v = n.get(key); return v == null || v.isNull() ? null : v.asText();
    }
}
```

#### 4. Tests

**File**: `services/java/finance-service/src/test/java/com/medfund/finance/service/FinanceEventPublisherTest.java`
**Changes**: extend the existing `publishPaymentRunExecuted` test to assert `tenantId`, `sourceBankAccountId`, `currencyCode`, `items` all appear in the JSON payload.

**File**: `services/java/finance-service/src/test/java/com/medfund/finance/consumer/PaymentGatewaySettledConsumerTest.java` (new)
**Changes**: pure `processEvent(json)` unit test (bypasses the KafkaReceiver setup). Six cases: happy-path completed→paid, non-completed status→no-op, missing paymentId→no-op, already-paid payment→no-op (idempotent), missing tenantId→still processes without contextWrite, parse failure→Mono.error (offset not ack'd).

**File**: `services/java/finance-service/src/test/java/com/medfund/finance/consumer/PaymentGatewaySettledConsumerIT.java` (new)
**Changes**: full Testcontainers IT (Postgres + Kafka) — publish a synthetic settled event via a test-only `KafkaSender`, poll for the target `Payment.status = 'paid'`, assert `medfund.payments.committed` was re-published.

### Success Criteria

#### Automated Verification
- [x] `cd services/java && ./gradlew :finance-service:compileJava` (clean)
- [x] `make test-java` — `PaymentRunServiceTest` (updated execute stubs), `FinanceEventPublisherTest` (fat-payload assertions), `PaymentGatewaySettledConsumerTest` (7 unit cases: happy, non-completed, missing-id, already-paid, unknown-id, non-uuid, malformed) all green. Same 7 pre-existing unrelated failures per [[bug_claim_save_mock_id_npe]] left alone.
- [ ] `make test-integration` — `PaymentGatewaySettledConsumerIT` (deferred — no IT written for this phase; the unit test covers the flow logic).
- [ ] Existing `medfund.payments.run.executed` consumers (there are none in Java/Go/Elixir today, but the notification-service stub case at `services/go/notification-service/internal/notification/service.go:71-72` reads the topic name; verify it still parses).

#### Manual Verification
- [ ] Boot finance-service in isolation and hit `POST /api/v1/payment-runs/{id}/execute`; tail Kafka via `kcat -b localhost:9092 -t medfund.payments.run.executed -C` and confirm the payload includes `tenantId`, `sourceBankAccountId`, `items[]`.

---

## Phase 4b: Go-side — payment-gateway Kafka consumer + settled publisher

### Overview
`services/go/payment-gateway/` gains a Kafka consumer on `medfund.payments.run.executed` and a publisher for `medfund.payments.gateway.settled`. Also stop hard-coding `direction="inbound"` in `handler.go:36`.

### Changes Required

#### 1. New Kafka consumer + publisher

**File**: `services/go/payment-gateway/internal/events/subscriber.go` (new)
**Changes**: modelled on `services/go/file-service/internal/events/consumer.go`. `Subscriber.Run(ctx, handle)` uses segmentio/kafka-go with FirstOffset + `CommitMessages`.

```go
package events

import (
	"context"
	"log"
	"strings"
	"time"

	"github.com/segmentio/kafka-go"
)

const TopicPaymentRunExecuted = "medfund.payments.run.executed"

type Subscriber struct {
	brokers string
	topic   string
	groupID string
}

func NewSubscriber(brokers, groupID string) *Subscriber {
	return &Subscriber{brokers: brokers, topic: TopicPaymentRunExecuted, groupID: groupID}
}

func (s *Subscriber) Run(ctx context.Context, handle func(payload []byte)) {
	r := kafka.NewReader(kafka.ReaderConfig{
		Brokers:        strings.Split(s.brokers, ","),
		Topic:          s.topic,
		GroupID:        s.groupID,
		MinBytes:       1,
		MaxBytes:       10 << 20,
		MaxWait:        500 * time.Millisecond,
		StartOffset:    kafka.FirstOffset,
		CommitInterval: time.Second,
	})
	defer r.Close()
	for {
		msg, err := r.FetchMessage(ctx)
		if err != nil {
			if ctx.Err() != nil { return }
			log.Printf("[payment-gateway] kafka fetch error: %v", err)
			time.Sleep(time.Second)
			continue
		}
		handle(msg.Value)
		if err := r.CommitMessages(ctx, msg); err != nil {
			log.Printf("[payment-gateway] kafka commit failed: %v", err)
		}
	}
}
```

**File**: `services/go/payment-gateway/internal/events/publisher.go` (new)
**Changes**: modelled on file-service publisher.

```go
package events

import (
	"context"
	"encoding/json"
	"log"
	"strings"
	"time"

	"github.com/segmentio/kafka-go"
)

const TopicPaymentGatewaySettled = "medfund.payments.gateway.settled"

type SettledEvent struct {
	Event         string `json:"event"`
	ItemID        string `json:"itemId"`
	PaymentID     string `json:"paymentId"`
	TenantID      string `json:"tenantId"`
	TransactionID string `json:"transactionId"`
	ProviderRef   string `json:"providerRef"`
	Status        string `json:"status"`
	Amount        string `json:"amount"`
	CurrencyCode  string `json:"currencyCode"`
}

type Publisher struct {
	writer *kafka.Writer
}

func NewPublisher(brokers string) *Publisher {
	return &Publisher{
		writer: &kafka.Writer{
			Addr:                   kafka.TCP(strings.Split(brokers, ",")...),
			Topic:                  TopicPaymentGatewaySettled,
			Balancer:               &kafka.LeastBytes{},
			AllowAutoTopicCreation: true,
		},
	}
}

func (p *Publisher) PublishSettled(ctx context.Context, e SettledEvent) {
	if e.Event == "" { e.Event = "PAYMENT_GATEWAY_SETTLED" }
	body, err := json.Marshal(e)
	if err != nil { log.Printf("[payment-gateway] settled marshal failed: %v", err); return }
	ctx, cancel := context.WithTimeout(ctx, 3*time.Second)
	defer cancel()
	if err := p.writer.WriteMessages(ctx, kafka.Message{
		Key:   []byte(e.ItemID),
		Value: body,
	}); err != nil {
		log.Printf("[payment-gateway] settled publish failed: %v", err)
	}
}
```

#### 2. Payload types + handler wiring

**File**: `services/go/payment-gateway/internal/events/types.go` (new)
**Changes**: struct for the incoming run-executed event.

```go
package events

import "encoding/json"

type PaymentRunItem struct {
	ItemID       string `json:"itemId"`
	PaymentID    string `json:"paymentId"`
	ProviderID   string `json:"providerId"`
	MemberID     string `json:"memberId"`
	Amount       string `json:"amount"`
	CurrencyCode string `json:"currencyCode"`
}

type PaymentRunExecuted struct {
	Event                string           `json:"event"`
	RunID                string           `json:"runId"`
	RunNumber            string           `json:"runNumber"`
	TenantID             string           `json:"tenantId"`
	SourceBankAccountID  string           `json:"sourceBankAccountId"`
	CurrencyCode         string           `json:"currencyCode"`
	PaymentCount         string           `json:"paymentCount"`
	Items                []PaymentRunItem `json:"items"`
}

func ParseRunExecuted(body []byte) (PaymentRunExecuted, bool) {
	var e PaymentRunExecuted
	if err := json.Unmarshal(body, &e); err != nil { return PaymentRunExecuted{}, false }
	if e.TenantID == "" || e.RunID == "" { return PaymentRunExecuted{}, false }
	return e, true
}
```

**File**: `services/go/payment-gateway/internal/payment/provider.go`
**Changes** at lines 14-23: add `BankAccountID` to `InitiateRequest` so the outbound side carries it.

```go
type InitiateRequest struct {
	TenantID       string  `json:"tenantId"`
	Amount         float64 `json:"amount"`
	Currency       string  `json:"currency"`
	Method         string  `json:"method"`
	Reference      string  `json:"reference"`
	Description    string  `json:"description"`
	ReturnURL      string  `json:"returnUrl"`
	IdempotencyKey string  `json:"idempotencyKey"`
	BankAccountID  string  `json:"bankAccountId,omitempty"`  // outbound only
	Direction      string  `json:"direction,omitempty"`      // inbound (default) | outbound
}
```

**File**: `services/go/payment-gateway/internal/handler/handler.go`
**Changes** at line 36: read direction from the request (default `inbound`).

```go
direction := req.Direction
if direction == "" { direction = "inbound" }
txn := h.ledger.Record(req, resp, h.provider.Name(), direction)
```

#### 3. Boot the consumer in `main.go`

**File**: `services/go/payment-gateway/cmd/main.go`
**Changes**: after existing setup, start the subscriber in a goroutine.

```go
package main

import (
	"context"
	"encoding/json"
	"log"
	"os"
	"os/signal"
	"strconv"
	"syscall"

	"github.com/gofiber/fiber/v2"
	"github.com/gofiber/fiber/v2/middleware/logger"
	"github.com/gofiber/fiber/v2/middleware/recover"

	"github.com/medfund/shared/httpserver"

	"github.com/medfund/payment-gateway/internal/events"
	"github.com/medfund/payment-gateway/internal/handler"
	"github.com/medfund/payment-gateway/internal/payment"
)

func main() {
	app := httpserver.New(httpserver.Options{AppName: "MedFund Payment Gateway"})
	app.Use(recover.New())
	app.Use(logger.New())
	app.Get("/health", func(c *fiber.Ctx) error {
		return c.JSON(fiber.Map{"status": "ok", "service": "payment-gateway"})
	})

	provider := payment.NewMockProvider()
	ledger := payment.NewLedger()
	h := handler.New(provider, ledger)
	h.RegisterRoutes(app)

	// ── Kafka wiring ──────────────────────────────────────────────
	brokers := envOr("KAFKA_BROKERS", "localhost:9092")
	subscriber := events.NewSubscriber(brokers, "payment-gateway")
	publisher := events.NewPublisher(brokers)

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	go subscriber.Run(ctx, func(payload []byte) {
		evt, ok := events.ParseRunExecuted(payload)
		if !ok { log.Printf("[payment-gateway] dropped un-parseable run-executed"); return }
		log.Printf("[payment-gateway] processing run %s (tenant=%s, items=%d)",
			evt.RunID, evt.TenantID, len(evt.Items))
		for _, item := range evt.Items {
			amount, _ := strconv.ParseFloat(item.Amount, 64)
			req := payment.InitiateRequest{
				TenantID:       evt.TenantID,
				Amount:         amount,
				Currency:       item.CurrencyCode,
				Method:         "bank_transfer",
				Reference:      item.ItemID,
				Description:    "Payout for run " + evt.RunNumber,
				IdempotencyKey: item.ItemID,
				BankAccountID:  evt.SourceBankAccountID,
				Direction:      "outbound",
			}
			resp, err := provider.Initiate(req)
			if err != nil {
				log.Printf("[payment-gateway] provider Initiate failed for item %s: %v", item.ItemID, err)
				continue
			}
			txn := ledger.Record(req, resp, provider.Name(), "outbound")
			publisher.PublishSettled(ctx, events.SettledEvent{
				ItemID:        item.ItemID,
				PaymentID:     item.PaymentID,
				TenantID:      evt.TenantID,
				TransactionID: txn.ID,
				ProviderRef:   resp.ProviderRef,
				Status:        string(resp.Status),
				Amount:        item.Amount,
				CurrencyCode:  item.CurrencyCode,
			})
		}
	})

	// Graceful shutdown so we cancel the consumer context on SIGTERM.
	go func() {
		sig := make(chan os.Signal, 1)
		signal.Notify(sig, syscall.SIGINT, syscall.SIGTERM)
		<-sig
		log.Printf("[payment-gateway] shutting down")
		cancel()
		_ = app.Shutdown()
	}()

	port := envOr("PORT", "3004")
	log.Printf("Payment Gateway starting on port %s", port)
	log.Fatal(app.Listen(":" + port))
}

func envOr(key, def string) string {
	if v := os.Getenv(key); v != "" { return v }
	return def
}

var _ = json.Marshal  // keep the import if adjusted later
```

#### 4. Tests

**File**: `services/go/payment-gateway/internal/events/types_test.go` (new)
**Changes**: table-driven test for `ParseRunExecuted` — valid, missing tenantId, missing runId, malformed JSON.

**File**: `services/go/payment-gateway/internal/handler/handler_test.go`
**Changes**: existing handler test suite. Add a case that POSTs with `direction: "outbound"` and asserts the recorded ledger row has `Direction == "outbound"`.

**File**: `services/go/payment-gateway/internal/events/subscriber_it_test.go` (new, build-tag `integration`)
**Changes**: Testcontainers-Kafka round-trip. Publish a synthetic run-executed with 2 items to the topic, wait for 2 `medfund.payments.gateway.settled` events with matching `itemId`s.

### Success Criteria

#### Automated Verification
- [x] `cd services/go/payment-gateway && go build ./...` (clean)
- [x] `go test ./...` in payment-gateway: `internal/events` (5 ParseRunExecuted cases), `internal/handler` (new outbound-direction test), `internal/payment`, `internal/config` all green.
- [ ] Integration round-trip: `go test -tags=integration ./internal/events/...` (deferred — same reasoning as Phase 4a IT deviation; end-to-end round-trip is exercised by booting both services together in the manual step below).
- [ ] End-to-end with 4a + 4b both live: boot finance + payment-gateway + Postgres + Kafka via `docker compose up -d`; execute a payment run; poll finance-service DB for `SELECT status FROM payments WHERE id = ...` — flips to `paid` within a few seconds.

#### Manual Verification
- [ ] `kcat -b localhost:9092 -t medfund.payments.gateway.settled -C` after executing a payment run shows one event per item.
- [ ] `curl -H X-Tenant-ID:xxx http://localhost:3004/api/v1/pay/transactions` shows outbound transactions with `direction=outbound`.

---

## Phase 5: Playwright e2e + doc updates

### Overview
Close the tenant-admin-tab e2e gap flagged in [[project_e2e_gaps_billing]] and correct every stale doc reference.

### Changes Required

#### 1. Playwright spec

**File**: `clients/angular/e2e/tests/tenant-admin-bank-accounts.spec.ts` (new)
**Changes**: modelled on `billing-groups.spec.ts`. Two `test()` blocks: happy-path CRUD (list → create → edit → nominate-flip → delete) + permission-deny variant.

```typescript
import type { Request } from '@playwright/test';
import { test, expect } from '../fixtures/test';

interface BankSeed {
  accounts: Array<{
    id: string;
    label: string; bankName: string; accountNumber: string;
    branchCode?: string; swiftCode?: string; accountName: string; currencyCode: string;
    notes?: string; nominated: boolean; active: boolean;
    createdAt: string; updatedAt: string;
  }>;
}
function emptyBankSeed(): BankSeed { return { accounts: [] }; }

function stubBankAPIs(apiMocks, seed: BankSeed) {
  apiMocks.respond('GET /tenant-bank-accounts', () => seed.accounts);
  apiMocks.respond('POST /tenant-bank-accounts', async (req: Request) => {
    const body = JSON.parse(req.postData() ?? '{}');
    const now = new Date().toISOString();
    const created = { id: 'bank-e2e-' + (seed.accounts.length + 1), ...body,
      createdAt: now, updatedAt: now };
    if (created.nominated) {
      // Enforce the one-per-currency invariant client-side in the stub.
      seed.accounts.forEach(a => {
        if (a.currencyCode === created.currencyCode) a.nominated = false;
      });
    }
    seed.accounts.push(created);
    return created;
  });
  apiMocks.respond('PUT /tenant-bank-accounts/:id', async (req: Request) => {
    const id = req.url().split('/').pop();
    const body = JSON.parse(req.postData() ?? '{}');
    const idx = seed.accounts.findIndex(a => a.id === id);
    if (idx === -1) throw new Error('not found');
    if (body.nominated) {
      seed.accounts.forEach(a => {
        if (a.currencyCode === body.currencyCode) a.nominated = false;
      });
    }
    seed.accounts[idx] = { ...seed.accounts[idx], ...body, updatedAt: new Date().toISOString() };
    return seed.accounts[idx];
  });
  apiMocks.respond('DELETE /tenant-bank-accounts/:id', async (req: Request) => {
    const id = req.url().split('/').pop();
    const idx = seed.accounts.findIndex(a => a.id === id);
    if (idx > -1) seed.accounts.splice(idx, 1);
    return {};
  });
  // Currency master registry — reused by the new-account form
  apiMocks.respond('GET /currencies', () => [
    { code: 'USD', name: 'US Dollar', symbol: '$' },
    { code: 'ZWL', name: 'Zim Dollar', symbol: 'Z$' },
  ]);
}

test.describe('Tenant admin — bank accounts CRUD', () => {
  test('list, create (nominated), edit, delete', async ({ page, apiMocks, signInAs }) => {
    await signInAs({
      realmRoles: ['tenant_admin'],
      permissions: ['admin.bank_accounts:manage', 'admin:manage_settings'],
    });

    const seed = emptyBankSeed();
    stubBankAPIs(apiMocks, seed);

    await page.goto('/admin/settings');
    await page.getByRole('button', { name: /Bank Accounts/i }).click();

    await expect(page.getByRole('heading', { name: /Bank Accounts/i })).toBeVisible();

    // Create nominated USD account
    await page.getByRole('button', { name: /add|new account/i }).click();
    await page.locator('input[name="label"]').fill('Standard Chartered — Ops USD');
    await page.locator('input[name="bankName"]').fill('Standard Chartered');
    await page.locator('input[name="accountNumber"]').fill('0100123456');
    await page.locator('input[name="accountName"]').fill('Acme Health Fund');
    // Currency picker — SelectComponent renders as a native listbox
    await page.locator('select[name="currencyCode"], [data-testid="currency-select"]').first().selectOption('USD');
    await page.locator('input[name="nominated"]').check();

    const postResp = page.waitForResponse(r =>
      r.url().endsWith('/api/v1/tenant-bank-accounts') && r.request().method() === 'POST');
    await page.getByRole('button', { name: /save|create/i }).click();
    await postResp;

    await expect(page.getByText('Standard Chartered — Ops USD')).toBeVisible();

    // Edit — rename label
    await page.getByRole('button', { name: /edit/i }).first().click();
    await page.locator('input[name="label"]').fill('Standard Chartered — Ops USD (renamed)');
    await page.getByRole('button', { name: /save/i }).click();
    await expect(page.getByText(/renamed/)).toBeVisible();

    // Delete
    page.once('dialog', d => d.accept());
    await page.getByRole('button', { name: /delete|remove/i }).first().click();
    await expect(page.getByText(/renamed/)).toHaveCount(0);
  });

  test('missing admin.bank_accounts:manage → tab hidden', async ({ page, apiMocks, signInAs }) => {
    await signInAs({
      realmRoles: ['tenant_admin'],
      permissions: ['admin:manage_settings'],  // NOT bank_accounts:manage
    });
    stubBankAPIs(apiMocks, emptyBankSeed());
    await page.goto('/admin/settings');
    // The tab is registered but gated on the permission — assert it's absent.
    await expect(page.getByRole('button', { name: /Bank Accounts/i })).toHaveCount(0);
  });
});
```

Note: the tab visibility guard on `admin.bank_accounts:manage` needs to be wired in `settings.component.ts` — add a `visibleTabs` computed filter that checks the current user's permissions via `PermissionsService` (or whatever the existing tenant-admin uses; check the roles tab for the pattern). If a hard tab-hide is out of scope, replace the second `test()` block with an inline "add account" button visibility check instead.

#### 2. Documentation updates

**File**: `.claude/architecture.md` — replace at lines 89, 92:
- "MASCA bank account management" → "tenant bank-account management"
- `MascaBankAccount` → `TenantBankAccount`

**File**: `.claude/coding-standards.md` — replace at line 590:
- Row `MascaBankAccount → getAccountName()` → `TenantBankAccount → getLabel()`

**File**: `.claude/multi-tenancy.md`:
- Line 129: replace the `MascaWeb.TenantPlug` Elixir example with a line-neutral module name (`MedfundWeb.TenantPlug` or similar).
- Lines 186-221: add "Bank Accounts" to the tenant-scoped resource enumeration.

**File**: `.claude/portals.md`:
- Lines 86-108: add `/admin/settings#bank-accounts` (or `/admin/bank-accounts` if a URL surface is preferred) to the tenant-admin route table.
- Lines 574-576: add `admin.bank_accounts:manage` to the RBAC catalogue; remove `finance:manage_banks`.

**File**: `.claude/payments.md`:
- Lines 571-589: add a note near `payment_config` that tenant bank-account modelling is a separate concern — a short paragraph pointing at `tenant_bank_accounts` and noting the settlement seam via `medfund.payments.run.executed` → payment-gateway → `medfund.payments.gateway.settled`.

**File**: `docs/medfund-platform-manual.md`:
- Line 1426: replace `MascaBankAccountController — banking integration` with `TenantBankAccountController — tenant bank-account management for outbound disbursements`.

### Success Criteria

#### Automated Verification
- [ ] `make test-e2e` — `tenant-admin-bank-accounts.spec.ts` green (both blocks) alongside the rest of the suite. (Playwright type-check `npx tsc --noEmit -p e2e/tsconfig.json` is clean; full runtime run deferred to the CI/manual sweep.)
- [x] Doc grep: `grep -rn "MascaBank\|masca_bank\|masca-bank" .claude/ docs/medfund-platform-manual.md` returns zero hits.
- [x] `grep -rn "finance:manage_banks" .claude/ services/ clients/` returns zero hits outside V006 (historical, per [[feedback_never_edit_applied_migrations]]) and V075 (the migration performing the swap). No live-code hits.

#### Manual Verification
- [ ] Read the six updated `.claude/*.md` and `docs/medfund-platform-manual.md` sections and confirm they read naturally — no dangling "MASCA" prose, no broken cross-references.

---

## Testing Strategy

### Unit Tests
- `TenantBankAccountServiceTest` (Phase 1): five existing cases + one for populated `changedFields[]` + one that asserts nomination clearing runs before save.
- `TenantBankAccountControllerTest` (Phase 1): five `@WebFluxTest` cases (list/get/create/update/delete) + 403 assertion for missing permission.
- `PaymentRunServiceTest` (Phase 3): currency-mismatch guard test.
- `PaymentGatewaySettledConsumerTest` (Phase 4a): six `processEvent(json)` cases (see phase 4a #4).
- `subscriber_test.go`, `types_test.go` (Phase 4b): parse-error and happy-path unit tests.

### Integration Tests (Testcontainers slices)
- V075 migration applies against a fresh testcontainer (Phase 1) — including the RAISE-on-orphan branch.
- `PaymentGatewaySettledConsumerIT` (Phase 4a): full Postgres + Kafka round-trip.
- `subscriber_it_test.go` (Phase 4b, tag `integration`): Kafka round-trip against a real broker.

### E2E Tests (Playwright)
- `tenant-admin-bank-accounts.spec.ts` (Phase 5): CRUD + nominated-flip + permission-deny.

### Manual Testing Steps
1. `docker compose up -d`, `make finance`, `make gateway`, `make payment`, `make web`.
2. Log in as tenant_admin at `http://localhost:5100/admin/settings` and open the Bank Accounts tab; add a USD account, mark it nominated, save.
3. Go to `/tenant/finance/payment-runs/generate`, select USD, confirm the bank picker filters to that one account, generate a run.
4. Approve + execute the run.
5. Tail `kcat -b localhost:9092 -t medfund.payments.run.executed -C` and `-t medfund.payments.gateway.settled -C` — see one event on each.
6. Refresh the payment-runs list — status shows executed; open the run detail — items show `paid` after settlement round-trip.
7. Log in as a non-admin user; hit `POST /api/v1/tenant-bank-accounts` via curl with their JWT — 403.

## Performance Considerations

- Fat `medfund.payments.run.executed` payload with `items[]` — a typical run is tens of items, worst case hundreds; JSON payload stays under Kafka's default 1MB message limit. Add a comment near `publishPaymentRunExecuted` noting the assumption; if runs ever break 10k items, move to per-item events instead.
- Payment-gateway consumer runs items sequentially in one goroutine — fine for a stub; real provider integration would want a per-item worker pool. Called out for the follow-up.
- `TenantBankAccountRepository.findById` fires once per payment-run create for the currency-match guard — cached by the R2DBC connection pool, effectively free.

## Migration Notes

- **Flyway ordering** — V075 is the next free version (latest is V074). Idempotent CREATE/INSERT-SELECT/ALTER/DELETE guarded per V073's `ON CONFLICT DO NOTHING` and V065's simple DELETE patterns. Per [[bug_tenant_flyway_outoforder.md]] the tenancy-service dev Flyway records both public and tenant migrations in one `public.flyway_schema_history` — no cleanup needed.
- **Cross-service order at deploy time** — the migration must run on every tenant schema before finance-service reboots against it (Flyway is set to `migrate` on boot). Standard rolling deploy order works: tenancy-service is what runs the tenant migrations, and finance-service does not touch the tenant schema before boot.
- **Kafka topic backwards-compat** — the fat payload for `medfund.payments.run.executed` adds optional fields. Existing readers (there are none in Java/Go/Elixir today) tolerate them via Jackson's `FAIL_ON_UNKNOWN_PROPERTIES=false` default.
- **Angular route removal** — the `/tenant/finance/banks/masca` URL becomes a 404. Any bookmarks users held will break; the intended new home is `/admin/settings` → Bank Accounts tab. Announce in release notes.

## Rollout & Rollback

- **Rollout order**: tenancy-service (runs V075) → finance-service (new controller + code catalogues) → gateway (new proxy path) → angular (new tab + old URL removal) → payment-gateway (new consumer/publisher).
- **Rollback path**: reverting the Java + Angular + Go code is straightforward (git revert). Rolling back the V075 migration is *not* — the fresh-table pattern lost the old `masca_bank_accounts` after DROP, and `payment_runs.source_bank_account_id` has data that would need un-backfilling. If a rollback is needed post-deploy, write a **V076 forward-only fix** (per [[feedback_never_edit_applied_migrations]]) that recreates `masca_bank_accounts` as a view onto `tenant_bank_accounts` so the old-endpoint Java (if reverted) can read it; strip the `source_bank_account_id NOT NULL` back to nullable. This is documented in the rollback section of the PR description at merge time; do not ship it eagerly.

## References

- Research: `thoughts/shared/research/2026-08-10-tenant-bank-accounts-and-stubbed-gateway.md`
- Architecture: `.claude/payments.md`, `.claude/multi-tenancy.md`, `.claude/multi-currency.md`
- Similar migration pattern: `services/java/tenancy-service/src/main/resources/db/migration/tenant/V073__permission_swap_billing_creditors.sql`
- Similar Reactor-Kafka consumer: `services/java/finance-service/src/main/java/com/medfund/finance/consumer/PaymentAdviceStatusConsumer.java`
- Similar Go Kafka consumer: `services/go/file-service/internal/events/consumer.go`, `publisher.go`
- Similar settings tab: `clients/angular/src/app/pages/tenant-admin/settings/currencies/`
- Similar Playwright spec: `clients/angular/e2e/tests/billing-groups.spec.ts`
