"""SQLAlchemy ORM models for AI prediction storage."""
import uuid
from datetime import UTC, datetime

from sqlalchemy import JSON, Boolean, Column, DateTime, Float, String, Text

from app.core.database import Base


class AIPredictionDB(Base):
    __tablename__ = "ai_predictions_store"

    id = Column(String, primary_key=True, default=lambda: str(uuid.uuid4()))
    tenant_id = Column(String, nullable=False, index=True)
    insurance_line = Column(String(20), nullable=True, index=True)
    entity_type = Column(String(50), nullable=False, index=True)
    entity_id = Column(String, nullable=False, index=True)
    prediction_type = Column(String(50), nullable=False)
    model_version = Column(String(50), default="1.0.0")
    input_features = Column(JSON, default=dict)
    output = Column(JSON, default=dict)
    confidence = Column(Float, nullable=True)
    accepted = Column(Boolean, nullable=True)
    reviewed_by = Column(String, nullable=True)
    reviewed_by_email = Column(String(255), nullable=True)
    reviewed_at = Column(DateTime, nullable=True)
    created_at = Column(DateTime, default=lambda: datetime.now(UTC))


class ConversationMessage(Base):
    __tablename__ = "conversation_messages"

    id = Column(String, primary_key=True, default=lambda: str(uuid.uuid4()))
    conversation_id = Column(String, nullable=False, index=True)
    tenant_id = Column(String, nullable=False, index=True)
    role = Column(String(20), nullable=False)  # user, assistant, system
    content = Column(Text, nullable=False)
    created_at = Column(DateTime, default=lambda: datetime.now(UTC))
