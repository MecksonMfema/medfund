# InsureFlow — User Workflows

Complete workflow documentation for all user roles across the InsureFlow healthcare claims management platform.

---

## Table of Contents

1. [Prospective Member — Getting a Quote & Joining](#1-prospective-member--getting-a-quote--joining)
2. [Member — Daily Usage](#2-member--daily-usage)
3. [Provider — Submitting Claims](#3-provider--submitting-claims)
4. [Claims Officer — Processing Claims](#4-claims-officer--processing-claims)
5. [Contributions Officer — Billing & Schemes](#5-contributions-officer--billing--schemes)
6. [Finance Officer — Payments & Reporting](#6-finance-officer--payments--reporting)
7. [Tenant Admin — Configuration](#7-tenant-admin--configuration)
8. [Super Admin — Platform Management](#8-super-admin--platform-management)
9. [Group Liaison — Employer Group Management](#9-group-liaison--employer-group-management)
10. [Lifecycle Summary](#10-lifecycle-summary)

---

## 1. Prospective Member — Getting a Quote & Joining

### Get an Insurance Quote (No Login Required)

```
Visit website or mobile app
    │
    ▼
Select a scheme (e.g., Gold Medical Aid)
Enter personal details:
  - Name, date of birth, email, phone
  - Add dependants (spouse, children — each with DOB)
    │
    ▼
System auto-calculates premiums:
  - Looks up scheme's age group pricing
  - Calculates per-person monthly contribution based on age bracket
  - Sums member + dependant premiums
    │
    ▼
Receive quotation:
  - Quote number (QTE-XXXXXX), valid for 30 days
  - Member premium: $350/month (Adult 30-39 bracket)
  - Spouse premium: $350/month
  - Child 1 premium: $150/month (Child 0-18 bracket)
  - Total monthly: $850/month
  - Benefits summary: annual limits per category
```

**API:** `POST /api/v1/quotes` (public, no authentication)

### Enrollment

**Individual member (self-registration):**
```
Prospect decides to join
    │
    ▼
Registers via Flutter app or website
  - Submits personal details, selects scheme
  - Uploads ID document
  - Makes first contribution payment (EcoCash, card, bank transfer)
    │
    ▼
System processes enrollment:
  - Member created (status: "enrolled")
  - Keycloak account created (email + temporary password)
  - Welcome email sent
  - Waiting periods start from enrollment date
  - Digital membership card issued (QR code)
    │
    ▼
Admin reviews and activates → status: "active"
```

**Group member (employer enrolls):**
```
Group Liaison logs in
    │
    ▼
Adds employee to group
  - Enters employee details
  - Assigns scheme (employer-selected)
  - Adds dependants
    │
    ▼
Member account created
  - Keycloak account provisioned
  - Contributions billed to employer group
```

**API:** `POST /api/v1/members` (authenticated)

---

## 2. Member — Daily Usage (Flutter Mobile App)

### Login

```
Open Flutter app → "Sign In with Keycloak"
  │
  ▼
Keycloak OIDC + PKCE authentication flow
  - MFA if required by tenant (TOTP / Email OTP / SMS OTP)
  - JWT token stored in secure storage
  - Tenant resolved from JWT claims
```

### Dashboard

After login, member sees their dashboard:

| Section | Content |
|---------|---------|
| **Benefits Used** | Percentage of annual limit consumed |
| **Active Claims** | Count of claims in progress |
| **Balance Remaining** | Available benefit balance |
| **Next Payment** | Upcoming contribution due date |
| **Recent Claims** | Last 3 claims with status badges |
| **Quick Actions** | View Claims, Benefits, Pay Bill, My Card |

### View Benefits

```
Navigate to Benefits tab
    │
    ▼
See scheme overview:
  - Scheme name (e.g., "Gold Medical Aid")
  - Annual limit: $50,000
  - Usage progress bar (25% used)
    │
    ▼
Per-category breakdown:
  - General Illness: $3,200 of $15,000 used
  - Maternity: $0 of $20,000 used
  - Dental: $1,800 of $5,000 used
  - Optical: $500 of $3,000 used
  - Chronic Medication: $2,400 of $8,000 used
```

### View Claims

```
Navigate to Claims tab
    │
    ▼
Filter by status: All | Pending | Approved | Paid | Rejected
    │
    ▼
Each claim shows:
  - Claim number (CLM-XXXXXX)
  - Status badge (color-coded)
  - Description + provider name
  - Claimed amount
  - Service date
    │
    ▼
Tap for details:
  - Full claim breakdown (line items)
  - Adjudication outcome
  - Co-payment amount (if any)
  - Rejection reason (if rejected)
```

### Pay Contribution

```
Navigate to Payments tab
    │
    ▼
See outstanding contribution:
  "April 2026 — $450.00, due Apr 15"
    │
    ▼
Tap "Pay Now"
  - Select payment method (EcoCash, Card, Bank Transfer)
  - Confirm amount
  - Payment Gateway processes payment
  - Receipt generated
  - Contribution status → "paid"
```

**API:** `POST /api/v1/pay/initiate` → Payment Gateway

### Manage Dependants

```
Navigate to Profile → Dependants
    │
    ▼
View existing dependants with status
Add new dependant:
  - Name, DOB, gender, relationship, national ID
  - POST /api/v1/dependants
Remove dependant:
  - POST /api/v1/dependants/{id}/remove → status "removed"
```

### Request Scheme Change

```
Navigate to Profile → My Scheme
    │
    ▼
Tap "Change Scheme"
  - Select new scheme (Bronze → Gold)
  - Choose effective date
  - Provide reason
  - POST /api/v1/scheme-changes
    │
    ▼
Request status: PENDING
  - Contributions officer reviews
  - APPROVED → effective on requested date
  - REJECTED → member notified with reason
```

### AI Chat Support

```
Navigate to Chat
    │
    ▼
Ask questions:
  "What is my maternity benefit balance?"
  "When will my claim CLM-001234 be processed?"
  "What does my dental cover include?"
    │
    ▼
AI assistant responds (Claude-powered with fallback)
  - Answers benefit queries
  - Explains claim status
  - Suggests next steps
  - Escalates to human agent if complex
```

### Membership Card

```
Navigate to Profile → Membership Card
    │
    ▼
Digital card displays:
  - QR code (for provider verification)
  - Member number, name, scheme
  - Status: Active
    │
    ▼
Provider scans QR → verifies coverage
```

---

## 3. Provider — Submitting Claims (Flutter App + Angular Portal)

### Login

```
Login via Keycloak (provider role in tenant realm)
  - Each provider has accounts in every tenant realm they serve
```

### Submit a Medical Claim

```
Provider Dashboard → Submit Claim
    │
    ▼
Step 1: Verify Member
  - Scan member's QR code (Flutter) or enter member number
  - System confirms: member active, coverage valid
    │
    ▼
Step 2: Enter Diagnosis
  - Search ICD-10 codes (e.g., "J06.9 — Acute upper respiratory infection")
  - GET /api/v1/icd-codes/search?q=respiratory
    │
    ▼
Step 3: Enter Procedures
  - Search tariff codes (e.g., "0190 — General consultation")
  - GET /api/v1/tariffs/codes/search?q=consultation
  - AI suggests tariff codes from description
  - Enter quantity and claimed amount per line
    │
    ▼
Step 4: Review & Submit
  - System shows: total claimed, estimated co-payment
  - POST /api/v1/claims
  - Claim created: status SUBMITTED, verification code generated
  - Claim number: CLM-XXXXXX
```

### Submit a Drug Claim (Pharmacy)

```
Provider Dashboard → Submit Drug Claim
    │
    ▼
Enter prescription details:
  - Prescription number
  - Prescribing doctor name
  - Drug code (NAPPI code), drug name
  - Quantity, dosage, days supply
  - POST /api/v1/drug-claims
    │
    ▼
Same adjudication pipeline, claimType = "drug"
```

### Request Pre-Authorization

```
Before performing an expensive procedure:
    │
    ▼
Submit pre-auth request:
  - Member, tariff code, diagnosis
  - Estimated amount
  - POST /api/v1/pre-authorizations
    │
    ▼
Status: PENDING
  - Claims officer reviews
  - APPROVED (with approved amount + expiry date)
  - REJECTED (with reason)
    │
    ▼
When submitting the actual claim:
  - System checks pre-auth exists and is valid
  - Claim passes Stage 4 (Pre-Authorization) of adjudication
```

### Request Cost Quotation

```
Before treatment, provide cost estimate to member:
    │
    ▼
Submit quotation:
  - Member, procedures, estimated cost
  - POST /api/v1/quotations
    │
    ▼
Medical aid reviews:
  - Sets covered amount + co-payment amount
  - Member sees: "Procedure costs $5,000, scheme covers $4,000, you pay $1,000"
    │
    ▼
Quotation APPROVED → provider proceeds with treatment
```

### View Payment History

```
Provider Dashboard → Payments
    │
    ▼
See payment history:
  - GET /api/v1/payments/provider/{providerId}
  - Outstanding balance per medical aid
  - Estimated next payout date
```

---

## 4. Claims Officer — Processing Claims (Angular Portal)

### Login

```
Login via Keycloak (claims_clerk or adjudicator role)
```

### Adjudication Queue

```
Claims Portal → Adjudication Queue
    │
    ▼
View claims awaiting adjudication:
  GET /api/v1/claims/status/VERIFIED
  Table: claim #, member, provider, amount, service date, status
  Filter by status, sort by date
```

### Adjudicate a Claim

```
Click on a claim → Adjudication Workspace
    │
    ▼
View claim details:
  - Member: John Doe (MBR-123456), Active, enrolled 2 years ago
  - Provider: City Hospital, AHFOZ: GP
  - Diagnosis: J06.9 — Acute upper respiratory infection
  - Procedures: 0190 — Consultation ($500)
  - Total claimed: $500.00
    │
    ▼
AI Analysis Panel:
  - AI Recommendation: APPROVE (confidence: 0.87)
  - Fraud Risk: LOW (score: 0.12)
  - Reasoning: "Standard consultation, within tariff limits, no flags"
    │
    ▼
6-Stage Pipeline Results:
  ✓ Stage 1 — Eligibility: Member active, provider registered
  ✓ Stage 2 — Waiting Period: 730 days since enrollment (90 required)
  ✓ Stage 3 — Benefit Limits: $11,800 remaining of $15,000 (General)
  ✓ Stage 4 — Pre-Authorization: Not required for this tariff
  ✓ Stage 5 — Tariff Pricing: $500 within tariff limit of $500
  ✓ Stage 6 — Clinical: Diagnosis-procedure mapping VALID
    │
    ▼
Co-Payment Calculation:
  - Tariff allows: $500, Claimed: $500 → Co-payment: $0
    │
    ▼
Decision:
  ├── APPROVE → POST /api/v1/claims/{id}/adjudicate
  │   Status → ADJUDICATED, approvedAmount = $500
  │   → Kafka event → Finance updates provider balance
  │   → Notification sent to member + provider
  │
  ├── REJECT → set rejectionReason (R01-R18)
  │   e.g., R02: Waiting period not served
  │   e.g., R03: Benefit limit exhausted
  │   → Notification sent with reason
  │
  └── FLAG FOR REVIEW → status PENDING_INFO
      → Supervisor reviews
```

### Manage Pre-Authorizations

```
Claims Portal → Pre-Authorizations
    │
    ▼
GET /api/v1/pre-authorizations?status=PENDING
    │
    ▼
Review request → Approve or Reject
  - Approve: set approved amount, expiry date
  - Reject: provide rejection reason
```

### Manage Provider Quotations

```
Claims Portal → Quotations
    │
    ▼
GET /api/v1/quotations/status/PENDING
    │
    ▼
Review → provide coverage estimate
  - Covered amount (what scheme pays)
  - Co-payment amount (what member pays)
  - Notes/conditions
```

---

## 5. Contributions Officer — Billing & Schemes (Angular Portal)

### Login

```
Login via Keycloak (contributions_clerk or contributions_supervisor role)
```

### Manage Schemes

```
Contributions Portal → Schemes
    │
    ▼
View/create schemes:
  - POST /api/v1/schemes (name, type, effective date)
  - Add benefits: POST /api/v1/schemes/benefits (annual limits per category)
  - Add age groups: POST /api/v1/schemes/age-groups (pricing brackets)
    │
    Example:
    Gold Medical Aid
    ├── Benefits: General ($15K), Maternity ($20K), Dental ($5K)
    └── Age Groups: Child 0-18 ($150), Adult 19-29 ($300), Adult 30-39 ($350)
```

### Generate Billing

```
Contributions Portal → Billing
    │
    ▼
Generate monthly billing:
  - Select scheme, period (start/end dates)
  - POST /api/v1/contributions/generate-billing
  - System creates contribution records for all active members
  - Invoices generated for group employers
    │
    ▼
Billing schedule is tenant-configurable:
  - Set via Scheduled Jobs (e.g., 15th of each month)
  - Each tenant can set different billing days
```

### Track Contributions

```
Contributions Portal → Contributions
    │
    ▼
View by status:
  - Pending: awaiting payment
  - Paid: payment received
  - Overdue: past due date (auto-flagged by OverdueCheckExecutor)
    │
    ▼
Record payment:
  POST /api/v1/contributions/{id}/pay
  - paymentMethod: bank_transfer, mobile_money, card
  - paymentReference: bank ref number
```

### Process Scheme Changes

```
Contributions Portal → Scheme Changes
    │
    ▼
GET /api/v1/scheme-changes (status: PENDING)
    │
    ▼
Review member's request:
  "John Doe requests: Bronze → Gold, effective May 1"
    │
    ├── Approve: POST /api/v1/scheme-changes/{id}/approve
    │   → Effective on requested date
    │   → New premium applies from effective date
    │   → Benefits recalculated
    │
    └── Reject: POST /api/v1/scheme-changes/{id}/reject
        → Member notified with reason
```

### Manage Bad Debts

```
Contributions Portal → Bad Debts
    │
    ▼
GET /api/v1/bad-debts
  Overdue contributions automatically flagged by scheduled job
    │
    ├── Write Off: POST /api/v1/bad-debts/{id}/write-off
    │   → Contribution marked as unrecoverable
    │
    └── Mark Recovered: POST /api/v1/bad-debts/{id}/recovered
        → Payment received after write-off
```

### Follow Up on Quotes

```
Contributions Portal → Insurance Quotes
    │
    ▼
View quotes generated by prospects
  - Contact prospects to encourage enrollment
  - Track conversion: GENERATED → enrolled member
```

---

## 6. Finance Officer — Payments & Reporting (Angular Portal)

### Login

```
Login via Keycloak (finance_clerk, finance_hod, or finance_supervisor role)
```

### Create & Execute Payment Runs

```
Finance Portal → Payment Runs
    │
    ▼
Create payment run:
  POST /api/v1/payment-runs
  - Description: "March 2026 Provider Payments"
  - Currency: USD
  - Status: DRAFT
    │
    ▼
Review payment run items:
  - Each item: provider, amount, claims included
  - GET /api/v1/payment-runs/{id}/items
    │
    ▼
Execute payment run:
  POST /api/v1/payment-runs/{id}/execute
  - Status: DRAFT → IN_PROGRESS → COMPLETED
  - Payments created per provider
  - Payment advice generated
  - Providers notified
    │
    ▼
Auto-execution:
  - Tenant configures: weekly Monday 6am (via Scheduled Jobs)
  - Draft runs older than 24h auto-executed
```

### Generate Payment Advice

```
Finance Portal → Reports → Payment Advice
    │
    ▼
GET /api/v1/reports/payment-advice/{paymentRunId}
    │
    ▼
Payment advice document:
  Provider: City Hospital
  Advice #: PA-2026-03-001
  Total: $45,200.00 USD

  Lines:
  | Claim #     | Member    | Claimed  | Approved | Paid     |
  |-------------|-----------|----------|----------|----------|
  | CLM-001234  | John Doe  | $500.00  | $500.00  | $500.00  |
  | CLM-001235  | Jane Doe  | $1,200   | $1,000   | $1,000   |
  | ...         | ...       | ...      | ...      | ...      |
```

### Manage Adjustments

```
Finance Portal → Adjustments
    │
    ▼
Create debit/credit note:
  POST /api/v1/adjustments
  - Type: debit or credit
  - Provider, amount, reason
  - Status: PENDING
    │
    ▼
Approval workflow:
  POST /api/v1/adjustments/{id}/approve → APPROVED
  POST /api/v1/adjustments/{id}/apply → APPLIED (affects provider balance)
```

### Bank Reconciliation

```
Finance Portal → Reconciliation
    │
    ▼
Enter bank statement data:
  POST /api/v1/reconciliations
  - Reference number, statement amount, system amount
  - System computes difference
  - Auto-matches if amounts equal (status: MATCHED)
  - Flags discrepancies (status: UNMATCHED)
    │
    ▼
Resolve discrepancies:
  POST /api/v1/reconciliations/{id}/match
```

### Financial Reports

```
Finance Portal → Reports
    │
    ▼
Available reports:
  - Claims Summary: total/approved/rejected by period
  - Payment Summary: total payments by status, by provider
  - Provider Performance: approval rates, average amounts
  - Contribution Summary: paid vs outstanding, collection rate
```

**API:**
- `GET /api/v1/reports/claims-summary?period=2026-03`
- `GET /api/v1/reports/payment-summary?period=2026-03`
- `GET /api/v1/reports/provider-performance`
- `GET /api/v1/reports/contribution-summary?period=2026-03`

---

## 7. Tenant Admin — Configuration (Angular Portal)

### Login

```
Login via Keycloak (tenant_admin role in tenant realm)
```

### Configuration Areas

```
Admin Portal
    │
    ├── Tenant Settings
    │   Organization name, domain, timezone, branding
    │
    ├── User Management
    │   Create staff accounts (claims clerks, finance officers, etc.)
    │   Assign roles to users
    │   POST /api/v1/roles/assign
    │
    ├── Role Management
    │   View system roles (tenant_admin, claims_clerk, finance_officer, etc.)
    │   Create custom roles with granular permissions
    │   POST /api/v1/roles
    │
    ├── Business Rules
    │   Configure adjudication rules via rules engine
    │   - Waiting periods: 90 days general, 300 days maternity
    │   - Benefit limits per category
    │   - Pre-authorization requirements
    │   - Tariff pricing rules
    │   Rules compiled to Drools DRL at runtime
    │
    ├── Scheduled Jobs
    │   GET /api/v1/scheduled-jobs
    │   Configure per-tenant job schedules:
    │   - Billing cycle: "Run on 15th of each month at 10am"
    │   - Overdue check: "Daily at 2am, grace period 30 days"
    │   - Payment runs: "Weekly Monday 6am, auto-execute after 24h"
    │   - Age processing: "Daily at 4am, max dependant age 21, student 26"
    │   Enable/disable jobs, update cron expressions and settings
    │
    └── Audit Log
        View all entity changes and security events
        GET /api/v1/audit/events?entityType=Member&action=UPDATE
        Filter by entity, action, actor, date range
```

---

## 8. Super Admin — Platform Management (Angular Portal)

### Login

```
Login via Keycloak (medfund-platform realm, super_admin role)
  - Separate from tenant realms
  - No tenant scope — platform-level access
```

### Tenant Management

```
Super Admin Portal
    │
    ▼
Create new tenant (medical aid society):
  POST /api/v1/tenants
  - Name: "Zimbabwe Medical Aid Society"
  - Slug: "zmmas"
  - Contact email, country code, timezone
  - Membership model: GROUP_ONLY / INDIVIDUAL_ONLY / BOTH
    │
    ▼
System provisions tenant:
  1. Creates PostgreSQL schema (tenant_{uuid})
  2. Runs Flyway migrations (V001-V005)
  3. Creates Keycloak realm with default roles
  4. Seeds default business rules
  5. Seeds default scheduled job configs
  6. Publishes TENANT_PROVISIONED Kafka event
    │
    ▼
Manage tenants:
  - View all tenants: GET /api/v1/tenants
  - Suspend tenant: POST /api/v1/tenants/{id}/suspend
  - Activate tenant: POST /api/v1/tenants/{id}/activate
```

### Plan Management

```
Manage subscription plans:
  POST /api/v1/plans
  - Starter: 100 members, 20 providers, $99/month
  - Professional: 1000 members, 100 providers, $499/month
  - Enterprise: unlimited, $999/month
```

---

## 9. Group Liaison — Employer Group Management (Flutter App)

### Dual Role

```
Group liaison has two modes:
    │
    ├── "My Account" (member role)
    │   View own benefits, claims, payments
    │   Same as regular member
    │
    └── "Group Management" (group_liaison role)
        Manage employer group members
        CANNOT see other members' medical data (claims, diagnoses)
```

### Group Management

```
Group Dashboard
    │
    ├── Members
    │   View group member list (name, status, scheme)
    │   Add new employee: POST /api/v1/members (with groupId)
    │   Suspend/terminate employee
    │   Manage dependants for employees
    │
    ├── Billing
    │   View group contribution invoices
    │   GET /api/v1/invoices/group/{groupId}
    │   Pay group invoice: POST /api/v1/pay/initiate
    │
    ├── Reports
    │   Group membership summary
    │   Billing history
    │   Outstanding balance
    │
    └── Schemes
        View available schemes and pricing
        (read-only — cannot modify schemes)
```

### Access Restrictions

```
Group Liaison CAN:
  ✓ View/add/edit/remove group members
  ✓ View/add/remove dependants
  ✓ View group invoices and billing
  ✓ Pay group invoices
  ✓ View schemes (read-only)

Group Liaison CANNOT (403 Forbidden):
  ✗ View any member's claims
  ✗ View diagnosis codes or medical data
  ✗ View adjudication details
  ✗ View benefit balances (usage)
  ✗ View pre-authorizations
  ✗ Access audit logs
```

---

## 10. Lifecycle Summary

### Member Lifecycle

```
PROSPECT
    │ (gets quote)
    ▼
ENROLLED
    │ (admin activates / auto-activate)
    ▼
ACTIVE ◄──────────────────┐
    │                      │
    ├── Claims flow         │ (payment received)
    │   Provider submits    │
    │   → Adjudicated       │
    │   → Paid to provider  │
    │                      │
    ├── Monthly billing     │
    │   → Paid             │
    │   → Overdue ──────────┤
    │   → Bad debt         │
    │                      │
    ├── Scheme change       │
    │   → Approved          │
    │   → New scheme active │
    │                      │
    ├── SUSPENDED ──────────┘
    │   (contributions in arrears)
    │
    └── TERMINATED
        (leaves scheme)
```

### Claim Lifecycle

```
DRAFT → SUBMITTED → VERIFIED → IN_ADJUDICATION
                                    │
                    ┌───────────────┼───────────────┐
                    ▼               ▼               ▼
               ADJUDICATED     REJECTED      PENDING_INFO
                    │                          (more info needed)
                    ▼
               COMMITTED
                    │
                    ▼
                  PAID
```

### Quotation Lifecycle

```
Insurance Quote:     GENERATED → [prospect enrolls] → CONVERTED
                     GENERATED → [30 days pass] → EXPIRED

Provider Quotation:  PENDING → REVIEWED (coverage set) → APPROVED/REJECTED
                     PENDING → [no review] → EXPIRED

Pre-Authorization:   PENDING → APPROVED (amount + expiry) → [used in claim]
                     PENDING → REJECTED
                     APPROVED → [expiry date passes] → EXPIRED
```

### Contribution Lifecycle

```
PENDING → PAID
PENDING → OVERDUE → BAD_DEBT → WRITTEN_OFF
                             → RECOVERED
```

### Scheme Change Lifecycle

```
PENDING → APPROVED → EFFECTIVE (on requested date)
PENDING → REJECTED
```

---

## API Endpoint Summary

| Domain | Base Path | Auth | Description |
|--------|-----------|------|-------------|
| Insurance Quotes | `/api/v1/quotes` | Public | Self-service pricing |
| Members | `/api/v1/members` | JWT | Member CRUD + lifecycle |
| Dependants | `/api/v1/dependants` | JWT | Member dependant management |
| Providers | `/api/v1/providers` | JWT | Provider onboarding |
| Claims | `/api/v1/claims` | JWT | Claim submission + adjudication |
| Drug Claims | `/api/v1/drug-claims` | JWT | Pharmaceutical claims |
| Pre-Auth | `/api/v1/pre-authorizations` | JWT | Pre-authorization workflow |
| Quotations | `/api/v1/quotations` | JWT | Provider cost estimates |
| Schemes | `/api/v1/schemes` | JWT | Scheme + benefit management |
| Contributions | `/api/v1/contributions` | JWT | Billing + payments |
| Invoices | `/api/v1/invoices` | JWT | Group invoicing |
| Scheme Changes | `/api/v1/scheme-changes` | JWT | Scheme switch requests |
| Bad Debts | `/api/v1/bad-debts` | JWT | Overdue tracking |
| Payments | `/api/v1/payments` | JWT | Provider payments |
| Payment Runs | `/api/v1/payment-runs` | JWT | Batch payment execution |
| Adjustments | `/api/v1/adjustments` | JWT | Debit/credit notes |
| Reconciliation | `/api/v1/reconciliations` | JWT | Bank matching |
| Reports | `/api/v1/reports` | JWT | Financial reporting |
| Tariffs | `/api/v1/tariffs` | JWT | Tariff schedule management |
| ICD-10 | `/api/v1/icd-codes` | JWT | Diagnosis code registry |
| Roles | `/api/v1/roles` | JWT | Role/permission management |
| Scheduled Jobs | `/api/v1/scheduled-jobs` | JWT | Tenant job config |
| Tenants | `/api/v1/tenants` | JWT | Platform tenant management |
| Plans | `/api/v1/plans` | JWT | Subscription plans |
| Audit | `/api/v1/audit` | JWT | Audit log queries |
| Notifications | `/api/v1/notifications` | JWT | Send notifications |
| Files | `/api/v1/files` | JWT | Upload/download/export |
| Payments (Gateway) | `/api/v1/pay` | JWT | Payment processing |
| AI Adjudication | `/api/v1/ai/adjudication` | JWT | AI recommendations |
| AI Fraud | `/api/v1/ai/fraud` | JWT | Fraud detection |
| AI OCR | `/api/v1/ai/ocr` | JWT | Document extraction |
| AI Chat | `/api/v1/ai/chat` | JWT | Member chatbot |
| AI Analytics | `/api/v1/ai/analytics` | JWT | Forecasting + anomalies |
