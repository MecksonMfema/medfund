# Multi-Currency Architecture

## Overview

The platform must handle multiple currencies per tenant. A tenant in Zimbabwe may bill in ZWL but pay providers in USD; a tenant in Botswana bills in BWP; a regional life insurer may collect premiums in ZAR and pay claims in USD. Each tenant configures their supported currencies, default currency, and exchange rate sources.

Multi-currency support is line-agnostic: contribution billing, claim settlement, premium collection, provider payout, and reserve accounting all flow through the same currency + exchange-rate machinery (`Currency`, `ExchangeRate`, `TenantCurrencyConfig` in `tenancy-service`).

## Core Principles

1. **Every monetary value is a (amount, currency_code) pair.** Never store an amount without its currency.
2. **Use `BigDecimal` (Java) / `Decimal` (Python) / fixed-point integers (Go).** Never use `float` or `double` for money.
3. **Store amounts with 4 decimal places** (`DECIMAL(19,4)` in PostgreSQL). This accommodates all ISO 4217 currencies including those with 3 decimal places (e.g., KWD).
4. **Exchange rates are immutable snapshots.** Once a rate is used in a transaction, it is recorded with the transaction and never retroactively changed.
5. **Conversion happens at the service layer, not the database.** Always store the original amount and currency, plus the converted amount and conversion metadata.

## Database Schema

### Shared Tables (public schema)

```sql
-- ISO 4217 currency registry
CREATE TABLE currencies (
    code            CHAR(3) PRIMARY KEY,      -- 'USD', 'ZWL', 'BWP', 'ZAR', etc.
    name            VARCHAR(100) NOT NULL,     -- 'United States Dollar'
    symbol          VARCHAR(10) NOT NULL,      -- '$', 'ZWL$', 'P', 'R'
    decimal_places  SMALLINT NOT NULL DEFAULT 2,
    is_active       BOOLEAN NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Exchange rate snapshots (daily or more frequent)
CREATE TABLE exchange_rates (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    base_currency   CHAR(3) NOT NULL REFERENCES currencies(code),
    quote_currency  CHAR(3) NOT NULL REFERENCES currencies(code),
    rate            DECIMAL(19,10) NOT NULL,   -- High precision for rates
    rate_date       DATE NOT NULL,
    source          VARCHAR(50) NOT NULL,       -- 'RBZ', 'manual', 'openexchangerates', etc.
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    UNIQUE(base_currency, quote_currency, rate_date, source)
);
CREATE INDEX idx_exchange_rates_lookup
    ON exchange_rates(base_currency, quote_currency, rate_date DESC);
```

### Per-Tenant Configuration

```sql
-- Tenant currency settings (in tenant schema)
CREATE TABLE tenant_currency_config (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    currency_code       CHAR(3) NOT NULL REFERENCES public.currencies(code),
    is_default          BOOLEAN NOT NULL DEFAULT FALSE,  -- Tenant's primary currency
    is_billing_currency BOOLEAN NOT NULL DEFAULT FALSE,  -- Can bill contributions in this currency
    is_claims_currency  BOOLEAN NOT NULL DEFAULT FALSE,  -- Can submit claims in this currency
    is_payment_currency BOOLEAN NOT NULL DEFAULT FALSE,  -- Can issue payments in this currency
    exchange_rate_source VARCHAR(50) NOT NULL DEFAULT 'manual',  -- How rates are obtained

    -- Ensure exactly one default currency per tenant
    UNIQUE(is_default) WHERE is_default = TRUE
);
```

## Monetary Amount Pattern

### Java (Spring Boot Services)

Use **JavaMoney (JSR 354)** with Moneta implementation:

```java
// Entity field pattern — store as two columns
@Column(name = "amount", precision = 19, scale = 4, nullable = false)
private BigDecimal amount;

@Column(name = "currency_code", length = 3, nullable = false)
private String currencyCode;

// Service layer — use MonetaryAmount for calculations
public MonetaryAmount toMoney() {
    return Money.of(amount, currencyCode);
}
```

### Go (Fiber Services)

Use **shopspring/decimal** or store as integer cents:

```go
type MonetaryAmount struct {
    Amount       decimal.Decimal `json:"amount"`
    CurrencyCode string          `json:"currency_code" validate:"len=3"`
}
```

### Python (FastAPI AI Service)

Use **decimal.Decimal**:

```python
from decimal import Decimal
from pydantic import BaseModel, Field

class MonetaryAmount(BaseModel):
    amount: Decimal = Field(decimal_places=4, max_digits=19)
    currency_code: str = Field(min_length=3, max_length=3)
```

### Angular / Flutter (Client)

Display amounts using **Intl.NumberFormat** (Angular) or **intl** package (Flutter) with the currency's symbol and decimal places.

```typescript
// Angular pipe or utility
formatCurrency(amount: number, currencyCode: string, locale: string): string {
  return new Intl.NumberFormat(locale, {
    style: 'currency',
    currency: currencyCode,
  }).format(amount);
}
```

## Currency Conversion Flow

```
1. Retrieve exchange rate:
   GET /api/v2/currency/rates?base=ZWL&quote=USD&date=2026-03-27
   → { rate: 0.00031, rate_date: "2026-03-27", source: "RBZ" }

2. Convert:
   original_amount * rate = converted_amount
   ZWL 100,000 × 0.00031 = USD 31.00

3. Store BOTH amounts + rate metadata:
   {
     original_amount: 100000.0000,
     original_currency: "ZWL",
     converted_amount: 31.0000,
     converted_currency: "USD",
     exchange_rate_id: "uuid-of-rate-used",
     exchange_rate: 0.00031
   }
```

## Multi-Currency in Each Domain

### Claims
- Claim submitted in provider's billing currency
- Adjudication can approve in a different currency (e.g., claim in ZWL, benefit limit in USD)
- Member benefit balance deducted in the benefit's currency
- Both original and converted amounts stored on claim detail adjudication

### Contributions
- Scheme pricing defined in the scheme's currency
- Contributions billed in the group's preferred currency
- Transactions recorded in the payment's actual currency
- Balance tracking maintains separate balances per currency (no implicit conversion)

### Finance
- Payment runs can be single-currency or multi-currency
- Provider balance tracked per currency
- Exchange rate at time of payment commitment is locked
- Financial reports support currency filtering and cross-currency totals (using a reporting currency)

### Reporting
- All financial reports allow selecting a **reporting currency**
- Cross-currency totals use the rate at time of transaction (historical accuracy)
- Dashboard widgets show amounts in the tenant's default currency with conversion indicators

## Exchange Rate Management

### Sources
1. **Manual entry** — Tenant admin or super admin enters rates via the admin portal
2. **Central bank API** — Automated daily fetch from central bank (e.g., Reserve Bank of Zimbabwe)
3. **Third-party API** — OpenExchangeRates, CurrencyLayer, etc.
4. **Per-tenant override** — Tenant admin can set a fixed rate that overrides automated rates

### Rate Update Flow
```
Scheduled Job (daily) or Manual Entry
      │
      ▼
  Exchange Rate Service (Java Tenancy Service)
      │
      ├── Validate rate (sanity check against previous rate — alert if >10% change)
      ├── Store in exchange_rates table
      ├── Publish medfund.currency.rate-updated Kafka event
      └── Invalidate Redis cache for affected currency pairs
```

### Rounding Rules
- Follow ISO 4217 decimal places for the target currency
- Use **HALF_EVEN** (banker's rounding) for all conversions
- Store full precision internally, round only for display and final settlement
