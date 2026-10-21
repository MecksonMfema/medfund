"""Runtime LLM provider selection.

`MEDFUND_LLM_PROVIDER` chooses between the Gemini and Claude clients. If
the chosen provider has no key, the returned client's `.available` is
False and endpoints fall back to their rule paths.

Reads through module attributes on every call so the lifespan handler's
re-assignment of `gemini_client` / `claude_client` is respected.
"""
from __future__ import annotations

from typing import Protocol

import app.core.anthropic_client as anthropic_module
import app.core.gemini_client as gemini_module
from app.core.anthropic_client import ClaudeClient
from app.core.config import Settings, settings
from app.core.gemini_client import GeminiClient


class LlmClient(Protocol):
    @property
    def available(self) -> bool: ...
    async def complete(
        self, system_prompt: str, messages: list[dict], max_tokens: int = ...,
    ) -> str | None: ...
    async def complete_json(
        self, system_prompt: str, messages: list[dict], max_tokens: int = ...,
    ) -> dict | None: ...


def resolve_llm(
    cfg: Settings | None = None,
    *,
    gemini: GeminiClient | None = None,
    claude: ClaudeClient | None = None,
) -> LlmClient:
    """Pick the active LLM per settings.llm_provider."""
    cfg = cfg or settings
    g = gemini if gemini is not None else gemini_module.gemini_client
    c = claude if claude is not None else anthropic_module.claude_client
    if (cfg.llm_provider or "gemini").lower() == "claude":
        return c
    return g


def get_active_llm() -> LlmClient:
    """FastAPI dependency wrapper."""
    return resolve_llm()
