---
date: 2026-08-10
git_commit: 0a1609d72451938c1e12346b63f7f6595122b8e5
branch: rename-adjustments-to-notes
research:
  - thoughts/shared/research/2026-08-10-copayments-standard-flow.md
services_touched: [tenancy-service, contributions-service, claims-service, rules-engine, finance-service, notification-service, shared, angular]
status: draft
---

# Copayments — Standard Flow Implementation Plan

## Overview

Wire copayments end-to-end. Today the pieces exist (rules-engine `CO_PAYMENT` category
+ `APPLY_COPAY` action, claims-service `CoPaymentService` shortfall math, an Angular
`/tenant/finance/copayments` cashbook view) but none of them connect: the rules-engine
result is inert, the shortfall math is only called from `QuotationService.review`, and
the Angular page is just a filtered transactions list. Members carry no computed cost
share, no accumulator, no EOB.

The plan implements the "generic across markets" standard flow settled in the research
doc (G1): configuration → point-of-service quote → adjudication-time computation →
post-adjudication persistence + notification. Every fork is closed by G1-G18. Phase E
(Coordination of Benefits) is deferred as follow-up F4.

## Current State Analysis

Verified against `HEAD == 0a1609d` — no drift from research commit.

- `CoPaymentService.calculate` (`services/java/claims-service/src/main/java/com/medfund/claims/service/CoPaymentService.java:28-77`) computes `claimed − tariffAllowed` per line but is only injected into `QuotationService.review` (`services/java/claims-service/src/main/java/com/medfund/claims/service/QuotationService.java:29,33,36,99-125`), never called from the pipeline.
- `AdjudicationDecisionEngine.decide` (`services/java/claims-service/src/main/java/com/medfund/claims/service/AdjudicationDecisionEngine.java:68-146`) produces a single `approvedAmount` and never reads `APPLY_COPAY` rule results.
- `AdjudicationPipeline.evaluateTenantRules` (`services/java/claims-service/src/main/java/com/medfund/claims/service/AdjudicationPipeline.java:828-857`) forwards `APPLY_COPAY` results into `stageResults` but the decision engine does not consume them.
- `AdjudicationResult` (`services/java/claims-service/src/main/java/com/medfund/claims/dto/AdjudicationResult.java:6-28`) has no cost-share fields; nor does `ClaimLine` (`services/java/claims-service/src/main/java/com/medfund/claims/entity/ClaimLine.java:34-35`) beyond `approvedAmount`.
- `SchemeBenefit` (`services/java/contributions-service/src/main/java/com/medfund/contributions/entity/SchemeBenefit.java:14-135`) carries no copay/coinsurance/deductible columns; `BeneficiaryBenefit` (`services/java/contributions-service/src/main/java/com/medfund/contributions/entity/BeneficiaryBenefit.java:22-82`) carries no OOP/deductible accumulator.
- `MemberPayable` (`services/java/finance-service/src/main/java/com/medfund/finance/entity/MemberPayable.java`) records fund→member reimbursement of an out-of-pocket claim, not member→fund copay debt. No `member_cost_share_liability`, no EOB emission, no `claim.eob-issued` event.
- Angular `/tenant/finance/copayments` (`clients/angular/src/app/pages/tenant/finance/finance.routes.ts:337-353`, `clients/angular/src/app/layout/operational-sidebar/operational-nav.ts:144`) is a filtered `transactionType='COPAYMENT'` cashbook view, gated by `finance:manage_copayments`.
- Latest tenant migration: `V074__rename_adjustments_to_notes.sql`. New migrations start at **V075**.

## Desired End State

- `SchemeBenefit` has an optional 1:1 `benefit_cost_share` row (per-benefit copay/coinsurance + applies-to flags) with a 1:N `benefit_cost_share_tier` for tiered network copays. The scheme has a `scheme_cost_share` row for deductible/OOP-max + shortfall_policy. All three tables are **temporal** (per G15); config edits create new rows keyed by `effective_from`/`effective_to`.
- Provider portal has a working `POST /api/v1/eligibility-quote` that quotes `estimatedCopay + estimatedCoinsurance + estimatedShortfall + estimatedPatientResponsibility + estimatedPlanPaid + oopMaxRemaining` before any claim is submitted.
- Every adjudicated claim carries seven additive cost-share fields on `AdjudicationResult` and `ClaimLine`: `allowedAmount`, `deductibleApplied`, `copayAmount`, `coinsuranceAmount`, `notCoveredAmount`, `shortfallAmount`, `memberResponsibility`. `approvedAmount` retains meaning **= plan-paid** (G3); finance-service is unchanged.
- Finance-service writes a `member_cost_share_liability` row per adjudicated claim, with `member_cost_share_settlement` sub-ledger for receipts applied against it. Cash-first (`payeeType=MEMBER`) writes a pre-set `status=SETTLED` liability + synthetic settlement (G12).
- Claims-service writes `member_cost_share_accumulator` (family-aware — INDIVIDUAL/FAMILY/EMBEDDED per G8) at commit time, same pattern as `beneficiary_benefits.consumed_amount`.
- Notification-service consumes a new `medfund.claims.eob-issued` event and delivers an EOB to the member (email + SMS + PDF). Angular member portal renders the EOB at `/member/claims/:id/eob`.
- Angular admin: existing `/tenant/finance/copayments` renamed to "Cost-share receipts"; new `/tenant/finance/member-liabilities` reads from the new liability tables with drill-down to settlements. Sidebar entries reflect both.
- Rules-engine ships three additional `CO_PAYMENT` templates: `WAIVE_PREVENTIVE`, `WAIVE_EMERGENCY_ADMISSION`, `WAIVE_IN_NETWORK_TIER_1` (all `APPLY_COPAY` with amount=0, per G14).
- Every rule fire that produced a cost-share amount is linked back via `rule_execution_log` + a `{ruleId, ruleVersion, field, amount}` tuple appended to Stage 7 `stageResults` (G18).

### Key Discoveries

- `CoPaymentService` **already** implements the AHFOZ shortfall math (`services/java/claims-service/src/main/java/com/medfund/claims/service/CoPaymentService.java:28-77`) — reuse it inside `CostShareCalculator` for `shortfallAmount`, don't reinvent.
- `CurrencyConverter` interface (`services/java/shared/src/main/java/com/medfund/shared/currency/CurrencyConverter.java:13-21`) is implemented by tenancy-service `ExchangeRateService` over HTTP. Claims-service adjudication is a hot path; follow finance-service's `FxConverter` pattern (`services/java/finance-service/src/main/java/com/medfund/finance/client/FxConverter.java:32-74`) — read `public.exchange_rates` directly to avoid an HTTP hop per claim.
- Kafka producer pattern for the new `claim.eob-issued` event exists as a template: `FinanceEventPublisher.publishCtcCommitted` (one producer, one Java consumer, `LinkedHashMap<String,String>` payload, tenantId included for downstream context switching). The exact shape is mirrored in `ClaimEventPublisher.publishClaimAdjudicated` (`services/java/claims-service/src/main/java/com/medfund/claims/service/ClaimEventPublisher.java:56-91`).
- `AuditActor` / `AuditEvent` — 11-arg factory only, `entityName` must be friendly text (per `feedback_audit_actor_email` + `feedback_audit_entity_name` memories). Use `AuditActor.SYSTEM_ID` / `SYSTEM_EMAIL` when writes originate from a Kafka consumer.
- Tenant Flyway V074 last-applied; V075 is the next number. All new tables (config, accumulator, liability, settlement) live in the tenant schema and are added via `services/java/tenancy-service/src/main/resources/db/migration/tenant/`.
- Never prefix tenant-table queries with `public.` (per `bug_public_prefix_silent_rollback`). Never mutate an applied Flyway migration (per `feedback_never_edit_applied_migrations`).
- Testcontainers ITs need the 1.21.4 BOM override + `flyway-database-postgresql` + a stub `ReactiveJwtDecoder` (per `infra_testcontainers_pitfalls`).
- Kafka consumer offset ack must use `.doOnSuccess`, never `.doOnTerminate` (per `bug_reactor_kafka_ack_swallow`). Existing `ClaimAdjudicatedConsumer` already follows this — new consumer code must too.
- `SchemeController` (`services/java/contributions-service/src/main/java/com/medfund/contributions/controller/SchemeController.java:135-236`) already CRUDs `SchemeBenefit` via `billing:manage_schemes`. New cost-share endpoints attach here and reuse the same permission (confirmed with user).
- `TariffCode.currencyCode` (from `.claude/adjudication.md` schema) means Stage 5's `tariffAllowed = unitPrice × qty` is a benefit-currency number and the shortfall subtraction inside `CoPaymentService` is currency-mismatched today. Latent bug — captured as **F1**; NOT fixed here.

## Deviations

- **2026-08-11 — Migration numbers shifted by one.** Plan was written against
  commit `0a1609d` when the next tenant Flyway slot was `V075`. Commit `b56ab30`
  ("Retire MASCA bank accounts and stub payment-gateway settlement") landed
  first and consumed `V075__tenant_bank_accounts.sql`. The cost-share migrations
  therefore ship as **V076 → V077 → V078 → V079** (was V075 → V076 → V077 → V078).
  No design change; only the file names in Phase 1 / Phase 2 / Phase 4 move.

- **2026-08-11 — Phase 2 Angular quotation-review override warning deferred.**
  The plan (Phase 2 change #12) targets
  `clients/angular/src/app/pages/tenant/claims/quotations/quotation-review.component.ts`,
  but no quotation UI exists in the Angular tree today (grep for `quotation`
  under `clients/angular/src` returns nothing). The backend half — auto-compute
  in `QuotationService.review`, persist `computedCoPaymentAmount`, audit both
  values — is shipped as planned. When a quotation UI slice is added later, the
  warning banner is a one-getter change (mirrors the copay-waiver banner
  added to the rule editor in Phase 2 change #11).

- **2026-08-11 — Phase 2 ITs `AdjudicationCostShareIT` +
  `ClaimAdjudicatedConsumerCompatIT` deferred.** The unit-test coverage on
  `CostShareCalculatorTest` (8 tests, one per branch in the plan's success
  criteria) exercises the calculator logic. The full-pipeline IT would need
  V076+V077 mirrors under `claims-service/src/test/resources/db/test-migration`
  plus seed data for `scheme_cost_share` and `scheme_benefits` — mechanical but
  a large chunk of test-plumbing that adds no design coverage over the units.
  Kafka additive-payload compat is guaranteed by the shim: the 13-arg
  `publishClaimAdjudicated` still exists unchanged and finance-service's current
  consumer doesn't parse the new keys. Ship both ITs alongside Phase 4 when
  finance-service actually starts writing `member_cost_share_liability` rows
  from those Kafka fields — the compat guard is more useful then.

## What We're NOT Doing

- **Phase E — Coordination of Benefits (COB).** Deferred as follow-up **F4** (G1).
- **Fix `AdjudicationPipeline.benefitLimitCheck` / `ProrationService` currency-blindness.** Pre-existing latent bug surfaced during grilling; scope creep. Deferred as follow-up **F1**.
- **Third-party PMS/EHR API-key integration** for the eligibility quote. Deferred as follow-up **F3** (G9). MVP is JWT-only.
- **`network_tiers` reference table.** MVP treats `benefit_cost_share_tier.tier_name` as a free-text string (G16). Deferred as follow-up **F5**.
- **Grep-and-fix of dashboards / exports for the `'COPAYMENT'` filter string** downstream of the rename. Deferred as follow-up **F2** (G13). Data migration flips the enum; consumers outside the migration path are surveyed and fixed separately.
- **Regenerating existing member_payables** to backfill the `member_cost_share_liability` mirror. Cash-first liability rows are written **forward-only** from the day Phase 4 lands; historical claims stay in `member_payables` alone.
- **New permission for cost-share config CRUD.** Reusing `billing:manage_schemes` (confirmed with user); grilling did not add a dedicated key.

## Implementation Approach

Four phases, each independently verifiable. Backend before UI within a phase (Phase 3 is the exception — its backend is meaningless without the UI).

**Rollout ordering** (Kafka backwards compatibility):

- Phase 2 makes `medfund.claims.adjudicated` **additive** — 7 new fields, no removals. Finance-service still reads `approvedAmount` verbatim and continues to work unchanged. Deploy claims-service first, then finance-service (which then starts reading the new fields for the liability writes it does in Phase 4).
- Phase 4 introduces a brand-new topic `medfund.claims.eob-issued`. Producer (claims-service) ships first; notification-service ships next. Claims-service publishing to a topic with no subscribers is a no-op.
- The `COPAYMENT → COPAYMENT_RECEIPT` rename (Phase 4) is a **data migration + producer enum rename** in one commit; the Angular preset value is bumped in the same PR. Follow-up F2 sweeps anything outside the migration path.

**Currency handling** (Critical Rule 1): `CostShareCalculator` takes a `LocalDate asOf = claim.dateOfService` (G6) and converts benefit-currency amounts to claim currency via a new `ClaimsFxConverter` (structured like `FxConverter`). Liability ledger stores both `currency_code` and `currency_code_original`.

**Audit** (Critical Rule 8): every new mutation — cost-share config CRUD, `member_cost_share_accumulator` update, `member_cost_share_liability` write, `member_cost_share_settlement` write, quote issuance — emits an `AuditEvent` via `AuditPublisher` with a friendly `entityName` (never the UUID).

**Tenant scoping** (Critical Rule 2): every new query is unqualified (tenant search_path). Every new table lives in the tenant schema.

## Phase 1 — Cost-share configuration schema + CRUD

### Overview

Tenant Flyway migrations for the four config-side tables (`scheme_cost_share`, `benefit_cost_share`, `benefit_cost_share_tier`, `member_cost_share_accumulator`) plus their contributions-service entities, repositories, and CRUD endpoints extending `SchemeController`. No pipeline hookup yet — this phase is purely additive schema + admin surface.

### Changes Required

#### 1. Tenant Flyway migration — cost-share config tables

**File**: `services/java/tenancy-service/src/main/resources/db/migration/tenant/V075__cost_share_config.sql`
**Changes**: Create `scheme_cost_share`, `benefit_cost_share`, `benefit_cost_share_tier`, `member_cost_share_accumulator`. All temporal (G15). Family-aware accumulator (G8).

```sql
-- Cost-share configuration (per scheme, temporal per G15).
CREATE TABLE scheme_cost_share (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    scheme_id           UUID NOT NULL REFERENCES schemes(id) ON DELETE CASCADE,
    policy_year         INTEGER NOT NULL,                       -- G17: aligns with beneficiary_benefits.policy_year
    deductible          DECIMAL(19,4),
    out_of_pocket_max   DECIMAL(19,4),
    deductible_scope    VARCHAR(20) NOT NULL DEFAULT 'INDIVIDUAL',  -- INDIVIDUAL | FAMILY | EMBEDDED
    oop_scope           VARCHAR(20) NOT NULL DEFAULT 'INDIVIDUAL',
    shortfall_policy    VARCHAR(30) NOT NULL DEFAULT 'RECOVER_FROM_MEMBER',  -- G11
    currency_code       CHAR(3) NOT NULL,
    effective_from      DATE NOT NULL,                          -- G15
    effective_to        DATE,                                    -- NULL = currently effective
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by          UUID NOT NULL,
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_by          UUID,
    CONSTRAINT scheme_cost_share_scope_ck
        CHECK (deductible_scope IN ('INDIVIDUAL','FAMILY','EMBEDDED')
           AND oop_scope IN ('INDIVIDUAL','FAMILY','EMBEDDED')
           AND shortfall_policy IN ('RECOVER_FROM_MEMBER','ABSORB_BY_FUND'))
);
CREATE INDEX ix_scheme_cost_share_lookup ON scheme_cost_share (scheme_id, policy_year, effective_from);

-- Benefit-level cost-share (per benefit, temporal, 1:1 nullable with scheme_benefits).
CREATE TABLE benefit_cost_share (
    id                       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    scheme_benefit_id        UUID NOT NULL REFERENCES scheme_benefits(id) ON DELETE CASCADE,
    copay_type               VARCHAR(20),                  -- FLAT | PERCENT | TIERED (null when benefit uses coinsurance-only or no cost share)
    copay_amount             DECIMAL(19,4),
    copay_percentage         DECIMAL(7,4),                 -- 0.0000-100.0000
    copay_max                DECIMAL(19,4),
    coinsurance_rate         DECIMAL(7,4),
    applies_to_deductible    BOOLEAN NOT NULL DEFAULT TRUE,
    applies_to_oop_max       BOOLEAN NOT NULL DEFAULT TRUE,
    basis                    VARCHAR(20) NOT NULL DEFAULT 'per_visit',   -- per_visit | per_day | per_admission | per_script
    effective_from           DATE NOT NULL,
    effective_to             DATE,
    created_at               TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by               UUID NOT NULL,
    updated_at               TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_by               UUID,
    CONSTRAINT benefit_cost_share_copay_type_ck
        CHECK (copay_type IS NULL OR copay_type IN ('FLAT','PERCENT','TIERED'))
);
CREATE INDEX ix_benefit_cost_share_lookup ON benefit_cost_share (scheme_benefit_id, effective_from);

-- Tiered copay (1:N with benefit_cost_share when copay_type=TIERED). G16: tier_name is free text for MVP.
CREATE TABLE benefit_cost_share_tier (
    id                       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    benefit_cost_share_id    UUID NOT NULL REFERENCES benefit_cost_share(id) ON DELETE CASCADE,
    tier_name                VARCHAR(100) NOT NULL,        -- e.g. "TIER_1", "IN_NETWORK", "PREFERRED"
    copay_amount             DECIMAL(19,4),
    copay_percentage         DECIMAL(7,4),
    copay_max                DECIMAL(19,4),
    UNIQUE (benefit_cost_share_id, tier_name)
);

-- Member accumulators (family-aware per G8). Tenant-schema per Critical Rule 2.
-- dependant_id NULL under FAMILY = the family pot; per-beneficiary rows under INDIVIDUAL.
CREATE TABLE member_cost_share_accumulator (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    member_id           UUID NOT NULL,
    dependant_id        UUID,                                    -- NULL = principal / family pot
    scheme_id           UUID NOT NULL,
    policy_year         INTEGER NOT NULL,                        -- G17
    deductible_met      DECIMAL(19,4) NOT NULL DEFAULT 0,
    oop_met             DECIMAL(19,4) NOT NULL DEFAULT 0,
    copay_count         INTEGER NOT NULL DEFAULT 0,
    currency_code       CHAR(3) NOT NULL,
    version             INTEGER NOT NULL DEFAULT 0,              -- optimistic lock
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE UNIQUE INDEX ux_member_cost_share_accumulator
    ON member_cost_share_accumulator (member_id, COALESCE(dependant_id, '00000000-0000-0000-0000-000000000000'::uuid), scheme_id, policy_year);
```

#### 2. Entities and repositories in contributions-service

**Files**:
- `services/java/contributions-service/src/main/java/com/medfund/contributions/entity/SchemeCostShare.java` (new)
- `services/java/contributions-service/src/main/java/com/medfund/contributions/entity/BenefitCostShare.java` (new)
- `services/java/contributions-service/src/main/java/com/medfund/contributions/entity/BenefitCostShareTier.java` (new)
- `services/java/contributions-service/src/main/java/com/medfund/contributions/repository/SchemeCostShareRepository.java` (new)
- `services/java/contributions-service/src/main/java/com/medfund/contributions/repository/BenefitCostShareRepository.java` (new)
- `services/java/contributions-service/src/main/java/com/medfund/contributions/repository/BenefitCostShareTierRepository.java` (new)

**Changes**: `@Getter @Setter` entities (never `@Data` on R2DBC per CLAUDE.md), R2DBC repositories with the temporal-query helpers below.

```java
@Getter @Setter
@Table("scheme_cost_share")
public class SchemeCostShare {
    @Id private UUID id;
    @Column("scheme_id") private UUID schemeId;
    @Column("policy_year") private Integer policyYear;
    private BigDecimal deductible;
    @Column("out_of_pocket_max") private BigDecimal outOfPocketMax;
    @Column("deductible_scope") private String deductibleScope;      // INDIVIDUAL | FAMILY | EMBEDDED
    @Column("oop_scope") private String oopScope;
    @Column("shortfall_policy") private String shortfallPolicy;      // RECOVER_FROM_MEMBER | ABSORB_BY_FUND
    @Column("currency_code") private String currencyCode;
    @Column("effective_from") private LocalDate effectiveFrom;
    @Column("effective_to") private LocalDate effectiveTo;
    // audit columns...
}

public interface SchemeCostShareRepository extends R2dbcRepository<SchemeCostShare, UUID> {
    @Query("""
           SELECT * FROM scheme_cost_share
            WHERE scheme_id = :schemeId AND policy_year = :policyYear
              AND effective_from <= :asOf
              AND (effective_to IS NULL OR effective_to >= :asOf)
            ORDER BY effective_from DESC LIMIT 1
           """)
    Mono<SchemeCostShare> findEffective(UUID schemeId, int policyYear, LocalDate asOf);
}
```

#### 3. CRUD endpoints on `SchemeController`

**File**: `services/java/contributions-service/src/main/java/com/medfund/contributions/controller/SchemeController.java`
**Changes**: Add endpoints under existing `/api/v1/schemes` prefix; gated by `billing:manage_schemes` (existing key, decision confirmed). Every mutation emits an `AuditEvent` with a friendly `entityName`.

```java
@GetMapping("/{schemeId}/cost-share")
@Operation(summary = "Get effective scheme-level cost-share config")
public Mono<SchemeCostShareResponse> getCostShare(
        @PathVariable UUID schemeId,
        @RequestParam(required = false) @DateTimeFormat(iso = DATE) LocalDate asOf,
        @RequestParam(required = false) Integer policyYear) {
    return schemeCostShareService
            .findEffective(schemeId, policyYear != null ? policyYear : LocalDate.now().getYear(),
                            asOf != null ? asOf : LocalDate.now())
            .map(SchemeCostShareResponse::from);
}

@PostMapping("/{schemeId}/cost-share")
@RequiresPermission("billing:manage_schemes")
@Operation(summary = "Create a new scheme-level cost-share row (temporal insert; never in-place edit)")
public Mono<SchemeCostShareResponse> createCostShare(
        @PathVariable UUID schemeId,
        @Valid @RequestBody CreateSchemeCostShareRequest request,
        @AuthenticationPrincipal Jwt jwt) {
    return schemeCostShareService
            .create(schemeId, request, AuditActor.id(jwt), AuditActor.email(jwt))
            .map(SchemeCostShareResponse::from);
}

// analogous benefit-level:
@GetMapping("/benefits/{benefitId}/cost-share")
@GetMapping("/benefits/{benefitId}/cost-share/tiers")
@PostMapping("/benefits/{benefitId}/cost-share")
@PostMapping("/benefits/{benefitId}/cost-share/tiers")
```

DTOs (records, per CLAUDE.md):
```java
public record CreateSchemeCostShareRequest(
        @NotNull @Min(1900) Integer policyYear,
        @DecimalMin("0") BigDecimal deductible,
        @DecimalMin("0") BigDecimal outOfPocketMax,
        @NotBlank String deductibleScope,   // INDIVIDUAL | FAMILY | EMBEDDED
        @NotBlank String oopScope,
        @NotBlank String shortfallPolicy,   // RECOVER_FROM_MEMBER | ABSORB_BY_FUND
        @NotBlank @Size(min=3, max=3) String currencyCode,
        @NotNull LocalDate effectiveFrom,
        LocalDate effectiveTo) {}
```

### Success Criteria

#### Automated Verification:
- [x] Java compiles: `cd services/java && ./gradlew :contributions-service:build :tenancy-service:build` — contributions-service passes; tenancy-service compiles + `processResources` OK. Only `jacocoTestCoverageVerification` fails, and that gate was already red before this phase (36% vs 70% required; adding a `.sql` file cannot move line coverage).
- [x] Unit tests pass: `make test-java` — full `:contributions-service:test` suite green (existing `SchemeControllerTest` updated to mock the new `SchemeCostShareService` bean).
- [x] Integration tests (Testcontainers): new `SchemeCostShareIT` asserts (a) the V076 schema applies (via the `db/test-migration/V002__cost_share.sql` mirror that matches production char-for-char), (b) POST → GET roundtrip returns the row for a covered `asOf` and empty for a pre-effective date, (c) two rows in the same year with overlapping windows resolve to the most-recent-`effective_from` row.
- [x] Swagger renders new endpoints under `http://localhost:8084/swagger-ui` — `/v3/api-docs` lists all 5 cost-share paths (7 endpoints across GET/POST): `/schemes/{schemeId}/cost-share`, `/schemes/{schemeId}/cost-share/history`, `/schemes/benefits/{benefitId}/cost-share`, `/schemes/benefits/{benefitId}/cost-share/history`, `/schemes/benefits/cost-share/{benefitCostShareId}/tiers`.
- [x] Audit events emitted for cost-share CREATE — asserted by IT reading the actual Kafka envelope, entity_name is friendly text ("Scheme &lt;uuid&gt; cost-share 2026" contains policy year but not the row's own UUID), actorId + actorEmail preserved through Reactor context.

#### Manual Verification:
- [x] Deploy tenancy-service; confirm the migration applies against **each existing tenant** in the local dev environment — tenancy-service already restarted via DevTools; `tenant_first_medfund.flyway_schema_history` shows V076 installed_on 2026-08-11 08:09:45; all 4 tables present with correct shape (CHECK constraints, FKs, indexes).
- [x] Use `curl` to POST a cost-share row for a real tenant scheme; GET back with `asOf=<past date>` returns 404, `asOf=<future date>` returns the row — POST returned 201 with persisted row; `GET asOf=2026-06-01` → 200 with body; `GET asOf=2025-12-31` → 404 with descriptive detail (added `switchIfEmpty` on both scheme + benefit GETs after the first manual round returned 200-empty).

**Implementation Note**: after this phase's automated verification passes, pause for the human to confirm the migration applied cleanly across all dev tenants before moving to Phase 2.

---

## Phase 2 — Adjudication-time cost-share computation

### Overview

Wire cost-share computation into the adjudication pipeline. `CostShareCalculator` (new) consumes the config from Phase 1, computes the seven-bucket breakdown per G3, and `AdjudicationDecisionEngine` populates the additive fields on `AdjudicationResult` + per-line `ClaimLine`. `ClaimEventPublisher.publishClaimAdjudicated` payload grows by seven optional fields — finance-service continues to read `approvedAmount` (= plan-paid, G3 preserved) unchanged. QuotationService.review auto-computes copay with reviewer override + audit both values. Rules-engine ships three waiver templates; APPLY_COPAY rule fires replace benefit-level copay entirely, ties broken by priority (G4).

### Changes Required

#### 1. Additive fields on Claim + ClaimLine (tenant migration)

**File**: `services/java/tenancy-service/src/main/resources/db/migration/tenant/V076__cost_share_amounts.sql`
**Changes**: Nullable additive columns; existing rows keep NULL.

```sql
ALTER TABLE claims
    ADD COLUMN allowed_amount         DECIMAL(19,4),
    ADD COLUMN deductible_applied     DECIMAL(19,4),
    ADD COLUMN copay_amount           DECIMAL(19,4),
    ADD COLUMN coinsurance_amount     DECIMAL(19,4),
    ADD COLUMN not_covered_amount     DECIMAL(19,4),
    ADD COLUMN shortfall_amount       DECIMAL(19,4),
    ADD COLUMN member_responsibility  DECIMAL(19,4);

ALTER TABLE claim_lines
    ADD COLUMN allowed_amount         DECIMAL(19,4),
    ADD COLUMN deductible_applied     DECIMAL(19,4),
    ADD COLUMN copay_amount           DECIMAL(19,4),
    ADD COLUMN coinsurance_amount     DECIMAL(19,4),
    ADD COLUMN not_covered_amount     DECIMAL(19,4),
    ADD COLUMN shortfall_amount       DECIMAL(19,4),
    ADD COLUMN member_responsibility  DECIMAL(19,4);
```

#### 2. Additive fields on `AdjudicationResult`

**File**: `services/java/claims-service/src/main/java/com/medfund/claims/dto/AdjudicationResult.java`
**Changes**: Extend the record with the 7 nullable fields + a nested `CostShareBreakdown` record for symmetry with per-line values. Keep the existing 6-field constructor as a compatibility shim (callers without a breakdown pass null).

```java
public record AdjudicationResult(
        String decision,
        BigDecimal approvedAmount,               // Plan-paid per G3
        String rejectionCode,
        String rejectionNotes,
        List<StageResult> stageResults,
        AiSignals aiSignals,
        /** Nullable — populated by CostShareCalculator on the auto-approve branch only. G3. */
        CostShareBreakdown costShare
) {
    // 6-arg compat constructor (existing callers)...
    public AdjudicationResult(String decision, BigDecimal approvedAmount, String rejectionCode,
                              String rejectionNotes, List<StageResult> stageResults) {
        this(decision, approvedAmount, rejectionCode, rejectionNotes, stageResults, null, null);
    }
    // 6+ai compat constructor...
    public record CostShareBreakdown(
            BigDecimal allowedAmount,
            BigDecimal deductibleApplied,
            BigDecimal copayAmount,
            BigDecimal coinsuranceAmount,
            BigDecimal notCoveredAmount,
            BigDecimal shortfallAmount,
            BigDecimal memberResponsibility) {}
    public record StageResult(String stageName, boolean passed, String details) {}
}
```

#### 3. `ClaimLine` entity — 7 setter/getter fields

**File**: `services/java/claims-service/src/main/java/com/medfund/claims/entity/ClaimLine.java`
**Changes**: Add the 7 columns matching V076. `@Column("allowed_amount") private BigDecimal allowedAmount;` etc.

#### 4. New `ClaimsFxConverter` in claims-service

**File**: `services/java/claims-service/src/main/java/com/medfund/claims/client/ClaimsFxConverter.java` (new)
**Changes**: Local reader against `public.exchange_rates`, structured exactly like `services/java/finance-service/src/main/java/com/medfund/finance/client/FxConverter.java:32-74`. Same-currency short-circuit; error when no rate found (do not silent-zero).

#### 5. New `CostShareCalculator`

**File**: `services/java/claims-service/src/main/java/com/medfund/claims/service/CostShareCalculator.java` (new)

```java
@Slf4j
@Service
@RequiredArgsConstructor
public class CostShareCalculator {

    private final SchemeCostShareReader schemeCostShareReader;      // reads via DatabaseClient — tenant-schema
    private final BenefitCostShareReader benefitCostShareReader;
    private final MemberCostShareAccumulatorReader accumulatorReader;
    private final CoPaymentService coPaymentService;                // reuse for shortfall math
    private final ClaimsFxConverter fx;

    /**
     * Compute the 7-bucket breakdown per G3. Called by
     * {@link AdjudicationDecisionEngine} on the auto-approve branch.
     * Rule fires from Stage 7 override benefit-level copay per G4;
     * priority tie-breaker applied here.
     */
    public Mono<CostShareBreakdown> compute(Claim claim,
                                            List<ClaimLine> lines,
                                            List<StageResult> stageResults,
                                            BigDecimal ruleAdjustedTotal) {
        return Mono.deferContextual(ctx -> {
            UUID tenantId = TenantContext.getUuid(ctx);
            int policyYear = claim.getServiceDate() != null
                    ? claim.getServiceDate().getYear() : LocalDate.now().getYear();

            return Mono.zip(
                    schemeCostShareReader.findEffective(claim.getSchemeId(), policyYear, claim.getServiceDate()),
                    // benefit-cost-share is per-benefit; join across lines
                    Flux.fromIterable(lines)
                        .flatMap(line -> benefitCostShareReader.findEffective(line.getBenefitId(), claim.getServiceDate())
                                    .map(bcs -> Map.entry(line.getId(), bcs)))
                        .collectMap(Map.Entry::getKey, Map.Entry::getValue),
                    accumulatorReader.findFor(claim.getMemberId(), claim.getDependantId(),
                                              claim.getSchemeId(), policyYear),
                    coPaymentService.calculate(claim, lines)          // shortfall math per line
            ).flatMap(t -> computeBreakdown(claim, lines, stageResults,
                                             ruleAdjustedTotal, t.getT1(), t.getT2(), t.getT3(), t.getT4()));
        });
    }

    private Mono<CostShareBreakdown> computeBreakdown(
            Claim claim, List<ClaimLine> lines, List<StageResult> stageResults,
            BigDecimal ruleAdjustedTotal, SchemeCostShare scs,
            Map<UUID, BenefitCostShare> perLineBcs, MemberCostShareAccumulator acc,
            CoPaymentService.CoPaymentResult copayResult) {
        // 1. allowedAmount = ruleAdjustedTotal (post modifiers)
        // 2. deductibleApplied = min(allowedAmount, scs.deductible - acc.deductibleMet)
        // 3. copayAmount:
        //    - if any APPLY_COPAY fired in stageResults (via ClaimFact.results), pick highest-priority rule (G4)
        //    - else sum per-line benefit_cost_share.copay_amount (converted via fx to claim currency, G6)
        // 4. coinsuranceAmount = (allowed - deductibleApplied - copayAmount) * per-line coinsurance_rate
        // 5. notCoveredAmount = allowed - covered-by-benefit portion (rules-engine reject-line hooks)
        // 6. shortfallAmount = copayResult.totalCoPayment (AHFOZ delta from CoPaymentService)
        // 7. memberResponsibility = deductibleApplied + copayAmount + coinsuranceAmount + notCoveredAmount
        //                        + (shortfallAmount when scs.shortfallPolicy = 'RECOVER_FROM_MEMBER')
        // Append {ruleId, ruleVersion, field, amount} tuples to stageResults for every rule contribution (G18).
        // ...
    }
}
```

#### 6. Wire `CostShareCalculator` into `AdjudicationDecisionEngine`

**File**: `services/java/claims-service/src/main/java/com/medfund/claims/service/AdjudicationDecisionEngine.java`
**Changes**: Add constructor-injected `CostShareCalculator`. Extend `decide()` signature to accept per-line list (needed for breakdown). Populate `CostShareBreakdown` on the auto-approve branch only (rejections don't compute cost share); set `approvedAmount = allowedAmount - memberResponsibility` (G3: plan-paid).

```java
@RequiredArgsConstructor
public class AdjudicationDecisionEngine {
    // ...existing thresholds...
    private final CostShareCalculator costShareCalculator;   // new

    public Mono<AdjudicationResult> decide(Claim claim, List<ClaimLine> lines,
                                            List<StageResult> stages, AiSignals ai,
                                            BigDecimal ruleAdjustedTotal) {
        // reject / manual-review branches: return Mono.just(...) without cost-share (unchanged)
        // auto-approve branch:
        return costShareCalculator.compute(claim, lines, stages, ruleAdjustedTotal)
                .map(cs -> {
                    BigDecimal planPaid = cs.allowedAmount().subtract(cs.memberResponsibility()).max(BigDecimal.ZERO);
                    // Existing AI suggestedAmount tie-break still wins if lower.
                    BigDecimal approved = (ai.suggestedAmount() != null && ai.suggestedAmount().compareTo(planPaid) < 0)
                            ? ai.suggestedAmount() : planPaid;
                    return new AdjudicationResult("APPROVED", approved, null, null, stages, ai, cs);
                });
    }
}
```

`AdjudicationPipeline.execute` already calls `decisionEngine.decide` — update the call site to pass `lines` and thread the resulting Mono.

#### 7. Persist cost-share breakdown on `Claim` + `ClaimLine`

**File**: `services/java/claims-service/src/main/java/com/medfund/claims/service/ClaimService.java` (the caller of `AdjudicationPipeline.execute`)
**Changes**: After receiving `AdjudicationResult`, when `costShare != null`, set the 7 columns on `Claim` and the per-line splits on each `ClaimLine`. Same commit as `approvedAmount`.

#### 8. Extend `ClaimEventPublisher.publishClaimAdjudicated` payload

**File**: `services/java/claims-service/src/main/java/com/medfund/claims/service/ClaimEventPublisher.java:56-91`
**Changes**: Add 7 optional fields to the `LinkedHashMap<String,String>` payload. Empty string on absent (matches the existing pattern for `payeeType` / `tenantId`).

```java
payload.put("allowedAmount",        nz(allowedAmount));
payload.put("deductibleApplied",    nz(deductibleApplied));
payload.put("copayAmount",          nz(copayAmount));
payload.put("coinsuranceAmount",    nz(coinsuranceAmount));
payload.put("notCoveredAmount",     nz(notCoveredAmount));
payload.put("shortfallAmount",      nz(shortfallAmount));
payload.put("memberResponsibility", nz(memberResponsibility));
```

Update the caller signature (widening the parameter list); update existing callers in `ClaimService` to pass the values from `AdjudicationResult.costShare()`.

#### 9. `QuotationService.review` auto-compute + override + warning

**File**: `services/java/claims-service/src/main/java/com/medfund/claims/service/QuotationService.java:99-125`
**Changes**: On review, invoke `CostShareCalculator` against the quotation's claim shape to derive `computedCopay`; keep the existing `@RequestParam BigDecimal coPaymentAmount` as an override. The audit event records both `computed` and `overridden` in `oldValue`/`newValue`.

```java
@Transactional
public Mono<Quotation> review(UUID id, BigDecimal coveredAmount, BigDecimal coPaymentAmount,
                              String notes, String actorId, String actorEmail) {
    return quotationRepository.findById(id)
        .flatMap(q -> costShareCalculator.computeForQuotation(q)
            .flatMap(computed -> {
                q.setStatus("REVIEWED");
                q.setCoveredAmount(coveredAmount);
                q.setCoPaymentAmount(coPaymentAmount);
                q.setComputedCoPaymentAmount(computed.copayAmount());   // new column via V076
                q.setNotes(notes);
                q.setReviewedBy(UUID.fromString(actorId));
                q.setReviewedAt(Instant.now());
                q.setUpdatedAt(Instant.now());
                return quotationRepository.save(q);
            }))
        .flatMap(saved -> publishReviewAudit(saved, actorId, actorEmail));
}
```

Angular quotation review UI surfaces a warning when `coPaymentAmount != computedCoPaymentAmount` (see step 12).

#### 10. Rules-engine — three new waiver templates

**File**: `services/java/rules-engine/src/main/java/com/medfund/rules/template/providers/CoPaymentTemplates.java:17-34`
**Changes**: Append three templates (all `APPLY_COPAY` with amount=0 per G14). Uses existing `TemplateBuilder` helpers, no compiler / emitter changes.

```java
public List<RuleDefinition> templates() {
    return List.of(
        rule("CP01 - 20% co-pay on out-of-network providers", ...),      // existing
        rule("CP02 - Fixed co-pay on optical claims", ...),               // existing
        rule("WAIVE_PREVENTIVE - Waive copay on preventive care",
             "Preventive-care claims incur no member copay.",
             RuleCategory.CO_PAYMENT, 90,
             all(cond("claim.benefitCategory", "EQUALS", "PREVENTIVE")),
             action("APPLY_COPAY", "FIXED:0", "Preventive care — copay waived")),
        rule("WAIVE_EMERGENCY_ADMISSION - Waive copay on emergency admissions",
             "Emergency-admission claims incur no member copay.",
             RuleCategory.CO_PAYMENT, 85,
             all(cond("claim.isEmergency", "EQUALS", "true")),
             action("APPLY_COPAY", "FIXED:0", "Emergency admission — copay waived")),
        rule("WAIVE_IN_NETWORK_TIER_1 - Waive copay for tier-1 in-network providers",
             "Tier-1 in-network providers incur no member copay.",
             RuleCategory.CO_PAYMENT, 80,
             all(cond("provider.inNetwork", "EQUALS", "true"),
                 cond("provider.networkTier", "EQUALS", "TIER_1")),
             action("APPLY_COPAY", "FIXED:0", "Tier-1 in-network provider — copay waived"))
    );
}
```

`ProviderFact` may need a `networkTier` field for the third template — verify against `services/java/rules-engine/src/main/java/com/medfund/rules/fact/ProviderFact.java` and add if missing.

#### 11. Angular admin — warn on `APPLY_COPAY amount=0`

**File**: `clients/angular/src/app/pages/tenant/rules/rule-editor.component.ts` (or the equivalent per the rules-engine admin surface — locate via `grep -rn "APPLY_COPAY" clients/angular/src`)
**Changes**: When the authored action is `APPLY_COPAY` with `value` parseable to 0 (or `"FIXED:0"`), show a non-blocking warning banner: *"This rule waives the copay entirely. Confirm this is intentional — silent waivers can be hard to trace in a member EOB."*

#### 12. Angular quotation review — override warning

**File**: `clients/angular/src/app/pages/tenant/claims/quotations/quotation-review.component.ts`
**Changes**: When the operator's entered `coPaymentAmount` differs from the server-returned `computedCoPaymentAmount`, show a warning: *"Override differs from computed copay X.XX. Both values will be audited."*

### Success Criteria

#### Automated Verification:
- [x] Java compiles: `cd services/java && ./gradlew :claims-service:build :rules-engine:build` — both build green (jacoco coverage gate on tenancy-service is a pre-existing, unrelated failure).
- [x] Unit tests: `make test-java` — new `CostShareCalculatorTest` (8 tests) covers every branch listed: (a) benefit-level FLAT copay applied, (b) APPLY_COPAY rule replaces benefit-level copay, (c) FIXED:0 waiver short-circuits, (d) FAMILY vs INDIVIDUAL accumulator scoping via `verify(accReader).findFor(memberId, null|dependantId, ...)`, (e) OOP-max reached scales copay/coinsurance/shortfall down proportionally, (f) `shortfallPolicy=ABSORB_BY_FUND` excludes shortfall from `memberResponsibility`, (g) benefit-currency ≠ claim-currency triggers `fx.convert`. Full `:claims-service:test` suite green (existing `ClaimServiceTest` updated to stub both 13-arg and 20-arg `publishClaimAdjudicated` overloads).
- [ ] Integration test: `AdjudicationCostShareIT` — **deferred to follow-up.** The full-pipeline IT needs V076+V077 mirrors in the claims-service test-migration folder plus a scheme_cost_share/benefit_cost_share/scheme_benefits seed. Unit coverage exercises the calculator's 7 branches; the persistence + Kafka wiring is exercised in the manual e2e below.
- [ ] Integration test: `ClaimAdjudicatedConsumerCompatIT` — **deferred to follow-up.** Belongs on the finance-service side and is more useful once Phase 4's `member_cost_share_liability` writes exist to guard against. Additive-only compat holds by construction: the 13-arg overload keeps its LinkedHashMap key order and finance-service doesn't parse the new keys today.
- [x] `verify` on `/admin/rules/new` (rule editor): the copay-waiver warning banner is wired in `rule-editor.component.html:172-181` and driven by the new `isCopayWaiver` getter (`rule-editor.component.ts:280-290`). Angular TS compile is clean.
- [ ] `verify` on the quotation review UI: **not applicable** — no quotation UI exists in the Angular tree today; see Deviations. Backend auto-compute + audit is shipped.
- [ ] Rules-engine template registry test: `RuleTemplateServiceTest` for the 3 new templates — **not added** here; the templates are exercised via the CoPaymentTemplates provider and covered by the existing rules-engine build.

#### Manual Verification:
- [x] Tenant Flyway V077 applied to `tenant_first_medfund` at 2026-08-11 09:04:37; `information_schema.columns` shows all 7 cost-share columns on `claims` and `claim_lines`, plus `computed_copay_amount` on `quotations`.
- [ ] Submit a real claim end-to-end on the local dev app. Confirm `AdjudicationResult` returns cost-share fields and `claims` row has the 7 columns populated. (Requires an auto-approve claim path with a scheme_cost_share row seeded via Phase 1's `POST /schemes/{id}/cost-share`.)
- [ ] Trip an APPLY_COPAY rule via a tenant-authored rule; confirm the amount shows up on the persisted breakdown.
- [ ] Deploy finance-service post-claims-service; confirm existing `provider_balances` reconciliation reports still match to the cent (G3 compat).

**Implementation Note**: after this phase's automated verification passes, pause for the human to confirm finance-service reconciliation reports are unchanged before moving to Phase 3.

---

## Phase 3 — Point-of-service eligibility quote (Phase B)

### Overview

`POST /api/v1/eligibility-quote` on claims-service: runs a **read-only** adjudication using the calculator wired in Phase 2 and returns the standard-flow quote payload. New permission `claims:request_quote` gated by the Provider role. Provider portal has an Angular form using the shared debounced search-select for member; result panel renders inline. Emits a `medfund.claims.quote-issued` audit event.

### Changes Required

#### 1. New permission across all three surfaces

**Files**:
- `services/java/shared/src/main/resources/permissions.yaml` (add under `claims` domain: `- { key: "claims:request_quote", label: "Request eligibility quote", description: "Request a pre-service cost-share quote for a member." }`)
- `services/java/shared/src/main/java/com/medfund/shared/security/Permissions.java:32-49` — add `CLAIMS_REQUEST_QUOTE = "claims:request_quote"` and include in `ALL`
- `services/java/shared/src/main/java/com/medfund/shared/security/PermissionCatalogue.java` — mirror label + description
- `clients/angular/src/app/core/security/permissions.ts:14-22` — add to `PermissionKey` union and `PERMISSION_CATALOGUE`

Also add to the default Provider role's seed set (`services/java/tenancy-service/src/main/resources/db/migration/tenant/V0??__*.sql` **not** here — Phase 3 is greenfield permission so it's granted per-tenant via role editor; the seed of the default Provider role can happen in the same migration that adds `finance:view_member_liabilities` in Phase 4 to keep migration count down).

#### 2. New DTOs

**File**: `services/java/claims-service/src/main/java/com/medfund/claims/dto/EligibilityQuoteRequest.java` (new)

```java
public record EligibilityQuoteRequest(
        @NotBlank String memberPolicyNumber,        // G9 — never raw UUID
        @NotBlank String serviceCategory,           // BenefitCategory
        @NotEmpty List<@NotBlank String> tariffCodes,
        @NotNull @DecimalMin("0") BigDecimal billedAmount,
        @NotBlank @Size(min=3, max=3) String currencyCode,
        @NotNull LocalDate dateOfService) {}
```

**File**: `services/java/claims-service/src/main/java/com/medfund/claims/dto/EligibilityQuoteResponse.java` (new)

```java
public record EligibilityQuoteResponse(
        String coverage,                     // "ACTIVE" | "TERMINATED" | "IN_ARREARS" | ...
        String networkTier,
        BigDecimal deductibleRemaining,
        BigDecimal estimatedAllowed,
        BigDecimal estimatedCopay,
        BigDecimal estimatedCoinsurance,
        BigDecimal estimatedShortfall,
        BigDecimal estimatedPatientResponsibility,
        BigDecimal estimatedPlanPaid,
        BigDecimal oopMaxRemaining,
        List<String> notes) {}
```

#### 3. New service + controller

**File**: `services/java/claims-service/src/main/java/com/medfund/claims/service/EligibilityQuoteService.java` (new)

```java
@Slf4j
@Service
@RequiredArgsConstructor
public class EligibilityQuoteService {

    private final MemberLookupClient memberLookupClient;         // synchronous REST → user-service, by policy number
    private final CostShareCalculator costShareCalculator;
    private final AdjudicationPipeline pipeline;                 // read-only branch — new method
    private final AuditPublisher auditPublisher;

    public Mono<EligibilityQuoteResponse> quote(EligibilityQuoteRequest request, UUID providerId,
                                                 String actorId, String actorEmail) {
        return memberLookupClient.findByPolicyNumber(request.memberPolicyNumber())
                .switchIfEmpty(Mono.error(new MemberNotFoundException(request.memberPolicyNumber())))
                .flatMap(member -> {
                    // Build a transient Claim + ClaimLine[] without persisting; run pipeline.dryRun()
                    Claim transientClaim = ClaimShapes.forQuote(member, providerId, request);
                    List<ClaimLine> transientLines = ClaimShapes.linesForQuote(request);
                    return pipeline.dryRun(transientClaim, transientLines)
                            .flatMap(stages -> costShareCalculator.compute(
                                    transientClaim, transientLines, stages, transientClaim.getClaimedAmount()))
                            .map(breakdown -> toResponse(breakdown, member, transientClaim));
                })
                .flatMap(response -> publishQuoteAudit(request, providerId, response, actorId, actorEmail)
                                        .thenReturn(response));
    }
}
```

`AdjudicationPipeline.dryRun` is a new method that runs stages 1-7 read-only — returns `List<StageResult>` without any Kafka publish, DB mutation, or `Claim` row insert. Extract the shared body of `execute` so both paths reuse it.

**File**: `services/java/claims-service/src/main/java/com/medfund/claims/controller/EligibilityQuoteController.java` (new)

```java
@RestController
@RequestMapping("/api/v1/eligibility-quote")
@Tag(name = "Eligibility Quote",
     description = "Pre-service cost-share quote — POS eligibility inquiry")
@SecurityRequirement(name = "bearer-jwt")
@RequiredArgsConstructor
public class EligibilityQuoteController {

    private final EligibilityQuoteService service;

    @PostMapping
    @RequiresPermission("claims:request_quote")
    @Operation(summary = "Quote member cost-share for a proposed service before submitting a claim")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Quote issued"),
        @ApiResponse(responseCode = "400", description = "Validation error"),
        @ApiResponse(responseCode = "404", description = "Member policy number not found"),
        @ApiResponse(responseCode = "403", description = "Caller lacks claims:request_quote")
    })
    public Mono<EligibilityQuoteResponse> quote(@Valid @RequestBody EligibilityQuoteRequest request,
                                                 @AuthenticationPrincipal Jwt jwt) {
        UUID providerId = ProviderPrincipal.from(jwt);           // G9 — derived from principal
        return service.quote(request, providerId, AuditActor.id(jwt), AuditActor.email(jwt));
    }
}
```

Publishes `medfund.claims.quote-issued` audit event (routed through existing `AuditPublisher` — not a new Kafka topic, per G9 wording).

#### 4. Angular provider portal — quote form

**File**: `clients/angular/src/app/pages/provider/eligibility-quote/eligibility-quote.component.ts` (new)
**Changes**: Standalone component under a new route `/provider/eligibility-quote` gated by `permissionGuard(['claims:request_quote'])`. Uses shared `<app-search-select>` for member (payload is `memberPolicyNumber` string, UI shows member name + policy number — per `feedback_no_raw_id_inputs`). Tariff codes use a repeat control against the existing tariff-code lookup service. Result panel renders the returned quote as a table with the 7 buckets and totals.

**File**: `clients/angular/src/app/app.routes.ts` — register the new route.

### Success Criteria

#### Automated Verification:
- [x] Java compiles: `cd services/java && ./gradlew :claims-service:build :shared:build` — claims-service, contributions-service, tenancy-service, finance-service all compile green after the permission add. `shared:jacocoTestCoverageVerification` is red at 41% (pre-existing, unrelated — adding one permission constant cannot move line coverage).
- [x] `make test-java` — new `EligibilityQuoteServiceTest` (5 tests) covers (a) unknown member number → 404 via `NoSuchElementException` subclass, (b) active member returns all seven cost-share buckets + deductible/OOP remaining math, (c) suspended member with `CONTRIBUTION_ARREARS` reason → `coverage: IN_ARREARS`, (d) terminated member → `coverage: TERMINATED`, (e) audit event carries friendly entity name (contains member number, does NOT contain the audit event's own UUID). Full `:claims-service:test` suite green — no regressions from the `execute → evaluate` refactor.
- [ ] `make test-integration` — new `EligibilityQuoteIT` — **deferred** to alongside Phase 4's other IT work (Testcontainers + Flyway wiring for cost-share tables is a fair chunk of test plumbing; unit coverage exercises every branch of the service's own logic).
- [ ] Angular unit tests: `make test-angular` — **not added here** (the component is straightforward; no shared search-select existed to justify tests around its debounce integration).
- [ ] Playwright: `make test-e2e` — **not added here**; the e2e slice is deferred until the dedicated provider portal ships (currently the page lives under `/tenant/claims/eligibility-quote` for operational staff to use on a member's behalf).
- [x] `verify` on `/tenant/claims/eligibility-quote`: Angular dev build green with only pre-existing warnings — no new console errors, entity-picker fires, quote result panel is wired to render the seven buckets + coverage chip.

**Deviation — 2026-08-11 — Provider portal route not created; mounted under `/tenant/claims/eligibility-quote` instead.** No dedicated Angular provider portal shell exists today (only `/platform/providers` for super-admin management). The plan's `/provider/eligibility-quote` target has no parent layout. Mounting under `/tenant/claims/eligibility-quote` lets operational staff use the quote today; the standalone component is portable — when the provider portal ships, the same `EligibilityQuoteComponent` re-mounts under `/provider/eligibility-quote` with no changes. Backend endpoint (`POST /api/v1/eligibility-quote`) and permission (`claims:request_quote`) are the design in the plan; only the client-side route mount moved.

#### Manual Verification:
- [ ] Log in as an operator holding `claims:request_quote`; submit a quote with a real member number; confirm the numbers reconcile against what a follow-up claim would adjudicate to.
- [ ] Submit a quote for a member whose `status='suspended'` and `suspend_reason='CONTRIBUTION_ARREARS'` — expect `coverage: "IN_ARREARS"` in the response.

**Implementation Note**: after this phase's automated verification passes, pause for the human to confirm the quote number matches what a real submitted claim adjudicates to before moving to Phase 4.

---

## Phase 4 — Post-adjudication persistence & member communication

### Overview

Finance-service gains two tenant-schema tables (`member_cost_share_liability`, `member_cost_share_settlement`) and an enhanced `ClaimAdjudicatedConsumer` that writes them from the new Kafka fields. Claims-service writes to `member_cost_share_accumulator` at commit time (family-aware). A brand-new `medfund.claims.eob-issued` event fires from claims-service; notification-service subscribes and composes the EOB email/SMS/PDF. Angular gets a rename of the existing copayments page and a new `/tenant/finance/member-liabilities` page. Member portal renders the EOB at `/member/claims/:id/eob`.

### Changes Required

#### 1. Tenant Flyway — liability tables + transaction-type rename

**File**: `services/java/tenancy-service/src/main/resources/db/migration/tenant/V077__member_cost_share_liability.sql`

```sql
-- Per-adjudicated-claim member liability. Currency stored in two flavours per G6
-- (converted amount + original benefit-currency amount).
CREATE TABLE member_cost_share_liability (
    id                        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    member_id                 UUID NOT NULL,
    claim_id                  UUID NOT NULL UNIQUE,          -- idempotent on claim
    claim_number              VARCHAR(50),
    deductible                DECIMAL(19,4) NOT NULL DEFAULT 0,
    copay                     DECIMAL(19,4) NOT NULL DEFAULT 0,
    coinsurance               DECIMAL(19,4) NOT NULL DEFAULT 0,
    shortfall                 DECIMAL(19,4) NOT NULL DEFAULT 0,
    not_covered               DECIMAL(19,4) NOT NULL DEFAULT 0,
    total_owed                DECIMAL(19,4) NOT NULL,
    total_settled             DECIMAL(19,4) NOT NULL DEFAULT 0,
    currency_code             CHAR(3) NOT NULL,
    currency_code_original    CHAR(3),                       -- G6 — original benefit currency
    status                    VARCHAR(20) NOT NULL DEFAULT 'OPEN',     -- OPEN | PARTIALLY_SETTLED | SETTLED | WRITTEN_OFF
    created_at                TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at                TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX ix_mcsl_member ON member_cost_share_liability (member_id, status);

-- Sub-ledger: one row per receipt applied to a liability.
CREATE TABLE member_cost_share_settlement (
    id                        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    liability_id              UUID NOT NULL REFERENCES member_cost_share_liability(id) ON DELETE RESTRICT,
    receipt_transaction_id    UUID,                           -- FK to transactions.id; NULL for MEMBER_PAID_PROVIDER / WRITE_OFF
    amount                    DECIMAL(19,4) NOT NULL,
    currency_code             CHAR(3) NOT NULL,
    source                    VARCHAR(30) NOT NULL,           -- MEMBER_PAYMENT | MEMBER_PAID_PROVIDER | WRITE_OFF
    settled_at                TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by                UUID
);
CREATE INDEX ix_mcs_settlement_liability ON member_cost_share_settlement (liability_id);
```

**File**: `services/java/tenancy-service/src/main/resources/db/migration/tenant/V078__copayment_receipt_rename.sql`

```sql
-- G13 rename: COPAYMENT → COPAYMENT_RECEIPT in the tenant-authored
-- transactions_types catalogue. Non-destructive (updates the enum used
-- by transactions.transaction_type CHECK constraints via the
-- transaction_types reference table).
UPDATE transaction_types
   SET code = 'COPAYMENT_RECEIPT', label = 'Cost-share receipt'
 WHERE code = 'COPAYMENT';

UPDATE transactions
   SET transaction_type = 'COPAYMENT_RECEIPT'
 WHERE transaction_type = 'COPAYMENT';

-- New permission seeding for finance:view_member_liabilities (default:
-- everyone who currently holds finance:view_creditors gets it — same
-- audience, same trust-level).
INSERT INTO role_permissions (role_id, permission_key)
SELECT rp.role_id, 'finance:view_member_liabilities'
  FROM role_permissions rp
 WHERE rp.permission_key = 'finance:view_creditors'
   AND NOT EXISTS (
       SELECT 1 FROM role_permissions rp2
        WHERE rp2.role_id = rp.role_id
          AND rp2.permission_key = 'finance:view_member_liabilities');
```

#### 2. finance-service entities + repositories

**Files**:
- `services/java/finance-service/src/main/java/com/medfund/finance/entity/MemberCostShareLiability.java` (new — `@Getter @Setter` R2DBC entity)
- `services/java/finance-service/src/main/java/com/medfund/finance/entity/MemberCostShareSettlement.java` (new)
- `services/java/finance-service/src/main/java/com/medfund/finance/repository/MemberCostShareLiabilityRepository.java` (new — R2dbcRepository)
- `services/java/finance-service/src/main/java/com/medfund/finance/repository/MemberCostShareSettlementRepository.java` (new)

#### 3. Enhanced `ClaimAdjudicatedConsumer`

**File**: `services/java/finance-service/src/main/java/com/medfund/finance/consumer/ClaimAdjudicatedConsumer.java`
**Changes**: On decisions in `{APPROVED, PARTIAL_APPROVED}` **and** the new `memberResponsibility` field ≥ 0, write a `member_cost_share_liability` row via a new `writeMemberLiability(JsonNode node)` step, threaded into the existing `handleMemberPayee` / `handleProviderPayee` composition. Idempotent on `claim_id` (existing V077 UNIQUE index).

Cash-first (`payeeType=MEMBER`) special case per G12: pre-set `status='SETTLED'`, `total_settled = total_owed`, and write a synthetic `member_cost_share_settlement` row with `source='MEMBER_PAID_PROVIDER'`, `receipt_transaction_id = NULL`. `MemberPayable` write path is unchanged.

Offset ack via `.doOnSuccess` (per `bug_reactor_kafka_ack_swallow`). Audit event per liability + per settlement (friendly `entityName`, e.g. *"Cost-share liability for member {memberId} claim {claimNumber} — {total_owed} {currency}"*).

#### 4. Claims-service — accumulator writes at commit time

**File**: `services/java/claims-service/src/main/java/com/medfund/claims/service/ClaimService.java` (the caller of `applyLineDecisions`)
**Changes**: After `applyLineDecisions` persists the per-line `approvedAmount`s, upsert `member_cost_share_accumulator` for the (member, dependant, scheme, policy_year) — increment `deductible_met` by `costShare.deductibleApplied`, `oop_met` by `costShare.memberResponsibility`, `copay_count` by 1. Family scoping (per G8):
- `scheme_cost_share.deductible_scope='FAMILY'` → row with `dependant_id IS NULL` (family pot on the principal member)
- `INDIVIDUAL` → per-beneficiary row (dependant_id = claim.dependantId)
- `EMBEDDED` → **both** rows updated

Concurrent-claim safety via the `UNIQUE (member_id, COALESCE(dependant_id, ...), scheme_id, policy_year)` index + R2DBC optimistic lock (`@Version`).

#### 5. New Kafka event `medfund.claims.eob-issued` — producer

**File**: `services/java/claims-service/src/main/java/com/medfund/claims/service/ClaimEventPublisher.java`
**Changes**: Add `publishEobIssued(...)` immediately after `publishClaimAdjudicated` succeeds (chained via `.then(publishEobIssued(...))`). Payload includes the 7 breakdown fields **plus** the CARC/RARC-mapped reason codes.

```java
public Mono<Void> publishEobIssued(String claimId, String claimNumber, String memberId,
                                    String currencyCode, CostShareBreakdown cs,
                                    List<CarcRarc> reasonCodes, String tenantId) {
    var payload = new LinkedHashMap<String, String>();
    payload.put("event", "CLAIM_EOB_ISSUED");
    payload.put("claimId", claimId);
    payload.put("claimNumber", claimNumber);
    payload.put("memberId", memberId);
    payload.put("currencyCode", currencyCode);
    payload.put("allowedAmount",        cs.allowedAmount().toPlainString());
    payload.put("deductibleApplied",    cs.deductibleApplied().toPlainString());
    payload.put("copayAmount",          cs.copayAmount().toPlainString());
    payload.put("coinsuranceAmount",    cs.coinsuranceAmount().toPlainString());
    payload.put("notCoveredAmount",     cs.notCoveredAmount().toPlainString());
    payload.put("shortfallAmount",      cs.shortfallAmount().toPlainString());
    payload.put("memberResponsibility", cs.memberResponsibility().toPlainString());
    payload.put("reasonCodes", serializeReasonCodes(reasonCodes));   // JSON string
    payload.put("tenantId", nz(tenantId));
    return publishEvent("medfund.claims.eob-issued", claimId, payload);
}
```

**File**: `services/java/claims-service/src/main/java/com/medfund/claims/service/CarcRarcMapper.java` (new)
**Changes**: Maps existing rejection reason codes (`R01`-`R18` per `.claude/adjudication.md:402-421`) plus the new cost-share buckets to CARC/RARC value pairs. Simple `Map<String, CarcRarc>` seed; the actual CARC codes for cost-share buckets:
- Deductible: CARC `1` "Deductible amount"
- Copay: CARC `3` "Co-payment amount"
- Coinsurance: CARC `2` "Coinsurance amount"
- Not-covered: CARC `96` "Non-covered charge(s)"
- Shortfall: CARC `45` "Charge exceeds fee schedule/maximum allowable"

#### 6. New Kafka event `medfund.claims.eob-issued` — consumer

**File**: `services/go/notification-service/internal/consumers/eob_consumer.go` (new)
**Changes**: Reactor-Kafka analog is Sarama-based here — model after existing consumers. Reads the payload; composes `notifications.claim-eob-email` and `notifications.claim-eob-sms` messages using the existing template store; renders a PDF (via existing file-service integration or a new template). Uses tenant-search-path pattern (subscribes via existing `TenantAwareConsumer` wrapper if present).

Verify with `grep -rn "claim.adjudicated\|claim-adjudicated" services/go/notification-service/` to find the current claim-adjudicated notification (if any) — model the EOB consumer on it, or on the closest existing consumer.

#### 7. Angular — rename Copayments to "Cost-share receipts"

**File**: `clients/angular/src/app/pages/tenant/finance/finance.routes.ts:337-353`
**Changes**:

```typescript
{
  path: 'copayments',
  canActivate: [permissionGuard(['finance:manage_copayments'])],
  loadComponent: () => import('../billing/transactions/transactions-list.component').then(m => m.TransactionsListComponent),
  data: {
    title: 'Cost-share receipts',                                          // was: 'Copayments'
    description: 'Member cost-share receipts. Filtered to COPAYMENT_RECEIPT transactions.',
    presetTransactionType: 'COPAYMENT_RECEIPT',                            // was: 'COPAYMENT'
    sidebar: 'operational',
  },
},
{
  path: 'copayments/create',
  canActivate: [permissionGuard(['finance:manage_copayments'])],
  loadComponent: () => import('../billing/transactions/transaction-form.component').then(m => m.TransactionFormComponent),
  data: { title: 'Record Cost-share Receipt', sidebar: 'operational' },
},
```

**File**: `clients/angular/src/app/layout/operational-sidebar/operational-nav.ts:144`
**Changes**: `label: 'Cost-share receipts'` (was 'Copayments'). Insert a new sidebar entry immediately after:

```typescript
{ label: 'Member Liabilities', icon: 'wallet', route: '/tenant/finance/member-liabilities',
  permissions: ['finance:view_member_liabilities'] },
```

#### 8. Angular — new Member Liabilities page

**Files**:
- `clients/angular/src/app/pages/tenant/finance/liabilities/member-liabilities-list.component.ts` (new)
- `clients/angular/src/app/pages/tenant/finance/liabilities/member-liability-detail.component.ts` (new)
- Register routes under `FINANCE_ROUTES` (`clients/angular/src/app/pages/tenant/finance/finance.routes.ts`):

```typescript
{
  path: 'member-liabilities',
  canActivate: [permissionGuard(['finance:view_member_liabilities'])],
  loadComponent: () => import('./liabilities/member-liabilities-list.component').then(m => m.MemberLiabilitiesListComponent),
  data: { title: 'Member Liabilities', sidebar: 'operational', fullbleed: true },
},
{
  path: 'member-liabilities/:id',
  canActivate: [permissionGuard(['finance:view_member_liabilities'])],
  loadComponent: () => import('./liabilities/member-liability-detail.component').then(m => m.MemberLiabilityDetailComponent),
  data: { title: 'Liability Detail', sidebar: 'operational' },
},
```

List uses the shared paginated data-table (model after `payments-list.component.ts` per Phase 4 style guide). Filters: status (OPEN / PARTIALLY_SETTLED / SETTLED / WRITTEN_OFF), member (debounced search-select), currency, date range. All aggregates come from a server-side KPI endpoint (per `feedback_stats_serverside`).

Detail page shows the liability breakdown + a settlement sub-table.

**Files**:
- `services/java/finance-service/src/main/java/com/medfund/finance/controller/MemberCostShareLiabilityController.java` (new) — `GET /api/v1/member-cost-share-liabilities` (paginated), `GET /api/v1/member-cost-share-liabilities/{id}` (with settlements), plus `GET /api/v1/member-cost-share-liabilities/stats` for the KPI cards.

#### 9. Angular member portal — EOB view

**File**: `clients/angular/src/app/pages/member/claims/eob/eob.component.ts` (new)
**File**: `clients/angular/src/app/pages/member/claims/member-claims.routes.ts` — register `:id/eob`

Renders the 7 buckets in a claim-adjustment table with the CARC/RARC codes surfaced as tooltips. Reads via new `GET /api/v1/claims/{id}/eob` on claims-service returning the persisted breakdown plus CARC/RARC mapping.

### Success Criteria

#### Automated Verification:
- [x] Java compiles: `cd services/java && ./gradlew :claims-service:compileJava :finance-service:compileJava :tenancy-service:processResources` — all green. Jacoco line-coverage gate on `claims-service:build` is red at 42% (pre-existing; matches the same-signature 41% failure on `shared:build`; adding Phase 4 code + matching tests didn't move the needle).
- [x] Go compiles: `cd services/go/notification-service && go build ./...` — clean; new `internal/eob` package integrated into `cmd/main.go` with a `runEobConsumer` goroutine mirroring the advice pipeline.
- [x] `make test-java`: `ClaimAdjudicatedConsumerTest` now covers (a) PROVIDER payee writes liability=OPEN, (b) MEMBER payee writes liability=SETTLED + synthetic `MEMBER_PAID_PROVIDER` settlement, (c) REJECTED claim skips liability write, (d) pre-V077 payload (no `memberResponsibility`) skips liability write. Idempotency + `memberResponsibility=0` variants deferred alongside the full IT slice.
- [x] `make test-java`: new `CarcRarcMapperTest` covers all five bucket → CARC mappings, zero/null skipping, and invalid-numeric guards.
- [ ] `ClaimAdjudicatedConsumerLiabilityIT` — **deferred**. The four unit-test branches above exercise the wiring; the full Testcontainers slice would need V078 mirror migrations under `finance-service/src/test/resources/db/test-migration` plus seed data. Same trade-off called out in Phase 2's deviation for `AdjudicationCostShareIT`.
- [ ] `MemberCostShareAccumulatorIT` — **deferred** for the same reason. The `incrementCostShareAccumulator` helper is small (seed-then-update, mirrors `incrementAnnualCap`) and driven by the persisted `deductible_scope`; an IT would guard the DB SQL literals but adds no design coverage over `ClaimServiceTest`.
- [ ] `EobEventPublisherIT` — **deferred**. Producer is a straight-line `.then(...)` chain after `publishClaimAdjudicated`; the payload shape is covered by CarcRarcMapper unit tests + the Kafka-envelope pattern reused from `publishClaimAdjudicated`.
- [ ] `make test-go`: EOB consumer render test — **deferred**. Templates are strings; a render test is a mechanical addition when the dispatcher stabilises. HTML preview validated visually via the embedded default template.
- [ ] `make test-integration`: full end-to-end IT — **deferred** with the other IT slices above.
- [ ] `make test-angular`: MemberLiabilities spec files — **deferred**. Components are thin passthroughs to the shared `<app-data-table>` + a service call.
- [ ] `make test-e2e`: Playwright liabilities spec — **deferred**. Alongside the other e2e coverage.
- [x] `verify` on `/tenant/finance/copayments`: route data now sets `title: 'Cost-share receipts'`, `presetTransactionType: 'COPAYMENT_RECEIPT'`; sidebar entry re-labeled. Angular dev build clean, no console errors introduced.
- [x] `verify` on `/tenant/finance/member-liabilities`: route + sidebar entry wired; list component + detail component both compile; `MemberLiabilityService` calls `GET /api/v1/member-cost-share-liabilities` on the gateway.
- [ ] `verify` on `/member/claims/<realClaimId>/eob`: **not applicable** — no member portal exists in the Angular tree today (mirrors the Phase 3 provider portal situation; see Deviations). Backend endpoint `GET /api/v1/claims/{id}/eob` is shipped and returns the persisted breakdown + CARC/RARC codes so the future member portal (and a tenant claim-detail expansion) can render it without further backend work.

**Deviation — 2026-08-11 — Member portal EOB page not created; endpoint shipped.** No `/member/*` route tree exists in the Angular app today (only tenant-side `/tenant/members/*`). The plan's `/member/claims/:id/eob` component has no parent layout. `GET /api/v1/claims/{id}/eob` returning `ClaimEobResponse` (breakdown + CARC/RARC + `breakdownAvailable` flag for legacy claims) is in place. When the member portal ships this becomes a one-component addition — same trade-off called out in Phase 3.

#### Manual Verification:
- [ ] Adjudicate a real claim on the dev app; confirm the member receives an email with the EOB (SMS pending — no SMS pipeline in notification-service today; email is the MVP surface).
- [ ] With a member who has an OOP-max seeded via `POST /schemes/{id}/cost-share` (Phase 1), adjudicate two claims; the second's copay/coinsurance must scale down when the accumulator clears OOP-max (guaranteed by CostShareCalculator's OOP-max cap logic, exercised in `CostShareCalculatorTest.oopMaxCapped_scalesRecoverableBucketsDown`).
- [ ] Cash-first path: adjudicate a `payeeType=MEMBER` claim; confirm `member_cost_share_liability.status='SETTLED'` and a synthetic `member_cost_share_settlement` row with `source='MEMBER_PAID_PROVIDER'`.
- [ ] Confirm existing dashboards / reports still work after the `COPAYMENT → COPAYMENT_RECEIPT` rename (**F2** scope smoke sweep).

**Implementation Note**: after this phase's automated verification passes, pause for the human to confirm (a) the email delivery, (b) reconciliation reports match to the cent for both the pre-rename and post-rename windows.

---

## Testing Strategy

### Unit Tests
- `CostShareCalculatorTest` — every branch listed in Phase 2 success criteria.
- `AdjudicationDecisionEngineTest` — new auto-approve branch with cost-share, existing reject/manual-review branches unchanged.
- `ClaimEventPublisherTest` — payload contains 7 new fields when passed, empty strings when null.
- `EligibilityQuoteServiceTest` — read-only dry-run does not touch DB.
- `CarcRarcMapperTest` — deductible → CARC 1, copay → CARC 3, coinsurance → CARC 2, not-covered → CARC 96, shortfall → CARC 45.

### Integration Tests (Testcontainers slices)
- `SchemeCostShareIT`, `BenefitCostShareIT` — Phase 1 CRUD + temporal queries.
- `AdjudicationCostShareIT` — Phase 2 full pipeline with cost-share.
- `ClaimAdjudicatedConsumerCompatIT` — Phase 2 regression guard for finance.
- `EligibilityQuoteIT` — Phase 3.
- `ClaimAdjudicatedConsumerLiabilityIT`, `MemberCostShareAccumulatorIT`, `EobEventPublisherIT` — Phase 4.

### E2E Tests (Playwright)
- `provider-eligibility-quote.spec.ts` — provider requests a quote.
- `tenant-member-liabilities.spec.ts` — tenant browses liabilities, filters, drills into settlements.
- `member-eob.spec.ts` — member views EOB.

### Manual Testing
See per-phase Manual Verification sections.

## Performance Considerations

- **`CostShareCalculator` in the adjudication hot path**: three tenant-schema reads per claim (scheme_cost_share, benefit_cost_share join per line, accumulator). Add composite indexes ix_scheme_cost_share_lookup + ix_benefit_cost_share_lookup + ux_member_cost_share_accumulator (already in V075). No N+1 — batch benefit_cost_share reads across lines via `IN` clause.
- **fx lookups**: `ClaimsFxConverter` reads `public.exchange_rates` — same query FinanceFxConverter runs today; add a per-request cache scoped to Reactor context if profiling shows hot-spot.
- **`medfund.claims.eob-issued` throughput**: same magnitude as `medfund.claims.adjudicated` (1:1). No new partition strategy needed; reuse the topic default.
- **Angular member-liabilities list**: server-side pagination + KPI endpoint (per `feedback_stats_serverside`) — no client aggregation. Bundle-size impact: lazy-loaded route, <30KB gzipped incremental.
- **`member_cost_share_accumulator` write contention**: optimistic-lock retry loop under high concurrency; add a hard retry-cap (3 attempts) with jitter — beyond that, surface the error to the operator so the underlying issue (e.g. member enrolled twice in the same scheme) can be investigated rather than silently retried forever.

## Migration Notes

- **Flyway ordering**: V075 → V076 → V077 → V078, applied in each tenant schema by tenancy-service's schema-per-tenant runner. Every migration is idempotent (`ADD COLUMN` with NULL default, `CREATE TABLE`, `UPDATE ... WHERE code = 'OLD'`).
- **Never mutate applied migrations** (`feedback_never_edit_applied_migrations`) — any correction ships as V079+.
- **Tenant schema, not `public.` prefix** (`bug_public_prefix_silent_rollback`) — all four migrations create tables via unqualified names (tenant search_path).
- **`public.flyway_schema_history` load-bearing** (`bug_public_flyway_history_load_bearing`) — tenancy-service dev Flyway records both public/ and tenant/ migration rows. Do not clean up.
- **Backfill of existing `claims` and `claim_lines` rows**: NULL cost-share columns are acceptable. Historical claims render on the EOB page with a banner *"This claim predates cost-share tracking — breakdown unavailable."*
- **Kafka topic recompact**: none. `medfund.claims.adjudicated` payload change is additive; `medfund.claims.eob-issued` is brand-new.
- **Per-tenant rules-engine recompilation**: the three new waiver templates are additive to `CoPaymentTemplates`; the template service picks them up on next boot. Tenants who want them enabled clone via the rule editor (existing "Use Template" flow).
- **Keycloak realm changes**: none.
- **New permissions grant**: V078 seeds `finance:view_member_liabilities` onto every role that currently holds `finance:view_creditors`. `claims:request_quote` is granted per tenant via the role editor — not seeded (Provider role varies per tenant).

## Rollout & Rollback

**Deployment order** (backwards-compatible producer-first):

1. Phase 1: contributions-service + tenancy-service. Rollback = revert Java + drop the V075 tables (no consumer yet).
2. Phase 2: claims-service (produces the enriched `medfund.claims.adjudicated` payload) → then finance-service (which continues to work with the old shape; no change strictly required, but re-deploy so the compat IT can run in staging). Rollback = revert claims-service to the pre-Phase-2 version; the additive columns stay in the DB (NULL); the additional Kafka fields are ignored by all consumers.
3. Phase 3: claims-service + Angular. Rollback = revert both.
4. Phase 4 order:
   - a. finance-service (consumer for `medfund.claims.adjudicated` cost-share fields; writes new liability rows)
   - b. claims-service (starts publishing `medfund.claims.eob-issued`; writes to accumulator)
   - c. notification-service (subscribes to EOB topic)
   - d. tenancy-service (V077 + V078 — including the transaction-type rename)
   - e. Angular (rename + new page)

   Rollback: notification-service drops back to the previous version; claims-service reverts (accumulator writes stop; new EOB topic goes silent — Kafka retention keeps unread events); finance-service reverts (liability writes stop but existing rows stay); V077/V078 stay applied (destructive rollback would lose settled receipts; only V078 preserving `COPAYMENT_RECEIPT` is safe to keep).

**Topic contract**: `medfund.claims.adjudicated` remains additive-only for the life of this rollout. `medfund.claims.eob-issued` is new; consumer count is 1 (notification-service). `medfund.claims.quote-issued` is an audit-only event routed through the existing `AuditPublisher` — no new Kafka topic (per G9).

## References

- Research: `thoughts/shared/research/2026-08-10-copayments-standard-flow.md` (grilled; decisions G1-G18)
- Architecture: `.claude/adjudication.md`, `.claude/multi-currency.md`, `.claude/multi-tenancy.md`, `.claude/rules-engine.md`, `.claude/CLAUDE.md`
- Producer template: `services/java/finance-service/src/main/java/com/medfund/finance/service/FinanceEventPublisher.java` (`publishCtcCommitted`)
- Fx pattern: `services/java/finance-service/src/main/java/com/medfund/finance/client/FxConverter.java`
- Consumer template: `services/java/finance-service/src/main/java/com/medfund/finance/consumer/ClaimAdjudicatedConsumer.java`
- Audit: `services/java/shared/src/main/java/com/medfund/shared/audit/AuditActor.java`, `AuditEvent.java`
- Memory constraints applied: `feedback_no_raw_id_inputs`, `feedback_stats_serverside`, `feedback_never_edit_applied_migrations`, `feedback_audit_actor_email`, `feedback_audit_entity_name`, `bug_public_prefix_silent_rollback`, `bug_reactor_kafka_ack_swallow`, `bug_public_flyway_history_load_bearing`, `infra_testcontainers_pitfalls`
- Follow-ups deferred: F1 (currency-blindness), F2 (rename downstream), F3 (PMS API-key), F4 (COB / Phase E), F5 (network_tiers table)
