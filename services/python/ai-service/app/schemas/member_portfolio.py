"""Multi-line member policy portfolio used to enrich chatbot prompts.

Every line has its own summary shape. `MemberPolicyPortfolio` is a
composition: any field may be None (member holds no policy in that line);
person-centric lines are singular; asset lines are lists (a member may
own multiple vehicles / properties).
"""
from __future__ import annotations

from pydantic import BaseModel, Field


class HealthPolicySummary(BaseModel):
    scheme_name: str
    annual_limit: float
    used_ytd: float
    remaining: float
    currency: str
    dependants_count: int = 0


class LifePolicySummary(BaseModel):
    sum_assured: float
    currency: str
    beneficiary_count: int = 0
    policy_start_date: str | None = None
    premium_status: str = "PAID"  # PAID | IN_ARREARS | LAPSED


class FuneralPolicySummary(BaseModel):
    benefit_tier: str
    lives_covered: int = 1
    currency: str
    premium_status: str = "PAID"


class DisabilityPolicySummary(BaseModel):
    benefit_type: str  # TEMPORARY | PERMANENT | PARTIAL
    monthly_benefit_amount: float
    currency: str
    waiting_period_days: int = 0
    benefit_period_years: int | None = None  # None == lifetime


class TravelPolicySummary(BaseModel):
    active_trips: list[dict] = Field(default_factory=list)
    annual_multitrip: bool = False


class VehiclePolicySummary(BaseModel):
    vehicle_id: str
    make_model: str
    registration: str
    coverage_type: str
    excess_amount: float
    currency: str
    no_claims_discount_years: int = 0


class PropertyPolicySummary(BaseModel):
    property_id: str
    address_summary: str
    coverage_type: str
    excess_amount: float
    currency: str


class MemberPolicyPortfolio(BaseModel):
    """One member, all their active policies across every line."""

    health: HealthPolicySummary | None = None
    life: LifePolicySummary | None = None
    funeral: FuneralPolicySummary | None = None
    disability: DisabilityPolicySummary | None = None
    travel: TravelPolicySummary | None = None
    vehicle: list[VehiclePolicySummary] = Field(default_factory=list)
    property: list[PropertyPolicySummary] = Field(default_factory=list)
    group: list[dict] = Field(default_factory=list)

    def is_empty(self) -> bool:
        return (
            self.health is None and self.life is None and self.funeral is None
            and self.disability is None and self.travel is None
            and not self.vehicle and not self.property and not self.group
        )
