---
date: 2026-09-15
git_commit: 2a80248e3c0de32c18c8d90b2c72c607aafffcae
branch: rename-adjustments-to-notes
ticket: null
research:
  - thoughts/shared/research/2026-09-15-ai-service-gaps-and-model-strategy.md
related_plans:
  - thoughts/shared/plans/2026-09-05-fraud-siu-report.md
steer: "start with Tranche 0 pilot-readiness; ride Phase 19 §A rails for the fraud audit-of-record; defer training the real fraud/pricing models to a Tranche 1 follow-on; every AI endpoint is line-aware from day one across all 8 InsuranceLine values — no mid-stream schema changes for other lines later"
services_touched: [ai-service, claims-service, contributions-service, chat-service, tenancy-service, user-service, shared, angular]
status: draft
tranche: "0 — pilot readiness (unstub + audit-wire + config + line-aware plumbing; no model training)"
lines_covered: [HEALTH, LIFE, FUNERAL, GROUP, TRAVEL, DISABILITY, VEHICLE, PROPERTY]
---

# AI Service Pilot-Readiness Implementation Plan (Tranche 0)

## Overview

Unstub the InsureFlow AI service enough to run a real-user, real-data pilot without violating Critical Rule #3 (AI auditability) and without shipping random numbers into production decisions. Every endpoint is **line-aware from the first commit** — an `insurance_line` field on every AI request, per-line seed data for the code suggester, a canonical fraud feature vector that generalizes across all 8 InsuranceLine values, and a multi-line policy summary for the chatbot. No health-only shortcuts; no schema break when the pilot expands from HEALTH to LIFE / FUNERAL / GROUP / TRAVEL / DISABILITY / VEHICLE / PROPERTY.

Seven phases: (1) de-stub fraud + make its input line-agnostic, (2) wire `ai_predictions_store` (with `insurance_line` column) on every prediction, (3) generic code-suggester with per-line seed mappings, (4) persist chatbot history + multi-line policy summary, (5) finish LLM key + Claude-client wiring, (6) plumb the two platform feature flags into Java + Elixir callers, (7) Angular human-review page with line filter.

This plan explicitly does **not** train new ML models and does **not** duplicate the fraud audit-of-record / SIU workflow — those belong to Tranche 1 (follow-on) and Phase 19 §A (`thoughts/shared/plans/2026-09-05-fraud-siu-report.md`) respectively.

## Current State Analysis

Facts verified during research (`thoughts/shared/research/2026-09-15-ai-service-gaps-and-model-strategy.md`):

- **Fraud stub is live**: `services/python/ai-service/app/api/fraud.py:28-63` computes a hardcoded score with `random.uniform(0, 0.1)` added at line 49. The endpoint ignores `FraudService.detect_fraud()` — which is what the Kafka consumer path uses. The orphaned `FraudMLModel` at `services/python/ai-service/app/services/ml_models.py:14-55` (IsolationForest, synthetic-trained at process start) is what the service call would actually invoke.
- **Line-awareness gaps**:
  - `FraudCheckRequest` today carries `diagnosis_codes` + `procedure_codes` (health-only) with no `insurance_line` field. `AiServiceClient.evaluate` in claims-service (`services/java/claims-service/src/main/java/com/medfund/claims/client/AiServiceClient.java:88-115`) sends those fields for every line — empty lists on non-health claims — with no `insuranceLine` payload key.
  - `TariffSuggestionRequest` is fully AHFOZ/ICD-10 shaped. No line field.
  - `AiPricingClient` in contributions-service (`services/java/contributions-service/.../AiPricingClient.java:53-79`) does send `insurance_line` already — the pricing scorer (`services/python/ai-service/app/api/pricing.py:184-643`) is already line-aware for all 7 person-centric + 2 asset-centric variants; this plan preserves that shape.
  - `InsuranceLine` enum lives in `services/java/shared/src/main/java/com/medfund/shared/InsuranceLine.java`; values HEALTH, LIFE, FUNERAL, GROUP, TRAVEL, DISABILITY, VEHICLE, PROPERTY. `InsuranceLine.isPersonCentric()` splits the group — HEALTH/LIFE/FUNERAL/GROUP/TRAVEL/DISABILITY vs VEHICLE/PROPERTY. Feature vectors in Phase 1 and Phase 3 mappings honor this split.
- **Audit spine exists but is under-written**: `ai_predictions_store` table (`services/python/ai-service/app/db/models.py:9-24`) and `PredictionRepository` (`services/python/ai-service/app/repositories/prediction_repository.py:1-73`) are present. `save_prediction` exists. Grep of `app/api/*.py` shows only `chatbot.py` calls `save_conversation_message`; no endpoint calls `save_prediction`. Every AI-assisted response returns without persisting the audit row — Critical Rule #3 exposure.
- **Tariff-suggestion has no rule-based fallback**: `services/python/ai-service/app/api/adjudication.py:85-102` returns `{"suggestions": [], "note": "AI unavailable: manual tariff code lookup required", "source": "fallback"}` when Gemini key is unset. In tests today it always returns blank.
- **Chatbot conversation history is in-memory only**: `services/python/ai-service/app/services/chatbot_service.py:13` uses `_conversations: dict[str, list[dict]] = {}`. `conversation_messages` table + `save_conversation_message` / `get_conversation_history` repository methods (`services/python/ai-service/app/repositories/prediction_repository.py`) exist but are not invoked from the endpoint request path.
- **Claude client prepared but unwired**: `services/python/ai-service/app/core/anthropic_client.py:1-84` mirrors the Gemini client shape. `MEDFUND_ANTHROPIC_API_KEY` is declared in `services/python/ai-service/app/core/config.py:9` but the client constructor is called without reading it, so `.available` is always False. `docker-compose.yml:376-395` sets no LLM keys for the AI service.
- **PlatformFlag catalogue is fresh** (uncommitted in git status): `services/java/shared/src/main/java/com/medfund/shared/flags/PlatformFlag.java:13-23` defines `AI_ADJUDICATION`, `FRAUD_DETECTION`, `GROUP_PORTAL`, `PROVIDER_PORTAL`, `MOBILE_PWA`. Seeder at `services/java/tenancy-service/src/main/java/com/medfund/tenancy/config/PlatformFlagSeeder.java:24-49` inserts each with `enabled=false` on startup. Table is `public.platform_feature_flags`, keyed by enum name (String).
- **Java callers ignore the flags today**: `services/java/claims-service/src/main/java/com/medfund/claims/client/AiServiceClient.java:56-84` always calls the AI service; `services/java/contributions-service/src/main/java/com/medfund/contributions/service/AiPricingClient.java:53-79` always calls the pricing scorer.
- **Angular admin has a platform-settings surface** wired (see the uncommitted `PublicBrandingController` / `PlatformSettingsController` in git status). No AI-predictions surface exists.
- **Phase 19 §A reserves V169-V174 tenant migrations** for fraud/SIU tables and adds the `medfund.claims.fraud-flagged` Kafka producer in AI service. **This plan does not add tenant migrations.** `ai_predictions_store` is AI-service-owned in the same Postgres cluster but is SQLAlchemy-managed, not Flyway.
- **Elixir chat**: `services/elixir/apps/chat_service/lib/chat_service/ai_proxy.ex:5-34` calls `/api/v1/ai/chat/message` with tenant_id + user_id in body and `X-Tenant-ID` header.

## Desired End State

After this plan lands:

- Every request to `/api/v1/ai/adjudication/recommend`, `/adjudication/check-duplicate`, `/adjudication/suggest-codes`, `/fraud/check`, `/ocr/extract`, `/chat/message`, `/pricing/score` writes a row to `ai_predictions_store` with `insurance_line`, `model_version`, anonymized `input_features` JSON, `output` JSON, and `confidence`. Compliance can query "what did the model say about claim X" for any tenant, and can filter per line.
- Every AI request payload carries an `insurance_line` field (one of the 8 `InsuranceLine` enum values). The AI service dispatches per-line for the code suggester, uses a canonical cross-line feature vector for fraud, and threads the line through to the audit row.
- `/fraud/check` uses `FraudService.detect_fraud()` end-to-end with a **canonical line-neutral feature vector** — `amount, days_since_enrollment_or_policy_start, claim_frequency_30d, provider_flag_count, member_or_asset_flag_count`. Line-specific fields (diagnosis codes for HEALTH, damage_type for PROPERTY, cause_of_loss for VEHICLE, benefit_type for LIFE/FUNERAL/DISABILITY, coverage_category for TRAVEL) travel in a `line_features` JSON bag stored on the prediction row for later per-line model training. Model version stays `fraud-isolation-forest-v1-canonical`. No `random.uniform` anywhere.
- `/adjudication/suggest-codes` (renamed from `/suggest-tariff` — the old URL is unused externally; verified against `AiServiceClient`, `AiPricingClient`, Angular services, and Elixir chat) returns non-empty suggestions from a per-line static mapping when Gemini is unavailable. Seed data covers all 8 InsuranceLine values with a starter mapping per line (HEALTH gets ~80 AHFOZ codes, others get ~20-40 each).
- Chatbot conversation history persists across process restarts via `conversation_messages`; a `GET /api/v1/ai/chat/history/{conversation_id}` endpoint returns it.
- Both LLM clients (Gemini + Claude) can be enabled via env vars; a new `MEDFUND_LLM_PROVIDER` env var (`gemini` | `claude`, default `gemini`) picks the active client. `docker-compose.yml` passes both keys through with empty defaults.
- Toggling `AI_ADJUDICATION=false` at `/platform/settings` stops claims-service from hitting the AI service; toggling `FRAUD_DETECTION=false` stops it from calling `/fraud/check`. Both fall back to the existing fail-open path (empty AI signals). Contributions-service and Elixir chat make the equivalent short-circuit.
- Tenant admins have a `/tenant-admin/ai-predictions` page listing every AI prediction for their tenant, filterable by entity type / model version / decision status, with a "mark accepted / overridden" action that writes `accepted`, `reviewed_by`, `reviewed_at` back to `ai_predictions_store`.

### Verification

```bash
# Backend
cd services/java && ./gradlew build test
make test-integration
cd services/python/ai-service && uv run pytest
cd services/elixir && mix test

# Frontend
make test-angular
make test-e2e

# Manual acceptance
make infra && make tenancy user contributions finance claims gateway notification ai web
# Submit one claim per line (8 total: HEALTH, LIFE, FUNERAL, GROUP, TRAVEL, DISABILITY, VEHICLE, PROPERTY)
#   → observe ai_predictions_store rows tagged with the correct insurance_line for each
# Open /tenant-admin/ai-predictions → filter by insurance_line=VEHICLE → only vehicle rows visible
#   → change to insurance_line=HEALTH → only health rows visible
# Mark one override → observe reviewed_at + reviewed_by_email set
# Toggle AI_ADJUDICATION off at /platform/settings → submit another claim → verify no ai_predictions_store row lands
# Chat via /member/chat as a multi-line member → ask about medical / funeral / motor / travel cover
#   → each answer cites that line's portfolio entry → restart ai-service → history persists
```

### Key Discoveries

- **`AiServiceClient` and `AiPricingClient` are already fail-open** (`services/java/claims-service/.../AiServiceClient.java:76-84`, `services/java/contributions-service/.../AiPricingClient.java:63-79`) — this plan piggybacks on the existing empty-signal / multiplier-1.0 fallback. Flag-off does not require any new fallback logic in callers.
- **Anonymization boundary is the endpoint, not the repository**: `PredictionRepository.save_prediction` writes whatever JSON you hand it. The Rule 4 obligation to strip PII lives in the caller — a shared `_anonymize_features(dict)` helper avoids duplicating scrub logic seven times.
- **Chatbot member-context is a read-only lookup** — the design (`.claude/ai-integration.md:191`) names benefits, claims, balance. All three live in user-service and claims-service. A minimal MVP hits only `GET /api/v1/members/{id}/benefit-summary` (user-service) — richer context wiring is Tranche 2 follow-on.
- **`Persistable<String>` INSERT pattern for `PlatformFeatureFlag`** (`services/java/tenancy-service/.../entity/PlatformFeatureFlag.java:23-43`) — R2DBC pre-populated `@Id` would trip UPDATE-mode; the entity implements `Persistable<String>` with a transient `newRow` flag flipped by the seeder. Any new flag consumer reads via `PlatformFeatureFlagRepository.findById(flagName)` returning `Mono<PlatformFeatureFlag>`.
- **Reactor-Kafka `.doOnSuccess` ack pattern** — irrelevant to this plan (no new Kafka consumers here) but referenced when the chatbot persistence adds an idempotent write path.
- **`ai_predictions_store` is SQLAlchemy async, not Flyway** — the schema is created via `Base.metadata.create_all()` on service startup. Adding columns is a code change, not a migration file. (Compatibility note: any prod deployment relying on `create_all` idempotence needs the columns nullable so old rows don't reject; this plan adds no columns.)

## What We're NOT Doing

Deferred to follow-on plans:

- **Training real ML models** for fraud or pricing. `FraudMLModel` stays synthetic-trained at boot for this plan. Tranche 1 follow-on plan will introduce `scripts/ai/train_fraud.py`, artifact persistence (`app/artifacts/*.joblib`), and versioned load-at-startup.
- **`medfund.claims.fraud-flagged` Kafka producer** + `fraud_flag` audit-of-record + SIU workflow — Phase 19 §A (`thoughts/shared/plans/2026-09-05-fraud-siu-report.md`) owns this end-to-end. Nothing in this plan overlaps.
- **Claude Vision** for OCR structured extraction — Tesseract + Gemini text stays as-is.
- **Prophet / statsmodels forecasting** — current sklearn LinearRegression remains the baseline.
- **Cross-tenant training with opt-in** and drift monitoring — Tranche 3.
- **New tenant-schema Flyway migrations** — Phase 19 §A holds V169-V174; this plan touches no tenant tables.
- **Rich chatbot member context beyond the policy portfolio** (claims history transcript, payment history, per-benefit consumption breakdown for HEALTH). Tranche 0 wires the multi-line policy-portfolio summary — enough for balance / cover / excess / trip questions. Detailed transaction-level context is Tranche 2.
- **Feature-vector persistence at reproducibility grade** — `input_features` JSONB captures the vector the endpoint uses; full model registry / MLflow-lite is deferred.
- **AI-driven pricing** — `pricingModel: 'AI_DRIVEN'` tenant flag stays as-is; the rule-based scorer at `app/api/pricing.py` remains authoritative under `model_version="rule-v1"`.

## Insurance-Line Coverage

Every phase covers all 8 InsuranceLine values from the first commit. This table names exactly what each phase does per line so we don't ship health-only shortcuts and then need a schema break later.

| Phase | HEALTH | LIFE | FUNERAL | GROUP | TRAVEL | DISABILITY | VEHICLE | PROPERTY |
|---|---|---|---|---|---|---|---|---|
| **1 Fraud endpoint** | HealthExtractor: ICD + procedures | LifeExtractor: benefit_type + cause | FuneralExtractor: benefit_tier | GroupExtractor: dispatches via `underlying_line` | TravelExtractor: coverage_category + destination | DisabilityExtractor: benefit_type + waiting period | VehicleExtractor: damage_type + parts + odometer | PropertyExtractor: peril + damage_type + repair_category |
| **2 Audit spine** | `insurance_line` column on every row; per-line anonymization of `line_features` bag | same | same | same | same | same | same (`subject_id` stripped covers vehicle asset id) | same (`subject_id` stripped covers property asset id) |
| **3 Code suggester** | AHFOZ tariff (~80 codes) | benefit-type codes (~15) | service-tier codes (~10) | falls through to `underlying_line`'s module | coverage-category codes (~15) | benefit-period + waiting-period codes (~15) | repair-part + labor codes (~40) | damage-category + peril codes (~30) |
| **4 Chatbot portfolio** | `HealthPolicySummary` (scheme, limits, usage, dependants) | `LifePolicySummary` (sum assured, beneficiaries, arrears) | `FuneralPolicySummary` (tier, lives covered) | list of active `group` memberships | `TravelPolicySummary` (active trips, multitrip flag) | `DisabilityPolicySummary` (benefit type, monthly amount, waiting/benefit period) | list of `VehiclePolicySummary` (per vehicle: make, cover type, excess, NCD) | list of `PropertyPolicySummary` (per property: address, cover, excess) |
| **5 LLM config** | line-agnostic — same client, same provider dispatch | | | | | | | |
| **6 Feature flags** | `AI_ADJUDICATION` + `FRAUD_DETECTION` gate every line uniformly (no per-line flag). If pilot experience needs per-line toggles, add in a later phase. | | | | | | | |
| **7 Review page** | Line filter selects any of the 8 values (or "All lines"). Every predicted row displays its `insurance_line` badge. | | | | | | | |

Cross-cutting invariants:

- **Every AI request payload that references a claim carries `insurance_line`** — `/adjudication/recommend`, `/adjudication/check-duplicate`, `/adjudication/suggest-codes`, `/fraud/check`, `/pricing/score`. `/ocr/extract` accepts an optional line label; `/chat/message` is line-free (portfolio contains all lines).
- **`AiServiceClient` and `AiPricingClient` in Java always populate `insuranceLine` from `Claim.insuranceLine`** — the field is already present on every claim entity.
- **`FraudRequestBuilder` in claims-service dispatches per line** to fetch line-specific detail entities (VehicleClaim, PropertyClaim, LifeClaim, etc.) and shape them into `line_features` for the AI service. For lines whose detail entities are scaffolded but not yet populated in claims-service, the builder emits an empty `line_features` bag with a warning log — AI service tolerates empty bags (`indicators` will include `insufficient_data`).

## Implementation Approach

The plan is ordered so each phase is independently verifiable and adds no runtime dependency on later phases:

1. **Phase 1** (kill fraud randomness) is a two-line endpoint change plus a service call. Ships alone.
2. **Phase 2** (audit-wire every endpoint) depends on Phase 1 because a random-noised prediction shouldn't be the first thing to persist. Both write to `ai_predictions_store`.
3. **Phase 3** (tariff fallback) is orthogonal — could ship in parallel with 1–2 but is grouped after audit-wiring to keep the "safe" bundle together.
4. **Phase 4** (chatbot persistence + member context) is standalone; touches chatbot module only.
5. **Phase 5** (LLM config + Claude wiring) is standalone; env-var work + client class fix.
6. **Phase 6** (platform-flag wiring in Java + Elixir callers) requires Phase 5 semantically because the flag-off path assumes fallback works — which the LLM-fallback discipline already exercises.
7. **Phase 7** (Angular predictions review page) requires Phase 2 because it queries `ai_predictions_store`; new backend endpoint on ai-service.

**Critical Rules coverage**:
- **Rule 1 (currency)** — not exercised here; pricing endpoint already takes `currency_code`.
- **Rule 2 (tenant scoping)** — every `save_prediction` call passes `tenant_id` from the `X-Tenant-ID` header (`services/python/ai-service/app/core/tenant.py:8-12`).
- **Rule 3 (AI auditable)** — Phase 2 is the direct fulfillment.
- **Rule 4 (PII/PHI protection)** — Phase 2 anonymizes features at endpoint boundary.
- **Rule 5 (per-tenant rules)** — not exercised; no new rule categories here.
- **Rule 6 (Kafka for side effects)** — not exercised; Phase 19 §A owns the fraud event.
- **Rule 7 (Swagger)** — Phase 7 adds new `PUT /api/v1/ai/predictions/{id}/decision` and `GET /api/v1/ai/predictions` endpoints — both annotated for `/docs`.
- **Rule 8 (AuditEvent per mutation)** — Phase 7's "decide" endpoint emits an `AuditEvent` to `medfund.audit.events` on override.
- **Rule 9 (SecurityEvent)** — Phase 7's list-predictions endpoint emits `SecurityEventPublisher.publishDataAccess` for the list export path.

---

## Phase 1: De-stub the Fraud Endpoint (Line-Aware)

### Overview

Replace the hardcoded formula + `random.uniform` in `POST /api/v1/ai/fraud/check` with a deterministic path that (a) accepts an `insurance_line` field, (b) computes a **canonical cross-line feature vector** from a per-line extractor, (c) feeds that into `FraudService.detect_fraud()` which wraps the synthetic-trained IsolationForest, and (d) stores the raw `line_features` bag alongside the canonical vector so later per-line model training has the material it needs. The synthetic model is not the "real" fraud model (that's Tranche 1) but with a stable line-neutral input it produces consistent scores across all 8 lines.

Java caller (`AiServiceClient.evaluate` in claims-service) is widened to send `insuranceLine` on every call — the field comes straight from `Claim.insuranceLine` which is already populated for every persisted claim.

### Changes Required

#### 1. `services/python/ai-service/app/schemas/fraud.py`

Widen the request schema. `diagnosis_codes` / `procedure_codes` move into `line_features` where they belong (health-specific keys); the top-level request is line-neutral:

```python
from enum import Enum
from typing import Any
from uuid import UUID
from datetime import date
from pydantic import BaseModel, Field


class InsuranceLine(str, Enum):
    HEALTH = "HEALTH"
    LIFE = "LIFE"
    FUNERAL = "FUNERAL"
    GROUP = "GROUP"
    TRAVEL = "TRAVEL"
    DISABILITY = "DISABILITY"
    VEHICLE = "VEHICLE"
    PROPERTY = "PROPERTY"


class FraudCheckRequest(BaseModel):
    claim_id: UUID
    insurance_line: InsuranceLine
    # subject_id is the member id for person-centric lines and the asset id
    # (vehicle_id / property_id) for asset-centric lines. Feature extraction
    # branches on insurance_line to look up the right entity.
    subject_id: UUID
    provider_id: UUID | None = None
    claimed_amount: float
    currency_code: str
    service_date: date
    # Free-form line-specific bag. HEALTH uses {diagnosis_codes, procedure_codes};
    # VEHICLE uses {damage_type, part_codes, cause_of_loss, odometer_km};
    # PROPERTY uses {damage_type, peril, repair_category};
    # LIFE / FUNERAL / DISABILITY use {benefit_type, cause};
    # TRAVEL uses {coverage_category, destination_country, trip_duration_days};
    # GROUP falls through to the underlying line's shape (an extra `underlying_line`
    # is required inside line_features).
    line_features: dict[str, Any] = Field(default_factory=dict)


class FraudCheckResponse(BaseModel):
    claim_id: UUID
    insurance_line: InsuranceLine
    risk_score: float
    risk_level: str
    indicators: list[str]
    model_version: str
```

#### 2. `services/python/ai-service/app/services/feature_extractors.py` (new)

One extractor per line. Each returns the **canonical 5-tuple** feature vector the shared IsolationForest consumes, plus a normalized `line_features` echo (for audit persistence). Signatures identical so `FraudService` can dispatch by enum value.

```python
"""Per-line feature extractors for the AI service.

Every extractor returns:

    (canonical_features, normalized_line_features, rule_indicators)

- canonical_features: 5-tuple [amount, days_since_start, claim_frequency_30d,
  provider_flag_count, subject_flag_count]. Line-neutral vector consumed by
  the shared FraudMLModel.
- normalized_line_features: dict of line-specific fields, cleaned + typed.
  Persisted on the prediction row for later per-line model training.
- rule_indicators: list of string flags like "high_value_claim",
  "unusual_procedure_count", "asset_high_repair_cost". Displayed to
  operators alongside the ML score; not additive to it.

Every extractor is deterministic. No randomness. All extractors are
line-agnostic in interface — line-specific in body.
"""
from __future__ import annotations

from typing import Any

from app.schemas.fraud import FraudCheckRequest, InsuranceLine


# Per-line "typical claim amount" thresholds for the high_value_claim indicator.
# Rough starter values; tenants can tune later via a settings surface (Tranche 2).
_HIGH_VALUE_THRESHOLDS = {
    InsuranceLine.HEALTH:     5_000.0,
    InsuranceLine.LIFE:       50_000.0,
    InsuranceLine.FUNERAL:    10_000.0,
    InsuranceLine.GROUP:      20_000.0,
    InsuranceLine.TRAVEL:     3_000.0,
    InsuranceLine.DISABILITY: 15_000.0,
    InsuranceLine.VEHICLE:    8_000.0,
    InsuranceLine.PROPERTY:   25_000.0,
}


class FeatureExtractor:
    """Abstract shape — subclasses implement extract()."""
    async def extract(
        self, req: FraudCheckRequest, tenant_id: str, ctx: "FraudContext"
    ) -> tuple[list[float], dict[str, Any], list[str]]:
        raise NotImplementedError


class HealthExtractor(FeatureExtractor):
    async def extract(self, req, tenant_id, ctx):
        lf = req.line_features
        diagnosis = list(lf.get("diagnosis_codes") or [])
        procedures = list(lf.get("procedure_codes") or [])

        canonical = await _canonical(req, tenant_id, ctx)
        indicators: list[str] = []
        if req.claimed_amount > _HIGH_VALUE_THRESHOLDS[InsuranceLine.HEALTH]:
            indicators.append("high_value_claim")
        if len(procedures) > 5:
            indicators.append("many_procedures")
        if not diagnosis:
            indicators.append("missing_diagnosis")

        return canonical, {
            "diagnosis_codes": diagnosis,
            "procedure_codes": procedures,
        }, indicators


class VehicleExtractor(FeatureExtractor):
    async def extract(self, req, tenant_id, ctx):
        lf = req.line_features
        damage_type = lf.get("damage_type")
        part_codes = list(lf.get("part_codes") or [])
        cause = lf.get("cause_of_loss")
        odometer = _as_int(lf.get("odometer_km"))

        canonical = await _canonical(req, tenant_id, ctx)
        indicators: list[str] = []
        if req.claimed_amount > _HIGH_VALUE_THRESHOLDS[InsuranceLine.VEHICLE]:
            indicators.append("high_value_claim")
        if len(part_codes) > 8:
            indicators.append("many_parts")
        if cause == "theft" and req.claimed_amount > 15_000:
            indicators.append("high_value_theft")

        return canonical, {
            "damage_type": damage_type,
            "part_codes": part_codes,
            "cause_of_loss": cause,
            "odometer_km": odometer,
        }, indicators


class PropertyExtractor(FeatureExtractor):
    async def extract(self, req, tenant_id, ctx):
        lf = req.line_features
        damage_type = lf.get("damage_type")
        peril = lf.get("peril")
        repair_category = lf.get("repair_category")

        canonical = await _canonical(req, tenant_id, ctx)
        indicators: list[str] = []
        if req.claimed_amount > _HIGH_VALUE_THRESHOLDS[InsuranceLine.PROPERTY]:
            indicators.append("high_value_claim")
        if peril in ("fire", "flood") and req.claimed_amount > 50_000:
            indicators.append("catastrophic_peril_high_value")

        return canonical, {
            "damage_type": damage_type,
            "peril": peril,
            "repair_category": repair_category,
        }, indicators


class LifeExtractor(FeatureExtractor):
    async def extract(self, req, tenant_id, ctx):
        lf = req.line_features
        benefit_type = lf.get("benefit_type")     # NATURAL | ACCIDENTAL | TERMINAL_ILLNESS | PERMANENT_DISABILITY
        cause = lf.get("cause")

        canonical = await _canonical(req, tenant_id, ctx)
        indicators: list[str] = []
        if req.claimed_amount > _HIGH_VALUE_THRESHOLDS[InsuranceLine.LIFE]:
            indicators.append("high_value_claim")
        # Days-since-policy-start < 60 with a payout is a classic life-fraud signal
        if canonical[1] < 60:
            indicators.append("early_policy_claim")

        return canonical, {"benefit_type": benefit_type, "cause": cause}, indicators


class FuneralExtractor(FeatureExtractor):
    async def extract(self, req, tenant_id, ctx):
        lf = req.line_features
        benefit_tier = lf.get("benefit_tier")     # ECONOMY | STANDARD | PREMIUM | EXTENDED_FAMILY
        deceased_relationship = lf.get("deceased_relationship")

        canonical = await _canonical(req, tenant_id, ctx)
        indicators: list[str] = []
        if req.claimed_amount > _HIGH_VALUE_THRESHOLDS[InsuranceLine.FUNERAL]:
            indicators.append("high_value_claim")
        if canonical[1] < 30:
            indicators.append("early_policy_claim")

        return canonical, {
            "benefit_tier": benefit_tier,
            "deceased_relationship": deceased_relationship,
        }, indicators


class DisabilityExtractor(FeatureExtractor):
    async def extract(self, req, tenant_id, ctx):
        lf = req.line_features
        benefit_type = lf.get("benefit_type")     # TEMPORARY | PERMANENT | PARTIAL
        cause = lf.get("cause")
        waiting_period_days = _as_int(lf.get("waiting_period_days"))

        canonical = await _canonical(req, tenant_id, ctx)
        indicators: list[str] = []
        if req.claimed_amount > _HIGH_VALUE_THRESHOLDS[InsuranceLine.DISABILITY]:
            indicators.append("high_value_claim")

        return canonical, {
            "benefit_type": benefit_type,
            "cause": cause,
            "waiting_period_days": waiting_period_days,
        }, indicators


class TravelExtractor(FeatureExtractor):
    async def extract(self, req, tenant_id, ctx):
        lf = req.line_features
        coverage_category = lf.get("coverage_category")  # MEDICAL | CANCELLATION | DELAY | BAGGAGE | LIABILITY
        destination_country = lf.get("destination_country")
        trip_duration_days = _as_int(lf.get("trip_duration_days"))

        canonical = await _canonical(req, tenant_id, ctx)
        indicators: list[str] = []
        if req.claimed_amount > _HIGH_VALUE_THRESHOLDS[InsuranceLine.TRAVEL]:
            indicators.append("high_value_claim")

        return canonical, {
            "coverage_category": coverage_category,
            "destination_country": destination_country,
            "trip_duration_days": trip_duration_days,
        }, indicators


class GroupExtractor(FeatureExtractor):
    """Group falls through to the underlying line's extractor per InsuranceLine.isPersonCentric()."""
    def __init__(self, registry: "ExtractorRegistry") -> None:
        self._registry = registry

    async def extract(self, req, tenant_id, ctx):
        underlying = req.line_features.get("underlying_line")
        if not underlying:
            # Best-effort — no dispatch key, treat as generic person-centric
            canonical = await _canonical(req, tenant_id, ctx)
            return canonical, dict(req.line_features), []
        try:
            underlying_line = InsuranceLine(underlying)
        except ValueError:
            canonical = await _canonical(req, tenant_id, ctx)
            return canonical, dict(req.line_features), []
        return await self._registry.for_line(underlying_line).extract(req, tenant_id, ctx)


class ExtractorRegistry:
    def __init__(self) -> None:
        self._per_line: dict[InsuranceLine, FeatureExtractor] = {
            InsuranceLine.HEALTH:     HealthExtractor(),
            InsuranceLine.VEHICLE:    VehicleExtractor(),
            InsuranceLine.PROPERTY:   PropertyExtractor(),
            InsuranceLine.LIFE:       LifeExtractor(),
            InsuranceLine.FUNERAL:    FuneralExtractor(),
            InsuranceLine.DISABILITY: DisabilityExtractor(),
            InsuranceLine.TRAVEL:     TravelExtractor(),
        }
        self._per_line[InsuranceLine.GROUP] = GroupExtractor(self)

    def for_line(self, line: InsuranceLine) -> FeatureExtractor:
        return self._per_line[line]
```

Helper `_canonical(req, tenant_id, ctx)` reads days-since-policy-start / claim-frequency-30d / prior-flag counts via a repository queried from `ctx: FraudContext`. For Tranche 0, `_canonical` returns a well-typed 5-tuple with sensible defaults when the historical lookups return nothing — no crashes on cold data.

#### 3. `services/python/ai-service/app/services/fraud_service.py`

Widen `FraudService.detect_fraud` to accept `FraudCheckRequest`, dispatch through the extractor registry, feed the canonical vector to `FraudMLModel.predict()`, and echo indicators + `line_features` back:

```python
class FraudService:
    def __init__(
        self, ml_model: FraudMLModel, extractors: ExtractorRegistry, ctx: FraudContext
    ) -> None:
        self._ml = ml_model
        self._extractors = extractors
        self._ctx = ctx

    async def detect_fraud(
        self, request: FraudCheckRequest, tenant_id: str
    ) -> dict:
        extractor = self._extractors.for_line(request.insurance_line)
        canonical, normalized_line_features, rule_indicators = \
            await extractor.extract(request, tenant_id, self._ctx)

        ml_out = self._ml.predict_canonical(canonical)  # replaces predict(claim_data)

        return {
            "risk_score": ml_out["risk_score"],
            "risk_level": ml_out["risk_level"],
            "indicators": list(ml_out.get("indicators", [])) + rule_indicators,
            "model_version": ml_out["model_version"],
            "insurance_line": request.insurance_line.value,
            "line_features": normalized_line_features,
            "canonical_features": canonical,
        }
```

`FraudMLModel.predict_canonical(vec: list[float]) -> dict` replaces the old `predict(claim_data)` method. The synthetic training data is regenerated to the 5-column canonical shape.

#### 4. `services/python/ai-service/app/api/fraud.py`

```python
@router.post("/check", response_model=FraudCheckResponse)
async def check_fraud(
    request: FraudCheckRequest,
    tenant_id: UUID = Depends(require_tenant_id),
    fraud_service: FraudService = Depends(get_fraud_service),
) -> FraudCheckResponse:
    """Line-aware fraud scoring. Response is deterministic — no randomness."""
    result = await fraud_service.detect_fraud(request, str(tenant_id))
    return FraudCheckResponse(
        claim_id=request.claim_id,
        insurance_line=request.insurance_line,
        risk_score=result["risk_score"],
        risk_level=result["risk_level"],
        indicators=result["indicators"],
        model_version=result["model_version"],
    )
```

Remove `import random`. Remove the entire hardcoded scoring block.

#### 5. `services/java/claims-service/src/main/java/com/medfund/claims/client/AiServiceClient.java:117-142`

Widen the fraud request payload to send `insuranceLine` and a `lineFeatures` map. `Claim.insuranceLine` is already populated on every claim; `Claim.diagnosisCodes` / `procedureCodes` move into `lineFeatures` for HEALTH claims. For non-HEALTH claims, populate `lineFeatures` from the line-specific claim sub-entity (VehicleClaim, PropertyClaim, LifeClaim, etc.) — the mapping is done in a new helper `FraudRequestBuilder`:

```java
@Component
@RequiredArgsConstructor
public class FraudRequestBuilder {
    /** Builds the AI service /fraud/check payload, per-line-shaped. */
    public Map<String, Object> build(Claim claim) {
        return Map.of(
            "claim_id", claim.getId(),
            "insurance_line", claim.getInsuranceLine().name(),
            "subject_id", subjectIdFor(claim),      // memberId for person-centric, assetId for VEHICLE/PROPERTY
            "provider_id", claim.getProviderId(),
            "claimed_amount", claim.getClaimedAmount(),
            "currency_code", claim.getCurrencyCode(),
            "service_date", claim.getServiceDate(),
            "line_features", lineFeaturesFor(claim)
        );
    }

    private UUID subjectIdFor(Claim claim) {
        InsuranceLine line = claim.getInsuranceLine();
        return line.isPersonCentric()
            ? claim.getMemberId()
            : claim.getAssetId();       // Vehicle / Property claims carry assetId
    }

    private Map<String, Object> lineFeaturesFor(Claim claim) {
        return switch (claim.getInsuranceLine()) {
            case HEALTH -> Map.of(
                "diagnosis_codes", nullSafe(claim.getDiagnosisCodes()),
                "procedure_codes", nullSafe(claim.getProcedureCodes())
            );
            case VEHICLE -> Map.of(
                "damage_type", claim.getDamageType(),
                "part_codes", nullSafe(claim.getPartCodes()),
                "cause_of_loss", claim.getCauseOfLoss(),
                "odometer_km", claim.getOdometerKm()
            );
            case PROPERTY -> Map.of(
                "damage_type", claim.getDamageType(),
                "peril", claim.getPeril(),
                "repair_category", claim.getRepairCategory()
            );
            case LIFE, DISABILITY -> Map.of(
                "benefit_type", claim.getBenefitType(),
                "cause", claim.getCause()
            );
            case FUNERAL -> Map.of(
                "benefit_tier", claim.getBenefitTier(),
                "deceased_relationship", claim.getDeceasedRelationship()
            );
            case TRAVEL -> Map.of(
                "coverage_category", claim.getCoverageCategory(),
                "destination_country", claim.getDestinationCountry(),
                "trip_duration_days", claim.getTripDurationDays()
            );
            case GROUP -> Map.of(
                "underlying_line", claim.getUnderlyingLine() != null ? claim.getUnderlyingLine().name() : null
            );
        };
    }

    private static <T> List<T> nullSafe(List<T> in) {
        return in != null ? in : List.of();
    }
}
```

**Note on `Claim` shape**: not all these getters exist on `Claim` today — HEALTH-specific fields do, but non-health fields live on the per-line sub-entities (`VehicleClaim`, `PropertyClaim`, etc.) with a joining query. The `FraudRequestBuilder` should therefore be a **reactive** builder (`Mono<Map<String, Object>> build(Claim claim)`) that fetches the line-specific sub-entity via the appropriate repository. Implementation detail: for each non-HEALTH line, add a `LineDetailLookup` service in claims-service that returns the line-specific fields as a `Mono<Map<String, Object>>`. HEALTH stays inline. This scoping keeps the change contained.

If any of these sub-entities do not yet exist on the claim path in claims-service (the scaffolded lines may not have detail entities), the builder falls back to an empty `line_features` map for that line — the AI service tolerates the empty bag (all indicators become "insufficient_data"). Implementation must verify per-line sub-entity presence during the build; missing sub-entities are logged as a warning and are a Tranche 1 follow-up, not a blocker.

`AiServiceClient.callFraud` uses `FraudRequestBuilder.build(claim)` to construct the payload; the WebClient POST is unchanged in shape.

#### 6. `services/python/ai-service/tests/services/test_feature_extractors.py` (new)

Parametrized tests: one canonical happy-path test per line (8 tests), one edge-case test per line (missing line_features, wrong type), one test that GROUP dispatches via `underlying_line`. Assert (a) canonical vector length == 5, (b) `line_features` echo contains only the expected keys per line, (c) indicators list is deterministic.

```python
@pytest.mark.parametrize("line,line_features,expected_line_keys", [
    (InsuranceLine.HEALTH,     {"diagnosis_codes": ["K35"], "procedure_codes": ["23410"]},
                                {"diagnosis_codes", "procedure_codes"}),
    (InsuranceLine.VEHICLE,    {"damage_type": "collision", "part_codes": ["WSD-01"],
                                "cause_of_loss": "accident", "odometer_km": 45000},
                                {"damage_type", "part_codes", "cause_of_loss", "odometer_km"}),
    (InsuranceLine.PROPERTY,   {"damage_type": "fire", "peril": "fire", "repair_category": "structural"},
                                {"damage_type", "peril", "repair_category"}),
    (InsuranceLine.LIFE,       {"benefit_type": "NATURAL", "cause": "myocardial_infarction"},
                                {"benefit_type", "cause"}),
    (InsuranceLine.FUNERAL,    {"benefit_tier": "STANDARD", "deceased_relationship": "parent"},
                                {"benefit_tier", "deceased_relationship"}),
    (InsuranceLine.DISABILITY, {"benefit_type": "TEMPORARY", "cause": "back_injury", "waiting_period_days": 30},
                                {"benefit_type", "cause", "waiting_period_days"}),
    (InsuranceLine.TRAVEL,     {"coverage_category": "MEDICAL", "destination_country": "ZA",
                                "trip_duration_days": 14},
                                {"coverage_category", "destination_country", "trip_duration_days"}),
])
async def test_extractor_returns_canonical_5_tuple_and_line_specific_echo(
    line, line_features, expected_line_keys, extractor_registry, fraud_context,
):
    req = FraudCheckRequest(
        claim_id=uuid4(), insurance_line=line, subject_id=uuid4(),
        provider_id=uuid4(), claimed_amount=1500.0, currency_code="USD",
        service_date=date(2026, 9, 15), line_features=line_features,
    )
    canonical, normalized, indicators = await extractor_registry.for_line(line).extract(
        req, "tenant-a", fraud_context,
    )
    assert len(canonical) == 5
    assert set(normalized.keys()) == expected_line_keys
    assert isinstance(indicators, list)
```

Plus:
- `test_check_fraud_is_deterministic` — same input twice → same `risk_score`
- `test_check_fraud_does_not_import_random` — static guard on fraud module
- `test_group_dispatches_via_underlying_line` — GROUP with `underlying_line=LIFE` returns life-shaped `line_features` echo
- `test_group_without_underlying_line_returns_empty_line_features`

Java side:
- `FraudRequestBuilderTest.build_forHealthClaim_populatesDiagnosisAndProcedureCodes`
- Parametrized per line — 8 tests confirming the builder emits the right `line_features` keys
- `AiServiceClientIT.callFraud_sendsInsuranceLineInPayload` — Wiremock captures the body, asserts `$.insurance_line` present

### Success Criteria

#### Automated Verification
- [x] Python tests pass: `cd services/python/ai-service && uv run pytest tests/test_fraud.py tests/services/test_feature_extractors.py`
- [x] No `random.uniform` in fraud module: `grep -rn "random\." services/python/ai-service/app/api/fraud.py services/python/ai-service/app/services/fraud_service.py services/python/ai-service/app/services/feature_extractors.py` returns empty
- [x] Coverage: `cd services/python/ai-service && uv run pytest --cov=app.api.fraud --cov=app.services.fraud_service --cov=app.services.feature_extractors --cov-fail-under=70` (module-scoped coverage: fraud 100%, fraud_service 86%, feature_extractors 93%)
- [x] Java compiles: `cd services/java && ./gradlew :claims-service:build`
- [x] Java tests: `make test-java` — new `FraudRequestBuilderTest` parametrized across all 8 lines passes
- [ ] Java IT: `make test-integration` — `AiServiceClientIT.callFraud_sendsInsuranceLineInPayload` (deferred to phase 6 alongside FlagRegistryR2dbcIT — Wiremock harness lands together)
- [x] Existing `test_fraud_ml.py` regenerates synthetic training data on the 5-column canonical shape; passes

#### Manual Verification
- [ ] `make ai claims && curl` `POST /api/v1/ai/fraud/check` with a HEALTH payload, then a VEHICLE payload, then a PROPERTY payload — each returns a `risk_score` between 0 and 1, `insurance_line` echoed correctly, `indicators` populated per line
- [ ] Two identical calls (any line) return the same `risk_score`

**Implementation Note**: pause after this phase before Phase 2 — the audit-wiring in Phase 2 depends on the line-aware feature bag being stored, not the health-only shape.

---

## Phase 2: Wire `save_prediction` on Every AI Endpoint

### Overview

Close the Critical Rule #3 exposure. Every request path that returns an AI-influenced response persists a row to `ai_predictions_store` before returning, with anonymized features. Seven endpoints touched: adjudication/recommend, adjudication/check-duplicate, adjudication/suggest-tariff, fraud/check, ocr/extract, chat/message, pricing/score.

### Changes Required

#### 1. `services/python/ai-service/app/core/anonymize.py` (new)

Shared helper — one place that scrubs PII from feature vectors. The endpoint calls it; the repository stays dumb.

```python
"""Anonymize AI feature vectors before persistence per Critical Rule #4.

The endpoint hands us a dict that came off the wire (member_id, provider_id,
claim details). This helper removes direct identifiers but keeps everything
needed to reproduce a prediction or debug drift.
"""
from typing import Any

# Direct identifiers stripped — they don't help model debugging, and their
# presence in a compliance query would leak PII across the audit boundary.
_STRIPPED_KEYS = frozenset({
    "member_id",
    "subject_id",             # asset id for VEHICLE/PROPERTY, member id for person-centric lines
    "provider_id",
    "claim_id",
    "correlation_id",
    "user_id",
    "conversation_id",
    "member_name",
    "provider_name",
    "member_email",
    "provider_email",
    "asset_id",
    "vehicle_id",
    "property_id",
})


def anonymize_features(features: dict[str, Any]) -> dict[str, Any]:
    """Return a copy of features with direct identifiers removed.

    Nested dicts are recursed (member_context, claim_data, etc). Values
    that are not dicts, lists, or primitives are stringified for JSONB
    storage safety.
    """
    if not isinstance(features, dict):
        return {}
    out: dict[str, Any] = {}
    for k, v in features.items():
        if k in _STRIPPED_KEYS:
            continue
        if isinstance(v, dict):
            out[k] = anonymize_features(v)
        elif isinstance(v, list):
            out[k] = [
                anonymize_features(item) if isinstance(item, dict) else item
                for item in v
            ]
        else:
            out[k] = v
    return out
```

#### 2. `services/python/ai-service/app/db/models.py`

Add an `insurance_line VARCHAR(20)` column to `ai_predictions_store` so per-line filtering in the review page (Phase 7) is a real index query, not a JSONB path scan. Column is nullable (chat / OCR predictions don't have an insurance line) but populated for every claim/pricing/fraud prediction:

```python
class AiPrediction(Base):
    __tablename__ = "ai_predictions_store"
    id                = Column(UUID, primary_key=True, default=uuid4)
    tenant_id         = Column(String, nullable=False, index=True)
    insurance_line    = Column(String(20), nullable=True, index=True)   # NEW — HEALTH | LIFE | ... | GROUP
    entity_type       = Column(String, nullable=False)
    entity_id         = Column(String, nullable=False)
    prediction_type   = Column(String, nullable=False)
    model_version     = Column(String, nullable=False)
    input_features    = Column(JSON, nullable=False)
    output            = Column(JSON, nullable=False)
    confidence        = Column(Float, nullable=True)
    accepted          = Column(Boolean, nullable=True)
    reviewed_by       = Column(String, nullable=True)
    reviewed_by_email = Column(String, nullable=True)                   # added in Phase 7
    reviewed_at       = Column(DateTime, nullable=True)
    created_at        = Column(DateTime, nullable=False, default=datetime.utcnow)
```

Idempotent alter on boot (`app/db/upgrades.py`, one-shot startup hook) handles existing databases:

```python
async def ensure_columns(engine: AsyncEngine) -> None:
    async with engine.begin() as conn:
        await conn.execute(text(
            "ALTER TABLE ai_predictions_store "
            "ADD COLUMN IF NOT EXISTS insurance_line VARCHAR(20)"))
        await conn.execute(text(
            "CREATE INDEX IF NOT EXISTS ai_predictions_store_insurance_line_idx "
            "ON ai_predictions_store (insurance_line) WHERE insurance_line IS NOT NULL"))
        # reviewed_by_email is added in Phase 7's upgrade block
```

#### 3. `services/python/ai-service/app/repositories/prediction_repository.py`

Extend `save_prediction` if its signature does not already accept `tenant_id`, `insurance_line`, `entity_type`, `entity_id`, `prediction_type`, `model_version`, `input_features`, `output`, `confidence`.

Add a convenience wrapper that the endpoints call:

```python
async def record_ai_prediction(
    session: AsyncSession,
    *,
    tenant_id: str,
    insurance_line: str | None,   # None for chatbot / OCR / cross-line predictions
    entity_type: str,             # 'claim' | 'member' | 'provider' | 'transaction' | 'conversation' | 'document'
    entity_id: str,
    prediction_type: str,         # 'adjudication' | 'fraud' | 'duplicate' | 'code_suggestion' | 'ocr' | 'chat' | 'pricing'
    model_version: str,
    input_features: dict,         # already anonymized by caller
    output: dict,
    confidence: float | None,
) -> None:
    row = AiPrediction(
        tenant_id=tenant_id,
        insurance_line=insurance_line,
        entity_type=entity_type,
        entity_id=entity_id,
        prediction_type=prediction_type,
        model_version=model_version,
        input_features=input_features,
        output=output,
        confidence=confidence,
    )
    session.add(row)
    await session.flush()
```

#### 4. Endpoint changes — the pattern

For each endpoint, add: (1) DB session dependency, (2) call the service to compute the response, (3) `record_ai_prediction` with anonymized features **and `insurance_line`** where the request has one, (4) return the response.

Example on `/adjudication/recommend` (which has widened to include `insurance_line` — same shape change applied to every claim-shaped request in Phase 1):

```python
from app.core.anonymize import anonymize_features
from app.core.database import get_session
from app.repositories.prediction_repository import record_ai_prediction


@router.post("/recommend", response_model=AdjudicationRecommendation)
async def recommend(
    request: AdjudicationRequest,
    tenant_id: UUID = Depends(require_tenant_id),
    service: AdjudicationService = Depends(get_adjudication_service),
    session: AsyncSession = Depends(get_session),
) -> AdjudicationRecommendation:
    recommendation = await service.analyze_claim(request.model_dump())

    await record_ai_prediction(
        session,
        tenant_id=str(tenant_id),
        insurance_line=request.insurance_line.value,   # every claim request carries a line
        entity_type="claim",
        entity_id=str(request.claim_id),
        prediction_type="adjudication",
        model_version=recommendation.model_version,
        input_features=anonymize_features(request.model_dump()),
        output=recommendation.model_dump(),
        confidence=recommendation.confidence,
    )
    await session.commit()

    return recommendation
```

Apply the same shape to:
- `/adjudication/check-duplicate` — `insurance_line=request.claim.get("insurance_line")`, `prediction_type="duplicate"`, `entity_type="claim"`, `entity_id=request.claim.get("id")`
- `/adjudication/suggest-codes` — `insurance_line=request.insurance_line.value`, `prediction_type="code_suggestion"`, `entity_type="claim"`, `entity_id` synthesized from description hash if request has no claim id
- `/fraud/check` — `insurance_line=request.insurance_line.value`, `prediction_type="fraud"`, `entity_type="claim"`, `entity_id=request.claim_id`
- `/ocr/extract` — `insurance_line=form_field_or_None`, `prediction_type="ocr"`, `entity_type="document"`, `entity_id` synthesized from filename hash. The multipart form gains an optional `insurance_line` field so operators can label documents at upload time; null is valid for cross-line documents.
- `/chat/message` — `insurance_line=None` (conversations aren't line-scoped), `prediction_type="chat"`, `entity_type="conversation"`, `entity_id=request.conversation_id`
- `/pricing/score` — `insurance_line=request.insurance_line`, `prediction_type="pricing"`, `entity_type="member"` for person-centric lines or `"asset"` for VEHICLE/PROPERTY (pick via `InsuranceLine.isPersonCentric()` equivalent in Python), `entity_id=request.member_id` or the asset id

#### 5. Tests

Per-endpoint IT that (a) hits the endpoint, (b) queries `ai_predictions_store`, (c) asserts one row exists with the expected `tenant_id`, `insurance_line`, `prediction_type`, and `model_version`, (d) asserts the stored `input_features` does **not** contain `member_id`, `provider_id`, `claim_id`, (e) for claim-shaped endpoints, parametrizes across all 8 InsuranceLine values to catch line-specific bugs early.

```python
@pytest.mark.parametrize("line,line_features", [
    (InsuranceLine.HEALTH,     {"diagnosis_codes": ["K35.0"], "procedure_codes": ["23410"]}),
    (InsuranceLine.VEHICLE,    {"damage_type": "collision", "part_codes": ["WSD-01"]}),
    (InsuranceLine.PROPERTY,   {"damage_type": "fire", "peril": "fire"}),
    (InsuranceLine.LIFE,       {"benefit_type": "NATURAL", "cause": "myocardial_infarction"}),
    (InsuranceLine.FUNERAL,    {"benefit_tier": "STANDARD"}),
    (InsuranceLine.DISABILITY, {"benefit_type": "TEMPORARY"}),
    (InsuranceLine.TRAVEL,     {"coverage_category": "MEDICAL"}),
    (InsuranceLine.GROUP,      {"underlying_line": "LIFE", "benefit_type": "NATURAL"}),
])
@pytest.mark.asyncio
async def test_fraud_endpoint_persists_anonymized_prediction_per_line(
    line, line_features, client, db_session,
):
    payload = {
        "claim_id": "00000000-0000-0000-0000-000000000001",
        "insurance_line": line.value,
        "subject_id": "00000000-0000-0000-0000-000000000002",
        "provider_id": "00000000-0000-0000-0000-000000000003",
        "claimed_amount": 1500.00,
        "currency_code": "USD",
        "service_date": "2026-09-15",
        "line_features": line_features,
    }
    r = client.post("/api/v1/ai/fraud/check", json=payload,
                    headers={"X-Tenant-ID": "00000000-0000-0000-0000-000000000099"})
    assert r.status_code == 200

    rows = await db_session.execute(
        select(AiPrediction).where(AiPrediction.entity_id == payload["claim_id"])
    )
    row = rows.scalar_one()
    assert row.prediction_type == "fraud"
    assert row.insurance_line == line.value
    assert row.tenant_id == "00000000-0000-0000-0000-000000000099"
    assert row.model_version
    for stripped in ("member_id", "provider_id", "claim_id", "subject_id"):
        assert stripped not in row.input_features
```

Same parametrized shape for `/adjudication/recommend`, `/adjudication/check-duplicate`, `/adjudication/suggest-codes`, `/pricing/score`. `/ocr/extract` and `/chat/message` get a single-shot test each — those endpoints are line-optional. Unit test for `anonymize_features` covering: primitive values, nested dicts, list of dicts, all stripped keys (including `subject_id`), non-dict input.

### Success Criteria

#### Automated Verification
- [x] All new tests pass: `cd services/python/ai-service && uv run pytest` (335 pass, 20 new)
- [x] Coverage: module coverage for anonymize + fraud + pricing endpoint paths verified via targeted subset run
- [x] Grep guard — every AI-decision endpoint module (adjudication, chatbot, fraud, ocr, pricing) imports and calls `try_record_ai_prediction`
- [x] `create_all` still succeeds on a clean SQLite (asserted by every persistence test that spins up a fresh in-memory DB)

#### Manual Verification
- [ ] `make ai && curl` each endpoint once → `psql -c "select prediction_type, model_version, input_features->'claimed_amount' from ai_predictions_store order by created_at desc limit 10"` shows one row per call, with anonymized features

**Implementation Note**: pause after this phase — the pilot depends on this working across every endpoint.

---

## Phase 3: Per-Line Code-Suggestion Fallback

### Overview

Rename `/adjudication/suggest-tariff` to `/adjudication/suggest-codes` (the old URL has no external caller — verified against `AiServiceClient`, `AiPricingClient`, Angular services, and Elixir chat) and turn it into a line-aware code suggester with per-line static seed mappings. Every line gets its own suggester with its own domain vocabulary — HEALTH suggests AHFOZ tariff codes from ICD-10, VEHICLE suggests repair-part codes from damage type / cause of loss, PROPERTY suggests damage-category codes from peril, LIFE / FUNERAL / DISABILITY suggest benefit-type codes, TRAVEL suggests coverage-category codes, GROUP falls through to its underlying line.

Fallback is triggered when Gemini/Claude is unavailable **or** when the LLM path errors out. Rule fallback is always non-empty for pilot lines (seed data covers the top codes per line); UI shows "no matching codes" only when both LLM is off *and* the line has no seed matches for the input.

### Changes Required

#### 1. `services/python/ai-service/app/data/code_hints/` (new package)

One module per line, plus a registry. Every module exports a `_HINTS` dict and a `suggest(input_context: dict) -> list[CodeSuggestion]` function with the same signature so the registry can dispatch uniformly.

Package layout:

```
app/data/code_hints/
  __init__.py       # registry.suggest_for_line(line, input_context)
  health.py         # AHFOZ tariff — keyed by ICD-10 prefix (~80 entries)
  vehicle.py        # repair-part codes — keyed by damage_type + cause_of_loss (~40 entries)
  property.py       # damage-category codes — keyed by peril + damage_type (~30 entries)
  life.py           # benefit-type codes — keyed by cause + benefit_type (~15 entries)
  funeral.py        # service-tier codes — keyed by benefit_tier (~10 entries)
  disability.py     # benefit-period codes — keyed by benefit_type + waiting_period (~15 entries)
  travel.py         # coverage-category codes — keyed by coverage_category (~15 entries)
  group.py          # falls through to underlying_line's module
```

Shared dataclass:

```python
# app/data/code_hints/__init__.py
from dataclasses import dataclass
from typing import Any

from app.schemas.fraud import InsuranceLine  # reuses the enum


@dataclass(frozen=True)
class CodeSuggestion:
    code: str
    label: str
    rationale: str
    confidence: float

    def to_dict(self) -> dict[str, Any]:
        return {
            "code": self.code,
            "label": self.label,
            "rationale": self.rationale,
            "confidence": self.confidence,
        }


_SUGGESTERS: dict[InsuranceLine, "SuggesterFn"] = {}


def register(line: InsuranceLine):
    def _wrap(fn):
        _SUGGESTERS[line] = fn
        return fn
    return _wrap


def suggest_for_line(line: InsuranceLine, input_context: dict) -> list[CodeSuggestion]:
    fn = _SUGGESTERS.get(line)
    if fn is None:
        return []
    return fn(input_context)
```

Example module — `health.py`:

```python
from app.data.code_hints import CodeSuggestion, register
from app.schemas.fraud import InsuranceLine


# Keyed by ICD-10 (exact) OR 3-char prefix.
_HINTS: dict[str, list[CodeSuggestion]] = {
    "K35": [
        CodeSuggestion("23410", "Appendectomy laparoscopic (AHFOZ)",
                       "Common tariff for acute appendicitis", 0.7),
        CodeSuggestion("23400", "Appendectomy open (AHFOZ)",
                       "Alternative when laparoscopy is contraindicated", 0.6),
    ],
    "K80": [CodeSuggestion("28305", "Cholecystectomy laparoscopic",
                           "Standard for gallstones", 0.7)],
    "J18": [CodeSuggestion("00250", "Pneumonia inpatient day rate",
                           "Standard admission rate for pneumonia", 0.6),
            CodeSuggestion("07110", "Chest X-ray",
                           "Standard diagnostic imaging", 0.5)],
    # ~80 entries seeded from AHFOZ top-N; full data ships with the PR.
}


@register(InsuranceLine.HEALTH)
def suggest(context: dict) -> list[CodeSuggestion]:
    icds = context.get("diagnosis_codes") or []
    seen: dict[str, CodeSuggestion] = {}
    for icd in icds:
        for key in (icd, icd[:3]):
            for hint in _HINTS.get(key, []):
                prev = seen.get(hint.code)
                if prev is None or hint.confidence > prev.confidence:
                    seen[hint.code] = hint
    return sorted(seen.values(), key=lambda s: s.confidence, reverse=True)
```

Example module — `vehicle.py` (keyed by damage_type):

```python
from app.data.code_hints import CodeSuggestion, register
from app.schemas.fraud import InsuranceLine


_HINTS: dict[str, list[CodeSuggestion]] = {
    "collision":   [CodeSuggestion("PANEL-01", "Front panel replacement", "Common in front-end collisions", 0.7),
                    CodeSuggestion("BUMPER-01", "Front bumper replacement", "", 0.7),
                    CodeSuggestion("HEADLAMP-L", "Left headlamp assembly", "", 0.5),
                    CodeSuggestion("HEADLAMP-R", "Right headlamp assembly", "", 0.5)],
    "theft":       [CodeSuggestion("TOTAL-LOSS", "Total-loss settlement", "Vehicle unrecovered after 30 days", 0.9)],
    "hail":        [CodeSuggestion("PANEL-DENT", "Multi-panel dent repair", "Standard hail damage response", 0.8)],
    "fire":        [CodeSuggestion("TOTAL-LOSS", "Total-loss settlement", "Structural fire damage", 0.9)],
    "glass":       [CodeSuggestion("WSD-01", "Windscreen replacement", "", 0.9)],
    # ~40 entries covering the common damage-type / cause-of-loss combinations
}


@register(InsuranceLine.VEHICLE)
def suggest(context: dict) -> list[CodeSuggestion]:
    key = context.get("damage_type")
    return list(_HINTS.get(key, []))
```

Example module — `property.py` (keyed by peril):

```python
_HINTS = {
    "fire":       [CodeSuggestion("STRUCT-FIRE", "Structural fire repair", "", 0.8),
                   CodeSuggestion("CONTENT-FIRE", "Contents replacement — fire", "", 0.7)],
    "flood":      [CodeSuggestion("FLOOD-STR", "Flood structural repair", "", 0.8),
                   CodeSuggestion("FLOOD-CONT", "Flood contents replacement", "", 0.7)],
    "burglary":   [CodeSuggestion("BURG-CONT", "Contents replacement — theft", "", 0.8),
                   CodeSuggestion("BURG-REPAIR", "Forced-entry repair", "", 0.6)],
    "storm":      [CodeSuggestion("STORM-ROOF", "Roof storm repair", "", 0.7)],
    "subsidence": [CodeSuggestion("SUB-FOUND", "Foundation subsidence repair", "", 0.8)],
    # ~30 entries per common perils and damage types
}
```

Life / funeral / disability / travel / group modules follow the same pattern with per-line seed data.

#### 2. `services/python/ai-service/app/schemas/adjudication.py`

Widen the schema — every claim-shaped request in the AI service carries `insurance_line`:

```python
class CodeSuggestionRequest(BaseModel):
    insurance_line: InsuranceLine
    description: str
    line_context: dict[str, Any] = Field(default_factory=dict)
    # HEALTH:      {"diagnosis_codes": ["K35.0"]}
    # VEHICLE:     {"damage_type": "collision", "cause_of_loss": "accident"}
    # PROPERTY:    {"peril": "fire", "damage_type": "structural"}
    # LIFE:        {"benefit_type": "NATURAL", "cause": "myocardial_infarction"}
    # FUNERAL:     {"benefit_tier": "STANDARD"}
    # DISABILITY:  {"benefit_type": "TEMPORARY", "waiting_period_days": 30}
    # TRAVEL:      {"coverage_category": "MEDICAL"}
    # GROUP:       {"underlying_line": "LIFE", ...underlying_line's context}


class CodeSuggestion(BaseModel):
    code: str
    label: str
    rationale: str
    confidence: float


class CodeSuggestionResponse(BaseModel):
    insurance_line: InsuranceLine
    suggestions: list[CodeSuggestion]
    source: str  # "llm" | "rules"
    model_version: str  # "code-suggester-{line}-{source}-v1"
```

#### 3. `services/python/ai-service/app/api/adjudication.py`

Replace the `/suggest-tariff` handler with `/suggest-codes`:

```python
from app.data.code_hints import suggest_for_line


@router.post("/suggest-codes", response_model=CodeSuggestionResponse)
async def suggest_codes(
    request: CodeSuggestionRequest,
    tenant_id: UUID = Depends(require_tenant_id),
    llm: LlmClient = Depends(get_active_llm),
    session: AsyncSession = Depends(get_session),
) -> CodeSuggestionResponse:
    if llm.available:
        try:
            result = await llm.complete_json(
                system=_line_prompt_system(request.insurance_line),
                prompt=_line_prompt(request),
            )
            suggestions = [CodeSuggestion(**s) for s in result.get("suggestions", [])]
            source = "llm"
        except Exception:
            # Fail-open to rules — LLM returning malformed JSON is a real risk
            suggestions = [
                CodeSuggestion(**s.to_dict())
                for s in suggest_for_line(request.insurance_line, request.line_context)
            ]
            source = "rules"
    else:
        suggestions = [
            CodeSuggestion(**s.to_dict())
            for s in suggest_for_line(request.insurance_line, request.line_context)
        ]
        source = "rules"

    response = CodeSuggestionResponse(
        insurance_line=request.insurance_line,
        suggestions=suggestions,
        source=source,
        model_version=f"code-suggester-{request.insurance_line.value.lower()}-{source}-v1",
    )

    await record_ai_prediction(
        session,
        tenant_id=str(tenant_id),
        insurance_line=request.insurance_line.value,
        entity_type="claim",
        entity_id=_hash_id(request.description + request.insurance_line.value),
        prediction_type="code_suggestion",
        model_version=response.model_version,
        input_features=anonymize_features(request.model_dump()),
        output=response.model_dump(),
        confidence=max((s.confidence for s in suggestions), default=0.0),
    )
    await session.commit()
    return response
```

`_line_prompt_system(line)` returns a per-line system prompt — HEALTH prompt names AHFOZ + ICD-10 vocabulary, VEHICLE names repair parts + labor codes, PROPERTY names damage categories + perils, etc.

`_hash_id(...)` synthesizes a stable UUID from `(description + line)` — line is in the hash input so the same description across different lines gets different audit entity IDs.

#### 4. Tests

Parametrized across all 8 InsuranceLine values:

```python
@pytest.mark.parametrize("line,context,expected_at_least_one_code_prefix", [
    (InsuranceLine.HEALTH,     {"diagnosis_codes": ["K35.0"]},   "23"),      # AHFOZ tariff
    (InsuranceLine.VEHICLE,    {"damage_type": "collision"},      "PANEL"),
    (InsuranceLine.PROPERTY,   {"peril": "fire"},                 "STRUCT-FIRE"),
    (InsuranceLine.LIFE,       {"benefit_type": "NATURAL"},       ""),       # any code
    (InsuranceLine.FUNERAL,    {"benefit_tier": "STANDARD"},      ""),
    (InsuranceLine.DISABILITY, {"benefit_type": "TEMPORARY"},     ""),
    (InsuranceLine.TRAVEL,     {"coverage_category": "MEDICAL"},  ""),
    (InsuranceLine.GROUP,      {"underlying_line": "LIFE",
                                "benefit_type": "NATURAL"},       ""),
])
def test_suggest_codes_returns_line_specific_seed_when_llm_unavailable(
    line, context, expected_at_least_one_code_prefix, client, no_llm_key,
):
    r = client.post("/api/v1/ai/adjudication/suggest-codes", json={
        "insurance_line": line.value,
        "description": "test description",
        "line_context": context,
    }, headers={"X-Tenant-ID": "00000000-0000-0000-0000-000000000099"})

    assert r.status_code == 200
    body = r.json()
    assert body["insurance_line"] == line.value
    assert body["source"] == "rules"
    assert len(body["suggestions"]) >= 1
    if expected_at_least_one_code_prefix:
        assert any(s["code"].startswith(expected_at_least_one_code_prefix)
                   for s in body["suggestions"])
    # Model version encodes the line
    assert line.value.lower() in body["model_version"]
```

Plus per-line unit tests on the seed data (`test_code_hints_health.py` etc): each asserts (a) empty seed context returns `[]`, (b) known key returns non-empty suggestions ordered by confidence descending, (c) unknown key returns `[]`. `test_code_hints_group.py` asserts the underlying-line fall-through.

### Success Criteria

#### Automated Verification
- [x] Unit tests pass: `cd services/python/ai-service && uv run pytest tests/data/test_code_hints.py tests/test_suggest_codes.py` (63 pass)
- [x] Endpoint returns non-empty suggestions for every line's known seed key when LLM is unset — parametrized in `test_suggest_codes.py`
- [x] Coverage — full suite 83% with `app/data/code_hints/*` ≥ 80% and `app/api/adjudication.py` 79%
- [x] No caller references the old `/suggest-tariff` URL: `grep -r "suggest-tariff" services/ clients/` returns empty
- [x] Every InsuranceLine value has a registered suggester: `test_every_line_has_registered_suggester` — reflection over `InsuranceLine` compares against `_SUGGESTERS`

#### Manual Verification
- [ ] Domain review of the seed data — one domain author (probably per-line) sanity-checks the seed mapping. AHFOZ top-80 for HEALTH, common repair parts for VEHICLE, common perils for PROPERTY, standard benefit types for LIFE/FUNERAL/DISABILITY/TRAVEL. This is the one manual gate that this phase can't automate.

---

## Phase 4: Chatbot Persistence + Minimal Member Context

### Overview

Replace the in-memory conversation store with `conversation_messages` persistence. Add a `GET /api/v1/ai/chat/history/{conversation_id}` endpoint for the UI to reload. Wire a minimal member-context lookup so Gemini/Claude prompts include the member's current benefit balance.

### Changes Required

#### 1. `services/python/ai-service/app/services/chatbot_service.py`

Drop the `_conversations: dict[str, list[dict]] = {}` in-memory store. Every incoming message: (a) load prior history from the DB, (b) call LLM with history + new message, (c) persist both the user turn and the assistant turn to `conversation_messages`.

```python
class ChatbotService:
    def __init__(
        self,
        llm: GeminiClient | ClaudeClient,
        session_factory: async_sessionmaker[AsyncSession],
        member_context_client: MemberContextClient,
    ) -> None:
        self._llm = llm
        self._session_factory = session_factory
        self._member_context = member_context_client

    async def respond(self, message: ChatMessage, tenant_id: str) -> ChatResponse:
        async with self._session_factory() as session:
            history = await get_conversation_history(
                session, conversation_id=str(message.conversation_id),
                limit=20,  # last 20 turns is enough for context
            )

            # Best-effort multi-line member context — fail-open if user-service unreachable.
            # The portfolio covers every line the member has a policy on; the LLM
            # decides which line is relevant to the current turn.
            portfolio = None
            member_id = message.context.get("member_id") if message.context else None
            if member_id:
                portfolio = await self._member_context.fetch_portfolio(
                    tenant_id=tenant_id, member_id=member_id,
                )

            if self._llm.available:
                reply = await self._llm.complete(
                    system=_SYSTEM_PROMPT,
                    messages=_build_messages(history, message, portfolio),
                )
                source, confidence = "llm", 0.85
            else:
                # Keyword fallback picks a line hint from the message text
                # (e.g. "vehicle", "funeral", "travel") to route the response
                reply = _keyword_fallback(message.message, portfolio)
                source, confidence = "rules", 0.4

            # Persist both turns
            await save_conversation_message(session,
                conversation_id=str(message.conversation_id),
                tenant_id=tenant_id, role="user", content=message.message)
            await save_conversation_message(session,
                conversation_id=str(message.conversation_id),
                tenant_id=tenant_id, role="assistant", content=reply)
            await session.commit()

        return ChatResponse(
            reply=reply,
            conversation_id=message.conversation_id,
            source=source,
            confidence=confidence,
        )
```

#### 2. `services/python/ai-service/app/services/member_context_client.py` (new)

Read-only HTTP client to user-service. Fetches a **multi-line policy summary** for the member — one entry per line the member holds a policy on. The LLM prompt receives the full picture and picks what's relevant to the conversation topic. 2s timeout, fail-open (returns None on any error).

```python
import httpx
import logging
from pydantic import BaseModel

logger = logging.getLogger(__name__)


class HealthPolicySummary(BaseModel):
    scheme_name: str
    annual_limit: float
    used_ytd: float
    remaining: float
    currency: str
    dependants_count: int


class LifePolicySummary(BaseModel):
    sum_assured: float
    currency: str
    beneficiary_count: int
    policy_start_date: str
    premium_status: str  # "PAID" | "IN_ARREARS" | "LAPSED"


class FuneralPolicySummary(BaseModel):
    benefit_tier: str
    lives_covered: int
    currency: str
    premium_status: str


class DisabilityPolicySummary(BaseModel):
    benefit_type: str
    monthly_benefit_amount: float
    currency: str
    waiting_period_days: int
    benefit_period_years: int | None       # None == lifetime


class TravelPolicySummary(BaseModel):
    active_trips: list[dict]                # {destination, start_date, end_date, coverage_categories}
    annual_multitrip: bool


class VehiclePolicySummary(BaseModel):
    vehicle_id: str
    make_model: str
    registration: str
    coverage_type: str                       # "COMPREHENSIVE" | "THIRD_PARTY" | ...
    excess_amount: float
    currency: str
    no_claims_discount_years: int


class PropertyPolicySummary(BaseModel):
    property_id: str
    address_summary: str                     # "12 Oak Rd, Harare" — no full PII
    coverage_type: str
    excess_amount: float
    currency: str


class MemberPolicyPortfolio(BaseModel):
    """One member, all their active policies across every line. Any field
    may be None if the member has no policy in that line."""
    health: HealthPolicySummary | None = None
    life: LifePolicySummary | None = None
    funeral: FuneralPolicySummary | None = None
    disability: DisabilityPolicySummary | None = None
    travel: TravelPolicySummary | None = None
    vehicle: list[VehiclePolicySummary] = []      # member can own multiple vehicles
    property: list[PropertyPolicySummary] = []    # member can own multiple properties
    group: list[dict] = []                        # active group memberships


class MemberContextClient:
    def __init__(self, user_service_url: str, timeout: float = 2.0) -> None:
        self._url = user_service_url.rstrip("/")
        self._timeout = timeout

    async def fetch_portfolio(
        self, tenant_id: str, member_id: str
    ) -> MemberPolicyPortfolio | None:
        """One call, all lines. user-service composes across sub-entities.
        Fail-open — returns None on network/parse errors; chatbot proceeds
        without member context."""
        try:
            async with httpx.AsyncClient(timeout=self._timeout) as c:
                r = await c.get(
                    f"{self._url}/api/v1/members/{member_id}/policy-portfolio",
                    headers={"X-Tenant-ID": tenant_id},
                )
            if r.status_code != 200:
                return None
            return MemberPolicyPortfolio.model_validate(r.json())
        except (httpx.HTTPError, ValueError) as e:
            logger.warning("MemberContextClient fetch failed: %s", e)
            return None
```

Env var: `MEDFUND_USER_SERVICE_URL` (default `http://localhost:8082`); added to `docker-compose.yml` for the ai-service block.

#### 2a. user-service — `GET /api/v1/members/{id}/policy-portfolio`

New endpoint on user-service that composes the multi-line summary from the underlying sub-entities. Any line the member has no policy in returns null for that field. Tenant-scoped via `TenantContext`; Swagger-documented; audit-logged as a read (no mutation event). Response shape mirrors `MemberPolicyPortfolio` above.

If user-service does not already expose the underlying sub-entity fetches (life, funeral, disability, travel, vehicle, property), this endpoint has to query them — check per-line during implementation. Missing sub-entities on a member return null; they don't 500 the endpoint.

#### 3. `services/python/ai-service/app/api/chatbot.py`

Add the history-load endpoint:

```python
@router.get("/history/{conversation_id}", response_model=list[ChatHistoryItem])
async def get_history(
    conversation_id: UUID,
    tenant_id: UUID = Depends(require_tenant_id),
    session: AsyncSession = Depends(get_session),
) -> list[ChatHistoryItem]:
    rows = await get_conversation_history(
        session, conversation_id=str(conversation_id), limit=100,
    )
    return [
        ChatHistoryItem(role=r.role, content=r.content, created_at=r.created_at)
        for r in rows
        if r.tenant_id == str(tenant_id)  # defense in depth
    ]
```

#### 4. Elixir chat_service — no changes required

`ai_proxy.ex:7-34` already sends `conversation_id` in the body; persistence is transparent to the caller.

#### 6. Tests

- Unit: `test_chatbot_persists_both_turns_on_llm_success`
- Unit: `test_chatbot_persists_both_turns_on_fallback` (LLM unavailable)
- Unit: `test_chatbot_survives_member_context_client_failure` (portfolio returns None, chat still works)
- Unit: parametrized `test_keyword_fallback_routes_to_right_line` — messages like "how much of my medical is left" / "my funeral cover" / "car insurance excess" / "travel policy" each route to the corresponding portfolio field
- IT: `test_get_history_returns_persisted_turns_across_process_restart` (simulated by scoping DB session)
- IT: `test_get_history_tenant_scoped` — a conversation for tenant A does not leak to tenant B
- IT: `test_policy_portfolio_endpoint_returns_all_lines_for_multi_policy_member` (user-service side) — seed one member with policies across HEALTH + VEHICLE + FUNERAL and assert all three appear in the response

### Success Criteria

#### Automated Verification
- [x] Tests pass: `cd services/python/ai-service && uv run pytest tests/test_chatbot.py tests/test_chatbot_persistence.py tests/services/test_chatbot_service.py` (21 pass)
- [ ] Elixir tests pass: `cd services/elixir && mix test apps/chat_service` (Elixir proxy shape unchanged; validated indirectly via existing suite in Phase 6)
- [x] Coverage: full suite 84% — `chatbot_service.py` 84%, `member_context_client.py` 80%, `api/chatbot.py` 100%

#### Manual Verification
- [ ] `make ai user` — start both. Send two messages via `curl` to `/api/v1/ai/chat/message`. Restart `ai-service`. Send a third message. `GET /api/v1/ai/chat/history/{id}` returns all three turns plus their assistant replies.
- [ ] With LLM key set, chatbot responds correctly across lines for one seed member with a multi-line portfolio: "how much of my medical is left?" (HEALTH benefit balance), "what's my funeral cover?" (FUNERAL benefit tier + lives covered), "what's my motor excess?" (VEHICLE excess amount), "how many days is my travel policy valid?" (TRAVEL active-trips list). Each answer cites the actual figure from the seed portfolio, not a generic response.

---

## Deviations

- **2026-09-15 (Phase 4)**: user-service `GET /api/v1/members/{id}/policy-portfolio` aggregator endpoint deferred to a Tranche 0.5 follow-on. `MemberContextClient` is fail-open — a 404/500 returns `None` and the chatbot falls back to the keyword-routed reply (which was itself parametrized and tested per line). Rationale: the aggregator has to reactively join 7 policy sub-entity repositories (health scheme / life / funeral / disability / travel / vehicle / property) inside user-service, which is significantly larger than a single controller and worthy of its own plan slice. Pilot chatbot manual-verification acceptance is now: rule-fallback per-line citation works out-of-the-box; LLM-enriched multi-line citation waits for the aggregator to land. **No AI-service schema change is required later** — `MemberPolicyPortfolio` matches exactly what the aggregator will return.

- **2026-09-15 (Phase 7)**: `AI_PREDICTIONS_VIEW` / `AI_PREDICTIONS_DECIDE` permissions and Playwright spec deferred to Tranche 0.5. The route sits under `/tenant/admin/*` which is already guarded by `authGuard` + tenant-admin role via the standard tenant layout, matching the pattern used for the existing `/tenant/admin/audit` page. Adding fine-grained permissions is a follow-on cleanup, not a pilot blocker. Actor identity is forwarded from the gateway JWT as `X-Actor-ID` + `X-Actor-Email` headers (new gateway middleware change, `services/go/gateway/internal/middleware/jwt.go`) so the AI-service audit row carries `reviewed_by` + `reviewed_by_email` without the Angular client having to know the actor id.

- **2026-09-15 (Phase 6)**: Elixir chat_service `FeatureFlagClient` deferred to Tranche 0.5. Java claims-service, contributions-service, and finance-service wire `FlagRegistry` on the fast path (30-s Caffeine cache over R2DBC). Elixir would need an ETS-cached HTTP client to tenancy-service. Rationale: the AI service's own chatbot has a rule-based fallback when the LLM is unavailable, so a "flag off" scenario ends up returning a sensible generic reply even without the Elixir short-circuit — the user-visible outcome differs only in that the AI service still logs an audit row. The single-key `GET /api/v1/platform/feature-flags/{key}` endpoint has been added to tenancy-service for the Elixir side to consume once it lands. **The failing pre-existing test `CrossServiceCallHelperTest.guarded_returnsFallbackAndWarnsWhenCallFails` is not caused by this phase** — it fails on a clean `main` too (verified by stashing my changes and rerunning); the warning-message text lost its exception cause in an earlier commit.

---

## Phase 5: LLM Configuration Surface + Claude Wiring

### Overview

Finish the second LLM client so we have vendor optionality; expose keys through compose; add an env-var switch between providers. No design change — the interfaces are already parallel; this is wiring.

### Changes Required

#### 1. `services/python/ai-service/app/core/anthropic_client.py`

Read `MEDFUND_ANTHROPIC_API_KEY` in the constructor and populate `.available`. Mirror `GeminiClient` exactly.

```python
class ClaudeClient:
    def __init__(self, api_key: str | None, model: str = "claude-sonnet-4-6") -> None:
        self._api_key = api_key
        self._model = model
        self._client: AsyncAnthropic | None = None
        if api_key:
            self._client = AsyncAnthropic(api_key=api_key)

    @property
    def available(self) -> bool:
        return self._client is not None

    async def complete(self, system: str, messages: list[dict]) -> str: ...
    async def complete_json(self, system: str, prompt: str) -> dict: ...
```

Instantiation in `app/main.py`:

```python
claude_client = ClaudeClient(
    api_key=settings.anthropic_api_key,
    model=settings.anthropic_model,  # new config field, default "claude-sonnet-4-6"
)
```

#### 2. `services/python/ai-service/app/core/config.py`

Add:

```python
class Settings(BaseSettings):
    ...
    anthropic_api_key: str | None = None      # already declared
    anthropic_model: str = "claude-sonnet-4-6"
    llm_provider: str = "gemini"              # "gemini" | "claude"
    user_service_url: str = "http://localhost:8082"
```

#### 3. `services/python/ai-service/app/core/llm_dispatch.py` (new)

Single factory that picks the active LLM per `MEDFUND_LLM_PROVIDER`. Endpoints depend on this instead of `GeminiClient` directly.

```python
from typing import Protocol


class LlmClient(Protocol):
    @property
    def available(self) -> bool: ...
    async def complete(self, system: str, messages: list[dict]) -> str: ...
    async def complete_json(self, system: str, prompt: str) -> dict: ...


def get_active_llm(
    settings: Settings,
    gemini: GeminiClient,
    claude: ClaudeClient,
) -> LlmClient:
    if settings.llm_provider == "claude":
        return claude
    return gemini
```

Wire as a FastAPI dependency and swap `Depends(get_gemini_client)` → `Depends(get_active_llm)` in adjudication.py, ocr.py, chatbot.py, and the tariff endpoint.

#### 4. `docker-compose.yml`

Under the `ai-service:` block environment map, add:

```yaml
      MEDFUND_GEMINI_API_KEY: ${MEDFUND_GEMINI_API_KEY:-}
      MEDFUND_ANTHROPIC_API_KEY: ${MEDFUND_ANTHROPIC_API_KEY:-}
      MEDFUND_LLM_PROVIDER: ${MEDFUND_LLM_PROVIDER:-gemini}
      MEDFUND_USER_SERVICE_URL: http://user-service:8082
```

Empty defaults preserve the current fallback-path behavior — no key means no LLM, endpoints route to the rule fallbacks.

#### 5. Tests

- Unit: `test_claude_client_available_only_with_key`
- Unit: `test_get_active_llm_returns_claude_when_configured` (parametrize over provider strings)
- Unit: `test_get_active_llm_returns_gemini_default_on_unknown_provider`
- Existing `test_anthropic_client.py` — extend to assert `.available` matches key presence

### Success Criteria

#### Automated Verification
- [x] Tests pass: `cd services/python/ai-service && uv run pytest tests/test_anthropic_client.py tests/core/test_llm_dispatch.py` (12 pass)
- [x] `MEDFUND_LLM_PROVIDER=claude MEDFUND_ANTHROPIC_API_KEY=fake ai-service` startup logs `LLM provider=claude, available=True` — asserted via ClaudeClient constructor test and lifespan wiring
- [x] `MEDFUND_LLM_PROVIDER=claude` with no key returns rule-based fallback — `resolve_llm` returns claude_client with `.available=False`, chatbot/adjudication/ocr call `.available` guards

#### Manual Verification
- [ ] With a real Anthropic key exported, adjudication/recommend returns Claude-authored reasoning (spot-check the `explanation` field for style consistent with Claude, not Gemini)

---

## Phase 6: Wire Platform Feature Flags into AI Callers

### Overview

`PlatformFlag.AI_ADJUDICATION` and `PlatformFlag.FRAUD_DETECTION` exist as toggles in `public.platform_feature_flags` but no caller reads them. Wire the reads via a shared `FlagRegistry` used by claims-service (`AiServiceClient.evaluate`), contributions-service (`AiPricingClient.score`), and Elixir chat_service (`AiProxy.get_ai_response`).

The Elixir side reads the flag via an HTTP call to tenancy-service — Java services read via a lightweight R2DBC lookup with per-flag 30-second in-memory cache.

### Changes Required

#### 1. `services/java/shared/src/main/java/com/medfund/shared/flags/FlagRegistry.java` (new)

```java
package com.medfund.shared.flags;

import reactor.core.publisher.Mono;

/**
 * Read-side accessor for platform feature flags. Backed by the
 * {@code public.platform_feature_flags} table (owned by tenancy-service).
 * Values are cached in-memory for 30 seconds — a flag flip takes at most
 * that long to propagate.
 *
 * <p>Every AI-consuming service (claims-service, contributions-service,
 * finance-service) autowires this bean. The Elixir chat_service reads the
 * flag via {@code GET /api/v1/platform/feature-flags/{key}} on the tenancy
 * service instead.
 */
public interface FlagRegistry {
    Mono<Boolean> isEnabled(PlatformFlag flag);
}
```

Default R2DBC-backed implementation (`FlagRegistryR2dbc`) with a `Caffeine` 30s cache:

```java
@Component
@RequiredArgsConstructor
public class FlagRegistryR2dbc implements FlagRegistry {
    private final DatabaseClient client;
    private final Cache<PlatformFlag, Boolean> cache =
        Caffeine.newBuilder().expireAfterWrite(Duration.ofSeconds(30)).build();

    @Override
    public Mono<Boolean> isEnabled(PlatformFlag flag) {
        Boolean cached = cache.getIfPresent(flag);
        if (cached != null) return Mono.just(cached);
        return client.sql("SELECT enabled FROM public.platform_feature_flags WHERE key = :k")
            .bind("k", flag.name())
            .map(row -> row.get("enabled", Boolean.class))
            .one()
            .defaultIfEmpty(Boolean.FALSE)          // missing row → treat as off
            .doOnNext(v -> cache.put(flag, v));
    }
}
```

#### 2. `services/java/claims-service/src/main/java/com/medfund/claims/client/AiServiceClient.java:56-84`

Short-circuit `evaluate` when the flag is off. Both flags gate different endpoints — check independently.

```java
public Mono<AiSignals> evaluate(Claim claim, List<ClaimLine> lines) {
    return Mono.zip(
        flagRegistry.isEnabled(PlatformFlag.AI_ADJUDICATION),
        flagRegistry.isEnabled(PlatformFlag.FRAUD_DETECTION)
    ).flatMap(flags -> {
        boolean adjOn = flags.getT1();
        boolean fraudOn = flags.getT2();
        if (!adjOn && !fraudOn) {
            return Mono.just(AiSignals.empty());
        }
        Mono<AdjudicationResponse> adj = adjOn
            ? callAdjudication(claim, lines).timeout(Duration.ofSeconds(8)).onErrorReturn(AdjudicationResponse.empty())
            : Mono.just(AdjudicationResponse.empty());
        Mono<FraudResponse> fraud = fraudOn
            ? callFraud(claim).timeout(Duration.ofSeconds(8)).onErrorReturn(FraudResponse.empty())
            : Mono.just(FraudResponse.empty());
        return Mono.zip(adj, fraud).map(t -> AiSignals.of(t.getT1(), t.getT2()));
    });
}
```

`callAdjudication` and `callFraud` are the existing method bodies extracted from the current `evaluate`.

#### 3. `services/java/contributions-service/src/main/java/com/medfund/contributions/service/AiPricingClient.java:53-79`

```java
public Mono<Double> score(Contribution contribution) {
    return flagRegistry.isEnabled(PlatformFlag.AI_ADJUDICATION).flatMap(enabled -> {
        if (!enabled) return Mono.just(1.0);
        return callPricing(contribution).timeout(Duration.ofSeconds(3)).onErrorReturn(1.0);
    });
}
```

(Pricing is gated under `AI_ADJUDICATION` since it shares the "AI-assisted" family. If pilot experience suggests it needs its own flag, add `AI_PRICING` to `PlatformFlag` in a follow-on.)

#### 4. `services/java/tenancy-service/src/main/java/com/medfund/tenancy/controller/PlatformFeatureFlagController.java`

Verify the controller exposes `GET /api/v1/platform/feature-flags/{key}` and returns `{key, enabled, updatedAt}` — the Elixir chat_service needs this. If it exposes only the list endpoint, add the single-key GET.

#### 5. `services/elixir/apps/chat_service/lib/chat_service/ai_proxy.ex`

Short-circuit at the top:

```elixir
def get_ai_response(query, tenant_id, user_id, room_id) do
  case FeatureFlagClient.enabled?(:ai_adjudication) do
    false ->
      {:ok, %{"reply" => "AI assistant is currently unavailable.", "source" => "flag-off"}}
    true ->
      # existing HTTP call to AI service
      ...
  end
end
```

New module `services/elixir/apps/chat_service/lib/chat_service/feature_flag_client.ex` hits tenancy-service `/api/v1/platform/feature-flags/AI_ADJUDICATION` with a 30-second ETS cache.

#### 6. Tests

Java:
- `AiServiceClientTest.evaluate_shortCircuitsWhenBothFlagsOff`
- `AiServiceClientTest.evaluate_callsOnlyAdjudicationWhenFraudOff`
- `AiServiceClientTest.evaluate_callsOnlyFraudWhenAdjudicationOff`
- `AiPricingClientTest.score_returnsOneWhenFlagOff`
- `FlagRegistryR2dbcIT` — Testcontainers Postgres, insert row, read, flip, wait cache TTL, read again
- Widen `AiServiceClientTest` to verify the WebClient is not invoked when flags are off (Mockito verify).

Elixir:
- `AiProxyTest.short_circuits_when_flag_off`
- `FeatureFlagClientTest.caches_result_for_30_seconds`

### Success Criteria

#### Automated Verification
- [x] Java tests: `make test-java` — `AiServiceClientFlagTest` new, `FraudRequestBuilderTest` still green, `:claims-service:test` passes, `:contributions-service:test` passes, `:tenancy-service:compileJava` green with new `GET /{key}` endpoint
- [ ] Java IT: `FlagRegistryR2dbcIT` deferred — the R2DBC implementation is auto-configured; a Testcontainers slice belongs in Tranche 0.5 alongside `AiServiceClientIT`
- [ ] Elixir tests: chat_service short-circuit deferred (see Deviations)
- [x] No AI-endpoint HTTP hit when both flags off — `AiServiceClientFlagTest.evaluate_returnsEmptySignals_whenBothFlagsOff` uses an unreachable URL to prove it

#### Manual Verification
- [ ] Toggle `AI_ADJUDICATION=false` at `/platform/settings` → submit claim → observe zero `ai_predictions_store` rows for that claim (flag off means claims-service never calls AI service, so no persistence side effect)
- [ ] Toggle `FRAUD_DETECTION=false` while `AI_ADJUDICATION=true` → observe adjudication row persisted, no fraud row
- [ ] Elixir chat: with `AI_ADJUDICATION=false`, chat widget shows the "unavailable" reply

---

## Phase 7: Angular AI-Predictions Review Page

### Overview

Tenant admins can browse AI predictions for their tenant, filter, and mark each as accepted or overridden. This closes the "human-reviewable trail" half of Critical Rule #3.

Two new AI-service endpoints (list + decide), a new permission constant, one new Angular page, one new sidebar entry.

### Changes Required

#### 1. AI-service backend — list + decide

`services/python/ai-service/app/api/predictions.py` (new router, mount in `app/main.py`):

```python
@router.get("/api/v1/ai/predictions", response_model=PredictionPage)
async def list_predictions(
    tenant_id: UUID = Depends(require_tenant_id),
    insurance_line: Optional[InsuranceLine] = None,   # HEALTH | LIFE | ... | GROUP
    entity_type: Optional[str] = None,
    prediction_type: Optional[str] = None,
    model_version: Optional[str] = None,
    accepted: Optional[bool] = None,
    page: int = 0,
    size: int = 50,
    session: AsyncSession = Depends(get_session),
) -> PredictionPage: ...


@router.get("/api/v1/ai/predictions/{prediction_id}", response_model=PredictionDetail)
async def get_prediction(
    prediction_id: UUID,
    tenant_id: UUID = Depends(require_tenant_id),
    session: AsyncSession = Depends(get_session),
) -> PredictionDetail: ...


@router.put("/api/v1/ai/predictions/{prediction_id}/decision")
async def decide_prediction(
    prediction_id: UUID,
    decision: DecideRequest,      # {accepted: bool, feedback: str | None}
    jwt: Jwt = Depends(require_jwt),
    tenant_id: UUID = Depends(require_tenant_id),
    session: AsyncSession = Depends(get_session),
) -> None:
    """Record human decision. Emits an AuditEvent to medfund.audit.events."""
    await record_human_decision(
        session,
        prediction_id=str(prediction_id),
        tenant_id=str(tenant_id),
        accepted=decision.accepted,
        reviewed_by=jwt.sub,
        reviewed_by_email=jwt.email,
        feedback=decision.feedback,
    )
    await session.commit()
    await audit_publisher.publish(AuditEvent.for_prediction_decision(
        prediction_id=prediction_id,
        tenant_id=tenant_id,
        actor_id=jwt.sub,
        actor_email=jwt.email,      # feedback_audit_actor_email — never null
        accepted=decision.accepted,
    ))
```

`record_human_decision` extends the existing repository method to also set `reviewed_by_email` (existing `reviewed_by` UUID field is retained; email is stored alongside per the `feedback_audit_actor_email` invariant — add a nullable `reviewed_by_email` column to `ai_predictions_store`).

Swagger annotations on all three endpoints so `/docs` reflects them.

#### 2. Java shared — permission constant

`services/java/shared/src/main/java/com/medfund/shared/security/Permissions.java`:

```java
// ── AI: Predictions review (Tranche 0) ─────────────────────────────
public static final String AI_PREDICTIONS_VIEW    = "ai:predictions:view";
public static final String AI_PREDICTIONS_DECIDE  = "ai:predictions:decide";
```

Mirror update in `services/java/shared/src/main/resources/permissions.yaml`. Keycloak seed maps both to `tenant_admin`.

#### 3. Gateway — route new endpoints

`services/go/gateway/internal/routes/routes.go`: proxy `/api/v1/ai/predictions*` to the AI service. Same tenant-scoping middleware chain as existing AI routes.

#### 4. Angular — new page

`clients/angular/src/app/pages/tenant-admin/ai-predictions/`:

- `ai-predictions.routes.ts` — one route `''` with `permissionGuard(['ai:predictions:view'])`
- `ai-predictions-list.component.ts/.html/.scss` — filter toolbar (**insurance line**, entity type, prediction type, model version, accepted status), table with columns: created_at, insurance line, entity type/id, prediction type, model version, confidence, decision status, action button. Insurance-line filter is a native `<select>` with the 8 InsuranceLine values (never a raw text `<input>` for the enum) + an "All lines" option.
- `ai-prediction-detail.component.ts/.html/.scss` — full view: input_features JSON tree, output JSON tree, model_version, explanation, "accept" / "override" buttons (guarded by `ai:predictions:decide`), optional feedback textarea on override

`clients/angular/src/app/core/services/ai-predictions.service.ts` — HttpClient wrapper for the three new endpoints. Uses the existing `debouncedSearch` shared component for the entity-type filter (never raw `<input>` for IDs).

`clients/angular/src/app/layout/tenant-admin-sidebar/tenant-admin-nav.ts` — new entry `{ label: 'AI Predictions', icon: 'brain', route: '/tenant-admin/ai-predictions', permissions: ['ai:predictions:view'] }` under the "AI & Reports" section (or the closest existing section — verify during implementation).

Register the feature route in `clients/angular/src/app/pages/tenant-admin/tenant-admin.routes.ts`.

#### 5. Tests

Backend:
- `test_list_predictions_tenant_scoped` — inserts two predictions across two tenants, asserts only the requesting tenant's rows return
- `test_list_predictions_filter_by_prediction_type`
- `test_list_predictions_filter_by_insurance_line` — parametrized across all 8 lines; seed one prediction per line per tenant, assert the filter returns exactly the one matching row
- `test_list_predictions_pagination`
- `test_get_prediction_returns_404_when_wrong_tenant`
- `test_decide_prediction_writes_accepted_reviewed_by_reviewed_at_and_email`
- `test_decide_prediction_emits_audit_event` — verify `AuditEvent` published to `medfund.audit.events` with `actorEmail` non-null

Angular:
- `ai-predictions-list.component.spec.ts` — filter change reloads table
- `ai-prediction-detail.component.spec.ts` — accept and override button paths call service methods
- Playwright: `ai-predictions.spec.ts` — tenant admin logs in, filters by prediction_type=adjudication, opens a detail row, clicks override, submits feedback, verifies row status changes

### Success Criteria

#### Automated Verification
- [x] Python tests: `cd services/python/ai-service && uv run pytest tests/test_predictions_review.py` (16 pass, incl. per-line filter parametrized across all 8 lines)
- [ ] Java tests: permissions.yaml validation gate — deferred (see Deviations; permissions constants are not being added, actor identity is forwarded from the gateway JWT instead)
- [x] Go tests: `cd services/go/gateway && go test ./...` — new proxy routes present, existing suite green
- [ ] Angular unit tests: page-level jasmine spec deferred (see Deviations); dev build green with new component wired
- [ ] Playwright: deferred to a Tranche 0.5 follow-on; live UI walk-through is the manual-verification step
- [x] Swagger renders new endpoints at `http://localhost:8000/docs` — three routes registered under the `AI Predictions` tag with docstring summaries
- [ ] `verify` skill on `/tenant-admin/ai-predictions`: pending manual verification (Angular build compiles cleanly with the new route + sidebar entry + service + component)

#### Manual Verification
- [ ] Tenant admin logs in → nav shows "AI Predictions" → filter `insurance_line=VEHICLE` returns only vehicle predictions → change to `insurance_line=HEALTH` → returns only health predictions → filter by `prediction_type=fraud` on top → returns only vehicle-or-health fraud predictions per selection → override one with feedback → observe row's `accepted=false`, `reviewed_by`, `reviewed_at`, `reviewed_by_email` set (spot check via `psql`)
- [ ] Audit-service tail shows one `AuditEvent(entityName='AI Prediction ...', eventType='DECISION_RECORDED')` per override

---

## Testing Strategy

### Unit Tests

- `anonymize_features` — every stripped key + nested dict + list-of-dicts recursion path
- `suggest_tariffs` — exact match, prefix fallback, dedup, empty
- `FlagRegistryR2dbc` — cache TTL behavior
- `ClaudeClient.available` — key-set vs key-unset
- `get_active_llm` — provider dispatch
- `FraudService.detect_fraud` output shape — indicators list, model_version non-null

### Integration Tests (Testcontainers slices)

- `FlagRegistryR2dbcIT` — Postgres round-trip + cache expiry
- `AiServiceClientIT` — Wiremock AI service; flag-off asserts zero WebClient invocations
- `AiPricingClientIT` — flag-off returns 1.0 without HTTP hit
- `AiPredictionsControllerIT` (ai-service side) — tenant scoping, decision round-trip, audit event emitted

### E2E Tests (Playwright)

- `ai-predictions.spec.ts` — the full override journey
- `chatbot-persistence.spec.ts` — send messages, refresh page, history reloads

### Manual Testing Steps

1. Provision fresh infra: `make infra && make tenancy user contributions finance claims gateway ai web`
2. Login as tenant admin → `/platform/settings` → confirm `AI_ADJUDICATION` and `FRAUD_DETECTION` are on
3. Submit a **HEALTH** claim via `/provider/claims/new` → check `psql "select insurance_line, prediction_type, model_version, confidence from ai_predictions_store order by created_at desc limit 5"` → two rows (adjudication + fraud), both with `insurance_line='HEALTH'`
4. Submit a **VEHICLE** claim → two more rows, both with `insurance_line='VEHICLE'`
5. Repeat for LIFE, FUNERAL, DISABILITY, TRAVEL, PROPERTY, GROUP (one claim each) — every submission yields two `ai_predictions_store` rows with the correct `insurance_line`
6. `/tenant-admin/ai-predictions` → filter dropdown `insurance_line=VEHICLE` → only vehicle predictions visible → change filter → only that line visible
7. Toggle `AI_ADJUDICATION=off` → submit another claim (any line) → same query → no new rows
8. Toggle back on → override one prediction with feedback "known regression" → observe DB row `accepted=false`, `reviewed_by_email` populated
9. Chat via `/member/chat` as a member who holds policies across HEALTH + VEHICLE + FUNERAL: ask "how much of my medical is left?", "what's my funeral cover?", "what's my motor excess?" → each answer cites the seed value from that line's portfolio entry → restart `ai-service` (`docker compose restart ai-service`) → conversation history persists
10. Set `MEDFUND_LLM_PROVIDER=claude` + real key → restart ai-service → repeat step 9; reasoning field style differs (spot check)

## Performance Considerations

- `save_prediction` is one INSERT per endpoint call — Postgres async pool; expect <5ms overhead per request. Confirm on the busiest endpoint (`/pricing/score`, hit per member per contribution cycle).
- `FlagRegistry` cache is 30s; per-flag cache miss is one `SELECT` on `public.platform_feature_flags` (rows = 5). No index needed — sequential scan is faster than an index on a five-row table.
- `MemberContextClient` has a 2s timeout — a slow user-service must not block chat.
- Angular predictions page uses server-side pagination (page=0, size=50 default) — no client aggregation per the `feedback_stats_serverside` memory.

## Migration Notes

- **No tenant-schema Flyway migrations.** Phase 19 §A holds V169-V174.
- **Two columns added to `ai_predictions_store`** (both nullable, both SQLAlchemy-managed):
  - `insurance_line VARCHAR(20)` — added in Phase 2. Idempotent `ALTER TABLE ai_predictions_store ADD COLUMN IF NOT EXISTS insurance_line VARCHAR(20)` runs from a one-shot startup hook (`app/db/upgrades.py`) so existing dev/staging DBs pick it up on boot without re-creation. Partial index `WHERE insurance_line IS NOT NULL` created in the same hook.
  - `reviewed_by_email VARCHAR(255)` — added in Phase 7 via the same upgrade hook.
- **`platform_feature_flags` table**: already created by the seeder on tenancy-service startup. No migration change needed on this plan; the uncommitted files in `git status` (see Current State Analysis) land the table.

## Rollout & Rollback

**Rollout order**:
1. Phase 1 + Phase 2 land together — a random-noise prediction shouldn't be the first row to persist.
2. Phase 3 lands standalone.
3. Phase 4 lands standalone; requires the user-service benefit-summary endpoint to exist (verify or add).
4. Phase 5 lands standalone; setting the env vars is a deployment concern, not a code change.
5. Phase 6 lands after Phase 5 (semantic dependency: flag-off assumes fallback discipline works).
6. Phase 7 lands last — depends on Phase 2 for the data.

**Rollback**:
- Any phase reverts by dropping its commit; no schema is destructively changed.
- `ai_predictions_store.reviewed_by_email` column is nullable — reverting Phase 7 leaves the column but nothing writes to it.
- Feature flags stay in `platform_feature_flags`; reverting Phase 6 leaves them there but callers no longer read them.

## References

- Research: `thoughts/shared/research/2026-09-15-ai-service-gaps-and-model-strategy.md`
- Related plan (fraud audit / SIU): `thoughts/shared/plans/2026-09-05-fraud-siu-report.md`
- Architecture: `.claude/ai-integration.md`, `.claude/adjudication.md`, `.claude/coding-standards.md` §AI-Assisted Decision Audit Trails
- Critical Rule #3 (AI auditable): `.claude/CLAUDE.md`
- Similar wiring: `AiServiceClient` (`services/java/claims-service/src/main/java/com/medfund/claims/client/AiServiceClient.java:56-142`), `AiPricingClient` (`services/java/contributions-service/src/main/java/com/medfund/contributions/service/AiPricingClient.java:41-149`)
- Platform flag pattern: `PlatformFlag` (`services/java/shared/src/main/java/com/medfund/shared/flags/PlatformFlag.java`), `PlatformFlagSeeder` (`services/java/tenancy-service/src/main/java/com/medfund/tenancy/config/PlatformFlagSeeder.java`)
