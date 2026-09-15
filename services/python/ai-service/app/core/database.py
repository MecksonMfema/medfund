"""Async database engine and session management."""
import logging
from sqlalchemy.ext.asyncio import create_async_engine, async_sessionmaker, AsyncSession
from sqlalchemy.orm import DeclarativeBase

logger = logging.getLogger(__name__)


class Base(DeclarativeBase):
    pass


engine = None
async_session_factory = None


async def init_db(database_url: str):
    """Initialize database engine and create tables."""
    global engine, async_session_factory

    # Use SQLite for testing if URL starts with sqlite
    if "sqlite" in database_url:
        engine = create_async_engine(database_url, echo=False)
    else:
        engine = create_async_engine(
            database_url,
            echo=False,
            pool_size=5,
            max_overflow=10,
        )

    async_session_factory = async_sessionmaker(engine, class_=AsyncSession, expire_on_commit=False)

    # Import models to register them with Base
    import app.models.db_models  # noqa: F401

    async with engine.begin() as conn:
        await conn.run_sync(Base.metadata.create_all)

    # Idempotent column additions for existing DBs (Base.metadata.create_all
    # only handles new tables). Postgres-only; SQLite skips inside the hook.
    from app.db.upgrades import ensure_columns
    await ensure_columns(engine)

    logger.info("Database initialized")


async def close_db():
    """Close database engine."""
    global engine
    if engine:
        await engine.dispose()
        logger.info("Database closed")


async def get_session() -> AsyncSession:
    """Get an async database session."""
    if async_session_factory is None:
        raise RuntimeError("Database not initialized. Call init_db first.")
    async with async_session_factory() as session:
        yield session


async def get_optional_session():
    """Yield an AsyncSession when the DB is initialized, else yield None.

    Endpoints use this so a stubbed / down DB never fails a request —
    persistence becomes a best-effort side effect. This preserves the
    fail-open contract with claims-service while still writing the
    Critical Rule #3 audit row when the DB is available.
    """
    if async_session_factory is None:
        yield None
        return
    async with async_session_factory() as session:
        yield session
