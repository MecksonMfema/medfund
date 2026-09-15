"""Tests for LLM provider dispatch."""
from __future__ import annotations

import pytest

from app.core.anthropic_client import ClaudeClient
from app.core.config import Settings
from app.core.gemini_client import GeminiClient
from app.core.llm_dispatch import resolve_llm


class _StubGemini:
    available = False

    async def complete(self, *_a, **_kw): return None
    async def complete_json(self, *_a, **_kw): return None


class _StubClaude:
    available = False

    async def complete(self, *_a, **_kw): return None
    async def complete_json(self, *_a, **_kw): return None


@pytest.mark.parametrize("provider,expect_claude", [
    ("gemini", False),
    ("GEMINI", False),
    ("claude", True),
    ("CLAUDE", True),
    ("bogus", False),   # unknown → default to gemini
    ("", False),
    (None, False),
])
def test_resolve_llm_picks_provider(provider, expect_claude):
    gemini = _StubGemini()
    claude = _StubClaude()
    settings = Settings(llm_provider=provider) if provider is not None else Settings()
    if provider is None:
        settings.llm_provider = None  # type: ignore[assignment]
    result = resolve_llm(settings, gemini=gemini, claude=claude)
    if expect_claude:
        assert result is claude
    else:
        assert result is gemini


def test_resolve_llm_defaults_to_module_globals():
    """Not passing gemini/claude arguments should still return SOME client."""
    result = resolve_llm(Settings())
    # Either gemini_client or claude_client (module singletons); both have .available attr.
    assert hasattr(result, "available")
    assert isinstance(result, (GeminiClient, ClaudeClient))


def test_claude_client_available_only_with_key():
    assert ClaudeClient(api_key="").available is False
    # non-empty key path (importing anthropic under the hood may fail if it's
    # not installed — but the ClaudeClient constructor swallows that)
    c = ClaudeClient(api_key="fake-key-123")
    # Either it initialized (True) or the anthropic lib is not installed
    # (False). Either way, the "no key" case must be strictly False.
    assert isinstance(c.available, bool)


def test_gemini_client_available_only_with_key():
    assert GeminiClient(api_key="").available is False
    c = GeminiClient(api_key="fake-key-123")
    assert isinstance(c.available, bool)
