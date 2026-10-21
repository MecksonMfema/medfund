"""Chatbot service — persists conversation turns and enriches prompts with
a member's multi-line policy portfolio.

Every response path (LLM success and rule fallback) writes both the user
turn and the assistant turn to ``conversation_messages`` before returning,
so a process restart preserves history via
``get_conversation_history``.
"""
from __future__ import annotations

import logging
import uuid
from collections.abc import Callable, Iterable
from typing import Any

from sqlalchemy.ext.asyncio import AsyncSession

from app.schemas.member_portfolio import (
    MemberPolicyPortfolio,
    PropertyPolicySummary,
    VehiclePolicySummary,
)
from app.services.member_context_client import MemberContextClient
from app.services.prediction_repository import (
    get_conversation_history,
    save_conversation_message,
)

logger = logging.getLogger(__name__)


SYSTEM_PROMPT = (
    "You are a multi-line insurance benefits assistant for the InsureFlow platform. "
    "You help members understand their benefits across medical aid, life, funeral, "
    "disability, travel, motor and property cover, check claim status, and answer FAQs. "
    "You NEVER modify any data — only provide information and guidance. "
    "Keep responses concise and friendly. If the member's portfolio names an amount, "
    "quote it verbatim (do not round or estimate)."
)


class ChatbotService:
    def __init__(
        self,
        gemini_client=None,
        member_context_client: MemberContextClient | None = None,
        llm_provider: Callable[[], Any] | None = None,
    ) -> None:
        # `llm_provider` is called on every respond() so a runtime provider
        # switch (Gemini ↔ Claude) is picked up without re-instantiating
        # the service. `gemini_client` is kept for backwards compat.
        self._llm_provider = llm_provider
        self.gemini_client = gemini_client
        self._member_context = member_context_client
        # In-memory fallback still used when no DB session is provided
        # (e.g. tests that stub out get_optional_session with None).
        self._conversations: dict[str, list[dict]] = {}

    def _current_llm(self):
        if self._llm_provider is not None:
            return self._llm_provider()
        return self.gemini_client

    async def respond(
        self,
        message: str,
        conversation_id: str | None = None,
        context: dict | None = None,
        tenant_id: str = "",
        session: AsyncSession | None = None,
    ) -> dict:
        conv_id = conversation_id or str(uuid.uuid4())
        ctx = context or {}
        member_id = ctx.get("member_id")

        history = await self._load_history(session, conv_id)

        portfolio: MemberPolicyPortfolio | None = None
        if member_id and self._member_context is not None:
            portfolio = await self._member_context.fetch_portfolio(
                tenant_id=tenant_id, member_id=str(member_id),
            )

        reply, source, confidence, model_version = await self._compose_reply(
            message=message, history=history, portfolio=portfolio, context=ctx,
        )

        await self._persist_turns(
            session=session,
            conv_id=conv_id,
            tenant_id=tenant_id,
            user_message=message,
            assistant_reply=reply,
        )

        return {
            "reply": reply,
            "conversation_id": conv_id,
            "source": source,
            "confidence": confidence,
            "model_version": model_version,
        }

    async def _compose_reply(
        self,
        *,
        message: str,
        history: list[dict[str, str]],
        portfolio: MemberPolicyPortfolio | None,
        context: dict[str, Any],
    ) -> tuple[str, str, float, str]:
        turns = list(history) + [{"role": "user", "content": message}]
        llm = self._current_llm()

        if llm is not None and llm.available:
            try:
                system = self._build_system_prompt(portfolio, context)
                reply = await llm.complete(
                    system_prompt=system,
                    messages=turns[-10:],
                )
                if reply:
                    return reply, "ai", 0.85, "chatbot-llm-v1"
            except Exception as e:
                logger.warning("Chatbot LLM path failed: %s", e)

        fallback = keyword_fallback(message, portfolio)
        return fallback, "fallback", 0.4, "chatbot-rules-v1"

    def _build_system_prompt(
        self,
        portfolio: MemberPolicyPortfolio | None,
        context: dict[str, Any],
    ) -> str:
        parts: list[str] = [SYSTEM_PROMPT]
        if portfolio is not None and not portfolio.is_empty():
            parts.append("\nMember portfolio:\n" + _portfolio_lines(portfolio))
        if context:
            # Preserve arbitrary caller-supplied context after stripping member_id
            trimmed = {k: v for k, v in context.items() if k not in {"member_id"}}
            if trimmed:
                parts.append(f"\nAdditional context: {trimmed}")
        return "\n".join(parts)

    async def _load_history(
        self, session: AsyncSession | None, conv_id: str
    ) -> list[dict[str, str]]:
        if session is None:
            return list(self._conversations.get(conv_id, []))
        rows = await get_conversation_history(session, conversation_id=conv_id, limit=20)
        return [{"role": r.role, "content": r.content} for r in rows]

    async def _persist_turns(
        self,
        *,
        session: AsyncSession | None,
        conv_id: str,
        tenant_id: str,
        user_message: str,
        assistant_reply: str,
    ) -> None:
        if session is None:
            self._conversations.setdefault(conv_id, []).extend([
                {"role": "user", "content": user_message},
                {"role": "assistant", "content": assistant_reply},
            ])
            return
        try:
            await save_conversation_message(
                session, conversation_id=conv_id, tenant_id=tenant_id,
                role="user", content=user_message,
            )
            await save_conversation_message(
                session, conversation_id=conv_id, tenant_id=tenant_id,
                role="assistant", content=assistant_reply,
            )
        except Exception:
            logger.exception("Failed to persist chat turns for conv=%s", conv_id)


# ── Keyword fallback --------------------------------------------------

# Order matters — earlier keys win when multiple keywords hit. Property is
# listed before vehicle because "home"/"house" is a clearer property signal
# than "excess" is a vehicle signal.
_LINE_KEYWORDS: dict[str, tuple[str, ...]] = {
    "health":     ("medical", "health", "hospital", "chronic", "gp", "consultation"),
    "life":       ("life cover", "life insurance", "life policy", "sum assured", "beneficiary"),
    "funeral":    ("funeral", "burial", "casket"),
    "disability": ("disability", "income protection", "income benefit", "monthly benefit"),
    "travel":     ("travel", "trip", "abroad", "baggage", "cancellation"),
    "property":   ("home ", "house", "property", "building", "contents"),
    "vehicle":    ("car ", "motor", "vehicle", "windscreen", "collision"),
    "group":      ("group", "employer",),
}


def _detect_line(message: str) -> str | None:
    m = message.lower()
    for line, keywords in _LINE_KEYWORDS.items():
        if any(kw in m for kw in keywords):
            return line
    return None


def keyword_fallback(
    message: str, portfolio: MemberPolicyPortfolio | None
) -> str:
    """Rule fallback for when the LLM is unavailable.

    Attempts to detect an insurance line from the message text and cite the
    matching portfolio entry verbatim. Falls back to a generic response
    when no line or portfolio field matches.
    """
    line = _detect_line(message)

    if portfolio is not None and line is not None:
        cited = _cite_line(portfolio, line)
        if cited is not None:
            return cited

    m = message.lower()
    if "balance" in m or "benefit" in m:
        return (
            "To check your benefit balance, please visit the Benefits section in your "
            "dashboard or contact your scheme administrator."
        )
    if "claim" in m:
        return (
            "For claim status inquiries, please check the Claims section in your "
            "dashboard. If you need further help, contact our support team."
        )
    if "payment" in m or "premium" in m:
        return (
            "For payment or premium information, please check the Payments section in "
            "your dashboard."
        )
    return (
        "Thank you for your message. Our AI assistant can help with benefits, claims, "
        "payments, and policy questions across medical aid, life, funeral, disability, "
        "travel, motor and property cover. Please contact support for complex inquiries."
    )


def _cite_line(portfolio: MemberPolicyPortfolio, line: str) -> str | None:
    if line == "health" and portfolio.health is not None:
        h = portfolio.health
        return (
            f"Your {h.scheme_name} medical aid has {h.remaining:.2f} {h.currency} "
            f"remaining for the year (annual limit {h.annual_limit:.2f} {h.currency}, "
            f"used {h.used_ytd:.2f} {h.currency}). Dependants covered: {h.dependants_count}."
        )
    if line == "life" and portfolio.life is not None:
        life = portfolio.life
        return (
            f"Your life cover has a sum assured of {life.sum_assured:.2f} {life.currency} "
            f"with {life.beneficiary_count} beneficiary(ies). Premium status: {life.premium_status}."
        )
    if line == "funeral" and portfolio.funeral is not None:
        f = portfolio.funeral
        return (
            f"Your funeral cover is on the {f.benefit_tier} tier covering "
            f"{f.lives_covered} live(s). Premium status: {f.premium_status}."
        )
    if line == "disability" and portfolio.disability is not None:
        d = portfolio.disability
        period = "lifetime" if d.benefit_period_years is None else f"{d.benefit_period_years} year(s)"
        return (
            f"Your {d.benefit_type} disability cover pays "
            f"{d.monthly_benefit_amount:.2f} {d.currency} per month after a "
            f"{d.waiting_period_days}-day waiting period, for up to {period}."
        )
    if line == "travel" and portfolio.travel is not None:
        t = portfolio.travel
        if t.annual_multitrip:
            return f"You have an annual multi-trip travel policy. Active trips: {len(t.active_trips)}."
        if t.active_trips:
            return _travel_trip_summary(t.active_trips)
        return "You have a travel policy but no active trips at the moment."
    if line == "vehicle" and portfolio.vehicle:
        return _vehicle_summary(portfolio.vehicle)
    if line == "property" and portfolio.property:
        return _property_summary(portfolio.property)
    if line == "group" and portfolio.group:
        return f"You are a member of {len(portfolio.group)} group scheme(s)."
    return None


def _travel_trip_summary(trips: Iterable[dict]) -> str:
    trip_list = list(trips)
    if not trip_list:
        return "You have no active travel trips."
    lines = []
    for t in trip_list[:3]:
        dest = t.get("destination", "unknown")
        start = t.get("start_date", "?")
        end = t.get("end_date", "?")
        lines.append(f"- {dest}: {start} to {end}")
    return "Your active travel trips:\n" + "\n".join(lines)


def _vehicle_summary(vehicles: list[VehiclePolicySummary]) -> str:
    if len(vehicles) == 1:
        v = vehicles[0]
        return (
            f"Your {v.make_model} ({v.registration}) is on {v.coverage_type} cover with "
            f"a {v.excess_amount:.2f} {v.currency} excess. No-claims discount: "
            f"{v.no_claims_discount_years} year(s)."
        )
    return "Your vehicles:\n" + "\n".join(
        f"- {v.make_model} ({v.registration}): {v.coverage_type}, excess {v.excess_amount:.2f} {v.currency}"
        for v in vehicles
    )


def _property_summary(properties: list[PropertyPolicySummary]) -> str:
    if len(properties) == 1:
        p = properties[0]
        return (
            f"Your property at {p.address_summary} is on {p.coverage_type} cover with a "
            f"{p.excess_amount:.2f} {p.currency} excess."
        )
    return "Your properties:\n" + "\n".join(
        f"- {p.address_summary}: {p.coverage_type}, excess {p.excess_amount:.2f} {p.currency}"
        for p in properties
    )


def _portfolio_lines(portfolio: MemberPolicyPortfolio) -> str:
    """Compact single-string summary passed to the LLM system prompt."""
    lines: list[str] = []
    if portfolio.health is not None:
        h = portfolio.health
        lines.append(
            f"HEALTH scheme={h.scheme_name} remaining={h.remaining:.2f} {h.currency}"
            f" annual_limit={h.annual_limit:.2f} used_ytd={h.used_ytd:.2f}"
            f" dependants={h.dependants_count}"
        )
    if portfolio.life is not None:
        life = portfolio.life
        lines.append(
            f"LIFE sum_assured={life.sum_assured:.2f} {life.currency}"
            f" beneficiaries={life.beneficiary_count} premium_status={life.premium_status}"
        )
    if portfolio.funeral is not None:
        f = portfolio.funeral
        lines.append(
            f"FUNERAL tier={f.benefit_tier} lives_covered={f.lives_covered}"
            f" premium_status={f.premium_status}"
        )
    if portfolio.disability is not None:
        d = portfolio.disability
        lines.append(
            f"DISABILITY type={d.benefit_type} monthly={d.monthly_benefit_amount:.2f} {d.currency}"
            f" waiting_days={d.waiting_period_days} benefit_period_years={d.benefit_period_years}"
        )
    if portfolio.travel is not None:
        t = portfolio.travel
        lines.append(
            f"TRAVEL annual_multitrip={t.annual_multitrip} active_trips={len(t.active_trips)}"
        )
    for v in portfolio.vehicle:
        lines.append(
            f"VEHICLE {v.make_model} reg={v.registration} cover={v.coverage_type}"
            f" excess={v.excess_amount:.2f} {v.currency}"
        )
    for p in portfolio.property:
        lines.append(
            f"PROPERTY addr={p.address_summary} cover={p.coverage_type}"
            f" excess={p.excess_amount:.2f} {p.currency}"
        )
    for g in portfolio.group:
        name = g.get("scheme_name", "group scheme") if isinstance(g, dict) else "group scheme"
        lines.append(f"GROUP {name}")
    return "\n".join(lines)
