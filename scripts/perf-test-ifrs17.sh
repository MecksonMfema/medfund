#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# Phase 15 §25 — IFRS 17 report performance smoke test.
#
# Submits N concurrent LRC/LIC reconciliation jobs against the finance-service
# and measures end-to-end wall-clock time until the job status flips to
# `completed`. Meant for a local `make infra` + all-services running or a
# staging environment.
#
# Target from the plan (Phase 25 §Performance verification): p99 < 60s for a
# 20-portfolio tenant. Take the reported timings as ground truth for the
# chosen infra; scaling behaviour lives in the per-service load tests.
#
# Usage:
#   scripts/perf-test-ifrs17.sh                   # 10 jobs, defaults
#   CONCURRENCY=20 scripts/perf-test-ifrs17.sh    # 20 concurrent jobs
#   FINANCE_URL=https://finance.staging BASE_JWT=eyJ… scripts/perf-test-ifrs17.sh
#
# Env:
#   FINANCE_URL     finance-service base URL (default http://localhost:8085)
#   BASE_JWT        Bearer JWT for the finance-service call
#   CONCURRENCY     Number of concurrent submits (default 10)
#   PERIOD_START    ISO date (default: first of previous month)
#   PERIOD_END      ISO date (default: last of previous month)
#   POLL_INTERVAL   Seconds between status polls (default 2)
#   POLL_TIMEOUT    Seconds before we give up on a job (default 300)
# ---------------------------------------------------------------------------

set -euo pipefail

FINANCE_URL="${FINANCE_URL:-http://localhost:8085}"
BASE_JWT="${BASE_JWT:-}"
CONCURRENCY="${CONCURRENCY:-10}"
POLL_INTERVAL="${POLL_INTERVAL:-2}"
POLL_TIMEOUT="${POLL_TIMEOUT:-300}"

PERIOD_START="${PERIOD_START:-$(date -u -d 'first day of last month' +%Y-%m-%d 2>/dev/null || date -u -v-1m +%Y-%m-01)}"
PERIOD_END="${PERIOD_END:-$(date -u -d 'last day of last month' +%Y-%m-%d 2>/dev/null || date -u -v-1d -v1m +%Y-%m-%d)}"

if [[ -z "$BASE_JWT" ]]; then
    echo "ERROR: BASE_JWT is required — export a Bearer token for the finance-service call." >&2
    exit 2
fi

echo "── IFRS 17 perf test ──────────────────────────────"
echo "Finance URL   : $FINANCE_URL"
echo "Concurrency   : $CONCURRENCY"
echo "Period        : $PERIOD_START → $PERIOD_END"
echo "Poll interval : ${POLL_INTERVAL}s"
echo "Poll timeout  : ${POLL_TIMEOUT}s"
echo "───────────────────────────────────────────────────"

WORKDIR="$(mktemp -d -t ifrs17-perf-XXXXXX)"
trap 'rm -rf "$WORKDIR"' EXIT

submit_job() {
    local i="$1"
    local out="$WORKDIR/job-$i.json"
    curl -s -X POST "$FINANCE_URL/api/v1/reports/ifrs17/lrc-lic-reconciliation" \
        -H "Authorization: Bearer $BASE_JWT" \
        -H "Content-Type: application/json" \
        -d "{\"periodStart\":\"$PERIOD_START\",\"periodEnd\":\"$PERIOD_END\"}" \
        -o "$out" \
        -w "%{http_code}" > "$WORKDIR/job-$i.status"
    local http_code
    http_code="$(cat "$WORKDIR/job-$i.status")"
    if [[ "$http_code" != "201" && "$http_code" != "200" ]]; then
        echo "job-$i: submit failed HTTP $http_code — $(cat "$out")" >&2
        return 1
    fi
    # Response shape: {"jobId":"...","status":"requested",...}
    local job_id
    job_id="$(sed -n 's/.*"jobId":"\([^"]*\)".*/\1/p' "$out" | head -1)"
    if [[ -z "$job_id" ]]; then
        echo "job-$i: no jobId in response — $(cat "$out")" >&2
        return 1
    fi
    echo "$job_id"
}

poll_until_terminal() {
    local job_id="$1"
    local i="$2"
    local start_ts elapsed status
    start_ts="$(date +%s)"
    while true; do
        elapsed="$(( $(date +%s) - start_ts ))"
        if (( elapsed > POLL_TIMEOUT )); then
            echo "job-$i ($job_id): TIMEOUT after ${POLL_TIMEOUT}s" >&2
            return 1
        fi
        local resp
        resp="$(curl -s -H "Authorization: Bearer $BASE_JWT" \
            "$FINANCE_URL/api/v1/reports/jobs/$job_id")"
        status="$(printf '%s' "$resp" | sed -n 's/.*"status":"\([^"]*\)".*/\1/p' | head -1)"
        case "$status" in
            completed) echo "$elapsed"; return 0 ;;
            failed)    echo "job-$i ($job_id): FAILED after ${elapsed}s — $resp" >&2; return 1 ;;
            *)         sleep "$POLL_INTERVAL" ;;
        esac
    done
}

run_one() {
    local i="$1"
    local job_id
    if ! job_id="$(submit_job "$i")"; then
        echo "$i	-1	SUBMIT_FAILED" >> "$WORKDIR/results.tsv"
        return
    fi
    local elapsed
    if elapsed="$(poll_until_terminal "$job_id" "$i")"; then
        echo "$i	$elapsed	OK" >> "$WORKDIR/results.tsv"
    else
        echo "$i	-1	POLL_FAILED" >> "$WORKDIR/results.tsv"
    fi
}

echo "Launching $CONCURRENCY jobs …"
: > "$WORKDIR/results.tsv"
for i in $(seq 1 "$CONCURRENCY"); do
    run_one "$i" &
done
wait
echo "All jobs terminated."
echo

# Report — sorted by elapsed asc, plus p50/p95/p99.
sort -n -k2 "$WORKDIR/results.tsv" > "$WORKDIR/results.sorted.tsv"
successes="$(awk -F'\t' '$3=="OK"' "$WORKDIR/results.sorted.tsv")"
success_count="$(printf '%s\n' "$successes" | grep -c . || true)"
failure_count="$(( CONCURRENCY - success_count ))"

echo "── Timings ────────────────────────────────────────"
echo "job#	elapsed_s	status"
cat "$WORKDIR/results.sorted.tsv"
echo "───────────────────────────────────────────────────"

if (( success_count == 0 )); then
    echo "FAIL: zero successful jobs — check finance-service logs." >&2
    exit 1
fi

pct() {
    local pct="$1"
    printf '%s\n' "$successes" | awk -F'\t' '{print $2}' | sort -n | \
        awk -v pct="$pct" 'BEGIN{n=0} {a[n++]=$1} END{
            if (n==0) exit;
            idx=int((pct/100.0)*(n-1)+0.5);
            printf "%s\n", a[idx];
        }'
}

p50="$(pct 50)"
p95="$(pct 95)"
p99="$(pct 99)"
avg="$(printf '%s\n' "$successes" | awk -F'\t' '{s+=$2; n++} END{if(n>0) printf "%.2f", s/n}')"

echo
echo "── Summary ────────────────────────────────────────"
echo "successful   : $success_count / $CONCURRENCY"
echo "failed       : $failure_count"
echo "avg (s)      : $avg"
echo "p50 (s)      : $p50"
echo "p95 (s)      : $p95"
echo "p99 (s)      : $p99"
echo "───────────────────────────────────────────────────"

# Plan target: p99 < 60s for 20-portfolio tenant.
if (( p99 > 60 )); then
    echo "WARN: p99=${p99}s exceeds the 60s target." >&2
    exit 3
fi
echo "OK: p99=${p99}s is within the 60s target."
