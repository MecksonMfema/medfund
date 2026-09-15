"""Repository for AI prediction persistence."""
import logging
from typing import Any
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy import select
from app.models.db_models import AIPredictionDB, ConversationMessage
from datetime import datetime, timezone

logger = logging.getLogger(__name__)


async def save_prediction(session: AsyncSession, prediction: AIPredictionDB) -> AIPredictionDB:
    session.add(prediction)
    await session.commit()
    await session.refresh(prediction)
    return prediction


async def try_record_ai_prediction(
    session: AsyncSession | None,
    *,
    tenant_id: str,
    insurance_line: str | None,
    entity_type: str,
    entity_id: str,
    prediction_type: str,
    model_version: str,
    input_features: dict[str, Any],
    output: dict[str, Any],
    confidence: float | None,
) -> AIPredictionDB | None:
    """Best-effort audit write. A missing DB or a broken write logs but
    does not fail the request — preserves the fail-open contract with
    claims-service. Prefer this over ``record_ai_prediction`` in
    endpoint code."""
    if session is None:
        logger.debug("No DB session — skipping prediction audit for %s/%s",
                     prediction_type, entity_id)
        return None
    try:
        return await record_ai_prediction(
            session,
            tenant_id=tenant_id,
            insurance_line=insurance_line,
            entity_type=entity_type,
            entity_id=entity_id,
            prediction_type=prediction_type,
            model_version=model_version,
            input_features=input_features,
            output=output,
            confidence=confidence,
        )
    except Exception:
        logger.exception("Prediction audit write failed for %s/%s",
                         prediction_type, entity_id)
        return None


async def record_ai_prediction(
    session: AsyncSession,
    *,
    tenant_id: str,
    insurance_line: str | None,
    entity_type: str,
    entity_id: str,
    prediction_type: str,
    model_version: str,
    input_features: dict[str, Any],
    output: dict[str, Any],
    confidence: float | None,
) -> AIPredictionDB:
    """Persist one AI prediction. Callers pass pre-anonymized features.

    Commits so the audit row lands even if the response fails downstream.
    """
    row = AIPredictionDB(
        tenant_id=tenant_id,
        insurance_line=insurance_line,
        entity_type=entity_type,
        entity_id=entity_id,
        prediction_type=prediction_type,
        model_version=model_version,
        input_features=input_features,
        output=output,
        confidence=confidence,
    )
    session.add(row)
    await session.commit()
    await session.refresh(row)
    return row


async def get_prediction(session: AsyncSession, prediction_id: str) -> AIPredictionDB | None:
    result = await session.execute(
        select(AIPredictionDB).where(AIPredictionDB.id == prediction_id)
    )
    return result.scalar_one_or_none()


async def list_predictions(
    session: AsyncSession, entity_type: str, entity_id: str
) -> list[AIPredictionDB]:
    result = await session.execute(
        select(AIPredictionDB)
        .where(AIPredictionDB.entity_type == entity_type, AIPredictionDB.entity_id == entity_id)
        .order_by(AIPredictionDB.created_at.desc())
    )
    return list(result.scalars().all())


async def record_human_decision(
    session: AsyncSession, prediction_id: str, decision: bool, feedback: str, decided_by: str
) -> AIPredictionDB | None:
    prediction = await get_prediction(session, prediction_id)
    if prediction:
        prediction.accepted = decision
        prediction.reviewed_by = decided_by
        prediction.reviewed_at = datetime.now(timezone.utc)
        await session.commit()
        await session.refresh(prediction)
    return prediction


async def save_conversation_message(
    session: AsyncSession, conversation_id: str, tenant_id: str, role: str, content: str
) -> ConversationMessage:
    msg = ConversationMessage(
        conversation_id=conversation_id,
        tenant_id=tenant_id,
        role=role,
        content=content,
    )
    session.add(msg)
    await session.commit()
    return msg


async def get_conversation_history(
    session: AsyncSession, conversation_id: str, limit: int = 20
) -> list[ConversationMessage]:
    result = await session.execute(
        select(ConversationMessage)
        .where(ConversationMessage.conversation_id == conversation_id)
        .order_by(ConversationMessage.created_at.asc())
        .limit(limit)
    )
    return list(result.scalars().all())
