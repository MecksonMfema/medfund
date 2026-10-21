#!/usr/bin/env bash
# =============================================================================
# InsureFlow — reset the two demo-seeder tenants (health-first, life-first)
#
# Drops the tenant Postgres schemas + rows, deletes their per-tenant Keycloak
# realms, and purges any exchange-rate rows tagged source='seeder'. Idempotent —
# safe to run repeatedly, safe when nothing is seeded yet.
#
# Non-seed tenants are untouched.
# =============================================================================
set -euo pipefail

: "${SEED_MODE_ENABLED:=false}"
if [ "$SEED_MODE_ENABLED" != "true" ]; then
  echo "ERROR: SEED_MODE_ENABLED=true must be set. Aborting." >&2
  exit 2
fi

if ! command -v psql >/dev/null 2>&1; then
  echo "ERROR: psql not found on PATH. Install postgresql-client." >&2
  exit 1
fi
if ! command -v curl >/dev/null 2>&1; then
  echo "ERROR: curl not found on PATH." >&2
  exit 1
fi
if ! command -v python3 >/dev/null 2>&1; then
  echo "ERROR: python3 not found on PATH." >&2
  exit 1
fi

POSTGRES_URL="${POSTGRES_URL:-postgres://medfund:medfund@localhost:5433/medfund}"
KEYCLOAK_URL="${KEYCLOAK_URL:-http://localhost:9080}"
ADMIN_USER="${KEYCLOAK_ADMIN:-admin}"
ADMIN_PASS="${KEYCLOAK_ADMIN_PASSWORD:-admin}"

TARGET_SLUGS=("health-first" "life-first")

echo "=== InsureFlow seed-tenant reset ==="
echo "Postgres: $POSTGRES_URL"
echo "Keycloak: $KEYCLOAK_URL"

# ── Keycloak admin token (master realm) ──────────────────────
echo "[1/3] Fetching Keycloak admin token..."
TOKEN=$(curl -sf -X POST "$KEYCLOAK_URL/realms/master/protocol/openid-connect/token" \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "grant_type=password&client_id=admin-cli&username=$ADMIN_USER&password=$ADMIN_PASS" \
  | python3 -c "import sys,json; print(json.load(sys.stdin)['access_token'])" 2>/dev/null || true)

if [ -z "$TOKEN" ]; then
  echo "  ! Could not fetch admin token — Keycloak may be down. Skipping realm delete."
else
  echo "  ✓ Admin token obtained"
fi

# ── Per-slug: drop schema + row, delete realm ───────────────
echo "[2/3] Removing per-tenant schemas and Keycloak realms..."
for SLUG in "${TARGET_SLUGS[@]}"; do
  # tenants table is only created after tenancy-service Flyway has run;
  # tolerate its absence on a fully-nuked infra.
  SCHEMA=$(psql "$POSTGRES_URL" -tAc \
    "SELECT schema_name FROM public.tenants WHERE slug='$SLUG'" 2>/dev/null || true)
  SCHEMA="${SCHEMA// /}"
  if [ -n "$SCHEMA" ]; then
    echo "  · $SLUG → dropping schema \"$SCHEMA\""
    psql "$POSTGRES_URL" -c "DROP SCHEMA IF EXISTS \"$SCHEMA\" CASCADE;" >/dev/null
    psql "$POSTGRES_URL" -c "DELETE FROM public.tenants WHERE slug='$SLUG';" >/dev/null
  else
    echo "  · $SLUG → no tenant row found (already reset)"
  fi

  REALM="medfund-$SLUG"
  if [ -n "$TOKEN" ]; then
    STATUS=$(curl -s -o /dev/null -w "%{http_code}" \
      -H "Authorization: Bearer $TOKEN" "$KEYCLOAK_URL/admin/realms/$REALM")
    if [ "$STATUS" = "200" ]; then
      echo "  · $SLUG → deleting Keycloak realm $REALM"
      curl -sf -X DELETE -H "Authorization: Bearer $TOKEN" \
        "$KEYCLOAK_URL/admin/realms/$REALM" >/dev/null
    else
      echo "  · $SLUG → realm $REALM absent (already reset)"
    fi
  fi
done

# ── Purge seeder-tagged exchange rates ───────────────────────
echo "[3/3] Purging seeder-tagged exchange rates..."
psql "$POSTGRES_URL" -c \
  "DELETE FROM public.exchange_rates WHERE source='seeder';" >/dev/null 2>&1 \
  || echo "  ! exchange_rates table absent — skipping (fresh infra)"

echo ""
echo "Reset complete."
