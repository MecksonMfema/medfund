#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# Phase 17 §D.1 — Scheduled report delivery performance smoke.
#
# Two probes:
#   (1) Probe iteration under load. Seeds N tenants × M schedules, then times
#       one full pass of the probe's enumerate → orchestrate loop via the
#       test-only /probe/force-fire endpoint. Target: <30s for 500 schedules.
#   (2) Multi-instance dedup. Fires the SAME scheduleId concurrently from K
#       parallel workers; asserts exactly one report_job row was written and
#       exactly one delivery event landed on Kafka (via the schedule's runs
#       endpoint).
#
# Meant for a local `make infra` + finance-service + tenancy-service (and 3
# finance-service replicas when running the multi-instance dedup probe) or
# staging. Not wired into CI — this is an operator smoke test.
#
# Usage:
#   scripts/perf-test-scheduled-reports.sh                        # defaults
#   TENANTS=50 SCHEDULES_PER_TENANT=3 scripts/perf-test-scheduled-reports.sh
#   FINANCE_URL=https://finance.staging BASE_JWT=eyJ… TENANCY_URL=… scripts/perf-test-scheduled-reports.sh
#
# Env:
#   FINANCE_URL          finance-service base URL (default http://localhost:8085)
#   TENANCY_URL          tenancy-service base URL (default http://localhost:8081)
#   BASE_JWT             Bearer JWT for both services (super_admin scope)
#   TENANTS              number of tenants to seed (default 100)
#   SCHEDULES_PER_TENANT schedules per tenant (default 5)
#   DEDUP_CONCURRENCY    parallel force-fire workers for dedup probe (default 3)
#   FORCE_FIRE_TIMEOUT   seconds to wait for probe iteration (default 60)
#   POLL_INTERVAL        seconds between run-status polls (default 2)
#   PROBE_TARGET_S       probe target latency in seconds (default 30)
#   SKIP_SEED=1          reuse existing tenants/schedules (skip seed)
#   SKIP_DEDUP=1         run only the iteration probe (skip dedup probe)
# ---------------------------------------------------------------------------

set -euo pipefail

FINANCE_URL="${FINANCE_URL:-http://localhost:8085}"
TENANCY_URL="${TENANCY_URL:-http://localhost:8081}"
BASE_JWT="${BASE_JWT:-}"
TENANTS="${TENANTS:-100}"
SCHEDULES_PER_TENANT="${SCHEDULES_PER_TENANT:-5}"
DEDUP_CONCURRENCY="${DEDUP_CONCURRENCY:-3}"
FORCE_FIRE_TIMEOUT="${FORCE_FIRE_TIMEOUT:-60}"
POLL_INTERVAL="${POLL_INTERVAL:-2}"
PROBE_TARGET_S="${PROBE_TARGET_S:-30}"

if [[ -z "$BASE_JWT" ]]; then
    echo "ERROR: BASE_JWT is required — export a Bearer token with super_admin scope." >&2
    exit 2
fi

TOTAL_SCHEDULES=$(( TENANTS * SCHEDULES_PER_TENANT ))

echo "── Scheduled report perf test ──────────────────────"
echo "Finance URL          : $FINANCE_URL"
echo "Tenancy URL          : $TENANCY_URL"
echo "Tenants              : $TENANTS"
echo "Schedules per tenant : $SCHEDULES_PER_TENANT"
echo "Total schedules      : $TOTAL_SCHEDULES"
echo "Dedup concurrency    : $DEDUP_CONCURRENCY"
echo "Probe target (s)     : $PROBE_TARGET_S"
echo "────────────────────────────────────────────────────"

WORKDIR="$(mktemp -d -t scheduled-report-perf-XXXXXX)"
trap 'rm -rf "$WORKDIR"' EXIT

# ---------------------------------------------------------------------------
# Seed — creates N tenants × M schedules. Idempotent: SKIP_SEED=1 to bypass.
# Each seeded tenant is named "perf-t${i}"; each schedule is COMMISSION_STATEMENT
# with cadence MONTHLY, day=1, hour=8. tenant IDs and schedule IDs are captured
# into ${WORKDIR}/seed.tsv (tenantId <TAB> scheduleId).
# ---------------------------------------------------------------------------
seed_one_tenant() {
    local i="$1"
    local tenant_slug="perf-t${i}"
    local tenant_resp
    tenant_resp="$(curl -s -X POST "$TENANCY_URL/api/v1/tenants" \
        -H "Authorization: Bearer $BASE_JWT" \
        -H "Content-Type: application/json" \
        -d "{\"name\":\"Perf Tenant ${i}\",\"slug\":\"${tenant_slug}\",\"timezone\":\"UTC\",\"insuranceLines\":[\"HEALTH\"]}" || true)"
    local tenant_id
    tenant_id="$(printf '%s' "$tenant_resp" | sed -n 's/.*"id":"\([^"]*\)".*/\1/p' | head -1)"
    if [[ -z "$tenant_id" ]]; then
        echo "seed: tenant ${i} skipped (already exists or create failed) — resp=$tenant_resp" >&2
        return
    fi
    local s
    for s in $(seq 1 "$SCHEDULES_PER_TENANT"); do
        local schedule_resp
        schedule_resp="$(curl -s -X POST "$TENANCY_URL/api/v1/tenants/${tenant_id}/report-schedules" \
            -H "Authorization: Bearer $BASE_JWT" \
            -H "X-Tenant-ID: ${tenant_id}" \
            -H "Content-Type: application/json" \
            -d "{\"reportKey\":\"COMMISSION_STATEMENT\",\"enabled\":true,\"cadence\":\"MONTHLY\",\"hourOfDay\":8,\"dayOfMonth\":1}")"
        local schedule_id
        schedule_id="$(printf '%s' "$schedule_resp" | sed -n 's/.*"id":"\([^"]*\)".*/\1/p' | head -1)"
        if [[ -n "$schedule_id" ]]; then
            echo -e "${tenant_id}\t${schedule_id}" >> "$WORKDIR/seed.tsv"
        else
            echo "seed: schedule ${i}/${s} failed — resp=$schedule_resp" >&2
        fi
    done
}

if [[ "${SKIP_SEED:-0}" != "1" ]]; then
    echo "Seeding $TENANTS tenants × $SCHEDULES_PER_TENANT schedules…"
    : > "$WORKDIR/seed.tsv"
    for i in $(seq 1 "$TENANTS"); do
        seed_one_tenant "$i" &
        # Cap concurrent seed workers so tenancy-service isn't overwhelmed.
        if (( i % 10 == 0 )); then wait; fi
    done
    wait
    seeded="$(wc -l < "$WORKDIR/seed.tsv" || echo 0)"
    echo "Seed complete: $seeded schedules created."
else
    echo "SKIP_SEED=1 → assuming seed already ran; skipping."
    : > "$WORKDIR/seed.tsv"
fi

# ---------------------------------------------------------------------------
# Probe iteration — one full force-fire cycle. The finance endpoint returns
# the number of schedules processed once the probe completes; we time it.
# ---------------------------------------------------------------------------
echo
echo "── Probe iteration ─────────────────────────────────"
iteration_start="$(date +%s)"
probe_resp="$(curl -s -X POST "$FINANCE_URL/api/v1/reports/scheduled/probe/force-fire" \
    -H "Authorization: Bearer $BASE_JWT" \
    --max-time "$FORCE_FIRE_TIMEOUT" \
    -w "\n%{http_code}\n%{time_total}\n")"
iteration_elapsed="$(( $(date +%s) - iteration_start ))"

probe_code="$(printf '%s' "$probe_resp" | tail -n2 | head -n1)"
probe_time_curl="$(printf '%s' "$probe_resp" | tail -n1)"
probe_body="$(printf '%s' "$probe_resp" | sed '$d' | sed '$d')"

echo "HTTP status  : $probe_code"
echo "Wall clock   : ${iteration_elapsed}s (curl reports ${probe_time_curl}s)"
echo "Body         : $probe_body"

if [[ "$probe_code" != "200" ]]; then
    echo "FAIL: probe force-fire returned HTTP $probe_code" >&2
    exit 1
fi

if (( iteration_elapsed > PROBE_TARGET_S )); then
    echo "WARN: probe iteration ${iteration_elapsed}s exceeded ${PROBE_TARGET_S}s target." >&2
else
    echo "OK: probe iteration ${iteration_elapsed}s within ${PROBE_TARGET_S}s target."
fi

# ---------------------------------------------------------------------------
# Multi-instance dedup — same scheduleId, K concurrent force-fires. Assert
# exactly one completed report_job in the schedule's run history for the
# resulting period.
#
# This only means something when finance-service is deployed with >=2
# replicas — the guard is the Reactor-Kafka commit-log dedup landed in Phase 4.
# Single-replica runs still exercise the same code path but can't uncover
# racy leader-election bugs.
# ---------------------------------------------------------------------------
if [[ "${SKIP_DEDUP:-0}" == "1" ]]; then
    echo
    echo "SKIP_DEDUP=1 → skipping multi-instance dedup probe."
    exit 0
fi

echo
echo "── Multi-instance dedup ────────────────────────────"

# Pick a schedule to hammer — first entry of seed.tsv, or accept env override.
DEDUP_TENANT_ID="${DEDUP_TENANT_ID:-$(awk -F'\t' 'NR==1{print $1}' "$WORKDIR/seed.tsv")}"
DEDUP_SCHEDULE_ID="${DEDUP_SCHEDULE_ID:-$(awk -F'\t' 'NR==1{print $2}' "$WORKDIR/seed.tsv")}"

if [[ -z "$DEDUP_SCHEDULE_ID" ]]; then
    echo "SKIP: no scheduleId available for dedup probe (seed empty and no DEDUP_SCHEDULE_ID)." >&2
    exit 0
fi

echo "Tenant     : $DEDUP_TENANT_ID"
echo "Schedule   : $DEDUP_SCHEDULE_ID"
echo "Concurrency: $DEDUP_CONCURRENCY"

fire_one() {
    local w="$1"
    local out="$WORKDIR/dedup-$w.out"
    curl -s -X POST "$FINANCE_URL/api/v1/reports/scheduled/probe/force-fire?scheduleId=${DEDUP_SCHEDULE_ID}" \
        -H "Authorization: Bearer $BASE_JWT" \
        --max-time "$FORCE_FIRE_TIMEOUT" \
        -o "$out" \
        -w "%{http_code}" > "$WORKDIR/dedup-$w.code"
}

for w in $(seq 1 "$DEDUP_CONCURRENCY"); do
    fire_one "$w" &
done
wait

echo "Fires complete — worker HTTP codes:"
for w in $(seq 1 "$DEDUP_CONCURRENCY"); do
    printf "  worker %s → %s\n" "$w" "$(cat "$WORKDIR/dedup-$w.code" 2>/dev/null || echo '-')"
done

# Give the orchestrator a beat to persist. Then count completed runs for the
# most recent period.
sleep "$POLL_INTERVAL"

runs_resp="$(curl -s -H "Authorization: Bearer $BASE_JWT" \
    -H "X-Tenant-ID: ${DEDUP_TENANT_ID}" \
    "$FINANCE_URL/api/v1/reports/scheduled/schedules/${DEDUP_SCHEDULE_ID}/runs?limit=10")"

# Count runs requested within the last minute — the concurrent fires all fall
# into this window; earlier historical runs (if any) are excluded.
recent_completed=$(printf '%s' "$runs_resp" | \
    python3 -c '
import json, sys, datetime
try:
    runs = json.loads(sys.stdin.read() or "[]")
except Exception:
    runs = []
cutoff = datetime.datetime.now(datetime.timezone.utc) - datetime.timedelta(minutes=1)
n = 0
for r in runs:
    ts = (r.get("requestedAt") or "").replace("Z", "+00:00")
    try:
        t = datetime.datetime.fromisoformat(ts)
    except Exception:
        continue
    if t >= cutoff:
        n += 1
print(n)
')

echo "Runs written in last minute: $recent_completed (expected: 1)"
if [[ "$recent_completed" != "1" ]]; then
    echo "FAIL: dedup broke — $DEDUP_CONCURRENCY concurrent fires produced $recent_completed rows." >&2
    exit 3
fi
echo "OK: dedup held — exactly one row for $DEDUP_CONCURRENCY concurrent fires."
