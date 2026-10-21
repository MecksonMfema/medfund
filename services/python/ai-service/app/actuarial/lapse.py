"""Lapse-study compute (Phase 12).

Given a list of cohort observations (cohort size + still-active count at
each checkpoint) and the tenant's expected-retention curves — the same
``tenant_persistency_basis`` rows the persistency study reads — produce
per-line actual-vs-expected lapse data.

The relationship to persistency is deliberate: lapse is the complement
of retention, so ``lapsed_count = cohort_size - still_active`` and
``actual_lapse_pct = 1 - actual_retention_pct``. Expected lapse comes
from the same basis point via ``1 - expected_retention_pct``. Reusing
one basis table keeps "PERSISTENCY_STUDY + LAPSE_STUDY sum to 100 %" a
by-construction invariant, so both reports stay consistent even when
the actuary tweaks the retention curve.

Like persistency, the compute is deterministic + side-effect free — the
Java shaping service in finance-service pre-aggregates the cohort/basis
payload from Phase-13 tables so this module only has to do arithmetic.
"""

from __future__ import annotations

from typing import Any

from pydantic import BaseModel, Field


class LapseCheckpoint(BaseModel):
    months: int
    still_active: int


class LapseCohort(BaseModel):
    cohort_month: str  # ISO YYYY-MM
    insurance_line: str
    cohort_size: int
    checkpoints: list[LapseCheckpoint] = Field(default_factory=list)


class LapseBasisPoint(BaseModel):
    cohort_months: int
    expected_retention_pct: float


class LapseCohortInput(BaseModel):
    cohorts: list[LapseCohort]
    expected_basis: dict[str, list[LapseBasisPoint]] = Field(default_factory=dict)


class LapseRow(BaseModel):
    cohort_month: str
    checkpoint_months: int
    cohort_size: int
    lapsed_count: int
    actual_lapse_pct: float
    expected_lapse_pct: float | None
    ae_ratio: float | None


class LapseResult(BaseModel):
    per_line: dict[str, list[LapseRow]] = Field(default_factory=dict)
    warnings: list[str] = Field(default_factory=list)


def compute(input: LapseCohortInput) -> LapseResult:
    per_line: dict[str, list[LapseRow]] = {}
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
            if cp.still_active < 0:
                warnings.append(
                    f"cohort {cohort.cohort_month} ({line}) checkpoint {cp.months}m "
                    "had negative still_active; clamped to 0"
                )
                still_active = 0
            elif cp.still_active > cohort.cohort_size:
                warnings.append(
                    f"cohort {cohort.cohort_month} ({line}) checkpoint {cp.months}m "
                    "had still_active > cohort_size; clamped to cohort_size"
                )
                still_active = cohort.cohort_size
            else:
                still_active = cp.still_active
            lapsed = cohort.cohort_size - still_active
            actual_pct = lapsed / cohort.cohort_size
            expected_retention = basis_lookup.get(cp.months)
            expected_lapse_pct: float | None
            ae_ratio: float | None
            if expected_retention is None:
                expected_lapse_pct = None
                ae_ratio = None
            else:
                expected_lapse_pct = 1.0 - expected_retention
                if expected_lapse_pct == 0:
                    ae_ratio = None
                else:
                    ae_ratio = actual_pct / expected_lapse_pct
            row = LapseRow(
                cohort_month=cohort.cohort_month,
                checkpoint_months=cp.months,
                cohort_size=cohort.cohort_size,
                lapsed_count=lapsed,
                actual_lapse_pct=actual_pct,
                expected_lapse_pct=expected_lapse_pct,
                ae_ratio=ae_ratio,
            )
            per_line.setdefault(line, []).append(row)

    for line, rows in per_line.items():
        rows.sort(key=lambda r: (r.cohort_month, r.checkpoint_months))

    return LapseResult(per_line=per_line, warnings=warnings)


def compute_from_dict(payload: dict[str, Any]) -> LapseResult:
    """Convenience for the Kafka runner — accepts the raw ``cohort`` slot."""
    return compute(LapseCohortInput(**payload))


__all__ = [
    "LapseBasisPoint",
    "LapseCheckpoint",
    "LapseCohort",
    "LapseCohortInput",
    "LapseResult",
    "LapseRow",
    "compute",
    "compute_from_dict",
]
