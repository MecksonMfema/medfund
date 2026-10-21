"""Persistency-study compute (Phase 11).

Given a list of cohort observations (cohort size + retained-count at each
checkpoint) and the tenant's expected-retention curves, produce per-line
actual-vs-expected retention data. A/E ratios are computed only where the
tenant has published an expected value for that checkpoint; ``None``
propagates through so downstream renderers can render a "no basis" cell
rather than silently divide by zero.

The compute is intentionally deterministic and side-effect free — the
Java shaping service in finance-service pre-aggregates the cohort/basis
payload from Phase-13 tables so this module only has to do arithmetic.
"""

from __future__ import annotations

from typing import Any

from pydantic import BaseModel, Field


class PersistencyCheckpoint(BaseModel):
    months: int
    retained_count: int


class PersistencyCohort(BaseModel):
    cohort_month: str  # ISO YYYY-MM
    insurance_line: str
    cohort_size: int
    checkpoints: list[PersistencyCheckpoint] = Field(default_factory=list)


class PersistencyBasisPoint(BaseModel):
    cohort_months: int
    expected_retention_pct: float


class PersistencyCohortInput(BaseModel):
    cohorts: list[PersistencyCohort]
    expected_basis: dict[str, list[PersistencyBasisPoint]] = Field(default_factory=dict)


class PersistencyRow(BaseModel):
    cohort_month: str
    checkpoint_months: int
    cohort_size: int
    retained_count: int
    actual_retention_pct: float
    expected_retention_pct: float | None
    ae_ratio: float | None


class PersistencyResult(BaseModel):
    per_line: dict[str, list[PersistencyRow]] = Field(default_factory=dict)
    warnings: list[str] = Field(default_factory=list)


def compute(input: PersistencyCohortInput) -> PersistencyResult:
    per_line: dict[str, list[PersistencyRow]] = {}
    warnings: list[str] = []

    for cohort in input.cohorts:
        line = cohort.insurance_line
        basis_lookup = {
            b.cohort_months: b.expected_retention_pct
            for b in input.expected_basis.get(line, [])
        }
        if cohort.cohort_size <= 0:
            warnings.append(
                f"cohort {cohort.cohort_month} ({line}) has non-positive size; skipped"
            )
            continue
        for cp in cohort.checkpoints:
            if cp.retained_count < 0:
                warnings.append(
                    f"cohort {cohort.cohort_month} ({line}) checkpoint {cp.months}m "
                    "had negative retained_count; clamped to 0"
                )
                retained = 0
            else:
                retained = cp.retained_count
            actual_pct = retained / cohort.cohort_size
            expected_pct = basis_lookup.get(cp.months)
            ae_ratio: float | None
            if expected_pct is None or expected_pct == 0:
                ae_ratio = None
            else:
                ae_ratio = actual_pct / expected_pct
            row = PersistencyRow(
                cohort_month=cohort.cohort_month,
                checkpoint_months=cp.months,
                cohort_size=cohort.cohort_size,
                retained_count=retained,
                actual_retention_pct=actual_pct,
                expected_retention_pct=expected_pct,
                ae_ratio=ae_ratio,
            )
            per_line.setdefault(line, []).append(row)

    for line, rows in per_line.items():
        rows.sort(key=lambda r: (r.cohort_month, r.checkpoint_months))

    return PersistencyResult(per_line=per_line, warnings=warnings)


def compute_from_dict(payload: dict[str, Any]) -> PersistencyResult:
    """Convenience for the Kafka runner — accepts the raw ``cohort`` slot."""
    return compute(PersistencyCohortInput(**payload))


__all__ = [
    "PersistencyBasisPoint",
    "PersistencyCheckpoint",
    "PersistencyCohort",
    "PersistencyCohortInput",
    "PersistencyResult",
    "PersistencyRow",
    "compute",
    "compute_from_dict",
]
