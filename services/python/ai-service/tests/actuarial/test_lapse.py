"""Unit tests for the Phase-12 lapse compute.

Mirrors ``test_persistency.py`` — the hand-calculated cases pin the A/E
arithmetic against a small fabricated cohort so a regression in the lapse
math is caught before any Kafka plumbing runs. Also asserts the plan's
by-construction invariant: ``lapse + retention = 1`` when both reports read
the same feed, so the pair stays consistent.
"""

from __future__ import annotations

import math

import pytest

from app.actuarial.lapse import (
    LapseBasisPoint,
    LapseCheckpoint,
    LapseCohort,
    LapseCohortInput,
    compute,
    compute_from_dict,
)


def _cohort(month: str, size: int, checkpoints: list[tuple[int, int]], line: str = "HEALTH"):
    return LapseCohort(
        cohort_month=month,
        insurance_line=line,
        cohort_size=size,
        checkpoints=[LapseCheckpoint(months=m, still_active=s) for m, s in checkpoints],
    )


def _basis(pairs: list[tuple[int, float]]) -> list[LapseBasisPoint]:
    return [LapseBasisPoint(cohort_months=m, expected_retention_pct=p) for m, p in pairs]


def test_ae_ratio_hand_calc():
    # size=100 with (95, 88, 72) still active at (3m, 6m, 12m).
    # lapse = (5, 12, 28) → 0.05, 0.12, 0.28.
    # expected retention = (0.90, 0.85, 0.75) → expected lapse = (0.10, 0.15, 0.25).
    result = compute(LapseCohortInput(
        cohorts=[_cohort("2024-01", 100, [(3, 95), (6, 88), (12, 72)])],
        expected_basis={"HEALTH": _basis([(3, 0.90), (6, 0.85), (12, 0.75)])},
    ))
    rows = result.per_line["HEALTH"]
    assert len(rows) == 3
    assert rows[0].lapsed_count == 5
    assert math.isclose(rows[0].actual_lapse_pct, 0.05)
    assert math.isclose(rows[0].expected_lapse_pct, 0.10, rel_tol=1e-9)
    assert math.isclose(rows[0].ae_ratio, 0.05 / 0.10, rel_tol=1e-6)
    assert math.isclose(rows[1].ae_ratio, 0.12 / 0.15, rel_tol=1e-6)
    assert math.isclose(rows[2].ae_ratio, 0.28 / 0.25, rel_tol=1e-6)


def test_missing_expected_yields_none_ae():
    result = compute(LapseCohortInput(
        cohorts=[_cohort("2024-01", 100, [(3, 95), (9, 80)])],
        expected_basis={"HEALTH": _basis([(3, 0.90)])},  # no 9m basis point
    ))
    rows = result.per_line["HEALTH"]
    assert rows[0].ae_ratio is not None
    assert rows[1].expected_lapse_pct is None
    assert rows[1].ae_ratio is None


def test_full_retention_expected_avoids_divide_by_zero():
    # expected_retention=1.0 → expected_lapse=0.0 → A/E undefined.
    result = compute(LapseCohortInput(
        cohorts=[_cohort("2024-01", 100, [(12, 50)])],
        expected_basis={"HEALTH": _basis([(12, 1.0)])},
    ))
    assert result.per_line["HEALTH"][0].expected_lapse_pct == 0.0
    assert result.per_line["HEALTH"][0].ae_ratio is None


def test_zero_cohort_size_is_warned_and_skipped():
    result = compute(LapseCohortInput(
        cohorts=[
            _cohort("2024-01", 0, [(3, 0)]),
            _cohort("2024-02", 200, [(3, 180)]),
        ],
        expected_basis={"HEALTH": _basis([(3, 0.90)])},
    ))
    assert any("non-positive size" in w for w in result.warnings)
    assert len(result.per_line["HEALTH"]) == 1
    assert result.per_line["HEALTH"][0].cohort_month == "2024-02"


def test_negative_still_active_is_clamped_and_warned():
    result = compute(LapseCohortInput(
        cohorts=[_cohort("2024-01", 100, [(3, -5)])],
        expected_basis={"HEALTH": _basis([(3, 0.90)])},
    ))
    row = result.per_line["HEALTH"][0]
    assert row.lapsed_count == 100  # negative clamped to 0 → all lapsed
    assert math.isclose(row.actual_lapse_pct, 1.0)
    assert any("negative still_active" in w for w in result.warnings)


def test_still_active_over_cohort_size_is_clamped_and_warned():
    result = compute(LapseCohortInput(
        cohorts=[_cohort("2024-01", 100, [(3, 120)])],
        expected_basis={"HEALTH": _basis([(3, 0.90)])},
    ))
    row = result.per_line["HEALTH"][0]
    assert row.lapsed_count == 0
    assert math.isclose(row.actual_lapse_pct, 0.0)
    assert any("still_active > cohort_size" in w for w in result.warnings)


def test_per_line_split_and_sort():
    result = compute(LapseCohortInput(
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
    assert [(r.cohort_month, r.checkpoint_months) for r in health] == [
        ("2024-01", 3), ("2024-01", 6), ("2024-02", 3),
    ]


def test_compute_from_dict_matches_typed():
    payload = {
        "cohorts": [{
            "cohort_month": "2024-01", "insurance_line": "HEALTH",
            "cohort_size": 100,
            "checkpoints": [{"months": 3, "still_active": 90}],
        }],
        "expected_basis": {
            "HEALTH": [{"cohort_months": 3, "expected_retention_pct": 0.90}],
        },
    }
    typed = compute(LapseCohortInput(**payload))
    from_dict = compute_from_dict(payload)
    assert typed.model_dump() == from_dict.model_dump()


def test_no_cohorts_produces_empty_result():
    result = compute(LapseCohortInput(cohorts=[], expected_basis={}))
    assert result.per_line == {}
    assert result.warnings == []


def test_lapse_plus_retention_equals_one():
    """By-construction invariant: reading the same feed twice must give
    persistency A/E + (lapse / expected_lapse) A/E pairs whose actual
    percentages sum to 1.0. Any drift here means one report is fibbing."""
    from app.actuarial.persistency import (
        PersistencyBasisPoint,
        PersistencyCheckpoint,
        PersistencyCohort,
        PersistencyCohortInput,
    )
    from app.actuarial.persistency import compute as compute_persistency

    cohorts = [_cohort("2024-01", 100, [(3, 88), (12, 62)])]
    basis = _basis([(3, 0.90), (12, 0.75)])
    lapse_result = compute(LapseCohortInput(
        cohorts=cohorts, expected_basis={"HEALTH": basis},
    ))
    persistency_result = compute_persistency(PersistencyCohortInput(
        cohorts=[
            PersistencyCohort(
                cohort_month="2024-01",
                insurance_line="HEALTH",
                cohort_size=100,
                checkpoints=[
                    PersistencyCheckpoint(months=3, retained_count=88),
                    PersistencyCheckpoint(months=12, retained_count=62),
                ],
            ),
        ],
        expected_basis={
            "HEALTH": [
                PersistencyBasisPoint(cohort_months=3, expected_retention_pct=0.90),
                PersistencyBasisPoint(cohort_months=12, expected_retention_pct=0.75),
            ],
        },
    ))
    for lap_row, pers_row in zip(
        lapse_result.per_line["HEALTH"], persistency_result.per_line["HEALTH"]
    ):
        assert math.isclose(lap_row.actual_lapse_pct + pers_row.actual_retention_pct, 1.0)
        assert lap_row.expected_lapse_pct is not None and pers_row.expected_retention_pct is not None
        assert math.isclose(
            lap_row.expected_lapse_pct + pers_row.expected_retention_pct, 1.0, rel_tol=1e-9,
        )


def test_result_serializes_json_safely():
    result = compute(LapseCohortInput(
        cohorts=[_cohort("2024-01", 100, [(3, 90)])],
        expected_basis={"HEALTH": _basis([(3, 0.90)])},
    ))
    dumped = result.model_dump()
    # 10/100 actual vs 0.10 expected → A/E = 1.0
    assert dumped["per_line"]["HEALTH"][0]["ae_ratio"] == pytest.approx(1.0, rel=1e-6)
    assert dumped["per_line"]["HEALTH"][0]["lapsed_count"] == 10
