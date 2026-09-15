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

    model_config = {"env_prefix": "MEDFUND_"}


settings = Settings()
