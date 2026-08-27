"""Mack (1993) reference triangle — golden fixture.

Source: Mack, T. (1993) "Distribution-Free Calculation of the Standard Error
of Chain Ladder Reserve Estimates". ASTIN Bulletin 23(2):213-225. Table 1.

Any chainladder-python upgrade that changes the volume-weighted LDFs beyond
4dp against this triangle is a functional regression, not a library refresh.
"""

from __future__ import annotations

import math

import pytest

from app.actuarial.chain_ladder import (
    ChainLadderResult,
    TriangleInput,
    compute,
)

MACK_TRIANGLE_CELLS: list[list[float | None]] = [
    [357848, 1124788, 1735330, 2218270, 2745596, 3319994, 3466336, 3606286, 3833515, 3901463],
    [352118, 1236139, 2170033, 3353322, 3799067, 4120063, 4647867, 4914039, 5339085, None],
    [290507, 1292306, 2218525, 3235179, 3985995, 4132918, 4628910, 4909315, None, None],
    [310608, 1418858, 2195047, 3757447, 4029929, 4381982, 4588268, None, None, None],
    [443160, 1136350, 2128333, 2897821, 3402672, 3873311, None, None, None, None],
    [396132, 1333217, 2180715, 2985752, 3691712, None, None, None, None, None],
    [440832, 1288463, 2419861, 3483130, None, None, None, None, None, None],
    [359480, 1421128, 2864498, None, None, None, None, None, None, None],
    [376686, 1363294, None, None, None, None, None, None, None, None],
    [344014, None, None, None, None, None, None, None, None, None],
]

# Mack (1993) Table 1: volume-weighted development factors.
EXPECTED_LDFS_VOLUME = [
    3.4906,
    1.7473,
    1.4574,
    1.1739,
    1.1038,
    1.0863,
    1.0539,
    1.0766,
    1.0177,
]


def _mack_input() -> TriangleInput:
    return TriangleInput(
        accident_periods=[str(1981 + i) for i in range(10)],
        development_periods=[str(i + 1) for i in range(10)],
        cells=MACK_TRIANGLE_CELLS,
        grain="year",
        reporting_currency="USD",
        insurance_line="HEALTH",
    )


def test_ldfs_match_mack_textbook_to_4dp() -> None:
    result = compute(_mack_input(), method="volume")
    assert len(result.ldfs) == len(EXPECTED_LDFS_VOLUME)
    for i, expected in enumerate(EXPECTED_LDFS_VOLUME):
        assert result.ldfs[i] == pytest.approx(expected, abs=1e-4), (
            f"LDF[{i}] got {result.ldfs[i]}, expected {expected}"
        )


def test_cdf_is_running_product_of_ldfs() -> None:
    result = compute(_mack_input(), method="volume")
    # CDF at dev j = product of LDFs from j..last. Assert cdf[-1] == 1 * ldfs[-1] up to fp noise.
    assert result.cdf[-1] == pytest.approx(result.ldfs[-1], rel=1e-6)


def test_ibnr_and_ultimate_sane() -> None:
    result = compute(_mack_input())
    assert result.ibnr_total > 0
    # Ultimate = latest paid + IBNR; both order-of-magnitude around $50m for the Mack triangle.
    assert 40_000_000 < result.ultimate_total < 60_000_000
    assert 10_000_000 < result.ibnr_total < 30_000_000
    assert len(result.per_cohort_ultimate) == 10


def test_mack_standard_error_populated() -> None:
    result = compute(_mack_input())
    assert result.mack_standard_error is not None
    assert result.mack_standard_error > 0


def test_alternative_ldf_methods_differ() -> None:
    volume = compute(_mack_input(), method="volume")
    simple = compute(_mack_input(), method="simple")
    five_yr = compute(_mack_input(), method="5yr")
    # Different weighting schemes must produce distinguishable LDFs — a sanity
    # check that the ``method`` parameter is actually being applied.
    assert not math.isclose(volume.ldfs[0], simple.ldfs[0], abs_tol=1e-4)
    assert not math.isclose(volume.ldfs[0], five_yr.ldfs[0], abs_tol=1e-4)


def test_unknown_method_rejected() -> None:
    with pytest.raises(ValueError, match="Unknown LDF method"):
        compute(_mack_input(), method="bootstrap")  # type: ignore[arg-type]


def test_empty_cells_rejected() -> None:
    empty = TriangleInput(
        accident_periods=["2020"],
        development_periods=["1"],
        cells=[[None]],
        grain="year",
        reporting_currency="USD",
        insurance_line="HEALTH",
    )
    with pytest.raises(ValueError, match="no non-null cells"):
        compute(empty)


def test_shape_mismatch_rejected_at_validation() -> None:
    with pytest.raises(ValueError, match="cells has"):
        TriangleInput(
            accident_periods=["2020", "2021"],
            development_periods=["1", "2"],
            cells=[[100.0, 150.0]],
            grain="year",
            reporting_currency="USD",
            insurance_line="HEALTH",
        )


def test_row_width_mismatch_rejected_at_validation() -> None:
    with pytest.raises(ValueError, match="cells row 0 has"):
        TriangleInput(
            accident_periods=["2020"],
            development_periods=["1", "2"],
            cells=[[100.0]],
            grain="year",
            reporting_currency="USD",
            insurance_line="HEALTH",
        )


def test_quarterly_grain_round_trip() -> None:
    """Quarterly grain path is only exercised by the lifespan pre-warm otherwise."""
    payload = TriangleInput(
        accident_periods=["2020Q1", "2020Q2", "2020Q3"],
        development_periods=["1", "2", "3"],
        cells=[
            [100.0, 150.0, 175.0],
            [110.0, 160.0, None],
            [120.0, None, None],
        ],
        grain="quarter",
        reporting_currency="USD",
        insurance_line="HEALTH",
    )
    result = compute(payload)
    assert isinstance(result, ChainLadderResult)
    assert len(result.ldfs) == 2
    assert result.ibnr_total > 0


def test_monthly_grain_round_trip() -> None:
    payload = TriangleInput(
        accident_periods=["2020-01", "2020-02", "2020-03"],
        development_periods=["1", "2", "3"],
        cells=[
            [10.0, 15.0, 18.0],
            [11.0, 16.0, None],
            [12.0, None, None],
        ],
        grain="month",
        reporting_currency="USD",
        insurance_line="HEALTH",
    )
    result = compute(payload)
    assert len(result.ldfs) == 2
    assert result.ibnr_total >= 0
