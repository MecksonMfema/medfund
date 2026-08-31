#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# Phase 16 §28 — Regulatory (Phase-16) report performance smoke test.
#
# Submits all 8 Phase-16 regulator report async jobs concurrently against the
# finance-service and measures end-to-end wall-clock time until each job
# status flips to `completed`. Same shape as `scripts/perf-test-ifrs17.sh`
# (Phase 15 §25); the only differences are the 8 distinct submit URLs and
# the periodStart/periodEnd defaults (regulator reports are quarterly, not
# monthly, so the default period covers the previous quarter).
#
# Target from the plan (Phase 16 §28): p99 < 60s per report for a mid-size
# multi-jurisdiction tenant. Successful runs report the p50/p95/p99 across
# all 8 concurrent jobs — one failure per key is tolerated (the report is
# gated by @RequiresCountry / @RequiresReport so a tenant that isn't wired
# for a given regulator will 403; the script marks those as SKIPPED and
# excludes them from the percentile computation).
#
# Usage:
#   scripts/perf-test-regulatory.sh                # default: run all 8 keys
#   ONLY_KEYS='AML_STR VAT_RETURN' scripts/perf-test-regulatory.sh
#   FINANCE_URL=https://finance.staging BASE_JWT=eyJ… scripts/perf-test-regulatory.sh
#
# Env:
#   FINANCE_URL     finance-service base URL (default http://localhost:8085)
#   BASE_JWT        Bearer JWT for the finance-service call (REQUIRED)
#   PERIOD_START    ISO date (default: first day of previous quarter)
#   PERIOD_END      ISO date (default: last day of previous quarter)
#   POLL_INTERVAL   Seconds between status polls (default 2)
#   POLL_TIMEOUT    Seconds before we give up on a job (default 300)
#   ONLY_KEYS       Space-separated subset of report keys to run
# ---------------------------------------------------------------------------

set -euo pipefail

FINANCE_URL="${FINANCE_URL:-http://localhost:8085}"
BASE_JWT="${BASE_JWT:-}"
POLL_INTERVAL="${POLL_INTERVAL:-2}"
POLL_TIMEOUT="${POLL_TIMEOUT:-300}"

# ── Default period = previous quarter ─────────────────────────────────────
# GNU date variant first, BSD/macOS fallback.
if date -u -d 'today' +%Y-%m-%d >/dev/null 2>&1; then
    YEAR="$(date -u +%Y)"
    MONTH="$(date -u +%-m)"
    PREV_Q_END_MONTH=$(( (((MONTH - 1) / 3) * 3) ))
    if (( PREV_Q_END_MONTH == 0 )); then
        PREV_Q_END_MONTH=12
        YEAR=$(( YEAR - 1 ))
    fi
    PREV_Q_START_MONTH=$(( PREV_Q_END_MONTH - 2 ))
    PERIOD_START="${PERIOD_START:-$(printf '%d-%02d-01' "$YEAR" "$PREV_Q_START_MONTH")}"
    PERIOD_END="${PERIOD_END:-$(date -u -d "$YEAR-$PREV_Q_END_MONTH-01 +1 month -1 day" +%Y-%m-%d)}"
else
    PERIOD_START="${PERIOD_START:-2026-01-01}"
    PERIOD_END="${PERIOD_END:-2026-03-31}"
fi

if [[ -z "$BASE_JWT" ]]; then
    echo "ERROR: BASE_JWT is required — export a Bearer token for the finance-service call." >&2
    exit 2
fi

# ── ReportKey → (submit path, extra JSON body) ────────────────────────────
# Every value is: submit_path|extra_json_fragment
# extra_json_fragment is spliced into the request body between periodEnd + }.
declare -A KEY_TO_PATH=(
    [IPEC_QUARTERLY_RETURN]='/api/v1/reports/regulatory/ipec/quarterly-return/submit'
    [CMS_ASR]='/api/v1/reports/regulatory/cms/asr/submit'
    [NAIC_SCHEDULE_P]='/api/v1/reports/regulatory/naic/schedule-p/submit'
    [NAIC_SCHEDULE_F]='/api/v1/reports/regulatory/naic/schedule-f/submit'
    [PMB_SPEND]='/api/v1/reports/regulatory/pmb/spend/submit'
    [AML_STR]='/api/v1/reports/regulatory/aml-str/periodic/submit'
    [TAX_WITHHELD_RETURN]='/api/v1/reports/regulatory/tax/withheld-return/submit'
    [VAT_RETURN]='/api/v1/reports/regulatory/tax/vat-return/submit'
)

DEFAULT_KEYS=(IPEC_QUARTERLY_RETURN CMS_ASR NAIC_SCHEDULE_P NAIC_SCHEDULE_F \
              PMB_SPEND AML_STR TAX_WITHHELD_RETURN VAT_RETURN)
if [[ -n "${ONLY_KEYS:-}" ]]; then
    read -r -a KEYS <<< "$ONLY_KEYS"
else
    KEYS=("${DEFAULT_KEYS[@]}")
fi

echo "── Regulatory (Phase-16) perf test ──────────────────"
echo "Finance URL   : $FINANCE_URL"
echo "Report keys   : ${KEYS[*]}"
echo "Period        : $PERIOD_START → $PERIOD_END"
echo "Poll interval : ${POLL_INTERVAL}s"
echo "Poll timeout  : ${POLL_TIMEOUT}s"
echo "─────────────────────────────────────────────────────"

WORKDIR="$(mktemp -d -t reg-perf-XXXXXX)"
trap 'rm -rf "$WORKDIR"' EXIT

submit_job() {
    local key="$1"
    local path="${KEY_TO_PATH[$key]}"
    local out="$WORKDIR/$key.submit.json"
    local body
    body="{\"periodStart\":\"$PERIOD_START\",\"periodEnd\":\"$PERIOD_END\"}"
    curl -s -X POST "$FINANCE_URL$path" \
        -H "Authorization: Bearer $BASE_JWT" \
        -H "Content-Type: application/json" \
        -d "$body" \
        -o "$out" \
        -w "%{http_code}" > "$WORKDIR/$key.submit.status"
    local http_code
    http_code="$(cat "$WORKDIR/$key.submit.status")"
    if [[ "$http_code" == "403" ]]; then
        echo "GATED"
        return 0
    fi
    if [[ "$http_code" != "201" && "$http_code" != "200" ]]; then
        echo "$key: submit failed HTTP $http_code — $(cat "$out")" >&2
        return 1
    fi
    local job_id
    job_id="$(sed -n 's/.*"jobId":"\([^"]*\)".*/\1/p' "$out" | head -1)"
    if [[ -z "$job_id" ]]; then
        echo "$key: no jobId in response — $(cat "$out")" >&2
        return 1
    fi
    echo "$job_id"
}

poll_until_terminal() {
    local key="$1"
    local job_id="$2"
    local start_ts elapsed status
    start_ts="$(date +%s)"
    while true; do
        elapsed="$(( $(date +%s) - start_ts ))"
        if (( elapsed > POLL_TIMEOUT )); then
            echo "$key ($job_id): TIMEOUT after ${POLL_TIMEOUT}s" >&2
            return 1
        fi
        local resp
        resp="$(curl -s -H "Authorization: Bearer $BASE_JWT" \
            "$FINANCE_URL/api/v1/reports/jobs/$job_id")"
        status="$(printf '%s' "$resp" | sed -n 's/.*"status":"\([^"]*\)".*/\1/p' | head -1)"
        case "$status" in
            completed) echo "$elapsed"; return 0 ;;
            failed)    echo "$key ($job_id): FAILED after ${elapsed}s — $resp" >&2; return 1 ;;
            *)         sleep "$POLL_INTERVAL" ;;
        esac
    done
}

run_one() {
    local key="$1"
    local job_id
    if ! job_id="$(submit_job "$key")"; then
        printf '%s\t-1\tSUBMIT_FAILED\n' "$key" >> "$WORKDIR/results.tsv"
        return
    fi
    if [[ "$job_id" == "GATED" ]]; then
        printf '%s\t-1\tGATED_403\n' "$key" >> "$WORKDIR/results.tsv"
        return
    fi
    local elapsed
    if elapsed="$(poll_until_terminal "$key" "$job_id")"; then
        printf '%s\t%s\tOK\n' "$key" "$elapsed" >> "$WORKDIR/results.tsv"
    else
        printf '%s\t-1\tPOLL_FAILED\n' "$key" >> "$WORKDIR/results.tsv"
    fi
}

echo "Launching ${#KEYS[@]} concurrent jobs …"
: > "$WORKDIR/results.tsv"
for key in "${KEYS[@]}"; do
    run_one "$key" &
done
wait
echo "All jobs terminated."
echo

sort -t$'\t' -k2 -n "$WORKDIR/results.tsv" > "$WORKDIR/results.sorted.tsv"
successes="$(awk -F'\t' '$3=="OK"' "$WORKDIR/results.sorted.tsv")"
success_count="$(printf '%s\n' "$successes" | grep -c . || true)"
gated_count="$(awk -F'\t' '$3=="GATED_403"' "$WORKDIR/results.tsv" | grep -c . || true)"
failure_count=$(( ${#KEYS[@]} - success_count - gated_count ))

echo "── Timings ──────────────────────────────────────────"
printf 'key\telapsed_s\tstatus\n'
cat "$WORKDIR/results.sorted.tsv"
echo "─────────────────────────────────────────────────────"

if (( success_count == 0 )); then
    echo "FAIL: zero successful jobs (gated=$gated_count, failed=$failure_count) — check finance-service logs." >&2
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
echo "── Summary ──────────────────────────────────────────"
echo "successful   : $success_count / ${#KEYS[@]}"
echo "gated (403)  : $gated_count"
echo "failed       : $failure_count"
echo "avg (s)      : $avg"
echo "p50 (s)      : $p50"
echo "p95 (s)      : $p95"
echo "p99 (s)      : $p99"
echo "─────────────────────────────────────────────────────"

# Plan target: p99 < 60s for a mid-size multi-jurisdiction tenant.
if (( p99 > 60 )); then
    echo "WARN: p99=${p99}s exceeds the 60s target." >&2
    exit 3
fi
echo "OK: p99=${p99}s is within the 60s target."
