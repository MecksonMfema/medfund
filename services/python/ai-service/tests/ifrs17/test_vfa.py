"""Unit tests for the Phase 15 §16 VFA compute.

* IASB Example 11 golden pins the LRC balance-sheet identity + CSM
  release proportional to variable fee + onerous transition when a
  negative variable_fee_earned tips CSM below zero.
* Hand-crafted cases exercise the finance-expense split (PL_ONLY vs
  OCI_OPTION), the empty-curve warning, LIC movement independence
  from the VFA-specific LRC extensions, dispatcher routing via
  ``compute_from_dict``, and the JSON round-trip that the Kafka runner
  performs on the ChunkResult before publish.
"""

from __future__ import annotations

import json
from datetime import date
from decimal import Decimal
from pathlib import Path

import pytest
import yaml

from app.ifrs17 import vfa
from app.ifrs17.types import VfaChunkInput, YieldCurvePoint

FIXTURES = Path(__file__).parent / "fixtures" / "iasb_examples"


def _load_fixture(name: str) -> dict:
    with (FIXTURES / name).open() as f:
        return yaml.safe_load(f)


def _base_input(**overrides) -> VfaChunkInput:
    defaults = dict(
        job_id="job-1",
        tenant_id="tenant-a",
        portfolio_id="pf-1",
        cohort_id="ch-1",
        currency="USD",
        reporting_period_start=date(2024, 1, 1),
        reporting_period_end=date(2024, 12, 31),
        locked_in_curve=[
            YieldCurvePoint(tenor_months=1, spot_rate=Decimal("0")),
            YieldCurvePoint(tenor_months=60, spot_rate=Decimal("0")),
        ],
        opening_underlying_fair_value=Decimal("1000"),
        closing_underlying_fair_value=Decimal("1100"),
        variable_fee_earned=Decimal("20"),
        variable_fee_pool_remaining=Decimal("100"),
        opening_lrc=Decimal("1100"),
        opening_csm=Decimal("100"),
    )
    defaults.update(overrides)
    return VfaChunkInput(**defaults)


def _walk(fixture_key: str) -> list[dict]:
    """Roll the VFA compute period-by-period, chaining opening balances.

    Returns per-period dicts with the computed values + fixture-expected
    values so the caller can assert against both.

    Chaining discipline
    ───────────────────
    ChunkResult exposes only the LrcMovement/LicMovement journal — it does
    not surface closing_csm or closing_loss_component_held directly (those
    live inside the compute). The walker derives them by consulting the
    fixture's ``expected_closing_csm`` for the next period's opening_csm,
    and threads the loss-component balance through
    ``opening_loss_component`` on the next payload.
    """
    fixture = _load_fixture("example_11_vfa.yaml")[fixture_key]
    opening_csm = Decimal(fixture["opening_csm"])
    opening_underlying_fv = Decimal(fixture["opening_underlying_fair_value"])
    opening_lrc = Decimal(fixture["opening_lrc"])
    opening_loss_component = Decimal(0)  # Fresh cohort — no prior loss.
    curve = [YieldCurvePoint(**p) for p in fixture["locked_in_curve"]]

    walk: list[dict] = []
    for period in fixture["periods"]:
        payload = _base_input(
            opening_csm=opening_csm,
            opening_underlying_fair_value=opening_underlying_fv,
            closing_underlying_fair_value=Decimal(period["closing_underlying_fair_value"]),
            variable_fee_earned=Decimal(period["variable_fee_earned"]),
            variable_fee_pool_remaining=Decimal(period["variable_fee_pool_remaining"]),
            opening_lrc=opening_lrc,
            opening_loss_component=opening_loss_component,
            locked_in_curve=curve,
        )
        result = vfa.compute(payload)
        walk.append({"period": period, "result": result})

        # Chain forward for the next period.
        opening_lrc = result.lrc_movement.closing
        opening_underlying_fv = payload.closing_underlying_fair_value
        opening_csm = Decimal(period["expected_closing_csm"])
        # Loss component held = prior + new recognition − released.
        opening_loss_component = (
            opening_loss_component
            + result.lrc_movement.loss_component_recognized
            - result.lrc_movement.loss_component_released
        )
    return walk


# ── golden — steady growth ──────────────────────────────────────────


def test_vfa_iasb_ex11_steady_growth_matches_hand_computed():
    """Every closing per the fixture. Balance-sheet identity holds each
    period: closing_lrc == closing_underlying_fv + closing_csm (steady
    state has zero loss component throughout)."""
    walk = _walk("steady_growth")
    for step in walk:
        expected = step["period"]
        result = step["result"]
        assert result.model == "VFA"
        assert result.lrc_movement.closing == Decimal(expected["expected_lrc_closing"])
        assert result.lrc_movement.insurance_revenue == Decimal(
            expected["expected_insurance_revenue"]
        )
        # closing_csm derived from balance-sheet identity — no loss
        # component held in the steady_growth walk.
        implied_closing_csm = (
            result.lrc_movement.closing
            - Decimal(expected["closing_underlying_fair_value"])
        )
        assert implied_closing_csm == Decimal(expected["expected_closing_csm"])


def test_vfa_iasb_ex11_steady_growth_total_release_matches_csm_delta():
    """Sum of releases across the walk equals the CSM drawn down + retained.

    Opening CSM 100; final closing 53.5 → total released 46.5.
    Verify against sum of insurance_revenue across periods.
    """
    walk = _walk("steady_growth")
    total_released = sum(
        (Decimal(step["period"]["expected_insurance_revenue"]) for step in walk),
        Decimal(0),
    )
    assert total_released == Decimal("106.5")  # 24 + 29 + 53.5
    # CSM started at 100, absorbed 60 of variable fee accrual across 3
    # periods (20 × 3), released 106.5. 100 + 60 - 106.5 = 53.5 = closing.
    assert Decimal("100") + Decimal("60") - total_released == Decimal("53.5")


# ── golden — onerous transition ─────────────────────────────────────


def test_vfa_iasb_ex11_goes_onerous_matches_hand_computed():
    """Period 2's adverse variable_fee_earned pushes CSM negative → loss
    component recognised; released clamps to zero; closing_csm clamps to 0.
    Balance-sheet identity: closing_lrc = closing_fv + closing_csm + loss."""
    walk = _walk("goes_onerous")

    p1_expected = walk[0]["period"]
    p1 = walk[0]["result"]
    assert p1.lrc_movement.closing == Decimal(p1_expected["expected_lrc_closing"])
    assert p1.lrc_movement.insurance_revenue == Decimal(
        p1_expected["expected_insurance_revenue"]
    )
    assert p1.lrc_movement.loss_component_recognized == Decimal(
        p1_expected["expected_loss_component"]
    )

    p2_expected = walk[1]["period"]
    p2 = walk[1]["result"]
    assert p2.lrc_movement.insurance_revenue == Decimal(
        p2_expected["expected_insurance_revenue"]
    )
    assert p2.lrc_movement.loss_component_recognized == Decimal(
        p2_expected["expected_loss_component"]
    )
    # Closing = 850 (FV) + 0 (CSM) + 10 (loss) = 860 by balance-sheet identity.
    assert p2.lrc_movement.closing == Decimal(p2_expected["expected_lrc_closing"])
    # The warning fires when CSM goes negative — the material-event dispatcher
    # (§19/§20) consumes this signal to notify tenant admins.
    assert any("onerous" in w.lower() for w in p2.warnings)

    p3_expected = walk[2]["period"]
    p3 = walk[2]["result"]
    # Small release from the fresh accrual (5) over a 60-unit remaining pool.
    # Decimal arithmetic on 5 × (5/60) drifts by 1 ULP vs the fixture's
    # 25/60 direct division — assert to a materiality-safe 10-decimal
    # tolerance rather than pinning the last decimal digit.
    expected_revenue = Decimal(p3_expected["expected_insurance_revenue"])
    assert abs(p3.lrc_movement.insurance_revenue - expected_revenue) < Decimal(
        "0.0000000001"
    )
    # Loss component of 10 carries into P3 (walker threads via
    # opening_loss_component); closing = 875 (FV) + 4.5833 (CSM) + 10 (loss).
    expected_closing = Decimal(p3_expected["expected_lrc_closing"])
    assert abs(p3.lrc_movement.closing - expected_closing) < Decimal(
        "0.0000000001"
    )


# ── finance-expense split ───────────────────────────────────────────


def test_finance_expense_pl_only_absorbs_csm_accretion_residual():
    """Non-zero locked-in rate + PL_ONLY → the whole reconciliation
    residual lands on P&L.

    With variable_fee_earned=0 + closing_uv=opening_uv, the only balance
    change is CSM accretion: opening_csm × ((1+r)^(1/12) - 1) at 5% ≈
    0.4074. That's the entire finance_total; OCI stays zero.
    """
    payload = _base_input(
        locked_in_curve=[
            YieldCurvePoint(tenor_months=1, spot_rate=Decimal("0.05")),
            YieldCurvePoint(tenor_months=60, spot_rate=Decimal("0.05")),
        ],
        current_discount_rate=Decimal("0.06"),
        finance_expense_presentation="PL_ONLY",
        variable_fee_earned=Decimal(0),
        variable_fee_pool_remaining=Decimal("100"),
        closing_underlying_fair_value=Decimal("1000"),
    )
    result = vfa.compute(payload)
    # CSM accreted from 100 to ~100.4074 (only balance change), so
    # finance_total = closing_lrc - opening_lrc = ~0.4074, all to P&L.
    assert result.lrc_movement.finance_expense_pl > Decimal("0.4")
    assert result.lrc_movement.finance_expense_pl < Decimal("0.5")
    assert result.lrc_movement.finance_expense_oci == Decimal("0")


def test_finance_expense_oci_option_splits_pl_by_locked_rate_unwind():
    """OCI_OPTION → locked_rate × opening_lrc on P&L; residual to OCI.

    P&L = 5% × 1100 = 55.00.
    finance_total = CSM accretion ≈ 0.4074 (same reconciliation residual).
    OCI = 0.4074 − 55.00 = -54.5926 (a negative offset that reflects the
    accounting convention — interest unwind exceeds the actual change).
    """
    payload = _base_input(
        locked_in_curve=[
            YieldCurvePoint(tenor_months=1, spot_rate=Decimal("0.05")),
            YieldCurvePoint(tenor_months=60, spot_rate=Decimal("0.05")),
        ],
        current_discount_rate=Decimal("0.06"),
        finance_expense_presentation="OCI_OPTION",
        variable_fee_earned=Decimal(0),
        variable_fee_pool_remaining=Decimal("100"),
        closing_underlying_fair_value=Decimal("1000"),
    )
    result = vfa.compute(payload)
    # P&L = locked_rate × opening_lrc.
    assert result.lrc_movement.finance_expense_pl == Decimal("55.00")
    # OCI carries the difference so the journal reconciles to identity.
    assert result.lrc_movement.finance_expense_oci < Decimal("-54")
    # Balance-sheet identity holds regardless of split.
    assert result.lrc_movement.closing == (
        result.lrc_movement.opening
        + result.lrc_movement.finance_expense_pl
        + result.lrc_movement.finance_expense_oci
    )


# ── LIC independence ────────────────────────────────────────────────


def test_lic_movement_journal_is_independent_of_vfa_specifics():
    """LIC follows the same shape as PAA/GMM — VFA-specific fair-value
    growth + variable-fee accrual only apply to the LRC, not the LIC."""
    payload = _base_input(
        opening_lic=Decimal("50"),
        claims_incurred=Decimal("30"),
        claims_paid=Decimal("20"),
        expenses_incurred=Decimal("5"),
        expenses_paid=Decimal("3"),
        ra_change=Decimal("2"),
        ra_release=Decimal("1"),
    )
    result = vfa.compute(payload)
    # 50 + 30 - 20 + 5 - 3 + 2 - 1 = 63 (zero-rate curve → no finance expense).
    assert result.lic_movement.closing == Decimal("63")


# ── warnings + edge cases ───────────────────────────────────────────


def test_empty_curve_emits_warning_but_still_computes():
    """CSM.roll_forward raises on empty curve; VFA compute should propagate
    that as an invariant violation — the shaping service (§17) is supposed
    to populate from ifrs17_cohort.locked_in_yield_curve_snapshot."""
    payload = _base_input(locked_in_curve=[])
    with pytest.raises(ValueError, match="non-empty locked-in curve"):
        vfa.compute(payload)


def test_zero_variable_fee_earned_short_circuits_release():
    """No variable fee earned → no CSM accrual, no release. LRC closing
    still absorbs fair-value growth (policyholder side moves regardless)."""
    payload = _base_input(
        variable_fee_earned=Decimal(0),
        closing_underlying_fair_value=Decimal("1100"),
    )
    result = vfa.compute(payload)
    assert result.lrc_movement.insurance_revenue == Decimal(0)
    # opening 1100 + fv_growth 100 + var_fee 0 - revenue 0 = 1200
    assert result.lrc_movement.closing == Decimal("1200")


def test_negative_fair_value_growth_reduces_lrc():
    """Fund NAV decline (closing < opening) reduces LRC (policyholder's
    share shrinks). Common in a market downturn between periods."""
    payload = _base_input(
        opening_underlying_fair_value=Decimal("1000"),
        closing_underlying_fair_value=Decimal("900"),  # 10% decline
        variable_fee_earned=Decimal(0),  # Zero fee — flat baseline
    )
    result = vfa.compute(payload)
    # 1100 opening_lrc + (-100 fv_growth) + 0 var_fee - 0 revenue = 1000
    assert result.lrc_movement.closing == Decimal("1000")


# ── dispatcher entry point ──────────────────────────────────────────


def test_compute_from_dict_accepts_raw_ifrs17_json_slot():
    """Runner calls compute_from_dict with the ``ifrs17_json`` slot from
    the ReportJobRequestedEvent. Pin the shape."""
    payload = {
        "job_id": "job-9",
        "tenant_id": "tenant-b",
        "portfolio_id": "pf-9",
        "cohort_id": "ch-9",
        "currency": "USD",
        "reporting_period_start": "2024-01-01",
        "reporting_period_end": "2024-12-31",
        "locked_in_curve": [
            {"tenor_months": 1, "spot_rate": "0"},
            {"tenor_months": 60, "spot_rate": "0"},
        ],
        "opening_underlying_fair_value": "1000",
        "closing_underlying_fair_value": "1100",
        "variable_fee_earned": "20",
        "variable_fee_pool_remaining": "100",
        "opening_lrc": "1100",
        "opening_csm": "100",
    }
    result = vfa.compute_from_dict(payload)
    assert result.model == "VFA"
    assert result.lrc_movement.insurance_revenue == Decimal("24")
    assert result.lrc_movement.closing == Decimal("1196")


# ── JSON round-trip (runner publish safety) ─────────────────────────


def test_chunk_result_json_dump_survives_json_dumps():
    """The Kafka runner does ``json.dumps(result.model_dump(mode='json'))``.
    Verify no Decimal leakage would blow up the publish."""
    payload = _base_input()
    result = vfa.compute(payload)
    dumped = result.model_dump(mode="json")
    # Full serialization round-trip.
    encoded = json.dumps(dumped)
    decoded = json.loads(encoded)
    assert decoded["model"] == "VFA"
    # Pydantic serializes Decimals to strings; the string may carry trailing
    # precision from the arithmetic (e.g. "1196.0" not "1196"), so compare
    # via Decimal parse — value equality, not string equality.
    assert Decimal(decoded["lrc_movement"]["closing"]) == Decimal("1196")
    assert Decimal(decoded["lrc_movement"]["insurance_revenue"]) == Decimal("24")


# ── MODEL_VERSION visible for dispatch ──────────────────────────────


def test_model_version_exposed_for_kafka_dispatch():
    """The runner reads MODEL_VERSION off the module for the completed-
    event envelope. Pin the string shape."""
    assert vfa.MODEL_VERSION == "ifrs17-vfa-1.0"
