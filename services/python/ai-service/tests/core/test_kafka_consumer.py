"""Unit tests for ClaimsEventConsumer's fraud-flag publishing path.

Phase 19 §A Phase 3 wires the consumer to republish every classified claim
onto medfund.claims.fraud-flagged (Rule 3 audit-of-record). These tests
cover the publish path in isolation without booting Kafka.
"""
from unittest.mock import AsyncMock

from app.core.kafka_consumer import ClaimsEventConsumer
from app.models.prediction import AIPrediction


def _adjudication_result() -> AIPrediction:
    return AIPrediction(
        tenant_id="t-1",
        entity_type="claim",
        entity_id="c-1",
        prediction_type="adjudication",
        confidence=0.9,
        output={"recommendation": "APPROVE"},
    )


def _fraud_result(risk_level: str = "HIGH", risk_score: float = 0.87) -> AIPrediction:
    return AIPrediction(
        tenant_id="t-1",
        entity_type="claim",
        entity_id="c-1",
        prediction_type="fraud_detection",
        model_version="fraud-isolation-forest-v1.2.0",
        confidence=1.0 - risk_score,
        output={
            "risk_score": risk_score,
            "risk_level": risk_level,
            "indicators": ["frequent_visits", "unusual_time_of_day"],
        },
    )


async def test_process_event_publishes_fraud_flag_after_prediction():
    adj_svc = AsyncMock()
    adj_svc.analyze_claim.return_value = _adjudication_result()
    fraud_svc = AsyncMock()
    fraud_svc.detect_fraud.return_value = _fraud_result()
    producer = AsyncMock()

    consumer = ClaimsEventConsumer(
        bootstrap_servers="localhost:9092",
        adjudication_service=adj_svc,
        fraud_service=fraud_svc,
        fraud_producer=producer,
    )

    event = {
        "event": "CLAIM_SUBMITTED",
        "tenantId": "t-1",
        "claimId": "c-1",
        "memberId": "m-1",
        "claimedAmount": 1500,
        "correlationId": "req-1",
    }
    await consumer.process_event(event)

    producer.publish_fraud_flagged.assert_awaited_once()
    payload = producer.publish_fraud_flagged.call_args.args[0]
    assert payload["eventType"] == "FRAUD_FLAG_EMITTED"
    assert payload["riskLevel"] == "HIGH"
    assert payload["riskScore"] == 0.87
    assert payload["claimId"] == "c-1"
    assert payload["tenantId"] == "t-1"
    assert payload["correlationId"] == "req-1"
    assert payload["modelVersion"] == "fraud-isolation-forest-v1.2.0"
    assert payload["indicators"] == ["frequent_visits", "unusual_time_of_day"]
    assert "eventId" in payload
    assert "occurredAt" in payload
    assert "computedAt" in payload


async def test_process_event_skips_publish_when_producer_absent():
    """Consumer must remain usable without a producer wired in — the
    lifespan degrades gracefully when producer start() fails."""
    adj_svc = AsyncMock()
    adj_svc.analyze_claim.return_value = _adjudication_result()
    fraud_svc = AsyncMock()
    fraud_svc.detect_fraud.return_value = _fraud_result()

    consumer = ClaimsEventConsumer(
        bootstrap_servers="localhost:9092",
        adjudication_service=adj_svc,
        fraud_service=fraud_svc,
        fraud_producer=None,
    )

    await consumer.process_event({
        "event": "CLAIM_SUBMITTED",
        "tenantId": "t-1",
        "claimId": "c-1",
        "memberId": "m-1",
        "claimedAmount": 1500,
    })

    # No exception, and the two AI services still ran
    adj_svc.analyze_claim.assert_awaited_once()
    fraud_svc.detect_fraud.assert_awaited_once()


async def test_process_event_ignores_non_submitted_events():
    """The consumer only reacts to CLAIM_SUBMITTED; other event types must
    not trigger fraud detection or fraud-flag publishing."""
    adj_svc = AsyncMock()
    fraud_svc = AsyncMock()
    producer = AsyncMock()

    consumer = ClaimsEventConsumer(
        bootstrap_servers="localhost:9092",
        adjudication_service=adj_svc,
        fraud_service=fraud_svc,
        fraud_producer=producer,
    )

    await consumer.process_event({
        "event": "CLAIM_APPROVED",
        "tenantId": "t-1",
        "claimId": "c-1",
    })

    adj_svc.analyze_claim.assert_not_awaited()
    fraud_svc.detect_fraud.assert_not_awaited()
    producer.publish_fraud_flagged.assert_not_awaited()


async def test_process_event_swallows_producer_error():
    """A broken producer must not derail claim processing — Kafka can be
    intermittently unreachable and the AI outputs are still useful even
    when the audit-of-record envelope is dropped."""
    adj_svc = AsyncMock()
    adj_svc.analyze_claim.return_value = _adjudication_result()
    fraud_svc = AsyncMock()
    fraud_svc.detect_fraud.return_value = _fraud_result()
    producer = AsyncMock()
    producer.publish_fraud_flagged.side_effect = RuntimeError("kafka down")

    consumer = ClaimsEventConsumer(
        bootstrap_servers="localhost:9092",
        adjudication_service=adj_svc,
        fraud_service=fraud_svc,
        fraud_producer=producer,
    )

    # Should not raise
    await consumer.process_event({
        "event": "CLAIM_SUBMITTED",
        "tenantId": "t-1",
        "claimId": "c-1",
        "memberId": "m-1",
        "claimedAmount": 1500,
    })
    producer.publish_fraud_flagged.assert_awaited_once()
