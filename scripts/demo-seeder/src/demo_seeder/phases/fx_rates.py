"""Phase 2 — historical FX rates (USD-ZWL, USD-ZAR) for the timeline window.

The plan targets `months * 30` daily rows per pair. To stay idempotent on re-runs
we `GET /api/v1/exchange-rates/history` first for each pair and POST only the
missing dates: the catch-all exception handler in tenancy-service maps
DuplicateKeyException to 500 (not 409), so we can't rely on POST-and-swallow.
"""

from __future__ import annotations

import asyncio
import random
from datetime import date, timedelta
from decimal import Decimal

import structlog

from ..client import SeederClient

log = structlog.get_logger()


# Depreciation-curve seeds. (base, quote, start_rate, drift_per_day, daily_noise_pct)
CURRENCY_PAIRS: list[tuple[str, str, Decimal, Decimal, Decimal]] = [
    ("USD", "ZWL", Decimal("500.00"), Decimal("2.9"), Decimal("0.5")),
    ("USD", "ZAR", Decimal("15.00"), Decimal("0.004"), Decimal("0.3")),
]

_CONCURRENCY = 20


async def seed_fx_rates(
    client: SeederClient,
    tenancy_url: str,
    months: int,
    rng: random.Random,
    header_tenant_id: str,
) -> int:
    """POST the missing daily rates. Returns the count of new rows inserted.

    `header_tenant_id` is used only to satisfy the shared TenantWebFilter (which
    requires an X-Tenant-ID header on non-allowlisted paths). The stored rate
    row is platform-wide because we set body.tenantId=null.
    """
    today = date.today()
    start = today - timedelta(days=months * 30)

    posted_total = 0
    for base, quote, start_rate, drift, noise_pct in CURRENCY_PAIRS:
        existing = await _fetch_existing_dates(
            client, tenancy_url, base, quote, start, today, header_tenant_id
        )
        payloads: list[dict] = []
        rate = start_rate
        d = start
        while d <= today:
            if d not in existing:
                noise = Decimal(str(rng.uniform(-1.0, 1.0))) * (rate * noise_pct / 100)
                rounded = (rate + noise).quantize(Decimal("0.000001"))
                if rounded <= 0:
                    rounded = Decimal("0.000001")
                payloads.append(
                    {
                        "baseCurrency": base,
                        "quoteCurrency": quote,
                        "rate": str(rounded),
                        "rateDate": d.isoformat(),
                        "source": "seeder",
                    }
                )
            rate += drift
            d += timedelta(days=1)

        log.info(
            "seeder.fx.plan",
            base=base,
            quote=quote,
            existing=len(existing),
            to_post=len(payloads),
        )

        sem = asyncio.Semaphore(_CONCURRENCY)

        async def _post_one(payload: dict) -> None:
            async with sem:
                resp = await client.post(
                    f"{tenancy_url}/api/v1/exchange-rates",
                    json=payload,
                    tenant_id=header_tenant_id,
                )
                if resp.status_code == 201:
                    return
                # Duplicate races through as 500 (no dedicated handler); log + continue.
                log.warning(
                    "seeder.fx.non-201",
                    status=resp.status_code,
                    payload=payload,
                    body=resp.text[:200],
                )

        await asyncio.gather(*(_post_one(p) for p in payloads))
        posted_total += len(payloads)

    return posted_total


async def _fetch_existing_dates(
    client: SeederClient,
    tenancy_url: str,
    base: str,
    quote: str,
    from_: date,
    to: date,
    header_tenant_id: str,
) -> set[date]:
    """Return the set of rate_dates already present for this pair in [from_, to]."""
    resp = await client.get(
        f"{tenancy_url}/api/v1/exchange-rates/history",
        params={"base": base, "quote": quote, "from": from_.isoformat(), "to": to.isoformat()},
        tenant_id=header_tenant_id,
    )
    if resp.status_code != 200:
        # Empty history is fine; anything else is worth surfacing.
        log.warning(
            "seeder.fx.history-non-200",
            base=base,
            quote=quote,
            status=resp.status_code,
            body=resp.text[:200],
        )
        return set()
    rows = resp.json() or []
    dates: set[date] = set()
    for row in rows:
        raw = row.get("rateDate")
        if raw:
            dates.add(date.fromisoformat(raw))
    return dates
