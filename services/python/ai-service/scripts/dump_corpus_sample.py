"""Anonymized 100-row corpus preview for the Phase 0.5 audit gate.

The auditor uses this to inspect what the Phase 2 training script will
see. Emits one JSON object per line to `--output` (default: stdout).

Column allowlist matches
`thoughts/shared/audit/2026-09-15-ai-training-corpus-anonymization.md` —
adding a column here without a matching row in the audit doc counts as
a re-review trigger.

Usage:
    uv run python scripts/dump_corpus_sample.py --output /tmp/sample.jsonl
    uv run python scripts/dump_corpus_sample.py --line HEALTH --limit 200
    uv run python scripts/dump_corpus_sample.py --output /tmp/health.jsonl \
        --model-type fraud --line HEALTH

Redaction rules applied per the audit doc:
- ``created_at`` truncated to month.
- ``_review_feedback`` dropped (free-text; not used in training).
- ``reviewed_by`` / ``reviewed_by_email`` dropped (internal staff PII, not
  member PII, but not needed for the training preview).
- Column allowlist is enforced — every other field is skipped.
"""
from __future__ import annotations

import argparse
import asyncio
import json
import random
import sys
from collections.abc import Iterable
from dataclasses import dataclass
from datetime import UTC, datetime
from typing import Any

from sqlalchemy import and_, select
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker, create_async_engine

from app.core.config import settings
from app.models.db_models import AIPredictionDB

ALLOWED_OUTPUT_KEYS = frozenset({
    "risk_level",
    "risk_score",
    "canonical_features",
    "line_features",
})


def _truncate_to_month(dt: datetime | None) -> str | None:
    if dt is None:
        return None
    if dt.tzinfo is None:
        dt = dt.replace(tzinfo=UTC)
    return dt.replace(day=1, hour=0, minute=0, second=0, microsecond=0).isoformat()


def _filter_output(output: Any) -> dict[str, Any]:
    """Drop everything outside the allowlist — audit-doc column contract."""
    if not isinstance(output, dict):
        return {}
    return {k: v for k, v in output.items() if k in ALLOWED_OUTPUT_KEYS}


def _to_row(pred: AIPredictionDB) -> dict[str, Any]:
    """Project an ai_predictions_store row to the audit-doc column set."""
    return {
        "tenant_id":        pred.tenant_id,
        "insurance_line":   pred.insurance_line,
        "entity_type":      pred.entity_type,
        "prediction_type":  pred.prediction_type,
        "model_version":    pred.model_version,
        "confidence":       pred.confidence,
        "accepted":         pred.accepted,
        "created_at_month": _truncate_to_month(pred.created_at),
        "output":           _filter_output(pred.output),
    }


@dataclass
class DumpArgs:
    output: str | None
    limit: int
    seed: int
    model_type: str | None
    line: str | None
    labeled_only: bool


async def _load(args: DumpArgs) -> list[AIPredictionDB]:
    engine = create_async_engine(settings.database_url)
    factory = async_sessionmaker(engine, class_=AsyncSession, expire_on_commit=False)
    try:
        async with factory() as session:
            conditions = []
            if args.model_type:
                conditions.append(AIPredictionDB.prediction_type == args.model_type)
            if args.line:
                conditions.append(AIPredictionDB.insurance_line == args.line)
            if args.labeled_only:
                conditions.append(AIPredictionDB.accepted.isnot(None))
            stmt = select(AIPredictionDB)
            if conditions:
                stmt = stmt.where(and_(*conditions))
            rows = (await session.execute(stmt)).scalars().all()
            return list(rows)
    finally:
        await engine.dispose()


def _sample(rows: list[AIPredictionDB], *, limit: int, seed: int) -> list[AIPredictionDB]:
    if len(rows) <= limit:
        return rows
    rng = random.Random(seed)
    return rng.sample(rows, limit)


def _emit(records: Iterable[dict[str, Any]], output: str | None) -> None:
    if output is None or output == "-":
        for r in records:
            sys.stdout.write(json.dumps(r) + "\n")
        return
    with open(output, "w", encoding="utf-8") as f:
        for r in records:
            f.write(json.dumps(r) + "\n")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--output", default=None,
                        help="Output path (JSONL). Defaults to stdout.")
    parser.add_argument("--limit", type=int, default=100,
                        help="Maximum rows to sample (default: 100).")
    parser.add_argument("--seed", type=int, default=42,
                        help="Deterministic sampling seed (default: 42).")
    parser.add_argument("--model-type", default=None,
                        help="Restrict to a prediction_type (e.g. 'fraud').")
    parser.add_argument("--line", default=None,
                        help="Restrict to a single InsuranceLine value.")
    parser.add_argument("--labeled-only", action="store_true",
                        help="Only include rows with an accept/override verdict.")
    parsed = parser.parse_args()
    args = DumpArgs(
        output=parsed.output,
        limit=parsed.limit,
        seed=parsed.seed,
        model_type=parsed.model_type,
        line=parsed.line,
        labeled_only=parsed.labeled_only,
    )

    rows = asyncio.run(_load(args))
    sampled = _sample(rows, limit=args.limit, seed=args.seed)
    records = [_to_row(r) for r in sampled]
    _emit(records, args.output)
    sys.stderr.write(
        f"dump_corpus_sample: {len(records)} rows written "
        f"(total available: {len(rows)}, limit: {args.limit}).\n"
    )


if __name__ == "__main__":
    main()
