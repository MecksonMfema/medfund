"""Phase 6 — month-by-month timeline replay.

Scope (this pass, matching the user steer to stub AI):
    - Pre-setup: seed tenant bank accounts (USD + local currency each).
    - For each month tick, per tenant:
        1. Contribution preview + commit (one call per line per tenant).
        2. Transactions in — one per group + a subset of ungrouped active members.
        3. Claim submissions — Poisson-distributed per active member with a mix
           of AHFOZ tariff + ICD codes drawn from the curated packs.
        4. Adjudication — STUBBED. Claims land in `submitted`; no `/adjudicate`
           call. See plan Deviations. Rewire to real AI when ai-service ships.
        5. Provider payment run — one PROVIDER run per currency per tenant per
           month (not weekly; keeps row counts manageable).
        6. Notes — small monthly mix of WRITE_OFF, TAX_WITHHELD, GOODWILL.

Deferred (documented deviations, non-blocking for demo shape):
    - New enrolments during the timeline (~2% growth / month)
    - Scheme changes, group changes
    - Pre-authorization submissions
    - Terminations, deaths
    - Memo notes
    - CTC member payment runs (needs member opt-in seeding first)
"""

from __future__ import annotations

import asyncio
import json
import math
import random
from datetime import date, timedelta
from decimal import Decimal
from pathlib import Path

import structlog
from dateutil.relativedelta import relativedelta
from faker import Faker

from ..client import SeederClient
from ..config import ServiceEndpoints, TierProfile

log = structlog.get_logger()


DATA_ROOT = Path(__file__).resolve().parents[3] / "data"


# ── Entry point ──────────────────────────────────────────────────────────────


async def run_timeline(
    client: SeederClient,
    endpoints: ServiceEndpoints,
    tenants: dict[str, str],
    profile: TierProfile,
    rng: random.Random,
    faker: Faker,
) -> dict[str, dict[str, int]]:
    """Drive the month-tick loop. Returns per-tenant aggregate counts."""
    bank_accounts = await _ensure_bank_accounts(client, endpoints.finance, tenants)
    log.info("seeder.timeline.bank-accounts", accounts=bank_accounts)

    icd_pack = json.loads((DATA_ROOT / "icd10-curated.json").read_text())
    ahfoz_pack = json.loads((DATA_ROOT / "ahfoz-curated.json").read_text())

    schemes_by_tenant = {}
    active_members_by_tenant = {}
    providers = await _fetch_providers(client, endpoints.user)
    for line, tenant_id in tenants.items():
        schemes_by_tenant[line] = await _fetch_schemes(client, endpoints.contributions, tenant_id)
        active_members_by_tenant[line] = await _fetch_all_members(client, endpoints.user, tenant_id)

    timeline_start = _snap_to_first_of_month(
        date.today() - timedelta(days=profile.timeline_months * 30)
    )
    log.info(
        "seeder.timeline.start",
        timeline_start=timeline_start.isoformat(),
        months=profile.timeline_months,
        tenants=list(tenants.values()),
        health_members=len(active_members_by_tenant.get("HEALTH", [])),
        life_members=len(active_members_by_tenant.get("LIFE", [])),
    )

    summary: dict[str, dict[str, int]] = {
        line: {
            "contributions_committed": 0,
            "transactions": 0,
            "claims_submitted": 0,
            "payment_runs": 0,
            "notes": 0,
        }
        for line in tenants
    }

    for i in range(profile.timeline_months):
        month = _snap_to_first_of_month(timeline_start + relativedelta(months=i))
        log.info("seeder.timeline.tick", month=month.isoformat())
        for line, tenant_id in tenants.items():
            schemes = schemes_by_tenant[line]
            members = active_members_by_tenant[line]
            bank_for_tenant = bank_accounts.get(line, {})

            # 1. Contributions preview + commit
            committed = await _run_contributions(
                client, endpoints.contributions, tenant_id, line, month
            )
            summary[line]["contributions_committed"] += committed

            # 2. Transactions in
            tx_count = await _run_transactions(
                client, endpoints.contributions, tenant_id, line, month, members, rng
            )
            summary[line]["transactions"] += tx_count

            # 3. Claim submissions
            claims_count = await _run_claim_submissions(
                client,
                endpoints.claims,
                tenant_id,
                line,
                month,
                members,
                schemes,
                providers,
                icd_pack,
                ahfoz_pack,
                profile,
                rng,
            )
            summary[line]["claims_submitted"] += claims_count

            # 4. Adjudication — STUBBED. See Deviations.

            # 5. Provider payment run (one per currency per tenant per month).
            pr_count = await _run_provider_payment_runs(
                client, endpoints.finance, tenant_id, line, month, bank_for_tenant
            )
            summary[line]["payment_runs"] += pr_count

            # 6. Notes.
            notes_count = await _run_notes(
                client, endpoints.finance, tenant_id, line, rng, members
            )
            summary[line]["notes"] += notes_count

    return summary


# ── Bank account pre-setup ───────────────────────────────────────────────────


TENANT_CURRENCIES: dict[str, list[str]] = {
    "HEALTH": ["USD", "ZWL"],
    "LIFE":   ["USD", "ZAR"],
}


async def _ensure_bank_accounts(
    client: SeederClient, finance_url: str, tenants: dict[str, str]
) -> dict[str, dict[str, str]]:
    """Return `{insuranceLine: {currency: bank_account_id}}` — idempotent."""
    out: dict[str, dict[str, str]] = {}
    for line, tenant_id in tenants.items():
        currencies = TENANT_CURRENCIES.get(line, ["USD"])
        existing = await _list_bank_accounts(client, finance_url, tenant_id)
        by_ccy = {row["currencyCode"]: row["id"] for row in existing if row.get("currencyCode")}
        for ccy in currencies:
            if ccy in by_ccy:
                continue
            payload = {
                "bankName":       f"Demo Bank ({ccy})",
                "accountNumber":  f"{tenant_id[:8].upper()}-{ccy}-1",
                "branchCode":     "0001",
                "swiftCode":      "DEMOZWHA",
                "accountName":    f"MedFund Demo {line} — {ccy}",
                "currencyCode":   ccy,
                "label":          f"Primary {ccy} Payout",
                "notes":          "Seeded by demo-seeder.",
                "nominated":      True,
                "active":         True,
            }
            resp = await client.post(
                f"{finance_url}/api/v1/tenant-bank-accounts",
                json=payload,
                tenant_id=tenant_id,
            )
            if resp.status_code in (201, 200):
                by_ccy[ccy] = resp.json()["id"]
            else:
                log.warning(
                    "seeder.timeline.bank-acct-non-201",
                    line=line,
                    currency=ccy,
                    status=resp.status_code,
                    body=resp.text[:300],
                )
        out[line] = by_ccy
    return out


async def _list_bank_accounts(
    client: SeederClient, finance_url: str, tenant_id: str
) -> list[dict]:
    resp = await client.get(
        f"{finance_url}/api/v1/tenant-bank-accounts", tenant_id=tenant_id
    )
    if resp.status_code != 200:
        return []
    body = resp.json()
    return body if isinstance(body, list) else (body.get("content") or [])


# ── Data loaders ─────────────────────────────────────────────────────────────


async def _fetch_schemes(
    client: SeederClient, contributions_url: str, tenant_id: str
) -> list[dict]:
    resp = await client.get(
        f"{contributions_url}/api/v1/schemes", tenant_id=tenant_id
    )
    if resp.status_code != 200:
        return []
    return [s for s in (resp.json() or []) if s.get("status") == "active"]


async def _fetch_all_members(
    client: SeederClient, user_url: str, tenant_id: str
) -> list[dict]:
    """Cursor-paginate through all members. Filters to active status."""
    rows: list[dict] = []
    cursor: str | None = None
    while True:
        params: dict[str, str | int] = {"limit": 100}
        if cursor:
            params["cursor"] = cursor
        resp = await client.get(
            f"{user_url}/api/v1/members", params=params, tenant_id=tenant_id
        )
        if resp.status_code != 200:
            break
        body = resp.json() or {}
        content = body.get("content") or []
        rows.extend(m for m in content if m.get("status") == "active")
        cursor = body.get("nextCursor")
        if not body.get("hasMore") or not cursor:
            break
    return rows


async def _fetch_providers(client: SeederClient, user_url: str) -> list[dict]:
    resp = await client.get(
        f"{user_url}/api/v1/providers", params={"page": 1, "size": 500}
    )
    if resp.status_code != 200:
        return []
    body = resp.json() or {}
    return body.get("content") if isinstance(body, dict) else body


# ── Tick sub-phases ──────────────────────────────────────────────────────────


# Endpoint-health flags per run. Once a POST endpoint 500s, subsequent
# ticks skip it entirely — the seeder's retry loop wastes ~6s per attempt
# and we've established the failure is intrinsic, not transient.
_BROKEN_ENDPOINTS: set[str] = set()


async def _run_contributions(
    client: SeederClient,
    contributions_url: str,
    tenant_id: str,
    line: str,
    month: date,
) -> int:
    """POST /contributions/preview then /commit for the month. Returns rows committed."""
    if "contributions" in _BROKEN_ENDPOINTS:
        return 0
    period_end = _end_of_month(month)
    body = {
        "periodStart": month.isoformat(),
        "periodEnd": period_end.isoformat(),
        "insuranceLine": line,
    }
    try:
        preview = await client.post_no_retry(
            f"{contributions_url}/api/v1/contributions/preview",
            json=body,
            tenant_id=tenant_id,
        )
        commit = await client.post_no_retry(
            f"{contributions_url}/api/v1/contributions/commit",
            json=body,
            tenant_id=tenant_id,
        )
    except Exception as exc:
        log.warning(
            "seeder.timeline.contributions-raised",
            line=line,
            month=month.isoformat(),
            error=str(exc)[:200],
        )
        _BROKEN_ENDPOINTS.add("contributions")
        return 0
    if commit.status_code == 409:
        return 0  # already committed for this period
    if commit.status_code >= 500 or preview.status_code >= 500:
        log.warning(
            "seeder.timeline.contributions-broken-skip",
            line=line,
            preview_status=preview.status_code,
            commit_status=commit.status_code,
            commit_body=commit.text[:300],
            hint="Endpoint 500s — pre-existing service bug. Skipping for the rest of this run.",
        )
        _BROKEN_ENDPOINTS.add("contributions")
        return 0
    if commit.status_code not in (200, 201):
        log.warning(
            "seeder.timeline.contributions-commit-4xx",
            line=line,
            month=month.isoformat(),
            status=commit.status_code,
            body=commit.text[:300],
        )
        return 0
    body_json = commit.json() or {}
    return int(body_json.get("committedCount") or body_json.get("count") or 0)


async def _run_transactions(
    client: SeederClient,
    contributions_url: str,
    tenant_id: str,
    line: str,
    month: date,
    members: list[dict],
    rng: random.Random,
) -> int:
    """Post PAYMENT transactions for grouped (via groupId) + subset of ungrouped members."""
    posted = 0
    # 1. Grouped: one PAYMENT per unique group.
    group_ids = {m["groupId"] for m in members if m.get("groupId")}
    for gid in group_ids:
        payload = {
            "groupId": gid,
            "amount": str(rng.randint(500, 3000)),
            "currencyCode": "USD",
            "transactionType": "PAYMENT",
            "paymentMethod": rng.choice(["EFT", "MOBILE_MONEY", "CASH"]),
            "reference": f"REF-{gid[:8]}-{month.strftime('%Y%m')}",
        }
        try:
            resp = await client.post(
                f"{contributions_url}/api/v1/transactions",
                json=payload,
                tenant_id=tenant_id,
            )
            if resp.status_code in (200, 201):
                posted += 1
        except Exception:
            continue

    # 2. Ungrouped: sample ~60% of ungrouped members each month.
    ungrouped = [m for m in members if not m.get("groupId")]
    if not ungrouped:
        return posted
    sample_size = int(len(ungrouped) * 0.6)
    chosen = rng.sample(ungrouped, min(sample_size, len(ungrouped)))
    sem = asyncio.Semaphore(20)

    async def _one_tx(m: dict) -> bool:
        payload = {
            "memberId": m["id"],
            "amount": str(rng.randint(20, 250)),
            "currencyCode": "USD",
            "transactionType": "PAYMENT",
            "paymentMethod": rng.choice(["EFT", "MOBILE_MONEY", "CASH"]),
            "reference": f"REF-M-{m['id'][:8]}-{month.strftime('%Y%m')}",
        }
        async with sem:
            try:
                resp = await client.post(
                    f"{contributions_url}/api/v1/transactions",
                    json=payload,
                    tenant_id=tenant_id,
                )
                return resp.status_code in (200, 201)
            except Exception:
                return False

    results = await asyncio.gather(*(_one_tx(m) for m in chosen))
    posted += sum(1 for ok in results if ok)
    return posted


async def _run_claim_submissions(
    client: SeederClient,
    claims_url: str,
    tenant_id: str,
    line: str,
    month: date,
    members: list[dict],
    schemes: list[dict],
    providers: list[dict],
    icd_pack: list[dict],
    ahfoz_pack: list[dict],
    profile: TierProfile,
    rng: random.Random,
) -> int:
    """POST /api/v1/claims per active member per month, Poisson-distributed."""
    if not members or not providers:
        return 0
    if "claims" in _BROKEN_ENDPOINTS:
        return 0
    per_year = (
        profile.health_claims_per_member_year
        if line == "HEALTH"
        else profile.life_claims_per_member_year
    )
    per_month = per_year / 12
    scheme_by_id = {s["id"]: s for s in schemes}
    sem = asyncio.Semaphore(20)
    counter = [0]
    failures_5xx = [0]
    max_5xx_before_bail = 5

    async def _one_member(m: dict) -> None:
        if "claims" in _BROKEN_ENDPOINTS:
            return
        n_claims = _poisson(per_month, rng)
        for _ in range(n_claims):
            if "claims" in _BROKEN_ENDPOINTS:
                return
            service_date = _random_date_in_month(month, rng)
            tariff = rng.choice(ahfoz_pack)
            icd = rng.choice(icd_pack)
            scheme = scheme_by_id.get(m.get("schemeId"))
            currency = scheme.get("currencyCode") if scheme else "USD"
            amount = Decimal(str(tariff["baseAmount"]))
            variance = Decimal(str(rng.uniform(0.85, 1.35)))
            claimed = (amount * variance).quantize(Decimal("0.01"))
            provider = rng.choice(providers)
            payload = {
                "memberId": m["id"],
                "providerId": provider["id"] if line != "LIFE" else None,
                "schemeId": m["schemeId"],
                "claimType": "medical" if line == "HEALTH" else "life",
                "insuranceLine": line,
                "serviceDate": service_date.isoformat(),
                "claimedAmount": str(claimed),
                "currencyCode": currency or "USD",
                "diagnosisCodes": icd["code"],
                "procedureCodes": tariff["code"],
                "notes": f"Auto-generated claim ({icd['description']})",
                "lines": [
                    {
                        "tariffCode": tariff["code"],
                        "description": tariff["description"],
                        "quantity": 1,
                        "unitPrice": str(claimed),
                        "claimedAmount": str(claimed),
                    }
                ],
            }
            async with sem:
                try:
                    resp = await client.post_no_retry(
                        f"{claims_url}/api/v1/claims",
                        json=payload,
                        tenant_id=tenant_id,
                    )
                except Exception:
                    continue
            if resp.status_code in (200, 201):
                counter[0] += 1
                continue
            if resp.status_code >= 500:
                failures_5xx[0] += 1
                if failures_5xx[0] == 1:
                    log.warning(
                        "seeder.timeline.claims-first-5xx",
                        status=resp.status_code,
                        body=resp.text[:300],
                    )
                if failures_5xx[0] == max_5xx_before_bail:
                    _BROKEN_ENDPOINTS.add("claims")
                    log.warning(
                        "seeder.timeline.claims-broken-skip",
                        hint="POST /api/v1/claims 500s consistently — skipping rest of run.",
                    )

    await asyncio.gather(*(_one_member(m) for m in members))
    return counter[0]


async def _run_provider_payment_runs(
    client: SeederClient,
    finance_url: str,
    tenant_id: str,
    line: str,
    month: date,
    bank_by_currency: dict[str, str],
) -> int:
    """One PROVIDER payment run per currency per month. Best-effort — will
    no-op if there are no outstanding provider balances for the currency."""
    if "payment_runs" in _BROKEN_ENDPOINTS:
        return 0
    created = 0
    for currency, bank_id in bank_by_currency.items():
        payload = {
            "currencyCode": currency,
            "description": f"Monthly provider payout {month.strftime('%Y-%m')} ({line})",
            "payeeType": "PROVIDER",
            "sourceBankAccountId": bank_id,
            "periodStart": month.isoformat(),
            "periodEnd": _end_of_month(month).isoformat(),
        }
        try:
            create = await client.post_no_retry(
                f"{finance_url}/api/v1/payment-runs",
                json=payload,
                tenant_id=tenant_id,
            )
        except Exception as exc:
            log.warning(
                "seeder.timeline.pr-create-raised",
                line=line,
                currency=currency,
                error=str(exc)[:200],
            )
            _BROKEN_ENDPOINTS.add("payment_runs")
            continue
        if create.status_code >= 500:
            log.warning(
                "seeder.timeline.pr-broken-skip",
                line=line,
                currency=currency,
                status=create.status_code,
                body=create.text[:200],
                hint="Endpoint 500s — skipping payment runs for rest of run.",
            )
            _BROKEN_ENDPOINTS.add("payment_runs")
            continue
        if create.status_code not in (200, 201):
            # An empty-run 4xx from the service is normal when there are no
            # unpaid claims for the currency — quiet log at debug altitude.
            log.debug(
                "seeder.timeline.pr-create-non-201",
                line=line,
                currency=currency,
                status=create.status_code,
                body=create.text[:200],
            )
            continue
        pr = create.json()
        pr_id = pr.get("id")
        created += 1
        if not pr_id:
            continue
        # Approve + execute best-effort. Failures don't affect the created count.
        try:
            await client.post(
                f"{finance_url}/api/v1/payment-runs/{pr_id}/approve",
                json={},
                tenant_id=tenant_id,
            )
            await client.post(
                f"{finance_url}/api/v1/payment-runs/{pr_id}/execute",
                json={},
                tenant_id=tenant_id,
            )
        except Exception:
            pass
    return created


NOTE_TYPES = ["WRITE_OFF", "TAX_WITHHELD", "GOODWILL", "PROVIDER_OVERPAYMENT_RECOVERY"]


async def _run_notes(
    client: SeederClient,
    finance_url: str,
    tenant_id: str,
    line: str,
    rng: random.Random,
    members: list[dict],
) -> int:
    """Post a small monthly mix of notes. HEALTH: ~10/mo, LIFE: ~4/mo."""
    if not members:
        return 0
    target = 10 if line == "HEALTH" else 4
    posted = 0
    for _ in range(target):
        member = rng.choice(members)
        note_type = rng.choice(NOTE_TYPES)
        direction = "CREDIT" if note_type in ("WRITE_OFF", "GOODWILL") else "DEBIT"
        payload = {
            "direction": direction,
            "noteType": note_type,
            "memberId": member["id"],
            "amount": str(rng.randint(20, 500)),
            "currencyCode": "USD",
            "reason": f"Auto-seeded {note_type.lower().replace('_', ' ')} for demo.",
        }
        try:
            resp = await client.post(
                f"{finance_url}/api/v1/notes", json=payload, tenant_id=tenant_id
            )
            if resp.status_code in (200, 201):
                posted += 1
        except Exception:
            continue
    return posted


# ── Date + RNG helpers ──────────────────────────────────────────────────────


def _snap_to_first_of_month(d: date) -> date:
    return d.replace(day=1)


def _end_of_month(d: date) -> date:
    return (d.replace(day=1) + relativedelta(months=1) - timedelta(days=1))


def _random_date_in_month(month_start: date, rng: random.Random) -> date:
    end = _end_of_month(month_start)
    delta = (end - month_start).days
    return month_start + timedelta(days=rng.randint(0, delta))


def _poisson(lmbda: float, rng: random.Random) -> int:
    L = math.exp(-lmbda)
    k = 0
    p = 1.0
    while True:
        k += 1
        p *= rng.random()
        if p <= L:
            return k - 1
