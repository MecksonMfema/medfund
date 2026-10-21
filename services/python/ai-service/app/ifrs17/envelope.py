"""Envelope shaping helpers for IFRS 17 rollups (Phase 15 §11 scaffold).

Grain per I13: per portfolio × cohort × currency chunk → aggregator (§18)
rolls into per-currency, per-cohort, per-portfolio slabs → final envelope.

Phase 11 lands only the type surface. Concrete rollup helpers land as
§17/§18 orchestration lights up — this file exists so imports from later
phases resolve today, and to declare the boundary between compute (this
package) and aggregation (finance-service Java + this module later).
"""

from __future__ import annotations

__all__: list[str] = []
