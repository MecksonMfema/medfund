"""Tests for prediction repository with in-memory SQLite."""
import pytest
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker, create_async_engine

from app.core.database import Base
from app.models.db_models import AIPredictionDB
from app.services import prediction_repository as repo


@pytest.fixture
async def session():
    engine = create_async_engine("sqlite+aiosqlite:///:memory:")
    async with engine.begin() as conn:
        await conn.run_sync(Base.metadata.create_all)

    factory = async_sessionmaker(engine, class_=AsyncSession, expire_on_commit=False)
    async with factory() as session:
        yield session

    await engine.dispose()


@pytest.mark.asyncio
async def test_save_and_get_prediction(session):
    prediction = AIPredictionDB(
        tenant_id="t1",
        entity_type="claim",
        entity_id="CLM-001",
        prediction_type="adjudication",
        confidence=0.85,
        output={"recommendation": "APPROVE"},
    )
    saved = await repo.save_prediction(session, prediction)
    assert saved.id is not None

    fetched = await repo.get_prediction(session, saved.id)
    assert fetched is not None
    assert fetched.entity_id == "CLM-001"
    assert fetched.confidence == 0.85


@pytest.mark.asyncio
async def test_list_predictions(session):
    for i in range(3):
        await repo.save_prediction(session, AIPredictionDB(
            tenant_id="t1", entity_type="claim", entity_id="CLM-001",
            prediction_type="adjudication", confidence=0.8 + i * 0.05,
        ))
    results = await repo.list_predictions(session, "claim", "CLM-001")
    assert len(results) == 3


@pytest.mark.asyncio
async def test_record_human_decision(session):
    prediction = AIPredictionDB(
        tenant_id="t1", entity_type="claim", entity_id="CLM-002",
        prediction_type="adjudication", confidence=0.7,
    )
    saved = await repo.save_prediction(session, prediction)

    updated = await repo.record_human_decision(session, saved.id, True, "Looks correct", "user-1")
    assert updated.accepted is True
    assert updated.reviewed_by == "user-1"


@pytest.mark.asyncio
async def test_conversation_messages(session):
    await repo.save_conversation_message(session, "conv-1", "t1", "user", "Hello")
    await repo.save_conversation_message(session, "conv-1", "t1", "assistant", "Hi there!")

    history = await repo.get_conversation_history(session, "conv-1")
    assert len(history) == 2
    assert history[0].role == "user"
    assert history[1].role == "assistant"
