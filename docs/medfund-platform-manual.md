---
title: "InsureFlow Platform — Functional Manual & Acceptance-Test Plan"
subtitle: "What 'fully working' looks like for the multi-tenant, multi-currency, settings-driven insurtech platform"
author: "InsureFlow Engineering"
date: "2026-06-24"
---

# About this document

This document is two things at once.

**For the business reader** — it is the product manual. It describes, in plain English, every capability the InsureFlow platform delivers when it is fully functional. Read it top to bottom and you should be able to picture what the product *is*, who uses it, and what it does for them.

**For the QA, operations, or engineering reader** — it is the acceptance-test plan. Each functional chapter ends with a numbered set of manual checks. Walk through those checks in a browser, the mobile app, the admin console, or against the API, and you can answer one question with confidence: *is the platform truly working?*

The platform has four non-negotiable properties that the document keeps reinforcing:

1. **Multi-tenant** — every tenant lives in its own PostgreSQL schema and its own Keycloak realm. No data leaks across tenants, ever. Every test in this document has a "repeat as a second tenant" companion check.
2. **Configurable insurance lines** — a tenant can run medical aid, life cover, funeral cover, motor, asset, GPA, travel, or any combination. Turning a line on or off changes the menus, the schemes, the claim types, the rules, and the reports.
3. **Multi-currency per tenant** — a single tenant can bill in USD, accept payment in ZWL, and pay providers in ZAR. Foreign-exchange rates are recorded, rates are locked at the moment of a transaction, rounding is deterministic, and reports can be rendered in any tenant currency.
4. **Settings-driven white-label** — branding, domain, templates, MFA policy, payment providers, billing cycles, waiting periods, benefit limits, business rules, and AI thresholds are all configurable from the tenant admin portal. A tenant can make the platform look and behave entirely like their own.

Each chapter follows the same shape, so you can use it as a checklist:

> **What it is** → **Why it matters** → **Who uses it** → **How to verify** (numbered manual steps with multi-tenant + multi-currency variants) → **Pass/fail criteria** → **Edge cases & negative tests**.

When a screenshot would help, the document leaves a labelled placeholder so reviewers can drop the real image in.

---

# Part A — Platform overview

## A1. Executive summary

InsureFlow is a Software-as-a-Service platform for organisations that administer insurance schemes. A single InsureFlow deployment hosts many tenants — each tenant is a complete, isolated insurance business. The platform handles the full lifecycle of insurance administration:

- **Onboard the carrier** — provision a new tenant in minutes, give it its own subdomain, brand it, choose its lines of business and currencies.
- **Enroll members** — directly (individual self-signup) or through corporate groups with a designated liaison.
- **Collect contributions** — schedule billing cycles, issue invoices, accept payment online and offline, manage arrears.
- **Receive claims** — through the provider portal, the provider mobile companion app, or via uploaded document with optical character recognition.
- **Adjudicate claims** — through a six-stage pipeline that blends a configurable rules engine with AI-assisted decisioning.
- **Pay providers and members** — in any supported currency, with single or batched payment runs, dual approval over threshold, and bank reconciliation.
- **Watch the business in real time** — live dashboards over WebSocket, fraud anomaly detection, financial forecasting, and a member chatbot.
- **Stay compliant** — every entity mutation is audit-logged immutably for seven years, every security event is captured, and PHI is encrypted at every layer.

There are five portals:

| Portal | Audience | Where |
| --- | --- | --- |
| Super Admin | The platform operator (InsureFlow staff) | `/platform/*` in the Angular web app |
| Tenant Admin | Each tenant's administrators | `/tenant/admin/*` in the Angular web app |
| Operations | Claims, finance, contributions staff inside a tenant | `/tenant/claims`, `/tenant/finance`, `/tenant/billing` |
| Provider | Healthcare providers (clinics, hospitals, doctors, pharmacies) | `/tenant/providers` and the Flutter provider companion app |
| Member / Group Liaison | Insured members and the people who administer corporate groups | Flutter mobile app (iOS, Android, Web PWA) — liaisons see a dual-mode "My Account" + "Group Management" view |

## A2. Glossary (read this first)

A short glossary so the rest of the document reads smoothly. A longer reference glossary is in Appendix F.

- **Tenant** — a single insurance carrier on the platform. Has its own database schema (`tenant_<uuid>`), its own Keycloak realm, its own settings, and its own users.
- **Realm** — a Keycloak directory. The platform has one master realm (`medfund-platform`) for super admins and one realm per tenant for everyone else.
- **Line of business** — a category of insurance the tenant offers: medical aid, life cover, funeral cover, motor, asset, GPA, travel. A tenant can enable any combination.
- **Scheme** (or "plan") — a named bundle of benefits the member subscribes to. Tied to one line of business. Example: "Gold Medical Aid" or "Family Funeral Cover".
- **Benefit** — a category of cover inside a scheme, with its own limits and rules. Example: "Out-patient consultations", "Hospitalisation", "Burial costs".
- **Member** — an insured individual. Belongs to a group (corporate) or stands alone (individual). May have dependants.
- **Group** — a corporate employer or association whose members enroll through them. Has one or more liaisons who administer the membership and the bills.
- **Liaison** — a person inside a group who administers the group on the platform. Sees member admin + group billing, never sees personal health information (PHI).
- **Provider** — a clinic, hospital, doctor, or pharmacy that delivers care and submits claims.
- **Contribution** — the periodic premium the member or group pays to keep cover active.
- **Tariff** — the agreed price for a specific procedure or item, identified by a code (e.g., an AHFOZ code in Zimbabwe).
- **ICD-10** — international diagnosis code system. Every claim line carries one or more.
- **Pre-authorization** — approval the provider obtains *before* delivering a planned service (e.g., elective surgery, an MRI). Carries an approved amount and an expiry date.
- **Adjudication** — the decision process for a submitted claim. Six stages — eligibility, waiting period, benefit limit, pre-auth, tariff/pricing, clinical+AI.
- **Payment run** — a batch of outgoing payments to providers, prepared as a draft, optionally approved by a senior, then executed.
- **Bank reconciliation** — matching the platform's payment records to the actual line items on a bank statement.
- **Audit event** — an immutable record of one entity mutation (create / update / delete / approve / reject / pay).
- **Security event** — an immutable record of one identity action (login / logout / failed auth / MFA / role change / permission denial / impersonation).

## A3. The five portals at a glance

### A3.1 Super Admin portal (`/platform/*`)

For the people who run the platform. They see across all tenants. They never read tenant PHI directly — what they see is metadata: tenant status, plan, billing health, system health, audit and security events platform-wide.

Top-level sections:

- **Dashboard** — platform KPIs, tenant count, MRR, system health.
- **Tenants** — create, suspend, activate, configure plan; impersonate (audit-logged).
- **Plans** — subscription tiers the platform offers tenants.
- **Currencies** — the master ISO 4217 registry.
- **Exchange rates** — platform-wide default rates and per-tenant overrides.
- **Providers** — the cross-tenant provider registry (a provider can serve many tenants).
- **Audit & security** — read-only platform-wide event search.
- **Feature flags** — global toggles and per-tenant overrides.

### A3.2 Tenant Admin portal (`/tenant/admin/*`)

For the tenant's administrators. Everything that makes the platform "theirs" lives here.

- **Branding** — logo, palette, favicon, login wallpaper, public landing page copy.
- **Domain** — subdomain and custom domain mapping.
- **Lines of business** — turn medical / life / funeral / motor / asset / GPA / travel on or off.
- **Currencies** — add currencies, set the default, set per-group billing currency, choose FX source.
- **Schemes & benefits** — create schemes, configure age-group pricing, benefits, waiting periods, limits, copays.
- **Rules** — the visual rule builder; six categories (eligibility, waiting period, benefit limit, pre-auth, tariff, clinical).
- **Users & roles** — staff invites, custom roles, the permission catalogue, MFA policy per role.
- **Payment providers** — enable Paynow / Stripe / Paystack / EcoCash / InnBucks / DPO; sandbox vs live keys.
- **Notification channels & templates** — SMTP/SES/Twilio/Africa's Talking/FCM credentials; email and SMS templates per locale.
- **AI configuration** — auto-approve confidence threshold, fraud-flag threshold, model version pin.
- **Audit & security** — tenant-scoped event console.

### A3.3 Operations portals (`/tenant/claims`, `/tenant/finance`, `/tenant/billing`)

For the staff who run the day-to-day insurance operation:

- **Claims** — adjudication queue, AI-recommended decisions, manual override workspace, pre-auth desk, tariff and ICD-10 management, fraud alerts, rejection-reason library, appeals.
- **Finance** — payment runs, inbound receipts, outbound payouts, adjustments, credit/debit notes, advance payments, CTC payments, bank reconciliation, payment advice generation, financial reports.
- **Contributions / Billing** — billing runs (preview → commit), invoice generation, scheme change requests, balances, statements, arrears and bad-debt tracking, insurance quotation for prospects.

### A3.4 Provider portal (`/tenant/providers` + Flutter companion)

For healthcare providers. They authenticate once into their account and choose which tenant (medical aid) they are submitting against.

- **Submit a claim** — enter member ID or scan digital card; enter diagnosis, procedure, amount; attach documents; submit.
- **Track claims** — queue with statuses; receive adjudication outcome notifications.
- **Request pre-authorization** — file an auth request, attach motivation, receive approval/rejection with amount and expiry.
- **Look up tariffs** — search by code or by procedure name; see the rate the tenant pays.
- **Verify member eligibility** — instant check that a member is active.
- **View payment history** — receipts, advice documents, outstanding balance.

### A3.5 Member / Group Liaison (Flutter)

For insured members on iOS, Android, or web PWA. A liaison sees a dual-mode UI:

- **My account** — same view a member sees: benefits, claims, payments, bills, digital card, chatbot.
- **Group management** (liaison only) — group dashboard, member enrolment, scheme changes, invoice payment, statements. Never shows PHI of group members.

## A4. The settings-driven platform — the "white-label switch panel"

The single most important property of InsureFlow is that *almost every visible thing* about the platform is a tenant setting, not a code change. A tenant can:

1. Replace the logo and the colour palette.
2. Use a custom subdomain (`zmmas.medfund.healthcare`) or a fully custom domain (`portal.zmmas.co.zw`).
3. Customise the login screen background, the favicon, and the public landing copy.
4. Choose its lines of business — turn medical aid on, life cover off, funeral cover on.
5. Add currencies, pick the default, change FX sources.
6. Configure billing cycles per scheme — monthly, quarterly, annual.
7. Set per-scheme waiting periods, annual limits, lifetime limits, copays, family pools.
8. Author its own business rules through the visual builder.
9. Decide which roles must use MFA, and which factor (TOTP, email OTP, SMS OTP).
10. Choose payment providers per currency.
11. Write its own email and SMS templates, in multiple locales.
12. Pin the AI model version, set its auto-approve confidence threshold, set its fraud-flag threshold.
13. Configure notification channels and per-event recipients.

The complete index of tenant-configurable settings is in **Appendix E**. Treat it as a release-time checklist: each setting has a "verify in this tenant" line.

## A5. Multi-tenancy in practice

### What isolation actually means

- Each tenant has a unique PostgreSQL schema named after its UUID. Every tenant-scoped table is replicated per schema. There is no `tenant_id` filter at the application level for tenant tables — the schema *is* the boundary.
- A handful of platform-wide tables live in the `public` schema: `tenants`, `plans`, `currencies`, `exchange_rates`, `staff_users` (platform admins), `providers` (the cross-tenant provider registry), `audit_events`, `security_events`.
- Each tenant has its own Keycloak realm. Users in one realm cannot authenticate into another.
- Every API request must resolve a tenant. The order is: JWT `tenant_id` claim → `X-Tenant-ID` header → subdomain → fail.

### What super admin can and cannot see

| Super admin can see | Super admin cannot see |
| --- | --- |
| Tenant metadata (name, slug, status, plan, MRR) | Tenant member or provider personal data |
| Aggregate platform metrics (claim counts, payment volume) | Individual claim contents or PHI |
| Audit and security events across all tenants | Tenant's business rules drafts unless impersonating |
| Tenant billing health, system health, error rates |  |

### Impersonation

Super admins can log in *as* a tenant admin to support an investigation. Every impersonation creates a security event (`IMPERSONATION_START`) and another when the session ends (`IMPERSONATION_END`). The impersonating identity is preserved in every audit event during the session as `actorId` + `impersonatorId`.

## A6. Multi-currency in practice

### Data model

- Money is stored everywhere as **(amount DECIMAL(19,4), currency_code CHAR(3))** — never as a bare number.
- The platform-wide `currencies` table holds the ISO 4217 master list, including decimal places (USD=2, KWD=3, JPY=0).
- `exchange_rates` stores daily snapshots (base, quote, rate, date, source, optional tenantId). Tenant-scoped rates take precedence over platform-wide rates for that tenant.
- Per-tenant `tenant_currency_config` defines: enabled currencies, default currency, per-group billing currency overrides, FX source (manual / RBZ / OpenExchangeRates / fixed override).

### Behavioural rules

- **Rate locking** — every monetary transaction stores the rate used at the time of the transaction. Restating yesterday's rate does not change yesterday's invoice.
- **Never mix currencies in arithmetic** — convert to a common currency before comparing or summing.
- **Rounding** — HALF_EVEN (banker's rounding) for all conversions. Display uses ISO 4217 decimal places for the currency.
- **Sanity check** — alert when a new rate deviates >10% from the previous rate.

### Example flow

A member is on a scheme priced in USD. They are billed in USD. They pay through EcoCash in ZWL. The provider's tariff is in USD and they want payout in ZAR. The platform stores:

- Invoice: `amount=120.00, currency=USD`, due date.
- Payment: `amount=540000.00, currency=ZWL`, FX rate `USD/ZWL=4500.00` locked.
- Claim approval: `claimed=180.00 USD, approved=140.00 USD`.
- Payout: `amount=2500.00, currency=ZAR`, FX rate `USD/ZAR=17.86` locked.

The audit trail can reconstruct every conversion using the rates stored on each row.

---

# Part B — Tenant provisioning and white-label verification

This part covers the chapters that prove a tenant can be created, branded, and made operational with no code changes.

## B7. Tenant onboarding

### What it is

Either a super admin creates a tenant manually, or a prospect signs up at `medfund.healthcare/pricing`, picks a plan, pays, and the tenant is provisioned automatically.

### Why it matters

Tenant onboarding is the moment of truth for multi-tenancy. If schema creation, Keycloak realm creation, or default-data seeding fails, the tenant is broken from day one.

### Who uses it

Super admins (manual path) and prospect customers (self-service path).

### How to verify — manual path

1. Log in to the super admin portal at `/platform/tenants`.
2. Click "New tenant". Fill in: name, slug, country, default currency, contact email, plan, lines of business.
3. Submit. Watch the provisioning log on screen.
4. Confirm in the database: a row in `public.tenants` with status `ACTIVE` and a `schemaName` like `tenant_<uuid>`.
5. Confirm in PostgreSQL: the schema exists; the Flyway versioned migrations have all run; default schemes, benefits, rules, and notification templates are seeded.
6. Confirm in Keycloak: a new realm exists; the OIDC clients `medfund-web-admin` and `medfund-mobile` are present; the default roles are present; one tenant admin user exists.
7. Confirm Kafka: a `medfund.tenants.provisioned` event was published with the new tenantId, slug, schemaName, and realm name.
8. Confirm email: the tenant admin received a welcome email with login credentials.
9. Log in as the tenant admin at `<slug>.medfund.healthcare`. Land on `/tenant/admin/dashboard`.

### How to verify — self-service path

1. Open `https://medfund.healthcare/pricing`.
2. Pick a plan. Fill in organisation name, country, admin email, admin phone.
3. Enter payment details in the Stripe checkout.
4. Wait for the success page.
5. Receive the welcome email.
6. Repeat steps 4–9 from the manual path.

### Pass criteria

- Tenant created in under 60 seconds end-to-end.
- All default data seeded; no Flyway migration failures.
- Welcome email delivered.
- Tenant admin can log in immediately.

### Edge cases

- Slug already taken — clear error, no half-provisioned tenant.
- Payment fails — no tenant, no Keycloak realm.
- Email delivery fails — tenant is still provisioned; super admin can resend credentials.
- Re-running provisioning for a partially-provisioned tenant must be idempotent.

## B8. Branding and white-labelling

### What it is

The tenant admin uploads their logo and colour palette, picks fonts, replaces the login background, sets the favicon, edits the email and SMS templates, and points a custom domain at the platform.

### Why it matters

The platform should feel like the tenant's own product to members and providers. A member of "ZMMAS" should never see the word "InsureFlow" anywhere in the UI or in the emails they receive.

### Who uses it

Tenant admins.

### How to verify

1. Log in as tenant admin, go to `/tenant/admin/branding`.
2. Upload a new logo. Pick a primary colour. Pick a secondary colour. Upload a favicon. Upload a login background.
3. Save. Open an incognito tab and visit the tenant's subdomain. The login screen reflects the new branding.
4. Log in as a member. Top navigation, dashboard, and footer use the new logo and palette.
5. Send a test transactional email (e.g., "welcome"). The email uses the new logo, the tenant's name in the from-line, and the customised copy.
6. Go to `/tenant/admin/domain`. Add a custom domain (`portal.zmmas.co.zw`). Follow the DNS instructions. Wait for the verification status to flip to "verified".
7. Visit the custom domain — it serves the same portal, branded the same way.

### Pass criteria

- No mention of "InsureFlow" anywhere in the tenant-facing UI.
- Logo and colours render correctly on web, mobile app, and PDF documents (invoice, statement, payment advice).
- Custom domain serves with a valid TLS certificate.

### Edge cases

- Logo too large — must be resized or rejected with a clear message.
- Colour contrast too low — soft warning, not a hard block.
- DNS not yet propagated — verification step holds in "pending" with retry.

## B9. Insurance lines of business

### What it is

Each tenant chooses which lines of insurance they offer. The choices change what is visible across the platform.

### Why it matters

A funeral-only tenant should not see medical-aid menus. A motor-insurance tenant should not see ICD-10 codes.

### Who uses it

Tenant admins (configure), all roles (observe the effect).

### How to verify

1. As tenant admin, go to `/tenant/admin/lines-of-business`. Enable: Medical, Funeral. Disable: Life, Motor, Asset, GPA, Travel. Save.
2. As a member, the mobile app shows only schemes from the enabled lines.
3. As a claims clerk, the new-claim wizard only asks for medical or funeral information — no ICD-10 codes on a funeral claim.
4. As a contributions clerk, invoice templates respect the line of business.
5. As a finance clerk, the reports portal only shows reports relevant to enabled lines.
6. Toggle Motor on. A motor-claim type appears in the claim wizard. A vehicle-detail panel appears in scheme configuration.

### Pass criteria

- Disabling a line cleanly hides every menu, field, and template for that line.
- Re-enabling restores them.
- Existing data on a disabled line is preserved (not deleted) and visible as read-only until re-enabled.

## B10. Currency configuration

### What it is

The tenant admin chooses which currencies they support, sets a default currency for the tenant, may set per-group billing currencies, and chooses an FX rate source.

### How to verify

1. Tenant admin: `/tenant/admin/currencies`. Add USD, ZAR, ZWL. Set USD as default.
2. Choose FX source: "OpenExchangeRates". Confirm latest rates appear within the configured refresh window.
3. Manually override the USD→ZWL rate for today. Confirm the override is used until cleared.
4. Create a group "Acme Corp" with billing currency ZAR (overriding the default). Invoice the group; the invoice is denominated in ZAR.
5. Create a payment run that includes payouts in two currencies. Confirm the payment-run header shows multi-currency totals per currency.
6. Open a financial report; toggle the reporting currency between USD and ZAR. All conversions use the historical rates on each transaction.

### Pass criteria

- Rates are locked on transactions at commit time and never restated.
- Reports converted to a chosen currency tie back to the underlying multi-currency totals to within rounding error.
- A 10%+ rate movement triggers a sanity alert.

## B11. MFA and authentication policy

### What it is

Per-role multi-factor authentication policy, social login configuration, session timeout, brute-force lockout.

### How to verify

1. `/tenant/admin/auth-policy`. For `tenant_admin`, require TOTP. For `claims_clerk`, allow TOTP or email OTP. For `member`, leave MFA optional.
2. Try logging in as a tenant admin without TOTP enrolled — forced into enrolment.
3. Log in as a claims clerk with email OTP — receive code, enter it, in.
4. Disable Google social login; re-enable Microsoft. Confirm the login screen reflects the change for the tenant only.
5. Configure session timeout to 30 minutes. Sit idle for 31 minutes. Confirm forced re-login.
6. Trigger 5 failed logins for the same user in quick succession. Confirm account temporarily locks and a `LOGIN_LOCKED` security event is recorded.

### Pass criteria

- A role marked MFA-required cannot complete login without an OTP.
- Brute-force protection trips at the configured threshold.
- Every auth event lands in the `medfund.security.events` Kafka topic.

## B12. Payment provider wiring

### What it is

Tenant admin enables and configures the payment gateways the tenant uses. Each provider has sandbox and live credentials and a webhook secret.

### How to verify

1. `/tenant/admin/payment-providers`. Enable Paynow (sandbox). Enter merchant ID and integration key.
2. Use the "Send test payment" button to initiate a one-cent payment; complete the test flow.
3. Confirm a `payments` row with status `COMPLETED` and a webhook receipt in the gateway log.
4. Disable Paynow, enable Stripe (sandbox). Repeat with a test card.
5. Switch Paynow to live keys. Block live keys from being saved unless TLS is verified and the host environment is production.
6. Confirm webhook signature validation rejects a tampered webhook payload.

### Pass criteria

- A disabled provider does not appear in member or admin payment checkouts.
- Webhook signatures are validated. Tampered webhooks are rejected with a security event.
- Idempotency keys prevent duplicate charges on retry.

## B13. Notification channels and templates

### What it is

Per-tenant configuration of email, SMS, push, and in-app notification channels, including the templates and locales used.

### How to verify

1. `/tenant/admin/notifications`. Configure SES as the email provider. Configure Twilio for SMS. Save credentials.
2. Edit the "welcome member" email template. Switch locale to French. Edit again. Save.
3. Enroll a new member with `preferred_locale=fr`. Confirm the French template is sent.
4. Edit the "claim adjudicated" SMS template; include a variable for the approved amount. Adjudicate a claim; confirm the SMS includes the correct amount in the correct currency format.
5. Send a notification that fails (invalid recipient). Confirm a retry runs with exponential backoff, then the notification is marked `FAILED` and surfaced in the admin notification queue.

### Pass criteria

- Templates render correctly for all configured locales.
- Variables substitute correctly, including currency formatting.
- Failed sends retry, then surface for inspection.

---

# Part C — Identity, access and audit

## C14. Roles and permissions

### What it is

A role is a named bundle of permissions. The platform ships with system roles (`tenant_admin`, `claims_supervisor`, `claims_clerk`, `finance_hod`, `finance_clerk`, `contributions_clerk`, `provider`, `provider_admin`, `member`, `group_liaison`) and the tenant admin can compose custom roles.

The permission model is `domain.section:action`, where action is one of `read | write | delete | approve | export | configure`. The full canonical catalogue is in Appendix C.

### Why it matters

Misconfigured RBAC is the single most common cause of data leakage. Verifying the permission boundary for every role on every release is non-negotiable.

### How to verify

1. As tenant admin, go to `/tenant/admin/roles`. Open the permission catalogue and confirm every domain (claims, contributions, finance, members, providers, rules, audit, reports, settings) is listed.
2. Create a custom role "Junior Adjudicator" with only `claims.queue:read` and `claims.queue:write`. No approve.
3. Assign the role to a test user. Log in as the user.
4. Confirm the user sees the claims queue and can update a claim's notes, but every "Approve" button is hidden or disabled.
5. Confirm the API rejects an attempt to call `POST /api/v1/claims/{id}/status` with body `{status: COMMITTED}` — expect 403 with a permission-denial security event.
6. Revoke the role from the user. Confirm the cached permissions on the user's session refresh within 60 seconds (or immediately on next request) — this is the `medfund.permissions.invalidated` Kafka path.
7. Confirm role assignment also synchronises to Keycloak realm roles.

### Pass criteria

- Permission changes propagate within 60 seconds.
- UI hides actions the user lacks; API enforces them.
- Every denial is logged as a security event.

## C15. Staff user lifecycle

### What it is

Invite, activate, suspend, terminate, role-reassign, force-MFA-enrolment for tenant staff.

### How to verify

1. Tenant admin invites `alice@tenant.example`. Alice receives an email with a one-time activation link.
2. Alice clicks the link, sets a password, enrols TOTP.
3. Suspend Alice. Confirm she cannot log in; her current sessions are revoked within one minute.
4. Reactivate Alice. She can log in again.
5. Change Alice's role from "claims_clerk" to "claims_supervisor". She gains "Approve" buttons on next page load.
6. Terminate Alice. Confirm her account is disabled in Keycloak, her active sessions are revoked, and an audit event of type `STAFF_TERMINATED` is recorded.

### Pass criteria

- Suspension and termination revoke sessions promptly.
- Activation never proceeds without enrolment of any required factors.

## C16. Member, dependant, provider, liaison lifecycles

### Member

- Enrol → activate (auto or manual) → suspend (e.g., for arrears) → terminate → re-enrol.
- Suspended members cannot submit claims and cannot pay (depending on tenant policy); their existing claims continue through adjudication.
- Terminated members lose access to the member portal at the end of cover.

### Dependant

- Add → activate → remove. Each transition is audit-logged. A removed dependant's historical claims remain visible to the member and to operations.

### Provider

- Onboard → verify (AHFOZ + HPA + tax clearance) → activate → suspend → reactivate → terminate.
- A suspended provider's existing claims continue but no new claims may be filed against the tenant until reactivation.

### Group liaison

- A liaison is created from the group screen with first name, last name, email, role flag (`group_liaison`). They receive an invite email and complete enrolment via Keycloak.
- Multiple liaisons per group are supported; one can be primary. Removing the last liaison places the group into "needs liaison" status.

### How to verify

1. Walk each entity through every transition. After each transition, confirm an audit event with the correct action and a friendly `entityName`.
2. Confirm that re-activating a previously terminated member preserves their member number and history.
3. Confirm that the provider verification step requires the verifier to enter the AHFOZ and HPA registration numbers, and that the system flags expired registrations.

## C17. Audit trail

### What it is

Every CREATE, UPDATE, DELETE, APPROVE, REJECT, PAY on a business entity emits an immutable audit event to Kafka. The audit service writes to an append-only, monthly-partitioned table with a minimum seven-year retention.

### Required fields on every audit event

- `entityType` (e.g., `claim`, `member`, `payment_run`)
- `entityId`
- `entityName` — a friendly, human-readable name (never the UUID — see Appendix F glossary).
- `action`
- `actorId` (Keycloak `sub`)
- `actorEmail` — must always be populated; never null.
- `correlationId`
- `tenantId`
- `oldValues` and `newValues` (changed fields only)
- `timestamp`

### How to verify

1. Make one of every kind of change as a known actor. Confirm one event each.
2. Open the audit console (`/tenant/audit` for tenant scope, `/platform/audit` for super admin).
3. Filter by `actorEmail`, by `entityType`, by `entityId`, by date range, by `action`. Confirm filters narrow the set correctly.
4. Confirm the daily-counts widget reflects 30 days, zero-filled.
5. Export a filtered set as CSV. Confirm the export contains the same rows.
6. Attempt to UPDATE or DELETE an audit row via the database — confirm RLS / constraints prevent it. Append-only is sacred.
7. Confirm a claim entity event carries `entityName = claim number` (e.g., `CLM-2026-000123`), not the UUID.

### Pass criteria

- Every mutation has an event with all required fields populated.
- No event can be modified or deleted.
- Search and export work on tenant-scoped events; super admin sees platform-wide events.

## C18. Security events and real-time alerts

### What it is

Login, logout, failed auth, MFA event, password change, role assignment, permission denial, and impersonation are recorded as security events. Real-time patterns trigger alerts.

### How to verify

1. Successfully log in, log out — confirm `LOGIN_SUCCESS` and `LOGOUT` events.
2. Fail login with a wrong password — confirm `LOGIN_FAILED` event.
3. Trigger 5 failed logins from the same IP in 60 seconds for different accounts — confirm a `BRUTE_FORCE_SUSPECTED` alert.
4. Log in from one country, then from another country 5 minutes later — confirm an `IMPOSSIBLE_TRAVEL_SUSPECTED` alert.
5. Assign a higher-privilege role to a user; confirm `ROLE_GRANTED` event with both the granter and the grantee identified.
6. Super admin impersonates a tenant admin — confirm `IMPERSONATION_START`, the audit events during the session carry both `actorId` and `impersonatorId`, and `IMPERSONATION_END` fires on session close.
7. Try to access an endpoint your role lacks — confirm a `PERMISSION_DENIED` event with the requested resource.

### Pass criteria

- Every auth event from Keycloak lands in the security console within a few seconds.
- Alerts fire on the documented patterns.
- The console allows filtering by user, type, severity, and date.

---

# Part D — Membership, groups and contributions

## D19. Group management

### What it is

A group is a corporate employer or association whose members enroll under it. The tenant or the liaison creates the group, assigns one or more liaisons, and selects which schemes the group offers its members.

### How to verify

1. Tenant admin creates "Acme Corp" with employer registration number, address, billing currency (ZAR), and primary contact.
2. Add a liaison `lisa@acme.example`. She receives an invite, completes enrolment, and lands on `/group-management`.
3. Lisa enables two schemes for Acme employees: "Silver Medical" and "Family Funeral".
4. Lisa enrolls 10 employees, each picking one or both schemes.
5. Suspend the group. Confirm no new enrolments are accepted; existing members remain active until billing decides otherwise.
6. Reactivate the group.

### Pass criteria

- Group liaison cannot see any other group, ever.
- Group liaison cannot see PHI of group members (claims, diagnoses, prescriptions).
- Group liaison can manage member enrolment, scheme changes, invoices, and statements.

## D20. Member enrolment

### What it is

Two paths: group-mediated (liaison enrolls an employee) or individual self-signup (a person signs up directly).

### How to verify — group path

1. Liaison opens the group management portal, clicks "Add member".
2. Enter personal details, ID number, date of birth, dependants, scheme(s), effective date.
3. Submit. A member number is generated (deterministic format per tenant). A Keycloak account is created. A welcome email is sent.
4. The new member receives the email, sets a password, logs into the mobile app, sees their digital card, benefits, and the date their waiting periods start.

### How to verify — individual self-signup

1. Visit `<tenant>.medfund.healthcare/signup`.
2. Pick a scheme. Review benefits and pricing. Continue.
3. Fill personal details, banking info, dependants. Pay the first contribution online.
4. On payment success, member is activated and welcomed.

### Back-dated enrolment

Enrolment effective date is always the 1st of a month. Back-dating to an earlier 1st-of-month is allowed but triggers an arrears adjustment on the contributions side for the missed cycles — see Appendix F for the explicit business rule.

### How to verify

1. Enrol a member with effective date three months in the past.
2. Confirm the contributions ledger immediately posts three missed-period invoices in `PENDING` status.
3. Confirm the member's portal shows the arrears prominently and offers to pay.

### Pass criteria

- A member number is unique per tenant and follows the configured format.
- Welcome email arrives in seconds.
- Back-dated enrolment produces correct arrears, not silent skips.

## D21. Schemes and benefits

### What it is

A scheme is a named bundle of benefits, with age-group pricing, waiting periods, annual and lifetime limits, copays, and optional family pools.

### How to verify

1. Tenant admin creates "Gold Medical 2026". Adds age groups: 0–18, 19–59, 60+. Sets monthly premium per age group per currency.
2. Add benefits: "Out-patient", "Hospitalisation", "Optical", "Dental", "Maternity". For each, set: annual limit (per benefit and per family), per-event limit, waiting period, copay percentage.
3. Save. Open the public scheme browser page — the new scheme appears with all its details.
4. Enroll a 30-year-old as a single member. Confirm the monthly premium matches the 19–59 bracket.
5. Add a dependant child (age 5). Confirm the premium recomputes correctly.
6. Submit a claim against "Maternity" within the waiting period; confirm a hard reject with the documented rejection code.

### Pass criteria

- All scheme settings are honoured in adjudication.
- Pricing recomputes immediately on dependant change.

## D22. Scheme change workflow

### What it is

A member or liaison requests a change to a different scheme. The change has an effective date, may reset waiting periods for new benefits, and changes the next billing run.

### How to verify

1. Member requests an upgrade from "Silver" to "Gold". Effective date defaults to the next 1st-of-month.
2. The request enters `PENDING`. Tenant admin (or auto-approval if enabled) approves.
3. The platform records the change with `effectiveDate` and a status snapshot of waiting periods for any new benefits.
4. Wait until the effective date (or simulate). Confirm the member's benefits show the new scheme and waiting periods restart only for benefits that did not exist in the old scheme.
5. Run the next billing cycle. Confirm the new scheme's premium is used.

### Pass criteria

- Existing waiting periods carry over where the benefit existed in the old scheme.
- New benefits get fresh waiting periods.
- No premium is charged at the old rate after the effective date.

## D23. Billing and invoicing

### What it is

A scheduled (or ad-hoc) billing run that previews per-member contributions, then commits them, generates invoices, and distributes them. Includes a cooldown between commits to prevent accidental double-billing.

### How to verify

1. Contributions clerk goes to `/tenant/billing/generate`.
2. Step 1: pick period (e.g., July 2026), pick scope (all members, by group, by scheme).
3. Step 2: click "Preview". The platform returns per-currency totals, sample line items, and an "estimated cycle count". No data is persisted.
4. Step 3: click "Commit". Within the cooldown window (configurable, default 30 minutes), a second commit attempt returns HTTP 409 with a clear message.
5. After commit: invoices appear in member and liaison portals; the `medfund.contributions.billed` Kafka event fires; invoice PDFs are generated.
6. A member pays partial; confirm balance updates accordingly.
7. A member overpays; confirm the overage credits next cycle.
8. Run the same preview on a large tenant via the background-job path (`POST /contributions/billing/enqueue`); confirm the job completes and its result is fetched via the job-runs short-poll endpoint.

### Pass criteria

- Preview never persists.
- Cooldown prevents double-commit.
- Multi-currency totals are correctly grouped per currency.

## D24. Balances and statements

### What it is

Running balances at member, group, benefit-category, and currency level. Statements as downloadable PDFs and CSV exports.

### How to verify

1. Member pays an invoice; confirm `runningBalance` decreases by the paid amount in the paid currency.
2. Provider has multiple paid claims in different currencies; confirm `providerBalance` shows per-currency rows.
3. A benefit category limit is approached; confirm the member portal shows "X of Y used".
4. Download a member statement PDF for the last three months. Confirm it lists every contribution, claim, and adjustment with the correct currencies.
5. Export the same data as CSV. Confirm fields match.

### Pass criteria

- Balances are always consistent with the ledger (no drift after a billing/payment run).
- Multi-currency totals are kept separate, never summed across currencies.

## D25. Arrears, reminders and bad debt

### What it is

Members in arrears receive escalating reminders. At a configurable threshold (e.g., 90 days), the platform suspends the member. Long-overdue invoices can be written off as bad debt.

### How to verify

1. Create a member with one unpaid invoice 7 days before due date — confirm the proactive SMS/email reminder is queued.
2. Skip the due date by 30 days — confirm the 30-day reminder fires.
3. Skip another 30 days — 60-day reminder fires.
4. Skip another 30 days — 90-day reminder fires and the member is automatically suspended (if the tenant policy enables auto-suspension).
5. Write off the invoice as bad debt. Confirm the bad-debt ledger entry, the audit event, and that the member's `outstandingBalance` is reduced by the written-off amount.
6. Later, the member pays the previously-written-off amount. Confirm a recovery entry is recorded.

### Pass criteria

- Reminders fire on the configured cadence.
- Auto-suspension is reversible.
- Bad-debt write-off is auditable and reversible.

## D26. Insurance quotation

### What it is

A premium quote for a prospect, by line of business, scheme, age, and family composition.

### How to verify

1. From the public marketing site, fill in age, family composition, and desired scheme; submit.
2. Receive an immediate quote with monthly premium in the tenant's default currency, and the option to start enrolment.
3. Tenant admin can configure quote validity (e.g., 30 days) and quote document branding.

### Pass criteria

- Quote pricing matches the configured age-group pricing exactly.
- Multi-currency quotes show the chosen currency clearly.

---

# Part E — Claims, pre-authorization and adjudication

## E27. Provider onboarding and tariff setup

### What it is

The tenant's providers must be onboarded, verified, and given tariff schedules to claim against. The tariff schedule is per-tenant and may be uploaded in bulk.

### How to verify

1. Tenant admin (or provider self-registers and tenant verifies) creates a provider with AHFOZ number, HPA number, specialty, practice type, banking details, accepted currencies.
2. Verification step checks AHFOZ status, HPA status, tax clearance. Until verified, the provider is `PENDING_VERIFICATION` and cannot submit claims.
3. Upload a tariff schedule via CSV import. Confirm row counts, validation errors, and that the schedule is versioned.
4. Edit a single tariff inline; confirm a new version is created and the old version remains queryable.
5. Search tariffs by description ("Appendicectomy") and by code; confirm modifiers (bilateral, assistant, after-hours) appear.
6. Upload the ICD-10 catalogue. Confirm code search returns matches by code, by description, by chapter.
7. Upload the drug catalogue (for drug claims) with NAPPI codes, dosage forms, unit prices.

### Pass criteria

- A `PENDING_VERIFICATION` provider cannot file claims.
- Versioned tariff history is queryable for historical claim re-pricing.
- Bulk import is idempotent on retry (no duplicate rows for the same code+version).

## E28. Pre-authorization workflow

### What it is

The provider requests pre-authorisation before delivering a planned service. The tenant approves (with an approved amount and an expiry date) or rejects.

### How to verify

1. Provider files a pre-auth request: member ID, planned procedure code(s), motivation text, attached supporting documents (referral, lab results).
2. Tenant claims supervisor sees it in the pre-auth queue. Reviews. Approves with `approvedAmount = 1200 USD, expiry = 2026-09-30`.
3. Confirm the `medfund.claims.pre-auth-decision` Kafka event fires.
4. Provider receives notification with the pre-auth number.
5. Provider submits a claim that references the pre-auth number. Adjudication stage 4 confirms validity.
6. Try to submit a second claim referencing the same pre-auth with an over-limit amount — confirm partial approval to the remaining pre-auth balance.
7. Try to submit a claim past the pre-auth expiry — confirm reject with the documented code.

### Pass criteria

- Pre-auth amount and expiry are enforced.
- Pre-auth status drives stage 4 of adjudication consistently.
- Rejection codes for "expired pre-auth" and "exceeded pre-auth" are distinct.

## E29. Claim submission channels

### What it is

Claims arrive through the provider portal, the provider mobile companion, a document upload with OCR, a drug claim (pharmacy), or a direct API integration.

### How to verify — provider portal

1. Provider logs into the portal, selects the tenant, opens "New claim".
2. Enters member ID (or scans QR), service date, diagnosis codes, procedure codes (tariff codes), claimed amount, currency, attached documents.
3. System suggests tariff codes given the diagnosis. Provider confirms.
4. Submits. Claim gets a claim number; an SMS verification code is dispatched to the member.

### How to verify — OCR document upload

1. Provider uploads a scanned claim form (PDF or image).
2. The AI service extracts member ID, service date, diagnosis text, procedure description, claimed amount, currency; presents the parsed fields for the provider to confirm.
3. Confidence score per field is shown; low-confidence fields are highlighted.
4. Provider edits, confirms, and submits.

### How to verify — drug claim

1. Pharmacy provider opens the drug claim form, picks the member, selects the drug (NAPPI code lookup), enters quantity and dosage, attaches the prescription image.
2. System validates against the tenant's formulary and the prescription rules.
3. Submits.

### Pass criteria

- Every channel yields the same internal claim representation.
- Member receives a verification code within seconds of submission.
- OCR confidence under threshold blocks auto-submit and forces human review.

## E30. Member verification of claims

### What it is

After a claim is submitted, the member confirms the service really happened, via SMS/email OTP or by scanning the digital card QR at the provider.

### How to verify

1. Member receives the verification code SMS within seconds.
2. Member opens the mobile app, enters the code on the pending-verification card.
3. The claim transitions from `SUBMITTED` to `VERIFIED` and enters adjudication.
4. Alternatively, the provider scans the member's digital-card QR at point of service; the claim is auto-verified.
5. A claim left unverified beyond the tenant's tolerance window is auto-rejected with the documented code.

### Pass criteria

- Verification works on all channels.
- Unverified-too-long claims are handled deterministically.

## E31. The six-stage adjudication pipeline

### What it is

Every verified claim flows through six ordered stages. Any stage can hard-fail (auto-reject), partial-approve, or pass to the next.

| # | Stage | Owner | Logic | Outcome |
| --- | --- | --- | --- | --- |
| 1 | Eligibility | Rules engine | Member active? Provider verified? In time window? Contribution standing OK? | Pass / R01 / R11 / R14 / R15 |
| 2 | Waiting period | Rules engine | Member has met waiting period for this benefit? Waivable for emergencies? | Pass / R02 |
| 3 | Benefit limit | Rules engine | Used-YTD + this claim ≤ annual limit? Lifetime limit? Family pool? | Pass / Partial / R03 |
| 4 | Pre-authorization | Rules engine | Tariff requires pre-auth? Auth exists, valid, not exceeded? | Pass / R04 / R05 |
| 5 | Tariff / pricing | Claims + rules | Tariff code valid? Specialty matches? Modifiers apply? Upcoding? Currency conversion? | Capping / R06 / R07 / R10 / pass |
| 6 | Clinical + AI | Rules + AI service | Diagnosis-procedure valid? Frequency OK? Gender/age appropriate? Duplicate? Fraud risk? | AI recommendation + confidence + explanation |

### Decision matrix after stage 6

- All stages pass + fraud risk < threshold + confidence > threshold + tenant allows auto → `AUTO_APPROVED`.
- Any stage hard-fails → `AUTO_REJECTED` with code.
- Otherwise → `ROUTE_TO_QUEUE` for manual adjudication.

### How to verify

For each stage, prepare a claim that fails it and one that passes it. Submit both. Confirm the rejection code (or pass) matches the table. Then prepare:

- A claim that partial-approves at stage 3 (benefit limit nearly used up).
- A claim that partial-approves at stage 5 (provider over-billed a capped tariff).
- A claim that lands in the manual queue because confidence < threshold.

### Pass criteria

- Stage order is preserved (a stage-1 failure must not run stages 2–6).
- Rejection codes are stable and documented (Appendix D).
- Multi-currency: a USD-priced tariff and a ZWL-claimed amount are correctly converted using the locked rate.

### Multi-tenant variant

Repeat the same set of claims as Tenant B with different rules. Confirm Tenant A's rules cannot leak across — this is the explicit `TenantRuleEngine` per-tenant `ReleaseId` guarantee.

## E32. AI-assisted decisioning

### What it is

The AI service offers a recommended decision, an approved amount, a confidence score, an explanation, and a fraud risk score. Every prediction is stored in an immutable `ai_predictions` table with the model version, input features, output, and any human feedback.

### How to verify

1. Submit a "clearly clean" claim — confirm an `AUTO_APPROVED` outcome with high confidence.
2. Submit a "very unusual" claim (high amount, unusual diagnosis combination) — confirm a high fraud risk score and `ROUTE_TO_QUEUE`.
3. Open the prediction record. Confirm model version, input features, output, confidence, and a human-readable explanation.
4. Manually override the AI decision. Confirm the override is recorded against the prediction as feedback.
5. As tenant admin, change the auto-approve confidence threshold from 0.8 to 0.95. Re-submit similar claims; confirm fewer auto-approvals.
6. Pin a specific AI model version. Confirm a deployment of a newer version does not change adjudications until the pin is moved.

### Pass criteria

- Every AI decision can be audited end-to-end: input → output → rationale.
- Threshold changes take effect on the next claim and are audit-logged.

## E33. Manual adjudication workspace

### What it is

When a claim lands in the queue, an adjudicator opens the workspace and sees the claim, the member's history, the applicable benefits, the rules that fired, the AI recommendation, and the tariff context — all in one screen.

### How to verify

1. Open the queue at `/tenant/claims/queue`. Filter by status, scheme, provider, amount range.
2. Open a claim. Confirm side panels: member summary, benefit usage YTD, prior claims, attached documents, AI recommendation with confidence, list of rule executions and their outcomes.
3. Approve with a modified amount and an internal note. Confirm an audit event with the override reason.
4. Reject with a rejection code. Confirm the member and provider are notified per template.
5. Send to supervisor for approval (over a threshold). Confirm the supervisor sees it and can approve, modify, or reject.
6. Forward the claim to "needs more info" with a question to the provider. Confirm the provider receives the question and can respond.

### Pass criteria

- Every override carries a reason and an auditor identity.
- Supervisor approval workflow respects the configured amount threshold.

## E34. Appeals

### What it is

A rejected claim can be appealed by the provider or the member. A supervisor reviews and can accept, modify, or request more info.

### How to verify

1. As a member, view a rejected claim. Click "Appeal", attach a doctor's note, submit.
2. As supervisor, see the appeal in the queue. Open it. Review the original decision context.
3. Modify the decision — confirm the original claim is re-adjudicated under the appeal context, the new decision is audit-logged, and the member is notified.

### Pass criteria

- The full history of the original decision and the appeal is preserved.
- Re-adjudication runs through the full pipeline with the appeal context.

## E35. Drug claims

### What it is

Pharmacy claims with NAPPI code, dosage, quantity, prescription verification, and formulary checks.

### How to verify

1. Pharmacy submits a drug claim. Confirm NAPPI lookup returns the drug name and unit price.
2. Submit a drug that is not on the tenant's formulary — confirm a hard reject with the formulary code.
3. Submit an unusual dosage for the drug — confirm a soft flag and routing to manual review.
4. Submit without a prescription image — confirm a reject for missing supporting document.

### Pass criteria

- Formulary check is enforced.
- Prescription is required where the tenant policy says so.

---

# Part F — Finance, payments and reconciliation

## F36. Payment run lifecycle

### What it is

A batch of outgoing payments to providers, prepared as a draft, optionally approved by a senior (dual approval over threshold), then executed. Each payment is locked to its currency and FX rate at execution time.

### How to verify

1. Finance clerk opens `/tenant/finance/payment-runs/new`. Filters claims by status (`ADJUDICATED`, payable), period, provider, currency.
2. Reviews the list. Excludes one claim. Submits as draft.
3. If total exceeds the dual-approval threshold, the finance HoD must approve. Confirm the HoD sees the run, reviews per-currency totals, and approves.
4. Click "Execute". Payments are dispatched to the configured payment provider per currency. Webhook confirmations update each payment's status.
5. Confirm `medfund.payments.outbound` and `medfund.payments.committed` events fired.
6. Confirm provider balances updated (claimed/approved/paid deltas) per currency.
7. Provider receives the payment notification and (where applicable) the payout in their bank or mobile money.

### Pass criteria

- A single run can include payouts in multiple currencies, each with a locked rate.
- Dual approval blocks execution until the second approver approves.
- Idempotent on retry: re-clicking "Execute" after a network blip does not double-pay.

## F37. Inbound payments

### What it is

Member and group contributions received through online providers (Paynow, Stripe, Paystack, EcoCash, InnBucks, DPO) or manual entry (cash, bank transfer with manual marking).

### How to verify

1. Member pays an invoice via Paynow. Confirm the redirect succeeds, the webhook arrives, the payment is recorded with status `COMPLETED` and the invoice marked paid.
2. Member pays partially. Confirm the invoice has status `PARTIALLY_PAID` and a remaining balance.
3. Member overpays. Confirm the overage is credited to the next invoice cycle.
4. Provider's webhook signature is invalid — payment is rejected and a security event is recorded.
5. Idempotency: replay the same successful webhook. Confirm no second payment row.
6. Finance clerk records a manual cash payment for an offline member; confirm a receipt is generated and the audit event names the actor.

### Pass criteria

- Idempotency keys work on every provider.
- Multi-currency invoices accept payment in any provider that supports the invoice currency.
- Manual entry produces a clear audit trail.

## F38. Outbound payouts

### What it is

Provider payments, member refunds, and group refunds. Multi-currency in one run is supported. Rate locking at commit.

### How to verify

1. Build a run with three providers in three currencies. Execute. Confirm each payment uses the rate locked at execution time.
2. Refund a member who was over-charged. Confirm the refund passes the same approval workflow and is recorded as a negative payment.

### Pass criteria

- Multi-currency rate locks are recorded with the payment row.
- Refunds appear distinctly in reports (not netted silently).

## F39. Adjustments, notes, credit/debit notes, advances, CTC

### What it is

Manual adjustments to a member's, group's, or provider's balance with a reason — e.g., a goodwill credit, a clawback, an advance against future claims, a CTC (training/certification) payment.

### How to verify

1. Issue a goodwill credit to a member: amount, currency, reason text. Confirm the credit appears on the member's statement and an audit event with the actor identity.
2. Issue a debit note against a provider for overpayment recovery; confirm the provider sees it on their next payment advice.
3. Issue an advance to a provider; confirm the advance recovers automatically against the next eligible claim payouts.
4. Record a CTC payment; confirm it lands in the right reporting category.

### Pass criteria

- Every adjustment carries a reason and an auditor identity.
- Notes do not silently change balances — they explain them.

## F40. Bank reconciliation

### What it is

Match the platform's payment records to actual bank statement line items.

### How to verify

1. Finance clerk uploads a bank statement (CSV). The platform attempts auto-match against payments by reference, amount, and date.
2. Matched lines transition to `MATCHED`. Unmatched lines transition to `UNMATCHED` for investigation.
3. Investigate an unmatched line. Either link it to an existing payment (resolving a reference mismatch) or create a manual journal entry. Status moves to `RESOLVED`.
4. Variance reports show by-currency totals.

### Pass criteria

- Auto-match works on the common reference patterns the tenant uses.
- Manual matches are auditable and reversible within a tolerance window.

## F41. Payment advice documents

### What it is

A per-run PDF (and CSV) summarising the payments made to a provider, with per-claim line items.

### How to verify

1. Generate the payment advice for a completed run. Confirm the PDF is branded with the tenant's logo and palette.
2. Open the CSV. Confirm columns match (claim number, member number, service date, claimed, approved, paid, currency).

### Pass criteria

- The advice ties exactly to the payment run total.
- Multi-currency runs produce per-currency sections.

## F42. Financial reports

### What it is

Claims summary, payment summary, provider performance, contribution summary, with a chosen reporting currency.

### How to verify

1. Open `/tenant/finance/reports`. Select period (Q2 2026).
2. Generate the claims summary; confirm counts and amounts by status, by line of business, by currency.
3. Toggle reporting currency to USD; confirm all amounts re-render using the historical rates locked on each transaction.
4. Generate the provider performance report; confirm approval rate, average claim amount, top providers by volume.
5. Generate the contribution summary; confirm billed vs collected, by group, by scheme, by currency.
6. Export each report as PDF and CSV.

### Pass criteria

- Reporting currency conversion uses transaction-time historical rates, not the current rate.
- The same report rendered in two currencies ties exactly to the underlying multi-currency totals.
- All KPIs and chart data come from server-side endpoints — never aggregated in the browser.

---

# Part G — Real-time, chat and AI surfaces

## G43. Live dashboard (per tenant)

### What it is

A WebSocket-driven dashboard showing the tenant's claims pipeline, revenue by currency, provider balances, and other KPIs in real time. Implemented in Phoenix LiveView with a Kafka-fed event aggregator.

### How to verify

1. Log in as a tenant admin or operations user. Open `/tenant/dashboard`.
2. In a second window, submit a claim, adjudicate it, then issue a payment. Confirm the dashboard updates within a couple of seconds without a refresh.
3. Disconnect the websocket (e.g., put the tab in background). Reconnect. Confirm a snapshot is delivered on join so the UI catches up.
4. Confirm per-currency revenue tiles are kept separate.
5. Two operators viewing the same dashboard see the same numbers.

### Pass criteria

- WebSocket reconnects gracefully.
- Snapshot on join matches subsequent live deltas (no drift).
- Per-currency totals never aggregate across currencies in the UI.
- All numbers come from server-side aggregations — never re-computed in the browser.

## G44. Member/provider chat

### What it is

A real-time chat channel per room (e.g., support, claim-discussion), with typing indicators, read receipts, file shares, and optional AI assist.

### How to verify

1. A member opens a chat room with support. Sees recent history.
2. A support agent types — the member sees the typing indicator.
3. The agent sends a message; the member sees it instantly; a read receipt fires when the member opens the chat.
4. The agent attaches a PDF; the member can download it.
5. The agent triggers `chat:ai_assist` — the AI service returns a suggested reply with a confidence score; the agent edits and sends.
6. Disconnect / reconnect — history loads cleanly.

### Pass criteria

- No message loss across reconnects.
- Read receipts are accurate.
- AI assist returns within the configured SLA, and never appears as a "live agent" message.

## G45. Member chatbot

### What it is

A Claude-powered chatbot inside the member mobile app. Strictly read-only scope — FAQ, account balance, claim status, scheme/benefit lookup. Never writes data.

### How to verify

1. Member asks "What is my outstanding balance?" — chatbot answers with the tenant's currency.
2. Member asks "Why was claim CLM-2026-000123 rejected?" — chatbot summarises the rejection reason in plain English.
3. Member asks a sensitive question outside scope (e.g., "Approve my claim") — chatbot politely refuses and routes to a human.
4. Member asks a clinical question — chatbot refuses to give clinical advice and refers to a healthcare professional.

### Pass criteria

- Chatbot never performs a write action.
- It refuses out-of-scope and clinical queries cleanly.
- Source and confidence are recorded with every reply.

## G46. Fraud anomaly surfaces

### What it is

Claims flagged by the fraud model surface in a fraud queue with risk indicators (high amount, many procedures, duplicate, unusual diagnosis-procedure combo) and drill-down to features.

### How to verify

1. Open `/tenant/claims/fraud-queue`. Confirm flagged claims appear with risk scores and indicators.
2. Open a flagged claim. Confirm the indicator list (e.g., `HIGH_VALUE_CLAIM`, `MANY_PROCEDURES`, `DUPLICATE_SUSPECTED`).
3. Confirm, override, or dismiss. Each action is audit-logged with the actor identity.
4. Provider with many flagged claims appears in the provider risk dashboard with trend lines.

### Pass criteria

- Flags include explanations a human can act on.
- Confirming a flag teaches the model (feedback recorded).

## G47. Financial forecasting

### What it is

A simple time-series forecast (Prophet/ARIMA) for cash flow, contributions inflow, and claims outflow. Reserve adequacy check against regulatory minimums.

### How to verify

1. Open `/tenant/finance/forecasting`. Pick "Claims outflow, next 6 months".
2. Confirm a forecast curve with confidence intervals appears.
3. Pick "Contributions inflow"; confirm seasonality is honoured.
4. Pick "Reserve adequacy"; confirm a clear pass/fail against the configured threshold.

### Pass criteria

- Forecasts are reproducible (same input → same output).
- Confidence intervals are shown alongside point forecasts.

---

# Part H — Documents, exports and bulk operations

## H48. File uploads

### What it is

Members, providers, and operations users can attach files — claim documents, profile photos, supporting evidence — via presigned URLs to S3.

### How to verify

1. Provider uploads a claim attachment. Confirm a presigned URL is issued, the file lands in S3 under the tenant's prefix, and a row in the documents table references it.
2. Virus scan runs asynchronously; an infected file is quarantined and the upload is rejected with a notification.
3. Confirm a member from Tenant A cannot fetch a URL belonging to Tenant B (the URL is keyed to the tenant prefix and the presign expires).
4. Delete an attachment; confirm the document row is soft-deleted and the S3 object is scheduled for deletion.

### Pass criteria

- Cross-tenant URL access is impossible.
- Virus scan blocks infected uploads.
- Presigned URLs expire on the configured TTL.

## H49. Exports

### What it is

PDF generation for invoice, statement, payment advice. CSV/XLSX exports for claims, members, payments, audit events.

### How to verify

1. Generate an invoice PDF. Confirm tenant branding, the correct currency formatting, and a deterministic filename.
2. Generate a statement PDF for a member.
3. Generate a payment advice PDF for a provider.
4. Export the claims list as CSV with applied filters. Confirm the file has the same row count and totals as the on-screen list.
5. Export a date-bounded audit set; confirm immutable fields are present.

### Pass criteria

- Branded outputs honour tenant settings.
- Exports match on-screen filters exactly.
- Multi-currency totals are preserved per currency, never summed across currencies.

## H50. Bulk import

### What it is

Bulk CSV import for members, tariffs, ICD-10 codes, drug catalogue, rule templates.

### How to verify

1. Upload a members CSV (well-formed). Confirm row counts, success, and audit events per member.
2. Upload a malformed CSV. Confirm errors are reported per row, partial import is either fully blocked or carefully reported, and no half-state exists.
3. Re-upload the same file. Confirm idempotency — no duplicates, only updates to changed fields.
4. Import a tariff schedule with a new version. Confirm the old version remains queryable.

### Pass criteria

- Idempotency on re-upload.
- Per-row error reporting.
- No silent partial imports.

---

# Part I — Cross-cutting acceptance tests

These are the smoke tests that prove the platform's defining properties end-to-end. Treat them as the release gate.

## I51. Tenant isolation smoke test

### Steps

1. Create Tenants A and B. Seed each with members, providers, claims, payments.
2. Log in as Tenant A admin. Capture every URL pattern in the app.
3. Replace `tenant_A_*` IDs with `tenant_B_*` IDs in URLs and API calls; expect 404 or 403 on every one.
4. Set `X-Tenant-ID` header to Tenant B's UUID from a Tenant A admin session; expect 403.
5. Inspect Tenant A's database connection; query Tenant B's schema; expect a permission error.
6. Confirm the `medfund.audit.events` and `medfund.security.events` Kafka topics carry the correct `tenantId` for every event.

### Pass criteria

- Zero cross-tenant data leaks.
- Tenant-bound queries never accidentally hit `public.<tenant-table>` (see the "public.<tenant-table> silent ROLLBACK" rule in Appendix F).

## I52. Multi-currency smoke test

### Steps

1. Tenant has USD, ZWL, ZAR enabled. USD is default.
2. Issue a USD invoice to a member. Member pays via EcoCash in ZWL with a locked rate.
3. The same tenant pays a provider in ZAR from a payment run with a different locked rate.
4. Generate the period's claims report in USD. Confirm conversions use historical, locked rates.
5. Move the USD→ZWL rate by 15% overnight. Confirm yesterday's invoice still uses yesterday's rate; today's new invoices use today's rate; a sanity alert fired on the >10% jump.

### Pass criteria

- Rate locking holds across restate operations.
- Reporting currency switches yield consistent reconciliation to multi-currency source.

## I53. Settings-driven rebrand smoke test

### Steps

1. Take screenshots of the member portal, the provider portal, and a sample transactional email "before".
2. Change logo, primary colour, login background, email "from" name, SMS sender ID, and custom domain.
3. Take the same screenshots "after". Trigger one of every notification kind and observe.
4. Confirm no mention of "InsureFlow" in the rebranded screens or emails.
5. Open the Flutter mobile app, sign out, sign in via the tenant-branded login — confirm the rebrand is reflected there too.

### Pass criteria

- The rebrand is consistent across web, mobile, email, SMS, PDF documents.
- The change took effect within the configured cache TTL.

## I54. Disaster drills

### Schema migration rollback

1. Apply a known-bad Flyway migration on a staging tenant; confirm the rollback procedure restores the previous version cleanly.

### Payment provider webhook replay

1. Replay a payment webhook for a transaction that was already processed. Confirm the second delivery is a no-op due to idempotency.

### Kafka consumer lag recovery

1. Stop a consumer service (e.g., the audit consumer). Generate 10k events. Restart. Confirm the consumer drains within the SLA, no events are lost, none are duplicated.

### Keycloak realm export/import

1. Export a tenant realm. Re-import into a fresh Keycloak. Confirm users, roles, OIDC clients, and event listener config survive.

### Pass criteria

- Each drill returns the system to known-good state with zero data loss.

## I55. Performance and SLA targets

| Surface | Target |
| --- | --- |
| Claim submission API | p95 < 400ms |
| Adjudication pipeline (no AI) | p95 < 1.5s |
| Adjudication pipeline (with AI) | p95 < 5s |
| Payment run execution (100 items, single currency) | < 60s |
| Dashboard WebSocket lag | < 2s end-to-end |
| Tenant provisioning | < 60s from request to first login |
| 30-day regulatory SLA on claims | tracked and visible per claim |

### How to verify

1. Hammer each API surface with a representative load profile; record p50, p95, p99.
2. Confirm the SLA tracker on each claim displays days-to-deadline and turns amber/red at the configured thresholds.

## I56. Compliance checks

1. Verify PHI columns are encrypted at rest using the per-tenant KMS key.
2. Verify TLS 1.3 is enforced across all public surfaces; HSTS is set.
3. Verify audit events cannot be modified or deleted (database-level constraints).
4. Verify the 7-year retention policy is configured on the audit and security events tables.
5. Verify the GDPR data export and deletion flows work for a test member.
6. Verify MFA enforcement at the configured roles by attempting login without the required factor.

---

# Part J — Appendices

## Appendix A — Kafka topic reference

| Topic | Producer(s) | Consumer(s) | Payload essentials |
| --- | --- | --- | --- |
| `medfund.tenants.provisioned` | tenancy-service | user-service, contributions-service, claims-service, audit-service | tenantId, slug, schemaName, keycloakRealm |
| `medfund.tenants.lifecycle` | tenancy-service | all services | tenantId, status (SUSPENDED / ACTIVATED) |
| `medfund.tenants.config-changed` | tenancy-service | rules-engine, all services | tenantId, setting key, new value |
| `medfund.users.member-enrolled` | user-service | contributions-service, notification-service, audit-service | tenantId, memberId, memberNumber, effectiveDate |
| `medfund.permissions.invalidated` | user-service | all services (cache invalidation) | userId, role IDs affected |
| `medfund.claims.submitted` | claims-service | ai-service, audit-service, live-dashboard | claimId, claimNumber, memberId, providerId |
| `medfund.claims.verified` | claims-service | audit-service, notification-service | claimId, verifiedBy, channel |
| `medfund.claims.adjudicated` | claims-service | finance-service (provider balance), notification-service, live-dashboard, audit-service | claimId, decision, approvedAmount, currencyCode, providerId |
| `medfund.claims.lifecycle` | claims-service | audit-service | claimId, oldStatus, newStatus |
| `medfund.claims.pre-auth-decision` | claims-service | notification-service, audit-service | preAuthId, decision, approvedAmount, expiry |
| `medfund.contributions.billed` | contributions-service | notification-service, audit-service | tenantId, runId, memberIds, totalsByCurrency |
| `medfund.contributions.paid` | contributions-service | finance-service, audit-service | contributionId, amount, currency, paymentMethod |
| `medfund.payments.inbound` | payment-gateway / finance-service | finance-service, audit-service | paymentId, source, amount, currency |
| `medfund.payments.outbound` | finance-service | payment-gateway, audit-service | paymentId, providerId, amount, currency |
| `medfund.payments.committed` | finance-service | live-dashboard, notification-service, audit-service | runId, totalsByCurrency |
| `medfund.payments.webhook` | payment-gateway | finance-service | provider, txnId, status |
| `medfund.documents.uploaded` | file-service | ai-service (OCR), notification-service | tenantId, documentId, contentType |
| `medfund.rules.updated` | rules-engine | rules-engine (KieContainer reload), audit-service | tenantId, ruleId, version |
| `medfund.audit.events` | every service | audit-service | entityType, entityId, entityName, action, actorId, actorEmail, oldValues, newValues, correlationId |
| `medfund.security.events` | gateway, keycloak-event-listener, every service | audit-service | type, userId, email, tenantId, ip, userAgent |

## Appendix B — REST controller reference

The full inventory by service is captured here for completeness. Each line is a controller + a short note on what it does. Endpoints are documented in OpenAPI (`/swagger-ui` per Java service, `/docs` per Python service).

### Tenancy service (Java, port 8081)

- `TenantController` — tenant CRUD, suspend/activate, per-tenant email-template override.
- `PlanController` — subscription plans the platform offers tenants.
- `CurrencyController` — master currency registry.
- `TenantCurrencyController` — per-tenant currency config (enabled currencies, default).
- `ExchangeRateController` — daily rate snapshots, history, per-tenant override.
- `PlatformStatsController` — platform-wide aggregated metrics.

### User service (Java, port 8082)

- `MemberController` — member CRUD, status transitions, Keycloak sync.
- `GroupController` — group CRUD.
- `GroupLiaisonController` — liaison CRUD + Keycloak grant.
- `ProviderController` — provider registry (platform-wide).
- `DependantController` — dependant CRUD.
- `StaffUserController` — staff CRUD, Keycloak sync to `medfund-platform` realm.
- `RoleController` — role CRUD, assignment, permission editing.
- `PermissionController` — canonical permission catalogue, `/me/permissions`.
- `EmailSenderController`, `EmailCampaignController` — bulk email.
- `TenantStatsController`, `PlatformStatsController` — KPI feeds.
- `WaitingPeriodController` — per-member waiting-period management.

### Claims service (Java, port 8083)

- `ClaimController` — submit, verify, adjudicate, status update.
- `PreAuthController` — pre-auth CRUD + approve/reject.
- `DrugClaimController`, `DrugController` — drug claim flow + drug catalogue.
- `TariffController` — tariff CRUD + bulk import.
- `IcdCodeController` — ICD-10 catalogue.
- `RejectionReasonController` — rejection code library.
- `QuotationController` — pre-claim cost estimate.
- `PlatformStatsController`.

### Contributions service (Java, port 8084)

- `ContributionController` — preview / commit / pay; background-job enqueue.
- `SchemeController` — scheme CRUD + benefits + age-group pricing.
- `SchemeChangeController` — request / approve / reject.
- `BillingCatalogueController` — billing configuration per tenant.
- `BalanceController` — running balances per entity per currency.
- `TransactionController` — transaction history.
- `BadDebtController` — write-offs + recoveries.
- `WaitingPeriodController` — per-scheme waiting periods.
- `InvoiceController`, `StatementController` — invoice + statement issuance.
- `InsuranceQuoteController` — premium quotation.
- `GroupSearchController` — search groups for billing scope.

### Finance service (Java, port 8085)

- `PaymentController`, `PaymentRunController` — payments + runs.
- `ProviderBalanceController` — per-provider per-currency balances (driven by `medfund.claims.adjudicated`).
- `ReconciliationController` — bank reconciliation.
- `TenantBankAccountController` — tenant bank-account management (outbound disbursements + inbound receipt matching). Mutating verbs gated by `admin.bank_accounts:manage`.
- `AdjustmentController`, `AdvancePaymentController`, `CtcPaymentController`, `NotesController`, `PaymentAdviceController` — adjustments / advances / CTC / notes / advice.
- `ReportController` — claims / payment / provider performance / contribution reports.

### Rules engine (Java)

- `TenantRuleController` — rules CRUD per tenant, `enable`/`disable`/`dry-run`, hot-reload via Kafka.
- `RuleTemplateController` — template library.

### Go services

- `gateway` — auth (JWT against Keycloak JWKS), tenant resolution, routing, rate limiting, CORS, security event publishing.
- `notification-service` — `POST /api/v1/notifications/send` (email / SMS / push / in-app dispatch).
- `audit-service` — event ingestion (Kafka consumer), `GET /api/v1/audit/events` with filters, daily counts.
- `file-service` — presigned URLs (upload/download), PDF & CSV exports.
- `payment-gateway` — provider abstraction (Paynow, Stripe, Paystack, EcoCash, InnBucks, DPO, manual), webhook receiver.

### Elixir services

- `live_dashboard` — `dashboard:{tenant_id}`, `claims:{tenant_id}`, `finance:{tenant_id}` channels.
- `chat_service` — `chat:{room_id}` channel, message history, AI assist proxy.

### Python AI service

- `/api/v1/ai/health`, `/adjudication/recommend`, `/adjudication/check-duplicate`, `/adjudication/suggest-tariff`, `/fraud/check`, `/ocr/extract`, `/chat/message`, `/analytics/anomalies`, `/analytics/provider-stats`, `/analytics/forecast`.

## Appendix C — Canonical permission catalogue

Permissions follow `domain.section:action`. Action is one of `read | write | delete | approve | export | configure`. The catalogue is exposed at `GET /api/v1/permissions/catalogue` and rendered in the tenant admin role editor.

| Domain | Section examples | Actions |
| --- | --- | --- |
| `claims` | `queue`, `pre_auth`, `tariffs`, `icd_codes`, `drugs`, `rejection_reasons`, `appeals`, `fraud` | read, write, approve, configure |
| `contributions` | `billing_runs`, `invoices`, `members`, `groups`, `scheme_changes`, `bad_debt`, `quotation` | read, write, delete, approve, export |
| `finance` | `payment_runs`, `payments_inbound`, `payments_outbound`, `adjustments`, `reconciliation`, `reports`, `advice` | read, write, approve, export |
| `members` | `profile`, `dependants`, `documents`, `statements` | read, write, delete |
| `providers` | `profile`, `tariff_assignments`, `payment_history` | read, write, approve, configure |
| `rules` | `library`, `templates`, `test_sandbox` | read, write, configure |
| `audit` | `events`, `daily_counts` | read, export |
| `settings` | `branding`, `domain`, `currencies`, `lines_of_business`, `auth_policy`, `payment_providers`, `notifications`, `ai_config` | read, configure |
| `users` | `staff`, `roles`, `permissions`, `mfa_policy` | read, write, delete, configure |

Tenant admins map any subset of these to a custom role and assign that role to users. Permission cache invalidates within 60s of a change (via `medfund.permissions.invalidated`).

## Appendix D — Rejection code reference

| Code | Stage | Meaning | Severity |
| --- | --- | --- | --- |
| `R01` | Eligibility | Member not active on service date | Hard reject |
| `R02` | Waiting period | Waiting period not yet served | Hard reject |
| `R03` | Benefit limit | Benefit annual or lifetime limit exhausted | Hard reject / partial |
| `R04` | Pre-auth | Pre-authorization required but absent | Hard reject |
| `R05` | Pre-auth | Pre-authorization expired or exceeded | Hard reject |
| `R06` | Tariff | Invalid tariff code for provider specialty | Hard reject |
| `R07` | Tariff | Modifier rules violated (e.g., bilateral on a non-bilateral code) | Hard reject |
| `R08` | Clinical | Diagnosis-procedure mismatch | Manual review |
| `R09` | Clinical | Frequency exceeded (e.g., repeat procedure too soon) | Hard reject |
| `R10` | Tariff | Upcoding suspected | Manual review |
| `R11` | Eligibility | Provider not verified or not in network | Hard reject |
| `R12` | Clinical | Gender / age inappropriate for procedure | Hard reject |
| `R13` | Clinical | Duplicate claim | Hard reject |
| `R14` | Eligibility | Submitted past tenant's time window | Hard reject |
| `R15` | Eligibility | Member in arrears beyond tolerance | Hard reject |
| `R16` | Drug | Drug not on formulary | Hard reject |
| `R17` | Drug | Missing prescription image | Hard reject |
| `R18` | Verification | Member never verified the claim within the window | Hard reject |

Tenant admins can extend the code list and customise messaging per code per locale.

## Appendix E — Tenant-configurable settings index ("white-label switch panel")

This is the single source of truth for "everything a tenant can change without code." Use it as a release checklist.

### Identity & branding

- Tenant name, slug, country, primary contact email.
- Logo, favicon, login background, primary colour, secondary colour, font family.
- Public landing copy (per locale).
- Subdomain (`<slug>.medfund.healthcare`) and optional custom domain (`portal.<tenant>.com`).
- Email "from" name and address. SMS sender ID.

### Lines of business

- Medical / Life / Funeral / Motor / Asset / GPA / Travel — toggle each.
- Per-line scheme templates (defaults seeded; edit freely).

### Currencies & FX

- Enabled currencies; default currency.
- FX source (manual, RBZ, OpenExchangeRates, tenant-provided fixed).
- Per-group billing currency override.
- 10%+ rate-move sanity threshold.

### Schemes & benefits

- Scheme CRUD with age-group pricing per currency.
- Per-benefit annual limit, lifetime limit, per-event limit, waiting period, copay, family pool flag.
- Scheme effective dating and versioning.

### Rules

- Rule libraries per six categories.
- Template-based authoring.
- Hot-reload on activate.
- Per-rule effective dating.
- Dry-run sandbox.

### Auth policy

- Required MFA factors per role.
- Social login providers (Google, Microsoft, Apple, SAML) enabled per tenant.
- Session timeout, brute-force lockout thresholds.
- Password complexity policy.

### Payment providers

- Enable / disable each provider; sandbox vs live; per-currency provider preference.
- Per-provider webhook secret and idempotency key strategy.

### Notification channels & templates

- SMTP/SES/Mailgun/Resend email channel credentials.
- Twilio/Africa's Talking/Vonage SMS channel credentials.
- Firebase Cloud Messaging credentials.
- Per-event templates per locale (welcome, invoice, claim adjudicated, payment received, OTP, etc.).
- Variable substitution catalogue.

### AI configuration

- Auto-approve confidence threshold.
- Fraud-flag threshold.
- AI model version pin.
- Whether to fall back to rule-based on AI outage.

### Operational policy

- Arrears thresholds (30/60/90), auto-suspension flag.
- Pre-auth tariff scope (which tariffs require pre-auth, value threshold).
- Claim submission time window (e.g., 60 days after service).
- Dual-approval threshold on payment runs.

### Audit & security

- Audit retention (≥ 7 years; can be longer per jurisdiction).
- Security event alerting thresholds.
- Allowed IP ranges per admin role (optional).

## Appendix F — Expanded glossary and known business rules

The glossary in A2 covers the day-to-day terms. This appendix adds the rules and traps that bite if forgotten.

### Members and enrolment

- **Effective date is always 1st-of-month.** Back-dated enrolment to an earlier 1st-of-month triggers an arrears adjustment for the missed cycles. This is a *contributions-side* posting, not silently absorbed. UI must surface it before commit.
- **No raw ID inputs in forms.** Wherever a UI needs to capture a group ID, scheme ID, member ID, etc., use a debounced search-select. The payload carries the ID; the UI shows the name.

### Adjudication and rules

- **Per-tenant rules engine isolation.** Each tenant's KieContainer is minted with a per-tenant `ReleaseId`. Rule changes in Tenant A must never affect Tenant B's running container.
- **Six-stage order is fixed.** Stage 1 failure must short-circuit; stages 2–6 do not run. This is testable explicitly.

### Audit hygiene

- **`actorEmail` is always populated.** Every `AuditEvent.create` must pass `actorEmail` via the shared `AuditActor` helper. Never null, never inline JWT extraction.
- **`entityName` is a friendly text label.** Never the UUID. Use the per-entity name field (claim number for `claim`, member number for `member`, etc.).

### Database

- **Don't query `public.<tenant-table>` for tenant data.** Tenant tables live in the tenant schema only. The `public.` prefix on a tenant-scoped query silently fails inside `onErrorResume` and poisons the transaction with an opaque rollback. Use unqualified names for tenant tables; prefix `public.` only for platform-wide tables.

### Statistics

- **Stats are server-side.** Every KPI tile and chart consumes a pre-computed endpoint. No aggregation in the Angular or Flutter client.

## Appendix G — Manual test execution log template

For each release, walk through every chapter's "How to verify" steps and tick each:

```
Release: ____________
Date: __________
Tester: ____________
Tenants used: A=__________, B=__________

Part A — Platform overview ................. [ ] reviewed
Part B — Tenant provisioning & white-label
  B7  Tenant onboarding ........................ [ ] manual  [ ] self-service
  B8  Branding & white-labelling ................ [ ] web    [ ] mobile  [ ] email
  B9  Lines of business ......................... [ ] enable [ ] disable [ ] data preserved
  B10 Currency configuration .................... [ ] add    [ ] FX source [ ] override
  B11 MFA & auth policy ......................... [ ] TOTP   [ ] OTP     [ ] lockout
  B12 Payment provider wiring ................... [ ] sandbox [ ] webhook
  B13 Notification channels & templates ......... [ ] email  [ ] SMS     [ ] locale
Part C — Identity, access & audit
  C14 Roles & permissions ....................... [ ] create [ ] grant   [ ] revoke 60s
  C15 Staff lifecycle ........................... [ ] invite [ ] suspend [ ] terminate
  C16 Member/dependant/provider/liaison lifecycles [ ] each transition audited
  C17 Audit trail ............................... [ ] required fields    [ ] immutable
  C18 Security events ........................... [ ] auth   [ ] brute   [ ] impersonation
Part D — Membership, groups & contributions
  D19 Group management .......................... [ ] PHI shielded
  D20 Member enrolment .......................... [ ] group  [ ] individual [ ] back-dated
  D21 Schemes & benefits ........................ [ ] pricing [ ] waiting period
  D22 Scheme change workflow .................... [ ] effective date [ ] waiting reset
  D23 Billing & invoicing ....................... [ ] preview [ ] commit [ ] cooldown
  D24 Balances & statements ..................... [ ] PDF    [ ] CSV
  D25 Arrears, reminders, bad debt .............. [ ] 30/60/90 [ ] write-off [ ] recovery
  D26 Insurance quotation ....................... [ ] all schemes
Part E — Claims, pre-auth & adjudication
  E27 Provider onboarding & tariff setup ........ [ ] verify [ ] bulk import
  E28 Pre-authorization workflow ................ [ ] request [ ] approve [ ] expiry
  E29 Claim submission channels ................. [ ] portal [ ] OCR    [ ] drug claim
  E30 Member verification of claims ............. [ ] OTP    [ ] QR     [ ] timeout
  E31 Six-stage adjudication pipeline ........... [ ] each stage hard-fail/pass tested
  E32 AI-assisted decisioning ................... [ ] threshold [ ] model pin [ ] override
  E33 Manual adjudication workspace ............. [ ] override audit
  E34 Appeals ................................... [ ] re-adjudicate full pipeline
  E35 Drug claims ............................... [ ] formulary [ ] prescription
Part F — Finance, payments & reconciliation
  F36 Payment run lifecycle ..................... [ ] draft [ ] approve [ ] execute
  F37 Inbound payments .......................... [ ] online [ ] manual [ ] idempotent
  F38 Outbound payouts .......................... [ ] multi-currency [ ] rate lock
  F39 Adjustments, notes, advances, CTC ......... [ ] all audited
  F40 Bank reconciliation ....................... [ ] auto match [ ] investigate
  F41 Payment advice documents .................. [ ] PDF/CSV match run
  F42 Financial reports ......................... [ ] reporting currency conversion
Part G — Real-time, chat & AI surfaces
  G43 Live dashboard ............................ [ ] reconnect [ ] snapshot
  G44 Member/provider chat ...................... [ ] history [ ] AI assist
  G45 Member chatbot ............................ [ ] read-only [ ] refusal
  G46 Fraud anomaly surfaces .................... [ ] explanations [ ] feedback
  G47 Financial forecasting ..................... [ ] reproducible
Part H — Documents, exports & bulk operations
  H48 File uploads .............................. [ ] virus scan [ ] tenant prefix
  H49 Exports ................................... [ ] branded   [ ] match filters
  H50 Bulk import ............................... [ ] idempotent [ ] per-row errors
Part I — Cross-cutting acceptance tests
  I51 Tenant isolation .......................... [ ] no leaks
  I52 Multi-currency ............................ [ ] rate lock holds
  I53 Settings-driven rebrand ................... [ ] web/mobile/email/PDF
  I54 Disaster drills ........................... [ ] migration [ ] webhook [ ] Kafka [ ] Keycloak
  I55 Performance / SLA ......................... [ ] all targets met
  I56 Compliance ................................ [ ] encryption [ ] retention [ ] MFA

Sign off: ______________________________  Date: ____________
```

---

# End of document

This document is the contract for "fully working" InsureFlow. Any feature not in this manual is either out of scope or, if it ought to be in scope, is a gap to log and address. The settings index in Appendix E is the daily reference for tenant onboarding and white-label work. The cross-cutting acceptance tests in Part I are the release gate.

Reviewers — edit freely. Suggested additions: real screenshots in `docs/images/`, a tenant-specific addendum for any tenant that introduces a non-standard line of business or jurisdictional requirement, and a separate operational runbook for the on-call team that maps each surface in this document to a Grafana board.
