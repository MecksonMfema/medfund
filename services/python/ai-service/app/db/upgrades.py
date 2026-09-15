"""Idempotent column additions applied at service startup.

Base.metadata.create_all only creates tables — it does not add columns to
tables that already exist. When a new column lands on an existing SQLAlchemy
model, we apply the ALTER here so dev/staging DBs pick it up without a
migration harness. Every statement uses IF NOT EXISTS so re-runs are safe.

Postgres-only DDL is guarded — SQLite (used in tests) skips the block
because create_all already puts every column on the fresh table.
"""
from __future__ import annotations

import logging

from sqlalchemy import text
from sqlalchemy.ext.asyncio import AsyncEngine

logger = logging.getLogger(__name__)


async def ensure_columns(engine: AsyncEngine) -> None:
    """Bring an existing ai_predictions_store schema up to the current model."""
    if engine.dialect.name != "postgresql":
        logger.debug("ensure_columns: skipping %s dialect", engine.dialect.name)
        return
    statements = [
        # Phase 2 — insurance_line for per-line filtering on the review page.
        "ALTER TABLE ai_predictions_store "
        "ADD COLUMN IF NOT EXISTS insurance_line VARCHAR(20)",
        "CREATE INDEX IF NOT EXISTS ai_predictions_store_insurance_line_idx "
        "ON ai_predictions_store (insurance_line) "
        "WHERE insurance_line IS NOT NULL",
        # Phase 7 — reviewed_by_email for the audit trail (feedback_audit_actor_email).
        "ALTER TABLE ai_predictions_store "
        "ADD COLUMN IF NOT EXISTS reviewed_by_email VARCHAR(255)",
    ]
    async with engine.begin() as conn:
        for stmt in statements:
            await conn.execute(text(stmt))
    logger.info("ai_predictions_store schema upgraded")
