#!/usr/bin/env bash
# Aggregate per-language coverage into a single human-readable table.
#
# Runs each language's test suite (skipping ones whose toolchain isn't on
# PATH), then parses the produced coverage artefact for the line-coverage
# percentage. Output is a compact table you can paste into a PR description.
#
# Usage:  bash scripts/coverage-summary.sh
#
# Backs the `make test-coverage` target. CI doesn't use this — Codecov is
# the source of truth there.

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

# Color helpers — disabled when output isn't a tty.
if [ -t 1 ]; then
  RED=$'\033[31m'; GREEN=$'\033[32m'; YELLOW=$'\033[33m'; DIM=$'\033[2m'; RESET=$'\033[0m'
else
  RED= ; GREEN= ; YELLOW= ; DIM= ; RESET=
fi

declare -a ROWS

note() { printf "%s%s%s\n" "$DIM" "$1" "$RESET"; }
row()  { printf "%-12s %-12s %-12s %s\n" "$1" "$2" "$3" "$4"; }

# ─── Angular ─────────────────────────────────────────────────────────────────
if command -v npx >/dev/null 2>&1 && [ -d clients/angular/node_modules ]; then
  note "[angular] running ng test --code-coverage…"
  (cd clients/angular && npx ng test --watch=false --code-coverage --browsers=ChromeHeadless >/dev/null 2>&1) \
    && PCT=$(awk '/Lines/{gsub("%",""); print $2; exit}' clients/angular/coverage/angular/index.html 2>/dev/null | head -1) \
    || PCT=""
  if [ -z "$PCT" ] && [ -f clients/angular/coverage/angular/lcov.info ]; then
    # Fallback: parse lcov.info — LF (lines found) and LH (lines hit) per file.
    PCT=$(awk -F: '/^LF:/{f+=$2} /^LH:/{h+=$2} END{if (f>0) printf "%.2f", h/f*100}' \
            clients/angular/coverage/angular/lcov.info)
  fi
  ROWS+=("angular|${PCT:-?}|coverage/angular/lcov.info|54.0")
else
  ROWS+=("angular|-|skipped — run web-setup first|-")
fi

# ─── Java ────────────────────────────────────────────────────────────────────
if command -v java >/dev/null 2>&1 && [ -f services/java/gradlew ]; then
  note "[java] running ./gradlew test jacocoTestReport…"
  (cd services/java && ./gradlew test jacocoTestReport >/dev/null 2>&1)
  # Sum INSTRUCTION counters across every subproject's report. JaCoCo XML
  # ships <counter type="INSTRUCTION" missed="X" covered="Y"/> at multiple
  # levels — we read the top-level <report> counter only (first match).
  PCT=$(python3 -c '
import glob, re
covered = missed = 0
for path in glob.glob("services/java/*/build/reports/jacoco/test/jacocoTestReport.xml"):
    with open(path) as f:
        head = f.read(8192)
    m = re.search(r"<counter type=\"LINE\" missed=\"(\d+)\" covered=\"(\d+)\"/>", head)
    if m:
        missed += int(m.group(1))
        covered += int(m.group(2))
total = covered + missed
print(f"{covered/total*100:.2f}" if total else "")
' 2>/dev/null)
  ROWS+=("java|${PCT:-?}|*/build/reports/jacoco/test/jacocoTestReport.xml|35.0")
else
  ROWS+=("java|-|skipped — JDK not on PATH|-")
fi

# ─── Go ──────────────────────────────────────────────────────────────────────
if command -v go >/dev/null 2>&1; then
  note "[go] running go test -coverprofile per service…"
  TOTAL_COVERED=0; TOTAL_STMTS=0
  for dir in gateway notification-service audit-service file-service payment-gateway; do
    [ -d "services/go/$dir" ] || continue
    (cd "services/go/$dir" && go test -coverprofile=coverage.out ./... >/dev/null 2>&1) || continue
    LINE=$(go tool cover -func="services/go/$dir/coverage.out" 2>/dev/null | awk '/total:/ {gsub("%",""); print $3}')
    [ -z "$LINE" ] && continue
    # No stmt-counts in -func output; use the per-service pct as an average.
    TOTAL_COVERED=$(awk "BEGIN {print $TOTAL_COVERED + $LINE}")
    TOTAL_STMTS=$((TOTAL_STMTS + 1))
  done
  if [ "$TOTAL_STMTS" -gt 0 ]; then
    PCT=$(awk "BEGIN {printf \"%.2f\", $TOTAL_COVERED / $TOTAL_STMTS}")
  else
    PCT=""
  fi
  ROWS+=("go|${PCT:-?}|*/coverage.out|50.0")
else
  ROWS+=("go|-|skipped — go not on PATH|-")
fi

# ─── Python ──────────────────────────────────────────────────────────────────
if command -v uv >/dev/null 2>&1 && [ -f services/python/ai-service/pyproject.toml ]; then
  note "[python] running uv run pytest --cov…"
  (cd services/python/ai-service && uv run pytest --cov-report=xml >/dev/null 2>&1) \
    && PCT=$(python3 -c '
import xml.etree.ElementTree as ET
r = ET.parse("services/python/ai-service/coverage.xml").getroot()
print(f"{float(r.get(\"line-rate\", 0))*100:.2f}")
' 2>/dev/null) \
    || PCT=""
  ROWS+=("python|${PCT:-?}|coverage.xml|65.0")
else
  ROWS+=("python|-|skipped — uv not on PATH|-")
fi

# ─── Elixir ──────────────────────────────────────────────────────────────────
if command -v mix >/dev/null 2>&1 && [ -d services/elixir ]; then
  note "[elixir] running mix coveralls.json…"
  (cd services/elixir && MIX_ENV=test mix coveralls.json --umbrella >/dev/null 2>&1) \
    && PCT=$(python3 -c '
import json
with open("services/elixir/cover/excoveralls.json") as f:
    data = json.load(f)
total = covered = 0
for src in data.get("source_files", []):
    cov = src.get("coverage", [])
    for c in cov:
        if c is None: continue
        total += 1
        if c > 0: covered += 1
print(f"{covered/total*100:.2f}" if total else "")
' 2>/dev/null) \
    || PCT=""
  ROWS+=("elixir|${PCT:-?}|cover/excoveralls.json|30.0")
else
  ROWS+=("elixir|-|skipped — mix not on PATH|-")
fi

# ─── Render ──────────────────────────────────────────────────────────────────
echo
row "Language" "Coverage" "Target" "Report"
printf '%s\n' "------------------------------------------------------------"
for line in "${ROWS[@]}"; do
  IFS='|' read -r lang pct report target <<< "$line"
  pct_disp="$pct"
  if [ "$pct" != "-" ] && [ "$pct" != "?" ] && [ -n "$target" ] && [ "$target" != "-" ]; then
    if awk "BEGIN {exit !($pct < $target)}"; then
      pct_disp="${RED}${pct}%${RESET}"
    else
      pct_disp="${GREEN}${pct}%${RESET}"
    fi
    target_disp="${target}%"
  else
    target_disp="$target"
  fi
  row "$lang" "$pct_disp" "$target_disp" "$report"
done
echo
echo "${DIM}Targets reflect the per-flag baseline in codecov.yml; the ratchet plan raises them +5% per month.${RESET}"
