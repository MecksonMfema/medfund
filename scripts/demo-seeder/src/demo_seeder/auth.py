"""Password-grant Keycloak token cache for the platform superadmin."""

from __future__ import annotations

import time
from dataclasses import dataclass, field

import httpx


@dataclass
class KeycloakTokenClient:
    keycloak_url: str
    realm: str = "medfund-platform"
    username: str = "superadmin"
    password: str = "admin123"
    client_id: str = "medfund-web"

    _token: str | None = field(default=None, repr=False)
    _expires_at: float = field(default=0.0, repr=False)

    async def get_token(self, http: httpx.AsyncClient) -> str:
        if self._token and time.time() < self._expires_at - 30:
            return self._token
        resp = await http.post(
            f"{self.keycloak_url}/realms/{self.realm}/protocol/openid-connect/token",
            data={
                "grant_type": "password",
                "client_id": self.client_id,
                "username": self.username,
                "password": self.password,
            },
        )
        resp.raise_for_status()
        body = resp.json()
        self._token = body["access_token"]
        self._expires_at = time.time() + body["expires_in"]
        return self._token
