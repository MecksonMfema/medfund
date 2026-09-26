"""Tests for Claude client wrapper."""
from unittest.mock import patch

import pytest

from app.core.anthropic_client import ClaudeClient


def test_client_unavailable_without_key():
    client = ClaudeClient(api_key="")
    assert client.available is False


def test_client_unavailable_with_empty_key():
    client = ClaudeClient(api_key="")
    assert client.available is False


@pytest.mark.asyncio
async def test_complete_returns_none_when_unavailable():
    client = ClaudeClient(api_key="")
    result = await client.complete("system", [{"role": "user", "content": "test"}])
    assert result is None


@pytest.mark.asyncio
async def test_complete_json_returns_none_when_unavailable():
    client = ClaudeClient(api_key="")
    result = await client.complete_json("system", [{"role": "user", "content": "test"}])
    assert result is None


def test_client_initializes_with_key():
    with patch("anthropic.AsyncAnthropic") as mock_cls:
        client = ClaudeClient(api_key="test-key-123")
        assert client.available is True
        mock_cls.assert_called_once_with(api_key="test-key-123")
