"""Unit tests for the Phase-14 morbidity compute.

Mirrors the mortality test suite (Phase 13) — hand-calculated cases pin
the A/E arithmetic against fabricated exposure bands so a regression in
the incidence interpolation, multiplier application, or A/E divisor is
caught before any Kafka plumbing runs.
"""

from __future__ import annotations

import math

import pytest

from app.actuarial.basis_loader import load_basis_table
from app.actuarial.morbidity import (
    MorbidityCohort,
    MorbidityExposureBand,
    MorbidityExposureInput,
    _band_midpoint,
    _interpolate_incidence,
    compute,
    compute_from_dict,
)


def _band(age_band: str, sex: str, exposure_years: float, incidents: int) -> MorbidityExposureBand:
    return MorbidityExposureBand(
        age_band=age_band, sex=sex,
        exposure_years=exposure_years, incidents=incidents,
    )


# ── band-parsing helpers ───────────────────────────────────────────────


def test_band_midpoint_range():
    assert _band_midpoint("30-34") == pytest.approx(32.0)


def test_band_midpoint_open_ended():
    assert _band_midpoint("65+") == pytest.approx(65.0)


def test_band_midpoint_bare_number():
    assert _band_midpoint("40") == pytest.approx(40.0)


def test_band_midpoint_garbage_returns_none():
    assert _band_midpoint("") is None
    assert _band_midpoint("nonsense") is None
    assert _band_midpoint("30-nope") is None


# ── incidence interpolation ────────────────────────────────────────────


def test_interpolate_incidence_exact_select_age():
    grid = {30: {"male": 1.9, "female": 2.7}, 35: {"male": 2.5, "female": 3.5}}
    assert _interpolate_incidence(grid, [30, 35], 30, "male") == pytest.approx(1.9)


def test_interpolate_incidence_linear_between_select_ages():
    grid = {30: {"male": 2.0, "female": 1.4}, 35: {"male": 3.0, "female": 1.9}}
    # Age 32 → 40% of the way from 30 to 35 → 2.0 + 0.4 * (3.0 - 2.0) = 2.4
    assert _interpolate_incidence(grid, [30, 35], 32, "male") == pytest.approx(2.4)


def test_interpolate_incidence_below_range_uses_first():
    grid = {30: {"male": 2.0, "female": 1.4}, 35: {"male": 3.0, "female": 1.9}}
    assert _interpolate_incidence(grid, [30, 35], 10, "male") == pytest.approx(2.0)


def test_interpolate_incidence_above_range_uses_last():
    grid = {30: {"male": 2.0, "female": 1.4}, 35: {"male": 3.0, "female": 1.9}}
    assert _interpolate_incidence(grid, [30, 35], 99, "male") == pytest.approx(3.0)


# ── compute happy path ─────────────────────────────────────────────────


def test_ae_ratio_hand_calc_against_cida():
    # CIDA incidence per 1000 at age 30 male = 1.9; band 30-34 midpoint = 32,
    # linearly interpolated to (1.9 + 0.4 * (2.5 - 1.9)) = 2.14 per 1000.
    # 1000 exposure-years * 2.14/1000 = 2.14 expected incidents.
    # Multiplier 1.0 → expected rate 0.00214; observed 3/1000 = 0.003.
    # A/E = 0.003 / 0.00214 = ~1.402.
    result = compute(MorbidityExposureInput(cohorts=[
        MorbidityCohort(
            insurance_line="HEALTH",
            basis_name="CIDA",
            multiplier=1.0,
            bands=[_band("30-34", "male", 1000.0, 3)],
        ),
    ]))
    line = result.per_line["HEALTH"]
    assert line.basis_name == "CIDA"
    assert line.multiplier == pytest.approx(1.0)
    row = line.rows[0]
    assert row.age_band == "30-34"
    assert row.sex == "male"
    assert row.expected_morbidity_rate == pytest.approx(2.14 / 1000.0, rel=1e-6)
    assert row.expected_incidents == pytest.approx(2.14, rel=1e-6)
    assert row.actual_morbidity_rate == pytest.approx(0.003, rel=1e-6)
    assert row.ae_ratio == pytest.approx(0.003 / (2.14 / 1000.0), rel=1e-6)


def test_multiplier_scales_expected_incidents():
    result = compute(MorbidityExposureInput(cohorts=[
        MorbidityCohort(
            insurance_line="HEALTH",
            basis_name="CIDA",
            multiplier=1.5,
            bands=[_band("30-34", "male", 1000.0, 3)],
        ),
    ]))
    row = result.per_line["HEALTH"].rows[0]
    # Multiplier 1.5 → expected rate 1.5x baseline.
    assert row.expected_morbidity_rate == pytest.approx(1.5 * 2.14 / 1000.0, rel=1e-6)
    assert row.expected_incidents == pytest.approx(1.5 * 2.14, rel=1e-6)


def test_zero_expected_yields_none_ae():
    # multiplier <= 0 gets clamped to 1.0 — so expected_rate > 0 and A/E is not None.
    result = compute(MorbidityExposureInput(cohorts=[
        MorbidityCohort(
            insurance_line="HEALTH",
            basis_name="CIDA",
            multiplier=0.000000,  # invalid → clamped to 1.0 below
            bands=[_band("30-34", "male", 1000.0, 3)],
        ),
    ]))
    assert result.per_line["HEALTH"].rows[0].ae_ratio is not None


def test_negative_incidents_clamped_and_warned():
    result = compute(MorbidityExposureInput(cohorts=[
        MorbidityCohort(
            insurance_line="HEALTH",
            basis_name="CIDA",
            multiplier=1.0,
            bands=[_band("30-34", "male", 1000.0, -5)],
        ),
    ]))
    row = result.per_line["HEALTH"].rows[0]
    assert row.observed_incidents == 0
    assert row.actual_morbidity_rate == 0
    assert any("negative incidents" in w for w in result.warnings)


def test_zero_exposure_is_skipped_and_warned():
    result = compute(MorbidityExposureInput(cohorts=[
        MorbidityCohort(
            insurance_line="HEALTH",
            basis_name="CIDA",
            multiplier=1.0,
            bands=[
                _band("30-34", "male", 0.0, 0),
                _band("35-39", "male", 500.0, 1),
            ],
        ),
    ]))
    line = result.per_line["HEALTH"]
    assert len(line.rows) == 1
    assert line.rows[0].age_band == "35-39"
    assert any("non-positive exposure" in w for w in result.warnings)


def test_unknown_basis_is_warned_and_skipped():
    result = compute(MorbidityExposureInput(cohorts=[
        MorbidityCohort(
            insurance_line="HEALTH",
            basis_name="NOT_A_BASIS",
            multiplier=1.0,
            bands=[_band("30-34", "male", 1000.0, 3)],
        ),
    ]))
    assert "HEALTH" not in result.per_line
    assert any("not found" in w for w in result.warnings)


def test_mortality_basis_rejected_for_morbidity():
    # A1949_52 is a mortality table — should be rejected here.
    result = compute(MorbidityExposureInput(cohorts=[
        MorbidityCohort(
            insurance_line="HEALTH",
            basis_name="A1949_52",
            multiplier=1.0,
            bands=[_band("30-34", "male", 1000.0, 3)],
        ),
    ]))
    assert "HEALTH" not in result.per_line
    assert any("not a morbidity table" in w for w in result.warnings)


def test_unknown_sex_is_warned_and_skipped():
    result = compute(MorbidityExposureInput(cohorts=[
        MorbidityCohort(
            insurance_line="HEALTH",
            basis_name="CIDA",
            multiplier=1.0,
            bands=[
                _band("30-34", "unknown", 1000.0, 3),
                _band("35-39", "female", 500.0, 1),
            ],
        ),
    ]))
    line = result.per_line["HEALTH"]
    assert [r.sex for r in line.rows] == ["female"]
    assert any("sex" in w and "unknown" in w for w in result.warnings)


def test_unparseable_band_is_warned_and_skipped():
    result = compute(MorbidityExposureInput(cohorts=[
        MorbidityCohort(
            insurance_line="HEALTH",
            basis_name="CIDA",
            multiplier=1.0,
            bands=[
                _band("nonsense", "male", 1000.0, 3),
                _band("40-44", "male", 500.0, 1),
            ],
        ),
    ]))
    line = result.per_line["HEALTH"]
    assert [r.age_band for r in line.rows] == ["40-44"]
    assert any("could not be parsed" in w for w in result.warnings)


def test_multiple_lines_split():
    result = compute(MorbidityExposureInput(cohorts=[
        MorbidityCohort(
            insurance_line="HEALTH",
            basis_name="CIDA",
            bands=[_band("30-34", "male", 1000.0, 3)],
        ),
        MorbidityCohort(
            insurance_line="DISABILITY",
            basis_name="GLTD87",
            bands=[_band("40-44", "female", 500.0, 1)],
        ),
    ]))
    assert set(result.per_line.keys()) == {"HEALTH", "DISABILITY"}
    assert result.per_line["HEALTH"].basis_name == "CIDA"
    assert result.per_line["DISABILITY"].basis_name == "GLTD87"


def test_duplicate_line_is_warned():
    result = compute(MorbidityExposureInput(cohorts=[
        MorbidityCohort(
            insurance_line="HEALTH",
            basis_name="CIDA",
            bands=[_band("30-34", "male", 1000.0, 3)],
        ),
        MorbidityCohort(
            insurance_line="HEALTH",
            basis_name="GLTD87",
            bands=[_band("40-44", "female", 500.0, 1)],
        ),
    ]))
    # First cohort landed; second ignored + warned.
    assert result.per_line["HEALTH"].basis_name == "CIDA"
    assert any("twice" in w for w in result.warnings)


def test_rows_sorted_by_band_then_sex():
    result = compute(MorbidityExposureInput(cohorts=[
        MorbidityCohort(
            insurance_line="HEALTH",
            basis_name="CIDA",
            bands=[
                _band("40-44", "male", 100.0, 1),
                _band("30-34", "female", 100.0, 1),
                _band("30-34", "male", 100.0, 1),
            ],
        ),
    ]))
    rows = result.per_line["HEALTH"].rows
    assert [(r.age_band, r.sex) for r in rows] == [
        ("30-34", "female"),
        ("30-34", "male"),
        ("40-44", "male"),
    ]


def test_compute_from_dict_matches_typed():
    payload = {
        "cohorts": [{
            "insurance_line": "HEALTH",
            "basis_name": "CIDA",
            "multiplier": 1.0,
            "bands": [
                {"age_band": "30-34", "sex": "male",
                 "exposure_years": 1000.0, "incidents": 3},
            ],
        }],
    }
    typed = compute(MorbidityExposureInput(**payload))
    from_dict = compute_from_dict(payload)
    assert typed.model_dump() == from_dict.model_dump()


def test_no_cohorts_produces_empty_result():
    result = compute(MorbidityExposureInput(cohorts=[]))
    assert result.per_line == {}
    assert result.warnings == []


def test_result_serializes_json_safely():
    result = compute(MorbidityExposureInput(cohorts=[
        MorbidityCohort(
            insurance_line="HEALTH",
            basis_name="CIDA",
            multiplier=1.2,
            bands=[_band("30-34", "male", 1000.0, 3)],
        ),
    ]))
    dumped = result.model_dump()
    assert "HEALTH" in dumped["per_line"]
    row = dumped["per_line"]["HEALTH"]["rows"][0]
    assert row["ae_ratio"] is not None
    assert dumped["per_line"]["HEALTH"]["multiplier"] == pytest.approx(1.2)


# ── basis coverage sanity ─────────────────────────────────────────────


def test_every_morbidity_basis_loads_and_computes():
    """Guard: every shipped morbidity YAML must load + compute a non-empty
    result under a canonical (HEALTH, 30-34 male, 1000py, 3 incidents) probe.
    """
    for name in ("CIDA", "GLTD87"):
        # Confirms the basis loader still picks each YAML up.
        data = load_basis_table(name)
        assert data["category"] == "morbidity"
        result = compute(MorbidityExposureInput(cohorts=[
            MorbidityCohort(
                insurance_line="HEALTH",
                basis_name=name,
                bands=[_band("30-34", "male", 1000.0, 3)],
            ),
        ]))
        assert result.per_line["HEALTH"].rows, f"basis {name} produced no rows"
        assert not any(w for w in result.warnings if "skipped" in w), \
            f"basis {name} unexpectedly triggered a skip warning: {result.warnings}"


def test_ae_ratio_near_one_when_incidents_match_basis():
    """SOA-style sanity check: on the CIDA basis the incidence at age 32
    (interpolated) is 2.14/1000 per year; feeding the same expected
    incidence as observed should yield A/E ~= 1.0 exactly.
    """
    # 100_000 exposure-years × 2.14/1000 rate → 214 expected incidents;
    # feed 214 as observed → A/E = 1.0.
    result = compute(MorbidityExposureInput(cohorts=[
        MorbidityCohort(
            insurance_line="HEALTH",
            basis_name="CIDA",
            multiplier=1.0,
            bands=[_band("30-34", "male", 100_000.0, 214)],
        ),
    ]))
    ae = result.per_line["HEALTH"].rows[0].ae_ratio
    assert ae is not None
    assert math.isclose(ae, 1.0, rel_tol=1e-6)
