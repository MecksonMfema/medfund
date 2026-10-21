import uuid
from datetime import datetime

from pydantic import BaseModel


class AIPrediction(BaseModel):
    id: str = ""
    tenant_id: str
    entity_type: str  # claim, fraud, ocr
    entity_id: str
    prediction_type: str  # adjudication, fraud_detection, ocr_extraction
    model_version: str = "1.0.0"
    input_features: dict = {}
    output: dict = {}
    confidence: float
    accepted: bool | None = None
    reviewed_by: str | None = None
    reviewed_at: datetime | None = None
    created_at: datetime = datetime.now(tz=__import__('datetime').timezone.utc)

    def __init__(self, **data):
        super().__init__(**data)
        if not self.id:
            self.id = str(uuid.uuid4())
