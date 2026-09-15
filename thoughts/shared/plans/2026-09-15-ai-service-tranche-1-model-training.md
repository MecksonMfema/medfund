---
date: 2026-09-15
git_commit: 357e2eee
branch: rename-adjustments-to-notes
ticket: null
research:
  - thoughts/shared/research/2026-09-15-ai-service-gaps-and-model-strategy.md
related_plans:
  - thoughts/shared/plans/2026-09-15-ai-service-pilot-readiness.md
steer: "train real fraud + pricing models per InsuranceLine using the labeled corpus that Phase 7 review page accumulates; artifacts on disk, versioned, load-at-startup; every line covered from the first commit; keep the synthetic-training fallback so dev environments always boot"
services_touched: [ai-service, tenancy-service, claims-service, contributions-service, angular]
status: grilled (2026-09-15) — decisions G1–G7 applied, ready for create-plan-style refinement
tranche: "1 — real model training (fraud per line, artifact registry, eval harness, retraining cadence; pricing training deferred to Tranche 2 per G3)"
lines_covered: [HEALTH, LIFE, FUNERAL, GROUP, TRAVEL, DISABILITY, VEHICLE, PROPERTY]
---

# AI Service Tranche 1 — Model Training Plan

## Grilling Decisions (2026-09-15)

Applied to the plan below via strikethroughs (`~~old~~ new`) so the trail of what changed is preserved.

| ID | Question | Decision |
|---|---|---|
| **G1** | Label strategy | Random-sample review queue as Tranche 1 pre-work (Phase 0). Training assumes it exists. |
| **G2** | Queue ratio | 50/50 HIGH/LOW; add a **calibration step** (Platt / isotonic) to every trained artifact. |
| **G3** | Pricing scope | **Defer training**; Phase 4 becomes plumbing only. Full pricing training in Tranche 2 with loss-ratio labels. |
| **G4** | Training scope | Cross-tenant; formal **anonymization audit** gate (Phase 0.5) before any artifact promotes. |
| **G5** | Promotion gating | Full UI-driven promote + `ai:models:promote` permission + confirmation modal (Phase 5 gains a write path). |
| **G6** | Reload propagation | **MinIO manifest + 60s pod poll**; artifacts land in `s3://medfund-ml-artifacts/`, not local disk. Softens the "reload-on-startup only" invariant. |
| **G7** | Feature evolution | Schema-versioned artifacts; loader hard-rejects mismatches, surfaces `SCHEMA_MISMATCH` on the admin page. |

Parked as a separate follow-up (Tranche 0 defect, not fixed here):
- `services/python/ai-service/app/api/pricing.py:141-152` keys on `"MOTOR"` but Java sends `"VEHICLE"` — every VEHICLE pricing call silently degrades to `multiplier=1.0`. Because G3 defers pricing training, this doesn't block Tranche 1, but must be fixed alongside pilot ops.

## Overview

Move the AI service from synthetic-at-boot fraud scoring ~~+ rule-based pricing~~ to **real trained fraud models per InsuranceLine**, using the labeled corpus that Tranche 0's `/tenant/admin/ai-predictions` review page ~~has been accumulating since pilot start~~ **will accumulate once Phase 0's stratified review queue lands (per G1)**. Every line is trainable from the first commit; a per-line data-sufficiency gate (default: 200 labeled fraud predictions per line, ~~500 labeled pricing predictions per line~~) decides whether the artifact for that line comes from a real dataset or falls back to the canonical synthetic model. Dev environments always boot green — a missing artifact never crashes the service.

~~Six~~ **Eight** phases: **(0) stratified review-queue backend + Angular queue UI (per G1); (0.5) formal anonymization audit sign-off (per G4);** (1) audit-corpus reader + eval harness ~~with schema-version handling~~; (2) fraud training pipeline + artifact registry **(MinIO-backed per G6, calibrator per G2, schema-version tag per G7)**; (3) load-at-startup with fallback **plus 60s manifest poll for cross-pod propagation (per G6)**; (4) ~~pricing training pipeline (per-line regressions)~~ **pricing plumbing only — resolver + registry slots + admin rows; training deferred to Tranche 2 (per G3)**; (5) model-registry Angular admin surface **with UI promote button + `ai:models:promote` permission (per G5) + `SCHEMA_MISMATCH` status column (per G7)**; (6) retraining cadence via a scheduled ArgoCD/CronWorkflow job.

This plan **does not** train ~~cross-tenant~~ **per-tenant** models ~~(Tranche 3)~~ **(cross-tenant is the default per G4; per-tenant opt-out stays Tranche 3)**, does **not** replace the Gemini/Claude LLM path (still governs the LLM endpoints), and does **not** add new tenant Flyway migrations. Model artifacts live ~~outside the tenant DBs (S3 + local disk in dev)~~ **in MinIO in both dev and prod (per G6) — no more local disk symlinks**.

## Current State Analysis

Facts verified against the just-shipped Tranche 0 bundle (commit `357e2eee`):

- **`FraudMLModel` is synthetic-only** — `services/python/ai-service/app/services/ml_models.py:22-62`. Every `__init__` regenerates 1000 random samples via `numpy.random.RandomState(42)` and fits IsolationForest against them. Model version stays `fraud-isolation-forest-v1-canonical` regardless of environment. No `joblib.load`, no artifact path, no `--load` flag.
- **Pricing scorer is rule-based** — `services/python/ai-service/app/api/pricing.py:129-165`. Per-line functions apply BigDecimal-style multipliers (health = age/BMI/chronic; motor = odometer/coverage_type; property = peril; etc.), clamp to `[0.5, 3.0]`, return a `model_version="rule-v1"`. No trained regressor.
- **Audit corpus exists** — `ai_predictions_store` now carries `tenant_id`, `insurance_line`, `entity_type`, `entity_id`, `prediction_type`, `model_version`, `input_features` (anonymized JSON), `output`, `confidence`, `accepted`, `reviewed_by`, `reviewed_by_email`, `reviewed_at`. Tenant admins mark accept/override via `PUT /api/v1/ai/predictions/{id}/decision` (`services/python/ai-service/app/api/predictions.py:150-192`). Feedback text is stored inside `output._review_feedback` — retrievable via the same table.
- **`line_features` bag persisted per row** — the fraud endpoint stores the per-line normalized features in `output.line_features` (`services/python/ai-service/app/api/fraud.py:57`), so a HEALTH training row has `{diagnosis_codes, procedure_codes}` alongside the canonical vector; VEHICLE has `{damage_type, part_codes, cause_of_loss, odometer_km}`. Per-line model training can consume these without a schema change.
- **No `scripts/` directory in ai-service** — `ls services/python/ai-service/scripts` returns "No such file or directory". Training scripts will land in a new `scripts/` subtree.
- **No artifact registry** — nothing under `app/artifacts/`, no S3 client configured for model I/O (the existing MinIO integration is for Kafka payload overflow, not model registry).
- **`FraudService.check_fraud_request` is model-agnostic** — `services/python/ai-service/app/services/fraud_service.py`. It calls `self._ml.predict_canonical(canonical)` and returns whatever the model gives back. Swapping in a trained model is a one-line constructor change; no endpoint or DTO changes required.
- **`resolve_llm()` pattern is the template** — Phase 5 (Tranche 0) introduced runtime provider swap via a module-attribute lookup (`services/python/ai-service/app/core/llm_dispatch.py`). The same shape works for model artifacts: `resolve_fraud_model(line)` returns the trained artifact if `MEDFUND_MODEL_ARTIFACTS_ENABLED=true` and one exists on disk, otherwise falls back to `FraudMLModel(seed=42)`.

## Desired End State

After this plan lands:

- **Fraud model per line** — ~~`app/artifacts/fraud-{line}-v{n}.joblib` on disk (or `s3://medfund-ml-artifacts/fraud-{line}-v{n}.joblib` in prod)~~ **`s3://medfund-ml-artifacts/fraud-{line}-v{n}.joblib` in MinIO for dev + prod (per G6)**. One artifact per InsuranceLine; the shared canonical model stays as a fallback for lines that haven't accumulated enough labeled data.
- ~~**Pricing model per line** — `app/artifacts/pricing-{line}-v{n}.joblib`. Per-line regression (sklearn `Ridge` or `GradientBoostingRegressor`) trained on member-attribute → premium-outcome pairs from the pricing audit rows joined against the corresponding contribution's actual loss experience.~~ **Per G3 — no trained pricing artifact ships in Tranche 1. `resolve_pricing_model(line)` returns the rule-based scorer for every line; the MinIO path is reserved but empty.**
- **Training scripts** — `services/python/ai-service/scripts/train_fraud.py --line HEALTH --output-version 2` ~~and `train_pricing.py --line VEHICLE --output-version 3`~~ **(pricing trainer deferred to Tranche 2 per G3)**. Each: reads the audit corpus for the specified line, splits train/val, **fits classifier + calibrator (per G2)**, evaluates, dumps a joblib **to MinIO (per G6) tagged with `canonical_features_schema` (per G7)**, prints a metrics summary. Deterministic (fixed seed).
- **Eval harness** — before promoting a new version to `latest`, `evaluate_model.py --line HEALTH --candidate v2` compares held-out precision/recall against the current `latest`. Regressions block the promotion; the artifact stays labeled as `-candidate-vN` until a human OKs it via the new admin surface.
- **Load-at-startup with fallback** — `FraudMLModel(line=InsuranceLine.HEALTH)` first tries `joblib.load(path_for("fraud", line, "latest"))`, then falls back to synthetic; logs which path won. Same for pricing.
- **Model version threaded through audit** — the prediction row's `model_version` becomes `fraud-{line}-{semver}` (e.g. `fraud-health-v2.1.0`) so a query like "which claims did fraud-health-v2.1.0 flag?" is a simple `SELECT`.
- **Admin surface** — tenant admins see the currently-active model version per line at `/tenant/admin/ai-predictions/models` with eval metrics (train date, sample count, precision/recall on held-out set). No promote/rollback in the UI in this tranche (CLI-only).
- **Retraining cadence** — a scheduled Kubernetes CronJob runs `train_fraud.py --line {line}` weekly for each line with sufficient data, drops a `-candidate` artifact, and posts to Slack for human review. Zero automatic promotions.

### Verification

```bash
# Data-sufficiency check
cd services/python/ai-service && uv run python scripts/data_sufficiency.py --line HEALTH
# Expected: "HEALTH: 1247 labeled fraud rows, 890 labeled pricing rows — trainable"

# Train + eval
uv run python scripts/train_fraud.py --line HEALTH --output-version 2
uv run python scripts/evaluate_model.py --line HEALTH --candidate v2
# Expected: candidate precision/recall reported; no promotion yet

# Promote (per G5 — UI-driven; CLI kept as an operator escape hatch)
# Preferred: click Promote v2 on /tenant/admin/ai-predictions/models
# CLI equivalent:
uv run python scripts/promote_model.py --line HEALTH --version v2 --actor-email you@example.com
# Expected: MinIO manifest.json now maps ("fraud", "HEALTH") -> "v2"; pods pick up on next 60s poll (per G6)

# Restart ai-service; observe model-version in the next prediction
make ai
curl -X POST http://localhost:8000/api/v1/ai/fraud/check -H "X-Tenant-ID: ..." \
  -H "Content-Type: application/json" \
  -d '{"claim_id": "...", "insurance_line": "HEALTH", ...}'
# Expected: model_version: "fraud-health-v2"

# Admin surface
open http://localhost:5100/tenant/admin/ai-predictions/models
# Expected: table with 8 rows (one per InsuranceLine), each showing active version + train date + eval metrics + sample count
```

### Key Discoveries

- **`ai_predictions_store` is the corpus, `_review_feedback` is the label** — every "override" row is a negative label for the model. Every "accept" is a positive. Pending rows are unlabeled and stay out of training.
- **Per-line `line_features` bag is the input-feature source of truth** — training does not re-derive features from the underlying claim / member row; it pulls them straight out of the audit JSONB. Reproducibility is built-in.
- **The canonical 5-tuple is already stored in `output.canonical_features`** — the fraud endpoint persists the vector it actually scored, so the training script joins `input_features` (line-specific) + `output.canonical_features` (shared) + `accepted` (label) with no extra derivation.
- **No new tenant Flyway migrations required** — labels are audit-service-owned. The AI service reads its own `ai_predictions_store` (SQLAlchemy).
- **`sklearn.ensemble.IsolationForest` is not the only choice** — the current shape uses IsolationForest because it's unsupervised (fraud training data used to be unlabeled). With labels now available, `RandomForestClassifier` or `xgboost.XGBClassifier` is the more natural fit. IsolationForest stays for the fallback / cold-start case.
- **Pricing regression labels are indirect** — pilots don't directly label a pricing multiplier as "right" or "wrong". The label comes from the eventual **loss ratio** (claims paid / premiums collected) per member per line over a 6-month window. That's a slower feedback loop than fraud, and needs a join into `contributions` + `claims` tables in the tenancy DB. Tranche 1 approximates with "reviewer override" as a weak label; Tranche 2 wires the loss-ratio join.
- **`resolve_fraud_model()` mirrors `resolve_llm()`** — the module lookup pattern from Phase 5 works for artifacts too. Endpoints don't hardcode a model instance; they resolve at request time so a promotion takes effect on the next request (no service restart needed if we go artifact-hot-reload; Tranche 1 stops at reload-on-startup to keep it simple).

## What We're NOT Doing

Deferred to follow-on plans:

- ~~**Cross-tenant training** — models stay per-tenant-agnostic (trained on the whole `ai_predictions_store` corpus across tenants). Per-tenant opt-in with differential privacy is Tranche 3.~~ **Superseded by G4: cross-tenant IS the default this tranche, gated by a formal anonymization audit. Per-tenant opt-out with differential privacy stays Tranche 3.**
- ~~**Model hot-reload without restart** — reload-on-startup keeps deployment simple. Tranche 2 adds `SIGHUP` or a Kafka topic for hot swap.~~ **Softened by G6: we now do reload via 60s manifest poll during process lifetime. What stays Tranche 2 is push-based / event-driven reload (SIGHUP or Kafka signal) — the polling loop is the "warm restart" version, good enough for pilot promotion latency.**
- ~~**UI-driven promotion** — admin surface is read-only in this tranche. Promotion stays a CLI action for gatekeeping.~~ **Superseded by G5: UI-driven promotion is IN this tranche, behind a new `ai:models:promote` permission + confirmation modal.**
- **MLflow / Weights-and-Biases** — a full experiment-tracking rig is overkill for the pilot volume. The metrics-log-in-joblib pattern is enough.
- ~~**Real loss-ratio labels for pricing** — Tranche 1 uses reviewer overrides as a proxy. True loss-ratio labels (contribution income vs. claims paid over a 6-month window) are Tranche 2.~~ **Hardened by G3: Tranche 1 does not train pricing at all. Loss-ratio labels + pricing training scripts are Tranche 2. Phase 4 in this tranche is registry / resolver / admin plumbing so the drop-in is ready.**
- **Claude Vision for OCR** — still Tesseract + LLM structured extraction; no vision model training here.
- **Drift monitoring** — a scheduled comparison of "current week's score distribution vs. last month's" is Tranche 3. This tranche just gets the artifact pipeline in place.
- **`claims:*` / `pricing:*` labels via the review page for lines other than HEALTH** — the review page already accepts overrides for every line, but the ML-model training scripts only ship for HEALTH + VEHICLE + PROPERTY in this tranche (the three highest-volume lines in the pilot). LIFE / FUNERAL / DISABILITY / TRAVEL / GROUP fall through to the canonical synthetic model until Tranche 2 adds their per-line training runs — the artifact framework is line-generic, so it's a data-availability limit, not a code limit.

## Insurance-Line Coverage

The **artifact framework** and **load-at-startup path** cover every InsuranceLine value from the first commit — no schema change when the remaining lines' training data accumulates. What differs per line is whether a real trained artifact ships in this tranche:

| Phase | HEALTH | LIFE | FUNERAL | GROUP | TRAVEL | DISABILITY | VEHICLE | PROPERTY |
|---|---|---|---|---|---|---|---|---|
| **1 Corpus reader** | reads HEALTH labels | reads LIFE labels | reads FUNERAL labels | reads GROUP labels via `underlying_line` | reads TRAVEL labels | reads DISABILITY labels | reads VEHICLE labels | reads PROPERTY labels |
| **2 Fraud training** | trained artifact ships (RandomForestClassifier) | canonical synthetic (data-sufficiency < 200) | canonical synthetic | routed to underlying line's model | canonical synthetic | canonical synthetic | trained artifact ships | trained artifact ships |
| **3 Load-at-startup** | resolves fraud-health-v1 | resolves canonical fallback | resolves canonical fallback | dispatches via underlying_line | resolves canonical fallback | resolves canonical fallback | resolves fraud-vehicle-v1 | resolves fraud-property-v1 |
| **4 Pricing plumbing (per G3)** | rule-v1 via resolver | rule-v1 | rule-v1 | rule-v1 (dispatch note) | rule-v1 | rule-v1 | rule-v1 (blocked also by parked MOTOR/VEHICLE bug) | rule-v1 |
| **5 Admin UI** | fraud-health-v1 active + metrics; pricing rule-v1 | "canonical fallback (insufficient data)"; pricing rule-v1 | "canonical fallback"; pricing rule-v1 | dispatch note; pricing rule-v1 | "canonical fallback"; pricing rule-v1 | "canonical fallback"; pricing rule-v1 | fraud-vehicle-v1 active + metrics; pricing rule-v1 | fraud-property-v1 active + metrics; pricing rule-v1 |
| **6 CronJob** | weekly **fraud** retrain | weekly data-sufficiency check only | weekly check | weekly check per underlying | weekly check | weekly check | weekly **fraud** retrain | weekly **fraud** retrain |

Cross-cutting invariants:

- **Every InsuranceLine value has a registered `resolve_fraud_model(line)` and `resolve_pricing_model(line)`** — a missing registration is a boot-time crash (a la `test_every_line_has_registered_suggester` in Tranche 0).
- **Every artifact path follows `{model_type}-{line}-{version}.joblib`** — no per-line special-casing in the loader.
- **Every trained artifact ships with a `.metadata.json` sidecar** — train date, sample count, feature list, precision/recall/AUC on held-out set, seed. Consumed by the admin surface (Phase 5) and the promotion script (Phase 2).

## Implementation Approach

Ordered so each phase is independently verifiable. **Phases 0 + 0.5 must land before Phase 2 promotes anything.**

0. **Phase 0 (new, per G1)** — Stratified review-queue backend + Angular queue UI. New endpoint `GET /api/v1/ai/predictions/review-queue?size=N` returns a 50/50 HIGH/LOW random-sample batch of unreviewed predictions; queue-mode toggle on `/tenant/admin/ai-predictions`. **Without this, the training corpus is HIGH-only-biased and no downstream training run produces a usable model** — this is a hard prerequisite, not a nice-to-have.
0.5. **Phase 0.5 (new, per G4)** — Formal anonymization audit sign-off. Auditor reviews every column of the corpus reader's output (Phase 1) and confirms no PII/PHI is re-identifiable, including through nested `output.line_features` bags. Sign-off is a manual gate — no Phase 2 artifact promotes until it lands.
1. **Phase 1** (corpus reader + eval harness) is standalone Python — no service changes. Ships alone; can be run manually against a live `ai_predictions_store`. **Adds a schema-version tag to every row it emits (per G7).**
2. **Phase 2** (fraud training + artifact registry) depends on Phase 1's reader. Produces `.joblib` files **in MinIO (per G6)** with a **calibrator alongside the classifier (per G2)** and a **`canonical_features_schema` field (per G7)**. Runtime not yet consuming them.
3. **Phase 3** (load-at-startup) is the runtime swap. Reads the artifacts Phase 2 produced **from MinIO via a 60s per-pod poll (per G6)**. Falls back to synthetic on any error; **surfaces `SCHEMA_MISMATCH` when the artifact's schema version doesn't match the extractor's (per G7)**.
4. **Phase 4** ~~(pricing training) is standalone from fraud — same corpus, different endpoint, different label source. Can ship in parallel with Phase 2.~~ **(pricing plumbing only per G3) ships resolver + registry slots + admin rows so Tranche 2's training-script drop is a small addition. No training script in this tranche.**
5. **Phase 5** (Angular admin surface) reads a new `GET /api/v1/ai/models` endpoint that reports the currently-active version per line + eval metrics from the sidecar JSON. **Gains a UI Promote button + `ai:models:promote` permission + confirmation modal (per G5) and a `SCHEMA_MISMATCH` status badge per row (per G7).**
6. **Phase 6** (CronJob) schedules Phase 2 ~~+ 4~~ to run weekly per line. Deployment concern; no code changes to the ai-service itself beyond a `Dockerfile.training` variant.

**Critical Rules coverage**:
- **Rule 1 (currency)** — not exercised; pricing multiplier is unitless.
- **Rule 2 (tenant scoping)** — training reads `ai_predictions_store` **across tenants** (Tranche 3 makes this opt-in). Every audit row still carries `tenant_id`; models are cross-tenant but predictions stay tenant-scoped at inference time.
- **Rule 3 (AI auditable)** — every trained artifact ships with metadata JSON; every prediction row records the exact `model_version` string. Reproducibility is direct.
- **Rule 4 (PII/PHI)** — `input_features` in `ai_predictions_store` is already anonymized (Tranche 0 Phase 2). Training scripts read those anonymized rows — no re-derivation from raw claims / member tables required.
- **Rule 5 (per-tenant rules)** — not exercised.
- **Rule 6 (Kafka)** — retraining CronJob posts to Slack (Phase 6), not Kafka. Model swap is filesystem-backed, not event-backed.
- **Rule 7 (Swagger)** — Phase 5 adds `GET /api/v1/ai/models` — Swagger-annotated.
- **Rule 8 (AuditEvent per mutation)** — `promote_model.py` writes an audit event to `medfund.audit.events` with actor, before/after version, and reason. CLI passes actor via env vars or a --actor flag.
- **Rule 9 (SecurityEvent)** — retraining is a read-only op (no data mutation); no security event.

---

## Phase 0: Stratified Review Queue (per G1)

### Overview

Every Phase-2 training run reads labels out of `ai_predictions_store`. Reviewers naturally only look at HIGH-risk predictions, so the corpus is skewed toward the model's own flagged set — no confirmed true-negatives, no false-negatives, and recall stays unmeasurable. Phase 0 fixes that by wiring a **stratified random-sample queue** that mixes HIGH and LOW predictions 50/50 (per G2). Reviewers work through the queue in order; their accept/override decisions produce a corpus with real true-negative signal.

### Changes Required

- `services/python/ai-service/app/api/predictions.py` — new `GET /api/v1/ai/predictions/review-queue?size=N&model_type=fraud` returning a batch of unreviewed predictions, half sampled uniformly from `risk_level=HIGH` and half from `risk_level=LOW` (fallback to whichever has volume when a bucket is thin). Tenant-scoped by `X-Tenant-ID` header.
- `clients/angular/src/app/pages/tenant-admin/ai-predictions/` — new "Review Queue" mode toggle on the existing predictions page. Renders one row at a time with accept/override + optional feedback textarea, then advances.
- `services/python/ai-service/tests/test_review_queue.py` — parametrized: assert queue size, HIGH/LOW ratio ≈ 50/50, tenant-scoping, unreviewed-only.

### Success Criteria

- [ ] Queue endpoint returns 50/50 HIGH/LOW when both buckets have volume
- [ ] Reviewer can burn through 50 predictions in ≤ 20 minutes in the queue UI
- [ ] Every row reviewed in queue mode ends up as a labeled `ai_predictions_store` row within one request

---

## Phase 0.5: Anonymization Audit Sign-Off (per G4)

### Overview

Cross-tenant training is the default (G4), but every column of the training corpus has to pass a formal anonymization review before any Phase 2 artifact is allowed to serve production requests. Auditor confirms the corpus reader's DataFrame contains **no re-identifiable PII/PHI**, including through nested `output.line_features` fields (a diagnosis-code list on a HEALTH row + a `days_since_start=14` is a quasi-identifier for small enough tenants).

### Changes Required

- New doc `thoughts/shared/audit/2026-XX-XX-ai-training-corpus-anonymization.md` — one row per column emitted by the corpus reader, auditor sign-off + date + rationale + any redaction rules added downstream.
- `services/python/ai-service/scripts/dump_corpus_sample.py` (new) — writes a 100-row anonymized sample of the training corpus for the auditor to inspect. Deterministic seed; safe to hand off.
- `services/python/ai-service/scripts/train_fraud.py` — checks for `MEDFUND_TRAINING_AUDIT_SIGNED_OFF=true` before writing an artifact; hard-refuses to train otherwise. Env var flipped by the auditor's promotion PR.

### Success Criteria

- [ ] Audit doc landed and merged; env var flipped on
- [ ] `train_fraud.py` refuses to write an artifact when the env var is absent
- [ ] Sample-dump script produces the corpus preview the auditor needs

---

## Phase 1: Corpus Reader + Eval Harness

### Overview

Standalone Python utilities that (a) read the `ai_predictions_store` audit corpus filtered by line + prediction_type + acceptance status, (b) split into train/val/test with stable random seed, (c) compute standard classification / regression metrics against a candidate model, and (d) report a data-sufficiency verdict for each line. Ships as CLI scripts under `services/python/ai-service/scripts/` — no changes to the running service.

### Changes Required

#### 1. `services/python/ai-service/scripts/__init__.py` + `scripts/data_sufficiency.py` (new)

```python
"""Print a per-line data-sufficiency verdict for the current audit corpus.

Usage:
    uv run python scripts/data_sufficiency.py
    uv run python scripts/data_sufficiency.py --line HEALTH --min-samples 500
"""
import argparse
import asyncio
from dataclasses import dataclass

from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import create_async_engine, async_sessionmaker, AsyncSession

from app.core.config import settings
from app.models.db_models import AIPredictionDB
from app.schemas.insurance_line import InsuranceLine

MIN_FRAUD_SAMPLES_DEFAULT = 200
MIN_PRICING_SAMPLES_DEFAULT = 500


@dataclass
class LineDataSufficiency:
    line: InsuranceLine
    fraud_labeled: int
    pricing_labeled: int
    is_fraud_trainable: bool
    is_pricing_trainable: bool


async def report(min_fraud: int, min_pricing: int) -> list[LineDataSufficiency]:
    engine = create_async_engine(settings.database_url)
    factory = async_sessionmaker(engine, class_=AsyncSession)
    async with factory() as session:
        results: list[LineDataSufficiency] = []
        for line in InsuranceLine:
            fraud = (await session.execute(
                select(func.count()).select_from(AIPredictionDB).where(
                    AIPredictionDB.insurance_line == line.value,
                    AIPredictionDB.prediction_type == "fraud",
                    AIPredictionDB.accepted.isnot(None),
                )
            )).scalar_one()
            pricing = (await session.execute(
                select(func.count()).select_from(AIPredictionDB).where(
                    AIPredictionDB.insurance_line == line.value,
                    AIPredictionDB.prediction_type == "pricing",
                    AIPredictionDB.accepted.isnot(None),
                )
            )).scalar_one()
            results.append(LineDataSufficiency(
                line=line,
                fraud_labeled=int(fraud or 0),
                pricing_labeled=int(pricing or 0),
                is_fraud_trainable=(fraud or 0) >= min_fraud,
                is_pricing_trainable=(pricing or 0) >= min_pricing,
            ))
    await engine.dispose()
    return results


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--min-fraud",   type=int, default=MIN_FRAUD_SAMPLES_DEFAULT)
    parser.add_argument("--min-pricing", type=int, default=MIN_PRICING_SAMPLES_DEFAULT)
    args = parser.parse_args()

    results = asyncio.run(report(args.min_fraud, args.min_pricing))
    print(f"{'LINE':<12} {'FRAUD':>8} {'PRICING':>8} {'FRAUD?':>8} {'PRICING?':>10}")
    for r in results:
        print(f"{r.line.value:<12} {r.fraud_labeled:>8} {r.pricing_labeled:>8} "
              f"{'YES' if r.is_fraud_trainable else 'no':>8} "
              f"{'YES' if r.is_pricing_trainable else 'no':>10}")


if __name__ == "__main__":
    main()
```

#### 2. `services/python/ai-service/scripts/_corpus.py` (new)

Shared reader that Phase 2 + Phase 4 both import. Returns `pandas.DataFrame` rows keyed by (line, prediction_type):

```python
"""Read the labeled ai_predictions_store corpus for training / evaluation."""
import numpy as np
import pandas as pd
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.db_models import AIPredictionDB
from app.schemas.insurance_line import InsuranceLine


async def read_fraud_corpus(
    session: AsyncSession, line: InsuranceLine, limit: int | None = None,
) -> pd.DataFrame:
    """Return labeled fraud rows: canonical_features + label (accepted → 0, overridden → 1)."""
    stmt = select(AIPredictionDB).where(
        AIPredictionDB.insurance_line == line.value,
        AIPredictionDB.prediction_type == "fraud",
        AIPredictionDB.accepted.isnot(None),
    )
    if limit:
        stmt = stmt.limit(limit)
    rows = (await session.execute(stmt)).scalars().all()
    frame = pd.DataFrame([{
        # Canonical 5-tuple lives on output.canonical_features
        "amount":               (r.output or {}).get("canonical_features", [0]*5)[0],
        "days_since_start":     (r.output or {}).get("canonical_features", [0]*5)[1],
        "claim_frequency_30d":  (r.output or {}).get("canonical_features", [0]*5)[2],
        "provider_flag_count":  (r.output or {}).get("canonical_features", [0]*5)[3],
        "subject_flag_count":   (r.output or {}).get("canonical_features", [0]*5)[4],
        # Label: overridden (accepted=False) is "the reviewer said this WASN'T fraud"
        # but the model SAID it was → so accepted=False means the model was wrong.
        # For training we want "is this row actually fraud?". The label comes from
        # the reviewer's verdict: accepted=True means the model was right about
        # fraud; accepted=False means it wasn't fraud after all.
        "label":                _fraud_label(r),
        "tenant_id":            r.tenant_id,
    } for r in rows])
    return frame


def _fraud_label(row: AIPredictionDB) -> int:
    """Reviewer verdict → training label.

    The AI service scored this row. Then a human reviewed:
    - accepted=True  → reviewer agreed with the AI's fraud score
    - accepted=False → reviewer disagreed (either false positive or false negative)

    We use `output.risk_level` to decide direction. If risk_level=HIGH + accepted=True,
    the true label is 1 (fraud). If risk_level=HIGH + accepted=False, true label is 0
    (not fraud). If risk_level=LOW + accepted=True, true label is 0. And so on.
    """
    out = row.output or {}
    predicted_fraud = (out.get("risk_level") == "HIGH")
    if row.accepted is True:
        return 1 if predicted_fraud else 0
    else:  # accepted is False
        return 0 if predicted_fraud else 1


async def read_pricing_corpus(
    session: AsyncSession, line: InsuranceLine, limit: int | None = None,
) -> pd.DataFrame:
    """Return labeled pricing rows. Attribute bag + multiplier + acceptance."""
    stmt = select(AIPredictionDB).where(
        AIPredictionDB.insurance_line == line.value,
        AIPredictionDB.prediction_type == "pricing",
        AIPredictionDB.accepted.isnot(None),
    )
    if limit:
        stmt = stmt.limit(limit)
    rows = (await session.execute(stmt)).scalars().all()
    return pd.DataFrame([{
        **((r.input_features or {}).get("attributes") or {}),
        "predicted_multiplier": (r.output or {}).get("multiplier"),
        "accepted":             bool(r.accepted),
        "tenant_id":            r.tenant_id,
    } for r in rows])


def stratified_split(
    frame: pd.DataFrame, label_col: str = "label",
    train_pct: float = 0.70, val_pct: float = 0.15,
    seed: int = 42,
) -> tuple[pd.DataFrame, pd.DataFrame, pd.DataFrame]:
    """Stable-seed stratified train/val/test split."""
    rng = np.random.RandomState(seed)
    frames = []
    for label_value in frame[label_col].unique():
        group = frame[frame[label_col] == label_value].sample(frac=1, random_state=seed)
        n = len(group)
        n_train = int(n * train_pct)
        n_val = int(n * val_pct)
        frames.append((
            group.iloc[:n_train],
            group.iloc[n_train:n_train + n_val],
            group.iloc[n_train + n_val:],
        ))
    train = pd.concat([f[0] for f in frames]).sample(frac=1, random_state=seed)
    val   = pd.concat([f[1] for f in frames]).sample(frac=1, random_state=seed)
    test  = pd.concat([f[2] for f in frames]).sample(frac=1, random_state=seed)
    return train, val, test
```

#### 3. `services/python/ai-service/scripts/evaluate_model.py` (new)

Loads a candidate `.joblib`, reads the test split from Phase 1's corpus reader, computes precision/recall/AUC (fraud) or R²/MAE (pricing), and prints a table. Compares against the current `latest` if it exists. Exit-code non-zero on regression.

```python
# Skeleton — full impl in the actual PR
def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--line", required=True, choices=[l.value for l in InsuranceLine])
    parser.add_argument("--candidate", required=True, help="e.g. 'v2'")
    parser.add_argument("--model-type", choices=["fraud", "pricing"], required=True)
    parser.add_argument("--regression-threshold", type=float, default=0.02,
                        help="Fail if candidate < latest - threshold on primary metric")
    args = parser.parse_args()

    candidate = load_artifact(args.model_type, args.line, args.candidate)
    latest    = load_artifact(args.model_type, args.line, "latest", allow_missing=True)

    corpus = asyncio.run(load_test_split(args.model_type, args.line))
    candidate_metrics = _eval(candidate, corpus, args.model_type)

    if latest:
        latest_metrics = _eval(latest, corpus, args.model_type)
        _print_comparison(latest_metrics, candidate_metrics)
        primary = "auc" if args.model_type == "fraud" else "r2"
        if candidate_metrics[primary] < latest_metrics[primary] - args.regression_threshold:
            sys.exit(2)
    else:
        _print_single(candidate_metrics)
```

#### 4. Tests

`tests/scripts/test_corpus_reader.py`:

- `test_fraud_label_inversion_when_reviewer_overrides` — HIGH + accepted=True → 1; HIGH + accepted=False → 0; LOW + accepted=False → 1; LOW + accepted=True → 0
- `test_stratified_split_preserves_label_distribution` — assert train/val/test each have ~same positive class fraction (± 5%)
- `test_read_fraud_corpus_filters_unlabeled_rows` — rows with `accepted=None` never appear in the returned frame
- `test_read_fraud_corpus_line_scoped` — inserting a HEALTH row and a VEHICLE row, requesting HEALTH returns only the HEALTH row
- `test_data_sufficiency_flags_lines_below_threshold` — seed 199 fraud rows for HEALTH, expect `is_fraud_trainable=False`; add one more, expect True

### Success Criteria

#### Automated Verification
- [ ] Scripts run without errors: `cd services/python/ai-service && uv run python scripts/data_sufficiency.py` prints an 8-row table
- [ ] `uv run pytest tests/scripts/test_corpus_reader.py` — all label-inversion + split-invariance cases green
- [ ] Type check: `uv run mypy scripts/`

#### Manual Verification
- [ ] On a dev DB with hand-inserted labeled rows, `data_sufficiency.py --min-fraud 5` reports the correct verdicts per line

---

## Phase 2: Fraud Training Pipeline + Artifact Registry

### Overview

`scripts/train_fraud.py --line HEALTH --output-version 2` reads the labeled corpus (Phase 1), trains a `RandomForestClassifier` (line-specific — VEHICLE uses different hyperparams than HEALTH), **fits a per-line calibrator (per G2)**, writes ~~`app/artifacts/fraud-health-v2.joblib`~~ **`s3://medfund-ml-artifacts/fraud-health-v2.joblib` (per G6)** plus `fraud-health-v2.metadata.json` **sidecar in the same bucket**, and prints the eval metrics. ~~`promote_model.py --line HEALTH --version v2` flips the `latest` symlink (or copies the artifact in prod where symlinks may not be available on S3).~~ **Per G5, promotion is UI-driven — the endpoint `PUT /api/v1/ai/models/fraud/HEALTH/promote` updates a shared `manifest.json` object in the same MinIO bucket. Per G6, every AI-service pod polls that manifest every 60s and reloads the resolver cache on change.**

Every trained artifact is a `dict` containing ~~`{"scaler": StandardScaler, "model": RandomForestClassifier, "feature_names": [...], "model_version": "fraud-health-v2"}`~~ **`{"scaler", "model", "calibrator" (per G2), "feature_names", "canonical_features_schema" (per G7), "model_version"}`** so the loader can validate feature-list compatibility on load **and reject artifacts whose schema version does not match the extractor's (per G7)**.

> **Note — code sketches below predate G2/G6/G7.** They still use local-filesystem paths (`Path`, `symlink_to`, `app/artifacts/`) and a naked classifier without the calibrator. The Phase 2 refinement pass (post-grill) will:
> - Replace `Path` → S3 URL / MinIO client calls (`boto3` or `minio-py`).
> - Replace `symlink_to` → `manifest.json` object update.
> - Wrap the classifier in a `CalibratedClassifierCV` (per G2).
> - Add the `canonical_features_schema: "v1"` field to the joblib dict (per G7).
> - Keep the metadata JSON sidecar shape unchanged apart from the schema-version addition.

### Changes Required

#### 1. `services/python/ai-service/scripts/train_fraud.py` (new)

```python
# Skeleton — full impl in the actual PR
import argparse, asyncio, json, joblib
from pathlib import Path
from datetime import datetime, timezone

from sklearn.ensemble import RandomForestClassifier
from sklearn.metrics import precision_score, recall_score, roc_auc_score
from sklearn.preprocessing import StandardScaler

from scripts._corpus import read_fraud_corpus, stratified_split
from app.schemas.insurance_line import InsuranceLine

ARTIFACT_DIR = Path("app/artifacts")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--line", required=True, choices=[l.value for l in InsuranceLine])
    parser.add_argument("--output-version", required=True, help="e.g. 'v2'")
    parser.add_argument("--min-samples", type=int, default=200)
    parser.add_argument("--seed", type=int, default=42)
    args = parser.parse_args()

    line = InsuranceLine(args.line)
    engine = create_async_engine(settings.database_url)
    factory = async_sessionmaker(engine, class_=AsyncSession)

    async def load():
        async with factory() as s:
            return await read_fraud_corpus(s, line)
    frame = asyncio.run(load())

    if len(frame) < args.min_samples:
        print(f"insufficient data: {len(frame)} < {args.min_samples}")
        sys.exit(1)

    train, val, test = stratified_split(frame, label_col="label", seed=args.seed)
    feature_cols = ["amount", "days_since_start", "claim_frequency_30d",
                    "provider_flag_count", "subject_flag_count"]

    scaler = StandardScaler().fit(train[feature_cols])
    X_train = scaler.transform(train[feature_cols])
    X_val   = scaler.transform(val[feature_cols])
    y_train = train["label"].values
    y_val   = val["label"].values

    model = RandomForestClassifier(
        n_estimators=100, max_depth=8, class_weight="balanced",
        random_state=args.seed,
    ).fit(X_train, y_train)

    y_pred = model.predict(X_val)
    y_proba = model.predict_proba(X_val)[:, 1]
    metrics = {
        "precision": precision_score(y_val, y_pred, zero_division=0),
        "recall":    recall_score(y_val, y_pred, zero_division=0),
        "auc":       roc_auc_score(y_val, y_proba),
        "train_samples": int(len(train)),
        "val_samples":   int(len(val)),
        "test_samples":  int(len(test)),
        "line":          line.value,
        "model_version": f"fraud-{line.value.lower()}-{args.output_version}",
        "trained_at":    datetime.now(timezone.utc).isoformat(),
        "feature_names": feature_cols,
        "seed":          args.seed,
    }

    ARTIFACT_DIR.mkdir(parents=True, exist_ok=True)
    artifact_path = ARTIFACT_DIR / f"fraud-{line.value.lower()}-{args.output_version}.joblib"
    metadata_path = ARTIFACT_DIR / f"fraud-{line.value.lower()}-{args.output_version}.metadata.json"

    joblib.dump({
        "scaler":        scaler,
        "model":         model,
        "feature_names": feature_cols,
        "model_version": metrics["model_version"],
    }, artifact_path)
    metadata_path.write_text(json.dumps(metrics, indent=2))
    print(f"Wrote {artifact_path} + {metadata_path}")
    print(json.dumps(metrics, indent=2))
```

#### 2. `services/python/ai-service/scripts/promote_model.py` (new)

Flips the `latest` symlink to the requested version. Writes an audit event to `medfund.audit.events` with actor + before/after version.

```python
def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--model-type", choices=["fraud", "pricing"], required=True)
    parser.add_argument("--line", required=True, choices=[l.value for l in InsuranceLine])
    parser.add_argument("--version", required=True, help="e.g. 'v2'")
    parser.add_argument("--actor-id", required=True)
    parser.add_argument("--actor-email", required=True)
    args = parser.parse_args()

    target = ARTIFACT_DIR / f"{args.model_type}-{args.line.lower()}-{args.version}.joblib"
    if not target.exists():
        sys.exit(f"missing artifact: {target}")

    latest_symlink = ARTIFACT_DIR / f"{args.model_type}-{args.line.lower()}-latest.joblib"
    before = latest_symlink.readlink().name if latest_symlink.exists() else "none"

    if latest_symlink.exists():
        latest_symlink.unlink()
    latest_symlink.symlink_to(target.name)

    # Audit event
    publish_model_promotion_event(
        actor_id=args.actor_id, actor_email=args.actor_email,
        model_type=args.model_type, line=args.line,
        before=before, after=target.name,
    )
    print(f"promoted {target.name} → latest")
```

#### 3. `services/python/ai-service/scripts/_registry.py` (new)

Shared artifact-path helpers used by trainer + evaluator + runtime loader:

```python
from pathlib import Path
from app.core.config import settings
from app.schemas.insurance_line import InsuranceLine


def path_for(model_type: str, line: InsuranceLine, version: str) -> Path:
    """Absolute path for an artifact — dev uses local dir, prod uses S3 via boto."""
    base = Path(settings.model_artifact_dir or "app/artifacts")
    return base / f"{model_type}-{line.value.lower()}-{version}.joblib"
```

#### 4. `services/python/ai-service/app/core/config.py`

Add `model_artifact_dir: str = "app/artifacts"` + `model_artifacts_enabled: bool = True`.

#### 5. Tests

`tests/scripts/test_train_fraud.py`:

- `test_train_writes_joblib_and_metadata` — seed 500 labeled rows for HEALTH, run trainer with `--output-version v-test`, assert both files land, model_version matches
- `test_train_metadata_contains_expected_fields` — `sample count`, `feature_names`, `trained_at`, `model_version`, `precision`, `recall`, `auc`
- `test_train_fails_below_min_samples` — 100 rows, `--min-samples 200`, exit code non-zero
- `test_promote_updates_latest_symlink` — write v1 and v2 artifacts; promote v2; readlink returns v2 filename
- `test_promote_emits_audit_event` — mock the audit publisher, assert `AuditEvent` posted with `entityType=AI_MODEL`, `actorEmail` present, `before/after` populated

### Success Criteria

#### Automated Verification
- [ ] Trainer runs on a seeded dev DB: `uv run python scripts/train_fraud.py --line HEALTH --output-version v-test --min-samples 5`
- [ ] Both files present on disk with the expected shapes
- [ ] Promotion flips the symlink and emits an audit event
- [ ] Tests: `uv run pytest tests/scripts/test_train_fraud.py`

#### Manual Verification
- [ ] Manually seed a mixed corpus (200 labeled HEALTH rows across the 2 classes), train, inspect the metadata JSON — precision/recall/auc all populated

---

## Phase 3: Load-at-Startup with Fallback

### Overview

The runtime picks up trained artifacts on service boot. `FraudMLModel` becomes `resolve_fraud_model(line: InsuranceLine)` which first tries `joblib.load(path_for("fraud", line, "latest"))`, and on any error (missing file, corrupt archive, feature-name mismatch) falls back to the synthetic canonical model with a warning log. The line dispatch happens on every fraud request — the resolved model is cached process-lifetime (invalidated on restart).

### Changes Required

#### 1. `services/python/ai-service/app/services/ml_models.py`

Refactor `FraudMLModel` to accept an optional pre-fitted `sklearn` model:

```python
class FraudMLModel:
    def __init__(
        self,
        line: InsuranceLine | None = None,
        seed: int = 42,
        artifact: dict | None = None,
    ):
        self._line = line
        self._model_version = (
            artifact["model_version"] if artifact
            else "fraud-isolation-forest-v1-canonical"
        )
        if artifact:
            self._isolation_forest = None
            self._classifier = artifact["model"]  # RandomForestClassifier
            self._scaler     = artifact["scaler"]
            self._feature_names = artifact["feature_names"]
            self._trained    = True
        else:
            # Existing synthetic-training path — unchanged from Tranche 0
            self._classifier = None
            self._train(seed)

    def predict_canonical(self, canonical: list[float]) -> dict:
        if self._classifier is not None:
            # New: real trained classifier path
            X_scaled = self._scaler.transform(np.array([canonical], dtype=float))
            risk_proba = float(self._classifier.predict_proba(X_scaled)[0, 1])
            return {
                "risk_score":    round(risk_proba, 4),
                "risk_level":    _bucket(risk_proba),
                "indicators":    self._rule_indicators(canonical),
                "model_version": self._model_version,
            }
        # Existing IsolationForest path — unchanged
        return self._legacy_predict(canonical)
```

#### 2. `services/python/ai-service/app/services/fraud_registry.py` (new)

Runtime resolver — mirror of `resolve_llm`:

```python
"""Runtime fraud-model registry per InsuranceLine."""
from __future__ import annotations
import logging
import joblib

from app.core.config import settings
from app.schemas.insurance_line import InsuranceLine
from app.services.ml_models import FraudMLModel
from scripts._registry import path_for

logger = logging.getLogger(__name__)

_CACHE: dict[InsuranceLine, FraudMLModel] = {}
_FALLBACK: FraudMLModel | None = None


def resolve_fraud_model(line: InsuranceLine) -> FraudMLModel:
    """Return the trained artifact for `line`, or the canonical fallback.

    First call for a given line pays the joblib.load cost; subsequent
    calls hit the process-lifetime cache. A promotion requires a
    service restart to take effect.
    """
    global _FALLBACK
    cached = _CACHE.get(line)
    if cached is not None:
        return cached

    if line == InsuranceLine.GROUP:
        # GROUP dispatches via underlying_line at the extractor level;
        # the model itself is the fallback canonical.
        _CACHE[line] = _get_fallback()
        return _CACHE[line]

    if settings.model_artifacts_enabled:
        artifact_path = path_for("fraud", line, "latest")
        if artifact_path.exists():
            try:
                artifact = joblib.load(artifact_path)
                model = FraudMLModel(line=line, artifact=artifact)
                logger.info("Loaded fraud model %s from %s",
                            artifact["model_version"], artifact_path)
                _CACHE[line] = model
                return model
            except Exception as e:
                logger.warning("Failed to load %s (falling back to canonical): %s",
                               artifact_path, e)

    _CACHE[line] = _get_fallback()
    return _CACHE[line]


def _get_fallback() -> FraudMLModel:
    global _FALLBACK
    if _FALLBACK is None:
        _FALLBACK = FraudMLModel(line=None, seed=42)
        logger.info("Fraud fallback: canonical synthetic model")
    return _FALLBACK
```

#### 3. `services/python/ai-service/app/services/fraud_service.py`

`FraudService.check_fraud_request` becomes line-aware in model choice:

```python
class FraudService:
    def __init__(self, extractors: ExtractorRegistry | None = None) -> None:
        self._extractors = extractors or ExtractorRegistry()

    async def check_fraud_request(
        self, request: FraudCheckRequest, tenant_id: str,
    ) -> dict:
        extractor = self._extractors.for_line(request.insurance_line)
        canonical, line_features, rule_indicators = await extractor.extract(
            request, tenant_id, FraudContext(),
        )

        model = resolve_fraud_model(request.insurance_line)   # NEW
        ml_out = model.predict_canonical(canonical)
        return {
            **ml_out,
            "indicators":       list(ml_out.get("indicators", [])) + rule_indicators,
            "insurance_line":   request.insurance_line.value,
            "line_features":    line_features,
            "canonical_features": canonical,
        }
```

#### 4. Tests

`tests/services/test_fraud_registry.py`:

- `test_resolve_returns_canonical_when_no_artifact` — cold start, no files, resolve returns model with `model_version` starting with `"fraud-isolation-forest-v1-canonical"`
- `test_resolve_returns_trained_when_artifact_present` — write a valid joblib to the expected path, resolve returns model with the trained model_version
- `test_resolve_falls_back_on_corrupt_artifact` — write garbage to the path, resolve returns canonical fallback (no crash)
- `test_resolve_falls_back_on_missing_scaler` — joblib with malformed dict, fall back
- `test_resolve_caches_per_line` — resolve HEALTH twice; joblib.load is called once
- `test_group_dispatches_to_fallback` — GROUP always returns the canonical fallback (line-level dispatch happens at extraction time)
- `test_fraud_service_uses_line_resolved_model` — parametrize across all 8 lines, assert `check_fraud_request` returns `model_version` matching the resolved model per line

### Success Criteria

#### Automated Verification
- [ ] Tests: `uv run pytest tests/services/test_fraud_registry.py tests/test_fraud.py`
- [ ] Existing fraud tests still green — `test_fraud.py` regression suite passes with default canonical fallback
- [ ] Coverage: `--cov=app.services.fraud_registry --cov-fail-under=90`

#### Manual Verification
- [ ] `make ai` on a dev DB with a valid HEALTH artifact: POST a HEALTH fraud request → response `model_version` is `fraud-health-vN`
- [ ] Same command with the artifact removed: response `model_version` reverts to canonical

---

## Phase 4: Pricing Plumbing (per G3 — training deferred to Tranche 2)

### Overview

~~Parallel to Phase 2 but for regression instead of classification. `train_pricing.py --line HEALTH --output-version v2` reads the labeled pricing corpus, trains a `Ridge` regression predicting `multiplier` from the member-attribute bag, writes `pricing-health-v2.joblib`. Runtime path mirrors fraud: `resolve_pricing_model(line)` loads the artifact or falls back to the rule-based scorer that Tranche 0 ships.~~

~~Label source: **reviewer override** (weak label). If the reviewer overrode a `multiplier=1.5` back to `1.0`, the training target for that row's attributes becomes `1.0`. This is a Tranche-1-only shortcut; Tranche 2 replaces it with the actual loss-ratio label.~~

**Per G3, pricing training is deferred to Tranche 2 (gated on loss-ratio labels).** Phase 4 ships the plumbing so that when Tranche 2 lands a `train_pricing.py` script, the runtime, registry, and admin surface pick it up automatically without further wiring:

- `resolve_pricing_model(line: InsuranceLine)` runtime resolver (mirrors `resolve_fraud_model`; today always returns the rule-based scorer wrapped in a `PricingModel` adapter).
- Registry slots reserved: `pricing-{line}-v{n}.joblib` paths in MinIO are the canonical location; loader is ready for artifacts but none exist.
- Admin surface (Phase 5) shows a "Canonical rule-v1 (fallback)" row per pricing line — no `SCHEMA_MISMATCH` because the fallback is the intended state for pricing in this tranche.

**Reviewer-override labels DO still accumulate on pricing predictions** — they're just not consumed by a training script until Tranche 2. When Tranche 2 lands, the corpus is already there.

### Changes Required

~~#### 1. `services/python/ai-service/scripts/train_pricing.py` (new)~~ **Deleted per G3 — no pricing training script this tranche.**

#### 1. `services/python/ai-service/app/services/pricing_registry.py` (new)

Mirror of `fraud_registry.py`. `resolve_pricing_model(line)` **today always returns the rule-based scorer wrapped in a `PricingModel` adapter** so `/api/v1/pricing/score` speaks a single interface. The MinIO manifest is polled (Phase 3 mechanism) but pricing entries stay empty in this tranche — when Tranche 2's `train_pricing.py` writes an artifact, the resolver picks it up on the next 60s poll without a code change.

#### 2. `services/python/ai-service/app/api/pricing.py`

`/api/v1/pricing/score` becomes:

```python
def score(req: ScoreRequest) -> ScoreResponse:
    model = resolve_pricing_model(req.insurance_line)   # NEW
    return model.score(req)   # scored_model has same {multiplier, rationale, model_version} shape
```

Rule-based scorer is wrapped in an adapter class implementing `.score()`; the trained-regressor path exists in code as a `MinioTrainedPricingModel` class but is never exercised in Tranche 1 (no artifact writes it). Parked separately: `services/python/ai-service/app/api/pricing.py:141-152` MOTOR/VEHICLE key mismatch (Tranche 0 bug — see plan header for the parked defect list).

#### 3. Tests

- `test_resolve_pricing_returns_rule_v1_when_no_artifact` — cold start, MinIO empty, resolver returns the rule-based adapter with `model_version="rule-v1"`
- `test_pricing_endpoint_uses_resolver` — parametrize across all 8 lines, assert response shape unchanged from Tranche 0
- ~~`test_pricing_multiplier_clamped_after_trained_prediction`~~ **Not applicable this tranche** (no trained pricing model exists)

### Success Criteria

#### Automated Verification
- [ ] ~~Trainer runs against seeded HEALTH pricing rows~~ **Removed per G3 — no trainer in this tranche.**
- [ ] `resolve_pricing_model()` returns the rule-based adapter for every line; `model_version` stays `rule-v1`
- [ ] Pricing regression tests pass — existing `test_pricing.py` suite green with the resolver in front of the rule-based scorer
- [ ] Admin surface (Phase 5) shows "Canonical rule-v1 (fallback)" for every pricing line

#### Manual Verification
- [ ] ~~Trained pricing model produces meaningful multipliers on a hand-picked set of member profiles~~ **Removed per G3 — retest in Tranche 2 when a real pricing model ships.**
- [ ] Pricing endpoint response shape unchanged from Tranche 0 for every line (VEHICLE excluded pending the parked MOTOR/VEHICLE fix)

---

## Phase 5: Admin Surface — Active Model Versions + Promote UI

### Overview

Tenant admins see which model version is currently serving predictions for each line, along with the metadata sidecar's train date + sample count + eval metrics. ~~Read-only.~~ **Read + a Promote button per row (per G5), guarded by a new `ai:models:promote` permission behind a confirmation modal.** Deep-linked from the `/tenant/admin/ai-predictions` list page — sits at `/tenant/admin/ai-predictions/models` behind the existing tenant-admin auth.

New endpoints on AI service:
- `GET /api/v1/ai/models` returns one row per (model_type, line) tuple with an `active_version`, `candidate_versions[]`, `eval_metrics`, and — **per G7** — a `schema_status` field (`OK` / `SCHEMA_MISMATCH`).
- **`PUT /api/v1/ai/models/{model_type}/{line}/promote` (per G5)** — body `{version: "v2"}`. Updates the MinIO manifest, emits an audit event with `actorEmail`, propagates to pods on the next 60s poll (per G6).

New Angular page consumes both.

### Changes Required

#### 1. `services/python/ai-service/app/api/models.py` (new)

```python
@router.get("/api/v1/ai/models", response_model=list[ActiveModel])
async def list_active_models(...) -> list[ActiveModel]:
    """Return the currently-active model version per (model_type, line)."""
    rows = []
    for model_type in ("fraud", "pricing"):
        for line in InsuranceLine:
            path = path_for(model_type, line, "latest")
            metadata_path = path.with_suffix(".metadata.json")
            if path.exists() and metadata_path.exists():
                metadata = json.loads(metadata_path.read_text())
                rows.append(ActiveModel(
                    model_type=model_type,
                    line=line,
                    active=True,
                    model_version=metadata["model_version"],
                    trained_at=metadata["trained_at"],
                    train_samples=metadata["train_samples"],
                    metrics=_pick_metrics(metadata, model_type),
                ))
            else:
                rows.append(ActiveModel(
                    model_type=model_type,
                    line=line,
                    active=False,
                    model_version=("fraud-isolation-forest-v1-canonical"
                                   if model_type == "fraud" else "rule-v1"),
                    trained_at=None,
                    train_samples=0,
                    metrics={},
                ))
    return rows
```

Gateway proxies `/api/v1/ai/models` → AI service (one-line addition to `services/go/gateway/internal/routes/routes.go`).

#### 2. Angular — new page

`clients/angular/src/app/pages/tenant-admin/ai-predictions/models/ai-models.component.ts` — table showing: model type, line, active, version, trained at, sample count, primary metric (AUC / R²), status badge. **Status badge now has three states per G7: `Trained` (green), `Canonical fallback` (grey), `SCHEMA_MISMATCH` (red — artifact exists but its `canonical_features_schema` doesn't match the extractor's; runtime silently reverted to canonical until retrain).**

**Per G5 — Promote button per candidate version row.** Confirmation modal names the source version, the target line, the eval metrics of the candidate vs. current active, and requires the reviewer to click Confirm. On confirm: `PUT /api/v1/ai/models/{type}/{line}/promote` is called; success rolls up a toast; audit event emitted server-side.

Link from the list page's page header. ~~No promote/rollback controls in this tranche.~~ **Promote landed (per G5); rollback is a "Promote v(n-1)" call — same endpoint, prior version.**

#### 3. Permissions

New constants added to `services/java/shared/src/main/java/com/medfund/shared/security/Permissions.java`:

```java
public static final String AI_MODELS_VIEW    = "ai:models:view";
public static final String AI_MODELS_PROMOTE = "ai:models:promote";
```

Keycloak seed maps both to `tenant_admin`. Corresponding entries in `services/java/shared/src/main/resources/permissions.yaml`.

### Success Criteria

#### Automated Verification
- [ ] `GET /api/v1/ai/models` returns 16 rows (2 model_types × 8 lines)
- [ ] `PUT /api/v1/ai/models/{type}/{line}/promote` updates the MinIO manifest and emits an audit event with `actorEmail` non-null
- [ ] `PUT` rejects when the actor lacks `ai:models:promote`
- [ ] Angular unit test: page renders 16 rows, sorts by line, shows correct status per row
- [ ] Angular unit test: promote button opens modal, submit calls the endpoint, toast on success
- [ ] Angular unit test: SCHEMA_MISMATCH badge renders when the API returns `schema_status: 'SCHEMA_MISMATCH'`

#### Manual Verification
- [ ] With one trained model in MinIO, admin page shows the correct version + metadata sidecar values
- [ ] Promoting v2 via the UI causes the next fraud call (within 60s) to return `model_version: fraud-{line}-v2`
- [ ] After a `canonical_features_schema` bump on the extractor, admin page flips every trained line to red `SCHEMA_MISMATCH` and runtime falls back to canonical without any 500s

---

## Phase 6: Retraining CronJob

### Overview

A Kubernetes CronJob runs `train_fraud.py` weekly per line (Sunday 03:00 UTC), then `evaluate_model.py --candidate v{n+1}`. On non-regression, posts to Slack for human OK. Zero automatic promotions. Same shape for pricing (Sunday 04:00 UTC).

### Changes Required

#### 1. `services/python/ai-service/Dockerfile.training` (new)

Same base as the main service Dockerfile but sets `CMD ["uv", "run", "python", "-m", "scripts.train_fraud"]`. Extra RUN line installs the training-only deps (`pandas`, `scikit-learn>=1.4`, which the runtime already has).

#### 2. `k8s/cronjobs/train-fraud.yaml` (new)

Standard CronJob. One CronJob per line — parameterized via a Helm chart.

#### 3. `scripts/notify_slack.py` (new)

Helper wrapping `slack_sdk.WebClient.chat_postMessage` — reads `SLACK_WEBHOOK_URL` env var, formats the eval metrics into a code block, includes a "promote command" one-liner for the operator to copy-paste.

### Success Criteria

#### Automated Verification
- [ ] `helm template` on the training chart renders without errors
- [ ] `kubectl apply --dry-run` accepts every CronJob spec

#### Manual Verification
- [ ] Trigger a CronJob manually via `kubectl create job --from=cronjob/train-fraud-health` — job succeeds, artifact lands in the mounted volume, Slack message posts

---

## Testing Strategy

### Unit Tests
- Corpus reader label inversion (Phase 1)
- Stratified split invariance (Phase 1)
- Data-sufficiency verdict boundaries (Phase 1)
- Trainer output shape + metadata contents (Phase 2 + 4)
- Registry cache-hit + fallback + corrupt-file handling (Phase 3 + 4)
- Model version threaded through audit row (Phase 3)

### Integration Tests
- `test_end_to_end_fraud_training_pipeline` — seed corpus, train, promote, restart service (simulated), predict → confirm the trained model_version lands in `ai_predictions_store` for the next call

### Manual Testing Steps
1. Provision fresh infra + seed data: `make infra && make tenancy user contributions finance claims gateway ai web`
2. Submit 500 fraud claims across 3 lines (HEALTH, VEHICLE, PROPERTY) — mix of expected-fraud and normal
3. Log in as tenant admin, override 100 of them via `/tenant/admin/ai-predictions`
4. Run `uv run python scripts/data_sufficiency.py` — expect the 3 lines with data to show `YES`
5. Train: `uv run python scripts/train_fraud.py --line HEALTH --output-version v1`; observe artifact + metadata
6. Evaluate: `uv run python scripts/evaluate_model.py --line HEALTH --candidate v1 --model-type fraud`
7. Promote: `uv run python scripts/promote_model.py --model-type fraud --line HEALTH --version v1 --actor-id user-1 --actor-email admin@tenant.com`
8. Restart ai-service; submit one more HEALTH fraud request → response's `model_version` is `fraud-health-v1`
9. Open `/tenant/admin/ai-predictions/models` → HEALTH fraud row shows `Trained`; other 7 lines' fraud rows show `Canonical fallback`

## Performance Considerations

- **Training runtime**: `RandomForestClassifier` with `n_estimators=100` on ~10k samples finishes in <10s per line. Weekly CronJob is not a hot path.
- **Load-at-startup**: `joblib.load` of a 5-tuple classifier is <500ms. Boot cost stays under 5s even with all 8 line artifacts present.
- **Inference**: `RandomForestClassifier.predict_proba` on a single row is ~1ms — negligible next to existing DB write per prediction.
- **Artifact storage**: 8 lines × 2 model types = 16 artifacts. Each ~500KB. Total <10MB — well under any S3 free tier.

## Migration Notes

- **No tenant Flyway migrations** — training reads from `ai_predictions_store`, which is AI-service-owned SQLAlchemy.
- **No new columns on `ai_predictions_store`** — `model_version` is already a string field; we're just writing more informative values.
- ~~**Artifact directory** — `app/artifacts/` is added to `.gitignore` (artifacts are build outputs, not source).~~ **Per G6 — no local artifact directory in prod or dev. Artifacts live in MinIO under `s3://medfund-ml-artifacts/`. Dev docker-compose already runs MinIO (`medfund-report-payloads` bucket exists; add `medfund-ml-artifacts` alongside).**
- **New MinIO bucket** — `medfund-ml-artifacts` (create in dev via docker-compose `minio` init hook; in prod via Terraform).
- **New env vars** — `MEDFUND_MODEL_ARTIFACTS_ENABLED` (default `true`), `MEDFUND_MODEL_MANIFEST_KEY` (default `manifest.json`), `MEDFUND_MODEL_POLL_INTERVAL_SECONDS` (default `60`), `MEDFUND_TRAINING_AUDIT_SIGNED_OFF` (default `false`; flipped per G4).
- **New permission constants** — `ai:models:view` + `ai:models:promote` (per G5); Keycloak seed update required.

## Rollout & Rollback

**Rollout order** (updated per G1/G4/G3):
1. **Phase 0** (review queue) lands first — training corpus depends on it. Ships alongside Angular changes.
2. **Phase 0.5** (anonymization audit) — manual gate; `MEDFUND_TRAINING_AUDIT_SIGNED_OFF=true` flipped when auditor signs off. No code artifact.
3. Phase 1 (corpus reader + eval harness) lands after Phase 0 accumulates ~500+ rows across the target lines.
4. Phase 2 (fraud training + MinIO artifact writes) lands next. Runtime not yet consuming.
5. Phase 3 (load-at-startup + 60s manifest poll) lands after Phase 2 — runtime consumes fraud artifacts; pricing artifacts stay absent by design (per G3).
6. Phase 4 (pricing plumbing) can ship in parallel with Phase 3 — no training script.
7. Phase 5 (admin surface + UI promote) lands after Phase 3 + 4.
8. Phase 6 (CronJob) lands last — infrastructure-only.

**Rollback**:
- Any phase reverts by dropping its commit. Runtime always has the canonical fallback, so removing all trained artifacts is equivalent to reverting the trained-model path — no service downtime.
- Rollback of a bad promotion: **per G5** click Promote v(n-1) in the admin UI, or `PUT /api/v1/ai/models/{type}/{line}/promote {version: "v1"}` directly. MinIO manifest updates; propagation within 60s (per G6).
- **`SCHEMA_MISMATCH` rollback** (per G7): revert the extractor commit; every trained line flips back from red to green on the next poll.

## References

- Ancestor plan: `thoughts/shared/plans/2026-09-15-ai-service-pilot-readiness.md` (Tranche 0 — ships the audit corpus this plan trains against)
- Research: `thoughts/shared/research/2026-09-15-ai-service-gaps-and-model-strategy.md`
- Runtime shape being extended: `services/python/ai-service/app/services/ml_models.py` (`FraudMLModel`), `services/python/ai-service/app/api/pricing.py` (`_score_health` / `_score_vehicle` / etc.)
- Audit corpus: `services/python/ai-service/app/models/db_models.py` (`AIPredictionDB`)
- Review page (label source): `services/python/ai-service/app/api/predictions.py` (`decide_prediction`)
- Runtime provider pattern (template for artifact resolver): `services/python/ai-service/app/core/llm_dispatch.py` (`resolve_llm`)
