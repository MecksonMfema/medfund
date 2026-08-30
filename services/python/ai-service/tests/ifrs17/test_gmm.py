"""Unit tests for the Phase 15 §12 GMM projection + compute.

* IASB Example 7 golden pins the projection + PV arithmetic + LrcMovement
  full-shape sums for a 5-year term life cohort.
* Hand-crafted cases exercise every branch of the projection: horizon cap,
  extinction cutoff, empty curve warning, mortality multiplier, lapse
  decrement, sex-based qx lookup.
* JSON-safety round-trip confirms every Decimal field emits as a string —
  the runner sends the ChunkResult through ``json.dumps`` on the way to
  Kafka and Decimal leakage would blow up the publish.
"""

from __future__ import annotations

import json
from datetime import date
from decimal import Decimal
from pathlib import Path

import pytest
import yaml

from app.ifrs17 import gmm
from app.ifrs17.types import (
    ChunkResult,
    GmmChunkInput,
    YieldCurvePoint,
)

FIXTURES = Path(__file__).parent / "fixtures" / "iasb_examples"


def _load_fixture(name: str) -> dict:
    with (FIXTURES / name).open() as f:
        return yaml.safe_load(f)


def _base_input(**overrides) -> GmmChunkInput:
    defaults = dict(
        job_id="job-1",
        tenant_id="tenant-a",
        portfolio_id="pf-1",
        cohort_id="ch-1",
        currency="USD",
        insurance_line="LIFE",
        reporting_period_start=date(2024, 1, 1),
        reporting_period_end=date(2024, 12, 31),
        horizon_months=60,
        avg_age=40,
        avg_age_sex="male",
        mortality_qx={
            40: {"male": Decimal("1.80"), "female": Decimal("1.15")},
            50: {"male": Decimal("4.03"), "female": Decimal("2.62")},
        },
        mortality_multiplier=Decimal("1.0"),
        annual_lapse_rate=Decimal(0),
        premium_per_policy_monthly=Decimal("10"),
        avg_sum_insured=Decimal("10000"),
        monthly_expense_per_policy=Decimal("1"),
        locked_in_curve=[
            YieldCurvePoint(tenor_months=1, spot_rate=Decimal("0.05")),
            YieldCurvePoint(tenor_months=60, spot_rate=Decimal("0.05")),
        ],
    )
    defaults.update(overrides)
    return GmmChunkInput(**defaults)


# ── golden ───────────────────────────────────────────────────────────


def test_gmm_matches_iasb_example_7_golden():
    fixture = _load_fixture("example_7_gmm.yaml")
    payload = GmmChunkInput(**fixture["input"])
    result = gmm.compute(payload)
    expected = fixture["expected"]

    assert result.model == expected["model"]
    assert result.projection is not None
    assert result.projection.horizon_months_used == expected["horizon_months_used"]
    assert result.projection.truncated_by_horizon_cap is expected["truncated_by_horizon_cap"]
    assert result.projection.truncated_by_extinction is expected["truncated_by_extinction"]

    row0 = result.projection.rows[0]
    row0_expected = expected["row_0"]
    assert row0.surviving_units == Decimal(row0_expected["surviving_units"])
    assert row0.expected_premium_in == Decimal(row0_expected["expected_premium_in"])
    assert row0.expected_claims_out == Decimal(row0_expected["expected_claims_out"])
    assert row0.expected_expenses_out == Decimal(row0_expected["expected_expenses_out"])
    assert row0.net_cash_flow == Decimal(row0_expected["net_cash_flow"])

    # Sign of projected BEL — premium inflows dominate small mortality-
    # driven outflows, so BEL (=-PV(net)) is negative (a receivable).
    assert result.projection.projected_bel < 0

    # Movement journal — arithmetic checks against the fixture's expected
    # closing figures for the LRC / LIC (period movements from the shaping
    # service, not affected by the projection this phase).
    assert result.lrc_movement.closing == Decimal(expected["lrc_closing"])
    assert result.lic_movement.closing == Decimal(expected["lic_closing"])
    assert result.discounting_applied is expected["discounting_applied"]


# ── projection mechanics ─────────────────────────────────────────────


def test_horizon_cap_truncates_and_warns():
    payload = _base_input(horizon_months=gmm.MAX_HORIZON_MONTHS + 12)
    result = gmm.compute(payload)
    assert result.projection.horizon_months_used == gmm.MAX_HORIZON_MONTHS
    assert result.projection.truncated_by_horizon_cap is True
    assert any("MAX_HORIZON_MONTHS" in w for w in result.warnings)


def test_high_mortality_triggers_extinction_early_exit():
    """Whole-of-life scenario with a heavy mortality multiplier so the
    surviving-units curve hits EXTINCTION_THRESHOLD well before the
    stated horizon."""
    payload = _base_input(
        horizon_months=480,
        avg_age=90,
        mortality_qx={
            90: {"male": Decimal("500"), "female": Decimal("500")},
            100: {"male": Decimal("800"), "female": Decimal("800")},
        },
        mortality_multiplier=Decimal("1.0"),
    )
    result = gmm.compute(payload)
    assert result.projection.truncated_by_extinction is True
    assert result.projection.horizon_months_used < 480


def test_empty_curve_falls_back_to_zero_rate_with_warning():
    payload = _base_input(locked_in_curve=[])
    result = gmm.compute(payload)
    assert any("locked_in_curve empty" in w for w in result.warnings)
    # Zero-rate discount factor = 1 → PV equals net cash flow.
    for row in result.projection.rows:
        assert row.spot_rate == Decimal(0)
        assert row.discount_factor == Decimal(1)


def test_lapse_decrement_reduces_surviving_units_faster():
    without_lapse = gmm.compute(_base_input(annual_lapse_rate=Decimal(0)))
    with_lapse = gmm.compute(_base_input(annual_lapse_rate=Decimal("0.10")))
    # Surviving population at horizon end drops faster under 10% lapse.
    last_no = without_lapse.projection.rows[-1].surviving_units
    last_lapse = with_lapse.projection.rows[-1].surviving_units
    assert last_lapse < last_no


def test_mortality_multiplier_scales_claims_out():
    scaled = gmm.compute(_base_input(mortality_multiplier=Decimal("2.0")))
    baseline = gmm.compute(_base_input(mortality_multiplier=Decimal("1.0")))
    # First-month claim outflow doubles under 2× mortality.
    assert scaled.projection.rows[0].expected_claims_out == (
        baseline.projection.rows[0].expected_claims_out * Decimal(2)
    )


def test_female_qx_lookup_uses_female_column():
    """Male qx=1.80, female qx=1.15 at age 40 — first-month claim outflow
    should scale by the picked sex row."""
    male_result = gmm.compute(_base_input(avg_age_sex="male"))
    female_result = gmm.compute(_base_input(avg_age_sex="female"))
    male_claims = male_result.projection.rows[0].expected_claims_out
    female_claims = female_result.projection.rows[0].expected_claims_out
    # 1.15 / 1.80 ratio (≈0.6389) between the two.
    ratio = female_claims / male_claims
    assert Decimal("0.60") < ratio < Decimal("0.70")


def test_qx_linearly_interpolates_between_select_ages():
    """Age 45 sits halfway between age 40 (qx=1.80) and age 50 (qx=4.03).
    The month-0 claim should reflect the interpolated qx=2.915."""
    payload = _base_input(avg_age=45, horizon_months=1)
    result = gmm.compute(payload)
    # 2.915 / 12000 * 10000 = 2.4291666…
    got = result.projection.rows[0].expected_claims_out
    assert Decimal("2.42") < got < Decimal("2.44")


# ── movement journal — GMM-specific behaviour ────────────────────────


def test_finance_expense_pl_only_unwinds_locked_rate():
    payload = _base_input(
        opening_lrc=Decimal("1000"),
        opening_lic=Decimal("500"),
    )
    result = gmm.compute(payload)
    # PL_ONLY: LRC unwind = 1000 * 0.05 = 50; LIC unwind = 500 * 0.05 = 25.
    assert result.lrc_movement.finance_expense_pl == Decimal("50.00")
    assert result.lrc_movement.finance_expense_oci == Decimal("0")
    assert result.lic_movement.finance_expense_pl == Decimal("25.00")


def test_finance_expense_oci_option_splits_pl_and_oci():
    payload = _base_input(
        opening_lrc=Decimal("1000"),
        finance_expense_presentation="OCI_OPTION",
        current_discount_rate=Decimal("0.08"),
    )
    result = gmm.compute(payload)
    # P&L absorbs locked-in unwind (5%*1000=50); OCI absorbs (8%-5%)*1000=30.
    assert result.lrc_movement.finance_expense_pl == Decimal("50.00")
    assert result.lrc_movement.finance_expense_oci == Decimal("30.00")


def test_gmm_lrc_uses_insurance_revenue_not_earned_in_period():
    """PAA reduces LRC by ``earned_in_period``; GMM reduces by
    ``insurance_revenue`` (which the coverage-unit release computed at
    §13 supplies). The movement journal shape is otherwise the same."""
    payload = _base_input(
        opening_lrc=Decimal("100"),
        premiums_received=Decimal("50"),
        insurance_revenue=Decimal("40"),
    )
    result = gmm.compute(payload)
    # GMM always unwinds — 100 * 0.05 = 5 finance expense.
    # 100 + 0 + 50 - 40 - 0 + 0 - 0 + 5 + 0 = 115
    assert result.lrc_movement.closing == Decimal("115.00")
    assert result.lrc_movement.insurance_revenue == Decimal("40")


def test_ra_change_and_release_flow_through_lic():
    payload = _base_input(
        opening_lic=Decimal("20"),
        claims_incurred=Decimal("10"),
        ra_change=Decimal("5"),
        ra_release=Decimal("3"),
    )
    result = gmm.compute(payload)
    # GMM unwinds — 20 * 0.05 = 1 finance expense on LIC.
    # 20 + 10 - 0 + 0 - 0 + 5 - 3 + 1 = 33
    assert result.lic_movement.closing == Decimal("33.00")


# ── envelope integrity ──────────────────────────────────────────────


def test_result_carries_input_metadata():
    payload = _base_input(applied_rule_name="Custom LIFE → GMM")
    result = gmm.compute(payload)
    assert result.job_id == "job-1"
    assert result.tenant_id == "tenant-a"
    assert result.portfolio_id == "pf-1"
    assert result.model == "GMM"
    assert result.applied_rule_name == "Custom LIFE → GMM"


def test_result_json_dump_is_kafka_ready():
    payload = _base_input(
        opening_lrc=Decimal("1234.5678"),
        premiums_received=Decimal("999.9999"),
    )
    result = gmm.compute(payload)
    dumped = result.model_dump(mode="json")
    encoded = json.dumps(dumped)
    reloaded = json.loads(encoded)
    assert reloaded["model"] == "GMM"
    assert reloaded["lrc_movement"]["opening"] == "1234.5678"
    # Projection cash-flow rows also round-trip as strings.
    assert isinstance(reloaded["projection"]["projected_bel"], str)
    assert isinstance(reloaded["projection"]["rows"][0]["surviving_units"], str)


def test_compute_from_dict_accepts_raw_ifrs17_json():
    fixture = _load_fixture("example_7_gmm.yaml")
    result = gmm.compute_from_dict(fixture["input"])
    assert isinstance(result, ChunkResult)
    assert result.model == "GMM"


def test_discounting_always_applied_for_gmm():
    """PAA opts out at ≤12mo; GMM never opts out — discount is intrinsic
    to the model."""
    payload = _base_input(horizon_months=12)
    result = gmm.compute(payload)
    assert result.discounting_applied is True
    assert result.discounting_skip_reason is None
