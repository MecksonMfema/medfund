"""Unit tests for ClaimsEventProducer (Phase 19 §A Phase 3)."""
from unittest.mock import AsyncMock, MagicMock

import pytest

from app.core.kafka_producer import ClaimsEventProducer


async def test_publish_fraud_flagged_sends_json_encoded_event_with_tenant_key(monkeypatch):
    """The producer wires tenantId as the partition key so per-tenant
    ordering is preserved on the fraud-flagged topic."""
    producer = ClaimsEventProducer(bootstrap_servers="localhost:9092")
    mock_kafka = AsyncMock()
    monkeypatch.setattr(
        "app.core.kafka_producer.AIOKafkaProducer",
        MagicMock(return_value=mock_kafka),
    )

    await producer.start()
    event = {"tenantId": "t-1", "claimId": "c-1", "riskLevel": "HIGH"}
    await producer.publish_fraud_flagged(event)

    mock_kafka.send_and_wait.assert_awaited_once()
    call = mock_kafka.send_and_wait.call_args
    assert call.args[0] == ClaimsEventProducer.TOPIC_FRAUD_FLAGGED
    assert call.kwargs["value"] == event
    assert call.kwargs["key"] == b"t-1"


async def test_publish_before_start_raises():
    producer = ClaimsEventProducer(bootstrap_servers="localhost:9092")
    with pytest.raises(RuntimeError, match="start"):
        await producer.publish_fraud_flagged({"tenantId": "t-1"})


async def test_stop_before_start_is_noop():
    producer = ClaimsEventProducer(bootstrap_servers="localhost:9092")
    # Should not raise even if start() was never called
    await producer.stop()


async def test_start_stop_lifecycle(monkeypatch):
    producer = ClaimsEventProducer(bootstrap_servers="localhost:9092")
    mock_kafka = AsyncMock()
    monkeypatch.setattr(
        "app.core.kafka_producer.AIOKafkaProducer",
        MagicMock(return_value=mock_kafka),
    )

    await producer.start()
    mock_kafka.start.assert_awaited_once()

    await producer.stop()
    mock_kafka.stop.assert_awaited_once()
