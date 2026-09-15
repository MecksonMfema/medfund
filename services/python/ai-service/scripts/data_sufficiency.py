"""Per-line data-sufficiency verdict for the training corpus.

Usage:
    uv run python scripts/data_sufficiency.py
    uv run python scripts/data_sufficiency.py --min-fraud 500 --min-pricing 500

Prints one row per InsuranceLine with the count of labeled fraud +
pricing predictions and a YES/no verdict against the configured minimums.
Used at the top of the weekly retraining CronJob (Phase 6) to skip lines
that don't yet have enough labeled data.
"""
from __future__ import annotations

import argparse
import asyncio
from dataclasses import dataclass
from typing import Iterable

from sqlalchemy import and_, func, select
from sqlalchemy.ext.asyncio import (
    AsyncSession,
    async_sessionmaker,
    create_async_engine,
)

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


async def _count(session: AsyncSession, line: InsuranceLine, prediction_type: str) -> int:
    stmt = (
        select(func.count())
        .select_from(AIPredictionDB)
        .where(and_(
            AIPredictionDB.insurance_line == line.value,
            AIPredictionDB.prediction_type == prediction_type,
            AIPredictionDB.accepted.isnot(None),
        ))
    )
    return int((await session.execute(stmt)).scalar_one() or 0)


async def report(
    session: AsyncSession, *, min_fraud: int, min_pricing: int,
) -> list[LineDataSufficiency]:
    results: list[LineDataSufficiency] = []
    for line in InsuranceLine:
        fraud = await _count(session, line, "fraud")
        pricing = await _count(session, line, "pricing")
        results.append(LineDataSufficiency(
            line=line,
            fraud_labeled=fraud,
            pricing_labeled=pricing,
            is_fraud_trainable=fraud >= min_fraud,
            is_pricing_trainable=pricing >= min_pricing,
        ))
    return results


def format_table(results: Iterable[LineDataSufficiency]) -> str:
    lines = [f"{'LINE':<12} {'FRAUD':>8} {'PRICING':>8} {'FRAUD?':>8} {'PRICING?':>10}"]
    for r in results:
        lines.append(
            f"{r.line.value:<12} {r.fraud_labeled:>8} {r.pricing_labeled:>8} "
            f"{'YES' if r.is_fraud_trainable else 'no':>8} "
            f"{'YES' if r.is_pricing_trainable else 'no':>10}"
        )
    return "\n".join(lines)


async def _run(min_fraud: int, min_pricing: int) -> list[LineDataSufficiency]:
    engine = create_async_engine(settings.database_url)
    factory = async_sessionmaker(engine, class_=AsyncSession, expire_on_commit=False)
    try:
        async with factory() as session:
            return await report(session, min_fraud=min_fraud, min_pricing=min_pricing)
    finally:
        await engine.dispose()


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--min-fraud", type=int, default=MIN_FRAUD_SAMPLES_DEFAULT)
    parser.add_argument("--min-pricing", type=int, default=MIN_PRICING_SAMPLES_DEFAULT)
    args = parser.parse_args()

    results = asyncio.run(_run(args.min_fraud, args.min_pricing))
    print(format_table(results))


if __name__ == "__main__":
    main()
