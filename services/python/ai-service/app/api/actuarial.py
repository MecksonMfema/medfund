"""Actuarial reference-table catalogue endpoint.

Phase 6 ships the read-only ``/api/v1/actuarial/basis-tables/list`` route so
the tenant-admin mortality / morbidity dropdowns in Angular can render the
canonical basis names from a single source of truth (the YAML tree under
``app/actuarial/basis_tables/``). The Phase-13 / Phase-14 compute paths
consume the same YAMLs via ``basis_loader.load_basis_table()``.

Route prefix is ``/api/v1/actuarial`` (not the plan's shorthand
``/actuarial``) to match every other ai-service router — the gateway does
verbatim URI pass-through, so the client-visible path
``/api/v1/actuarial/basis-tables/list`` must be the router prefix here.
"""

from typing import Literal

from fastapi import APIRouter, Query

from app.actuarial.basis_loader import BasisTableMetadata, list_basis_tables

router = APIRouter(prefix="/api/v1/actuarial", tags=["Actuarial"])


@router.get(
    "/basis-tables/list",
    response_model=list[BasisTableMetadata],
    summary="List available mortality + morbidity reference tables",
    description=(
        "Read-only catalogue of the reference basis tables (mortality qx, "
        "morbidity incidence) that tenant admins pick from when configuring "
        "per-line basis multipliers. Filter by ``category`` to narrow the "
        "list to just mortality or morbidity."
    ),
)
async def list_tables(
    category: Literal["mortality", "morbidity"] | None = Query(
        None,
        description="Optional category filter — mortality or morbidity.",
    ),
) -> list[BasisTableMetadata]:
    return list_basis_tables(category)
