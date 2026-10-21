"""Unit tests for the multi-line-aware ChatbotService."""
from __future__ import annotations

import pytest

from app.schemas.member_portfolio import (
    DisabilityPolicySummary,
    FuneralPolicySummary,
    HealthPolicySummary,
    LifePolicySummary,
    MemberPolicyPortfolio,
    PropertyPolicySummary,
    TravelPolicySummary,
    VehiclePolicySummary,
)
from app.services.chatbot_service import (
    ChatbotService,
    _cite_line,
    keyword_fallback,
)


class _FakeLLM:
    def __init__(self, available: bool, reply: str = "hello from LLM") -> None:
        self._available = available
        self._reply = reply
        self.last_system = None
        self.last_messages = None

    @property
    def available(self) -> bool:
        return self._available

    async def complete(self, system_prompt: str, messages: list[dict]) -> str:
        self.last_system = system_prompt
        self.last_messages = messages
        return self._reply


class _StubPortfolioFetcher:
    def __init__(self, portfolio: MemberPolicyPortfolio | None) -> None:
        self._portfolio = portfolio
        self.calls: list[tuple[str, str]] = []

    async def fetch_portfolio(self, *, tenant_id: str, member_id: str):
        self.calls.append((tenant_id, member_id))
        return self._portfolio


class _FailingPortfolioFetcher:
    async def fetch_portfolio(self, *, tenant_id: str, member_id: str):
        return None


def _sample_portfolio() -> MemberPolicyPortfolio:
    return MemberPolicyPortfolio(
        health=HealthPolicySummary(
            scheme_name="Gold Plus", annual_limit=5000.0, used_ytd=1500.0,
            remaining=3500.0, currency="USD", dependants_count=2,
        ),
        life=LifePolicySummary(
            sum_assured=100_000.0, currency="USD", beneficiary_count=1,
            policy_start_date="2024-01-01", premium_status="PAID",
        ),
        funeral=FuneralPolicySummary(
            benefit_tier="STANDARD", lives_covered=4, currency="USD",
        ),
        disability=DisabilityPolicySummary(
            benefit_type="TEMPORARY", monthly_benefit_amount=2500.0,
            currency="USD", waiting_period_days=30, benefit_period_years=2,
        ),
        travel=TravelPolicySummary(
            active_trips=[{"destination": "South Africa",
                           "start_date": "2026-10-01",
                           "end_date": "2026-10-10"}],
            annual_multitrip=True,
        ),
        vehicle=[
            VehiclePolicySummary(
                vehicle_id="V-1", make_model="Toyota Fortuner",
                registration="ABC-123", coverage_type="COMPREHENSIVE",
                excess_amount=500.0, currency="USD",
                no_claims_discount_years=3,
            ),
        ],
        property=[
            PropertyPolicySummary(
                property_id="P-1", address_summary="12 Oak Rd, Harare",
                coverage_type="COMPREHENSIVE", excess_amount=250.0, currency="USD",
            ),
        ],
    )


# ── keyword_fallback ------------------------------------------------------


@pytest.mark.parametrize("message,expected_substring", [
    ("How much of my medical is left?",   "Gold Plus"),
    ("What is my life cover sum assured?", "100000.00"),
    ("Tell me about my funeral cover",    "STANDARD"),
    ("What is my disability benefit?",    "TEMPORARY"),
    ("Am I covered on my trip abroad?",   "multi-trip"),
    ("What is my motor excess?",          "500.00"),
    ("What is my home cover excess?",     "250.00"),
])
def test_keyword_fallback_cites_portfolio_line(message, expected_substring):
    result = keyword_fallback(message, _sample_portfolio())
    assert expected_substring in result, f"'{expected_substring}' not in {result!r}"


def test_keyword_fallback_generic_when_no_portfolio_match():
    empty = MemberPolicyPortfolio()
    reply = keyword_fallback("What is my balance?", empty)
    assert "Benefits section" in reply


def test_keyword_fallback_generic_when_no_portfolio_at_all():
    reply = keyword_fallback("Something totally off-topic", None)
    # Falls through to the catch-all
    assert "medical aid, life, funeral" in reply


def test_cite_line_returns_none_when_line_missing():
    empty = MemberPolicyPortfolio()
    assert _cite_line(empty, "health") is None
    assert _cite_line(empty, "vehicle") is None


# ── ChatbotService.respond ------------------------------------------------


@pytest.mark.asyncio
async def test_respond_uses_llm_when_available():
    llm = _FakeLLM(available=True, reply="hi from LLM")
    fetcher = _StubPortfolioFetcher(_sample_portfolio())
    svc = ChatbotService(llm, member_context_client=fetcher)

    result = await svc.respond(
        message="How much of my medical is left?",
        conversation_id="c1",
        context={"member_id": "m1"},
        tenant_id="t1",
        session=None,
    )
    assert result["source"] == "ai"
    assert result["reply"] == "hi from LLM"
    assert result["conversation_id"] == "c1"
    # Portfolio was fetched
    assert fetcher.calls == [("t1", "m1")]
    # System prompt names the portfolio
    assert "Gold Plus" in llm.last_system


@pytest.mark.asyncio
async def test_respond_falls_back_when_llm_unavailable():
    llm = _FakeLLM(available=False)
    fetcher = _StubPortfolioFetcher(_sample_portfolio())
    svc = ChatbotService(llm, member_context_client=fetcher)

    result = await svc.respond(
        message="What is my motor excess?",
        conversation_id="c2",
        context={"member_id": "m1"},
        tenant_id="t1",
        session=None,
    )
    assert result["source"] == "fallback"
    assert "500.00" in result["reply"]


@pytest.mark.asyncio
async def test_respond_survives_member_context_failure():
    llm = _FakeLLM(available=False)
    fetcher = _FailingPortfolioFetcher()
    svc = ChatbotService(llm, member_context_client=fetcher)

    result = await svc.respond(
        message="What is my medical balance?",
        conversation_id="c3",
        context={"member_id": "m1"},
        tenant_id="t1",
        session=None,
    )
    # No portfolio → generic response, but no exception
    assert result["source"] == "fallback"
    assert result["reply"]


@pytest.mark.asyncio
async def test_respond_uses_in_memory_history_when_no_session():
    llm = _FakeLLM(available=True, reply="reply-B")
    svc = ChatbotService(llm)

    await svc.respond(
        message="first message", conversation_id="c4",
        tenant_id="t1", session=None,
    )
    llm._reply = "reply-B2"
    await svc.respond(
        message="second message", conversation_id="c4",
        tenant_id="t1", session=None,
    )

    # LLM saw the earlier assistant turn (reply-B) in the history
    assistant_contents = [
        m["content"] for m in llm.last_messages if m.get("role") == "assistant"
    ]
    assert "reply-B" in assistant_contents
