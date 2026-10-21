---
date: 2026-09-15T08:28:01+02:00
researcher: Methuseli
git_commit: 2a80248e3c0de32c18c8d90b2c72c607aafffcae
branch: rename-adjustments-to-notes
repository: medfund
topic: "AI service gaps — what is stubbed, what the design promises, and how to close the gap so we can go to real-user/real-data testing"
tags: [research, codebase, ai-service, adjudication, fraud, ocr, chatbot, pricing, forecasting, ml, model-training]
status: complete
last_updated: 2026-09-15
last_updated_by: Methuseli
---

# Research: AI Service Gaps and Model Development Strategy

**Date**: 2026-09-15 08:28 +02:00 · **Researcher**: Methuseli · **Commit**: 2a80248e · **Branch**: rename-adjustments-to-notes

## Research Question

The UI and the main functional flow of InsureFlow are implemented; what remains is comprehensive real-user / real-data testing. The AI service (`services/python/ai-service`) is the outstanding gap — most endpoints are either stubbed, LLM-only with a "fallback" branch, or in-memory throwaway ML. What are the concrete gaps between the AI capabilities the design promises and what runs in code today, and how should we cover them (build vs LLM-relay vs classical ML vs deferred), including model development and training?

## Summary

The AI service is **partially real, partially stubbed, and partially "LLM-if-available-else-fallback"**. The gap is uneven — some capabilities are production-shaped (anomaly detection, forecasting, actuarial chain-ladder, rule-based pricing scorer), others are LLM-relay wrappers that silently degrade in tests (adjudication recommend, OCR structured extraction, chatbot), and one is a **live but orphaned** ML model (`FraudMLModel` exists but the `/fraud/check` endpoint uses a hardcoded formula plus `random.uniform(0, 0.1)` noise — see `services/python/ai-service/app/api/fraud.py:48-49`).

The design in `.claude/ai-integration.md` promises seven AI modules with a hybrid **Drools rules + classical ML (XGBoost / Isolation Forest / Prophet) + Claude reasoning** pipeline, human-in-the-loop, tenant-scoped configuration, and full auditability via an `ai_predictions` table. The code has the auditability spine (DB tables + Kafka topics + model_version fields), so the gap is mostly **models and their integration**, not infrastructure.

Two things matter for the "go-to-real-users" ambition:

1. **Nothing in the AI service can currently be validated against real data** because there is no persisted model, no training pipeline, no labelled dataset, and no drift monitoring. The `FraudMLModel` retrains on synthetic data at every process start (`app/services/ml_models.py:14-55`).
2. **Callers already treat the AI service as fail-open** — claims (`AiServiceClient.evaluate` fail-open on error, 8s timeout), contributions (`AiPricingClient.score` returns 1.0 on error), chat (Elixir `ai_proxy.ex` handles 5xx). This means you can move to real-user testing with AI stubs in place, but any decision-quality benefit will be near-zero until models are trained and wired.

The recommended shape of the work is **three tranches**: (a) unblock testing today by making the fallbacks explicit and non-random and by turning the LLM path on with a Gemini/Claude key, (b) train two classical models offline against seeded/real data — fraud (Isolation Forest + XGBoost) and pricing risk (XGBoost) — and persist them with versioned artifacts, (c) treat the remaining LLM-only capabilities (adjudication recommendation, OCR structured extraction, chatbot) as prompt-engineering + evaluation harness work, not model-training work.

## Findings

### The AI service today — endpoint-by-endpoint state

Full report from the exploration pass, condensed here. Every endpoint is registered in `services/python/ai-service/app/main.py:116-124`.

| Endpoint | State | Notes |
|---|---|---|
| `GET /health` (`app/api/health.py:1-9`) | **Real** | Trivial liveness. |
| `POST /api/v1/ai/adjudication/recommend` (`app/api/adjudication.py:54-72`) | **LLM-or-fallback** | Calls `AdjudicationService.analyze_claim`; Gemini path (adjudication_service.py:35-47) if `MEDFUND_GEMINI_API_KEY` set, else rule-based fallback (49-105) flagging HIGH_VALUE_CLAIM (>$10k) and MISSING_DIAGNOSIS. |
| `POST /api/v1/ai/adjudication/check-duplicate` (`app/api/adjudication.py:75-82`) | **Real** | Deterministic fuzzy matching — member/provider/date/amount + Jaccard on procedures. Solid enough as a first baseline. |
| `POST /api/v1/ai/adjudication/suggest-tariff` (`app/api/adjudication.py:85-102`) | **LLM-only, blank fallback** | If Gemini unavailable, returns `{"note": "AI unavailable: manual tariff code lookup required"}` and empty suggestions. No rule-based fallback exists. |
| `POST /api/v1/ai/fraud/check` (`app/api/fraud.py:28-63`) | **Stubbed** | Hardcoded base 0.1 + fixed deltas + `random.uniform(0, 0.1)` at `app/api/fraud.py:48-49`. The trained `FraudMLModel` (isolation-forest on synthetic data) exists in `app/services/ml_models.py:14-55` but the endpoint never calls it. |
| `POST /api/v1/ai/ocr/extract` (`app/api/ocr.py:23-44`) | **Real Tesseract + LLM fallback** | Tesseract does raw extraction; structured extraction goes to Gemini or a bare fallback. Confidence heuristic 0.8 / 0.4 / 0.0. |
| `POST /api/v1/ai/chat/message` (`app/api/chatbot.py:29-41`) | **LLM-or-keyword-fallback** | Gemini when available; else keyword patterns (chatbot_service.py:49-57) — e.g. "benefit balance…". Conversation history is **in-memory only** unless the persistence path is wired. |
| `POST /api/v1/ai/analytics/forecast` (`app/api/forecasting.py:19-25`) | **Real** | sklearn `LinearRegression`, fit-per-call. Good baseline; not Prophet-grade as the doc promises. |
| `POST /api/v1/ai/analytics/anomalies` (`app/api/analytics.py:24-30`) | **Real** | sklearn `IsolationForest(contamination=0.05)`. |
| `POST /api/v1/ai/analytics/provider-stats` (`app/api/analytics.py:33-57`) | **Real, no AI** | Pure aggregation. |
| `POST /api/v1/pricing/score` (`app/api/pricing.py:649-656`) | **Real, rule-based** | Full multi-line scorer (HEALTH, MOTOR, LIFE, PROPERTY, FUNERAL, TRAVEL, DISABILITY) at `app/api/pricing.py:184-643`. `model_version="rule-v1"`. Comment (lines 12-18) marks it as swap-in-ready for an ML/Gemini-backed scorer. |
| `GET /api/v1/actuarial/basis-tables/list` (`app/api/actuarial.py:24-41`) | **Real** | YAML-backed read-only catalogue. |
| Actuarial async compute (Kafka job path via `services/python/ai-service/app/report/*`) | **Real** | chainladder + IFRS 17 math; feeds `medfund.report.job-completed`. Not a "model" — deterministic math. |

**Zero persisted model artifacts.** No `.pkl`, `.onnx`, `.joblib`, `.pth` in the tree. `FraudMLModel` is retrained on synthetic data every process boot. This means today's AI service has nothing that could be "the model in production" — the ML that is real is either stateless (LinearRegression fit-per-call, IsolationForest fit-per-call, fuzzy matching, chain-ladder) or synthetic-trained-in-memory.

### External LLM wiring

- **Gemini** (`app/core/gemini_client.py:1-96`) — wired everywhere the design says "Claude" (adjudication, OCR, chatbot, tariff). Env var `MEDFUND_GEMINI_API_KEY`; default model `gemini-2.0-flash`. **Not set in `docker-compose.yml`** — must be injected at runtime.
- **Claude** (`app/core/anthropic_client.py:1-84`) — **prepared but not wired.** The client class exists with the same interface as `GeminiClient`; `MEDFUND_ANTHROPIC_API_KEY` is declared in `config.py:9` but never read anywhere except in the client constructor at instantiation. `.available` is always False today.

This is a drift from the design doc, which explicitly names Claude for adjudication and chatbot reasoning (`.claude/ai-integration.md:39-93, 173-200`). Whichever LLM we standardize on, only one is wired end-to-end right now.

### Persistence and audit — the good news

The audit spine promised by Critical Rule #3 is in place at the schema level:

- **`ai_predictions_store`** (`app/db/models.py:9-24`) — `tenant_id, entity_type, entity_id, prediction_type, model_version, input_features (JSON), output (JSON), confidence, accepted, reviewed_by, reviewed_at, created_at`. This matches the schema in `.claude/ai-integration.md:213-235` closely.
- **`conversation_messages`** (`app/db/models.py:27-35`) — chatbot history persistence exists as a table; endpoint uses in-memory list. Wiring gap.
- **Repository** (`app/repositories/prediction_repository.py:1-73`) — `save_prediction`, `record_human_decision`, `save_conversation_message`, `get_conversation_history`. Present.

**Gap:** the endpoints call the services and return, but I did not find calls into `save_prediction` from within the request-handling paths of `/adjudication/recommend`, `/fraud/check`, `/ocr/extract`, or `/pricing/score`. The write side of the audit spine exists but isn't invoked on every prediction. This is Critical Rule #3 territory — the second-highest-priority fix behind the fraud stub.

### Kafka event surface

Producer/consumer wiring already matches the design's event-driven claim.

- **Inbound**: `medfund.claims.submitted` — consumed by AI service (`app/core/kafka_consumer.py:58-59`). Fans out into adjudication + fraud in parallel (`:99-102`).
- **Outbound**: `medfund.claims.fraud-flagged` — produced by AI service (`app/core/kafka_producer.py:21`), consumed by claims-service `FraudFlaggedConsumer` (`services/java/claims-service/.../siu/consumer/FraudFlaggedConsumer.java:24,44`). One fraud_flag audit row per event. This is Phase 19 §A material — see the "Prior work" section.
- **Actuarial report jobs**: `medfund.report.job-requested` (Java → Python) and `medfund.report.job-completed` (Python → Java) — canonical Phase 15 topics; idempotent DB-trigger-guarded.

### Cross-service flow — where AI is expected to plug in

Traced from the "callers" pass; every reference below is a real live integration point.

- **claims-service → AI**: `AiServiceClient.evaluate` (`services/java/claims-service/src/main/java/com/medfund/claims/client/AiServiceClient.java:56-142`) makes two parallel HTTP calls per claim: `/adjudication/recommend` and `/fraud/check`, 8-second timeout, fail-open (`AiSignals.empty()` on any error). Sends `X-Tenant-ID` header.
- **contributions-service → AI**: `AiPricingClient.score` (`services/java/contributions-service/src/main/java/com/medfund/contributions/service/AiPricingClient.java:53-149`) calls `/api/v1/pricing/score` with a per-member feature vector pulled from `members.medical_history` JSONB. Fail-open to multiplier 1.0.
- **elixir chat_service → AI**: `AiProxy.get_ai_response` (`services/elixir/apps/chat_service/lib/chat_service/ai_proxy.ex:7-34`) → `/api/v1/ai/chat/message`, tenant + conversation context in body, `X-Tenant-ID` header.
- **Gateway → AI**: `services/go/gateway/internal/routes/routes.go:346-352` proxies `/api/v1/actuarial` and `/api/v1/actuarial/*`. AI service health check registered at `services/go/gateway/internal/platform/handler.go:166-167` under the "AI & Analytics" tile.
- **Angular pricing suggestion** (`clients/angular/src/app/core/services/pricing-suggestion.service.ts:32-44`) — comment says "ai-service will swap in without changing this contract". Currently relayed via user-service.
- **Angular admin tenant field `pricingModel: 'STANDARD' | 'INDIVIDUAL' | 'AI_DRIVEN'`** (`clients/angular/src/app/core/services/admin.service.ts:65,419`, `tenant.service.ts:53-56`) — the tenant-level toggle exists but only `STANDARD` is meaningfully wired end-to-end.
- **Flutter chat** — placeholder UI, no real AI call yet.

### Design doc vs code — where they agree, where they drift

Where they agree:
- Seven-module split (adjudication, fraud, OCR, chatbot, forecasting, pricing/analytics, actuarial) — endpoints exist for all of them.
- Audit table schema — `ai_predictions_store` (code) closely mirrors `ai_predictions` (doc, `.claude/ai-integration.md:213-235`).
- Kafka event contract — `medfund.claims.submitted` in, `medfund.claims.fraud-flagged` out, `medfund.report.job-*` for actuarial.
- Fail-open behavior — the doc says AI never rejects claims, only helps auto-approve or route to review; the callers already implement this.

Where they drift:
- **LLM vendor**: doc says Claude for adjudication and chatbot; code uses Gemini and has an unwired Claude client. Not a hard blocker, but the design intent and the wired code disagree.
- **Fraud detection**: doc says `Isolation Forest + XGBoost + Claude explanation` (`.claude/ai-integration.md:101-121`). Code has: (a) `FraudMLModel` (isolation-forest, synthetic-trained, in-memory, orphaned), (b) `/fraud/check` endpoint using hardcoded rule + `random.uniform`. Neither wired to Claude/Gemini.
- **Adjudication classifier**: doc names XGBoost as the ML layer (`.claude/ai-integration.md:65-72`). No XGBoost model exists in code — only the rule-based fallback + Gemini reasoning.
- **Forecasting**: doc names Prophet/statsmodels (`.claude/ai-integration.md:167-171`); code uses sklearn `LinearRegression`. Fine for a baseline, but less than what the doc suggests for cash flow / reserve prediction.
- **Chatbot memory**: doc implies persistent conversation history; code uses in-memory dict (`chatbot_service.py:13`) even though `conversation_messages` table exists.
- **Per-prediction audit writes**: doc mandates every prediction rows into `ai_predictions` (Critical Rule #3); code has the repository but not every endpoint calls it on the request path.
- **`pricingModel: 'AI_DRIVEN'` tenant toggle**: Angular exposes it, but there is no AI-driven pricing model — only the rule-based `rule-v1` scorer.

### The audit / regulatory implications (Critical Rule #3)

Critical Rule #3 in `.claude/CLAUDE.md`: "Every AI-assisted adjudication, fraud flag, or billing suggestion must log the model version, input features, confidence score, and output — with a human-reviewable trail."

Concrete state:

- `model_version` field: **present in every response schema and stored on `ai_predictions_store`** — good.
- Input features JSON: **modelled in the table**, but I did not find write-side calls from the request handlers.
- Confidence: **modelled and returned**, but derived from a heuristic in most endpoints today.
- Human-reviewable trail: `human_decision`, `reviewed_by`, `reviewed_at` columns exist; the UI to review/override is not obviously wired in Angular (the tenant admin has a `pricingModel` field but nothing surfaced for reviewing individual predictions).

If we're going to real-user testing with AI in the loop, the audit-write must happen on every prediction (adjudication, fraud, OCR, pricing, chatbot) before we ship. This is a wiring gap, not a modelling gap.

## Where the gaps actually are — a classification

The word "AI gap" collapses three different kinds of work. Splitting them helps the plan:

**Gap type A — "The model is missing."** Fraud detection needs a real trained classifier. Adjudication risk-scoring needs an XGBoost model. Pricing needs (optionally) an ML-driven scorer. Provider risk / churn scoring, if we want them, need models. These need training data, feature engineering, model training pipelines, versioned artifacts, and inference-time loading.

**Gap type B — "The model exists (as a prompt), but nothing is validated."** LLM-backed capabilities — adjudication recommendation reasoning, OCR structured extraction, tariff suggestion, chatbot. Here there is no "train a model" step. The work is: turn on the API key, harden the prompt, build an eval harness on labelled examples, tighten the fallback behavior, and wire audit writes. This is prompt-engineering + eval work, not ML.

**Gap type C — "The pipe is not connected."** Every endpoint that has a real implementation but doesn't call `save_prediction`, doesn't emit audit events, or has a UI toggle (`pricingModel: 'AI_DRIVEN'`) with no backend. Also: chatbot persistence, tariff-suggestion fallback, Claude client wiring, per-tenant threshold config. Cheap, non-model work; blocks compliance more than it blocks utility.

Recognizing these three gap types is more useful than a single "AI is stubbed" statement — they need different specialists and different timelines.

## What the design promises we still need to build

Distilled from `.claude/ai-integration.md`, `.claude/adjudication.md`, `.claude/rules-engine.md`, `.claude/coding-standards.md`. See the deep-read pass for full citations.

1. **Claims auto-adjudication pipeline** (`.claude/ai-integration.md:39-99`) — Drools rules + XGBoost risk classifier + Claude reasoning + per-tenant thresholds. XGBoost model does not exist; Claude client not wired; per-tenant thresholds not exposed.
2. **Fraud detection** (`.claude/ai-integration.md:101-121`) — Isolation Forest + XGBoost + Claude explanation. Isolation Forest exists (`FraudMLModel`) but is synthetic-trained and orphaned; XGBoost missing; Claude explanation missing.
3. **Document OCR + structured extraction** (`.claude/ai-integration.md:123-148`) — Tesseract + Claude Vision. Tesseract wired; vision-model structured extraction is Gemini text-only today (no image → structured JSON via vision API).
4. **Member chatbot** (`.claude/ai-integration.md:173-200`) — Claude + member-context retrieval. Gemini-only, in-memory history, no member-context lookup wired (no read-only queries to member/claim/benefit tables).
5. **Billing optimization / pricing** (`.claude/ai-integration.md:152-160`) — scheme recommendation, pricing anomalies, default prediction. Rule-based scorer covers pricing baseline; scheme recommendation and default prediction models do not exist.
6. **Financial forecasting** (`.claude/ai-integration.md:162-171`) — Prophet/statsmodels for claim volume, cash flow, reserve adequacy. Only sklearn `LinearRegression` today.
7. **Provider intelligence** (`.claude/ai-integration.md:202-209`) — approval rates, rejection reasons, tariff suggestions, pre-auth predictor, payment forecast, peer benchmarking. `/analytics/provider-stats` covers the deterministic aggregation slice; the predictive slices don't exist.
8. **Per-tenant AI config** (`.claude/ai-integration.md:95-99`) — auto-approve threshold, auto-flag threshold, enable/disable, custom rule weights. Not exposed in tenancy-service or the Angular admin portal (`pricingModel` enum is the closest thing, and it's not wired to thresholds).
9. **Feedback loop / model retraining** (`.claude/ai-integration.md:93-94, 243-244`) — clerk-overrides recorded, models retrained periodically per tenant or globally-with-tenant-as-feature. The DB columns exist (`human_decision`, `human_feedback`, `reviewed_by`, `reviewed_at`); no retraining pipeline exists.
10. **Anonymization + tenant opt-in for cross-tenant training** — no implementation, no interface.
11. **Drift monitoring** — not mentioned in code at all.

## Model development strategy — a working recommendation

Auto mode, so committing to a reasonable shape rather than asking. Redirect if any of this is wrong.

**Tranche 0 — "make real-user testing possible with AI as-is" (days, not weeks).** Do this now, no models needed:
- Wire `save_prediction` into every endpoint request path. Non-negotiable for Critical Rule #3.
- Kill `random.uniform(0, 0.1)` in `/fraud/check` (`app/api/fraud.py:48-49`); replace with a deterministic rule-only scorer that at least calls the existing (orphaned) `FraudMLModel` for the anomaly signal. It's synthetic-trained, but it beats random and gives the same shape for later replacement.
- Add a rule-based tariff-suggestion fallback so `/adjudication/suggest-tariff` doesn't return blank when Gemini is off.
- Wire chatbot persistence to `conversation_messages`.
- Set `MEDFUND_GEMINI_API_KEY` (or `MEDFUND_ANTHROPIC_API_KEY` + finish the Claude wiring) in the dev/staging compose files, gated behind a per-tenant enable flag.
- Expose the three per-tenant thresholds (auto-approve, auto-flag, enable) in tenancy-service + Angular admin. Wire claims-service to read them.
- Fill in the Claude client wiring so we have both LLMs available and can switch or A/B on the audit trail.

**Tranche 1 — "train the two models that actually matter" (weeks).**
- **Fraud classifier**: build a real Isolation Forest + XGBoost stack against historical claims. Because there is no real historical labelled data yet, seed with rule-generated labels + hand-labelled examples during pilot testing; feed clerk overrides via the existing `human_decision` column as ground truth. Persist artifacts (`fraud_if_v1.joblib`, `fraud_xgb_v1.joblib`) under `services/python/ai-service/app/artifacts/` and load at startup. Store `model_version = "fraud_if_v1+xgb_v1"` on every prediction. Wire the trained model into `/fraud/check`.
- **Pricing risk classifier**: XGBoost against historical claim experience per member/policy. Same shape — offline notebook trains, versioned artifact loaded at inference. Backfill via Phase 12 UPR + Phase 14 actuarial history if that data is present.
- Add a **training runbook** — one script, `scripts/ai/train_fraud.py`, one `scripts/ai/train_pricing.py`, reproducible with `uv run` and dataset paths as args. Not a full MLOps pipeline; just a make-it-repeatable-across-releases story.

**Tranche 2 — "LLM capabilities become production-shaped" (weeks, parallel to Tranche 1).**
- Build a golden-set evaluation harness — 100-500 labelled examples per capability (adjudication reasoning, OCR extraction, tariff suggestion, chatbot). Store under `services/python/ai-service/eval/`. Report precision/recall/agreement on every model-version change.
- Choose between Gemini and Claude on eval performance + cost. Fold the loser out to a backup.
- Add Claude Vision (or Gemini's vision model) to OCR so structured extraction is grounded on the image, not on Tesseract-only text.
- Wire member-context retrieval into the chatbot (read-only projections of member/claim/benefit data).

**Tranche 3 — deferred (months, or never depending on scale).**
- Prophet/statsmodels forecasting.
- Provider risk / pre-auth prediction / churn prediction models — need real labelled data at scale; defer until pilot has produced enough.
- Cross-tenant training and drift monitoring — needs the feedback loop humming first.

**Data strategy.** No real training data exists in-repo today. Realistically the strategy is:

1. Seed data (synthetic + generated) for the initial artifact so the pipeline works end-to-end.
2. During real-user pilot, capture every clerk override into `ai_predictions_store.human_decision` — this becomes the primary ground truth.
3. Retrain monthly (per-tenant if the tenant has enough volume, otherwise global with tenant-as-feature). One `.joblib` per model per version; keep the last three so rollback is cheap.
4. Anonymization: strip member_id/provider_id from feature vectors before training; keep numeric/categorical shape only. Per `.claude/ai-integration.md:243` the default is data-isolation with opt-in cross-tenant training.

## Code References

- `services/python/ai-service/app/main.py:27-125` — service bootstrap, routers, Kafka/DB init
- `services/python/ai-service/app/api/fraud.py:28-63` — the primary stub (hardcoded + `random.uniform`)
- `services/python/ai-service/app/services/ml_models.py:14-55` — orphaned `FraudMLModel`
- `services/python/ai-service/app/api/adjudication.py:54-102` — LLM-or-fallback endpoints
- `services/python/ai-service/app/api/pricing.py:12-18,124-657` — rule-based multi-line scorer + swap-in hook
- `services/python/ai-service/app/api/chatbot.py:29-41`, `services/python/ai-service/app/services/chatbot_service.py:13,31-57` — in-memory chat, Gemini-with-fallback
- `services/python/ai-service/app/core/gemini_client.py:1-96` — wired LLM client
- `services/python/ai-service/app/core/anthropic_client.py:1-84` — prepared but unwired
- `services/python/ai-service/app/db/models.py:9-35`, `app/repositories/prediction_repository.py:1-73` — audit spine
- `services/python/ai-service/app/core/kafka_consumer.py:58-102`, `app/core/kafka_producer.py:21-54` — event I/O
- `services/java/claims-service/src/main/java/com/medfund/claims/client/AiServiceClient.java:43-142` — Java caller, fail-open, 8s timeout
- `services/java/claims-service/src/main/java/com/medfund/claims/siu/consumer/FraudFlaggedConsumer.java:24-44` — fraud-flag consumer
- `services/java/contributions-service/src/main/java/com/medfund/contributions/service/AiPricingClient.java:41-149` — pricing caller
- `services/elixir/apps/chat_service/lib/chat_service/ai_proxy.ex:5-34` — chatbot caller
- `services/go/gateway/internal/routes/routes.go:346-352` — actuarial proxy
- `services/go/gateway/internal/platform/handler.go:166-167` — AI health tile
- `clients/angular/src/app/core/services/admin.service.ts:65,419`, `tenant.service.ts:53-56` — `pricingModel: 'STANDARD' | 'INDIVIDUAL' | 'AI_DRIVEN'` tenant toggle
- `clients/angular/src/app/core/services/pricing-suggestion.service.ts:32-44` — pricing swap-in-shaped contract
- `clients/angular/src/app/pages/tenant-admin/settings/actuarial-bases/mortality-basis-tab.component.ts:28-30` — live query into ai-service actuarial catalogue
- `.claude/ai-integration.md:5-244` — full AI design intent
- `.claude/adjudication.md:74-241` — six-stage pipeline, decision matrix
- `.claude/coding-standards.md:699-709` — AI-assisted decision audit trail contract
- `.claude/CLAUDE.md` — Critical Rule #3 (AI auditability), Critical Rule #4 (data protection)

## Architecture Insights

- **Fail-open discipline is already codified in every caller.** Claims-service (`AiServiceClient.evaluate` — 8s timeout, `AiSignals.empty()` on error), contributions (`AiPricingClient.score` — 1.0 fallback), Elixir chat (5xx error handling). This means we can iterate on AI capability without any risk to the transactional path — the platform continues to function without AI. Preserve this — do not tighten timeouts or make callers block on AI.
- **The audit spine (`ai_predictions_store`) is present but under-used.** This is a wiring problem, not a design problem. Critical Rule #3 exposure is high until every endpoint writes on every prediction.
- **LLM vendor drift.** Design says Claude; code says Gemini; both clients exist. Pick one primary + one backup and finish the wiring for both. Don't leave the Claude client in a "declared but unread env var" state (config.py:9).
- **`FraudMLModel` is a classical case of "orphaned production code".** It exists, is training-on-boot on synthetic data, and no endpoint calls it. Fixing this is the cheapest single win in the AI service — swap the endpoint body for a model.predict call, keep the rule-based indicators as annotations, wire audit.
- **Tenant-scoping**: `AiServiceClient` sends `X-Tenant-ID` header, `AiPricingClient` sends tenant_id in the body, `AiProxy` sends the header. AI service reads it via `app/core/tenant.py:8-12`. This is consistent; keep it as the single source of tenant identity into AI.
- **The rules-engine / AI split is clean in the docs and mostly clean in code.** Deterministic decisions (eligibility, waiting period, benefit limits, tariff modifiers) live in Drools + `RuleCategory`; probabilistic decisions (fraud, adjudication recommendation, tariff suggestion) live in the AI service. New Phase 19 §A introduces `FRAUD_TRIAGE` as a rules-engine category that operates on AI-produced fraud flags — this is the correct pattern (rules on top of AI outputs, not either-or).
- **Actuarial ≠ AI.** Chain-ladder, mortality, IFRS 17 are deterministic math. They live in `services/python/ai-service` only because the ML/DS Python stack is convenient. Don't confuse "training models" with "actuarial compute" — actuarial has no gaps.

## Historical Context (from thoughts/shared/)

- `thoughts/shared/plans/2026-09-05-fraud-siu-report.md` (Phase 19 §A/§B, Draft, ready) — the immediate follow-on for fraud. Adds `medfund.claims.fraud-flagged` producer wiring, persistence of every fraud prediction as an immutable `fraud_flag` row, SIU workflow (3-state MVP), tenant-configurable `FRAUD_TRIAGE` rules-engine category, `/tenant/finance/reports/fraud/` report. **Any real fraud model work should ride Phase 19's rails, not fork new ones.** V169-V171 migrations reserved.
- `thoughts/shared/notes/2026-09-05-phase19-fraud-siu-grill.md` — grilling scratchpad, 16 firm decisions (FR1-FR16), 7 fact-checked findings. Confirms fraud detection is live-but-orphaned and Kafka producer is the missing link.
- `thoughts/shared/plans/2026-08-25-actuarial-module.md` (Phase 14, Landed) — 16-phase actuarial module already implemented. Establishes the async Kafka job pattern the AI service uses today.
- `thoughts/shared/plans/2026-08-11-financial-reporting-suite.md` — parent plan, ~60 reports across 4 buckets, 19 phases.
- `thoughts/shared/plans/2026-08-30-regulatory-format-reports.md` (Phase 16, Draft) — adds `RuleCategory.PMB_CLASSIFICATION` (writes `claim.is_pmb`/`claim.pmb_condition_code` at adjudication). Same pattern: deterministic classification on top of AI signals, not instead of.
- `thoughts/shared/plans/2026-08-31-scheduled-email-delivery.md` (Phase 17) and `thoughts/shared/plans/2026-09-05-executive-kpi-dashboards.md` (Phase 18) — adjacent, not directly AI-related.

**No prior research doc exists on AI service gaps or model training strategy.** This is the first.

## Related Research

- `thoughts/shared/research/2026-08-11-financial-reporting-vs-masca-reference.md` — reporting architecture; useful for framing AI-derived report tiles like fraud analytics.

## Open Questions

1. **LLM vendor decision** — Gemini (already wired) or Claude (design intent)? Or both, with A/B routing on the audit trail?
2. **Real training data availability** — is there a labelled historical dataset from any prior deployment, or is the pilot the primary source of ground truth?
3. **Per-tenant vs global models** — is enough per-tenant volume expected during pilot to justify per-tenant training, or start with global + tenant-as-feature?
4. **Human-review UI** — the `ai_predictions_store.human_decision` + `reviewed_by` columns imply a review UI. Does the Angular admin have (or need) an "AI Predictions" review page, and if so, is that part of Phase 19 §A or a separate stream?
5. **Chatbot member-context scope** — read-only queries into member/claim/benefit tables. Which service owns this projection — user-service, chat_service, or a dedicated AI-context service? Design doc doesn't nail it.
6. **`pricingModel: 'AI_DRIVEN'` tenant flag** — what is its actual semantics if there's no AI pricing model? Deprecate, or ship an ML pricer to justify it?
7. **Anonymization enforcement** — where is the guardrail that prevents PII from leaking into a training feature vector? Design says "anonymized" but no code enforces it.
8. **Drift monitoring** — deferred to Tranche 3, but do we need a lightweight metric-tracking loop from day one (prediction-vs-override rate per model_version) to know when to retrain?
