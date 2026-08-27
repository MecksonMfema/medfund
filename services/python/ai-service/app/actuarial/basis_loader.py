"""YAML-backed loader for actuarial mortality + morbidity reference tables.

Every basis table lives as a single YAML file under
``app/actuarial/basis_tables/{mortality,morbidity}/<NAME>.yaml``. The loader
walks that tree, parses metadata for the ``/actuarial/basis-tables/list`` API,
and returns the full parsed dict on demand for the Phase-13 mortality /
Phase-14 morbidity compute paths.
"""

from pathlib import Path
from typing import Literal

import yaml
from pydantic import BaseModel

Category = Literal["mortality", "morbidity"]


class BasisTableMetadata(BaseModel):
    """Row shape returned by ``GET /actuarial/basis-tables/list``."""

    name: str
    display_name: str
    category: Category
    jurisdiction_hints: list[str] = []
    default_line: str | None = None
    source: str | None = None


BASIS_DIR = Path(__file__).parent / "basis_tables"


def list_basis_tables(category: Category | None = None) -> list[BasisTableMetadata]:
    """Return the metadata rows for every YAML under ``basis_tables/``.

    ``category`` filters to ``mortality`` or ``morbidity``; ``None`` returns
    both, sorted (category, name) so callers get a stable order for UI
    rendering.
    """
    tables: list[BasisTableMetadata] = []
    for path in sorted(BASIS_DIR.rglob("*.yaml")):
        with path.open() as f:
            data = yaml.safe_load(f) or {}
        allowed = {k: v for k, v in data.items() if k in BasisTableMetadata.model_fields}
        meta = BasisTableMetadata(**allowed)
        if category is None or meta.category == category:
            tables.append(meta)
    return sorted(tables, key=lambda t: (t.category, t.name))


def load_basis_table(name: str) -> dict:
    """Return the fully parsed YAML dict for the basis table with the given ``name``.

    Raises ``FileNotFoundError`` if no matching YAML exists.
    """
    for path in BASIS_DIR.rglob(f"{name}.yaml"):
        with path.open() as f:
            return yaml.safe_load(f)
    raise FileNotFoundError(f"Basis table not found: {name}")
