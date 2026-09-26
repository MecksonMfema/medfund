"""Google Gemini client wrapper with graceful fallback."""
import logging

logger = logging.getLogger(__name__)


class GeminiClient:
    """Wrapper around Google's Gemini async client. Falls back to None when API key is not set."""

    def __init__(self, api_key: str = "", model: str = "gemini-2.0-flash"):
        self._client = None
        self._available = False
        self._model = model

        if api_key:
            try:
                import google.generativeai as genai
                genai.configure(api_key=api_key)
                self._client = genai.GenerativeModel(model)
                self._available = True
                logger.info(f"Gemini client initialized successfully (model: {model})")
            except Exception as e:
                logger.warning(f"Failed to initialize Gemini client: {e}")
        else:
            logger.info("Gemini client not initialized — MEDFUND_GEMINI_API_KEY not set. Using rule-based fallbacks.")

    @property
    def available(self) -> bool:
        return self._available

    async def complete(
        self,
        system_prompt: str,
        messages: list[dict],
        max_tokens: int = 1024,
        model: str = "",
    ) -> str | None:
        """Send a completion request to Gemini. Returns None if client unavailable."""
        if not self._available:
            return None

        try:
            import google.generativeai as genai

            # Use a different model if requested
            client = self._client
            if model and model != self._model:
                client = genai.GenerativeModel(model)

            # Build prompt: prepend system prompt then alternate user/model turns
            full_prompt = f"{system_prompt}\n\n"
            for msg in messages:
                role = "User" if msg["role"] == "user" else "Assistant"
                full_prompt += f"{role}: {msg['content']}\n"

            response = await client.generate_content_async(
                full_prompt,
                generation_config={"max_output_tokens": max_tokens},
            )
            return response.text
        except Exception as e:
            logger.error(f"Gemini API call failed: {e}")
            return None

    async def complete_json(
        self,
        system_prompt: str,
        messages: list[dict],
        max_tokens: int = 1024,
    ) -> dict | None:
        """Send a completion request expecting JSON response. Parses and returns dict."""
        import json

        text = await self.complete(
            system_prompt=system_prompt + "\n\nRespond ONLY with valid JSON, no markdown or explanation.",
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
            logger.warning(f"Failed to parse Gemini response as JSON: {text[:200]}")
            return None


# Global instance — initialized in main.py lifespan
gemini_client: GeminiClient = GeminiClient()
