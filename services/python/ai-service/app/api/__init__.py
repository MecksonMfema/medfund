from app.api.adjudication import router as adjudication_router
from app.api.chatbot import router as chatbot_router
from app.api.fraud import router as fraud_router
from app.api.health import router as health_router
from app.api.ocr import router as ocr_router

__all__ = ["health_router", "adjudication_router", "fraud_router", "ocr_router", "chatbot_router"]
