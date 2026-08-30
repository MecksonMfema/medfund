"""Unit tests for the Phase 15 §11 PAA compute.

* IASB Example 3 golden pins the LRC/LIC arithmetic + the 17.59 discount
  opt-out at ≤12mo coverage.
* Hand-crafted cases exercise every branch of the finance-expense split,
  the movement-journal path (loss component, acquisition CF, expenses),
  and the discounting decision matrix.
* A ``json.dumps(model_dump(mode="json"))`` round-trip confirms every
  Decimal field emits as a JSON-safe string — the runner sends the
  ChunkResult through ``json.dumps`` on the way to Kafka.
"""

from __future__ import annotations

import json
from datetime import date
from decimal import Decimal
from pathlib import Path

import pytest
import yaml

from app.ifrs17 import paa
from app.ifrs17.types import PaaChunkInput

FIXTURES = Path(__file__).parent / "fixtures" / "iasb_examples"


def _load_fixture(name: str) -> dict:
    with (FIXTURES / name).open() as f:
        return yaml.safe_load(f)


def _base_input(**overrides) -> PaaChunkInput:
    defaults = dict(
        job_id="job-1",
        tenant_id="tenant-a",
        portfolio_id="pf-1",
        cohort_id="ch-1",
        currency="USD",
        reporting_period_start=date(2024, 1, 1),
        reporting_period_end=date(2024, 12, 31),
        earliest_coverage_start=date(2024, 1, 1),
        latest_coverage_end=date(2024, 12, 31),
    )
    defaults.update(overrides)
    return PaaChunkInput(**defaults)


# ── golden ───────────────────────────────────────────────────────────


def test_paa_matches_iasb_example_3_golden():
    fixture = _load_fixture("example_3_paa.yaml")
    payload = PaaChunkInput(**fixture["input"])

    result = paa.compute(payload)
    expected = fixture["expected"]

    assert result.model == expected["model"]
    assert result.lrc_movement.closing == Decimal(expected["lrc_closing"])
    assert result.lic_movement.closing == Decimal(expected["lic_closing"])
    assert result.discounting_applied is expected["discounting_applied"]
    assert result.discounting_skip_reason == expected["discounting_skip_reason"]
    # LRC arithmetic breakdown: opening + new_business + premiums
    # - earned - acquisition_cf + lc_recognized - lc_released + finance
    assert result.lrc_movement.opening == Decimal("100")
    assert result.lrc_movement.insurance_revenue == Decimal("70")
    assert result.lrc_movement.finance_expense_pl == Decimal("0")
    # LIC arithmetic breakdown.
    assert result.lic_movement.opening == Decimal("5")
    assert result.lic_movement.claims_incurred == Decimal("12")
    assert result.lic_movement.claims_paid == Decimal("9")


# ── discounting decision matrix ──────────────────────────────────────


def test_discounting_skipped_when_coverage_and_settlement_le_12mo():
    payload = _base_input(median_settlement_days=60)  # 12mo coverage default
    result = paa.compute(payload)
    assert result.discounting_applied is False
    assert result.discounting_skip_reason == (
        "IFRS_17_59_coverage_and_settlement_le_12mo"
    )


def test_discounting_applied_when_coverage_exceeds_12mo():
    payload = _base_input(
        earliest_coverage_start=date(2024, 1, 1),
        latest_coverage_end=date(2025, 6, 30),  # 18mo
        opening_lrc=Decimal("1000"),
        locked_in_discount_rate=Decimal("0.05"),
    )
    result = paa.compute(payload)
    assert result.discounting_applied is True
    assert result.discounting_skip_reason is None
    # 5% × 1000 opening LRC = 50 to P&L, OCI zero under PL_ONLY.
    assert result.lrc_movement.finance_expense_pl == Decimal("50.00")
    assert result.lrc_movement.finance_expense_oci == Decimal("0")


def test_discounting_applied_when_settlement_exceeds_12mo():
    payload = _base_input(
        median_settlement_days=400,  # >365d ⇒ LIC unwinds
        opening_lic=Decimal("500"),
        locked_in_discount_rate=Decimal("0.04"),
    )
    result = paa.compute(payload)
    assert result.discounting_applied is True
    # LRC still ≤12mo — no LRC unwind — but LIC unwinds.
    assert result.lrc_movement.finance_expense_pl == Decimal("0")
    assert result.lic_movement.finance_expense_pl == Decimal("20.00")


# ── finance expense OCI branch ───────────────────────────────────────


def test_oci_option_splits_finance_expense_between_pl_and_oci():
    payload = _base_input(
        earliest_coverage_start=date(2024, 1, 1),
        latest_coverage_end=date(2026, 12, 31),  # 36mo → discount applies
        finance_expense_presentation="OCI_OPTION",
        opening_lrc=Decimal("1000"),
        locked_in_discount_rate=Decimal("0.05"),
        current_discount_rate=Decimal("0.08"),
    )
    result = paa.compute(payload)
    # P&L absorbs locked-in unwind, OCI absorbs the (current − locked) delta.
    assert result.lrc_movement.finance_expense_pl == Decimal("50.00")
    assert result.lrc_movement.finance_expense_oci == Decimal("30.00")


def test_pl_only_puts_full_unwind_in_pl():
    payload = _base_input(
        earliest_coverage_start=date(2024, 1, 1),
        latest_coverage_end=date(2026, 12, 31),
        opening_lrc=Decimal("1000"),
        locked_in_discount_rate=Decimal("0.05"),
        current_discount_rate=Decimal("0.08"),  # ignored under PL_ONLY
    )
    result = paa.compute(payload)
    assert result.lrc_movement.finance_expense_pl == Decimal("50.00")
    assert result.lrc_movement.finance_expense_oci == Decimal("0")


# ── movement journal — full-shape sums ───────────────────────────────


def test_loss_component_movements_flow_through_lrc():
    payload = _base_input(
        opening_lrc=Decimal("100"),
        earned_in_period=Decimal("20"),
        loss_component_recognized=Decimal("15"),
        loss_component_released=Decimal("5"),
    )
    result = paa.compute(payload)
    # 100 + 0 + 0 - 20 - 0 + 15 - 5 = 90
    assert result.lrc_movement.closing == Decimal("90")


def test_acquisition_cf_reduces_lrc():
    payload = _base_input(
        opening_lrc=Decimal("100"),
        premiums_received=Decimal("50"),
        insurance_acquisition_cf=Decimal("10"),
    )
    result = paa.compute(payload)
    # 100 + 0 + 50 - 0 - 10 = 140
    assert result.lrc_movement.closing == Decimal("140")


def test_ra_change_and_release_flow_through_lic():
    payload = _base_input(
        opening_lic=Decimal("20"),
        claims_incurred=Decimal("10"),
        ra_change=Decimal("5"),
        ra_release=Decimal("3"),
        expenses_incurred=Decimal("2"),
        expenses_paid=Decimal("1"),
    )
    result = paa.compute(payload)
    # 20 + 10 - 0 + 2 - 1 + 5 - 3 = 33
    assert result.lic_movement.closing == Decimal("33")


# ── envelope integrity ──────────────────────────────────────────────


def test_result_carries_input_metadata():
    payload = _base_input(applied_rule_name="Custom HEALTH → PAA")
    result = paa.compute(payload)
    assert result.job_id == "job-1"
    assert result.tenant_id == "tenant-a"
    assert result.portfolio_id == "pf-1"
    assert result.cohort_id == "ch-1"
    assert result.currency == "USD"
    assert result.model == "PAA"
    assert result.applied_rule_name == "Custom HEALTH → PAA"
    assert result.warnings == []


def test_result_json_dump_is_kafka_ready():
    """Decimals must not leak into ``json.dumps`` — the runner serializes
    result envelopes through the aiokafka JSON producer and Decimal would
    raise TypeError. ``model_dump(mode='json')`` converts to strings."""
    payload = _base_input(
        opening_lrc=Decimal("1234.5678"),
        premiums_received=Decimal("999.9999"),
    )
    result = paa.compute(payload)
    # model_dump(mode='json') is what the runner uses.
    dumped = result.model_dump(mode="json")
    encoded = json.dumps(dumped)
    # Round-trip decodes to the same shape.
    reloaded = json.loads(encoded)
    assert reloaded["lrc_movement"]["opening"] == "1234.5678"
    assert reloaded["model"] == "PAA"


# ── input-validation guards ─────────────────────────────────────────


def test_reversed_coverage_dates_do_not_apply_discount():
    """Guard against a shaping bug that puts end < start — the month
    calculation floors at zero, so the ≤12mo opt-out still fires and
    finance expense stays at zero, rather than silently emitting a
    negative unwind."""
    payload = _base_input(
        earliest_coverage_start=date(2024, 12, 31),
        latest_coverage_end=date(2024, 1, 1),
        opening_lrc=Decimal("100"),
        locked_in_discount_rate=Decimal("0.05"),
    )
    result = paa.compute(payload)
    assert result.discounting_applied is False
    assert result.lrc_movement.finance_expense_pl == Decimal("0")
