# Payment Gateway & Online Payments

> **Status:** the `payment-gateway` Go service is currently scaffolded (~10 files) with no live processor integrations. This doc is the target specification.

## Overview

The MedFund platform handles three categories of monetary transactions (across all insurance lines — the "contribution" terminology comes from the medical-aid vertical, but the flows apply equally to premiums for life/funeral/motor/etc.):

1. **Inbound — Contribution / Premium Payments**: Members/groups pay contributions or premiums online, regardless of insurance line
2. **Inbound — Tenant Subscriptions**: Insurance carriers subscribe to and pay for the MedFund platform
3. **Outbound — Payouts**: Tenants pay providers (claim settlements, garages, funeral homes) and members (refunds, benefits) through the platform

All payment processing flows through the **Payment Gateway Service** (Go/Fiber), which provides a unified abstraction over multiple payment providers.

## Architecture

```
┌─────────────────────────────────────────────────────────────────────┐
│                        CLIENT APPS                                   │
│                                                                      │
│  Angular: Contribution payments, payout execution, subscription mgmt │
│  Flutter: Member bill payment, payment history, receipts             │
└───────────────────────────┬─────────────────────────────────────────┘
                            │
                            ▼
┌───────────────────────────────────────────────────────────────────┐
│                      API Gateway (Go)                             │
└───────────────────────────┬───────────────────────────────────────┘
                            │
          ┌─────────────────▼──────────────────┐
          │     Payment Gateway Service (Go)    │
          │                                     │
          │  ┌───────────────────────────────┐  │
          │  │    Provider Abstraction Layer  │  │
          │  │                               │  │
          │  │  PaymentProvider interface:    │  │
          │  │    - InitiatePayment()        │  │
          │  │    - InitiatePayout()         │  │
          │  │    - VerifyWebhook()          │  │
          │  │    - CheckStatus()            │  │
          │  │    - RefundPayment()          │  │
          │  └──┬──────┬──────┬──────┬───────┘  │
          │     │      │      │      │          │
          │  ┌──▼──┐┌──▼──┐┌─▼───┐┌─▼────────┐ │
          │  │Payni││Strip││Paynt││EcoCash/  │ │
          │  │te   ││e    ││ab   ││InnBucks  │ │
          │  │     ││     ││     ││OneMoney  │ │
          │  └─────┘└─────┘└─────┘└──────────┘ │
          │                                     │
          │  Webhook receiver (POST /webhooks/) │
          │  Idempotency layer                  │
          │  Payment ledger                     │
          └──────────┬──────────────────────────┘
                     │
          ┌──────────▼──────────────────┐
          │  Kafka Events               │
          │  medfund.payments.inbound    │
          │  medfund.payments.outbound   │
          │  medfund.payments.webhook    │
          └──────────┬──────────────────┘
                     │
    ┌────────────────▼─────────────────┐
    │  Finance Service (Java)          │
    │  - Update balances               │
    │  - Record transactions           │
    │  - Generate receipts             │
    │  - Reconciliation                │
    └──────────────────────────────────┘
```

## Payment Providers

The platform supports multiple payment providers to cover different markets and payment methods. Each tenant configures which providers are enabled for their region.

| Provider | Type | Markets | Payment Methods |
|----------|------|---------|-----------------|
| **Paynow** | Inbound + Outbound | Zimbabwe | EcoCash, OneMoney, bank transfer, VISA/Mastercard |
| **Stripe** | Inbound + Outbound | International | Credit/debit cards, bank transfers, Apple Pay, Google Pay |
| **Paystack** | Inbound + Outbound | Africa (Nigeria, Ghana, SA, Kenya) | Cards, bank transfer, USSD, mobile money |
| **EcoCash API** | Inbound | Zimbabwe | EcoCash mobile money (direct integration) |
| **InnBucks** | Inbound | Zimbabwe | InnBucks wallet |
| **DPO (Network International)** | Inbound | Pan-Africa | Cards, mobile money, bank transfer |
| **Manual/Bank Transfer** | Inbound + Outbound | All | Manual reconciliation (upload bank statement, match to payments) |

### Provider Abstraction

```go
// All payment providers implement this interface
type PaymentProvider interface {
    // Inbound: initiate a payment collection
    InitiatePayment(ctx context.Context, req PaymentRequest) (*PaymentResponse, error)

    // Outbound: initiate a payout/disbursement
    InitiatePayout(ctx context.Context, req PayoutRequest) (*PayoutResponse, error)

    // Verify webhook signature
    VerifyWebhook(ctx context.Context, headers map[string]string, body []byte) (*WebhookEvent, error)

    // Check payment status (polling fallback)
    CheckStatus(ctx context.Context, providerRef string) (*PaymentStatus, error)

    // Reverse/refund a payment
    RefundPayment(ctx context.Context, providerRef string, amount decimal.Decimal) (*RefundResponse, error)

    // Provider capabilities
    Capabilities() ProviderCapabilities
}

type ProviderCapabilities struct {
    SupportsInbound  bool
    SupportsOutbound bool
    SupportedCurrencies []string    // ["USD", "ZWL", "ZAR"]
    SupportedMethods    []string    // ["card", "mobile_money", "bank_transfer"]
    SupportsRefund      bool
    SupportsRecurring   bool
    WebhookBased        bool        // true = async via webhooks, false = sync response
}
```

## 1. Inbound — Contribution Payments (Members/Groups Paying Bills)

### Flow

```
Member opens Flutter app → Bills → View outstanding invoice
   │
   ▼
Select invoice(s) to pay → Choose payment method
   │
   ▼
POST /api/v2/payments/contributions/initiate
{
  "tenant_id": "uuid",
  "invoice_ids": ["uuid1", "uuid2"],
  "payment_method": "ecocash",      // or "card", "bank_transfer", etc.
  "provider": "paynow",             // resolved from tenant config + method
  "currency": "USD",
  "amount": "450.00",
  "payer": {
    "member_id": "uuid",
    "phone": "+263771234567"         // for mobile money
  }
}
   │
   ▼
Payment Gateway Service:
   ├── Validate request (amount matches invoice total, member authorized)
   ├── Create payment_transactions record (status: PENDING)
   ├── Call PaymentProvider.InitiatePayment()
   │   └── Paynow returns: { poll_url, redirect_url, provider_ref }
   ├── Return checkout URL / mobile money prompt to client
   └── Publish medfund.payments.inbound (status: INITIATED)
   │
   ▼
Member completes payment on provider's side (EcoCash USSD, card form, etc.)
   │
   ▼
Provider sends webhook → POST /api/v2/payments/webhooks/paynow
   │
   ▼
Payment Gateway Service:
   ├── Verify webhook signature (VerifyWebhook())
   ├── Update payment_transactions (status: SUCCESS or FAILED)
   ├── Publish medfund.payments.inbound (status: SUCCESS)
   └── Publish medfund.payments.webhook
   │
   ▼
Finance Service (Kafka consumer):
   ├── Record transaction in tenant schema
   ├── Update group/member balance
   ├── Mark invoice(s) as PAID
   ├── Generate receipt PDF (via File Service)
   └── Send payment confirmation notification (via Notification Service)
```

### Payment Methods by Channel

| Channel | Available Methods |
|---------|------------------|
| **Flutter mobile (member)** | EcoCash (USSD prompt), OneMoney, InnBucks, card (in-app WebView), bank transfer (reference number) |
| **Flutter web (member)** | Card (Stripe/Paynow hosted checkout), EcoCash (redirect), bank transfer |
| **Angular (group admin)** | Card, bank transfer, EcoCash, bulk payment upload (CSV of payments made offline) |

### Partial Payments & Overpayments

- Members can make **partial payments** against an invoice — balance is tracked per invoice
- **Overpayments** are credited to the member's contribution balance (carried forward to next billing cycle)
- The Finance Service handles all balance arithmetic — the Payment Gateway only records the raw transaction

### Recurring Payments

For providers that support it (Stripe, Paynow):
- Members/groups can set up **recurring contribution payments** (monthly auto-debit)
- Stored as a `payment_subscription` in the tenant schema
- Payment Gateway initiates collection on billing date
- Failed recurring payments trigger retry (3 attempts over 7 days) + notification to member

## 2. Inbound — Tenant Subscriptions (Medical Aid Societies Paying for MedFund)

### Self-Service Tenant Onboarding

```
Prospect visits medfund.healthcare/pricing
   │
   ▼
Select plan (Starter / Professional / Enterprise)
   │
   ▼
Fill registration form:
  - Organization name, country, contact email
  - Admin user details (name, email, phone)
  - Select base currency
  │
  ▼
Choose payment method (card or bank transfer)
   │
   ▼
POST /api/v2/subscriptions/register
{
  "organization": { "name": "...", "country": "ZW", ... },
  "admin_user": { "name": "...", "email": "...", "phone": "..." },
  "plan_id": "uuid",
  "payment_method": "card",
  "billing_cycle": "monthly"    // or "annual"
}
   │
   ▼
Tenancy Service:
   ├── Create tenant record (status: PENDING_PAYMENT)
   ├── Call Payment Gateway → InitiatePayment() for first subscription payment
   │   └── Stripe returns checkout session URL
   ├── Return redirect URL to client
   └── Publish medfund.subscriptions.created (status: PENDING_PAYMENT)
   │
   ▼
Admin completes payment on Stripe checkout
   │
   ▼
Webhook → Payment Gateway → medfund.payments.webhook
   │
   ▼
Tenancy Service (Kafka consumer):
   ├── Update tenant status: ACTIVE
   ├── Provision tenant schema (Flyway migrations)
   ├── Create Keycloak realm + admin user
   ├── Seed default data (schemes, rules, templates)
   ├── Send welcome email with login credentials
   └── Publish medfund.tenants.provisioned
```

### Subscription Management

| Feature | Description |
|---------|-------------|
| **Plan upgrade/downgrade** | Tenant admin changes plan → prorated billing adjustment |
| **Billing history** | View past invoices + payment receipts in tenant admin portal |
| **Payment method update** | Change card on file (Stripe customer portal) |
| **Cancel subscription** | Soft cancel → data retained for 90 days → archived → purged |
| **Failed payment** | 3 retry attempts over 14 days → suspend tenant (read-only) → notify admin |
| **Annual discount** | Configurable discount for annual vs. monthly billing |
| **Free trial** | Optional trial period (configurable per plan, e.g., 14 days) |

### Subscription Database Schema (public schema)

```sql
CREATE TABLE public.subscriptions (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           UUID NOT NULL REFERENCES tenants(id),
    plan_id             UUID NOT NULL REFERENCES plans(id),
    status              VARCHAR(20) NOT NULL DEFAULT 'active',
                        -- 'trialing', 'active', 'past_due', 'suspended', 'cancelled'
    billing_cycle       VARCHAR(10) NOT NULL,   -- 'monthly', 'annual'
    currency_code       CHAR(3) NOT NULL,
    amount              DECIMAL(19,4) NOT NULL,  -- Current billing amount
    payment_provider    VARCHAR(50) NOT NULL,     -- 'stripe', 'paynow', etc.
    provider_customer_id VARCHAR(255),            -- Stripe customer ID, etc.
    provider_subscription_id VARCHAR(255),        -- Stripe subscription ID, etc.
    current_period_start DATE NOT NULL,
    current_period_end   DATE NOT NULL,
    trial_end           DATE,
    cancelled_at        TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE public.subscription_invoices (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    subscription_id     UUID NOT NULL REFERENCES subscriptions(id),
    amount              DECIMAL(19,4) NOT NULL,
    currency_code       CHAR(3) NOT NULL,
    status              VARCHAR(20) NOT NULL,  -- 'draft', 'open', 'paid', 'past_due', 'void'
    provider_invoice_id VARCHAR(255),
    invoice_pdf_url     TEXT,
    period_start        DATE NOT NULL,
    period_end          DATE NOT NULL,
    paid_at             TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
```

## 3. Outbound — Payouts (Tenants Paying Providers & Members)

### Payout Flow

```
Finance clerk creates payment run (Angular Finance Portal)
   │
   ▼
Select claims to include → Review amounts per provider → Approve
   │
   ▼
Finance HoD approves payment run (dual-approval for high-value payouts)
   │
   ▼
POST /api/v2/payments/payouts/execute
{
  "tenant_id": "uuid",
  "payment_run_id": "uuid",
  "payouts": [
    {
      "recipient_type": "provider",
      "recipient_id": "uuid",
      "amount": "15000.00",
      "currency": "USD",
      "method": "bank_transfer",
      "bank_details": { "bank_name": "...", "account_number": "...", "branch_code": "..." }
    },
    {
      "recipient_type": "member",
      "recipient_id": "uuid",
      "amount": "250.00",
      "currency": "USD",
      "method": "ecocash",
      "mobile_number": "+263771234567"
    }
  ]
}
   │
   ▼
Payment Gateway Service:
   ├── Validate all payouts (amounts, recipients, bank details)
   ├── Create payout_transactions records (status: PENDING)
   ├── For each payout:
   │   ├── Resolve provider (Paynow for ZW mobile money, Stripe for international bank)
   │   ├── Call PaymentProvider.InitiatePayout()
   │   └── Record provider reference
   ├── Return batch status to client
   └── Publish medfund.payments.outbound per payout
   │
   ▼
Provider processes payout (async — may take minutes to days for bank transfers)
   │
   ▼
Webhook or polling confirms settlement
   │
   ▼
Finance Service:
   ├── Update payment record (status: PAID, bank_date)
   ├── Update provider/member balance (debit)
   ├── Create balance snapshot (audit)
   ├── Generate payment advice PDF
   └── Send notification to recipient (email/SMS/push)
```

### Payout Methods

| Method | Provider | Speed | Use Case |
|--------|----------|-------|----------|
| **EcoCash disbursement** | Paynow / EcoCash API | Instant | Provider/member payouts in Zimbabwe |
| **OneMoney** | Paynow | Instant | Alternative mobile money in Zimbabwe |
| **Bank transfer (local)** | Paynow / bank API | 1-3 business days | Bulk provider payments in Zimbabwe |
| **Bank transfer (SWIFT)** | Stripe / DPO | 3-5 business days | International provider payments |
| **Mobile money (other markets)** | Paystack / DPO | Instant to same-day | Pan-Africa mobile money payouts |

### Payout Controls

| Control | Description |
|---------|-------------|
| **Dual approval** | Payment runs above a configurable threshold require approval from two different authorized users |
| **Daily payout limit** | Per-tenant daily payout cap (configurable by super admin based on plan) |
| **Recipient verification** | First payout to a new bank account triggers a micro-deposit verification |
| **Payout schedule** | Tenants can configure payout frequency (daily, weekly, bi-weekly, monthly) |
| **Hold period** | Configurable hold period between adjudication and payout (e.g., 7 days) |
| **Balance check** | Payout blocked if tenant's pooled fund balance is insufficient |

## Payment Gateway Service — Database Schema (public schema)

The Payment Gateway owns a ledger in the `public` schema (cross-tenant, since it handles platform subscriptions too). Per-tenant payment records are also written to tenant schemas by the Finance Service.

```sql
-- Master transaction ledger (public schema, owned by Payment Gateway)
CREATE TABLE public.payment_transactions (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           UUID REFERENCES tenants(id),   -- NULL for platform subscription payments
    direction           VARCHAR(10) NOT NULL,           -- 'INBOUND' or 'OUTBOUND'
    category            VARCHAR(30) NOT NULL,           -- 'CONTRIBUTION', 'SUBSCRIPTION', 'PAYOUT_PROVIDER', 'PAYOUT_MEMBER', 'REFUND'
    status              VARCHAR(20) NOT NULL DEFAULT 'PENDING',
                        -- 'PENDING', 'INITIATED', 'PROCESSING', 'SUCCESS', 'FAILED', 'REFUNDED', 'CANCELLED'
    amount              DECIMAL(19,4) NOT NULL,
    currency_code       CHAR(3) NOT NULL,
    payment_provider    VARCHAR(50) NOT NULL,           -- 'stripe', 'paynow', 'ecocash', 'manual'
    payment_method      VARCHAR(30) NOT NULL,           -- 'card', 'ecocash', 'bank_transfer', 'onemoney', etc.
    provider_ref        VARCHAR(255),                   -- Provider's transaction reference
    provider_status     VARCHAR(50),                    -- Raw status from provider
    idempotency_key     VARCHAR(255) NOT NULL UNIQUE,   -- Prevents duplicate processing

    -- Payer/recipient details
    payer_type          VARCHAR(20),                    -- 'member', 'group', 'tenant'
    payer_id            UUID,
    recipient_type      VARCHAR(20),                    -- 'provider', 'member', 'platform'
    recipient_id        UUID,

    -- References
    invoice_id          UUID,                           -- Contribution invoice being paid
    payment_run_id      UUID,                           -- Payout batch reference
    subscription_id     UUID REFERENCES subscriptions(id),

    -- Metadata
    metadata            JSONB DEFAULT '{}',             -- Provider-specific data, checkout URLs, etc.
    failure_reason      TEXT,
    webhook_received_at TIMESTAMPTZ,
    settled_at          TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    INDEX idx_pt_tenant_status (tenant_id, status, created_at DESC),
    INDEX idx_pt_provider_ref (payment_provider, provider_ref),
    INDEX idx_pt_idempotency (idempotency_key)
);

-- Webhook event log (for debugging and replay)
CREATE TABLE public.webhook_events (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    provider            VARCHAR(50) NOT NULL,
    event_type          VARCHAR(100) NOT NULL,          -- 'payment.success', 'payout.completed', etc.
    provider_event_id   VARCHAR(255),
    payload             JSONB NOT NULL,
    signature_valid     BOOLEAN NOT NULL,
    processed           BOOLEAN NOT NULL DEFAULT FALSE,
    processing_error    TEXT,
    transaction_id      UUID REFERENCES payment_transactions(id),
    received_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    INDEX idx_we_provider (provider, received_at DESC),
    INDEX idx_we_unprocessed (processed) WHERE processed = FALSE
);
```

## Per-Tenant Payment Configuration

```sql
-- In tenant schema: which payment providers/methods are enabled
CREATE TABLE payment_config (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    provider            VARCHAR(50) NOT NULL,       -- 'paynow', 'stripe', etc.
    is_enabled          BOOLEAN NOT NULL DEFAULT FALSE,
    direction           VARCHAR(10) NOT NULL,       -- 'INBOUND', 'OUTBOUND', 'BOTH'
    provider_credentials JSONB NOT NULL DEFAULT '{}', -- Encrypted: API keys, merchant IDs, etc.
    supported_methods   TEXT[] NOT NULL,             -- ['card', 'ecocash', 'bank_transfer']
    supported_currencies TEXT[] NOT NULL,            -- ['USD', 'ZWL']
    payout_schedule     VARCHAR(20) DEFAULT 'weekly', -- 'daily', 'weekly', 'biweekly', 'monthly'
    payout_hold_days    INTEGER DEFAULT 7,           -- Days between adjudication and payout eligibility
    daily_payout_limit  DECIMAL(19,4),              -- NULL = no limit
    dual_approval_threshold DECIMAL(19,4),          -- Payouts above this need two approvals
    is_active           BOOLEAN NOT NULL DEFAULT TRUE,

    UNIQUE(provider, direction)
);
```

**Important**: `provider_credentials` is encrypted at the application level (AES-256-GCM) using a per-tenant key from AWS KMS. The Payment Gateway Service decrypts credentials at runtime when calling the provider API.

## Portal Integration

### Angular — Finance Portal

| Route | Feature |
|-------|---------|
| `/finance/payments/inbound` | View all incoming contribution payments, filter by status/method/date |
| `/finance/payments/inbound/:id` | Payment detail (provider ref, status timeline, receipt) |
| `/finance/payouts` | View all outbound payouts, filter by status/provider/recipient |
| `/finance/payouts/new` | Create payment run → select claims → review → submit for approval |
| `/finance/payouts/:id` | Payout detail (provider ref, settlement status, payment advice) |
| `/finance/reconciliation` | Match bank statements against platform transactions |

### Angular — Tenant Admin Portal

| Route | Feature |
|-------|---------|
| `/admin/payments/config` | Configure payment providers (API keys, enabled methods, currencies) |
| `/admin/payments/payout-settings` | Payout schedule, hold days, approval thresholds, daily limits |

### Angular — Super Admin Portal

| Route | Feature |
|-------|---------|
| `/super-admin/subscriptions` | All tenant subscriptions, billing status, revenue metrics |
| `/super-admin/subscriptions/:id` | Subscription detail, invoice history, payment method |
| `/super-admin/plans` | Plan management (pricing, features, trial periods) |

### Angular — Contributions Portal

| Route | Feature |
|-------|---------|
| `/contributions/payments/record` | Record offline payment (bank transfer, cash) — enters reference manually |
| `/contributions/invoices/:id/pay-link` | Generate shareable payment link for a member/group invoice |

### Flutter — Member App

| Screen | Feature |
|--------|---------|
| Bills → Invoice detail | "Pay Now" button → select method (EcoCash, card, etc.) → complete payment |
| Bills → Payment history | Past payments with receipts (downloadable PDF) |
| Bills → Auto-pay | Set up recurring payment (if supported by provider) |
| Payments → Refunds | View refund status for overpayments |

### Flutter — Provider App

| Screen | Feature |
|--------|---------|
| Payments | View payouts received per medical aid, download remittance advice |
| Balance | Outstanding balance per medical aid, estimated next payout date |

## Idempotency & Safety

- Every payment request requires a client-generated `idempotency_key` (UUID). Duplicate requests with the same key return the existing result.
- Webhook events are logged raw and replayed if processing fails.
- Payment status transitions are strictly ordered: `PENDING → INITIATED → PROCESSING → SUCCESS/FAILED`. No skipping or reversal without an explicit refund.
- All payment operations are audit-logged with full before/after state.
- Provider credentials are never logged or included in error messages.

## Reconciliation

The Finance Service runs a daily reconciliation job:

1. Fetch all `INITIATED` or `PROCESSING` transactions older than 1 hour
2. Poll each transaction's status via `PaymentProvider.CheckStatus()`
3. Update stale transactions (mark as SUCCESS or FAILED based on provider response)
4. Flag discrepancies between platform ledger and provider reports
5. Generate reconciliation report (Finance Portal → `/finance/reconciliation`)
