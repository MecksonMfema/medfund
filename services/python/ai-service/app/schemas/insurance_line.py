"""Insurance line enum mirroring services/java/shared/.../InsuranceLine.java."""
from __future__ import annotations

from enum import StrEnum


class InsuranceLine(StrEnum):
    HEALTH = "HEALTH"
    LIFE = "LIFE"
    FUNERAL = "FUNERAL"
    GROUP = "GROUP"
    TRAVEL = "TRAVEL"
    DISABILITY = "DISABILITY"
    VEHICLE = "VEHICLE"
    PROPERTY = "PROPERTY"

    def is_person_centric(self) -> bool:
        return self in {
            InsuranceLine.HEALTH,
            InsuranceLine.LIFE,
            InsuranceLine.FUNERAL,
            InsuranceLine.GROUP,
            InsuranceLine.TRAVEL,
            InsuranceLine.DISABILITY,
        }
