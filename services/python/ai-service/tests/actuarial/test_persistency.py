"""Unit tests for the Phase-11 persistency compute.

The hand-calculated cases pin the A/E arithmetic against a small fabricated
cohort so a regression in the retention math is caught before any Kafka
plumbing runs.
"""

from __future__ import annotations

import math

import pytest

from app.actuarial.persistency import (
    PersistencyBasisPoint,
    PersistencyCheckpoint,
    PersistencyCohort,
    PersistencyCohortInput,
    compute,
    compute_from_dict,
)


def _cohort(month: str, size: int, checkpoints: list[tuple[int, int]], line: str = "HEALTH"):
    return PersistencyCohort(
        cohort_month=month,
        insurance_line=line,
        cohort_size=size,
        checkpoints=[PersistencyCheckpoint(months=m, retained_count=c) for m, c in checkpoints],
    )


def _basis(pairs: list[tuple[int, float]]) -> list[PersistencyBasisPoint]:
    return [PersistencyBasisPoint(cohort_months=m, expected_retention_pct=p) for m, p in pairs]


def test_ae_ratio_hand_calc():
    result = compute(PersistencyCohortInput(
        cohorts=[_cohort("2024-01", 100, [(3, 95), (6, 88), (12, 72)])],
        expected_basis={"HEALTH": _basis([(3, 0.90), (6, 0.85), (12, 0.75)])},
    ))
    rows = result.per_line["HEALTH"]
    assert len(rows) == 3
    assert math.isclose(rows[0].actual_retention_pct, 0.95)
    assert math.isclose(rows[0].expected_retention_pct, 0.90)
    assert math.isclose(rows[0].ae_ratio, 0.95 / 0.90, rel_tol=1e-6)
    assert math.isclose(rows[1].ae_ratio, 0.88 / 0.85, rel_tol=1e-6)
    assert math.isclose(rows[2].ae_ratio, 0.72 / 0.75, rel_tol=1e-6)


def test_missing_expected_yields_none_ae():
    result = compute(PersistencyCohortInput(
        cohorts=[_cohort("2024-01", 100, [(3, 95), (9, 80)])],
        expected_basis={"HEALTH": _basis([(3, 0.90)])},  # no 9m basis point
    ))
    rows = result.per_line["HEALTH"]
    assert rows[0].ae_ratio is not None
    assert rows[1].expected_retention_pct is None
    assert rows[1].ae_ratio is None


def test_zero_expected_avoids_divide_by_zero():
    result = compute(PersistencyCohortInput(
        cohorts=[_cohort("2024-01", 100, [(12, 50)])],
        expected_basis={"HEALTH": _basis([(12, 0.0)])},
    ))
    assert result.per_line["HEALTH"][0].ae_ratio is None


def test_zero_cohort_size_is_warned_and_skipped():
    result = compute(PersistencyCohortInput(
        cohorts=[
            _cohort("2024-01", 0, [(3, 0)]),
            _cohort("2024-02", 200, [(3, 180)]),
        ],
        expected_basis={"HEALTH": _basis([(3, 0.90)])},
    ))
    assert any("non-positive size" in w for w in result.warnings)
    assert len(result.per_line["HEALTH"]) == 1
    assert result.per_line["HEALTH"][0].cohort_month == "2024-02"


def test_negative_retained_is_clamped_and_warned():
    result = compute(PersistencyCohortInput(
        cohorts=[_cohort("2024-01", 100, [(3, -5)])],
        expected_basis={"HEALTH": _basis([(3, 0.90)])},
    ))
    assert result.per_line["HEALTH"][0].retained_count == 0
    assert any("negative retained_count" in w for w in result.warnings)


def test_per_line_split_and_sort():
    result = compute(PersistencyCohortInput(
        cohorts=[
            _cohort("2024-03", 100, [(3, 90)], line="LIFE"),
            _cohort("2024-02", 100, [(3, 92)], line="HEALTH"),
            _cohort("2024-01", 100, [(6, 80), (3, 88)], line="HEALTH"),
        ],
        expected_basis={
            "HEALTH": _basis([(3, 0.90), (6, 0.85)]),
            "LIFE": _basis([(3, 0.92)]),
        },
    ))
    assert set(result.per_line.keys()) == {"HEALTH", "LIFE"}
    health = result.per_line["HEALTH"]
    # sorted by (cohort_month, checkpoint_months)
    assert [(r.cohort_month, r.checkpoint_months) for r in health] == [
        ("2024-01", 3), ("2024-01", 6), ("2024-02", 3),
    ]


def test_compute_from_dict_matches_typed():
    payload = {
        "cohorts": [{
            "cohort_month": "2024-01", "insurance_line": "HEALTH",
            "cohort_size": 100,
            "checkpoints": [{"months": 3, "retained_count": 90}],
        }],
        "expected_basis": {
            "HEALTH": [{"cohort_months": 3, "expected_retention_pct": 0.90}],
        },
    }
    typed = compute(PersistencyCohortInput(**payload))
    from_dict = compute_from_dict(payload)
    assert typed.model_dump() == from_dict.model_dump()


def test_no_cohorts_produces_empty_result():
    result = compute(PersistencyCohortInput(cohorts=[], expected_basis={}))
    assert result.per_line == {}
    assert result.warnings == []


def test_result_serializes_json_safely():
    result = compute(PersistencyCohortInput(
        cohorts=[_cohort("2024-01", 100, [(3, 90)])],
        expected_basis={"HEALTH": _basis([(3, 0.90)])},
    ))
    dumped = result.model_dump()
    # 90/100 actual vs 0.90 expected -> A/E = 1.0
    assert dumped["per_line"]["HEALTH"][0]["ae_ratio"] == pytest.approx(1.0, rel=1e-6)
