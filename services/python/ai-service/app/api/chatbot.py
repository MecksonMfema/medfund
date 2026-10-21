"""AI chatbot endpoints — persistent conversation history + line-neutral prompts."""
from __future__ import annotations

import logging
from datetime import datetime
from uuid import UUID

from fastapi import APIRouter, Depends, Header, HTTPException, status
from pydantic import BaseModel
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.anonymize import anonymize_features
from app.core.config import settings
from app.core.database import get_optional_session
from app.core.llm_dispatch import resolve_llm
from app.services.chatbot_service import ChatbotService
from app.services.member_context_client import MemberContextClient
from app.services.prediction_repository import (
    get_conversation_history,
    try_record_ai_prediction,
)

logger = logging.getLogger(__name__)
router = APIRouter(prefix="/api/v1/ai/chat", tags=["AI Chatbot"])

_member_context_client = MemberContextClient(user_service_url=settings.user_service_url)
_chatbot = ChatbotService(
    member_context_client=_member_context_client,
    llm_provider=resolve_llm,
)


class ChatMessage(BaseModel):
    message: str
    conversation_id: str | None = None
    context: dict = {}


class ChatResponse(BaseModel):
    reply: str
    conversation_id: str
    source: str
    confidence: float


class ChatHistoryItem(BaseModel):
    role: str
    content: str
    created_at: datetime | None = None


@router.post("/message", response_model=ChatResponse)
async def chat(
    request: ChatMessage,
    x_tenant_id: str = Header(..., alias="X-Tenant-ID"),
    session: AsyncSession | None = Depends(get_optional_session),
):
    """AI-powered chatbot for member queries. Persists both turns."""
    result = await _chatbot.respond(
        message=request.message,
        conversation_id=request.conversation_id,
        context=request.context,
        tenant_id=x_tenant_id,
        session=session,
    )
    response = ChatResponse(
        reply=result["reply"],
        conversation_id=result["conversation_id"],
        source=result["source"],
        confidence=result["confidence"],
    )

    # Chat is line-free — the portfolio contains all lines, LLM picks.
    await try_record_ai_prediction(
        session,
        tenant_id=x_tenant_id,
        insurance_line=None,
        entity_type="conversation",
        entity_id=response.conversation_id,
        prediction_type="chat",
        model_version=result.get("model_version", "chatbot-v1"),
        input_features=anonymize_features(request.model_dump()),
        output=response.model_dump(),
        confidence=response.confidence,
    )
    return response


@router.get("/history/{conversation_id}", response_model=list[ChatHistoryItem])
async def get_history(
    conversation_id: UUID,
    x_tenant_id: str = Header(..., alias="X-Tenant-ID"),
    session: AsyncSession | None = Depends(get_optional_session),
):
    """Return persisted conversation turns, tenant-scoped."""
    if session is None:
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail="Conversation history persistence not available",
        )
    rows = await get_conversation_history(
        session, conversation_id=str(conversation_id), limit=100,
    )
    return [
        ChatHistoryItem(role=r.role, content=r.content, created_at=r.created_at)
        for r in rows
        if r.tenant_id == x_tenant_id
    ]
