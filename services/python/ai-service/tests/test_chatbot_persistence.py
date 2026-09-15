"""End-to-end tests for chatbot persistence and history retrieval."""
from __future__ import annotations

from uuid import uuid4

import pytest
from fastapi.testclient import TestClient
from sqlalchemy import select
from sqlalchemy.ext.asyncio import (
    AsyncSession,
    async_sessionmaker,
    create_async_engine,
)

from app.core.database import Base, get_optional_session
from app.main import app
from app.models.db_models import ConversationMessage


TENANT = "00000000-0000-0000-0000-000000000099"
OTHER_TENANT = "00000000-0000-0000-0000-000000000098"


@pytest.fixture
async def persistence_factory():
    engine = create_async_engine("sqlite+aiosqlite:///:memory:")
    async with engine.begin() as conn:
        await conn.run_sync(Base.metadata.create_all)
    factory = async_sessionmaker(engine, class_=AsyncSession, expire_on_commit=False)
    yield factory
    await engine.dispose()


@pytest.fixture
def client(persistence_factory):
    async def override():
        async with persistence_factory() as session:
            yield session
    app.dependency_overrides[get_optional_session] = override
    with TestClient(app) as c:
        yield c
    app.dependency_overrides.pop(get_optional_session, None)


@pytest.mark.asyncio
async def test_chat_persists_both_turns(client, persistence_factory):
    conv_id = str(uuid4())
    r = client.post(
        "/api/v1/ai/chat/message",
        json={"message": "hi", "conversation_id": conv_id},
        headers={"X-Tenant-ID": TENANT},
    )
    assert r.status_code == 200, r.text

    async with persistence_factory() as session:
        rows = list(
            (await session.execute(
                select(ConversationMessage).where(
                    ConversationMessage.conversation_id == conv_id
                )
            )).scalars().all()
        )
    roles = [r.role for r in rows]
    assert roles.count("user") == 1
    assert roles.count("assistant") == 1


@pytest.mark.asyncio
async def test_history_endpoint_returns_turns_in_order(client):
    conv_id = str(uuid4())
    for msg in ("first", "second", "third"):
        client.post(
            "/api/v1/ai/chat/message",
            json={"message": msg, "conversation_id": conv_id},
            headers={"X-Tenant-ID": TENANT},
        )

    r = client.get(
        f"/api/v1/ai/chat/history/{conv_id}",
        headers={"X-Tenant-ID": TENANT},
    )
    assert r.status_code == 200, r.text
    history = r.json()
    # 3 messages → 6 turns (user + assistant per message)
    assert len(history) == 6
    user_contents = [h["content"] for h in history if h["role"] == "user"]
    assert user_contents == ["first", "second", "third"]


@pytest.mark.asyncio
async def test_history_endpoint_is_tenant_scoped(client):
    conv_id = str(uuid4())
    # Tenant A sends a message
    client.post(
        "/api/v1/ai/chat/message",
        json={"message": "tenant a message", "conversation_id": conv_id},
        headers={"X-Tenant-ID": TENANT},
    )
    # Tenant B tries to read the same conversation
    r = client.get(
        f"/api/v1/ai/chat/history/{conv_id}",
        headers={"X-Tenant-ID": OTHER_TENANT},
    )
    assert r.status_code == 200
    assert r.json() == []


@pytest.mark.asyncio
async def test_history_endpoint_rejects_non_uuid_conversation_id(client):
    r = client.get(
        "/api/v1/ai/chat/history/not-a-uuid",
        headers={"X-Tenant-ID": TENANT},
    )
    assert r.status_code == 422


@pytest.mark.asyncio
async def test_history_persists_across_service_restart(client, persistence_factory):
    """Simulate a restart by re-issuing the request against the same DB."""
    conv_id = str(uuid4())
    client.post(
        "/api/v1/ai/chat/message",
        json={"message": "pre-restart", "conversation_id": conv_id},
        headers={"X-Tenant-ID": TENANT},
    )

    # DB survives the "restart" — new session, same underlying engine
    r = client.get(
        f"/api/v1/ai/chat/history/{conv_id}",
        headers={"X-Tenant-ID": TENANT},
    )
    assert r.status_code == 200
    history = r.json()
    assert any(h["content"] == "pre-restart" for h in history)
