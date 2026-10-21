"""Phase 3 — per-tenant rule bundles.

Reads a JSON array of rule payloads from `data/rules/<line>.json` (deviation
from the plan's per-file layout — 27 tiny files → 2 array files, easier to
diff, single loader). Each entry maps to `CreateRuleRequest` on rules-engine's
`POST /api/v1/rules`. Skips ruleKeys that already exist for the tenant.
"""

from __future__ import annotations

import json
from pathlib import Path

import structlog

from ..client import SeederClient

log = structlog.get_logger()


DATA_ROOT = Path(__file__).resolve().parents[3] / "data" / "rules"

INSURANCE_LINE_TO_PACK: dict[str, str] = {
    "HEALTH": "health.json",
    "LIFE": "life.json",
}


async def seed_rules(
    client: SeederClient,
    rules_url: str,
    tenants: dict[str, str],
) -> dict[str, int]:
    """POST each rule per tenant. Returns `{insuranceLine: count_posted}`.

    Bail-out probe: if the first tenant's first POST returns 500 (the known
    rules-engine JSONB-converter bug documented in the plan's Deviations),
    skip the whole phase to avoid ~3 minutes of retry backoff × 28 rules.
    """
    posted: dict[str, int] = {}
    probe_result = await _probe_rules_endpoint(client, rules_url, tenants)
    if probe_result == "broken":
        log.warning(
            "seeder.rules.endpoint-broken-skip",
            hint=(
                "POST /api/v1/rules returns 500 (rules-engine BadSqlGrammarException "
                "on jsonb column). Pre-existing bug, see plan Deviations. "
                "Rule packs land on disk under data/rules/; POST when fixed."
            ),
        )
        return {line: 0 for line in tenants}

    for line, tenant_id in tenants.items():
        pack_name = INSURANCE_LINE_TO_PACK.get(line)
        if pack_name is None:
            log.warning("seeder.rules.no-pack-for-line", line=line)
            continue
        pack_path = DATA_ROOT / pack_name
        rules = json.loads(pack_path.read_text())

        existing_keys = await _fetch_existing_rule_keys(client, rules_url, tenant_id)
        to_post = [r for r in rules if r["ruleKey"] not in existing_keys]
        log.info(
            "seeder.rules.plan",
            line=line,
            tenant_id=tenant_id,
            total_in_pack=len(rules),
            existing=len(existing_keys),
            to_post=len(to_post),
        )

        count = 0
        failed = 0
        for rule in to_post:
            # A pre-existing rules-engine bug (see plan Deviations) makes
            # POST /api/v1/rules return 500 for structured `definition`
            # payloads — the entity holds `String` but the column is `jsonb`
            # with no R2DBC converter. Swallow per-rule failure so schemes
            # + downstream phases still ship.
            try:
                resp = await client.post(
                    f"{rules_url}/api/v1/rules", json=rule, tenant_id=tenant_id
                )
            except Exception as exc:
                failed += 1
                log.warning(
                    "seeder.rules.post-raised",
                    line=line,
                    rule_key=rule["ruleKey"],
                    error=str(exc)[:200],
                )
                continue
            if resp.status_code == 201:
                count += 1
                continue
            if resp.status_code == 409:
                # Race — another run created it. Treat as no-op.
                continue
            failed += 1
            log.warning(
                "seeder.rules.non-201",
                line=line,
                rule_key=rule["ruleKey"],
                status=resp.status_code,
                body=resp.text[:300],
            )
        posted[line] = count
        log.info("seeder.rules.done", line=line, posted=count, failed=failed)
    return posted


async def _fetch_existing_rule_keys(
    client: SeederClient, rules_url: str, tenant_id: str
) -> set[str]:
    resp = await client.get(f"{rules_url}/api/v1/rules", tenant_id=tenant_id)
    if resp.status_code != 200:
        log.warning(
            "seeder.rules.list-non-200",
            tenant_id=tenant_id,
            status=resp.status_code,
            body=resp.text[:200],
        )
        return set()
    return {row["ruleKey"] for row in (resp.json() or []) if row.get("ruleKey")}


async def _probe_rules_endpoint(
    client: SeederClient, rules_url: str, tenants: dict[str, str]
) -> str:
    """Return "ok" if POST works, "broken" if the pre-existing JSONB bug fires,
    or "unknown" if the endpoint's behavior is different from either case.

    Fires exactly one POST against the first tenant; loader either proceeds
    with the real batch or bails out fast. Uses one_shot to skip 5xx retries.
    """
    first_tenant = next(iter(tenants.values()), None)
    if first_tenant is None:
        return "unknown"

    # Minimal well-formed rule for the probe.
    probe = {
        "ruleKey": "seeder.probe.jsonb",
        "name": "Seeder JSONB probe",
        "description": "Delete me — seeder wire-up probe.",
        "category": "ELIGIBILITY",
        "priority": 1,
        "enabled": False,
        "definition": {
            "id": "seeder.probe.jsonb",
            "name": "probe",
            "category": "ELIGIBILITY",
            "priority": 1,
            "enabled": False,
            "conditions": {"operator": "AND", "items": []},
            "action": {"type": "WARN", "message": "probe"},
        },
    }
    try:
        resp = await client.post_no_retry(
            f"{rules_url}/api/v1/rules", json=probe, tenant_id=first_tenant, timeout=10
        )
    except Exception as exc:
        log.warning("seeder.rules.probe-raised", error=str(exc)[:200])
        return "unknown"

    if resp.status_code in (201, 409):
        return "ok"
    if resp.status_code == 500:
        return "broken"
    log.warning("seeder.rules.probe-unexpected", status=resp.status_code, body=resp.text[:200])
    return "unknown"
