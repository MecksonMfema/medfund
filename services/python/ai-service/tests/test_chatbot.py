"""Tests for the chat endpoint.

These override ``get_optional_session`` with an in-memory SQLite session, the
same as the sibling DB-backed tests (``test_chatbot_persistence.py``,
``test_prediction_repository.py``). Without the override the chat handler hits
the real default asyncpg pool, whose connections belong to a different event
loop than the TestClient's, producing
``RuntimeError: Task got Future attached to a different loop``.
"""
from __future__ import annotations

import pytest
from fastapi.testclient import TestClient
from sqlalchemy.ext.asyncio import (
    AsyncSession,
    async_sessionmaker,
    create_async_engine,
)

from app.core.database import Base, get_optional_session
from app.main import app


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


def test_chat_message(client):
    response = client.post(
        "/api/v1/ai/chat/message",
        json={"message": "What is my benefit balance?"},
        headers={"X-Tenant-ID": "test-tenant"},
    )
    assert response.status_code == 200
    data = response.json()
    assert data["source"] in ("ai", "fallback")
    assert data["conversation_id"] is not None
    assert len(data["reply"]) > 0


def test_chat_with_conversation_id(client):
    response = client.post(
        "/api/v1/ai/chat/message",
        json={
            "message": "Follow up question",
            "conversation_id": "conv-123",
        },
        headers={"X-Tenant-ID": "test-tenant"},
    )
    assert response.status_code == 200
    assert response.json()["conversation_id"] == "conv-123"
