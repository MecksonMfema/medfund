"""Anthropic Claude client wrapper — mirrors GeminiClient shape.

`.available` is True iff an API key was passed. Endpoints depend on the
common LlmClient protocol (see app/core/llm_dispatch.py) so a provider
swap is a config change, not a code change.
"""
from __future__ import annotations

import logging
from typing import Optional

logger = logging.getLogger(__name__)


class ClaudeClient:
    def __init__(
        self,
        api_key: str = "",
        model: str = "claude-sonnet-4-5-20250929",
    ) -> None:
        self._client = None
        self._available = False
        self._model = model

        if api_key:
            try:
                import anthropic

                self._client = anthropic.AsyncAnthropic(api_key=api_key)
                self._available = True
                logger.info(f"Claude client initialized (model: {model})")
            except Exception as e:
                logger.warning(f"Failed to initialize Claude client: {e}")
        else:
            logger.info(
                "Claude client not initialized — MEDFUND_ANTHROPIC_API_KEY not set. "
                "Falling back to Gemini or rule-based paths."
            )

    @property
    def available(self) -> bool:
        return self._available

    async def complete(
        self,
        system_prompt: str,
        messages: list[dict],
        max_tokens: int = 1024,
        model: str = "",
    ) -> Optional[str]:
        if not self._available:
            return None
        try:
            response = await self._client.messages.create(
                model=model or self._model,
                max_tokens=max_tokens,
                system=system_prompt,
                messages=messages,
            )
            return response.content[0].text
        except Exception as e:
            logger.error(f"Claude API call failed: {e}")
            return None

    async def complete_json(
        self,
        system_prompt: str,
        messages: list[dict],
        max_tokens: int = 1024,
    ) -> Optional[dict]:
        import json

        text = await self.complete(
            system_prompt=(
                system_prompt
                + "\n\nRespond ONLY with valid JSON, no markdown or explanation."
            ),
            messages=messages,
            max_tokens=max_tokens,
        )
        if text is None:
            return None
        try:
            cleaned = text.strip()
            if cleaned.startswith("```"):
                cleaned = cleaned.split("\n", 1)[1] if "\n" in cleaned else cleaned
                cleaned = cleaned.rsplit("```", 1)[0]
            return json.loads(cleaned)
        except json.JSONDecodeError:
            logger.warning(f"Failed to parse Claude response as JSON: {text[:200]}")
            return None


# Global instance — initialized in main.py lifespan
claude_client: ClaudeClient = ClaudeClient()
