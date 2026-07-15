# AI Integration

## Overview

AI is integrated across the InsureFlow platform to augment human decision-making, automate repetitive tasks, detect fraud, and improve member/provider experience. All AI features follow a **human-in-the-loop** design — AI makes recommendations, humans make final decisions (except for low-risk automated approvals configured per tenant).

AI features are exposed by the Python `ai-service` (FastAPI) across seven modules today: `adjudication`, `fraud`, `ocr`, `chatbot`, `forecasting`, `analytics`, `pricing`. Most endpoints are stubs with stable contracts; the concrete ML models below are the target design. Adjudication AI is currently focused on the medical-aid vertical (ICD/tariff-aware); fraud, OCR, forecasting, and pricing generalise across insurance lines.

## AI Service Architecture (Python / FastAPI)

```
┌─────────────────────────────────────────────────────────────┐
│                    AI Service (FastAPI)                       │
│                                                              │
│  ┌──────────────┐  ┌──────────────┐  ┌───────────────────┐  │
│  │ Claude API   │  │ ML Models    │  │ OCR Pipeline      │  │
│  │ Integration  │  │ (scikit/XGB) │  │ (Tesseract)       │  │
│  │              │  │              │  │                   │  │
│  │ - Reasoning  │  │ - Fraud Det. │  │ - Doc extraction  │  │
│  │ - Chat       │  │ - Classify.  │  │ - Form parsing    │  │
│  │ - Doc Analysis│  │ - Anomaly   │  │ - ID verification │  │
│  │ - Summarize  │  │ - Forecast   │  │                   │  │
│  └──────┬───────┘  └──────┬───────┘  └───────┬───────────┘  │
│         │                 │                   │              │
│  ┌──────▼─────────────────▼───────────────────▼──────────┐   │
│  │              Feature Engineering Layer                 │   │
│  │  (reads tenant data, computes features, caches)       │   │
│  └───────────────────────┬───────────────────────────────┘   │
│                          │                                   │
│  ┌───────────────────────▼───────────────────────────────┐   │
│  │              Prediction Store                          │   │
│  │  (tenant_schema.ai_predictions — versioned, auditable)│   │
│  └───────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────┘
```

## AI Features by Domain

### 1. Claims Auto-Adjudication

**Problem**: Manual adjudication is slow, inconsistent, and doesn't scale across tenants with different rules.

**Solution**: Hybrid approach — tenant-specific Drools rules (Java Rules Engine) + ML model + Claude reasoning.

```
Claim Submitted
      │
      ▼
┌─────────────────┐
│ Rules Engine     │  ← Drools (Java): Apply tenant-specific rules
│ (deterministic)  │     - Benefit limits check
│                  │     - Tariff code validation
│                  │     - Waiting period check
│                  │     - Exclusion list check
└────────┬────────┘
         │ Rules pass? (No auto-reject first)
         ▼
┌─────────────────┐
│ ML Classifier    │  ← XGBoost/scikit-learn: Score claim risk
│ (probabilistic)  │     Features: provider history, claim patterns,
│                  │     member history, amount vs. tariff, timing
│                  │     Output: risk_score (0-1), category
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│ Claude Reasoning │  ← Claude API: Complex case analysis
│ (LLM)           │     - Explain adjudication recommendation
│                  │     - Flag unusual patterns in natural language
│                  │     - Compare with similar historical claims
└────────┬────────┘
         │
         ▼
┌─────────────────────────────────────────────┐
│ Decision Matrix                              │
│                                              │
│ risk_score < 0.2 AND rules_pass              │
│   → Auto-approve (if tenant allows)          │
│                                              │
│ risk_score > 0.8 OR rules_fail               │
│   → Auto-flag for manual review              │
│                                              │
│ Otherwise                                    │
│   → Present to claims clerk with:            │
│     - AI recommendation (approve/reject)     │
│     - Confidence score                       │
│     - Claude's reasoning explanation         │
│     - Similar historical claims              │
│     - Flagged anomalies                      │
└─────────────────────────────────────────────┘
```

**Feedback Loop**: When a claims clerk overrides an AI recommendation, the decision is stored as training feedback. Models are retrained periodically (per tenant or globally with tenant as a feature).

**Per-Tenant Configuration** (via Tenant Admin portal):
- Auto-approve threshold (risk score below which claims auto-approve)
- Auto-flag threshold (risk score above which claims require manual review)
- Enable/disable auto-approval entirely
- Custom rule weights

### 2. Fraud Detection

**Problem**: Fraudulent claims waste funds and are hard to detect manually at scale.

**Approach**: Anomaly detection + pattern matching + Claude analysis.

**Features Used**:
- Provider claim frequency and average amounts (deviation from peer group)
- Member claim patterns (frequency, timing, provider diversity)
- Duplicate or near-duplicate claim detection (fuzzy matching on dates, codes, amounts)
- Unusual tariff code combinations
- Claims submitted outside normal hours
- Geographic anomalies (member location vs. provider location)
- Sequential claim amounts (₤100, ₤200, ₤300 pattern)

**Models**:
- **Isolation Forest** — Unsupervised anomaly detection on claim features
- **XGBoost** — Supervised fraud classifier (trained on historically confirmed fraud cases)
- **Claude** — Natural language explanation of why a claim is flagged

**Output**: Fraud risk score (0-1) attached to each claim, with explanation. High-risk claims are flagged in the claims adjudication dashboard.

### 3. Document OCR & Data Extraction

**Problem**: Providers submit claim forms as scanned PDFs/images. Manual data entry is slow and error-prone.

**Pipeline**:
```
Document Upload
      │
      ▼
  Preprocessing (deskew, denoise, contrast enhancement)
      │
      ▼
  Tesseract OCR (text extraction)
      │
      ▼
  Claude Vision API (structured data extraction)
      │
      ▼
  Output: {
    provider_name, member_id, diagnosis_codes[],
    tariff_codes[], amounts[], dates, currency
  }
      │
      ▼
  Pre-fill claim form in UI (provider/clerk reviews and confirms)
```

**Claude's Role**: After Tesseract extracts raw text, Claude parses it into structured claim data, handling messy handwriting, non-standard formats, and ambiguous fields.

### 4. Billing Optimization

**Problem**: Contribution billing errors, incorrect age group assignments, and inefficient scheme configurations.

**AI Capabilities**:
- **Scheme Recommendation**: Suggest optimal scheme for a member based on their dependant profile, age, and historical claim patterns
- **Pricing Anomaly Detection**: Flag when a member's contributions don't match their scheme/age group pricing
- **Collection Optimization**: Predict which groups are likely to default, suggest proactive outreach
- **Benefit Utilization Analysis**: Identify under-utilized benefits (member education opportunity) or over-utilized benefits (potential abuse)

### 5. Financial Forecasting

**Problem**: Healthcare fund managers need to predict cash flow, reserve requirements, and claim trends.

**Models**:
- **Time Series** (Prophet / statsmodels): Monthly claim volume and amount forecasting per tenant
- **Cash Flow Prediction**: Expected contributions in vs. expected payouts, projected fund balance
- **Reserve Adequacy**: Estimate required reserves based on claim trends and member demographics

**Dashboard Integration**: Forecasts displayed in the Elixir Live Dashboard with confidence intervals.

### 6. Member Chatbot (Claude-Powered)

**Channels**: Flutter mobile app, Angular web portal, chat widget.

**Capabilities**:
- Answer FAQs about benefits, schemes, claim procedures
- Check claim status (reads from database, no write access)
- Explain benefit balance and remaining limits
- Guide through claim submission process
- Escalate to human agent when confidence is low or member requests it

**Architecture**:
```
User Message → Chat Service (Elixir)
                    │
                    ▼
              AI Service (FastAPI)
                    │
                    ├── Retrieve member context (benefits, claims, balance)
                    ├── Claude API call with context + conversation history
                    ├── If action needed → return structured action (not executed, just suggested)
                    └── Return response text
                    │
                    ▼
              Chat Service → User
```

**Safety**: The chatbot NEVER modifies data. It only reads. Any action (submit claim, update profile) is surfaced as a suggestion with a link to the appropriate form.

### 7. Provider Intelligence

**For the Provider Portal**:
- **Claim Pattern Analysis**: Show providers their claim approval rates, common rejection reasons, average processing times
- **Tariff Code Suggestions**: As providers enter claim details, suggest likely tariff codes based on diagnosis
- **Pre-Authorization Predictor**: Estimate likelihood of pre-auth approval before submission
- **Payment Forecast**: Predict when the next payment run will include their claims
- **Peer Benchmarking**: Compare provider metrics against anonymized peer averages (same specialty, same region)

## AI Prediction Storage Schema

```sql
CREATE TABLE ai_predictions (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    entity_type     VARCHAR(50) NOT NULL,    -- 'claim', 'member', 'provider', 'transaction'
    entity_id       UUID NOT NULL,
    prediction_type VARCHAR(50) NOT NULL,    -- 'adjudication', 'fraud', 'churn', 'forecast'
    model_name      VARCHAR(100) NOT NULL,   -- 'fraud_xgboost_v3', 'claude-sonnet-4-6', etc.
    model_version   VARCHAR(50) NOT NULL,
    input_features  JSONB NOT NULL,          -- Features used for prediction
    prediction      JSONB NOT NULL,          -- Model output
    confidence      DECIMAL(5,4),            -- 0.0000 to 1.0000
    explanation     TEXT,                    -- Human-readable reasoning (often from Claude)
    human_decision  VARCHAR(50),            -- NULL until reviewed: 'accepted', 'overridden', 'ignored'
    human_feedback  TEXT,                   -- Why the human disagreed (training signal)
    decided_by      UUID REFERENCES users(id),
    decided_at      TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    -- Indexing for feedback loops
    INDEX idx_predictions_entity (entity_type, entity_id),
    INDEX idx_predictions_type (prediction_type, created_at),
    INDEX idx_predictions_feedback (human_decision) WHERE human_decision IS NOT NULL
);
```

## AI Ethics & Governance

1. **Transparency**: Every AI decision includes an explanation. Members and providers can request explanation of any AI-influenced decision.
2. **Fairness**: Models are audited for bias across demographics. Per-tenant model performance is monitored.
3. **Human Override**: AI never makes final decisions on claim rejections. Only approvals below a risk threshold can be automated (and only if the tenant enables it).
4. **Data Privacy**: AI models are trained on anonymized data. Per-tenant data is never used to train models for other tenants unless explicitly opted in.
5. **Audit Trail**: Every AI prediction is stored with full context (see schema above) and is part of the immutable audit log.
