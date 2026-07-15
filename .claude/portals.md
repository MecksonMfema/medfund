# Portals & Roles

## Overview

InsureFlow has five distinct portal experiences, each serving a different audience. The Angular web application hosts four of these as route groups (super admin, tenant admin, operations, provider). The Flutter application serves two audiences (members and providers) on both mobile and web.

> Language note: many role and permission descriptions in this doc use "medical aid" and "healthcare providers" because the health vertical is the currently implemented one. The portal shapes, role definitions, and RBAC design are line-agnostic — a life or funeral tenant uses the same tenant-admin/operations/provider/member portal structure with line-appropriate features enabled. "Provider" is a service provider generally (hospital, clinic, garage, funeral home, travel agent) depending on the tenant's active insurance lines.

## Portal Map

```
┌────────────────────────────────────────────────────────────────────┐
│                     ANGULAR WEB APPLICATION                        │
│                                                                    │
│  ┌────────────────┐  ┌────────────────┐  ┌──────────────────────┐  │
│  │ SUPER ADMIN    │  │ TENANT ADMIN   │  │ OPERATIONS           │  │
│  │ /super-admin/* │  │ /admin/*       │  │ /claims/*            │  │
│  │                │  │                │  │ /finance/*           │  │
│  │ Platform-wide  │  │ Per-tenant     │  │ /contributions/*     │  │
│  │ management     │  │ configuration  │  │ /tickets/*           │  │
│  └────────────────┘  └────────────────┘  └──────────────────────┘  │
│                                                                    │
│  ┌─────────────────────────────────────────────────────────────┐   │
│  │ PROVIDER PORTAL                                             │   │
│  │ /providers/*                                                │   │
│  │                                                             │   │
│  │ Claim submission, payment tracking, multi-tenant access     │   │
│  └─────────────────────────────────────────────────────────────┘   │
└────────────────────────────────────────────────────────────────────┘

┌────────────────────────────────────────────────────────────────────┐
│               FLUTTER APPLICATION (Mobile + Web)                   │
│                                                                    │
│  ┌──────────────────────────────┐  ┌───────────────────────────┐   │
│  │ MEMBER PORTAL                │  │ PROVIDER PORTAL           │   │
│  │                              │  │ (mobile companion)        │   │
│  │ Benefits, claims, payments,  │  │                           │   │
│  │ profile, chat, digital card  │  │ Quick claim submission,   │   │
│  │                              │  │ member verification,      │   │
│  │ iOS + Android + Web (PWA)    │  │ payment notifications     │   │
│  └──────────────────────────────┘  └───────────────────────────┘   │
└────────────────────────────────────────────────────────────────────┘
```

---

## 1. Super Admin Portal (Angular: `/super-admin/*`)

**Audience**: InsureFlow platform operators. These are the people who run the SaaS platform itself.

**Keycloak Realm**: `medfund-platform` (separate from tenant realms)

**Role**: `super_admin`

### Pages & Features

| Route | Page | Description |
|-------|------|-------------|
| `/super-admin/dashboard` | Platform Dashboard | Total tenants, total members, total claims, revenue, system health |
| `/super-admin/tenants` | Tenant List | All tenants with status, plan, member count, last activity |
| `/super-admin/tenants/new` | Create Tenant | Provision new tenant (name, domain, plan, admin user, initial config) |
| `/super-admin/tenants/:id` | Tenant Detail | Tenant health metrics, configuration, usage, feature flags |
| `/super-admin/tenants/:id/impersonate` | Impersonate | Log into tenant admin portal as the tenant admin (for support) |
| `/super-admin/plans` | Plan Management | Create/edit subscription tiers (member limits, features, pricing) |
| `/super-admin/features` | Feature Flags | Enable/disable features globally or per-tenant |
| `/super-admin/currencies` | Currency Management | ISO 4217 currency registry, global exchange rate sources |
| `/super-admin/exchange-rates` | Exchange Rates | View/override exchange rates, configure auto-fetch schedules |
| `/super-admin/system-health` | System Health | Service status, Kafka lag, DB connections, error rates |
| `/super-admin/audit` | Platform Audit Log | Cross-tenant audit events for platform-level actions |
| `/super-admin/analytics` | Platform Analytics | Cross-tenant aggregated metrics (no tenant-specific PHI) |
| `/super-admin/users` | Platform Users | Manage super admin users |
| `/super-admin/notifications` | System Notifications | Broadcast announcements to all tenants |

---

## 2. Tenant Admin Portal (Angular: `/admin/*`)

**Audience**: Administrators of a specific medical aid society / insurance fund.

**Keycloak Realm**: `tenant-{slug}` (per-tenant realm)

**Role**: `tenant_admin`

### Pages & Features

| Route | Page | Description |
|-------|------|-------------|
| `/admin/dashboard` | Tenant Dashboard | Member count, active claims, contribution collections, financial summary |
| `/admin/settings` | Tenant Settings | Organization name, logo, colors, contact info, timezone |
| `/admin/settings/domain` | Custom Domain | Configure custom domain (e.g., `portal.zmmas.co.zw`) |
| `/admin/users` | User Management | Create/manage staff users, assign roles |
| `/admin/roles` | Role Management | View all custom and system roles with their permission summary |
| `/admin/roles/new` | Create Role | Custom role builder: select portal sections, toggle read/write/approve per section |
| `/admin/roles/:id` | Edit Role | Modify permissions, view users assigned to this role |
| `/admin/roles/:id/users` | Role Members | Assign/unassign users to this role |
| `/admin/users/:id/roles` | User Roles | View/edit all roles assigned to a specific user, see effective permission summary |
| `/admin/membership-model` | Membership Model | Configure GROUP_ONLY, INDIVIDUAL_ONLY, or BOTH; set individual registration approval rules |
| `/admin/schemes` | Scheme Management | Create/edit benefit schemes, pricing, benefits, age groups |
| `/admin/rules` | Business Rules | Configure adjudication rules, billing rules, waiting periods, exclusions |
| `/admin/rules/test` | Rule Testing | Dry-run rules against sample claims to validate behavior |
| `/admin/currencies` | Currency Config | Select supported currencies, set default, configure exchange rate source |
| `/admin/providers` | Provider Network | Manage in-network providers, invite new providers |
| `/admin/templates` | Notification Templates | Customize email/SMS templates with tenant branding |
| `/admin/ai-config` | AI Settings | Auto-adjudication thresholds, enable/disable AI features |
| `/admin/audit` | Audit Log | All actions within the tenant (who did what, when) |
| `/admin/reports` | Reports | Member stats, claim trends, financial summaries |
| `/admin/data-export` | Data Export | GDPR-compliant member data export |
| `/admin/billing` | Platform Billing | View InsureFlow subscription usage and invoices |

---

## 3. Operations Portal (Angular: `/claims/*`, `/finance/*`, `/contributions/*`)

**Audience**: Day-to-day operational staff of the medical aid society.

**Keycloak Realm**: `tenant-{slug}`

### Claims Operations (`/claims/*`)

**Roles**: `claims_clerk`, `adjudicator`, `claims_supervisor`

| Route | Page | Description |
|-------|------|-------------|
| `/claims/dashboard` | Claims Dashboard | Pending, approved, rejected counts. AI recommendation accuracy |
| `/claims/queue` | Adjudication Queue | Claims awaiting review, sorted by priority/AI confidence |
| `/claims/queue/:id` | Adjudication Workspace | Claim details + AI recommendation + reasoning + similar claims + member history. Approve/reject/modify actions |
| `/claims/submissions` | All Claims | Searchable list of all claims with filters (status, date, provider, member) |
| `/claims/submissions/:id` | Claim Detail | Full claim view with timeline, documents, adjudication history |
| `/claims/pre-auth` | Pre-Authorizations | Pending pre-auth requests, approve/reject |
| `/claims/tariffs` | Tariff Management | View/edit tariff codes and rates |
| `/claims/drug-claims` | Drug Claims | Drug-specific claim queue and management |
| `/claims/rejections` | Rejection Reasons | Manage rejection reason codes |
| `/claims/reports` | Claims Reports | Approval rate, average processing time, top rejection reasons, provider analysis |
| `/claims/fraud-flags` | Fraud Alerts | AI-flagged suspicious claims and patterns |

### Finance Operations (`/finance/*`)

**Roles**: `finance_clerk`, `finance_hod`, `finance_supervisor`

| Route | Page | Description |
|-------|------|-------------|
| `/finance/dashboard` | Finance Dashboard | Pending payments, balances, cash flow summary (live via Elixir WebSocket) |
| `/finance/payments` | Payment List | All payments with status filters, currency filters |
| `/finance/payment-runs` | Payment Runs | Create/execute batch payment runs |
| `/finance/payment-runs/new` | New Payment Run | Select claims to include, preview amounts, execute |
| `/finance/payment-runs/:id` | Payment Run Detail | Payments in run, totals by currency, status |
| `/finance/provider-balances` | Provider Balances | Outstanding balances per provider per currency |
| `/finance/member-refunds` | Member Refunds | Process refunds to members |
| `/finance/adjustments` | Adjustments | Create payment adjustments (linked to tickets) |
| `/finance/bank-reconciliation` | Bank Reconciliation | Match bank statements against recorded transactions |
| `/finance/debit-notes` | Debit Notes | Issue and manage debit notes |
| `/finance/credit-notes` | Credit Notes | Issue and manage credit notes |
| `/finance/reports` | Financial Reports | Exportable reports (P&L, balance sheet, provider aging, payment summary) |
| `/finance/forecasting` | AI Forecasting | Cash flow predictions, reserve adequacy (AI-generated) |

### Contributions Operations (`/contributions/*`)

**Roles**: `contributions_clerk`, `contributions_supervisor`

| Route | Page | Description |
|-------|------|-------------|
| `/contributions/dashboard` | Contributions Dashboard | Collection rate, outstanding amounts, upcoming billing cycles |
| `/contributions/billing-runs` | Billing Runs | Generate monthly/quarterly contribution invoices |
| `/contributions/billing-runs/:id` | Billing Run Detail | Member contributions breakdown, group totals |
| `/contributions/groups` | Group Management | View/manage employer groups, balances, contact details |
| `/contributions/groups/:id` | Group Detail | Group members, contribution history, balance, transactions |
| `/contributions/members` | Member Management | Member list with scheme, status, balance, membership type (group/individual) |
| `/contributions/members/:id` | Member Detail | Full member profile, dependants, scheme, benefits, contribution history |
| `/contributions/members/pending` | Pending Registrations | Individual self-registrations awaiting verification (if tenant requires approval) |
| `/contributions/individual-billing` | Individual Billing | Billing run for individual members (separate from group billing) |
| `/contributions/transactions` | Transactions | Record payments, view transaction history |
| `/contributions/invoices` | Invoices | Generate/send contribution invoices |
| `/contributions/scheme-changes` | Scheme Changes | Process member scheme upgrades/downgrades |
| `/contributions/bad-debts` | Bad Debts | Track and manage unpaid contributions |
| `/contributions/reports` | Contribution Reports | Collection rates, aging analysis, group compliance |

### Tickets (`/tickets/*`)

**Roles**: all staff roles

| Route | Page | Description |
|-------|------|-------------|
| `/tickets/list` | Ticket List | Internal support tickets |
| `/tickets/:id` | Ticket Detail | Ticket conversation, linked adjustments/claims |
| `/tickets/new` | Create Ticket | New internal ticket |

---

## 4. Provider Portal

### Angular Web (`/providers/*`)

**Audience**: Healthcare providers (hospitals, clinics, pharmacies, opticians).

**Keycloak Realm**: Each provider has accounts in every tenant realm they serve. A provider user can belong to multiple tenant realms. The Angular app shows a **tenant switcher** in the header.

**Role**: `provider`, `provider_admin`

| Route | Page | Description |
|-------|------|-------------|
| `/providers/dashboard` | Provider Dashboard | Summary across all medical aids: pending claims, recent payments, total outstanding |
| `/providers/tenant-selector` | Medical Aid Selector | Switch between medical aids the provider serves |
| `/providers/claims/new` | Submit Claim | Submit a new claim against the selected medical aid |
| `/providers/claims` | My Claims | All claims submitted, filterable by medical aid, status, date |
| `/providers/claims/:id` | Claim Detail | Claim status, adjudication outcome, AI explanation (if available) |
| `/providers/pre-auth/new` | Request Pre-Auth | Submit pre-authorization request |
| `/providers/pre-auth` | Pre-Authorizations | View pre-auth requests and outcomes |
| `/providers/payments` | Payment History | Payments received per medical aid, download remittance advice |
| `/providers/balance` | Outstanding Balance | Balance owed by each medical aid, by currency |
| `/providers/members/verify` | Member Verification | Verify member eligibility (scan QR or enter member ID) |
| `/providers/tariffs` | Tariff Lookup | Search tariff codes and rates per medical aid |
| `/providers/insights` | AI Insights | Claim approval rate, common rejections, processing time trends, peer benchmarking |
| `/providers/documents` | Documents | Upload/download supporting documents |
| `/providers/profile` | Provider Profile | Banking details, tax clearance, practice info |
| `/providers/staff` | Staff Management | Manage provider staff users (receptionist, billing clerk) |

### Flutter Mobile (Provider Mode)

The Flutter app has a **provider mode** (switchable from member mode, or dedicated provider app variant).

| Feature | Description |
|---------|-------------|
| Quick Claim Submission | Camera-based document capture, OCR auto-fill, submit claim |
| Member Verification | Scan QR code from member's digital card, verify eligibility instantly |
| Payment Notifications | Push notifications when payments are processed |
| Claim Status | Track submitted claims in real-time |
| Balance Overview | Outstanding amounts across medical aids |

---

## 5. Group Liaison Portal (Flutter Mobile + Web)

**Audience**: Corporate/employer group account managers (HR officers, company administrators) who manage their organization's medical aid membership.

**Keycloak Realm**: `tenant-{slug}`

**Role**: `group_liaison` (assigned **in addition to** `member` role)

**Important**: Group liaisons are themselves members of the medical aid. They have a dual role — as a `member` they can see their own benefits, claims, and medical data. As a `group_liaison` they can manage the group's members and billing but **NEVER** see other members' medical/claims data. The Flutter app provides a **role switcher** to toggle between "My Account" (member view) and "Group Management" (liaison view).

**Key constraint**: The `group_liaison` permission set grants access to member admin and billing but **NEVER** to medical/claims data (PHI) of other members. This is enforced at the API level — the Claims Service rejects all requests made in group_liaison context for other members' data.

### What Group Liaisons CAN See

| Data | Access | Rationale |
|------|--------|-----------|
| Group profile and settings | Read/Write | They manage the corporate account |
| Member list (names, ID numbers, scheme, status, dependants) | Read/Write | They enroll and manage members |
| Dependant list per member (names, relationship, age) | Read/Write | They manage dependants for the group |
| Group contribution invoices and billing history | Read | They are responsible for paying the bill |
| Group balance (outstanding, overpaid) | Read | They need to know what they owe |
| Payment history (contribution payments made) | Read | Track what they've paid |
| Member contribution breakdown (how much per member) | Read | Understand the bill composition |
| Scheme details and pricing | Read | Know what plans are available for their employees |

### What Group Liaisons CANNOT See

| Data | Access | Reason |
|------|--------|--------|
| Claims (any claim data) | **BLOCKED** | Medical privacy — PHI |
| Adjudication details | **BLOCKED** | Medical privacy |
| Diagnosis codes, tariff codes | **BLOCKED** | Medical privacy |
| Member benefit balances (usage) | **BLOCKED** | Reveals medical utilization |
| Pre-authorizations | **BLOCKED** | Medical privacy |
| Provider visit history | **BLOCKED** | Medical privacy |
| Chat messages | **BLOCKED** | Private member communications |

### Flutter Role Switcher

Since group liaisons are also members, the Flutter app shows a **role switcher** in the app header/drawer:

```
┌────────────────────────────┐
│  ☰  InsureFlow                │
│                            │
│  ┌──────────────────────┐  │
│  │ 👤 John Doe          │  │
│  │                      │  │
│  │ ○ My Account         │  │  ← Member view (benefits, claims, payments)
│  │ ● Group Management   │  │  ← Liaison view (members, billing, enrollment)
│  │                      │  │
│  │ ACME Corp            │  │
│  └──────────────────────┘  │
│                            │
│  [Dashboard]               │  ← Changes based on active role
│  [Members]                 │
│  [Bills]                   │
│  ...                       │
└────────────────────────────┘
```

- **"My Account"** mode: Shows the standard member portal (dashboard, benefits, claims, payments, profile, chat, digital card)
- **"Group Management"** mode: Shows the group liaison portal (group dashboard, members, bills, payments, enrollment)
- The active mode determines which API context is used — member requests use the user's member_id, group requests use the group_id
- If a user is a liaison for multiple groups (rare), a group selector appears under Group Management

### Screens (Group Management Mode)

| Screen | Features |
|--------|----------|
| **Dashboard** | Group summary: total members, active/suspended count, outstanding balance, next billing date |
| **Members** | Full member list with search/filter. Status (active, suspended, terminated). View member details (personal info, scheme, dependants — no medical data) |
| **Add Member** | Enroll new employee: personal details, scheme selection, dependants, effective date |
| **Edit Member** | Update member details, add/remove dependants, request scheme change |
| **Terminate Member** | Terminate a member (with effective date, e.g., employee left the company) |
| **Suspend/Reinstate** | Suspend a member (e.g., unpaid leave) or reinstate a suspended member |
| **Bills** | View group contribution invoices, breakdown per member, download invoice PDF |
| **Pay Bill** | Pay outstanding invoice via EcoCash, card, bank transfer (Payment Gateway) |
| **Payment History** | Past payments with receipts, payment method, date |
| **Auto-Pay** | Set up recurring contribution payments for the group |
| **Schemes** | View available schemes and pricing, request scheme changes for members |
| **Reports** | Group membership report (headcount by scheme, age group, status), billing summary |
| **Profile** | Group details (company name, address, banking details), liaison contact info |
| **Notifications** | Billing reminders, payment confirmations, member enrollment confirmations |

### API Enforcement

The permission model ensures data isolation at the service level:

```
group_liaison role permissions:
  members:read, members:create, members:update, members:suspend, members:terminate
  dependants:read, dependants:create, dependants:update, dependants:remove
  groups:read, groups:update (own group only)
  contributions:read (own group only)
  invoices:read (own group only)
  transactions:read (own group only)
  schemes:read
  payments:create (own group invoices only)

  # Explicitly DENIED (not just missing — actively rejected with 403):
  claims:* — DENIED
  adjudications:* — DENIED
  pre_authorizations:* — DENIED
  benefit_balances:* — DENIED
  audit:* — DENIED (except own group member enrollment actions)
```

The Claims Service, Finance Service (claim-related endpoints), and AI Service validate the caller's role and return `403 Forbidden` if a `group_liaison` attempts to access any medical data.

---

## 6. Member Portal (Flutter Mobile + Web)

**Audience**: Insurance members (corporate employees AND individual subscribers) and their dependants.

**Keycloak Realm**: `tenant-{slug}` (same realm as tenant staff, different role)

**Role**: `member`

### Membership Models

The member experience differs based on the tenant's `membership_model` setting:

| Feature | Group/Corporate Member | Individual Member |
|---------|----------------------|-------------------|
| **Enrollment** | Enrolled by group liaison or HR via admin portal | Self-registers via Flutter app or public signup page |
| **Scheme selection** | Assigned by employer group | Chooses own scheme during registration |
| **Contribution billing** | Billed to employer group (payroll deduction) | Billed directly to individual (pays via app) |
| **Payment** | Group pays on behalf (member sees bills but doesn't pay) | Pays directly via EcoCash, card, bank transfer |
| **Group admin features** | No access to group management | N/A — no group |
| **Scheme changes** | Requested, approved by group liaison | Self-service (subject to waiting periods) |
| **Dependant management** | May require group liaison approval | Self-service |

### Individual Self-Registration Flow

```
Prospect opens Flutter app or visits tenant's public signup page
   │
   ▼
Select scheme → View benefits and pricing → Continue
   │
   ▼
Fill registration form:
  - Personal details (name, ID number, date of birth, gender, contact)
  - Dependants (optional, can add later)
  - Banking details (for refunds)
  - Upload ID copy
   │
   ▼
Select payment method → Pay first contribution
   │
   ▼
Payment Gateway processes payment
   │
   ▼
On success:
  ├── User Service creates member (status: PENDING_VERIFICATION or ACTIVE per tenant config)
  ├── Keycloak account created, welcome email sent
  ├── Waiting periods start from enrollment date
  ├── Digital membership card issued
  └── If tenant requires verification: admin reviews and activates
```

### Screens

| Feature | Screen | Description |
|---------|--------|-------------|
| **Dashboard** | Home | Benefits summary, balance overview, recent claims, notifications |
| **Benefits** | Benefits | Scheme details, benefit categories, limits, remaining balance per benefit |
| **Claims** | Claims List | Full claim history with status (submitted → verified → adjudicated → paid) |
| **Claim Detail** | Claim Detail | Provider, diagnosis, amounts, adjudication outcome, AI explanation |
| **Payments** | Payments | Payment/refund history, download payment advice PDF |
| **Bills** | Contributions | Contribution invoices, outstanding balance, **"Pay Now" button** (individual members) |
| **Auto-Pay** | Auto-Pay Setup | Set up recurring contribution payments (individual members only) |
| **Dependants** | Dependants | View/manage dependants, their benefits and claims |
| **Scheme** | My Scheme | Current scheme details; **"Change Scheme"** button (individual members — self-service upgrade/downgrade) |
| **Profile** | Profile | Personal info, banking details, contact info, change password |
| **Digital Card** | Membership Card | QR code for provider verification, scheme info, member number |
| **Chat** | Support Chat | AI-assisted chatbot for FAQs + escalation to human agent |
| **Notifications** | Notifications | Push notification history (claim updates, payment confirmations) |
| **Documents** | Documents | Upload ID copies, download statements |

---

## Role Hierarchy & Permissions

### Platform Level
```
super_admin
└── Full platform access: tenant CRUD, feature flags, system config, impersonation
```

### Tenant Level
```
tenant_admin
├── Full tenant access: settings, users, roles, rules, schemes, AI config
│
├── claims_supervisor
│   └── claims_clerk / adjudicator
│       - View and adjudicate claims
│       - Manage pre-authorizations
│       - Override AI decisions
│
├── finance_supervisor
│   └── finance_clerk / finance_hod
│       - Process payments, payment runs
│       - Manage adjustments, notes
│       - View financial reports
│
├── contributions_supervisor
│   └── contributions_clerk
│       - Run billing cycles
│       - Manage members, groups
│       - Record transactions
│
├── provider (external)
│   └── provider_admin
│       - Submit claims, request pre-auth
│       - View payments, balances
│       - Manage provider staff
│
├── group_liaison (external, always combined with member role)
│   - View/manage group members and dependants
│   - View group bills, pay invoices
│   - Enroll/terminate/suspend members
│   - NO access to other members' claims, medical data, or PHI
│   - Flutter app shows role switcher: "My Account" ↔ "Group Management"
│
└── member (external)
    - View own benefits, claims, payments
    - Manage dependants, profile
    - Use chat support
```

### Permission Model (Granular RBAC with Custom Roles)

The platform uses a **fine-grained permission system** where tenant admins can create custom roles that grant access to specific portal sections with explicit read or write access. A single user can be assigned multiple roles, giving them cross-portal access as needed.

#### Permission Structure

Every permission follows the format: `{portal_section}:{action}`

Actions are:
- `read` — View data (read-only access to the section)
- `write` — Create and update data
- `delete` — Remove/terminate/suspend records
- `approve` — Authorize actions (adjudication decisions, payment run approvals, etc.)
- `export` — Download reports, PDFs, CSVs
- `configure` — Change settings, rules, configuration

#### Full Permission Registry

```
# Claims Portal
claims.queue:read                    — View adjudication queue
claims.queue:approve                 — Adjudicate (approve/reject) claims
claims.queue:override_ai             — Override AI recommendations
claims.submissions:read              — View all submitted claims
claims.submissions:write             — Edit claim details
claims.pre_auth:read                 — View pre-authorization requests
claims.pre_auth:approve              — Approve/deny pre-authorizations
claims.tariffs:read                  — View tariff codes and schedules
claims.tariffs:write                 — Create/edit tariff codes
claims.drug_claims:read              — View drug claims
claims.drug_claims:approve           — Adjudicate drug claims
claims.rejections:read               — View rejection reasons
claims.rejections:write              — Manage rejection reason codes
claims.fraud_flags:read              — View AI-flagged fraud alerts
claims.reports:read                  — View claims reports
claims.reports:export                — Export claims reports

# Finance Portal
finance.dashboard:read               — View finance dashboard
finance.payments:read                — View payment records
finance.payments:write               — Record manual payments
finance.payment_runs:read            — View payment runs
finance.payment_runs:write           — Create payment runs
finance.payment_runs:approve         — Approve/execute payment runs
finance.provider_balances:read       — View provider balances
finance.adjustments:read             — View adjustments
finance.adjustments:write            — Create adjustments
finance.adjustments:approve          — Approve adjustments
finance.debit_notes:read             — View debit notes
finance.debit_notes:write            — Create/edit debit notes
finance.credit_notes:read            — View credit notes
finance.credit_notes:write           — Create/edit credit notes
finance.reconciliation:read          — View reconciliation
finance.reconciliation:write         — Perform reconciliation
finance.reports:read                 — View finance reports
finance.reports:export               — Export finance reports
finance.forecasting:read             — View AI financial forecasts
finance.payouts:read                 — View outbound payouts
finance.payouts:write                — Initiate payouts
finance.payouts:approve              — Approve payouts (dual approval)

# Contributions Portal
contributions.dashboard:read         — View contributions dashboard
contributions.billing_runs:read      — View billing runs
contributions.billing_runs:write     — Create/execute billing runs
contributions.groups:read            — View employer groups
contributions.groups:write           — Create/edit groups
contributions.members:read           — View member list and details
contributions.members:write          — Create/edit members
contributions.members:delete         — Terminate/suspend members
contributions.dependants:read        — View dependants
contributions.dependants:write       — Add/edit dependants
contributions.transactions:read      — View transactions
contributions.transactions:write     — Record transactions
contributions.invoices:read          — View invoices
contributions.invoices:write         — Generate invoices
contributions.schemes:read           — View schemes
contributions.schemes:write          — Create/edit schemes
contributions.scheme_changes:read    — View scheme changes
contributions.scheme_changes:approve — Approve scheme changes
contributions.bad_debts:read         — View bad debts
contributions.reports:read           — View contribution reports
contributions.reports:export         — Export contribution reports

# Tenant Admin Portal
admin.settings:read                  — View tenant settings
admin.settings:configure             — Change tenant settings
admin.users:read                     — View staff users
admin.users:write                    — Create/edit users
admin.users:delete                   — Deactivate users
admin.roles:read                     — View roles and permissions
admin.roles:configure                — Create/edit roles, assign permissions
admin.rules:read                     — View business rules
admin.rules:configure                — Create/edit business rules
admin.rules:approve                  — Activate/deactivate rules
admin.schemes:read                   — View scheme configuration
admin.schemes:configure              — Configure schemes and benefits
admin.currencies:read                — View currency configuration
admin.currencies:configure           — Change currency settings
admin.providers:read                 — View provider network
admin.providers:write                — Manage provider network
admin.templates:read                 — View notification templates
admin.templates:configure            — Edit notification templates
admin.ai:read                        — View AI configuration
admin.ai:configure                   — Change AI settings/thresholds
admin.branding:read                  — View branding settings
admin.branding:configure             — Change branding/UI customization
admin.membership_model:read          — View membership model
admin.membership_model:configure     — Change membership model
admin.payments_config:read           — View payment provider config
admin.payments_config:configure      — Change payment provider settings
admin.audit:read                     — View tenant audit log
admin.billing:read                   — View platform subscription/billing

# Tickets
tickets:read                         — View support tickets
tickets:write                        — Create/edit tickets
tickets:assign                       — Assign tickets to staff

# Audit (read-only by design)
audit.entity:read                    — View entity change history
audit.security:read                  — View security events
```

#### Custom Role Builder

Tenant admins create custom roles via **`/admin/roles`** in the Angular web app:

```
┌─────────────────────────────────────────────────────────────┐
│  Create Role                                                 │
│                                                              │
│  Role Name: [ Operations Manager              ]              │
│  Description: [ View across claims and finance, approve...] │
│                                                              │
│  ┌─────────────────────────────────────────────────────┐    │
│  │ Claims Portal                           [▼ Expand]  │    │
│  │                                                     │    │
│  │  □ Adjudication Queue     ☑ Read  □ Approve         │    │
│  │  ☑ All Claims             ☑ Read  □ Write           │    │
│  │  □ Pre-Authorizations     ☑ Read  □ Approve         │    │
│  │  □ Tariff Management      ☑ Read  □ Write           │    │
│  │  □ Drug Claims            ☑ Read  □ Approve         │    │
│  │  □ Fraud Flags            ☑ Read                    │    │
│  │  ☑ Claims Reports         ☑ Read  ☑ Export          │    │
│  └─────────────────────────────────────────────────────┘    │
│  ┌─────────────────────────────────────────────────────┐    │
│  │ Finance Portal                          [▼ Expand]  │    │
│  │                                                     │    │
│  │  ☑ Dashboard              ☑ Read                    │    │
│  │  ☑ Payments               ☑ Read  □ Write           │    │
│  │  ☑ Payment Runs           ☑ Read  □ Write □ Approve │    │
│  │  ☑ Provider Balances      ☑ Read                    │    │
│  │  □ Adjustments            ☑ Read  □ Write □ Approve │    │
│  │  ☑ Finance Reports        ☑ Read  ☑ Export          │    │
│  │  ☑ Forecasting            ☑ Read                    │    │
│  └─────────────────────────────────────────────────────┘    │
│  ┌─────────────────────────────────────────────────────┐    │
│  │ Contributions Portal                   [▼ Expand]   │    │
│  │  ...                                                │    │
│  └─────────────────────────────────────────────────────┘    │
│  ┌─────────────────────────────────────────────────────┐    │
│  │ Admin Portal                            [▼ Expand]  │    │
│  │  ...                                                │    │
│  └─────────────────────────────────────────────────────┘    │
│                                                              │
│  [ Cancel ]                              [ Save Role ]       │
└─────────────────────────────────────────────────────────────┘
```

#### Default Roles (Seeded per Tenant)

These are pre-configured when a tenant is provisioned. Tenant admins can modify them or create new ones.

| Default Role | Portal Access | Permissions Summary |
|-------------|---------------|---------------------|
| `tenant_admin` | All portals | Full read/write/approve/configure on everything |
| `claims_clerk` | Claims | Read + approve on claims queue, read on submissions, pre-auth, tariffs |
| `claims_supervisor` | Claims | Everything `claims_clerk` has + override AI, manage tariffs, manage rejections |
| `finance_clerk` | Finance | Read on all finance sections, write on payments and adjustments |
| `finance_hod` | Finance | Everything `finance_clerk` has + approve payment runs, approve payouts |
| `finance_supervisor` | Finance + Claims (read-only) | Full finance access + read-only claims view (to verify before payment) |
| `contributions_clerk` | Contributions | Read/write on billing, members, groups, transactions, invoices |
| `contributions_supervisor` | Contributions + Finance (read-only) | Full contributions access + read-only finance payments view |
| `operations_manager` | Claims + Finance + Contributions (read-only) | Read + export across all operational portals, no write access |
| `auditor` | All portals (read-only) + Audit | Read-only on everything + full audit log access + export |

#### Assigning Roles to Users

A user can have **multiple roles**. Their effective permissions are the **union** of all assigned roles.

Example: A user assigned both `claims_clerk` and `finance_clerk` roles can:
- Access the Claims portal (adjudicate claims)
- Access the Finance portal (record payments)
- See both portals in their navigation sidebar

The Angular app dynamically shows/hides navigation items and portal sections based on the user's effective permissions.

#### Database Schema

```sql
-- Custom roles (per-tenant schema)
CREATE TABLE roles (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name            VARCHAR(100) NOT NULL,
    description     TEXT,
    is_system       BOOLEAN NOT NULL DEFAULT FALSE,  -- true for default seeded roles (can be modified but not deleted)
    is_active       BOOLEAN NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by      UUID NOT NULL,
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_by      UUID NOT NULL,

    UNIQUE(name)
);

-- Permission assignments per role
CREATE TABLE role_permissions (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    role_id         UUID NOT NULL REFERENCES roles(id) ON DELETE CASCADE,
    permission      VARCHAR(100) NOT NULL,   -- 'claims.queue:read', 'finance.payments:approve', etc.

    UNIQUE(role_id, permission)
);

-- User-role assignments (many-to-many)
CREATE TABLE user_roles (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID NOT NULL REFERENCES users(id),
    role_id         UUID NOT NULL REFERENCES roles(id),
    assigned_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    assigned_by     UUID NOT NULL,

    UNIQUE(user_id, role_id)
);

-- Indexes
CREATE INDEX idx_role_permissions_role ON role_permissions(role_id);
CREATE INDEX idx_user_roles_user ON user_roles(user_id);
CREATE INDEX idx_user_roles_role ON user_roles(role_id);
```

#### How Permissions Are Enforced

**1. JWT Token** — When a user logs in, Keycloak includes their effective permissions in the token (via a custom protocol mapper that queries the role/permission tables):

```json
{
  "permissions": [
    "claims.queue:read",
    "claims.submissions:read",
    "claims.reports:read",
    "claims.reports:export",
    "finance.dashboard:read",
    "finance.payments:read",
    "finance.reports:read",
    "finance.reports:export"
  ]
}
```

**2. API Gateway (Go)** — Checks the permission claim against the requested endpoint before forwarding:

```go
// Permission middleware — maps routes to required permissions
var routePermissions = map[string]string{
    "GET /api/v2/claims/queue":           "claims.queue:read",
    "POST /api/v2/claims/:id/adjudicate": "claims.queue:approve",
    "GET /api/v2/finance/payments":       "finance.payments:read",
    "POST /api/v2/finance/payment-runs":  "finance.payment_runs:write",
    // ...
}

func PermissionMiddleware() fiber.Handler {
    return func(c *fiber.Ctx) error {
        requiredPerm := matchRoutePermission(c.Method(), c.Path())
        if requiredPerm == "" {
            return c.Next() // No permission required (public endpoint)
        }
        userPerms := c.Locals("permissions").([]string)
        if !contains(userPerms, requiredPerm) {
            return c.Status(403).JSON(ProblemDetail{
                Type:   "forbidden",
                Title:  "Insufficient permissions",
                Detail: "Required: " + requiredPerm,
            })
        }
        return c.Next()
    }
}
```

**3. Angular Frontend** — Dynamically renders navigation and UI based on permissions:

```typescript
// PermissionService — checks user's effective permissions
@Injectable({ providedIn: 'root' })
export class PermissionService {
  private permissions = signal<Set<string>>(new Set());

  has(permission: string): boolean {
    return this.permissions().has(permission);
  }

  hasAny(...permissions: string[]): boolean {
    return permissions.some(p => this.permissions().has(p));
  }

  // Check if user has any permission in a portal section
  canAccessPortal(portal: 'claims' | 'finance' | 'contributions' | 'admin'): boolean {
    return [...this.permissions()].some(p => p.startsWith(portal + '.'));
  }
}

// Navigation — only shows portals the user can access
@Component({ selector: 'app-sidebar' })
export class SidebarComponent {
  showClaims = computed(() => this.perms.canAccessPortal('claims'));
  showFinance = computed(() => this.perms.canAccessPortal('finance'));
  showContributions = computed(() => this.perms.canAccessPortal('contributions'));
  showAdmin = computed(() => this.perms.canAccessPortal('admin'));
}

// Route guard — protects routes by permission
export const claimsGuard: CanActivateFn = () => {
  return inject(PermissionService).canAccessPortal('claims');
};

// In-template usage — show/hide buttons based on write/approve access
<button *ngIf="perms.has('claims.queue:approve')" (click)="adjudicate()">
  Approve Claim
</button>
<!-- Read-only users see the data but no action buttons -->
```

**4. Read-Only Enforcement** — When a user has `read` but not `write`/`approve` for a section:
- API returns `403` on POST/PATCH/DELETE requests
- Angular hides action buttons (create, edit, approve, delete)
- Form fields are displayed as read-only text instead of editable inputs
- Export buttons only appear if `export` permission is present

---

## Tenant Branding & UI Customization

Tenant admins can customize the look and feel of their **Angular web portals** (admin, claims, finance, contributions, provider portal). The **Flutter member and provider mobile apps remain generic** with InsureFlow branding — only the logo and color scheme adapt per tenant.

### What is Customizable (Angular Web — Tenant Portals)

| Category | Setting | Description | Default |
|----------|---------|-------------|---------|
| **Identity** | Logo (light) | Primary logo for light backgrounds | InsureFlow logo |
| | Logo (dark) | Logo variant for dark backgrounds/sidebar | InsureFlow logo |
| | Favicon | Browser tab icon | InsureFlow favicon |
| | Organization name | Displayed in header, footer, PDF reports | Tenant name |
| **Colors** | Primary color | Buttons, links, active states, selection highlights | `#1677ff` |
| | Secondary color | Accents, badges, secondary actions | `#52c41a` |
| | Sidebar background | Side navigation background color | `#001529` |
| | Sidebar text color | Side navigation text and icon color | `#ffffff` |
| | Header background | Top header bar color | `#ffffff` |
| | Success / Warning / Error | Semantic feedback colors | Standard green/amber/red |
| **Layout** | Sidebar style | `collapsed` (icon-only), `expanded` (icon + text), `hidden` (top nav only) | `expanded` |
| | Layout mode | `sidebar` (side nav + content) or `topnav` (horizontal nav + content) | `sidebar` |
| | Content width | `fluid` (full width) or `fixed` (max-width centered) | `fluid` |
| | Sidebar position | `left` or `right` | `left` |
| | Theme mode | `light`, `dark`, or `system` (follows OS preference) | `light` |
| | Compact mode | Reduces padding/spacing for information-dense screens | `false` |
| **Typography** | Font family | Primary font for UI text | `Inter` |
| | Heading font | Font for headings (h1-h6) — can differ from body | Same as body |
| **Login Page** | Background image | Login/signup page background | InsureFlow default |
| | Welcome message | Text displayed on login page | "Welcome to {org_name}" |
| **PDF Reports** | Letterhead image | Top of invoices, payment advice, statements | Tenant logo |
| | Footer text | Bottom of PDF documents | Tenant name + address |
| **Email** | Header logo | Logo in email notification header | Tenant logo |
| | Footer text | Email footer (address, contact info, legal) | Tenant defaults |
| **Custom Domain** | Domain | `portal.zmmas.co.zw` instead of `zmmas.medfund.healthcare` | `{slug}.medfund.healthcare` |

### What is NOT Customizable

- Page structure, navigation items, component behavior — these are platform-defined
- Flutter mobile/web app UI layout — remains generic InsureFlow design (only logo + colors adapt)
- Super admin portal — always uses InsureFlow platform branding

### Branding Configuration Schema

```sql
-- Per-tenant branding (in tenant schema)
CREATE TABLE branding_config (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    -- Identity
    logo_light_url  TEXT,               -- S3 URL for light background logo
    logo_dark_url   TEXT,               -- S3 URL for dark background logo
    favicon_url     TEXT,               -- S3 URL for favicon
    org_display_name VARCHAR(200),      -- Override display name

    -- Colors (stored as hex)
    color_primary       VARCHAR(7),     -- '#1677ff'
    color_secondary     VARCHAR(7),
    color_sidebar_bg    VARCHAR(7),
    color_sidebar_text  VARCHAR(7),
    color_header_bg     VARCHAR(7),
    color_success       VARCHAR(7),
    color_warning       VARCHAR(7),
    color_error         VARCHAR(7),

    -- Layout
    layout_mode         VARCHAR(10) DEFAULT 'sidebar',    -- 'sidebar', 'topnav'
    sidebar_style       VARCHAR(10) DEFAULT 'expanded',   -- 'collapsed', 'expanded', 'hidden'
    sidebar_position    VARCHAR(5) DEFAULT 'left',        -- 'left', 'right'
    content_width       VARCHAR(5) DEFAULT 'fluid',       -- 'fluid', 'fixed'
    theme_mode          VARCHAR(10) DEFAULT 'light',      -- 'light', 'dark', 'system'
    compact_mode        BOOLEAN DEFAULT FALSE,

    -- Typography
    font_family         VARCHAR(100) DEFAULT 'Inter',
    heading_font_family VARCHAR(100),                     -- NULL = same as font_family

    -- Login
    login_bg_image_url  TEXT,
    login_welcome_text  TEXT,

    -- PDF
    pdf_letterhead_url  TEXT,
    pdf_footer_text     TEXT,

    -- Email
    email_header_logo_url TEXT,
    email_footer_text   TEXT,

    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_by          UUID NOT NULL
);
```

### How Branding is Applied

#### Angular Web App

Branding is loaded on app initialization and applied via CSS custom properties (variables):

```typescript
// BrandingService — loads tenant branding from API on app init
@Injectable({ providedIn: 'root' })
export class BrandingService {
  private config = signal<BrandingConfig | null>(null);

  async loadBranding(): Promise<void> {
    const branding = await firstValueFrom(
      this.http.get<BrandingConfig>('/api/v2/tenancy/branding')
    );
    this.config.set(branding);
    this.applyToDOM(branding);
  }

  private applyToDOM(config: BrandingConfig): void {
    const root = document.documentElement;

    // Colors → CSS custom properties
    if (config.colorPrimary)
      root.style.setProperty('--color-primary', config.colorPrimary);
    if (config.colorSecondary)
      root.style.setProperty('--color-secondary', config.colorSecondary);
    if (config.colorSidebarBg)
      root.style.setProperty('--color-sidebar-bg', config.colorSidebarBg);
    // ... all color variables

    // Typography
    if (config.fontFamily)
      root.style.setProperty('--font-family', config.fontFamily);

    // Theme mode
    if (config.themeMode === 'dark')
      document.body.classList.add('dark');
    else if (config.themeMode === 'system')
      this.applySystemTheme();

    // Favicon
    if (config.faviconUrl)
      (document.querySelector('link[rel="icon"]') as HTMLLinkElement).href = config.faviconUrl;

    // Document title
    if (config.orgDisplayName)
      document.title = config.orgDisplayName;
  }
}
```

Components reference these variables via Tailwind or direct CSS:

```css
/* tailwind.config.js extends theme with CSS variables */
:root {
  --color-primary: #1677ff;
  --color-secondary: #52c41a;
  --color-sidebar-bg: #001529;
  --color-sidebar-text: #ffffff;
  --color-header-bg: #ffffff;
  --font-family: 'Inter', sans-serif;
}

/* Components use the variables */
.btn-primary { background-color: var(--color-primary); }
.sidebar { background-color: var(--color-sidebar-bg); color: var(--color-sidebar-text); }
```

Layout mode is applied via a top-level component class:

```html
<!-- app.component.html -->
<div [class]="layoutClass()">
  <app-sidebar *ngIf="branding.layoutMode() === 'sidebar'" />
  <app-topnav *ngIf="branding.layoutMode() === 'topnav'" />
  <main [class.max-w-7xl]="branding.contentWidth() === 'fixed'"
        [class.compact]="branding.compactMode()">
    <router-outlet />
  </main>
</div>
```

#### Flutter Mobile/Web App (Limited)

Flutter adapts only logo and primary/secondary colors — layout remains standard InsureFlow:

```dart
// ThemeService loads tenant branding for color adaptation
class TenantTheme {
  final Color primaryColor;
  final Color secondaryColor;
  final String? logoUrl;

  ThemeData toThemeData() {
    return ThemeData(
      colorScheme: ColorScheme.fromSeed(seedColor: primaryColor),
      // Layout, typography, component styles remain InsureFlow standard
    );
  }
}
```

### Tenant Admin Branding UI

**Route**: `/admin/settings/branding`

| Section | Controls |
|---------|----------|
| **Identity** | Logo upload (drag-and-drop, crop/resize preview), favicon upload, display name |
| **Colors** | Color picker for each configurable color, with live preview panel |
| **Layout** | Radio buttons for layout mode, sidebar style, content width. Toggle for compact mode |
| **Typography** | Font selector (dropdown of web-safe + Google Fonts) |
| **Theme** | Light/dark/system toggle with preview |
| **Login Page** | Background image upload, welcome text editor |
| **PDF & Email** | Letterhead upload, footer text editor, email preview |
| **Preview** | Full-page live preview showing how the portal will look with current settings |
| **Reset** | Reset all to InsureFlow defaults |

Changes are saved and applied immediately (no deploy needed) — the Angular app fetches branding config on every page load (cached in Redis, ~50ms overhead).
