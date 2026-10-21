"""Tier profiles and per-service endpoint configuration."""

from __future__ import annotations

import os
from dataclasses import dataclass
from enum import StrEnum


class Tier(StrEnum):
    MICRO = "micro"
    FAST = "fast"
    FULL = "full"


@dataclass(frozen=True)
class TierProfile:
    timeline_months: int
    health_members: int
    life_members: int
    groups_per_tenant: int
    health_providers: int
    life_providers: int
    health_claims_per_member_year: float
    life_claims_per_member_year: float
    concurrent_enrolls: int
    concurrent_adjudications: int


TIER_PROFILES: dict[Tier, TierProfile] = {
    Tier.MICRO: TierProfile(
        timeline_months=3,
        health_members=500,
        life_members=375,
        groups_per_tenant=3,
        health_providers=50,
        life_providers=10,
        health_claims_per_member_year=4,
        life_claims_per_member_year=0.05,
        concurrent_enrolls=10,
        concurrent_adjudications=3,
    ),
    Tier.FAST: TierProfile(
        timeline_months=6,
        health_members=2_000,
        life_members=1_500,
        groups_per_tenant=8,
        health_providers=500,
        life_providers=30,
        health_claims_per_member_year=6,
        life_claims_per_member_year=0.08,
        concurrent_enrolls=20,
        concurrent_adjudications=5,
    ),
    Tier.FULL: TierProfile(
        timeline_months=24,
        health_members=20_000,
        life_members=15_000,
        groups_per_tenant=15,
        health_providers=5_000,
        life_providers=50,
        health_claims_per_member_year=8,
        life_claims_per_member_year=0.1,
        concurrent_enrolls=40,
        concurrent_adjudications=5,
    ),
}


@dataclass(frozen=True)
class ServiceEndpoints:
    keycloak: str
    tenancy: str
    user: str
    contributions: str
    claims: str
    finance: str
    rules: str

    @classmethod
    def from_env(cls) -> "ServiceEndpoints":
        return cls(
            keycloak=os.environ.get("SEED_KEYCLOAK_URL", "http://localhost:9080"),
            tenancy=os.environ.get("SEED_TENANCY_URL", "http://localhost:8081"),
            user=os.environ.get("SEED_USER_URL", "http://localhost:8082"),
            claims=os.environ.get("SEED_CLAIMS_URL", "http://localhost:8083"),
            contributions=os.environ.get("SEED_CONTRIBUTIONS_URL", "http://localhost:8084"),
            finance=os.environ.get("SEED_FINANCE_URL", "http://localhost:8085"),
            rules=os.environ.get("SEED_RULES_URL", "http://localhost:8086"),
        )
