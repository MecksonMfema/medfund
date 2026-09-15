"""Document OCR service — Tesseract text extraction + LLM structured data."""
import logging
from typing import Any, Callable

logger = logging.getLogger(__name__)


class OCRService:
    """Extracts text from documents using Tesseract, structures data with an LLM."""

    def __init__(
        self,
        gemini_client=None,
        llm_provider: Callable[[], Any] | None = None,
    ):
        # llm_provider is called on every request so a provider switch (Gemini
        # ↔ Claude) is picked up without re-instantiating the service.
        self._llm_provider = llm_provider
        self.gemini_client = gemini_client
        self._tesseract_available = self._check_tesseract()

    def _current_llm(self):
        if self._llm_provider is not None:
            return self._llm_provider()
        return self.gemini_client

    def _check_tesseract(self) -> bool:
        try:
            import pytesseract
            pytesseract.get_tesseract_version()
            return True
        except Exception:
            logger.warning("Tesseract not available — OCR will return empty text")
            return False

    def extract_text(self, image_bytes: bytes) -> str:
        """Extract raw text from image using Tesseract."""
        if not self._tesseract_available:
            return ""
        try:
            import pytesseract
            from PIL import Image
            import io
            image = Image.open(io.BytesIO(image_bytes))
            return pytesseract.image_to_string(image)
        except Exception as e:
            logger.error(f"Tesseract OCR failed: {e}")
            return ""

    async def extract_structured_data(
        self, raw_text: str, image_bytes: bytes | None = None
    ) -> dict:
        """Extract structured claim data from OCR text using Claude."""
        llm = self._current_llm()
        if llm is not None and llm.available:
            try:
                prompt = f"""Extract structured healthcare claim data from this OCR text.
Return JSON with fields: provider_name, member_id, diagnosis_codes (list),
tariff_codes (list), amounts (list of {{code, amount}}), service_date, currency.
If a field cannot be determined, use null.

OCR Text:
{raw_text}"""
                result = await llm.complete_json(
                    system_prompt="You are a healthcare document data extractor.",
                    messages=[{"role": "user", "content": prompt}],
                )
                if result:
                    return result
            except Exception as e:
                logger.warning(f"LLM structured extraction failed: {e}")

        # Fallback: return raw text only
        return {"raw_text": raw_text, "extraction_method": "tesseract_only"}
