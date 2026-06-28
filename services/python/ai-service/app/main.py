"""MedFund AI Service — FastAPI application."""
from contextlib import asynccontextmanager
from fastapi import FastAPI
import logging

from app.core.config import settings
from app.core.gemini_client import GeminiClient
import app.core.gemini_client as gemini_module
from app.core.database import init_db, close_db

from app.api.health import router as health_router
from app.api.adjudication import router as adjudication_router
from app.api.fraud import router as fraud_router
from app.api.ocr import router as ocr_router
from app.api.chatbot import router as chatbot_router
from app.api.forecasting import router as forecasting_router
from app.api.analytics import router as analytics_router
from app.api.pricing import router as pricing_router

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)


@asynccontextmanager
async def lifespan(app: FastAPI):
    logger.info("AI Service starting up...")
    gemini_module.gemini_client = GeminiClient(api_key=settings.gemini_api_key, model=settings.gemini_model)
    if gemini_module.gemini_client.available:
        logger.info(f"Gemini AI: ACTIVE (model: {settings.gemini_model})")
    else:
        logger.info("Gemini AI: INACTIVE (rule-based fallbacks)")

    try:
        await init_db(settings.database_url)
        logger.info("Database: CONNECTED")
    except Exception as e:
        logger.warning(f"Database init failed: {e}")

    kafka_consumer = None
    if settings.kafka_bootstrap_servers:
        try:
            from app.core.kafka_consumer import ClaimsEventConsumer
            from app.services.adjudication_service import AdjudicationService
            from app.services.fraud_service import FraudService
            adj_svc = AdjudicationService(gemini_module.gemini_client)
            fraud_svc = FraudService()
            kafka_consumer = ClaimsEventConsumer(settings.kafka_bootstrap_servers, adj_svc, fraud_svc)
            await kafka_consumer.start()
            logger.info("Kafka consumer: STARTED")
        except Exception as e:
            logger.warning(f"Kafka consumer failed: {e}")

    yield

    if kafka_consumer:
        await kafka_consumer.stop()
    await close_db()
    logger.info("AI Service shut down")


app = FastAPI(
    title="MedFund AI Service",
    version="0.1.0",
    description="AI-powered adjudication, fraud detection, OCR, chatbot, and analytics",
    docs_url="/docs",
    lifespan=lifespan,
)

app.include_router(health_router)
app.include_router(adjudication_router)
app.include_router(fraud_router)
app.include_router(ocr_router)
app.include_router(chatbot_router)
app.include_router(forecasting_router)
app.include_router(analytics_router)
app.include_router(pricing_router)
