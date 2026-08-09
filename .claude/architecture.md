# Architecture Overview

## What InsureFlow Is

InsureFlow is a **core insurance operating system** — a multi-tenant SaaS whose Java domain services are line-agnostic and today host eight insurance lines via the `InsuranceLine` enum (`HEALTH`, `LIFE`, `FUNERAL`, `GROUP`, `TRAVEL`, `DISABILITY`, `VEHICLE`, `PROPERTY`).

- **Person-centric lines** — `HEALTH`, `LIFE`, `FUNERAL`, `DISABILITY`, `TRAVEL`, `GROUP` — share the member/dependant model and adjudicated claims flow.
- **Asset-centric lines** — `VEHICLE`, `PROPERTY` — attach cover to an insured asset entity (`Vehicle`, `Property`) rather than requiring member identity for pricing.
- **Vertical maturity today** — the health (medical aid) vertical is production-ready end-to-end (tariffs, ICD codes, drug claims, pre-auth, AHFOZ integration). Life, funeral, disability, travel, vehicle, and property lines have policy entities, enrollment, and billing hooks in place but are not yet fully adjudicated end-to-end.

## Design Philosophy

InsureFlow is a **polyglot microservices platform** where each service is built with the language and framework best suited to its workload:

- **Java (Spring Boot)** — Domain-heavy services with complex business rules that vary per tenant *and per insurance line*
- **Go (Fiber)** — High-throughput, low-latency services where raw performance matters
- **Elixir (Phoenix)** — Real-time features leveraging BEAM's concurrency model
- **Python (FastAPI)** — AI/ML services leveraging the Python data science ecosystem

## System Architecture

```
                    ┌──────────────────────────────────────────────────┐
                    │                   CLIENTS                        │
                    │                                                  │
                    │  ┌─────────────┐  ┌──────────┐  ┌────────────┐  │
                    │  │ Angular Web │  │ Flutter  │  │ Flutter    │  │
                    │  │ (Admin/Ops) │  │ Mobile   │  │ Web (PWA)  │  │
                    │  └──────┬──────┘  └────┬─────┘  └─────┬──────┘  │
                    └─────────┼──────────────┼──────────────┼─────────┘
                              │              │              │
                              ▼              ▼              ▼
                    ┌─────────────────────────────────────────────────┐
                    │            API GATEWAY (Go + Fiber)             │
                    │  Rate limiting, auth validation, routing,       │
                    │  request logging, tenant resolution             │
                    └──┬──────────┬───────────┬──────────┬───────────┘
                       │          │           │          │
          ┌────────────▼───┐ ┌───▼────────┐ ┌▼────────┐ ┌▼──────────────┐
          │ JAVA SERVICES  │ │GO SERVICES │ │ ELIXIR  │ │PYTHON SERVICE │
          │ (Spring Boot)  │ │ (Fiber)    │ │(Phoenix)│ │ (FastAPI)     │
          │                │ │            │ │         │ │               │
          │ Claims Service │ │ Notif. Svc │ │ Live    │ │ AI Service    │
          │ Contrib. Svc   │ │ File Svc   │ │ Dash.   │ │ - Adjudicat. │
          │ Finance Svc    │ │ Audit Svc  │ │ Chat    │ │ - Fraud Det. │
          │ Tenancy Svc    │ │ Export Svc │ │ Events  │ │ - Doc OCR    │
          │ Rules Engine   │ │            │ │ Presence│ │ - Billing ML │
          │ User Svc       │ │            │ │         │ │ - Analytics  │
          └──┬──────┬──────┘ └──┬────┬────┘ └──┬─────┘ └──┬───────────┘
             │      │           │    │         │          │
             ▼      ▼           ▼    ▼         ▼          ▼
    ┌──────────┐ ┌──────┐ ┌─────────┐ ┌────────────┐ ┌──────────┐
    │PostgreSQL│ │Redis │ │  Kafka  │ │ S3 / MinIO │ │Keycloak  │
    │ (schema  │ │      │ │         │ │            │ │ (IAM)    │
    │ per      │ │      │ │         │ │            │ │          │
    │ tenant)  │ │      │ │         │ │            │ │          │
    └──────────┘ └──────┘ └─────────┘ └────────────┘ └──────────┘
```

## Service Breakdown

### Java Services (Spring Boot 3.3 + Java 21)

These handle the **core business domain** where complex, tenant-specific rules dominate.

#### 1. Claims Service
- Line-tagged claims lifecycle (submission → verification → adjudication → payment). Every `Claim` row carries an `insurance_line` (V053 migration).
- Health-vertical specifics: medical + drug claims, ICD code validation, AHFOZ tariff lookup, tariff modifiers, drug catalogue
- Pre-authorization workflow (health vertical; dependant-aware since V064)
- Claim verification codes (SMS/email)
- Quotation workflow
- Key entities: `Claim`, `ClaimLine`, `PreAuthorization`, `Quotation`, `TariffCode`, `TariffCategory`, `TariffModifier`, `IcdCode`, `Drug`, `BeneficiaryBenefit`, `DiagnosisProcedureMapping`, `RejectionReason`
- **Per-tenant rules**: adjudication criteria, benefit limits, co-payment rules, exclusion lists, clinical validation (all delegated to rules-engine)

#### 2. Contributions Service
- Scheme and benefit management (schemes are line-scoped; benefit `usage_mode` added V061)
- Age group pricing, tariff→benefit mappings, annual caps
- Contribution billing cycles (monthly, quarterly, annual — per tenant), with one-contribution-per-month invariant enforced at V034
- Member/group balance tracking, group running balances, bad-debt tracking
- Scheme change workflows with tenant-configurable proration (7 strategies wired via `TenantProrationConfig`)
- Insurance quotes, dunning config
- Key entities: `Contribution`, `Invoice`, `Scheme`, `Benefit`, `BenefitType`, `SchemeBenefit`, `Group`, `GroupRunningBalance`, `BillingCycleConfig`, `BadDebt`, `InsuranceQuote`, `AgeGroup`, `DunningConfig`, `Beneficiary`, `BeneficiaryBenefit`
- **Per-tenant rules**: billing schedules, grace periods, penalty calculations, shortfall rules, proration strategy

#### 3. Finance Service
- Payment processing, payment runs, payment-run items
- Provider balance management, advance payments
- Debit/credit notes and adjustments
- Bank reconciliation, MASCA bank account management
- CTC (Claims-to-Contributions) payments, payment advice records
- Financial reporting and statements
- Key entities: `Payment`, `PaymentRun`, `PaymentRunItem`, `ProviderBalance`, `Adjustment`, `AdvancePayment`, `DebitNote`, `CreditNote`, `BankReconciliation`, `CtcPayment`, `PaymentAdviceRecord`, `MascaBankAccount`
- **Per-tenant rules**: payment terms, withholding tax rates, payment method restrictions

#### 4. Tenancy Service
- Tenant provisioning (create DB schema, Keycloak realm, seed data)
- Tenant configuration management (currency config, email templates, proration config, rules)
- Currency + exchange rate registry (`Currency`, `ExchangeRate`)
- Per-tenant rule storage (`TenantRule`); rules-engine reads from here
- Super admin operations (tenant CRUD, feature flags, plan limits)
- Tenant onboarding workflow
- **Owns Flyway** for the entire platform: `public` schema (V100–V127) and every tenant schema (V001–V065). No other Java service ships migrations.
- Key entities: `Tenant`, `Plan`, `TenantCurrencyConfig`, `TenantEmailTemplate`, `TenantProrationConfig`, `Currency`, `ExchangeRate`, `TenantRule`

#### 5. Rules Engine
- JSON `RuleDefinition` → Drools DRL compilation via `DrlCompiler`
- Extensible fact registry (`FACT_MAPPINGS`) — adding a new fact type does not require compiler edits
- Pluggable `ActionEmitter` beans per action type — adding a new action does not require compiler edits
- Per-tenant `ReleaseId` isolation (tenant-isolation bug guard — see [rules-engine.md](rules-engine.md))
- 15 rule-template categories shipped: `EligibilityTemplates`, `PreAuthorizationTemplates`, `ClinicalValidationTemplates`, `CoPaymentTemplates`, `BenefitLimitTemplates`, `TariffPricingTemplates`, `ContributionBillingTemplates`, `ContributionPricingTemplates`, `AgeGroupTemplates`, `WaitingPeriodTemplates`, `SchemeChangeProrationTemplates`, `ProviderPaymentTemplates`, `ReconciliationTemplates`, `MemberLifecycleTemplates`, `UnderwritingTemplates`
- Facts are line-agnostic: `ClaimFact`, `ClaimDetailFact`, `MemberFact`, `DependantFact`, `ProviderFact`, `FamilyFact`, `ContributionFact`, `MemberLifecycleFact`, `PaymentRunFact`, `SchemeChangeContext`, `TimeFact`
- Rules stored in `tenancy-service`'s `TenantRule` table and hot-reloaded per tenant

#### 6. User Service
- User profile management (members, providers, dependants, staff)
- Integration with Keycloak for auth operations
- Member enrollment and lifecycle (active → suspended → terminated); effective-date snapping (starts → 1st-of-month, terminations → last-day-of-month)
- Provider onboarding and verification (AHFOZ number for health providers)
- **Policy entities per insurance line** — this service is where non-health lines currently live:
  - `LifePolicy`, `FuneralPolicy`, `DisabilityPolicy`, `TravelPolicy` (person-centric)
  - `Vehicle`, `Property` (asset-centric)
- Email senders and email campaigns (per-tenant branding)
- **Membership models** (configurable per tenant):
  - **Group/Corporate**: Members belong to an employer group. Group pays contributions on behalf of members. Group liaison manages enrollment. This is the traditional medical aid model
  - **Individual**: Members register and pay independently. No employer group. Self-service enrollment via Flutter/Angular
  - **Hybrid (both)**: Tenant supports both group and individual members. Some members are under corporate groups, others are self-paying individuals
- Group/organization management (for corporate model)
- **Group liaison** role: manages group members, dependants, and billing — explicitly blocked from claims/PHI (relevant to the health vertical)
- Individual self-registration workflow (for individual model)
- Tenant configuration: `membership_model` = `GROUP_ONLY`, `INDIVIDUAL_ONLY`, or `BOTH`
- Roles + permissions (`Role`, `Permission`) — RBAC catalogue

### Go Services (Fiber v2)

These handle **high-throughput, stateless operations** where Go's performance shines.

#### 7. API Gateway
- Request routing to downstream services
- JWT validation (verify Keycloak tokens)
- Tenant resolution from subdomain / JWT / header
- Rate limiting (per tenant, per user)
- Request/response logging
- CORS handling
- API versioning routing

#### 8. Notification Service
- Email dispatch (via AWS SES / Resend)
- SMS dispatch (via Twilio / Africa's Talking)
- Push notifications (via FCM for Flutter mobile)
- Template management (per-tenant branding)
- Delivery tracking and retry logic
- **Replaces**: Email-Service (Flask)

#### 9. Audit Service
- High-throughput event ingestion from Kafka
- Immutable append-only audit log storage
- Query API for audit trail retrieval
- Compliance reporting
- **Replaces**: Masca-Admin-Backend (entire separate Django app)

#### 10. File & Export Service
- Document upload/download (S3 presigned URLs)
- PDF generation for invoices, payment advice, statements
- CSV/XLSX export for financial reports
- Bulk import processing (member data, tariff codes)

#### 11. Payment Gateway Service
- Unified abstraction over multiple payment providers
- Handles inbound payments (contributions, subscriptions) and outbound payouts (provider payments, member refunds)
- Webhook receiver for async payment confirmations
- **Current state:** service is scaffolded (~10 files); no live processor integrations yet
- See [payments.md](payments.md) for the target specification

### Elixir Services (Phoenix 1.7)

These handle **real-time, concurrent** features where BEAM excels.

#### 12. Live Dashboard Service
- WebSocket channels for real-time dashboard updates
- Live claim status tracking
- Real-time financial dashboard (payment runs, balances)
- Active user presence tracking
- Live notifications feed

#### 13. Chat Service
- Member support chat (with AI-assisted responses via Python AI Service)
- Internal staff messaging
- Chat history persistence
- Typing indicators, read receipts
- Presence awareness

### Python Service (FastAPI)

#### 14. AI Service
See [ai-integration.md](ai-integration.md) for full details. Seven FastAPI modules today:
- `adjudication` — claims auto-adjudication assistance
- `fraud` — fraud detection and anomaly scoring
- `ocr` — document OCR and data extraction
- `chatbot` — member chatbot (integrates Gemini + Anthropic)
- `forecasting` — predictive analytics (claim trends, financial forecasting)
- `analytics` — cross-line analytics endpoints
- `pricing` — pricing / billing optimization suggestions
- Kafka consumer for async processing. Most endpoints are stubbed today; contracts are stable.

## Client Applications

### Angular Web Application (Admin/Operations)

Single Angular 19 application with **role-based routing**:

| Route Group | Audience | Key Features |
|-------------|----------|-------------|
| `/super-admin/*` | Platform super administrators | Tenant management, platform analytics, feature flags, system config |
| `/admin/*` | Tenant administrators | Tenant settings, user management, rules configuration, branding |
| `/claims/*` | Claims clerks, adjudicators | Claim review queues, adjudication workspace, AI recommendations |
| `/finance/*` | Finance clerks, finance HoD | Payment runs, reconciliation, reports, provider balances |
| `/contributions/*` | Contributions staff | Billing runs, scheme management, member balances, invoicing |
| `/providers/*` | Providers (medical providers, garages, funeral homes — line-dependent) | Claim submission, payment tracking, pre-authorization requests |

### Flutter Applications (Member-Facing)

**Mobile app** (iOS + Android) and **Web app** (PWA) sharing the same Flutter codebase. Currently ~8 scaffolded screens (login, home, dashboard, claims, benefits, chat, profile, payments); target feature set below:

| Feature | Description |
|---------|-------------|
| **Dashboard** | Overview of benefits, balances, recent claims |
| **Claims** | View claim history, track claim status in real-time |
| **Benefits** | View scheme details, benefit limits, remaining balances |
| **Payments** | View payout history, download payment advice PDFs |
| **Bills** | View contribution invoices, payment history |
| **Profile** | Manage dependants, banking details, contact info |
| **Chat** | AI-assisted support chat for queries |
| **Notifications** | Push notifications for claim updates, payment confirmations |
| **Documents** | Upload/download claim documents, ID copies |
| **Digital Card** | Digital membership card with QR code for provider verification |
| **Group Liaison Mode** | For `group_liaison` role: manage group members, view/pay bills, enroll/terminate — NO access to PHI/claims for the health vertical |

## Event-Driven Communication

All cross-service side effects flow through **Apache Kafka**:

### Kafka Topics

```
medfund.claims.submitted        → AI auto-adjudication, fraud check, audit log
medfund.claims.adjudicated      → Finance balance update, notification, audit log
medfund.claims.verified         → Claim status update, member notification
medfund.payments.committed      → Provider notification, balance snapshot, audit log
medfund.members.enrolled        → Welcome notification, benefit provisioning, audit log
medfund.contributions.billed    → Invoice generation, notification, audit log
medfund.tenants.provisioned     → Schema creation, Keycloak realm setup, seed data
medfund.tenants.config-changed  → Rules engine reload, service config refresh
medfund.ai.prediction-made      → Audit log, feedback collection
medfund.documents.uploaded      → OCR processing, virus scan
medfund.chat.message-sent       → AI response generation, audit
medfund.payments.inbound        → Contribution credited, balance updated, receipt generated, audit
medfund.payments.outbound       → Provider/member payout initiated, balance updated, audit
medfund.payments.webhook        → Payment provider callback processed, status updated
medfund.subscriptions.created   → Tenant provisioned, welcome flow triggered
medfund.subscriptions.renewed   → Platform billing updated
medfund.subscriptions.failed    → Tenant admin notified, grace period started
```

### Synchronous Communication

For **query/read** operations only (not side effects):

- **gRPC** between backend services (e.g., Claims Service querying User Service for member eligibility)
- **REST** from clients to API Gateway
- **REST** from backend services to AI Service for real-time predictions

## Authentication — JWT in HTTP-Only Cookies

All client-to-server authentication uses **JWT tokens stored in HTTP-only, Secure, SameSite cookies**. Tokens are NEVER stored in localStorage or sessionStorage (XSS attack vector).

### Cookie Configuration

| Cookie | Content | Flags | Expiry |
|--------|---------|-------|--------|
| `__Host-at` | Access token (JWT) | `HttpOnly`, `Secure`, `SameSite=Strict`, `Path=/` | 15 minutes |
| `__Host-rt` | Refresh token (opaque) | `HttpOnly`, `Secure`, `SameSite=Strict`, `Path=/api/v2/auth/refresh` | 7 days |

- **`__Host-` prefix** ensures the cookie is only sent to the exact host, over HTTPS, with `Path=/` — prevents subdomain attacks.
- **Access token is short-lived (15 min).** Reduces window of compromise. Clients use silent refresh via the refresh token cookie.
- **Refresh token path is restricted** to `/api/v2/auth/refresh` — it is never sent with normal API requests.
- **SameSite=Strict** prevents CSRF. For cross-origin scenarios (provider portal accessing multiple tenants), use `SameSite=Lax` with CSRF token double-submit pattern.

### Auth Flow

```
1. Login (Angular/Flutter) → POST /api/v2/auth/login { username, password }
   ← Set-Cookie: __Host-at=<jwt>; HttpOnly; Secure; SameSite=Strict; Max-Age=900
   ← Set-Cookie: __Host-rt=<refresh>; HttpOnly; Secure; SameSite=Strict; Path=/api/v2/auth/refresh; Max-Age=604800
   ← Body: { user profile, roles, tenant_id } (no tokens in body)

2. API calls — browser automatically sends __Host-at cookie
   → GET /api/v2/claims  (cookie attached by browser)
   ← API Gateway validates JWT from cookie, extracts tenant_id + user_id

3. Token refresh — before access token expires (client-side timer or 401 response)
   → POST /api/v2/auth/refresh  (refresh cookie auto-attached due to path match)
   ← New Set-Cookie: __Host-at=<new-jwt>; ...

4. Logout → POST /api/v2/auth/logout
   ← Set-Cookie: __Host-at=; Max-Age=0 (clear access token)
   ← Set-Cookie: __Host-rt=; Max-Age=0; Path=/api/v2/auth/refresh (clear refresh token)
   ← Keycloak session invalidated server-side
```

### Per-Client Implementation

**Angular**: HttpClient interceptor reads 401 responses and triggers silent refresh. No token handling in JS — cookies are automatic.

```typescript
// Angular HTTP interceptor — NO token management needed
// Cookies are sent automatically by the browser
@Injectable()
export class AuthInterceptor implements HttpInterceptor {
  intercept(req: HttpRequest<any>, next: HttpHandler): Observable<HttpEvent<any>> {
    // Just ensure withCredentials is set for cross-origin
    const authReq = req.clone({ withCredentials: true });
    return next.handle(authReq).pipe(
      catchError((error: HttpErrorResponse) => {
        if (error.status === 401) {
          return this.authService.refreshToken().pipe(
            switchMap(() => next.handle(authReq))
          );
        }
        return throwError(() => error);
      })
    );
  }
}
```

**Flutter (mobile)**: Since mobile apps can't use browser cookies natively, use `flutter_secure_storage` for token persistence + Dio interceptor for attaching tokens via `Authorization` header as a fallback. The API Gateway accepts both cookie-based and header-based JWT.

```dart
// Flutter Dio interceptor — mobile uses Authorization header
class AuthInterceptor extends Interceptor {
  @override
  void onRequest(RequestOptions options, RequestInterceptorHandler handler) async {
    final token = await secureStorage.read(key: 'access_token');
    if (token != null) {
      options.headers['Authorization'] = 'Bearer $token';
    }
    handler.next(options);
  }
}
```

**API Gateway (Go)**: Extracts JWT from cookie first, falls back to `Authorization` header (for mobile/service-to-service).

```go
func ExtractJWT(c *fiber.Ctx) (string, error) {
    // 1. Try HTTP-only cookie first (web clients)
    if cookie := c.Cookies("__Host-at"); cookie != "" {
        return cookie, nil
    }
    // 2. Fallback to Authorization header (mobile/service-to-service)
    auth := c.Get("Authorization")
    if strings.HasPrefix(auth, "Bearer ") {
        return strings.TrimPrefix(auth, "Bearer "), nil
    }
    return "", ErrNoToken
}
```

### CSRF Protection

Since `SameSite=Strict` blocks cross-origin cookie sending, CSRF is mitigated by default. For cases where `SameSite=Lax` is needed:

- API Gateway generates a CSRF token and sets it in a **non-HttpOnly** cookie (`XSRF-TOKEN`)
- Angular reads this cookie and sends it back as the `X-XSRF-TOKEN` header (Angular does this automatically)
- API Gateway validates the header matches the cookie (double-submit pattern)

### Multi-Factor Authentication (MFA)

MFA is managed entirely by **Keycloak** and enforced per role and per tenant. The platform supports three MFA methods — users can enroll in one or more:

#### Supported MFA Methods

| Method | How It Works | Keycloak Config | Best For |
|--------|-------------|-----------------|----------|
| **TOTP (Authenticator App)** | Google Authenticator, Authy, Microsoft Authenticator — generates time-based 6-digit codes | Built-in OTP policy. Algorithm: SHA-256, period: 30s, digits: 6 | Staff users, admins, providers |
| **Email OTP** | One-time code sent to user's registered email address | Custom Keycloak authenticator SPI or built-in "Email OTP" (Keycloak 24+) | Members, low-frequency users |
| **SMS OTP** | One-time code sent via SMS (Twilio / Africa's Talking) | Custom Keycloak SPI that calls Notification Service (Go) to dispatch SMS | Members, providers in areas with limited internet |

#### MFA Enforcement Policy

| Role | MFA Requirement | Methods Allowed |
|------|----------------|-----------------|
| `super_admin` | **Required** (always) | TOTP only (strongest) |
| `tenant_admin` | **Required** (always) | TOTP, Email |
| `claims_supervisor`, `finance_supervisor`, `finance_hod` | **Required** (always) | TOTP, Email |
| `claims_clerk`, `finance_clerk`, `contributions_clerk` | **Required** (configurable per tenant) | TOTP, Email, SMS |
| `provider`, `provider_admin` | **Required** (configurable per tenant) | TOTP, Email, SMS |
| `group_liaison` | **Required** (configurable per tenant) | TOTP, Email, SMS |
| `member` | **Optional** (encouraged, configurable per tenant) | TOTP, Email, SMS |

Tenant admins can override MFA requirements for their tenant (only stricter, not weaker than platform defaults):
- Enable/disable MFA for specific roles
- Choose allowed MFA methods
- Set grace period for new users to enroll (e.g., 7 days after first login)
- Configure MFA challenge frequency (every login vs. every 24h on trusted devices)

#### MFA Auth Flow

```
1. User enters username + password
   → Keycloak validates credentials

2. If MFA required for this user's role:
   → Keycloak checks enrolled MFA methods

   2a. If user has TOTP enrolled:
       ← Return MFA challenge: "Enter code from authenticator app"
       → User enters 6-digit TOTP code
       ← Keycloak validates against stored secret

   2b. If user has Email OTP enrolled:
       → Keycloak triggers email via Notification Service (Kafka event)
       ← Return MFA challenge: "Enter code sent to j***@email.com"
       → User enters 6-digit code from email
       ← Keycloak validates code (5-minute expiry, single use)

   2c. If user has SMS OTP enrolled:
       → Keycloak triggers SMS via Notification Service (Kafka event)
       ← Return MFA challenge: "Enter code sent to +263***1234"
       → User enters 6-digit code from SMS
       ← Keycloak validates code (5-minute expiry, single use)

3. MFA passed → Issue JWT tokens (cookies)
4. MFA failed → Increment failure count, lock after 5 attempts

5. If user has NO MFA enrolled but it's required:
   → Redirect to MFA enrollment flow
   → User sets up at least one MFA method before proceeding
```

#### MFA Enrollment (Client UI)

**Angular (Settings page at `/admin/security/mfa`):**
- TOTP: Display QR code (generated by Keycloak), user scans with authenticator app, enters verification code to confirm
- Email: Sends test code to registered email, user confirms
- SMS: Sends test code to registered phone, user confirms
- Manage enrolled methods (add/remove, set primary)

**Flutter (Profile → Security):**
- Same flows adapted for mobile
- TOTP: Can use device camera to scan QR or manual key entry
- Biometric unlock (fingerprint/face) as **device-level** convenience (not a Keycloak MFA method — this just unlocks the stored tokens locally)

#### Keycloak MFA Configuration

```json
// Per-tenant realm authentication flow (configured via Keycloak Admin API during tenant provisioning)
{
  "alias": "medfund-mfa-flow",
  "authenticationExecutions": [
    {
      "authenticator": "auth-username-password-form",
      "requirement": "REQUIRED"
    },
    {
      "authenticator": "auth-conditional-otp-form",
      "requirement": "CONDITIONAL",
      "authenticatorConfig": {
        "otpControlSkip": "false",
        "forceOtpRole": "tenant_admin,claims_supervisor,finance_supervisor,finance_hod",
        "defaultOtpOutcome": "skip"  // For roles not in forceOtpRole, skip unless user enrolled
      }
    }
  ]
}
```

### OAuth 2.0 / OpenID Connect (OIDC)

All authentication flows use **OAuth 2.0 + OIDC** via Keycloak. No custom auth logic in application code.

#### Supported OAuth Flows

| Flow | Used By | Description |
|------|---------|-------------|
| **Authorization Code + PKCE** | Angular web app, Flutter web app | Standard browser-based OIDC login. PKCE (Proof Key for Code Exchange) prevents authorization code interception. No client secret needed |
| **Authorization Code + PKCE (mobile)** | Flutter mobile app | Uses `flutter_appauth` with custom URL scheme callback. System browser for login (not WebView — prevents credential theft) |
| **Client Credentials** | Service-to-service (backend) | Machine-to-machine auth. Each service has a Keycloak service account with specific scopes |
| **Refresh Token** | All clients | Silent token renewal via refresh token (cookie for web, secure storage for mobile) |

#### OIDC Token Contents

```json
// Access token JWT payload (issued by Keycloak)
{
  "sub": "user-uuid",
  "iss": "https://auth.medfund.healthcare/realms/tenant-zmmas",
  "aud": "medfund-api",
  "exp": 1711540200,
  "iat": 1711539300,
  "tenant_id": "tenant-uuid",
  "tenant_slug": "zmmas",
  "realm_access": {
    "roles": ["claims_clerk", "adjudicator"]
  },
  "resource_access": {
    "medfund-api": {
      "roles": ["claims:read", "claims:adjudicate"]
    }
  },
  "name": "John Doe",
  "email": "john@example.com",
  "mfa_verified": true,
  "amr": ["pwd", "otp"]  // Authentication methods reference
}
```

Key claims:
- `tenant_id` / `tenant_slug` — Tenant identity (added via Keycloak protocol mapper)
- `realm_access.roles` — User roles within the tenant
- `resource_access` — Fine-grained permissions per API resource
- `mfa_verified` — Whether MFA was completed in this session
- `amr` — Which authentication methods were used (`pwd` = password, `otp` = TOTP/SMS/Email)

#### OAuth Social Login / Identity Federation (Per Tenant)

Tenant admins can enable social/enterprise identity providers for their users:

| Provider | Use Case | Keycloak Config |
|----------|----------|----------------|
| **Google** | Members signing in with Google accounts | OIDC identity provider in tenant realm |
| **Microsoft / Azure AD** | Corporate groups with Azure AD | SAML or OIDC federation |
| **Apple** | iOS mobile app users | OIDC identity provider |
| **Custom SAML IdP** | Large corporate groups with their own IdP | SAML identity provider broker |

Configuration is per-tenant — each tenant realm has its own set of enabled identity providers. Social login users still require MFA if their role mandates it.

#### Per-Client OIDC Configuration

**Angular (`angular-oauth2-oidc`):**
```typescript
export const authConfig: AuthConfig = {
  issuer: 'https://auth.medfund.healthcare/realms/tenant-zmmas',
  clientId: 'medfund-web-admin',
  redirectUri: window.location.origin + '/callback',
  responseType: 'code',
  scope: 'openid profile email',
  useSilentRefresh: true,
  silentRefreshRedirectUri: window.location.origin + '/silent-refresh.html',
  requireHttps: true,
  pkce: true,          // PKCE enabled — no client secret
  sessionChecksEnabled: true,
};
```

**Flutter (`flutter_appauth`):**
```dart
final AuthorizationTokenResponse result = await appAuth.authorizeAndExchangeCode(
  AuthorizationTokenRequest(
    'medfund-mobile',  // client_id
    'com.medfund.app://callback',  // redirect URI (custom scheme)
    issuer: 'https://auth.medfund.healthcare/realms/tenant-zmmas',
    scopes: ['openid', 'profile', 'email', 'offline_access'],
    preferEphemeralSession: false,  // Use system browser, keep session
  ),
);
// Store tokens in flutter_secure_storage
await secureStorage.write(key: 'access_token', value: result.accessToken);
await secureStorage.write(key: 'refresh_token', value: result.refreshToken);
```

### Service-to-Service Auth

Backend services authenticate to each other via:
- **gRPC**: mTLS certificates (mutual TLS managed by service mesh or manually)
- **REST** (to AI Service): OAuth2 client credentials flow via Keycloak (short-lived service tokens)

## Database Architecture

See [multi-tenancy.md](multi-tenancy.md) for full schema details.

```
PostgreSQL 17 Cluster
├── public schema (shared across all tenants — migrations V100–V127 in tenancy-service)
│   ├── tenants                    — Tenant registry and metadata
│   ├── plans                      — Subscription plans (tenant tiers)
│   ├── staff_users                — Platform staff (super admins) — cross-tenant
│   ├── providers                  — Platform-level provider registry
│   ├── currencies                 — ISO 4217 currency master data
│   ├── exchange_rates             — Historical exchange rates (daily snapshots)
│   ├── permissions                — RBAC catalogue
│   ├── email_templates            — Platform-default email templates
│   ├── notifications              — Cross-tenant notification records
│   ├── rules                      — Rule metadata (per-tenant rule bodies in tenant schema)
│   ├── scheduled_jobs             — Job config + run history (V114–V116)
│   └── proration_config           — Per-tenant benefit-limit proration strategy
│
├── tenant_{uuid} schema (one per tenant, completely isolated)
│   ├── members, dependants, providers, groups, group_liaisons
│   ├── life_policies, funeral_policies, disability_policies, travel_policies
│   ├── vehicles, properties  (asset-line insured objects)
│   ├── claims (line-tagged via insurance_line), claim_lines, pre_authorizations, quotations
│   ├── tariff_codes, tariff_categories, tariff_modifiers, icd_codes, drugs  (health-vertical adjudication)
│   ├── schemes, scheme_benefits, benefits, benefit_types, age_groups, tariff_benefit_mappings
│   ├── contributions, invoices, group_running_balances, billing_cycle_config, dunning_config, bad_debts
│   ├── payments, payment_runs, payment_run_items, adjustments, debit_notes, credit_notes, bank_reconciliations, provider_balances
│   ├── audit_events (immutable, partitioned by month)
│   ├── security_events (immutable, partitioned by month)
│   ├── ai_predictions (model outputs + human feedback)
│   ├── notification_templates (per-tenant branding)
│   └── chat_messages
│   Note: Flyway migrations for this schema live in `tenancy-service` at V001–V065.
│
└── analytics schema (read replica)
    └── Materialized views for cross-tenant platform analytics (super admin only)
```

## Monorepo Structure

```
medfund/
├── services/
│   ├── java/                        # Gradle multi-project
│   │   ├── shared/                  # InsuranceLine, TenantContext, audit, scheduling, RBAC
│   │   ├── tenancy-service/         # Spring Boot + Flyway (owns all migrations)
│   │   ├── user-service/            # Spring Boot — members, providers, policies (life/funeral/etc.), vehicles, properties
│   │   ├── claims-service/          # Spring Boot — line-tagged claims, health-vertical adjudication
│   │   ├── contributions-service/   # Spring Boot — schemes, billing, invoices
│   │   ├── finance-service/         # Spring Boot — payments, runs, reconciliation
│   │   └── rules-engine/            # Spring Boot + Drools — line-agnostic rules engine
│   ├── go/                          # Go workspace
│   │   ├── shared/                  # Tenant middleware, audit helpers
│   │   ├── gateway/                 # API gateway
│   │   ├── notification-service/    # Email, SMS, push
│   │   ├── audit-service/           # Kafka → immutable audit log
│   │   ├── file-service/            # S3/MinIO uploads, PDF/CSV
│   │   └── payment-gateway/         # Scaffolded — no live processor yet
│   ├── elixir/                      # Mix umbrella
│   │   └── apps/
│   │       ├── live_dashboard/      # Phoenix — real-time dashboards
│   │       └── chat_service/        # Phoenix — chat channels
│   └── python/
│       └── ai-service/              # FastAPI — adjudication, fraud, ocr, chatbot, forecasting, analytics, pricing
├── clients/
│   ├── angular/                     # Angular 19 web app (all portals)
│   └── flutter/                     # Flutter mobile + web (early stage)
├── infra/
│   └── docker/                      # PostgreSQL init, pg_hba
├── docs/                            # User workflows, platform manual, local dev setup
├── docker-compose.yml               # Local dev: Postgres, Redis, Kafka, Keycloak, MinIO, Mailpit
└── .claude/                         # These guidelines
```

Not yet in repo (aspirational, referenced in older docs): top-level `proto/`, `schemas/avro/`, `infra/helm`, `infra/terraform`, `infra/argocd`.
