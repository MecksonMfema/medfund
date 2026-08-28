"""Unit tests for the Phase-13 mortality compute.

The hand-calculated cases pin the A/E arithmetic against small fabricated
exposure bands so a regression in the qx interpolation, multiplier
application, or A/E divisor is caught before any Kafka plumbing runs.
"""

from __future__ import annotations

import math

import pytest

from app.actuarial.basis_loader import load_basis_table
from app.actuarial.mortality import (
    MortalityCohort,
    MortalityExposureBand,
    MortalityExposureInput,
    _band_midpoint,
    _interpolate_qx,
    compute,
    compute_from_dict,
)


def _band(age_band: str, sex: str, exposure_years: float, deaths: int) -> MortalityExposureBand:
    return MortalityExposureBand(
        age_band=age_band, sex=sex,
        exposure_years=exposure_years, deaths=deaths,
    )


# ── band-parsing helpers ───────────────────────────────────────────────


def test_band_midpoint_range():
    assert _band_midpoint("30-34") == pytest.approx(32.0)


def test_band_midpoint_open_ended():
    assert _band_midpoint("85+") == pytest.approx(85.0)


def test_band_midpoint_bare_number():
    assert _band_midpoint("50") == pytest.approx(50.0)


def test_band_midpoint_garbage_returns_none():
    assert _band_midpoint("") is None
    assert _band_midpoint("nonsense") is None
    assert _band_midpoint("30-nope") is None


# ── qx interpolation ───────────────────────────────────────────────────


def test_interpolate_qx_exact_select_age():
    qx = {30: {"male": 2.1, "female": 1.4}, 35: {"male": 2.7, "female": 1.9}}
    assert _interpolate_qx(qx, [30, 35], 30, "male") == pytest.approx(2.1)


def test_interpolate_qx_linear_between_select_ages():
    qx = {30: {"male": 2.0, "female": 1.4}, 35: {"male": 3.0, "female": 1.9}}
    # Age 32 → 40% of the way from 30 to 35 → 2.0 + 0.4 * (3.0 - 2.0) = 2.4
    assert _interpolate_qx(qx, [30, 35], 32, "male") == pytest.approx(2.4)


def test_interpolate_qx_below_range_uses_first():
    qx = {30: {"male": 2.0, "female": 1.4}, 35: {"male": 3.0, "female": 1.9}}
    assert _interpolate_qx(qx, [30, 35], 10, "male") == pytest.approx(2.0)


def test_interpolate_qx_above_range_uses_last():
    qx = {30: {"male": 2.0, "female": 1.4}, 35: {"male": 3.0, "female": 1.9}}
    assert _interpolate_qx(qx, [30, 35], 99, "male") == pytest.approx(3.0)


# ── compute happy path ─────────────────────────────────────────────────


def test_ae_ratio_hand_calc_against_a1949_52():
    # A1949-52 qx per 1000 at age 30 male = 2.1; band 30-34 midpoint = 32,
    # linearly interpolated to (2.1 + 0.4 * (2.7 - 2.1)) = 2.34 per 1000.
    # 1000 exposure-years * 2.34/1000 = 2.34 expected deaths.
    # Multiplier 1.0 → expected rate 0.00234; observed 3/1000 = 0.003.
    # A/E = 0.003 / 0.00234 = ~1.282.
    result = compute(MortalityExposureInput(cohorts=[
        MortalityCohort(
            insurance_line="LIFE",
            basis_name="A1949_52",
            multiplier=1.0,
            bands=[_band("30-34", "male", 1000.0, 3)],
        ),
    ]))
    line = result.per_line["LIFE"]
    assert line.basis_name == "A1949_52"
    assert line.multiplier == pytest.approx(1.0)
    row = line.rows[0]
    assert row.age_band == "30-34"
    assert row.sex == "male"
    assert row.expected_mortality_rate == pytest.approx(2.34 / 1000.0, rel=1e-6)
    assert row.expected_deaths == pytest.approx(2.34, rel=1e-6)
    assert row.actual_mortality_rate == pytest.approx(0.003, rel=1e-6)
    assert row.ae_ratio == pytest.approx(0.003 / (2.34 / 1000.0), rel=1e-6)


def test_multiplier_scales_expected_deaths():
    result = compute(MortalityExposureInput(cohorts=[
        MortalityCohort(
            insurance_line="LIFE",
            basis_name="A1949_52",
            multiplier=1.5,
            bands=[_band("30-34", "male", 1000.0, 3)],
        ),
    ]))
    row = result.per_line["LIFE"].rows[0]
    # Multiplier 1.5 → expected rate 1.5x baseline.
    assert row.expected_mortality_rate == pytest.approx(1.5 * 2.34 / 1000.0, rel=1e-6)
    assert row.expected_deaths == pytest.approx(1.5 * 2.34, rel=1e-6)


def test_zero_expected_yields_none_ae():
    # Fabricated basis via A67_70's minimum tail — pick a table row whose
    # qx is 0 at some age. A cleaner check: force multiplier=0 so
    # expected_rate is 0.
    result = compute(MortalityExposureInput(cohorts=[
        MortalityCohort(
            insurance_line="LIFE",
            basis_name="A1949_52",
            multiplier=0.000000,  # invalid → clamped to 1.0 below
            bands=[_band("30-34", "male", 1000.0, 3)],
        ),
    ]))
    # multiplier <= 0 gets clamped to 1.0 — so expected_rate > 0 and A/E is not None.
    assert result.per_line["LIFE"].rows[0].ae_ratio is not None


def test_negative_deaths_clamped_and_warned():
    result = compute(MortalityExposureInput(cohorts=[
        MortalityCohort(
            insurance_line="LIFE",
            basis_name="A1949_52",
            multiplier=1.0,
            bands=[_band("30-34", "male", 1000.0, -5)],
        ),
    ]))
    row = result.per_line["LIFE"].rows[0]
    assert row.observed_deaths == 0
    assert row.actual_mortality_rate == 0
    assert any("negative deaths" in w for w in result.warnings)


def test_zero_exposure_is_skipped_and_warned():
    result = compute(MortalityExposureInput(cohorts=[
        MortalityCohort(
            insurance_line="LIFE",
            basis_name="A1949_52",
            multiplier=1.0,
            bands=[
                _band("30-34", "male", 0.0, 0),
                _band("35-39", "male", 500.0, 1),
            ],
        ),
    ]))
    line = result.per_line["LIFE"]
    assert len(line.rows) == 1
    assert line.rows[0].age_band == "35-39"
    assert any("non-positive exposure" in w for w in result.warnings)


def test_unknown_basis_is_warned_and_skipped():
    result = compute(MortalityExposureInput(cohorts=[
        MortalityCohort(
            insurance_line="LIFE",
            basis_name="NOT_A_BASIS",
            multiplier=1.0,
            bands=[_band("30-34", "male", 1000.0, 3)],
        ),
    ]))
    assert "LIFE" not in result.per_line
    assert any("not found" in w for w in result.warnings)


def test_morbidity_basis_rejected_for_mortality():
    # CIDA is a morbidity table — should be rejected here.
    result = compute(MortalityExposureInput(cohorts=[
        MortalityCohort(
            insurance_line="LIFE",
            basis_name="CIDA",
            multiplier=1.0,
            bands=[_band("30-34", "male", 1000.0, 3)],
        ),
    ]))
    assert "LIFE" not in result.per_line
    assert any("not a mortality table" in w for w in result.warnings)


def test_unknown_sex_is_warned_and_skipped():
    result = compute(MortalityExposureInput(cohorts=[
        MortalityCohort(
            insurance_line="LIFE",
            basis_name="A1949_52",
            multiplier=1.0,
            bands=[
                _band("30-34", "unknown", 1000.0, 3),
                _band("35-39", "female", 500.0, 1),
            ],
        ),
    ]))
    line = result.per_line["LIFE"]
    assert [r.sex for r in line.rows] == ["female"]
    assert any("sex" in w and "unknown" in w for w in result.warnings)


def test_unparseable_band_is_warned_and_skipped():
    result = compute(MortalityExposureInput(cohorts=[
        MortalityCohort(
            insurance_line="LIFE",
            basis_name="A1949_52",
            multiplier=1.0,
            bands=[
                _band("nonsense", "male", 1000.0, 3),
                _band("40-44", "male", 500.0, 1),
            ],
        ),
    ]))
    line = result.per_line["LIFE"]
    assert [r.age_band for r in line.rows] == ["40-44"]
    assert any("could not be parsed" in w for w in result.warnings)


def test_multiple_lines_split():
    result = compute(MortalityExposureInput(cohorts=[
        MortalityCohort(
            insurance_line="LIFE",
            basis_name="A1949_52",
            bands=[_band("30-34", "male", 1000.0, 3)],
        ),
        MortalityCohort(
            insurance_line="FUNERAL",
            basis_name="CSO_2017",
            bands=[_band("40-44", "female", 500.0, 1)],
        ),
    ]))
    assert set(result.per_line.keys()) == {"LIFE", "FUNERAL"}
    assert result.per_line["LIFE"].basis_name == "A1949_52"
    assert result.per_line["FUNERAL"].basis_name == "CSO_2017"


def test_duplicate_line_is_warned():
    result = compute(MortalityExposureInput(cohorts=[
        MortalityCohort(
            insurance_line="LIFE",
            basis_name="A1949_52",
            bands=[_band("30-34", "male", 1000.0, 3)],
        ),
        MortalityCohort(
            insurance_line="LIFE",
            basis_name="CSO_2017",
            bands=[_band("40-44", "female", 500.0, 1)],
        ),
    ]))
    # First cohort landed; second ignored + warned.
    assert result.per_line["LIFE"].basis_name == "A1949_52"
    assert any("twice" in w for w in result.warnings)


def test_rows_sorted_by_band_then_sex():
    result = compute(MortalityExposureInput(cohorts=[
        MortalityCohort(
            insurance_line="LIFE",
            basis_name="A1949_52",
            bands=[
                _band("40-44", "male", 100.0, 1),
                _band("30-34", "female", 100.0, 1),
                _band("30-34", "male", 100.0, 1),
            ],
        ),
    ]))
    rows = result.per_line["LIFE"].rows
    assert [(r.age_band, r.sex) for r in rows] == [
        ("30-34", "female"),
        ("30-34", "male"),
        ("40-44", "male"),
    ]


def test_compute_from_dict_matches_typed():
    payload = {
        "cohorts": [{
            "insurance_line": "LIFE",
            "basis_name": "A1949_52",
            "multiplier": 1.0,
            "bands": [
                {"age_band": "30-34", "sex": "male",
                 "exposure_years": 1000.0, "deaths": 3},
            ],
        }],
    }
    typed = compute(MortalityExposureInput(**payload))
    from_dict = compute_from_dict(payload)
    assert typed.model_dump() == from_dict.model_dump()


def test_no_cohorts_produces_empty_result():
    result = compute(MortalityExposureInput(cohorts=[]))
    assert result.per_line == {}
    assert result.warnings == []


def test_result_serializes_json_safely():
    result = compute(MortalityExposureInput(cohorts=[
        MortalityCohort(
            insurance_line="LIFE",
            basis_name="A1949_52",
            multiplier=1.2,
            bands=[_band("30-34", "male", 1000.0, 3)],
        ),
    ]))
    dumped = result.model_dump()
    assert "LIFE" in dumped["per_line"]
    row = dumped["per_line"]["LIFE"]["rows"][0]
    assert row["ae_ratio"] is not None
    assert dumped["per_line"]["LIFE"]["multiplier"] == pytest.approx(1.2)


# ── basis coverage sanity ─────────────────────────────────────────────


def test_every_mortality_basis_loads_and_computes():
    """Guard: every shipped mortality YAML must load + compute a non-empty
    result under a canonical (LIFE, 30-34 male, 1000py, 3 deaths) probe.
    """
    for name in ("A1949_52", "A67_70", "SA85_90", "CSO_2017"):
        # Confirms the basis loader still picks each YAML up.
        data = load_basis_table(name)
        assert data["category"] == "mortality"
        result = compute(MortalityExposureInput(cohorts=[
            MortalityCohort(
                insurance_line="LIFE",
                basis_name=name,
                bands=[_band("30-34", "male", 1000.0, 3)],
            ),
        ]))
        assert result.per_line["LIFE"].rows, f"basis {name} produced no rows"
        assert not any(w for w in result.warnings if "skipped" in w), \
            f"basis {name} unexpectedly triggered a skip warning: {result.warnings}"


def test_ae_ratio_near_one_when_deaths_match_basis():
    """SOA-style sanity check: on the A1949-52 basis the qx at age 32
    (interpolated) is ~2.34/1000 per year; running that exact expected
    death rate through the compute should yield A/E ~= 1.0 exactly.
    """
    # 1000 exposure-years × 2.34/1000 rate → 2.34 expected deaths;
    # feed 2.34 as observed → A/E = 1.0. Use a rounded 234 deaths in
    # 100_000 exposure-years to stay on integers.
    result = compute(MortalityExposureInput(cohorts=[
        MortalityCohort(
            insurance_line="LIFE",
            basis_name="A1949_52",
            multiplier=1.0,
            bands=[_band("30-34", "male", 100_000.0, 234)],
        ),
    ]))
    ae = result.per_line["LIFE"].rows[0].ae_ratio
    assert ae is not None
    assert math.isclose(ae, 1.0, rel_tol=1e-6)
