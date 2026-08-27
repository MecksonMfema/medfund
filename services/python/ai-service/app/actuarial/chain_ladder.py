"""Chain-ladder compute for IBNR + loss-triangle reports.

Wraps ``chainladder-python`` behind a stable Pydantic API so callers (the
Kafka consumer in Phase 8, the golden-fixture tests in Phase 7) do not depend
on the library's shape conventions. Input is a pre-shaped
accident-by-development matrix; output is a small flat result envelope
serializable straight to Kafka.
"""

from __future__ import annotations

from datetime import date
from typing import Literal

import chainladder as cl
import pandas as pd
from pydantic import BaseModel, model_validator

Grain = Literal["month", "quarter", "year"]
LdfMethod = Literal["volume", "simple", "5yr"]


class TriangleInput(BaseModel):
    """Pre-shaped triangle payload published by ``finance-service``.

    ``cells[i][j]`` is the cumulative amount for accident period ``i`` at
    development lag ``j``; ``None`` marks empty upper-right cells above the
    diagonal.
    """

    accident_periods: list[str]
    development_periods: list[str]
    cells: list[list[float | None]]
    grain: Grain
    reporting_currency: str
    insurance_line: str

    @model_validator(mode="after")
    def _check_shape(self) -> "TriangleInput":
        n_acc = len(self.accident_periods)
        n_dev = len(self.development_periods)
        if len(self.cells) != n_acc:
            raise ValueError(
                f"cells has {len(self.cells)} rows but accident_periods has {n_acc}"
            )
        for i, row in enumerate(self.cells):
            if len(row) != n_dev:
                raise ValueError(
                    f"cells row {i} has {len(row)} cols but development_periods has {n_dev}"
                )
        return self


class ChainLadderResult(BaseModel):
    """Flat result envelope. All numeric fields are ``float`` for JSON safety.

    Callers that need exact-precision arithmetic should re-fetch the source
    claim rows and recompute; this envelope is display + summary use.
    """

    ldfs: list[float]
    cdf: list[float]
    ibnr_total: float
    ultimate_total: float
    mack_standard_error: float | None
    per_cohort_ultimate: list[float]


def _parse_period_start(period: str, grain: Grain) -> date:
    if grain == "year":
        return date(int(period), 1, 1)
    if grain == "quarter":
        year_str, q_str = period.upper().split("Q")
        month = (int(q_str) - 1) * 3 + 1
        return date(int(year_str), month, 1)
    # month
    parts = period.split("-")
    return date(int(parts[0]), int(parts[1]), 1)


def _add_periods(origin: date, n: int, grain: Grain) -> date:
    if grain == "year":
        return date(origin.year + n, origin.month, origin.day)
    step_months = 3 if grain == "quarter" else 1
    total_months = origin.month - 1 + n * step_months
    year_off, mon_off = divmod(total_months, 12)
    return date(origin.year + year_off, mon_off + 1, 1)


def _to_long_dataframe(payload: TriangleInput) -> pd.DataFrame:
    rows: list[dict[str, object]] = []
    for i, accident_label in enumerate(payload.accident_periods):
        origin = _parse_period_start(accident_label, payload.grain)
        for j, value in enumerate(payload.cells[i]):
            if value is None:
                continue
            dev = _add_periods(origin, j, payload.grain)
            rows.append(
                {
                    "origin": origin.isoformat(),
                    "development": dev.isoformat(),
                    "value": float(value),
                }
            )
    if not rows:
        raise ValueError("TriangleInput has no non-null cells")
    return pd.DataFrame(rows)


def _development_estimator(method: LdfMethod) -> cl.Development:
    if method == "volume":
        return cl.Development(average="volume")
    if method == "simple":
        return cl.Development(average="simple")
    if method == "5yr":
        return cl.Development(average="volume", n_periods=5)
    raise ValueError(f"Unknown LDF method: {method}")


def compute(payload: TriangleInput, method: LdfMethod = "volume") -> ChainLadderResult:
    """Run Mack chain-ladder on a pre-shaped triangle.

    Deterministic given ``payload`` + ``method`` — the same input hashes to the
    same result envelope, which is why Phase 9 caches by ``params_hash``.
    """
    df = _to_long_dataframe(payload)
    triangle = cl.Triangle(
        df,
        origin="origin",
        development="development",
        columns="value",
        cumulative=True,
    )
    dev = _development_estimator(method)
    transformed = dev.fit_transform(triangle)
    mack = cl.MackChainladder().fit(transformed)

    # ``dev.ldf_`` has one LDF per adjacent-development-period gap; ``mack.ldf_``
    # right-pads with 1.0 tail factors to reach Mack's ultimate horizon — which
    # would silently break Mack (1993) Table 1 comparisons at 4dp.
    ldfs = [float(x) for x in dev.ldf_.iloc[0].values.flatten()]
    cdf = [float(x) for x in dev.cdf_.iloc[0].values.flatten()]
    per_cohort_ultimate = [float(x) for x in mack.ultimate_.iloc[0].values.flatten()]

    mack_std_err: float | None
    try:
        mack_std_err = float(mack.total_mack_std_err_.iloc[0, 0])
    except (AttributeError, IndexError, ValueError):
        mack_std_err = None

    return ChainLadderResult(
        ldfs=ldfs,
        cdf=cdf,
        ibnr_total=float(mack.ibnr_.sum()),
        ultimate_total=float(mack.ultimate_.sum()),
        mack_standard_error=mack_std_err,
        per_cohort_ultimate=per_cohort_ultimate,
    )
