"""httpx wrapper: bearer token + optional X-Tenant-ID + retry on 5xx."""

from __future__ import annotations

import asyncio

import httpx
import structlog

from .auth import KeycloakTokenClient

log = structlog.get_logger()


class SeederClient:
    def __init__(self, http: httpx.AsyncClient, tokens: KeycloakTokenClient):
        self._http = http
        self._tokens = tokens

    async def _headers(self, tenant_id: str | None) -> dict[str, str]:
        headers = {"Authorization": f"Bearer {await self._tokens.get_token(self._http)}"}
        if tenant_id:
            headers["X-Tenant-ID"] = tenant_id
        return headers

    async def _request(
        self,
        method: str,
        url: str,
        *,
        tenant_id: str | None = None,
        **kwargs,
    ) -> httpx.Response:
        headers = await self._headers(tenant_id)
        # merge caller-supplied headers on top of auth headers
        headers.update(kwargs.pop("headers", None) or {})
        for attempt in range(3):
            resp = await self._http.request(method, url, headers=headers, **kwargs)
            if resp.status_code < 500:
                if resp.status_code >= 400:
                    log.warning(
                        "seeder.http.4xx",
                        method=method,
                        url=url,
                        status=resp.status_code,
                        body=resp.text[:500],
                    )
                return resp
            log.warning(
                "seeder.http.5xx-retry",
                method=method,
                url=url,
                status=resp.status_code,
                attempt=attempt + 1,
            )
            await asyncio.sleep(2**attempt)
        resp.raise_for_status()
        return resp

    async def get(self, url: str, *, tenant_id: str | None = None, **kwargs) -> httpx.Response:
        return await self._request("GET", url, tenant_id=tenant_id, **kwargs)

    async def post(
        self, url: str, *, json: dict | list | None = None, tenant_id: str | None = None, **kwargs
    ) -> httpx.Response:
        return await self._request("POST", url, tenant_id=tenant_id, json=json, **kwargs)

    async def put(
        self, url: str, *, json: dict | list | None = None, tenant_id: str | None = None, **kwargs
    ) -> httpx.Response:
        return await self._request("PUT", url, tenant_id=tenant_id, json=json, **kwargs)

    async def delete(self, url: str, *, tenant_id: str | None = None, **kwargs) -> httpx.Response:
        return await self._request("DELETE", url, tenant_id=tenant_id, **kwargs)

    async def post_no_retry(
        self,
        url: str,
        *,
        json: dict | list | None = None,
        tenant_id: str | None = None,
        timeout: float | None = None,
    ) -> httpx.Response:
        """Single POST — no 5xx retries. Used for endpoint-health probes."""
        headers = await self._headers(tenant_id)
        return await self._http.post(url, json=json, headers=headers, timeout=timeout)
