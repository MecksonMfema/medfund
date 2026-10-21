"""Morbidity-study compute (Phase 14).

Given a set of exposure observations aggregated by (age_band, sex) —
exposure-years denominator + observed incidents (new illness / disability
onsets) numerator per band — and a morbidity basis identifier the tenant
has picked, produce per-line actual-vs-expected morbidity data. Expected
incidents come from a YAML morbidity basis (e.g. CIDA, GLTD87) shipped
under ``app/actuarial/basis_tables/morbidity/`` and read via the Phase-6
loader, then scaled by the tenant's per-line
``tenant_morbidity_basis.morbidity_multiplier`` (a decimal fudge factor
that lets a tenant admin nudge the industry basis to fit their book).

The compute is deterministic + side-effect free — Java's
``MorbidityExposureShapingService`` pre-aggregates the exposure feed and
resolves the basis metadata (name + multiplier) into the payload so this
module only has to do arithmetic against the YAML incidence grid.

Age-bin incidence → basis-band expected morbidity
─────────────────────────────────────────────────
The YAMLs express incidence per 1000 at canonical select ages (20, 25,
30, …, 65). Real exposure feeds report age-bands ("30-34"). For each
band we compute a representative incidence via linear interpolation
between the two nearest canonical select ages, then multiply by
exposure-years to get expected incidents. Same shape as mortality but
with an ``incidence`` grid instead of ``qx`` and ``observed_incidents``
in the numerator instead of ``deaths``.
"""

from __future__ import annotations

from typing import Any

from pydantic import BaseModel, Field

from app.actuarial.basis_loader import load_basis_table


class MorbidityExposureBand(BaseModel):
    """One (age_band, sex) tuple with observed exposure + incidents.

    ``age_band`` is a canonical string like ``"30-34"``. The compute
    parses the low + high age off the label and takes the midpoint for
    the incidence lookup — a five-year band centred on 32.5 will map to
    a linearly-interpolated incidence between the 30 and 35 canonical
    ages.
    """

    age_band: str
    sex: str  # "male" | "female"
    exposure_years: float
    incidents: int


class MorbidityCohort(BaseModel):
    insurance_line: str
    basis_name: str
    multiplier: float = 1.0
    bands: list[MorbidityExposureBand] = Field(default_factory=list)


class MorbidityExposureInput(BaseModel):
    cohorts: list[MorbidityCohort] = Field(default_factory=list)


class MorbidityRow(BaseModel):
    age_band: str
    sex: str
    exposure_years: float
    observed_incidents: int
    expected_incidents: float
    actual_morbidity_rate: float
    expected_morbidity_rate: float
    ae_ratio: float | None


class MorbidityLineResult(BaseModel):
    basis_name: str
    multiplier: float
    rows: list[MorbidityRow] = Field(default_factory=list)


class MorbidityResult(BaseModel):
    per_line: dict[str, MorbidityLineResult] = Field(default_factory=dict)
    warnings: list[str] = Field(default_factory=list)


def compute(input: MorbidityExposureInput) -> MorbidityResult:
    per_line: dict[str, MorbidityLineResult] = {}
    warnings: list[str] = []

    for cohort in input.cohorts:
        line = cohort.insurance_line
        try:
            basis = load_basis_table(cohort.basis_name)
        except FileNotFoundError:
            warnings.append(
                f"basis {cohort.basis_name!r} not found for line {line}; skipped"
            )
            continue
        if basis.get("category") != "morbidity":
            warnings.append(
                f"basis {cohort.basis_name!r} is not a morbidity table (category="
                f"{basis.get('category')!r}); skipped"
            )
            continue
        incidence = basis.get("incidence") or {}
        if not incidence:
            warnings.append(
                f"basis {cohort.basis_name!r} has no incidence grid; skipped"
            )
            continue
        multiplier = cohort.multiplier if cohort.multiplier > 0 else 1.0
        select_ages = sorted(int(a) for a in incidence.keys())
        line_rows: list[MorbidityRow] = []
        for band in cohort.bands:
            if band.exposure_years <= 0:
                warnings.append(
                    f"line {line} basis {cohort.basis_name} band {band.age_band!r} "
                    f"({band.sex}) has non-positive exposure; skipped"
                )
                continue
            observed = band.incidents if band.incidents >= 0 else 0
            if band.incidents < 0:
                warnings.append(
                    f"line {line} band {band.age_band!r} ({band.sex}) had negative "
                    "incidents; clamped to 0"
                )
            midpoint = _band_midpoint(band.age_band)
            if midpoint is None:
                warnings.append(
                    f"line {line} band {band.age_band!r} could not be parsed; skipped"
                )
                continue
            sex_key = band.sex.lower()
            if sex_key not in ("male", "female"):
                warnings.append(
                    f"line {line} band {band.age_band!r} sex {band.sex!r} unknown; skipped"
                )
                continue
            base_incidence_per_1000 = _interpolate_incidence(
                incidence, select_ages, midpoint, sex_key
            )
            expected_rate = base_incidence_per_1000 / 1000.0 * multiplier
            expected_incidents = expected_rate * band.exposure_years
            actual_rate = observed / band.exposure_years
            ae_ratio = actual_rate / expected_rate if expected_rate > 0 else None
            line_rows.append(
                MorbidityRow(
                    age_band=band.age_band,
                    sex=band.sex,
                    exposure_years=band.exposure_years,
                    observed_incidents=observed,
                    expected_incidents=expected_incidents,
                    actual_morbidity_rate=actual_rate,
                    expected_morbidity_rate=expected_rate,
                    ae_ratio=ae_ratio,
                )
            )
        line_rows.sort(key=lambda r: (r.age_band, r.sex))
        if line in per_line:
            warnings.append(
                f"line {line} appeared twice in the exposure feed; second cohort ignored"
            )
            continue
        per_line[line] = MorbidityLineResult(
            basis_name=cohort.basis_name,
            multiplier=multiplier,
            rows=line_rows,
        )

    return MorbidityResult(per_line=per_line, warnings=warnings)


def compute_from_dict(payload: dict[str, Any]) -> MorbidityResult:
    """Convenience for the Kafka runner — accepts the raw ``exposure`` slot."""
    return compute(MorbidityExposureInput(**payload))


def _band_midpoint(age_band: str) -> float | None:
    label = age_band.strip()
    if not label:
        return None
    # Accept "30-34", "0-4", "85+", and a bare "30".
    if label.endswith("+"):
        try:
            return float(label[:-1])
        except ValueError:
            return None
    if "-" in label:
        lo, hi = label.split("-", 1)
        try:
            return (float(lo) + float(hi)) / 2.0
        except ValueError:
            return None
    try:
        return float(label)
    except ValueError:
        return None


def _interpolate_incidence(incidence: dict[Any, dict[str, float]],
                           select_ages: list[int],
                           age: float, sex: str) -> float:
    """Linear interpolation between the two nearest canonical select ages."""
    if age <= select_ages[0]:
        return float(incidence[select_ages[0]][sex])
    if age >= select_ages[-1]:
        return float(incidence[select_ages[-1]][sex])
    for i in range(len(select_ages) - 1):
        lo = select_ages[i]
        hi = select_ages[i + 1]
        if lo <= age <= hi:
            lo_val = float(incidence[lo][sex])
            hi_val = float(incidence[hi][sex])
            if hi == lo:
                return lo_val
            frac = (age - lo) / (hi - lo)
            return lo_val + frac * (hi_val - lo_val)
    # Should not reach here because of the >= last guard, but be defensive.
    return float(incidence[select_ages[-1]][sex])


__all__ = [
    "MorbidityCohort",
    "MorbidityExposureBand",
    "MorbidityExposureInput",
    "MorbidityLineResult",
    "MorbidityResult",
    "MorbidityRow",
    "compute",
    "compute_from_dict",
]
