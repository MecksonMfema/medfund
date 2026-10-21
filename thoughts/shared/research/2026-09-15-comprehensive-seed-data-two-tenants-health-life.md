---
date: 2026-09-15T09:15:00+02:00
researcher: Methuseli
git_commit: 2a80248e3c0de32c18c8d90b2c72c607aafffcae
branch: rename-adjustments-to-notes
repository: medfund
topic: "Comprehensive environment-agnostic seed data for two tenants (HEALTH + LIFE), 20k+ members each, 2 years of realistic operations"
tags: [research, seed-data, demo, multi-tenant, health, life, claims, contributions, payments, notes, testing]
status: complete
last_updated: 2026-09-15
last_updated_by: Methuseli
---

# Research: Comprehensive Seed Data for HEALTH + LIFE Tenants (20k members, 2 years of ops)

**Date**: 2026-09-15T09:15:00+02:00 · **Researcher**: Methuseli · **Commit**: 2a80248e · **Branch**: rename-adjustments-to-notes

## Research Question

We need to test the application end-to-end. Build a seed for **two tenants** (one HEALTH/medical, one LIFE), each with **20k+ members** in a realistic **individual + group** mix, and populate every downstream artefact needed to exercise the full platform: staff members, roles, rules, claims, contributions, transactions, payments, notes, scheme changes, group changes, and any other business operation. The dataset must span **at least 24 months** of realistic activity and must be **reproducible on any environment** (fresh Docker up, CI, staging, a dev laptop).

## Summary

The platform has strong per-tenant provisioning primitives (`TenantService.create()` at `services/java/tenancy-service/src/main/java/com/medfund/tenancy/service/TenantService.java:115` handles schema, Flyway, Keycloak realm, currency config, and 6 default scheduled jobs) plus rich reference-data seeds via Flyway (AHFOZ tariffs V056, PMB rules V172, IFRS17 defaults V165, tax config V174). It has **no bulk demo-data seeder**: everything past reference data is expected to arrive through service APIs. `bootstrap-keycloak.sh` seeds only 8 platform staff users in a single `medfund-platform` realm; there is no per-tenant realm bulk provisioning and no member Keycloak user population.

The recommended shape is a **new bounded-context module** (Java Spring Boot, sits alongside existing services) that:

1. Boots two tenants via existing `TenantService` HTTP APIs (so Flyway, Keycloak realms, default roles, currency, and scheduled jobs get provisioned identically to production).
2. Seeds per-tenant reference config (schemes, benefits, age groups, waiting-period rules, providers, tariff schedule links, rule bundles) via existing service APIs.
3. Bulk-loads members / dependants / groups via `MemberService.enroll()` and `DependantService.create()` (keeps enrollment invariants: 1st-of-month snap, MEMBER_ENROLLED Kafka event, `BeneficiaryBenefitSeeder` cascade).
4. Runs a **deterministic 24-month time-travel loop** driving service methods month-by-month: contribution generation, invoice commit, transactions (individual + group payer), claim submission + adjudication, payment runs (PROVIDER + MEMBER), scheme changes, group changes, terminations, reactivations, deaths, notes. All timestamps controlled via a seedable `Clock` bean so runs are reproducible.
5. Is invoked via `make seed-demo` (fresh) or `make seed-demo-reset` (drop tenant schemas first). No prod-visible endpoints. The whole thing is gated behind an env flag (`SEED_MODE_ENABLED=true`) so it cannot run in prod.

This is the only shape that respects the 9 Critical Rules in `.claude/CLAUDE.md`: every mutation goes through the service layer, so audit events fire, `TenantContext` is honored, MEMBER_ENROLLED / SCHEME_CHANGED / PAYMENT_RUN_EXECUTED Kafka events publish downstream, and per-tenant rules-engine `ReleaseId` invalidation happens correctly. Anything bypassing service methods (direct SQL INSERTs into `claims`, `payments`, etc.) silently violates rules 2, 5, 6, 8, and 9 and will not exercise the flows we want to test.

**Row-count budget for a 2-year, 20k-member HEALTH tenant** (order of magnitude, per pass 4): 1.8M claims, 2.2M claim lines, 540k pre-auths, 600k contributions, 240k invoices, 2M transactions, 100k payments across ~600 payment runs, 50k notes, and ~7M+ audit events. LIFE is thinner (fewer claims, denser billing). Seeding this in real-time via service methods is slow (est. days if serialized); the plan needs to think about **partitioned parallel loading per group / per scheme cohort**, with the AI adjudication stage bypassed or stubbed by default (real Claude API calls in the seed would be prohibitive).

## Findings

### 1. What seed infrastructure already exists

**Environment bootstrap** (Docker + Keycloak + MinIO):
- `docker-compose.yml` — postgres 17, kafka, keycloak, minio; started via `make infra`.
- `infra/docker/init-db.sql:1` — creates `keycloak` + `audit` schemas and `medfund_audit` DB on postgres startup.
- `scripts/init-minio-buckets.sh:26` — creates `medfund-report-payloads` bucket via `minio-init` docker service.
- `scripts/bootstrap-keycloak.sh:1-336` — creates `medfund-platform` realm, `medfund-web` OIDC client, 11 realm roles (super_admin, tenant_admin, claims_clerk, claims_assessor, finance_officer, contributions_officer, provider, member, group_liaison, siu_officer, siu_supervisor), tenant_id protocol mapper, and 8 test users (superadmin/admin123 + 7 test123 users). Idempotent on re-run. Invoked via `make keycloak-setup` (`Makefile:42`).

**Reference-data Flyway seeds** (public schema, run once at tenancy-service boot):
- `services/java/tenancy-service/src/main/resources/db/migration/public/V111__currencies.sql` — active currencies.
- `services/java/tenancy-service/src/main/resources/db/migration/public/V165__seed_ifrs17_model_default_rules.sql` — IFRS-17 defaults per tenant.
- `services/java/tenancy-service/src/main/resources/db/migration/public/V172__seed_pmb_classification_default_rules.sql:29` — 40+ PMB default rules, `CROSS JOIN` into every tenant, idempotent.
- `services/java/tenancy-service/src/main/resources/db/migration/public/V174__seed_tenant_tax_config_defaults.sql` — tax defaults.
- `services/java/tenancy-service/src/main/resources/db/migration/public/V183__platform_settings.sql` — platform settings row.

**Reference-data Flyway seeds** (per-tenant schema, run on provisioning + boot backfill):
- `services/java/tenancy-service/src/main/resources/db/migration/tenant/V041__transaction_types_and_reason.sql` — PAYMENT/REFUND/WRITE_OFF/etc. vocabulary.
- `services/java/tenancy-service/src/main/resources/db/migration/tenant/V048__member_operations.sql` — member operation reason codes.
- `services/java/tenancy-service/src/main/resources/db/migration/tenant/V056__seed_ahfoz_march_tariffs.sql:15` — 4,860 AHFOZ tariff codes at fixed UUID `a8f0c1e2-3b4d-4a56-8789-abc123def456`, effective 2026-03-01, USD.
- `services/java/tenancy-service/src/main/resources/db/migration/tenant/V060__beneficiary_benefits.sql` — backfill BeneficiaryBenefit rows for existing members + active scheme benefits.
- `services/java/tenancy-service/src/main/resources/db/migration/tenant/V260__ifrs17_opening_balance_seed.sql` — IFRS-17 opening balances.
- Total: 136 tenant migrations, 62 public migrations. 27 of the tenant ones insert data.

**Reactive Java seeders**:
- `services/java/tenancy-service/src/main/java/com/medfund/tenancy/config/PlatformFlagSeeder.java:24` — `CommandLineRunner` that ensures a `public.platform_feature_flags` row exists per `PlatformFlag` enum value on tenancy-service boot. Pattern: `Flux.fromArray(...).flatMap(findById.switchIfEmpty(save))`.
- `services/java/contributions-service/src/main/java/com/medfund/contributions/service/BeneficiaryBenefitSeeder.java:52` — event-driven seeder that populates per-beneficiary ledger rows when a Member or Dependant is enrolled (consumes MEMBER_ENROLLED / DEPENDANT_ENROLLED Kafka events). Handles age-gating, usage-mode filtering, ONE_TIME_PER_BENEFICIARY lifetime tracking, and annual cap rows.
- `services/java/tenancy-service/src/main/java/com/medfund/tenancy/service/TenantMigrationRunner.java:32` — `CommandLineRunner` that backfills pending tenant migrations on boot across every schema listed in `public.tenants`.

**What is NOT there**:
- No `make seed-demo` / `make demo-data` Makefile target.
- No bulk member / group / claim / payment loader anywhere.
- No `datafaker` / `mockneat` / `javafaker` on the classpath.
- No Angular or Flutter mock demo data (only unit-test mocks under `clients/angular/src/app/_test-utils/mock-*.ts`).
- No historical Kafka replay tool.
- `bootstrap-keycloak.sh` only creates the platform realm and 8 platform staff users. Per-tenant realms are created by `TenantService.create()` but the script does not seed users into them.

### 2. Tenant provisioning + auth model

**Tenant creation flow** (`services/java/tenancy-service/src/main/java/com/medfund/tenancy/service/TenantService.java:114-170`):
1. INSERT into `public.tenants` (id, name, slug, schemaName, insuranceLines[], status, currencyCode, etc.).
2. `SchemaProvisioningService.provisionSchema(schemaName)` at `services/java/tenancy-service/src/main/java/com/medfund/tenancy/service/SchemaProvisioningService.java:36` runs `CREATE SCHEMA`, then Flyway `db/migration/tenant/*` against the new schema (`repair()` + `migrate()`, idempotent).
3. Keycloak realm created (`medfund-{slug}`).
4. Currency config seeded per tenant.
5. Default 6 scheduled jobs seeded (BILLING_CYCLE, OVERDUE_CHECK, PAYMENT_RUN, AGE_PROCESSING, PRE_AUTH_EXPIRY, TARIFF_ACTIVATION).
6. `TENANT_PROVISIONED` Kafka event published; consumed by `RoleService.seedDefaultRoles` which seeds the 7 default roles into the new tenant schema.

**Staff / User / Role model**:
- `services/java/user-service/src/main/java/com/medfund/user/entity/StaffUser.java` — platform-scoped in `public.staff_users` (V100), with optional `tenant_id` for per-tenant assignment.
- `services/java/user-service/src/main/java/com/medfund/user/entity/Role.java` + `RolePermission.java` + `UserRole.java` — per-tenant `roles` / `role_permissions` / `user_roles` tables.
- 7 default roles seeded per tenant: `tenant_admin` (full), `operations`, `claims_officer`, `finance_officer`, `provider`, `member`, `group_liaison`. tenant_admin gets every permission via V006 SQL migration; the other 6 are created by `RoleService`.
- Permission format: `{portal}:{action}` (e.g., `claims.queue:approve`, `finance.payments:read`).
- Keycloak stores identity + auth only; permissions stored in DB and resolved at token-refresh time. `services/java/tenancy-service/src/main/java/com/medfund/tenancy/config/SecurityConfig.java` (in git status) wires the JWT decoder.

**Portal / realm binding** (from `.claude/portals.md`):
| Portal | Realm | Role(s) |
|---|---|---|
| Super Admin | medfund-platform | super_admin |
| Tenant Admin | medfund-{slug} | tenant_admin |
| Operations | medfund-{slug} | claims_officer, finance_officer, contributions_officer, siu_officer, siu_supervisor |
| Provider | medfund-{slug} | provider, provider_admin (multi-tenant-capable) |
| Member | medfund-{slug} | member (+ group_liaison for group-level ops) |

**Minimum entities for a functional tenant with logged-in-able users for every portal**: 1 tenant row, 1 per-tenant Keycloak realm, 7 default roles, ~60 role_permissions, ~12 staff_users (2 per role), the Member + Provider users, and their Keycloak counterparts with role bindings.

### 3. Member / Group / Scheme model (HEALTH + LIFE)

**Person-centric core** (lives under `services/java/user-service/src/main/java/com/medfund/user/entity/`):
- `Member.java:14` — fields: memberNumber (auto-generated per tenant `member_number_scheme` config), firstName/lastName, dateOfBirth, gender, nationalId, email, phone, address, `status` (active/suspended/terminated/deactivated), enrollmentDate, terminationDate, deathDate, causeOfDeath, **groupId (nullable FK to `groups`)**, schemeId (FK), ageGroupId, billingAgeGroupId, billingOverrideAmount, billingOverrideReason, billingOverrideEffectiveFrom, scheduledStatus + scheduledStatusEffectiveFrom (V042 future-dated flips), suspendReason (V043).
  - **Individual vs. group**: inferred from `groupId IS NULL`. Memory: [[feedback_grouped_members_cannot_pay]] — `TransactionService.record` 422s a supplied `memberId` whose `group_id IS NOT NULL`.
- `Dependant.java:13` — memberId (FK CASCADE), memberNumber, firstName/lastName, dob, gender, relationship (SPOUSE/CHILD/PARENT/etc.), status, enrollmentDate (V047, 1st-of-month only), deactivationEffectiveDate. No separate Beneficiary table; LIFE beneficiaries are dependants filtered by relationship.
- `Group.java:13` — name, registrationNumber (auto-generated per V125 tenant config), address, email, status, `liaisonKind` (MEMBER|STAFF) + `liaisonUserId` (V023 CHECK constraint).
- `GroupLiaison.java`, `MemberDependantSwap.java`, `PendingGroupChange.java:26` (state machine: PENDING → APPROVED → APPLIED, or REJECTED/CANCELLED; back-dated requests bypass state machine and go straight to APPLIED, arrears/rebate posted asynchronously by GroupChangedConsumer).

**Scheme + benefit model** (contributions-service):
- `services/java/contributions-service/src/main/java/com/medfund/contributions/entity/Scheme.java:14` — name, description, `schemeType` (medical_aid, life, funeral, etc.), `insuranceLine` (HEALTH/LIFE/...), status, effectiveDate, endDate, currencyCode, `minAge`/`maxAge` (V050 age gates enforced at enrollment), `tracksMemberBalances` (V061 boolean, false for indemnity products), `annualMemberCap` (V062), `defaultPortfolioId` (V102 IFRS17).
- `SchemeBenefit.java:13` — schemeId, name, benefitType (INPATIENT, DENTAL, PREVENTIVE, DEATH_BENEFIT, etc.), annualLimit, dailyLimit, eventLimit, waitingPeriodDays, minAge/maxAge (V051 benefit-level gates), cashClaimAllowed, usageMode (RUNNING_BALANCE/ONE_TIME/PER_EVENT/NO_TRACKING).
- `AgeGroup.java` — per-scheme age bands (0-17, 18-34, ..., 65+) with contributionAmount + currencyCode.
- `SchemeCostShare.java:27` — **temporal** table (every edit appends a row keyed by `effective_from`); deductible, oopMax, deductibleScope (INDIVIDUAL/FAMILY/EMBEDDED), shortfallPolicy.
- `WaitingPeriodRule.java` — scheme-level (conditionType: PRE_EXISTING_CONDITION, NEW_MEMBER, etc.).
- `SchemeChange.java:13` — state machine PENDING → APPROVED → APPLIED (or REJECTED/CANCELLED). `changeKind` (UPGRADE/DOWNGRADE/CURRENCY_CHANGE/CROSS_GRADE) drives downstream ledger posting via `SchemeChangedConsumer` (UPGRADE → SCHEME_UPGRADE_ARREARS; DOWNGRADE → SCHEME_DOWNGRADE_REBATE; CURRENCY_CHANGE → FX delta; CROSS_GRADE → no auto post).
- `SchemeChangeWaitingPeriodRule.java` — waiting periods for changed benefits.

**LIFE line specifics**:
- `services/java/user-service/src/main/java/com/medfund/user/entity/LifePolicy.java:14` — schemeId, groupId (nullable), insuredMemberId, policyNumber, sumAssured, `occupationHazardClass` (PROFESSIONAL/CLERICAL/HAZARDOUS), termMonths, status, coverageStart/End, `renewedFromPolicyId`, portfolioId + cohortId (IFRS17), billingOverride*.
- Sibling scaffolds: `FuneralPolicy.java`, `DisabilityPolicy.java`, `TravelPolicy.java`. Asset lines: `Vehicle.java`, `Property.java`.
- LIFE claims: **same `Claim` entity** with `insuranceLine='LIFE'`. LIFE-specific claim fields (cause_of_death, beneficiary_payout, sum_assured_payout) are scaffolded but not fully wired per `.claude/CLAUDE.md:29`. Adjudication pipeline applies but Stage 6 differs: no ICD-procedure mapping, no frequency limits. Payment routing: `payee_type='MEMBER'` (beneficiary payout).

**Notes** (finance-side, renamed from adjustments per current branch `rename-adjustments-to-notes`):
- `services/java/finance-service/src/main/java/com/medfund/finance/entity/Note.java:29-96` — table `notes` (per-tenant); fields: noteNumber, providerId OR memberId (either), direction (DEBIT/CREDIT), noteType (TAX_WITHHELD, WRITE_OFF, GOODWILL, ENDORSEMENT_PREMIUM, PREMIUM_REFUND, PROVIDER_OVERPAYMENT_RECOVERY, MEMO), type (ORIGINAL/REVERSAL), reversesNoteId, amount, currencyCode, reason, status (pending → approved → applied → reversed).
- Not polymorphic; attaches to provider or member only. Claim rejection notes are captured on the Claim row itself, not as separate Note records.

### 4. Claims / Contributions / Payments / Transactions

**Claim** (`services/java/claims-service/src/main/java/com/medfund/claims/entity/Claim.java`):
- Statuses: DRAFT → SUBMITTED → VERIFIED → IN_ADJUDICATION → ADJUDICATED → COMMITTED → PAID. Alternate: REJECTED, PENDING_INFO, PARTIAL_APPROVED, APPEALED.
- Adjudication pipeline (6 stages, `.claude/adjudication.md:26-239`): Eligibility, Waiting Period, Benefit Limit, Pre-Authorization, Tariff & Pricing, Clinical & AI. Every stage is a rules-engine call except Stage 6 which fans out to `services/python/ai-service`.
- `ClaimLine.java` — per-line tariff_code, benefit_id, modifiers, cost-share breakdown.
- `PreAuthorization.java` — separate table; statuses PENDING/APPROVED/REJECTED/EXPIRED.
- Committed claims flow into finance-service as `provider_balances` credits; batched into a PROVIDER `PaymentRun`.

**Contribution / Invoice / Transaction**:
- `services/java/contributions-service/src/main/java/com/medfund/contributions/entity/Contribution.java` — memberId, groupId, schemeId, amount, period_start, period_end, status (pending → paid). **V034 UNIQUE(member_id, scheme_id, period_start)** enforces one contribution per member per month per scheme (memory: [[feedback_one_contribution_per_month]]).
- `Invoice.java` — aggregates contribution rows per group/member/scheme/period; committed invoices snapshot opening_balance, closing_balance, payments_in_window, adjustments_in_window; `prior_invoice_id` chains them.
- `Transaction.java` — group_id XOR member_id (V039 CHECK); transaction_type = PAYMENT / ADJUSTMENT / REVERSAL; status is `completed` (immutable once posted). Individual payers only permitted when member.group_id IS NULL (memory: [[feedback_grouped_members_cannot_pay]]).
- Regeneration: `RevokeBillingRequest` DTO, next-month-only (memory: [[feedback_one_contribution_per_month]]).

**Payment / PaymentRun / PaymentRunItem** (finance-service):
- `PaymentRun.java` lifecycle: draft → generated → approved → executed → settled.
- `PaymentRunGenerator.java` `populate()` routes on `payeeType` (PROVIDER/MEMBER/PRODUCER); reads provider_balances or member_payables; creates Payment + PaymentRunItem rows.
- Provider payouts: monthly (or weekly) batches keyed off outstanding provider balances; carriedIn/carriedOut bridges runs (V067).
- Member payouts: CTC opt-in (memory: [[project_ctc_is_opt_in]]) vs. cash refund; separate `CtcPayment.java` scaffolded.
- V072 trigger: all PaymentRunItems in a run must share the run's payeeType (never mixed).

**Notes** (repeated for full flow context): see Section 3.

**Audit** (`services/java/shared/src/main/java/com/medfund/shared/audit/AuditEvent.java:7-43`):
- Immutable record; factory `AuditEvent.create(...)` requires entityName + actorEmail (memory: [[feedback_audit_actor_email]], [[feedback_audit_entity_name]]).
- `AuditActor.systemActor()` for background jobs / Kafka consumers.
- Kafka topic → `services/go/audit-service` persists to `audit_events` in tenant schema.

**Security events**:
- `services/java/keycloak-event-listener` — pushes LOGIN / LOGOUT / LOGIN_ERROR / MFA / password-change / role-change / impersonation events.
- `services/go/audit-service/internal/audit/event.go:21-31` — `SecurityEvent` struct with UserID, ActorEmail, IPAddress, UserAgent.

### 5. Rules engine and per-line rule bundles

**Storage & compilation**:
- `services/java/rules-engine/src/main/java/com/medfund/rules/model/RuleDefinition.java:8-105` — JSON stored in per-tenant `tenant_rules.definition` column; compiled to Drools DRL by `DrlCompiler`.
- `services/java/rules-engine/src/main/java/com/medfund/rules/service/TenantRuleLoader.java:33-105` — lazy-loads enabled rules, compiles to a **per-tenant `ReleaseId`** KieBase (memory: [[bug_rules_engine_tenant_isolation]]). Invalidation via Kafka `medfund.tenants.config-changed`.
- `RuleCategory` enum (24 categories at `services/java/rules-engine/src/main/java/com/medfund/rules/model/RuleCategory.java:12-161`): ELIGIBILITY, WAITING_PERIOD, BENEFIT_LIMIT, BENEFIT_PRORATION, CO_PAYMENT, PRE_AUTHORIZATION, TARIFF_PRICING, MODIFIER_ADJUSTMENT, CLINICAL_VALIDATION, CONTRIBUTION_BILLING, CONTRIBUTION_PRICING, MEMBER_LIFECYCLE, AGE_GROUP, UNDERWRITING, PROVIDER_PAYMENT, RECONCILIATION, REINSURANCE, COMMISSION, PREMIUM_EARNING, ACTUARIAL, IFRS17, REGULATORY, PMB, FRAUD_TRIAGE.

**15 shipped templates** (Spring `TemplateProvider` beans):
- `WaitingPeriodTemplates` (4): General 90-day, Maternity 300-day, Dental 180-day, Senior 730-day.
- `EligibilityTemplates` (5): Active member, Arrears ≤3mo, Claim ≤90 days, Health age cutoff, Senior cash block.
- `BenefitLimitTemplates` (1): Exhausted limit rejection.
- Plus 21 more providers for Co-Pay, Tariff, Pre-Auth, Clinical, Billing, Provider Payment.

**Per-line seed strategy**:
- **HEALTH tenant** should ship with ~15 active rules: General waiting 90, Maternity 300, Dental prosthetics 180, Senior 730; 5 Eligibility templates; Exhausted-limit rejection; Pre-auth for elective surgery / imaging / prosthetics; Tariff capping to AHFOZ; Multi-procedure discount / after-hours loading; Clinical ICD validation; Co-pay 20% on non-formulary.
- **LIFE tenant** ~12 active rules: MEMBER_LIFECYCLE (auto-terminate after N months arrears), UNDERWRITING (occupation hazard loadings), AGE_GROUP (age-band premium), CONTRIBUTION_BILLING (arrears suspension), Extended senior waiting 180-730 days, waiver-of-premium.
- **Proration** (`ProrationStrategy` enum, 7 strategies at `services/java/rules-engine/src/main/java/com/medfund/rules/model/ProrationStrategy.java:21-121`): pick DELTA_CREDIT for HEALTH, NONE or CALENDAR for LIFE.

**No auto-seeding of tenant rules on provisioning today.** A tenant admin clones from the templates gallery post-creation. The seed script must POST rule bundles per tenant via the rules-engine API after tenant creation.

### 6. Reference data already in the repo

- **AHFOZ tariffs (4,860 codes)**: `services/java/tenancy-service/src/main/resources/db/migration/tenant/V056__seed_ahfoz_march_tariffs.sql:15`, schedule UUID `a8f0c1e2-3b4d-4a56-8789-abc123def456`, effective 2026-03-01, USD. Auto-seeded on every new tenant schema.
- **PMB classification rules (40+)**: V172 in public. Auto-seeded per tenant via CROSS JOIN.
- **IFRS17 defaults**: V165 (rules), V260 (opening balances).
- **Tax config**: V174.
- **Transaction type vocabulary**: V041.
- **Member operation reasons**: V048.

**What is NOT bundled**:
- ICD-10 code registry (only the join table `diagnosis_procedure_mappings`). Needs external CSV load.
- Provider AHFOZ registry beyond seed placeholders.
- Historical exchange rates (only `ExchangeRate` entity + table exists; no historical rows).

## Cross-service flow (single "seed a claim + payment" trace)

1. Seed calls **user-service** `POST /api/v1/members` → `MemberService.enroll()` writes Member (1st-of-month snap), publishes `MEMBER_ENROLLED` on `medfund.users.member-enrolled`.
2. **contributions-service** `MemberEnrolledConsumer` receives → `BeneficiaryBenefitSeeder.seed(member, dependants)` writes `beneficiary_benefits` rows per active scheme benefit (age-gated). If enrollment is mid-billed-period, posts LATE_ENROLMENT_CHARGE.
3. Seed calls **contributions-service** `POST /api/v1/billing/generate` for period 2024-01 → generates ~20k Contribution rows (V034 unique), emits per-row CREATE audit events on `medfund.audit`.
4. Seed calls **contributions-service** `POST /api/v1/invoices/commit` → 20k Invoice rows snapshotted, `prior_invoice_id` chain.
5. Seed calls **contributions-service** `POST /api/v1/transactions` (PAYMENT type) once per group + once per ungrouped member → Transaction rows written, Contribution.status flips to paid.
6. Seed calls **claims-service** `POST /api/v1/claims` per synthetic visit → Claim.status=SUBMITTED, ClaimLine rows written.
7. Seed calls **claims-service** `POST /api/v1/claims/{id}/adjudicate` → routes to rules-engine (Stages 1-5) + AI service (Stage 6). Stage 6 is a Python HTTP call, expensive: **stub in seed mode** via config flag.
8. Adjudicated claim → COMMITTED → provider_balance credit in finance-service.
9. Seed calls **finance-service** `POST /api/v1/payment-runs` (payeeType=PROVIDER, period) → PaymentRun.status=DRAFT, then `POST /populate` → Payment + PaymentRunItem rows per provider.
10. Seed calls **finance-service** `POST /payment-runs/{id}/approve` then `/execute` → publishes `payment-execution` event, **payment-gateway** stub confirms settlement.
11. Every step above emits AuditEvent to Kafka; **audit-service** persists into per-tenant `audit_events`.

Every hop uses production code paths, so seed correctness = production correctness.

## Architecture doc vs. code

- `.claude/portals.md` documents 5 portals + roles. **Aligned** with the roles in `bootstrap-keycloak.sh:163` and RoleService default seeding.
- `.claude/multi-tenancy.md` describes schema-per-tenant with per-tenant Keycloak realms. **Aligned** with `TenantService.create()` and `SchemaProvisioningService`.
- `.claude/adjudication.md` describes the 6-stage pipeline. **Aligned** with `ClaimStatus` transitions and `RuleCategory` coverage.
- `.claude/rules-engine.md:552` describes tenant config-changed invalidation. **Aligned** with `TenantRuleLoader` implementation.
- **Drift**: `.claude/CLAUDE.md:29` says LIFE is "Scaffolded" with `LifePolicy` entity + enrollment + billing hooks. Verified: `LifePolicy.java` exists but LIFE-specific **claim payout fields** (cause_of_death, beneficiary_payout, sum_assured_payout) are NOT on the `Claim` entity. Seed will need to represent LIFE claims as-is (same Claim entity, `insuranceLine='LIFE'`, payee routed to MEMBER) and mark this gap for follow-up.
- **Drift**: Makefile header at `Makefile:9` says Angular runs on port 4200. Memory [[reference_angular_port]] and `bootstrap-keycloak.sh:25` both use 5100. The Makefile header comment is stale.

## Recommended seeder architecture

**Shape**: A dedicated Spring Boot module `services/java/demo-seeder-service` (own `bootRun`, no HTTP endpoints exposed by default), gated on `SEED_MODE_ENABLED=true` env var (fails fast otherwise, cannot run in prod).

**Structure**:
```
services/java/demo-seeder-service/
  src/main/java/com/medfund/seeder/
    SeederApplication.java              # CommandLineRunner, orchestrator
    config/
      SeederProperties.java             # tenant count, member counts, month span, RNG seed
      HttpClientConfig.java             # WebClients pointing at tenancy/user/claims/contributions/finance
    orchestrator/
      SeedOrchestrator.java             # phase runner
      TimelineDriver.java               # month-by-month clock advance
    phase/
      Phase1_Tenants.java               # POST tenancy-service /tenants (x2)
      Phase2_Users.java                 # per-tenant realm users via Keycloak Admin API + staff_users
      Phase3_Reference.java             # schemes, benefits, age groups, providers, tariff assignment, rules
      Phase4_Members.java               # bulk enroll members + dependants
      Phase5_Timeline.java              # 24-month replay
    generator/
      FakerFactory.java                 # datafaker-based person/company data
      MemberFactory.java                # produces Member payloads with realistic age dist, group mix
      ClaimFactory.java                 # HEALTH/LIFE-aware claim payloads
      TransactionFactory.java           # PAYMENT/ADJUSTMENT/REVERSAL
    stub/
      AiAdjudicationStub.java           # replaces Python AI call with deterministic scoring
```

**Invocation** (add to `Makefile`):
```
seed-demo:
    SEED_MODE_ENABLED=true cd services/java && ./gradlew :demo-seeder-service:bootRun

seed-demo-reset:
    bash scripts/reset-tenant-schemas.sh && $(MAKE) seed-demo
```

**Determinism**:
- Single `long` RNG seed drives all random choices (member DOBs, group sizes, claim frequency).
- All timestamps derived from a `Clock` bean; `TimelineDriver` advances one month at a time. Every timestamp is monotonic per timeline, not `Instant.now()`.
- MemberNumber / GroupNumber / NoteNumber allocators are called via existing services (respect per-tenant number scheme).

**Idempotency + reset**:
- `scripts/reset-tenant-schemas.sh` — DROPs the two demo tenant schemas + deletes their `public.tenants` rows + deletes Keycloak realms (idempotent).
- Seeder short-circuits Phase 1 if the two tenant slugs already exist (allows "top up more months" runs).

**Performance strategy for 20k × 24 months × 2 tenants**:
- Concurrency: partition members into ~20 group cohorts + a pool of ungrouped individuals. Run each cohort in a parallel Flux with backpressure. Per pass 4 estimate, 1.8M claims per HEALTH tenant is the long pole; batch-submit claims 100 at a time.
- AI Stage 6: stub by default (deterministic pseudo-score). Real Claude invocation gated behind `SEED_AI_CALLS=true` for a small subset (~100 claims).
- Kafka: leave it running; consumers (audit, notification, beneficiary-benefit-seeder) will keep up if we backpressure the producer.

## Row-count budget per tenant (24 months, 20k members)

| Entity | HEALTH | LIFE | Notes |
|---|---|---|---|
| Tenants | 1 | 1 | via TenantService |
| Keycloak realm users | ~25 staff + 20k members | ~25 staff + 20k members | member Keycloak users optional; can defer |
| Schemes | 3-5 | 3 | Basic/Standard/Premium; Term10/Term20/Endowment |
| SchemeBenefits | 60-90 | 9-15 | per scheme |
| AgeGroups | 15-25 | 15 | per scheme |
| Providers | ~5,000 | ~50 | HEALTH-dominant; LIFE mostly cash payouts |
| Groups | 5-15 | 5-15 | ~60% of members in a group |
| Members | 20,000 | 15,000 | LIFE is opt-in for ~75% of HEALTH members |
| Dependants | ~30,000 | subset | avg 1.5 per member |
| LifePolicy | 0 | 15,000 | HEALTH tenant has no LifePolicy rows |
| Contributions | 600,000 | 375,000 | monthly × months × subset |
| Invoices | 240,000 | 150,000 | ~1 per member per month |
| Transactions | 2,000,000 | 400,000 | payments + adjustments + reversals |
| Claims | 1,800,000 | 30,000 | HEALTH-dominant |
| ClaimLines | 2,200,000 | 30,000 | ~1.2 lines/claim |
| PreAuthorizations | 540,000 | 500 | ~30% of HEALTH claims |
| PaymentRuns | 576 | 288 | weekly PROVIDER + monthly MEMBER |
| Payments | 100,000 | 15,000 | |
| PaymentRunItems | 100,000 | 15,000 | |
| Notes | 50,000 | 5,000 | write-offs, tax, refunds |
| SchemeChanges | ~2,000 | ~200 | ~1% of members per year |
| PendingGroupChanges | ~1,000 | ~100 | ~0.5% of members per year |
| Member terminations | ~2,000 | ~500 | ~5% cumulative over 24 months |
| Member deaths (V140) | ~200 | ~200 | ~0.5% annual mortality |
| AuditEvents | 7-24M | 2-5M | every mutation |

## Over-time event replay (24 months, deterministic)

Per month tick:

1. **Enrollments**: draw N new individuals + M new group members per group; call `POST /members` (dob-realistic, national-id-realistic). Cascade dependant enrollments.
2. **Scheme changes** (~0.5%/month of active members): draw member, choose changeKind (UPGRADE 40% / DOWNGRADE 20% / CROSS_GRADE 30% / CURRENCY_CHANGE 10%), submit → auto-approve within same tick if random draw < 80%, else leave PENDING to seed queue.
3. **Group changes** (~0.2%/month): draw member with group, transfer to another group. 10% back-dated (bypass approval, arrears/rebate posted).
4. **Contribution generation**: `POST /billing/generate?period=YYYY-MM` for both tenants. Emit LATE_ENROLMENT_CHARGE for members enrolled mid-period.
5. **Invoice commit**: `POST /invoices/commit?period=YYYY-MM`.
6. **Payments in**: for each group, single PAYMENT transaction covering the invoice; for each ungrouped active member, PAYMENT transaction (with 5% partial-pay and 3% missed-pay noise). Missed payments accrue arrears; if 3+ months arrears, MEMBER_LIFECYCLE rule fires suspend.
7. **Reactivations**: ~30% of suspended members catch up next month; single PAYMENT with arrears.
8. **Claims submission**: per active HEALTH member draw claim count (Poisson λ=4/year for young, λ=12/year for senior); per active LIFE member draw claim count (~0.1/year, mostly zero); post `POST /claims` with realistic tariff codes drawn from AHFOZ, ICD codes drawn from a small curated list.
9. **Adjudication tick**: batch-adjudicate SUBMITTED claims. Distribution: 70% APPROVED, 15% PARTIAL_APPROVED, 10% REJECTED, 5% PENDING_INFO. AI stage stubbed.
10. **Pre-auth request tick**: for ~30% of pre-auth-required claims, generate PreAuthorization ahead of the claim; some APPROVED, some REJECTED, ~5% EXPIRED.
11. **Provider payment run** (weekly): `POST /payment-runs` (PROVIDER), populate + approve + execute. Stub payment-gateway to auto-confirm.
12. **Member payment run** (monthly): CTC opt-in members' payouts (10-15% of members opt in).
13. **Notes** (~50/tenant/month for HEALTH, 10 for LIFE): WRITE_OFF, TAX_WITHHELD, GOODWILL, PROVIDER_OVERPAYMENT_RECOVERY; some REVERSAL entries.
14. **Terminations / deaths**: monthly draws; write terminationDate or deathDate + causeOfDeath (V140).
15. **Notes on members** (memo type): random operational notes on ~5% of active members per month.

## Architecture Insights (against the 9 Critical Rules)

1. **Rule 1 (no cross-currency arithmetic)**: seed picks one currency per tenant (USD for HEALTH, USD for LIFE). Any CURRENCY_CHANGE scheme change goes through the existing `CURRENCY_CHANGE_ADJUSTMENT` code path which uses ExchangeRate lookups. Seed loads historical exchange rates first.
2. **Rule 2 (tenant-scoped queries)**: honored automatically because seed uses HTTP APIs, not direct SQL.
3. **Rule 3 (AI decisions auditable)**: seed stubs AI Stage 6 by default. If SEED_AI_CALLS=true, the stub records model_version="stub-1.0" and confidence=1.0 so the audit trail exists.
4. **Rule 4 (data protection)**: seed generates synthetic PII (datafaker); no real PII touched. Members do not get real Keycloak logins by default (deferrable).
5. **Rule 5 (per-tenant rules)**: seed calls the rules-engine API to seed HEALTH vs LIFE bundles. Per-tenant `ReleaseId` isolation preserved.
6. **Rule 6 (Kafka events, no direct inter-service calls)**: honored because seed uses HTTP APIs of each service; consumers (BeneficiaryBenefitSeeder, SchemeChangedConsumer, GroupChangedConsumer, MemberEnrolledConsumer, audit-service) receive real events.
7. **Rule 7 (Swagger)**: not seed's concern; existing services already Swagger-document their endpoints.
8. **Rule 8 (audit-log every mutation)**: honored because every mutation goes through service methods that already emit AuditEvent. Seed sets `X-Actor-Id` header + `Authorization` Bearer token per phase so AuditActor extracts the right actor.
9. **Rule 9 (security events)**: seed does not simulate security events by default. Optionally: seed can generate ~10 LOGIN/LOGOUT/LOGIN_ERROR events per active staff per month via keycloak-event-listener replay.

## Code References

- `services/java/tenancy-service/src/main/java/com/medfund/tenancy/service/TenantService.java:115` — Tenant.create() orchestration.
- `services/java/tenancy-service/src/main/java/com/medfund/tenancy/service/SchemaProvisioningService.java:36` — schema + Flyway.
- `services/java/tenancy-service/src/main/java/com/medfund/tenancy/service/TenantMigrationRunner.java:32` — boot-time migration backfill.
- `services/java/tenancy-service/src/main/java/com/medfund/tenancy/config/PlatformFlagSeeder.java:24` — reactive seeder pattern.
- `services/java/contributions-service/src/main/java/com/medfund/contributions/service/BeneficiaryBenefitSeeder.java:52` — event-driven downstream seed on enrollment.
- `services/java/user-service/src/main/java/com/medfund/user/entity/Member.java:14` — Member entity.
- `services/java/user-service/src/main/java/com/medfund/user/entity/Group.java:13` — Group entity.
- `services/java/user-service/src/main/java/com/medfund/user/entity/LifePolicy.java:14` — LifePolicy entity.
- `services/java/user-service/src/main/java/com/medfund/user/entity/PendingGroupChange.java:26` — group-change state machine.
- `services/java/contributions-service/src/main/java/com/medfund/contributions/entity/Scheme.java:14` — Scheme entity.
- `services/java/contributions-service/src/main/java/com/medfund/contributions/entity/SchemeChange.java:13` — scheme-change state machine.
- `services/java/contributions-service/src/main/java/com/medfund/contributions/entity/SchemeCostShare.java:27` — temporal cost-share.
- `services/java/contributions-service/src/main/java/com/medfund/contributions/entity/Contribution.java` — Contribution entity.
- `services/java/contributions-service/src/main/java/com/medfund/contributions/entity/Transaction.java` — Transaction entity.
- `services/java/claims-service/src/main/java/com/medfund/claims/entity/Claim.java` — Claim entity + lifecycle.
- `services/java/claims-service/src/main/java/com/medfund/claims/entity/ClaimLine.java` — line items.
- `services/java/claims-service/src/main/java/com/medfund/claims/entity/PreAuthorization.java` — pre-auth.
- `services/java/finance-service/src/main/java/com/medfund/finance/entity/PaymentRun.java` — payment run.
- `services/java/finance-service/src/main/java/com/medfund/finance/entity/PaymentRunItem.java` — run items.
- `services/java/finance-service/src/main/java/com/medfund/finance/entity/Payment.java` — payment.
- `services/java/finance-service/src/main/java/com/medfund/finance/entity/Note.java:29-96` — Note entity (post-rename).
- `services/java/finance-service/src/main/java/com/medfund/finance/service/PaymentRunGenerator.java` — payment-run population.
- `services/java/rules-engine/src/main/java/com/medfund/rules/model/RuleDefinition.java:8-105` — rule model.
- `services/java/rules-engine/src/main/java/com/medfund/rules/model/RuleCategory.java:12-161` — 24 categories.
- `services/java/rules-engine/src/main/java/com/medfund/rules/model/ProrationStrategy.java:21-121` — 7 strategies.
- `services/java/rules-engine/src/main/java/com/medfund/rules/service/TenantRuleLoader.java:33-105` — per-tenant ReleaseId.
- `services/java/shared/src/main/java/com/medfund/shared/audit/AuditEvent.java:7-43` — audit record.
- `services/java/shared/src/main/java/com/medfund/shared/audit/AuditActor.java:29-72` — actor helper.
- `services/go/audit-service/internal/audit/event.go:5-31` — Go consumer + SecurityEvent struct.
- `scripts/bootstrap-keycloak.sh:1-336` — realm, client, roles, 8 platform users.
- `Makefile:42` — keycloak-setup target.
- `services/java/tenancy-service/src/main/resources/db/migration/tenant/V056__seed_ahfoz_march_tariffs.sql:15` — AHFOZ.
- `services/java/tenancy-service/src/main/resources/db/migration/public/V172__seed_pmb_classification_default_rules.sql:29` — PMB.
- `.claude/adjudication.md:26-239` — 6-stage pipeline.
- `.claude/portals.md` — portal / role map.
- `.claude/multi-tenancy.md` — schema-per-tenant.
- `.claude/rules-engine.md:552` — config-changed invalidation.

## Historical Context (from thoughts/shared/)

- `thoughts/shared/research/2026-08-10-creditors-workflow-unify-providers-and-members.md` — creditors flow; provider payouts vs. member payouts.
- `thoughts/shared/research/2026-08-09-payment-run-vs-payments.md` — PaymentRun vs Payment split; run lifecycle.
- `thoughts/shared/research/2026-08-09-ctc-payments.md` — CTC opt-in details ([[project_ctc_is_opt_in]]).
- `thoughts/shared/research/2026-08-10-debit-and-credit-notes-in-insurance.md` — Note domain background (pre-rename).
- `thoughts/shared/research/2026-08-08-advance-payments.md` — advance payment mechanic (relevant if seed generates any).
- `thoughts/shared/research/2026-09-15-ai-service-gaps-and-model-strategy.md` — AI service pilot readiness; informs the Stage 6 stub decision.

## Related Research

- `thoughts/shared/research/2026-08-10-copayments-standard-flow.md` — CO_PAYMENT rule wiring; relevant for HEALTH rule bundle.
- `thoughts/shared/research/2026-08-10-tenant-bank-accounts-and-stubbed-gateway.md` — payment-gateway stubbing; relevant for seed's payment execution.

## Open Questions

1. **Are member Keycloak users required for the seed?** If we defer them (seed only staff Keycloak users), we can still fully exercise operator flows but can't log in as any of the 20k members to test the member portal. Recommendation: seed ~50 "showcase" member Keycloak users spread across groups + individuals + suspended + terminated statuses, not all 20k.
2. **ICD-10 registry loading**: no bundled CSV. Do we ship a small curated ~500-code subset with the seeder, or expect a separate migration to load the full 70k-code registry first?
3. **Historical exchange rates**: needed if we simulate CURRENCY_CHANGE scheme changes. Do we bundle a static USD-ZWL rate table for 24 months, or skip currency-change scheme events entirely?
4. **AI Stage 6 policy**: default to stub (recommended); is any subset (say 100 claims) worth running against real Claude for a realistic mixed dataset?
5. **Seeding order relative to tenant migrations**: `TenantMigrationRunner` runs on tenancy-service boot. If the seed spins up tenants via API, migrations already run inline. Confirm no race with `BeneficiaryBenefitSeeder` consuming MEMBER_ENROLLED before all benefit tables are populated.
6. **Row-count sanity**: 1.8M claims per HEALTH tenant via HTTP will take hours even with parallelism. Should the seed offer a `--fast` mode (10x fewer claims / month) for CI + local dev, and `--full` for staging soak?
7. **Rules-engine seeding format**: is the ideal shape a JSON pack file (one per line, checked into `services/java/demo-seeder-service/src/main/resources/rules/`), or programmatic Java builders? Recommendation: JSON pack files, easier to review and edit.
