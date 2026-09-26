"""Document OCR endpoints."""
from __future__ import annotations

import hashlib
import logging

from fastapi import APIRouter, Depends, File, Form, Header, UploadFile
from pydantic import BaseModel
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.anonymize import anonymize_features
from app.core.database import get_optional_session
from app.core.llm_dispatch import resolve_llm
from app.services.ocr_service import OCRService
from app.services.prediction_repository import try_record_ai_prediction

logger = logging.getLogger(__name__)
router = APIRouter(prefix="/api/v1/ai/ocr", tags=["Document OCR"])

_ocr_service = OCRService(llm_provider=resolve_llm)


class OCRResult(BaseModel):
    filename: str
    extracted_text: str
    structured_data: dict
    confidence: float
    model_version: str = "1.0.0"


@router.post("/extract", response_model=OCRResult)
async def extract_document(
    file: UploadFile = File(...),
    insurance_line: str | None = Form(None),
    x_tenant_id: str = Header(..., alias="X-Tenant-ID"),
    session: AsyncSession | None = Depends(get_optional_session),
):
    """Extract text and structured data from uploaded documents."""
    logger.info(f"OCR extraction for {file.filename}, tenant {x_tenant_id}")

    content = await file.read()
    raw_text = _ocr_service.extract_text(content)
    structured = await _ocr_service.extract_structured_data(raw_text, content)

    confidence = 0.8 if raw_text else 0.0
    if structured.get("extraction_method") == "tesseract_only":
        confidence = 0.4

    filename = file.filename or "unknown"
    response = OCRResult(
        filename=filename,
        extracted_text=raw_text,
        structured_data=structured,
        confidence=confidence,
    )

    entity_id = hashlib.sha1(
        f"{filename}|{len(content)}".encode()
    ).hexdigest()
    await try_record_ai_prediction(
        session,
        tenant_id=x_tenant_id,
        insurance_line=insurance_line,
        entity_type="document",
        entity_id=entity_id,
        prediction_type="ocr",
        model_version=response.model_version,
        input_features=anonymize_features({
            "filename": filename,
            "bytes": len(content),
            "extraction_method": structured.get("extraction_method"),
        }),
        output=anonymize_features(response.model_dump()),
        confidence=confidence,
    )
    return response
