"""Loader + route tests for the actuarial basis-table catalogue.

Sourced YAMLs live under ``app/actuarial/basis_tables/`` — one file per
basis. These tests pin the shape (metadata parsing, category filter,
full-load round-trip, missing-name error) and the FastAPI route so a
future refactor cannot silently drop a table from the catalogue.
"""

import pytest
from fastapi.testclient import TestClient

from app.actuarial.basis_loader import (
    BasisTableMetadata,
    list_basis_tables,
    load_basis_table,
)
from app.main import app

client = TestClient(app)


# ── list_basis_tables ────────────────────────────────────────────────


def test_list_returns_all_six_by_default():
    rows = list_basis_tables()
    names = [r.name for r in rows]
    assert set(names) == {"A1949_52", "A67_70", "SA85_90", "CSO_2017", "CIDA", "GLTD87"}


def test_list_sorted_by_category_then_name():
    rows = list_basis_tables()
    assert [r.category for r in rows] == sorted(r.category for r in rows)


def test_filter_mortality():
    rows = list_basis_tables(category="mortality")
    assert len(rows) == 4
    assert {r.name for r in rows} == {"A1949_52", "A67_70", "SA85_90", "CSO_2017"}
    assert all(r.category == "mortality" for r in rows)


def test_filter_morbidity():
    rows = list_basis_tables(category="morbidity")
    assert len(rows) == 2
    assert {r.name for r in rows} == {"CIDA", "GLTD87"}
    assert all(r.category == "morbidity" for r in rows)


def test_metadata_exposes_display_name_and_source():
    rows = list_basis_tables(category="mortality")
    cso = next(r for r in rows if r.name == "CSO_2017")
    assert cso.display_name.startswith("CSO 2017")
    assert cso.source is not None and "NAIC" in cso.source
    assert "US" in cso.jurisdiction_hints


# ── load_basis_table ─────────────────────────────────────────────────


def test_load_returns_full_qx_table():
    data = load_basis_table("A1949_52")
    assert data["name"] == "A1949_52"
    assert data["category"] == "mortality"
    assert "qx" in data
    assert 0 in data["qx"] and 100 in data["qx"]
    assert data["qx"][0]["male"] > data["qx"][0]["female"]


def test_load_morbidity_returns_incidence():
    data = load_basis_table("CIDA")
    assert data["category"] == "morbidity"
    assert "incidence" in data
    assert 20 in data["incidence"]


def test_load_missing_raises():
    with pytest.raises(FileNotFoundError):
        load_basis_table("NONEXISTENT_TABLE")


# ── HTTP surface ─────────────────────────────────────────────────────


def test_route_lists_all_tables():
    resp = client.get("/api/v1/actuarial/basis-tables/list")
    assert resp.status_code == 200
    body = resp.json()
    assert len(body) == 6
    # Contract shape: pydantic model round-trips cleanly
    parsed = [BasisTableMetadata(**row) for row in body]
    assert {p.name for p in parsed} == {
        "A1949_52",
        "A67_70",
        "SA85_90",
        "CSO_2017",
        "CIDA",
        "GLTD87",
    }


def test_route_filters_by_category():
    resp = client.get("/api/v1/actuarial/basis-tables/list", params={"category": "mortality"})
    assert resp.status_code == 200
    body = resp.json()
    assert len(body) == 4
    assert all(row["category"] == "mortality" for row in body)


def test_route_rejects_unknown_category():
    resp = client.get("/api/v1/actuarial/basis-tables/list", params={"category": "nope"})
    assert resp.status_code == 422  # FastAPI Literal validation
