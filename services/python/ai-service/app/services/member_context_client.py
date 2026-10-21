"""Read-only HTTP client to user-service for a member's multi-line portfolio.

Fetches all active policies for a member across every insurance line in a
single call. Fail-open: any network or parse failure returns None; the
chatbot proceeds without member context rather than blocking the response.
"""
from __future__ import annotations

import logging

import httpx

from app.schemas.member_portfolio import MemberPolicyPortfolio

logger = logging.getLogger(__name__)


class MemberContextClient:
    def __init__(self, user_service_url: str, timeout: float = 2.0) -> None:
        self._url = user_service_url.rstrip("/")
        self._timeout = timeout

    async def fetch_portfolio(
        self, *, tenant_id: str, member_id: str
    ) -> MemberPolicyPortfolio | None:
        """One HTTP call returns all lines. Fail-open — None on any error."""
        try:
            async with httpx.AsyncClient(timeout=self._timeout) as c:
                r = await c.get(
                    f"{self._url}/api/v1/members/{member_id}/policy-portfolio",
                    headers={"X-Tenant-ID": tenant_id},
                )
            if r.status_code != 200:
                logger.debug(
                    "policy-portfolio non-200: %s for member=%s tenant=%s",
                    r.status_code, member_id, tenant_id,
                )
                return None
            return MemberPolicyPortfolio.model_validate(r.json())
        except (httpx.HTTPError, ValueError) as e:
            logger.warning("MemberContextClient fetch failed: %s", e)
            return None
