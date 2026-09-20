"""Phase 4 — per-tenant Keycloak realm: operational roles + 25 staff users.

Tenant realms are auto-created by `TenantService.create()` (KeycloakRealmService)
with only two seeded roles: `tenant_admin` and `group_liaison`. Operational
roles (`claims_clerk`, `finance_officer`, etc.) are tenant-defined via the
Roles & Permissions UI. This phase materializes those roles on each tenant
realm and creates 25 staff users spread across roles — enough to log into
every portal surface end-to-end.

Password: `test123` for every seeded user.

Idempotency: probes existence by username / role name before creating.
"""

from __future__ import annotations

import asyncio

import httpx
import structlog

from ..auth import KeycloakTokenClient

log = structlog.get_logger()


# Operational roles that must exist on every tenant realm before we can
# assign them to users. `tenant_admin` and `group_liaison` are seeded by
# TenantService.create(); the rest are tenant-defined.
OPERATIONAL_ROLES: list[str] = [
    "tenant_admin",
    "claims_clerk",
    "claims_assessor",
    "finance_officer",
    "contributions_officer",
    "siu_officer",
    "siu_supervisor",
    "provider",
    "member",
    "group_liaison",
]


# Per-tenant staff manifest: role → total user count for that role.
# 25 users total = 1 (admin) + 5 (claims_clerk) + 2 (claims_assessor) +
# 5 (finance_officer) + 4 (contributions_officer) + 2 (siu_officer) +
# 2 (siu_supervisor) + 2 (provider) + 2 (group_liaison).
STAFF_PLAN: dict[str, int] = {
    "tenant_admin": 1,
    "claims_clerk": 5,
    "claims_assessor": 2,
    "finance_officer": 5,
    "contributions_officer": 4,
    "siu_officer": 2,
    "siu_supervisor": 2,
    "provider": 2,
    "group_liaison": 2,
}

DEFAULT_PASSWORD = "test123"


async def seed_realm_users(
    http: httpx.AsyncClient,
    keycloak_url: str,
    tokens: KeycloakTokenClient,
    tenant_slug_to_id: dict[str, str],
) -> dict[str, int]:
    """Seed roles + staff users on each per-tenant realm. Returns `{slug: users_created}`.

    `tokens` is the platform-superadmin token cache; the superadmin has
    admin-realm-management roles across all realms so we can hit
    `/admin/realms/{tenant-realm}/users` with the same bearer.
    """
    counts: dict[str, int] = {}
    for slug in tenant_slug_to_id:
        realm = f"medfund-{slug}"

        # Ensure every operational role exists on this realm.
        roles_created = 0
        for role in OPERATIONAL_ROLES:
            if await _ensure_role(http, keycloak_url, tokens, realm, role):
                roles_created += 1
        log.info("seeder.kc.roles-ready", realm=realm, extra_created=roles_created)

        # Create staff users per plan.
        created = 0
        for role, count_for_role in STAFF_PLAN.items():
            for idx in range(1, count_for_role + 1):
                username = _staff_username(role, slug, idx)
                if await _ensure_user(
                    http, keycloak_url, tokens, realm, username, role, slug, idx
                ):
                    created += 1
        counts[slug] = created
        log.info("seeder.kc.staff-done", realm=realm, created=created)
    return counts


# ── Helpers ──────────────────────────────────────────────────────────────────


def _staff_username(role: str, slug: str, idx: int) -> str:
    return f"{role.replace('_', '-')}-{slug}-{idx}"


async def _ensure_role(
    http: httpx.AsyncClient,
    keycloak_url: str,
    tokens: KeycloakTokenClient,
    realm: str,
    role_name: str,
) -> bool:
    """Create the role if missing. Returns True when it was newly created."""
    token = await tokens.get_token(http)
    headers = {"Authorization": f"Bearer {token}"}

    check = await http.get(
        f"{keycloak_url}/admin/realms/{realm}/roles/{role_name}", headers=headers
    )
    if check.status_code == 200:
        return False
    if check.status_code != 404:
        log.warning(
            "seeder.kc.role-check-non-200",
            realm=realm,
            role=role_name,
            status=check.status_code,
        )
        return False

    resp = await http.post(
        f"{keycloak_url}/admin/realms/{realm}/roles",
        json={"name": role_name, "description": f"MedFund role: {role_name}"},
        headers=headers,
    )
    if resp.status_code in (201, 409):
        return resp.status_code == 201
    log.warning(
        "seeder.kc.role-create-non-201",
        realm=realm,
        role=role_name,
        status=resp.status_code,
        body=resp.text[:200],
    )
    return False


async def _ensure_user(
    http: httpx.AsyncClient,
    keycloak_url: str,
    tokens: KeycloakTokenClient,
    realm: str,
    username: str,
    role: str,
    slug: str,
    idx: int,
) -> bool:
    """Create the user + assign the role. Returns True when newly created."""
    token = await tokens.get_token(http)
    headers = {"Authorization": f"Bearer {token}"}

    # Existence probe by username.
    probe = await http.get(
        f"{keycloak_url}/admin/realms/{realm}/users",
        params={"username": username, "exact": "true"},
        headers=headers,
    )
    if probe.status_code == 200 and probe.json():
        return False
    if probe.status_code not in (200, 404):
        log.warning(
            "seeder.kc.user-probe-non-200",
            realm=realm,
            username=username,
            status=probe.status_code,
        )
        return False

    first_last = _first_last_for(role, idx)
    payload = {
        "username": username,
        "email": f"{username}@medfund.example",
        "firstName": first_last[0],
        "lastName": first_last[1],
        "enabled": True,
        "emailVerified": True,
        "credentials": [
            {"type": "password", "value": DEFAULT_PASSWORD, "temporary": False}
        ],
    }
    create = await http.post(
        f"{keycloak_url}/admin/realms/{realm}/users", json=payload, headers=headers
    )
    if create.status_code not in (201, 409):
        log.warning(
            "seeder.kc.user-create-non-201",
            realm=realm,
            username=username,
            status=create.status_code,
            body=create.text[:200],
        )
        return False

    # Fetch the user id (also handles the 409 race case).
    user_id = await _fetch_user_id(http, keycloak_url, tokens, realm, username)
    if user_id is None:
        return create.status_code == 201

    role_repr = await _fetch_role(http, keycloak_url, tokens, realm, role)
    if role_repr is None:
        log.warning("seeder.kc.role-not-found", realm=realm, role=role, username=username)
        return create.status_code == 201

    assign = await http.post(
        f"{keycloak_url}/admin/realms/{realm}/users/{user_id}/role-mappings/realm",
        json=[role_repr],
        headers=headers,
    )
    if assign.status_code not in (204, 200):
        log.warning(
            "seeder.kc.role-assign-non-204",
            realm=realm,
            username=username,
            role=role,
            status=assign.status_code,
            body=assign.text[:200],
        )

    return create.status_code == 201


async def _fetch_user_id(
    http: httpx.AsyncClient,
    keycloak_url: str,
    tokens: KeycloakTokenClient,
    realm: str,
    username: str,
) -> str | None:
    token = await tokens.get_token(http)
    resp = await http.get(
        f"{keycloak_url}/admin/realms/{realm}/users",
        params={"username": username, "exact": "true"},
        headers={"Authorization": f"Bearer {token}"},
    )
    if resp.status_code != 200:
        return None
    rows = resp.json() or []
    return rows[0]["id"] if rows else None


async def _fetch_role(
    http: httpx.AsyncClient,
    keycloak_url: str,
    tokens: KeycloakTokenClient,
    realm: str,
    role_name: str,
) -> dict | None:
    token = await tokens.get_token(http)
    resp = await http.get(
        f"{keycloak_url}/admin/realms/{realm}/roles/{role_name}",
        headers={"Authorization": f"Bearer {token}"},
    )
    if resp.status_code != 200:
        return None
    body = resp.json()
    return {"id": body["id"], "name": body["name"]}


_FIRST_NAMES = [
    "Alex", "Sam", "Jordan", "Taylor", "Morgan", "Casey", "Riley", "Quinn",
    "Avery", "Skylar", "Devon", "Elliot",
]

_LAST_NAMES = [
    "Chen", "Patel", "Moyo", "Ndlovu", "Sithole", "Tembo", "Zulu", "Khumalo",
    "Botha", "Naidoo", "Silva", "Osei",
]


def _first_last_for(role: str, idx: int) -> tuple[str, str]:
    """Stable, readable names per (role, idx). Just for demo readability."""
    role_seed = sum(ord(c) for c in role) + idx
    return (_FIRST_NAMES[role_seed % len(_FIRST_NAMES)],
            _LAST_NAMES[(role_seed // 3) % len(_LAST_NAMES)])
