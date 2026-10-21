from pydantic_settings import BaseSettings


class Settings(BaseSettings):
    app_name: str = "MedFund AI Service"
    database_url: str = "postgresql+asyncpg://medfund:medfund@localhost:5433/medfund"
    kafka_bootstrap_servers: str = "localhost:9092"
    redis_url: str = "redis://localhost:6380/0"
    anthropic_api_key: str = ""
    anthropic_model: str = "claude-sonnet-4-5-20250929"
    gemini_api_key: str = ""
    gemini_model: str = "gemini-2.0-flash"
    llm_provider: str = "gemini"  # "gemini" | "claude"
    user_service_url: str = "http://localhost:8082"
    log_level: str = "INFO"

    # ── Model artifact / training gates ─────────────────────────────────
    # G4: cross-tenant training is gated on a formal anonymization audit.
    # Set to true only when the auditor signs off
    # (thoughts/shared/audit/2026-09-15-ai-training-corpus-anonymization.md).
    training_audit_signed_off: bool = False

    # G6: model artifacts live in MinIO in both dev and prod. Set to false
    # to force the canonical fallback everywhere (kill switch).
    model_artifacts_enabled: bool = True
    model_artifacts_bucket: str = "medfund-ml-artifacts"
    model_manifest_key: str = "manifest.json"
    # 60s per-pod poll cadence — pilot promotion latency lives here.
    model_poll_interval_seconds: int = 60

    # G7: bump this when the fraud extractor's canonical feature schema
    # changes. Trained artifacts tagged with a mismatching version are
    # rejected at load time and the admin surface (Phase 5) flags them.
    canonical_features_schema_version: str = "v1"

    model_config = {"env_prefix": "MEDFUND_"}


settings = Settings()
