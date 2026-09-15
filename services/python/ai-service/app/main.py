"""MedFund AI Service — FastAPI application."""
from contextlib import asynccontextmanager
from fastapi import FastAPI
import logging

from app.core.config import settings
from app.core.gemini_client import GeminiClient
import app.core.gemini_client as gemini_module
from app.core.anthropic_client import ClaudeClient
import app.core.anthropic_client as anthropic_module
from app.core.database import init_db, close_db

from app.api.health import router as health_router
from app.api.adjudication import router as adjudication_router
from app.api.fraud import router as fraud_router
from app.api.ocr import router as ocr_router
from app.api.chatbot import router as chatbot_router
from app.api.forecasting import router as forecasting_router
from app.api.analytics import router as analytics_router
from app.api.pricing import router as pricing_router
from app.api.actuarial import router as actuarial_router
from app.api.predictions import router as predictions_router
from app.api.models import router as models_router
from app.actuarial.chain_ladder import TriangleInput, compute as chain_ladder_compute
from app.report.kafka import ReportJobRunner
from app.services.fraud_registry import start_polling as start_fraud_poll
from app.services.fraud_registry import stop_polling as stop_fraud_poll
from app.services.pricing_registry import start_polling as start_pricing_poll
from app.services.pricing_registry import stop_polling as stop_pricing_poll

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)


@asynccontextmanager
async def lifespan(app: FastAPI):
    logger.info("AI Service starting up...")
    gemini_module.gemini_client = GeminiClient(
        api_key=settings.gemini_api_key, model=settings.gemini_model,
    )
    anthropic_module.claude_client = ClaudeClient(
        api_key=settings.anthropic_api_key, model=settings.anthropic_model,
    )
    provider = (settings.llm_provider or "gemini").lower()
    active_available = (
        anthropic_module.claude_client.available if provider == "claude"
        else gemini_module.gemini_client.available
    )
    logger.info(
        "LLM provider=%s, available=%s (gemini=%s, claude=%s)",
        provider, active_available,
        gemini_module.gemini_client.available,
        anthropic_module.claude_client.available,
    )

    try:
        await init_db(settings.database_url)
        logger.info("Database: CONNECTED")
    except Exception as e:
        logger.warning(f"Database init failed: {e}")

    try:
        chain_ladder_compute(
            TriangleInput(
                accident_periods=["2020Q1", "2020Q2"],
                development_periods=["1", "2"],
                cells=[[100.0, 150.0], [110.0, None]],
                grain="quarter",
                reporting_currency="USD",
                insurance_line="HEALTH",
            )
        )
        logger.info("chainladder JIT pre-warm complete")
    except Exception as e:
        logger.warning(f"chainladder pre-warm skipped: {e}")

    kafka_consumer = None
    fraud_producer = None
    report_runner: ReportJobRunner | None = None
    if settings.kafka_bootstrap_servers:
        try:
            from app.core.kafka_producer import ClaimsEventProducer
            fraud_producer = ClaimsEventProducer(
                bootstrap_servers=settings.kafka_bootstrap_servers
            )
            await fraud_producer.start()
            logger.info("Fraud event producer: STARTED")
        except Exception as e:
            logger.warning(f"Fraud event producer failed: {e}")
            fraud_producer = None

        try:
            from app.core.kafka_consumer import ClaimsEventConsumer
            from app.services.adjudication_service import AdjudicationService
            from app.services.fraud_service import FraudService
            adj_svc = AdjudicationService(gemini_module.gemini_client)
            fraud_svc = FraudService()
            kafka_consumer = ClaimsEventConsumer(
                settings.kafka_bootstrap_servers,
                adj_svc,
                fraud_svc,
                fraud_producer=fraud_producer,
            )
            await kafka_consumer.start()
            logger.info("Kafka consumer: STARTED")
        except Exception as e:
            logger.warning(f"Kafka consumer failed: {e}")

        try:
            report_runner = ReportJobRunner(settings.kafka_bootstrap_servers)
            await report_runner.start()
        except Exception as e:
            logger.warning(f"Report job runner failed: {e}")
            report_runner = None

    try:
        start_fraud_poll()
        logger.info("Fraud model registry poll: STARTED")
    except Exception as e:
        logger.warning(f"Fraud model registry poll failed to start: {e}")

    try:
        start_pricing_poll()
        logger.info("Pricing model registry poll: STARTED")
    except Exception as e:
        logger.warning(f"Pricing model registry poll failed to start: {e}")

    yield

    await stop_fraud_poll()
    await stop_pricing_poll()
    if report_runner:
        await report_runner.stop()
    if kafka_consumer:
        await kafka_consumer.stop()
    if fraud_producer:
        await fraud_producer.stop()
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
app.include_router(actuarial_router)
app.include_router(predictions_router)
app.include_router(models_router)
