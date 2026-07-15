# Claims Adjudication Specification

> **Scope:** This document specifies adjudication for the **medical-aid vertical** (`InsuranceLine.HEALTH`) — the vertical that is production-ready today. The pipeline shape (six stages, rules-engine + AI split) is intended to generalize across insurance lines; the tariff/ICD/AHFOZ/clinical-validation specifics in this doc are health-only. Life/funeral/disability/travel/vehicle/property lines will each get analogous but line-appropriate adjudication when they graduate from scaffolding.

## Overview

Claims adjudication is the core business process of the platform. It determines what amount (if any) should be paid for a submitted claim. Every `Claim` row carries an `insurance_line` (V053 migration) so downstream stages can dispatch to line-appropriate rules. InsureFlow implements a **six-stage adjudication pipeline** that combines deterministic rules (Java Rules Engine via Drools) with AI-assisted analysis (Python AI Service).

## Where Each Component Lives

| Component | Service | Technology | Rationale |
|-----------|---------|-----------|-----------|
| **Tariff Management** | Claims Service | Java (Spring Boot) | Complex data model, per-tenant tariff schedules, CRUD operations |
| **ICD-10 Registry** | Claims Service | Java (Spring Boot) | Reference data, diagnosis-procedure mapping |
| **AHFOZ Provider Validation** | User Service | Java (Spring Boot) | Provider identity, specialization verification |
| **Eligibility Rules** | Rules Engine | Java (Drools) | Per-tenant configurable rules, deterministic logic |
| **Waiting Period Rules** | Rules Engine | Java (Drools) | Per-tenant, per-scheme configurable periods |
| **Benefit Limit Rules** | Rules Engine | Java (Drools) | Per-tenant limits, accumulation logic |
| **Pre-Authorization Rules** | Rules Engine | Java (Drools) | Per-tenant pre-auth requirements |
| **Tariff Validation** | Rules Engine + Claims Service | Java (Drools + Spring) | Modifier application, tariff capping, unbundling detection |
| **Clinical Validation** | Rules Engine + AI | Java (Drools) + Python | Diagnosis-procedure matching (rules), anomaly detection (AI) |
| **Fraud Detection** | AI Service | Python (FastAPI) | ML models, pattern analysis — probabilistic by nature |
| **Auto-Adjudication Recommendation** | AI Service | Python (FastAPI) | Claude reasoning, confidence scoring |
| **Duplicate Detection** | AI Service + Rules Engine | Python + Java | Fuzzy matching (AI), exact matching (rules) |

## Adjudication Pipeline

```
┌─────────────────────────────────────────────────────────────────┐
│                    CLAIM SUBMITTED                               │
│   Provider submits via Provider Portal (Angular/Flutter)         │
└──────────────────────────┬──────────────────────────────────────┘
                           │
                           ▼
┌─────────────────────────────────────────────────────────────────┐
│  STAGE 1: ELIGIBILITY VERIFICATION         [Rules Engine]       │
│                                                                  │
│  ✓ Is member active on date of service?                         │
│  ✓ Is dependant registered and within age limits?               │
│  ✓ Are contributions paid up (or within grace period)?          │
│  ✓ Is provider AHFOZ number valid and active?                   │
│  ✓ Does provider specialty match the service type?              │
│  ✓ Is claim within submission time limit (e.g., 90 days)?      │
│                                                                  │
│  FAIL → Auto-reject with rejection code (R01-R15)              │
│  PASS → Continue to Stage 2                                     │
└──────────────────────────┬──────────────────────────────────────┘
                           │
                           ▼
┌─────────────────────────────────────────────────────────────────┐
│  STAGE 2: WAITING PERIOD CHECK             [Rules Engine]       │
│                                                                  │
│  Calculate: days_since_enrollment = date_of_service - join_date │
│  (or upgrade_date if member recently changed scheme)            │
│                                                                  │
│  Check against per-scheme waiting periods:                      │
│  ┌─────────────────────┬────────────────────┐                   │
│  │ Benefit Category     │ Typical Period     │                   │
│  ├─────────────────────┼────────────────────┤                   │
│  │ General illness      │ 3 months           │                   │
│  │ Maternity            │ 10-12 months       │                   │
│  │ Pre-existing cond.   │ 12-24 months       │                   │
│  │ Dental prosthetics   │ 6-12 months        │                   │
│  │ Optical (spectacles) │ 6-12 months        │                   │
│  │ Elective surgery     │ 6-12 months        │                   │
│  │ Chronic conditions   │ 3-12 months        │                   │
│  └─────────────────────┴────────────────────┘                   │
│                                                                  │
│  Uses ICD code + tariff code to determine which category applies│
│  Emergency/accident claims bypass waiting periods               │
│  Special waivers override waiting periods for specific members  │
│                                                                  │
│  FAIL → Reject with R02 "Waiting period not served"            │
│  PASS → Continue to Stage 3                                     │
└──────────────────────────┬──────────────────────────────────────┘
                           │
                           ▼
┌─────────────────────────────────────────────────────────────────┐
│  STAGE 3: BENEFIT LIMIT CHECK              [Rules Engine]       │
│                                                                  │
│  1. Determine benefit category from tariff code                 │
│  2. Look up member's benefit balance for that category          │
│  3. Check against scheme-defined limits:                        │
│                                                                  │
│  Limit Types:                                                    │
│  - Annual limit per benefit category                            │
│  - Per-event limit (e.g., per hospital admission)               │
│  - Lifetime limit                                                │
│  - Family pooled limit vs. individual limit                     │
│  - Sub-limits within categories                                 │
│                                                                  │
│  Accumulation:                                                   │
│  - Sum all adjudicated claims YTD for this member + dependants  │
│  - Include pending (adjudicated but unpaid) amounts             │
│  - Remaining = annual_limit - accumulated                       │
│                                                                  │
│  FAIL (exhausted) → Reject with R03 "Benefit limit exhausted"  │
│  PARTIAL → Approve up to remaining limit, balance as co-payment│
│  PASS → Continue to Stage 4                                     │
└──────────────────────────┬──────────────────────────────────────┘
                           │
                           ▼
┌─────────────────────────────────────────────────────────────────┐
│  STAGE 4: PRE-AUTHORIZATION CHECK          [Rules Engine]       │
│                                                                  │
│  Check if the tariff code requires pre-authorization:           │
│  - Elective surgical admissions                                 │
│  - Advanced imaging (MRI, CT, PET)                              │
│  - Prosthetics and devices above threshold                      │
│  - Cancer treatment (chemo, radiotherapy)                       │
│  - Extended rehabilitation                                       │
│  - Cross-border/overseas treatment                              │
│  - High-cost medication                                         │
│                                                                  │
│  If required:                                                    │
│  - Match claim to pre-authorization record                      │
│  - Verify authorization number, approved procedures, amounts    │
│  - Check authorization has not expired                          │
│  - Check claim does not exceed authorized amount                │
│                                                                  │
│  FAIL (no auth) → Reject with R04 "Pre-authorization needed"   │
│  FAIL (expired) → Reject with R05 "Pre-authorization expired"  │
│  PASS → Continue to Stage 5                                     │
└──────────────────────────┬──────────────────────────────────────┘
                           │
                           ▼
┌─────────────────────────────────────────────────────────────────┐
│  STAGE 5: TARIFF & PRICING VALIDATION      [Claims Service +   │
│                                              Rules Engine]      │
│                                                                  │
│  5a. Tariff Code Validation:                                    │
│  - Verify tariff code exists and is active in the tenant's      │
│    tariff schedule                                               │
│  - Verify tariff code is applicable to the provider's specialty │
│    (via AHFOZ classification)                                   │
│  - Verify tariff code version matches date of service           │
│                                                                  │
│  5b. Modifier Application:                                       │
│  ┌────────────────────────────────────────────────────────────┐  │
│  │ Modifier Type          │ How Applied                       │  │
│  ├────────────────────────┼───────────────────────────────────┤  │
│  │ Anaesthesia time       │ base + (time_units × unit_rate)   │  │
│  │ Bilateral procedure    │ 100% + 50-75% for second side     │  │
│  │ Assistant surgeon      │ 20-25% of primary surgeon tariff  │  │
│  │ After-hours/emergency  │ 125-200% of standard tariff       │  │
│  │ Multiple procedures    │ 100% highest + 50-75% subsequent  │  │
│  │ Paediatric/geriatric   │ 125-150% of standard tariff       │  │
│  │ Complexity upcharge    │ Requires pre-auth justification   │  │
│  │ Follow-up reduction    │ 50-75% if within 30 days          │  │
│  └────────────────────────┴───────────────────────────────────┘  │
│                                                                  │
│  5c. Tariff Capping:                                            │
│  - If billed amount > tariff amount → pay at tariff rate        │
│  - Excess becomes patient co-payment                            │
│                                                                  │
│  5d. Unbundling Detection:                                       │
│  - Check if multiple billed codes should be a single bundled    │
│    code (flag for review or auto-apply bundled rate)            │
│                                                                  │
│  5e. Upcoding Detection:                                        │
│  - Flag if the billed code is higher-value than expected for    │
│    the diagnosis (fed into AI fraud scoring)                    │
│                                                                  │
│  FAIL → Reject with R06 "Invalid tariff" or R07 "Exceeds tariff│
│  PASS → Continue to Stage 6                                     │
└──────────────────────────┬──────────────────────────────────────┘
                           │
                           ▼
┌─────────────────────────────────────────────────────────────────┐
│  STAGE 6: CLINICAL & AI VALIDATION         [Rules Engine + AI] │
│                                                                  │
│  6a. Diagnosis-Procedure Matching [Rules Engine]:               │
│  - Validate ICD-10 code against tariff code                     │
│  - Use diagnosis_procedure_mappings table                       │
│  - Severity levels: auto-reject, flag for review, allow         │
│  - Example:                                                      │
│    ✓ K35.0 (Appendicitis) + 23410 (Appendicectomy) = Valid     │
│    ✗ J06.9 (URI) + 23410 (Appendicectomy) = Invalid            │
│                                                                  │
│  6b. Frequency & Utilization Checks [Rules Engine]:             │
│  - Annual health check: max 1/year                              │
│  - Spectacles: max 1 set per 2 years                           │
│  - Dental check-up: max 2/year                                  │
│  - Gender-specific validation (maternity, prostate, etc.)       │
│  - Age-appropriate validation (paediatric procedures)           │
│                                                                  │
│  6c. Duplicate Claim Detection [AI Service]:                    │
│  - Exact match: same provider + member + tariff + date          │
│  - Fuzzy match: similar claims within a time window             │
│  - Near-duplicate scoring                                       │
│                                                                  │
│  6d. Fraud Risk Scoring [AI Service]:                           │
│  - Provider pattern analysis (claim volume, avg amounts)        │
│  - Member pattern analysis (frequency, provider diversity)      │
│  - Unusual tariff combinations                                  │
│  - Geographic anomalies                                         │
│  - Timing anomalies (after-hours patterns)                      │
│  - Output: fraud_risk_score (0.0 - 1.0)                        │
│                                                                  │
│  6e. AI Adjudication Recommendation [AI Service]:               │
│  - Claude analyzes claim context + all stage results            │
│  - Generates: recommendation, confidence, explanation           │
│  - Compares with similar historical claims                      │
│                                                                  │
│  FAIL → Reject with R08 (duplicate) or R09 (diagnosis mismatch)│
│  FLAG → Route to manual review queue with AI explanation        │
│  PASS → Decision matrix (see below)                             │
└──────────────────────────┬──────────────────────────────────────┘
                           │
                           ▼
┌─────────────────────────────────────────────────────────────────┐
│  DECISION MATRIX                                                 │
│                                                                  │
│  All 6 stages passed AND fraud_risk < auto_approve_threshold    │
│  AND confidence > 0.8 AND tenant allows auto-approval:          │
│    → AUTO-APPROVE (logged, auditable, reversible)               │
│                                                                  │
│  Any stage failed with hard reject:                             │
│    → AUTO-REJECT with rejection code(s) and explanation         │
│                                                                  │
│  All stages passed BUT fraud_risk > flag_threshold              │
│  OR confidence < 0.8                                             │
│  OR tenant does not allow auto-approval:                        │
│    → ROUTE TO MANUAL REVIEW QUEUE                               │
│    → Display to claims clerk:                                   │
│      - AI recommendation (approve/reject/partial)               │
│      - Confidence score                                         │
│      - Claude's reasoning explanation                           │
│      - Flagged issues                                           │
│      - Similar historical claims                                │
│      - Member benefit balance impact                            │
│                                                                  │
│  Clerk actions:                                                  │
│    - Accept AI recommendation (one-click)                       │
│    - Override: approve with different amount                     │
│    - Override: reject with different reason                     │
│    - Request more information from provider                     │
│    - Escalate to supervisor                                     │
└─────────────────────────────────────────────────────────────────┘
```

## Data Models

### Tariff Schedule

```sql
-- Per-tenant tariff schedule (versioned)
CREATE TABLE tariff_schedules (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name            VARCHAR(200) NOT NULL,     -- "MIPS 2026", "NSSA 2026", "Custom Schedule A"
    source          VARCHAR(50) NOT NULL,      -- 'MIPS', 'NSSA', 'CUSTOM', 'AHFOZ'
    version         VARCHAR(50) NOT NULL,      -- "2026-Q1"
    effective_from  DATE NOT NULL,
    effective_to    DATE,                       -- NULL = currently active
    base_currency   CHAR(3) NOT NULL,          -- Currency of tariff prices
    is_active       BOOLEAN NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by      UUID NOT NULL
);

-- Individual tariff codes within a schedule
CREATE TABLE tariff_codes (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    schedule_id         UUID NOT NULL REFERENCES tariff_schedules(id),
    code                VARCHAR(20) NOT NULL,       -- "0190", "G041", "23410"
    description         TEXT NOT NULL,               -- "Appendicectomy"
    category            VARCHAR(100) NOT NULL,       -- "Surgery", "Radiology", "Pathology", "Dental"
    sub_category        VARCHAR(100),                -- "Abdominal Surgery", "MRI"
    base_price          DECIMAL(19,4) NOT NULL,      -- Base tariff amount
    currency_code       CHAR(3) NOT NULL,
    unit_type           VARCHAR(20) DEFAULT 'FIXED', -- 'FIXED', 'PER_UNIT', 'PER_TIME_UNIT'
    unit_value          DECIMAL(19,4),               -- Value per unit (if unit-based pricing)
    requires_pre_auth   BOOLEAN NOT NULL DEFAULT FALSE,
    max_frequency       VARCHAR(50),                  -- "1/year", "2/year", "1/2years", NULL=unlimited
    gender_restriction  VARCHAR(10),                  -- 'MALE', 'FEMALE', NULL=any
    min_age             INTEGER,                      -- NULL = no minimum
    max_age             INTEGER,                      -- NULL = no maximum
    provider_types      TEXT[],                       -- AHFOZ specialty codes that can bill this
    benefit_category    VARCHAR(100) NOT NULL,        -- Maps to scheme benefit category
    is_active           BOOLEAN NOT NULL DEFAULT TRUE,

    UNIQUE(schedule_id, code),
    INDEX idx_tariff_codes_lookup (schedule_id, code, is_active)
);

-- Tariff modifiers
CREATE TABLE tariff_modifiers (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    schedule_id         UUID NOT NULL REFERENCES tariff_schedules(id),
    code                VARCHAR(20) NOT NULL,        -- "BIL", "ASS", "AH", "PED", etc.
    name                VARCHAR(100) NOT NULL,       -- "Bilateral Procedure"
    description         TEXT,
    adjustment_type     VARCHAR(20) NOT NULL,        -- 'PERCENTAGE', 'FIXED_ADD', 'MULTIPLIER', 'PER_UNIT'
    adjustment_value    DECIMAL(10,4) NOT NULL,      -- e.g., 0.50 for 50%, 1.25 for 125%
    applies_to          TEXT[],                      -- Tariff code categories this can apply to
    conditions          JSONB,                       -- Additional conditions (e.g., {"min_time_units": 4})
    stackable           BOOLEAN NOT NULL DEFAULT FALSE, -- Can combine with other modifiers?
    requires_justification BOOLEAN NOT NULL DEFAULT FALSE,
    is_active           BOOLEAN NOT NULL DEFAULT TRUE,

    UNIQUE(schedule_id, code)
);
```

### ICD-10 Codes & Diagnosis-Procedure Mapping

```sql
-- ICD-10 diagnosis code registry
CREATE TABLE icd_codes (
    code            VARCHAR(10) PRIMARY KEY,  -- "K35.0", "S72.0", "E11"
    description     TEXT NOT NULL,            -- "Acute appendicitis with generalized peritonitis"
    chapter         VARCHAR(5) NOT NULL,      -- "XI" (Diseases of the Digestive System)
    chapter_name    VARCHAR(200) NOT NULL,
    block           VARCHAR(20),              -- "K35-K38" (Diseases of appendix)
    is_chronic      BOOLEAN NOT NULL DEFAULT FALSE,
    gender_specific VARCHAR(10),              -- 'MALE', 'FEMALE', NULL
    is_active       BOOLEAN NOT NULL DEFAULT TRUE
);

-- Valid diagnosis-procedure combinations
CREATE TABLE diagnosis_procedure_mappings (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    icd_code        VARCHAR(10) NOT NULL,     -- Can be specific "K35.0" or range "K35"
    icd_code_range  VARCHAR(20),              -- Optional: "K35.0-K35.9" for range matching
    tariff_code     VARCHAR(20) NOT NULL,
    validity        VARCHAR(20) NOT NULL DEFAULT 'VALID',  -- 'VALID', 'REVIEW', 'INVALID'
    notes           TEXT,                      -- "Only valid if patient is under 18"

    INDEX idx_dpm_lookup (icd_code, tariff_code)
);
```

### Provider AHFOZ Registration

```sql
-- Provider AHFOZ details (within user/provider table)
-- These fields are part of the providers table:

-- ahfoz_number        VARCHAR(20) UNIQUE NOT NULL  -- Unique AHFOZ identifier
-- specialty_code      VARCHAR(20) NOT NULL          -- GP, SURG, DENT, PHARM, PHYSIO, OPT, etc.
-- specialty_name      VARCHAR(100) NOT NULL          -- "General Practitioner", "Surgeon", etc.
-- practice_type       VARCHAR(50) NOT NULL           -- "INDIVIDUAL", "GROUP", "HOSPITAL", "CLINIC", "PHARMACY"
-- council_registration VARCHAR(50)                   -- Health Professions Authority registration number
-- registration_status  VARCHAR(20) NOT NULL           -- "ACTIVE", "SUSPENDED", "DEREGISTERED"
-- tax_clearance_valid  BOOLEAN NOT NULL DEFAULT FALSE
-- tax_withholding      BOOLEAN NOT NULL DEFAULT FALSE -- Whether to withhold tax from payments

-- AHFOZ specialty classification (reference data)
CREATE TABLE ahfoz_specialties (
    code            VARCHAR(20) PRIMARY KEY,   -- "GP", "SURG", "ORTHO", "DENT", "PHARM", etc.
    name            VARCHAR(100) NOT NULL,     -- "General Practitioner", "Surgeon", etc.
    category        VARCHAR(50) NOT NULL,      -- "MEDICAL", "DENTAL", "ALLIED", "PHARMACY"
    can_bill_codes  TEXT[],                    -- Tariff code categories this specialty can bill
    description     TEXT
);
```

### Waiting Period Configuration

```sql
-- Per-scheme waiting period rules
CREATE TABLE waiting_period_rules (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    scheme_id           UUID NOT NULL REFERENCES schemes(id),
    benefit_category    VARCHAR(100) NOT NULL,     -- "GENERAL", "MATERNITY", "DENTAL_PROSTHETICS", etc.
    period_months       INTEGER NOT NULL,           -- Number of months to wait
    applies_to          VARCHAR(20) NOT NULL DEFAULT 'NEW_MEMBERS', -- 'NEW_MEMBERS', 'SCHEME_UPGRADES', 'ALL'
    bypass_for_emergency BOOLEAN NOT NULL DEFAULT TRUE,
    bypass_for_accident  BOOLEAN NOT NULL DEFAULT TRUE,
    description         TEXT,
    is_active           BOOLEAN NOT NULL DEFAULT TRUE,

    UNIQUE(scheme_id, benefit_category, applies_to)
);

-- Special waivers (per-member exception)
CREATE TABLE waiting_period_waivers (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    member_id       UUID NOT NULL REFERENCES members(id),
    dependant_id    UUID REFERENCES dependants(id),  -- NULL = applies to member
    benefit_category VARCHAR(100) NOT NULL,
    waiver_reason   TEXT NOT NULL,
    approved_by     UUID NOT NULL,
    effective_from  DATE NOT NULL,
    effective_to    DATE,                            -- NULL = permanent
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
```

### Rejection Reasons

```sql
CREATE TABLE rejection_reasons (
    code            VARCHAR(10) PRIMARY KEY,   -- "R01", "R02", etc.
    category        VARCHAR(50) NOT NULL,      -- "ELIGIBILITY", "WAITING_PERIOD", "BENEFIT", etc.
    description     TEXT NOT NULL,             -- "Member not active on date of service"
    is_auto         BOOLEAN NOT NULL DEFAULT FALSE, -- Can this be applied automatically?
    is_active       BOOLEAN NOT NULL DEFAULT TRUE
);

-- Seed data (per-tenant, can customize)
INSERT INTO rejection_reasons VALUES
('R01', 'ELIGIBILITY',     'Member not active on date of service', TRUE),
('R02', 'WAITING_PERIOD',  'Waiting period not served', TRUE),
('R03', 'BENEFIT',         'Benefit limit exhausted', TRUE),
('R04', 'PRE_AUTH',        'Pre-authorization not obtained', TRUE),
('R05', 'PRE_AUTH',        'Pre-authorization expired', TRUE),
('R06', 'TARIFF',          'Invalid tariff code', TRUE),
('R07', 'TARIFF',          'Billed amount exceeds tariff rate', TRUE),
('R08', 'DUPLICATE',       'Duplicate claim detected', TRUE),
('R09', 'CLINICAL',        'Diagnosis does not support procedure', TRUE),
('R10', 'PROVIDER',        'Provider not registered for this service type', TRUE),
('R11', 'ELIGIBILITY',     'Contributions in arrears', TRUE),
('R12', 'EXCLUSION',       'Procedure excluded from scheme benefits', TRUE),
('R13', 'UTILIZATION',     'Frequency limit exceeded', TRUE),
('R14', 'ELIGIBILITY',     'Dependant over age limit', TRUE),
('R15', 'ADMIN',           'Claim submitted outside time limit', FALSE),
('R16', 'FRAUD',           'Claim flagged for suspected fraud', FALSE),
('R17', 'CLINICAL',        'Gender-inappropriate procedure', TRUE),
('R18', 'CLINICAL',        'Age-inappropriate procedure', TRUE);
```

## Drools Rule Examples

### Eligibility Rule
```drl
rule "Member must be active on date of service"
    when
        $claim : Claim(dateOfService != null)
        $member : Member(
            status != "ACTIVE",
            $claim.dateOfService > activationDate,
            terminationDate == null || $claim.dateOfService < terminationDate
        )
    then
        $claim.addRejection("R01", "Member status is " + $member.getStatus());
end
```

### Waiting Period Rule
```drl
rule "Check general illness waiting period"
    when
        $claim : Claim(benefitCategory == "GENERAL")
        $member : Member()
        $rule : WaitingPeriodRule(
            schemeId == $member.schemeId,
            benefitCategory == "GENERAL"
        )
        eval(ChronoUnit.MONTHS.between($member.getJoinDate(), $claim.getDateOfService()) < $rule.getPeriodMonths())
        not WaitingPeriodWaiver(
            memberId == $member.getId(),
            benefitCategory == "GENERAL",
            effectiveFrom <= $claim.getDateOfService()
        )
    then
        $claim.addRejection("R02", "General illness waiting period: " + $rule.getPeriodMonths() + " months required");
end
```

### Tariff Modifier Rule
```drl
rule "Apply bilateral modifier"
    when
        $detail : ClaimDetail(modifiers contains "BIL", isPrimary == false)
        $modifier : TariffModifier(code == "BIL", scheduleId == $detail.tariffScheduleId)
    then
        BigDecimal adjusted = $detail.getBaseAmount().multiply($modifier.getAdjustmentValue());
        $detail.setAdjudicatedAmount(adjusted);
        $detail.addAppliedModifier("BIL", $modifier.getAdjustmentValue());
end
```

## Regulatory Compliance

### IPEC Requirements (Insurance and Pensions Commission)
- Claims must be processed within **30 days** of receipt
- Claim processing SLA tracked per claim (`received_at`, `adjudicated_at`, `paid_at`)
- Dashboard widget showing SLA compliance percentage
- Alert when claims approach 30-day deadline

### Appeals Workflow
- Members and providers can dispute rejections
- Appeal creates a review case routed to supervisor
- Supervisor can override original decision with documented reason
- Appeal outcome is audited separately

### Reporting for Regulators
- Claims ratio (claims paid / contributions received)
- Average processing time
- Rejection rate by reason category
- Turnaround time distribution
- Generated via Finance Service, exported via File Service

## Claim State Machine

```
DRAFT → SUBMITTED → VERIFIED → IN_ADJUDICATION → ADJUDICATED → COMMITTED → PAID
                                                      │
                                                      ├→ REJECTED
                                                      ├→ PARTIAL_APPROVED
                                                      └→ PENDING_INFO (awaiting provider response)

Transitions:
  DRAFT → SUBMITTED          : Provider submits claim
  SUBMITTED → VERIFIED       : Member/dependant verification code confirmed
  VERIFIED → IN_ADJUDICATION : Claim enters adjudication pipeline
  IN_ADJUDICATION → ADJUDICATED : Pipeline completes (auto or manual)
  IN_ADJUDICATION → REJECTED    : Pipeline auto-rejects
  IN_ADJUDICATION → PENDING_INFO: Additional information requested
  PENDING_INFO → IN_ADJUDICATION: Info received, re-enters pipeline
  ADJUDICATED → COMMITTED    : Approved claim committed to payment queue
  COMMITTED → PAID           : Payment run executed, payment confirmed

  Any state → APPEALED       : Member/provider files appeal
  APPEALED → IN_ADJUDICATION : Appeal triggers re-adjudication
```
