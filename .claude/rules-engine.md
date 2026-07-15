# Rules Engine — Tenant Rule Configuration

## Overview

The rules engine is **line-agnostic** — it's shared infrastructure for every insurance line InsureFlow supports. Facts (`ClaimFact`, `MemberFact`, `ContributionFact`, `PaymentRunFact`, `SchemeChangeContext`, etc.) carry the `insurance_line` where relevant so rule authors can scope conditions to a specific line. Line-specific behaviour lives in *rule content*, not engine code.

The engine ships **15 rule-template categories** out of the box:

| Category | Applies to |
|---|---|
| `Eligibility` | All lines |
| `WaitingPeriod` | All lines with cover-start delays |
| `BenefitLimit` | All lines with capped benefits |
| `CoPayment` | All lines that split cost with the beneficiary |
| `TariffPricing` | Health today; extensible to line-specific fee schedules |
| `PreAuthorization` | Health today (clinical pre-auth); extensible |
| `ClinicalValidation` | Health only (ICD/procedure matching) |
| `ContributionBilling` | All lines |
| `ContributionPricing` | All lines |
| `AgeGroup` | All person-centric lines |
| `SchemeChangeProration` | All lines with mid-cycle plan changes |
| `ProviderPayment` | All lines that pay a service provider |
| `Reconciliation` | All lines |
| `MemberLifecycle` | All person-centric lines |
| `Underwriting` | All lines with risk assessment |

Tenant admins do **not** write Drools DRL code. They configure business rules through a **visual rule builder** in the Angular admin portal (`/admin/rules`). The UI translates their input into structured rule definitions stored in the database. The Rules Engine service (Java) compiles these definitions into executable Drools rules at runtime via `DrlCompiler`.

**Extensibility contract:** adding a new fact type or action does **not** require editing the compiler — extend `FACT_MAPPINGS` for new facts, add a new `ActionEmitter` bean for new actions. Tenant isolation is enforced via a per-tenant `ReleaseId` in `TenantRuleEngine.loadRules` (see project memory `bug_rules_engine_tenant_isolation`).

## Architecture

```
┌──────────────────────────────────────────────────────────────────┐
│  Angular Admin Portal — /admin/rules                             │
│                                                                  │
│  Visual Rule Builder UI                                          │
│  ┌────────────────────────────────────────────────────────────┐  │
│  │  IF [condition] AND [condition] ...                        │  │
│  │  THEN [action]                                             │  │
│  │                                                            │  │
│  │  Dropdowns, number inputs, toggles — no code writing       │  │
│  └────────────────────────────────────────────────────────────┘  │
└───────────────────────────┬──────────────────────────────────────┘
                            │ REST API
                            ▼
┌──────────────────────────────────────────────────────────────────┐
│  Rules Engine Service (Java / Spring Boot WebFlux)               │
│                                                                  │
│  1. Store rule definition as structured JSON in DB               │
│  2. Compile JSON → Drools DRL at runtime                         │
│  3. Load into KieSession per tenant (hot-reload on change)       │
│  4. Expose evaluation API for Claims/Contributions/Finance svcs  │
└──────────────────────────────────────────────────────────────────┘
```

## How It Works: JSON Rule Definitions → Drools

Tenant admins configure rules as structured data. The system converts this to Drools DRL behind the scenes.

### Example: Tenant configures a waiting period rule

**What the admin sees (Angular UI):**

```
┌─────────────────────────────────────────────────────────────────┐
│  Rule: Maternity Waiting Period                                  │
│                                                                  │
│  Category: [ Waiting Period       ▼]                             │
│  Priority:  [ 100                  ]  (higher = evaluated first) │
│  Status:    ● Active  ○ Inactive                                 │
│                                                                  │
│  ── CONDITIONS (ALL must be true) ──────────────────────────     │
│                                                                  │
│  IF  [ Benefit Category  ▼]  [ equals        ▼]  [ MATERNITY ▼] │
│  AND [ Member Type       ▼]  [ equals        ▼]  [ NEW       ▼] │
│  AND [ Days Since Enroll ▼]  [ is less than  ▼]  [ 300         ]│
│  AND [ Is Emergency      ▼]  [ equals        ▼]  [ No        ▼] │
│                                                        [ + Add ] │
│                                                                  │
│  ── ACTION ─────────────────────────────────────────────────     │
│                                                                  │
│  THEN [ Reject Claim     ▼]                                      │
│        Rejection Code: [ R02 — Waiting period not served     ▼]  │
│        Message: [ Maternity waiting period: 10 months required ] │
│                                                                  │
│  ── TEST ───────────────────────────────────────────────────     │
│  [ Test with sample claim... ]   [ Save Draft ]   [ Activate ]   │
└─────────────────────────────────────────────────────────────────┘
```

**What gets stored in the database (JSON):**

```json
{
  "id": "uuid",
  "name": "Maternity Waiting Period",
  "category": "WAITING_PERIOD",
  "priority": 100,
  "status": "ACTIVE",
  "version": 3,
  "conditions": {
    "operator": "AND",
    "items": [
      { "field": "claim.benefitCategory", "operator": "EQUALS", "value": "MATERNITY" },
      { "field": "member.memberType", "operator": "EQUALS", "value": "NEW" },
      { "field": "member.daysSinceEnrollment", "operator": "LESS_THAN", "value": 300 },
      { "field": "claim.isEmergency", "operator": "EQUALS", "value": false }
    ]
  },
  "action": {
    "type": "REJECT",
    "rejectionCode": "R02",
    "message": "Maternity waiting period: 10 months required"
  }
}
```

**What the Rules Engine compiles to (Drools DRL — never seen by tenant admin):**

```drl
rule "Maternity Waiting Period"
  salience 100
  when
    $claim : ClaimFact(benefitCategory == "MATERNITY", isEmergency == false)
    $member : MemberFact(memberType == "NEW", daysSinceEnrollment < 300)
  then
    $claim.addRejection("R02", "Maternity waiting period: 10 months required");
end
```

## Rule Categories

Each category has its own set of available **fields**, **operators**, and **actions** — the UI only shows options relevant to the selected category.

### 1. Eligibility Rules

**Purpose**: Check if the member/dependant is eligible for coverage on the date of service.

| Available Fields | Type | Description |
|-----------------|------|-------------|
| `member.status` | Enum | ACTIVE, SUSPENDED, TERMINATED |
| `member.memberType` | Enum | NEW, EXISTING, TRANSFERRED |
| `member.contributionStatus` | Enum | UP_TO_DATE, IN_ARREARS, IN_GRACE_PERIOD |
| `member.gracePeriodDays` | Number | Days of grace period allowed |
| `member.arrearsMonths` | Number | How many months in arrears |
| `dependant.status` | Enum | ACTIVE, SUSPENDED, TERMINATED, OVER_AGE |
| `dependant.age` | Number | Current age |
| `dependant.maxAge` | Number | Scheme's max dependant age |
| `provider.registrationStatus` | Enum | ACTIVE, SUSPENDED, DEREGISTERED |
| `provider.ahfozSpecialty` | Enum | GP, SURG, DENT, PHARM, etc. |
| `claim.dateOfService` | Date | Service date |
| `claim.submissionDate` | Date | When claim was submitted |
| `claim.daysSinceService` | Number | Days between service and submission |

**Available Actions**: `REJECT` (with rejection code), `FLAG_FOR_REVIEW`, `WARN` (non-blocking warning)

**Example rules tenant admins typically configure:**
- Reject if member is suspended
- Reject if contributions in arrears for more than X months
- Reject if claim submitted more than 90 days after service
- Reject if dependant is over age limit
- Flag if provider is not in-network

### 2. Waiting Period Rules

**Purpose**: Enforce waiting periods before benefits become available.

| Available Fields | Type | Description |
|-----------------|------|-------------|
| `claim.benefitCategory` | Enum | GENERAL, MATERNITY, DENTAL, OPTICAL, CHRONIC, ELECTIVE_SURGERY, etc. |
| `member.daysSinceEnrollment` | Number | Days since member joined |
| `member.daysSinceSchemeChange` | Number | Days since scheme upgrade |
| `member.memberType` | Enum | NEW, EXISTING, TRANSFERRED |
| `claim.isEmergency` | Boolean | Emergency/accident claim |
| `claim.isAccident` | Boolean | Accident-related claim |
| `member.hasWaiver` | Boolean | Special waiver exists for this benefit |

**Available Actions**: `REJECT` (R02), `FLAG_FOR_REVIEW`

**Example rules:**
- General illness: reject if enrolled < 90 days (except emergencies)
- Maternity: reject if enrolled < 300 days
- Dental prosthetics: reject if enrolled < 180 days
- Optical: reject if enrolled < 180 days
- Bypass all waiting periods if `isEmergency` or `isAccident` is true

### 3. Benefit Limit Rules

**Purpose**: Check claims against annual/per-event/lifetime benefit limits.

| Available Fields | Type | Description |
|-----------------|------|-------------|
| `claim.benefitCategory` | Enum | Benefit type being used |
| `claim.amount` | Money | Requested amount |
| `member.benefitUsedYTD` | Money | Already used this year for this benefit |
| `member.benefitLimit` | Money | Annual limit for this benefit |
| `member.benefitRemaining` | Money | Remaining balance |
| `member.absoluteLimit` | Money | Overall annual limit across all benefits |
| `member.absoluteUsedYTD` | Money | Total used across all benefits |
| `claim.isFamily` | Boolean | Whether this is a family-pooled benefit |
| `family.benefitUsedYTD` | Money | Family total used (for pooled benefits) |

**Available Actions**: `REJECT` (R03), `PARTIAL_APPROVE` (approve up to remaining), `FLAG_FOR_REVIEW`

**Example rules:**
- Reject if `claim.amount` > `member.benefitRemaining`
- Partial approve: approve `member.benefitRemaining`, excess as co-payment
- Reject if absolute limit exhausted
- Flag if claim is >80% of remaining limit (approaching exhaustion)

### 4. Pre-Authorization Rules

**Purpose**: Define which procedures require prior approval.

| Available Fields | Type | Description |
|-----------------|------|-------------|
| `claim.tariffCode` | String | Tariff code being billed |
| `claim.tariffCategory` | Enum | Surgery, Radiology, Dental, etc. |
| `claim.amount` | Money | Billed amount |
| `claim.hasPreAuth` | Boolean | Whether a pre-auth exists for this claim |
| `claim.preAuthStatus` | Enum | APPROVED, EXPIRED, NONE |
| `claim.preAuthAmount` | Money | Approved pre-auth amount |
| `claim.isElective` | Boolean | Elective procedure flag |

**Available Actions**: `REJECT` (R04/R05), `FLAG_FOR_REVIEW`

**Tenant configures a list of conditions that trigger pre-auth requirement:**
- All elective surgical admissions
- Any imaging code (MRI, CT, PET) above amount threshold
- Prosthetics above threshold
- Specific tariff code ranges
- Any single claim above a configurable amount (e.g., $500)

### 5. Tariff & Pricing Rules

**Purpose**: Validate pricing against tariff schedules.

| Available Fields | Type | Description |
|-----------------|------|-------------|
| `claimDetail.billedAmount` | Money | What provider billed |
| `claimDetail.tariffAmount` | Money | What the tariff schedule says |
| `claimDetail.tariffCode` | String | Tariff code |
| `claimDetail.modifiers` | List | Applied modifiers |
| `claimDetail.providerSpecialty` | Enum | Provider's AHFOZ specialty |
| `claimDetail.tariffAllowedSpecialties` | List | Specialties allowed to bill this code |
| `claim.procedureCount` | Number | Number of procedures in this claim |
| `claimDetail.procedureRank` | Number | Rank by amount (1 = highest) |

**Available Actions**: `CAP_TO_TARIFF` (pay at tariff rate), `REJECT` (R06/R07/R10), `APPLY_MODIFIER`, `FLAG_FOR_REVIEW`

**Example rules:**
- If billed > tariff: cap to tariff amount, excess as co-payment
- If provider specialty not in allowed list: reject with R10
- Multiple procedures: pay 100% for rank 1, configurable % for rank 2+
- After-hours modifier: apply X% loading between configurable hours

### 6. Clinical Validation Rules

**Purpose**: Validate medical appropriateness.

| Available Fields | Type | Description |
|-----------------|------|-------------|
| `claim.icdCode` | String | ICD-10 diagnosis code |
| `claimDetail.tariffCode` | String | Procedure tariff code |
| `claim.diagnosisProcedureValidity` | Enum | VALID, REVIEW, INVALID (from mapping table) |
| `member.gender` | Enum | MALE, FEMALE |
| `member.age` | Number | Member/dependant age |
| `claim.benefitCategory` | Enum | Benefit category |
| `member.claimCountYTD` | Number | Claims this year for this benefit |
| `claim.maxFrequency` | String | Allowed frequency from tariff (e.g., "1/year") |

**Available Actions**: `REJECT` (R09/R13/R17/R18), `FLAG_FOR_REVIEW`

**Example rules:**
- Reject if diagnosis-procedure mapping is INVALID
- Flag for review if mapping is REVIEW
- Reject if frequency exceeded (e.g., 2 dental checkups/year already used)
- Reject maternity codes for male members
- Flag paediatric codes for members over age 12

### 7. Billing Rules

**Purpose**: Control contribution billing behavior.

| Available Fields | Type | Description |
|-----------------|------|-------------|
| `group.contributionStatus` | Enum | UP_TO_DATE, IN_ARREARS, SUSPENDED |
| `group.arrearsMonths` | Number | Months in arrears |
| `group.memberCount` | Number | Active members in group |
| `member.scheme` | Enum | Member's current scheme |
| `member.ageGroup` | Enum | CHILD, ADULT, SENIOR |
| `billingCycle.month` | Number | Current billing month |
| `billingCycle.frequency` | Enum | MONTHLY, QUARTERLY, ANNUAL |

**Available Actions**: `SUSPEND_GROUP`, `APPLY_PENALTY`, `SEND_REMINDER`, `FLAG_FOR_REVIEW`

**Example rules:**
- Suspend group if arrears > 3 months
- Apply X% penalty on late payments after grace period
- Send reminder 7 days before due date
- Send escalation notice at 30, 60, 90 days overdue

## Rule Builder UI — Angular Admin Portal

### Route: `/admin/rules`

| Page | Route | Description |
|------|-------|-------------|
| Rule Dashboard | `/admin/rules` | All rules grouped by category, with status (active/inactive), last modified, version |
| Create Rule | `/admin/rules/new` | Visual rule builder (see below) |
| Edit Rule | `/admin/rules/:id` | Edit existing rule, creates new version |
| Rule History | `/admin/rules/:id/history` | Version history, diff between versions, rollback |
| Test Rule | `/admin/rules/:id/test` | Dry-run against sample or real claims |
| Rule Templates | `/admin/rules/templates` | Pre-built rule templates that can be cloned and customized |
| Import/Export | `/admin/rules/import` | Import rules from JSON, export for backup or sharing between tenants |

### Visual Rule Builder Components

```
┌─────────────────────────────────────────────────────────────────────┐
│  Create Rule                                                         │
│                                                                      │
│  Name:     [ _____________________________________ ]                 │
│  Category: [ Waiting Period                      ▼ ]                 │
│  Priority: [ 100     ] (1-1000, higher = first)                      │
│  Description: [ ______________________________________ ]             │
│                                                                      │
│  ═══════════════════════════════════════════════════════════════════  │
│  CONDITIONS                                                          │
│  ─────────────────────────────────────────────────────────────────   │
│                                                                      │
│  Match: (● ALL conditions) (○ ANY condition)                         │
│                                                                      │
│  ┌─────────────────────────────────────────────────────────────┐     │
│  │ [Benefit Category ▼] [equals         ▼] [MATERNITY       ▼]│ [✕] │
│  └─────────────────────────────────────────────────────────────┘     │
│  ┌─────────────────────────────────────────────────────────────┐     │
│  │ [Days Since Enroll ▼] [is less than  ▼] [    300          ]│ [✕] │
│  └─────────────────────────────────────────────────────────────┘     │
│  ┌─────────────────────────────────────────────────────────────┐     │
│  │ [Is Emergency      ▼] [equals        ▼] [No             ▼]│ [✕] │
│  └─────────────────────────────────────────────────────────────┘     │
│                                                                      │
│  [ + Add Condition ]    [ + Add Condition Group (nested AND/OR) ]    │
│                                                                      │
│  ═══════════════════════════════════════════════════════════════════  │
│  ACTION                                                              │
│  ─────────────────────────────────────────────────────────────────   │
│                                                                      │
│  When conditions are met: [ Reject Claim                         ▼]  │
│                                                                      │
│  Rejection Code:  [ R02 — Waiting period not served              ▼]  │
│  Message:         [ Maternity benefit requires 10-month waiting    ] │
│                   [ period from enrollment date.                   ] │
│                                                                      │
│  ═══════════════════════════════════════════════════════════════════  │
│  TEST                                                                │
│  ─────────────────────────────────────────────────────────────────   │
│                                                                      │
│  Test this rule against:                                             │
│  (○ Sample claim)  (● Existing claim ID: [ _____________ ])         │
│                                                                      │
│  [ Run Test ]                                                        │
│                                                                      │
│  Result: ⚠ WOULD REJECT — R02 "Maternity benefit requires..."       │
│  Matched conditions: 3/3                                             │
│  Execution time: 2ms                                                 │
│                                                                      │
│  ═══════════════════════════════════════════════════════════════════  │
│                                                                      │
│  [ Cancel ]          [ Save as Draft ]          [ Save & Activate ]  │
│  │                                                                   │
│  │  ⚠ Activating will apply this rule to all new claims immediately │
└─────────────────────────────────────────────────────────────────────┘
```

### Condition Builder — Field Selection

When the admin selects a category, only relevant fields appear in the dropdown:

```
Category: Waiting Period
┌──────────────────────────────┐
│  Field ▼                     │
│  ┌──────────────────────────┐│
│  │ 📋 Claim                 ││
│  │   • Benefit Category     ││
│  │   • Is Emergency         ││
│  │   • Is Accident          ││
│  │   • Date of Service      ││
│  │ 👤 Member                ││
│  │   • Days Since Enrollment││
│  │   • Days Since Scheme Chg││
│  │   • Member Type          ││
│  │   • Has Waiver           ││
│  │   • Scheme Name          ││
│  └──────────────────────────┘│
└──────────────────────────────┘
```

Operators change based on field type:

| Field Type | Available Operators |
|-----------|-------------------|
| **Enum** | equals, not equals, in list, not in list |
| **Number** | equals, not equals, greater than, less than, greater or equal, less or equal, between |
| **Money** | same as Number (with currency awareness) |
| **Boolean** | equals (Yes/No) |
| **Date** | before, after, between, within last X days |
| **String** | equals, contains, starts with, in list |
| **List** | contains, does not contain, is empty, is not empty |

### Nested Condition Groups

For complex rules, admins can create nested AND/OR groups:

```
Match ALL:
  ├── Benefit Category = DENTAL
  ├── Match ANY:
  │   ├── Member Type = NEW
  │   └── Member Type = TRANSFERRED
  └── Days Since Enrollment < 180
```

This translates to: `benefitCategory == DENTAL AND (memberType == NEW OR memberType == TRANSFERRED) AND daysSinceEnrollment < 180`

## Rule Templates

Pre-built templates that tenants can clone and customize. Saves time for common industry-standard rules.

| Template | Category | Description |
|----------|----------|-------------|
| General Waiting Period | Waiting Period | 3-month waiting period for new members (configurable) |
| Maternity Waiting Period | Waiting Period | 10-month maternity waiting period |
| Dental Prosthetics Wait | Waiting Period | 6-month wait for dental prosthetics |
| Optical Wait | Waiting Period | 6-month wait for optical benefits |
| Contribution Arrears Check | Eligibility | Reject if contributions more than X months in arrears |
| Claim Submission Deadline | Eligibility | Reject if claim submitted more than X days after service |
| Tariff Capping | Tariff | Cap billed amount to tariff rate |
| Multiple Procedure Discount | Tariff | Pay 100% first procedure, X% subsequent |
| After-Hours Loading | Tariff | Apply X% loading for after-hours services |
| Annual Benefit Limit | Benefit Limit | Reject/partial when annual limit exhausted |
| Pre-Auth for High-Value | Pre-Auth | Require pre-auth for claims above X amount |
| Pre-Auth for Elective Surgery | Pre-Auth | Require pre-auth for all elective admissions |
| Duplicate Claim Check | Clinical | Flag exact duplicate claims |
| Gender Validation | Clinical | Reject gender-inappropriate procedures |
| Age Validation | Clinical | Reject age-inappropriate procedures |
| Arrears Suspension | Billing | Suspend group after X months of arrears |
| Late Payment Penalty | Billing | Apply X% penalty after grace period |

Tenants click "Use Template" → cloned into their rules → customize values → activate.

## Rule Versioning & Lifecycle

```
DRAFT ──→ ACTIVE ──→ INACTIVE
  │          │           │
  │          ▼           │
  │       ACTIVE (v2) ──┘
  │          │
  └──────────┘ (edit creates new draft version)
```

- **Draft**: Saved but not applied to claims. Can be tested.
- **Active**: Applied to all new claims. Only one version of a rule can be active at a time.
- **Inactive**: Deactivated. Historical claims processed under this rule retain their audit trail.

Every edit creates a **new version** (v1 → v2 → v3). Tenant admin can:
- View diff between versions (what changed)
- Rollback to a previous version (activates the old version, deactivates current)
- See which claims were processed under each version

## Rule Testing (Sandbox)

Before activating, admins can test rules:

### Test Against Sample Claim
Create a hypothetical claim in the test UI:
- Select member, provider, tariff codes, amounts, diagnosis
- Run the rule engine against this sample
- See which rules fired, in what order, and the outcome

### Test Against Existing Claim
Enter a real claim ID → re-run adjudication pipeline with the new/modified rule included → compare:
- What the original adjudication decided
- What would happen with the new rule
- Highlight differences

### Batch Test
Run all rules against the last 100 claims → report showing:
- How many claims would be affected by the new rule
- How many would change from approve → reject or vice versa
- Projected financial impact

## Database Schema

```sql
-- Rule definitions (per-tenant schema)
CREATE TABLE business_rules (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name            VARCHAR(200) NOT NULL,
    description     TEXT,
    category        VARCHAR(50) NOT NULL,        -- 'ELIGIBILITY', 'WAITING_PERIOD', 'BENEFIT_LIMIT', etc.
    priority        INTEGER NOT NULL DEFAULT 100, -- Higher = evaluated first
    version         INTEGER NOT NULL DEFAULT 1,
    status          VARCHAR(20) NOT NULL DEFAULT 'DRAFT',  -- 'DRAFT', 'ACTIVE', 'INACTIVE'
    conditions      JSONB NOT NULL,              -- Structured condition tree
    action          JSONB NOT NULL,              -- Action definition
    is_system       BOOLEAN NOT NULL DEFAULT FALSE,  -- true = seeded from template, cannot be deleted
    template_id     VARCHAR(100),                -- Which template this was cloned from
    previous_version_id UUID REFERENCES business_rules(id),

    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by      UUID NOT NULL,
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_by      UUID NOT NULL,
    activated_at    TIMESTAMPTZ,                 -- When this version was activated
    deactivated_at  TIMESTAMPTZ,

    INDEX idx_rules_category (category, status),
    INDEX idx_rules_active (status) WHERE status = 'ACTIVE'
);

-- Rule execution log (for auditing which rules fired on each claim)
CREATE TABLE rule_execution_log (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    claim_id        UUID NOT NULL,
    rule_id         UUID NOT NULL REFERENCES business_rules(id),
    rule_version    INTEGER NOT NULL,
    fired           BOOLEAN NOT NULL,            -- Did conditions match?
    action_taken    VARCHAR(50),                 -- 'REJECT', 'PARTIAL_APPROVE', 'FLAG', 'WARN', NULL if not fired
    action_detail   JSONB,                       -- Rejection code, message, etc.
    execution_time_ms INTEGER,
    evaluated_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    INDEX idx_rule_exec_claim (claim_id),
    INDEX idx_rule_exec_rule (rule_id, evaluated_at DESC)
);
```

## Hot-Reload Architecture

When a tenant admin activates or deactivates a rule:

```
Angular UI → POST /api/v2/rules/:id/activate
                    │
                    ▼
            Rules Engine Service:
            ├── Update rule status in DB
            ├── Publish medfund.tenants.config-changed (Kafka)
            ├── Invalidate tenant's rule cache (Redis)
            └── Recompile tenant's Drools KieBase
                    │
                    ▼
            Next claim evaluation uses updated rules
            (no service restart needed)
```

Each tenant has its own compiled `KieBase` (Drools knowledge base) cached in memory. On rule change:
1. The JSON rules for that tenant are loaded from DB
2. Each rule is compiled to DRL
3. A new `KieBase` is built and swapped atomically
4. The old `KieBase` is discarded after in-flight evaluations complete

This means rule changes take effect within seconds, with zero downtime.
