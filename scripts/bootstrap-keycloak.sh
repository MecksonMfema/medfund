#!/bin/bash
# ============================================================================
# MedFund — Keycloak Bootstrap Script
#
# Creates the medfund-platform realm, OIDC client, default roles,
# and a test super admin user so the Angular dashboard can authenticate.
#
# Prerequisites: Keycloak running at http://localhost:9080 (admin/admin)
# Usage: bash scripts/bootstrap-keycloak.sh
# ============================================================================

set -e

# Detect working Python command (python3 may be a Windows Store stub)
if python3 -c "pass" 2>/dev/null; then
  PY=python3
elif python -c "pass" 2>/dev/null; then
  PY=python
else
  echo "ERROR: No working Python found. Install Python 3."
  exit 1
fi

KEYCLOAK_URL="${KEYCLOAK_URL:-http://localhost:9080}"
ADMIN_USER="${KEYCLOAK_ADMIN:-admin}"
ADMIN_PASS="${KEYCLOAK_ADMIN_PASSWORD:-admin}"
REALM="medfund-platform"
CLIENT_ID="medfund-web"
ANGULAR_URL="http://localhost:4200"
GATEWAY_URL="http://localhost:3000"

echo "=== MedFund Keycloak Bootstrap ==="
echo "Keycloak URL: $KEYCLOAK_URL"
echo ""

# ── Wait for Keycloak to be ready ──────────────────────────────
echo "Waiting for Keycloak to be ready..."
for i in $(seq 1 30); do
  CODE=$(curl -s -o /dev/null -w "%{http_code}" "$KEYCLOAK_URL/realms/master" 2>/dev/null)
  if [ "$CODE" = "200" ]; then
    echo "  ✓ Keycloak is ready"
    break
  fi
  if [ "$i" = "30" ]; then
    echo "ERROR: Keycloak not ready after 60s. Is it running at $KEYCLOAK_URL?"
    exit 1
  fi
  sleep 2
done
echo ""

# ── Step 1: Get admin token ────────────────────────────────────
echo "[1/7] Getting admin token..."
TOKEN=$(curl -s -X POST "$KEYCLOAK_URL/realms/master/protocol/openid-connect/token" \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "grant_type=password&client_id=admin-cli&username=$ADMIN_USER&password=$ADMIN_PASS" \
  | $PY -c "import sys,json; print(json.load(sys.stdin)['access_token'])" 2>/dev/null)

if [ -z "$TOKEN" ]; then
  echo "ERROR: Failed to get admin token. Is Keycloak running at $KEYCLOAK_URL?"
  exit 1
fi
echo "  ✓ Admin token obtained"

# ── Step 2: Create realm ───────────────────────────────────────
echo "[2/7] Creating realm '$REALM'..."
REALM_EXISTS=$(curl -s -o /dev/null -w "%{http_code}" \
  -H "Authorization: Bearer $TOKEN" \
  "$KEYCLOAK_URL/admin/realms/$REALM")

if [ "$REALM_EXISTS" = "200" ]; then
  echo "  ✓ Realm already exists, skipping"
else
  curl -s -X POST "$KEYCLOAK_URL/admin/realms" \
    -H "Authorization: Bearer $TOKEN" \
    -H "Content-Type: application/json" \
    -d '{
      "realm": "'$REALM'",
      "enabled": true,
      "displayName": "MedFund Platform",
      "registrationAllowed": false,
      "resetPasswordAllowed": true,
      "loginTheme": "keycloak",
      "bruteForceProtected": true,
      "permanentLockout": false,
      "failureFactor": 5,
      "maxFailureWaitSeconds": 900,
      "accessTokenLifespan": 300,
      "ssoSessionIdleTimeout": 1800,
      "ssoSessionMaxLifespan": 36000
    }'
  echo "  ✓ Realm created"
fi

# ── Step 2b: Configure SMTP (Mailpit) ─────────────────────────
# Keycloak (running in Docker) reaches Mailpit via the container name.
# Override SMTP_HOST if running Keycloak natively: SMTP_HOST=localhost
SMTP_HOST="${SMTP_HOST:-mailpit}"
echo "[2b/7] Configuring SMTP → $SMTP_HOST:1025 (Mailpit)..."
curl -s -X PUT "$KEYCLOAK_URL/admin/realms/$REALM" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "smtpServer": {
      "host": "'"$SMTP_HOST"'",
      "port": "1025",
      "from": "noreply@medfund.local",
      "fromDisplayName": "MedFund Platform",
      "ssl": "false",
      "starttls": "false",
      "auth": "false"
    }
  }' > /dev/null
echo "  ✓ SMTP configured (all emails captured by Mailpit at http://localhost:8025)"

# ── Step 3: Create OIDC client ─────────────────────────────────
echo "[3/7] Creating OIDC client '$CLIENT_ID'..."
CLIENT_EXISTS=$(curl -s -H "Authorization: Bearer $TOKEN" \
  "$KEYCLOAK_URL/admin/realms/$REALM/clients?clientId=$CLIENT_ID" \
  | $PY -c "import sys,json; d=json.load(sys.stdin); print(len(d))" 2>/dev/null \
  || echo "0")

if [ "$CLIENT_EXISTS" != "0" ]; then
  echo "  ✓ Client already exists, skipping"
else
  curl -s -X POST "$KEYCLOAK_URL/admin/realms/$REALM/clients" \
    -H "Authorization: Bearer $TOKEN" \
    -H "Content-Type: application/json" \
    -d '{
      "clientId": "'$CLIENT_ID'",
      "name": "MedFund Web App",
      "enabled": true,
      "publicClient": true,
      "directAccessGrantsEnabled": true,
      "standardFlowEnabled": true,
      "implicitFlowEnabled": false,
      "rootUrl": "'$ANGULAR_URL'",
      "baseUrl": "'$ANGULAR_URL'",
      "redirectUris": [
        "'$ANGULAR_URL'/*",
        "'$GATEWAY_URL'/*",
        "http://localhost:3000/*"
      ],
      "webOrigins": [
        "'$ANGULAR_URL'",
        "'$GATEWAY_URL'",
        "+"
      ],
      "protocol": "openid-connect",
      "attributes": {
        "pkce.code.challenge.method": "S256"
      }
    }'
  echo "  ✓ Client created"
fi

# ── Step 4: Create realm roles ─────────────────────────────────
echo "[4/7] Creating realm roles..."
for ROLE in super_admin tenant_admin claims_clerk claims_assessor finance_officer contributions_officer provider member group_liaison; do
  RESULT=$(curl -s -o /dev/null -w "%{http_code}" -X POST \
    "$KEYCLOAK_URL/admin/realms/$REALM/roles" \
    -H "Authorization: Bearer $TOKEN" \
    -H "Content-Type: application/json" \
    -d '{"name": "'$ROLE'", "description": "MedFund role: '$ROLE'"}')
  if [ "$RESULT" = "201" ]; then
    echo "  ✓ Role created: $ROLE"
  elif [ "$RESULT" = "409" ]; then
    echo "  - Role exists: $ROLE"
  else
    echo "  ✗ Failed to create role $ROLE (HTTP $RESULT)"
  fi
done

# ── Step 5: Add tenant_id to token via protocol mapper ─────────
echo "[5/7] Adding tenant_id protocol mapper to client..."
CLIENT_UUID=$(curl -s -H "Authorization: Bearer $TOKEN" \
  "$KEYCLOAK_URL/admin/realms/$REALM/clients?clientId=$CLIENT_ID" \
  | $PY -c "import sys,json; print(json.load(sys.stdin)[0]['id'])" 2>/dev/null || echo "")

if [ -n "$CLIENT_UUID" ]; then
  curl -s -X POST "$KEYCLOAK_URL/admin/realms/$REALM/clients/$CLIENT_UUID/protocol-mappers/models" \
    -H "Authorization: Bearer $TOKEN" \
    -H "Content-Type: application/json" \
    -d '{
      "name": "tenant_id",
      "protocol": "openid-connect",
      "protocolMapper": "oidc-hardcoded-claim-mapper",
      "config": {
        "claim.name": "tenant_id",
        "claim.value": "platform",
        "jsonType.label": "String",
        "id.token.claim": "true",
        "access.token.claim": "true",
        "userinfo.token.claim": "true"
      }
    }' 2>/dev/null
  echo "  ✓ tenant_id mapper added (default: 'platform')"
else
  echo "  ✗ Could not find client UUID"
fi

# ── Step 6: Create test super admin user ───────────────────────
echo "[6/7] Creating test super admin user..."
USER_EXISTS=$(curl -s -H "Authorization: Bearer $TOKEN" \
  "$KEYCLOAK_URL/admin/realms/$REALM/users?username=superadmin" \
  | $PY -c "import sys,json; d=json.load(sys.stdin); print(len(d))" 2>/dev/null \
  || echo "0")

if [ "$USER_EXISTS" != "0" ]; then
  echo "  ✓ User 'superadmin' already exists, skipping"
else
  # Create user
  curl -s -X POST "$KEYCLOAK_URL/admin/realms/$REALM/users" \
    -H "Authorization: Bearer $TOKEN" \
    -H "Content-Type: application/json" \
    -d '{
      "username": "superadmin",
      "email": "admin@medfund.healthcare",
      "firstName": "Super",
      "lastName": "Admin",
      "enabled": true,
      "emailVerified": true,
      "credentials": [{
        "type": "password",
        "value": "admin123",
        "temporary": false
      }]
    }'
  echo "  ✓ User 'superadmin' created (password: admin123)"

  # Get user ID
  USER_ID=$(curl -s -H "Authorization: Bearer $TOKEN" \
    "$KEYCLOAK_URL/admin/realms/$REALM/users?username=superadmin" \
    | $PY -c "import sys,json; print(json.load(sys.stdin)[0]['id'])" 2>/dev/null || echo "")

  # Assign super_admin role
  if [ -n "$USER_ID" ]; then
    ROLE_ID=$(curl -s -H "Authorization: Bearer $TOKEN" \
      "$KEYCLOAK_URL/admin/realms/$REALM/roles/super_admin" \
      | $PY -c "import sys,json; print(json.load(sys.stdin)['id'])" 2>/dev/null || echo "")

    if [ -n "$ROLE_ID" ]; then
      curl -s -X POST "$KEYCLOAK_URL/admin/realms/$REALM/users/$USER_ID/role-mappings/realm" \
        -H "Authorization: Bearer $TOKEN" \
        -H "Content-Type: application/json" \
        -d '[{"id": "'$ROLE_ID'", "name": "super_admin"}]'
      echo "  ✓ Role 'super_admin' assigned to user"
    fi
  fi
fi

# ── Step 7: Create additional test users ───────────────────────
echo "[7/7] Creating additional test users..."

create_user() {
  local USERNAME=$1
  local EMAIL=$2
  local FIRST=$3
  local LAST=$4
  local ROLE=$5

  local EXISTS=$(curl -s -H "Authorization: Bearer $TOKEN" \
    "$KEYCLOAK_URL/admin/realms/$REALM/users?username=$USERNAME" \
    | $PY -c "import sys,json; d=json.load(sys.stdin); print(len(d))" 2>/dev/null || echo "0")

  if [ "$EXISTS" != "0" ]; then
    echo "  - User '$USERNAME' exists"
    return
  fi

  curl -s -X POST "$KEYCLOAK_URL/admin/realms/$REALM/users" \
    -H "Authorization: Bearer $TOKEN" \
    -H "Content-Type: application/json" \
    -d '{
      "username": "'$USERNAME'",
      "email": "'$EMAIL'",
      "firstName": "'$FIRST'",
      "lastName": "'$LAST'",
      "enabled": true,
      "emailVerified": true,
      "credentials": [{"type": "password", "value": "test123", "temporary": false}]
    }'

  local USER_UUID=$(curl -s -H "Authorization: Bearer $TOKEN" \
    "$KEYCLOAK_URL/admin/realms/$REALM/users?username=$USERNAME" \
    | $PY -c "import sys,json; print(json.load(sys.stdin)[0]['id'])" 2>/dev/null || echo "")

  if [ -n "$USER_UUID" ] && [ -n "$ROLE" ]; then
    local RID=$(curl -s -H "Authorization: Bearer $TOKEN" \
      "$KEYCLOAK_URL/admin/realms/$REALM/roles/$ROLE" \
      | $PY -c "import sys,json; print(json.load(sys.stdin)['id'])" 2>/dev/null || echo "")

    if [ -n "$RID" ]; then
      curl -s -X POST "$KEYCLOAK_URL/admin/realms/$REALM/users/$USER_UUID/role-mappings/realm" \
        -H "Authorization: Bearer $TOKEN" \
        -H "Content-Type: application/json" \
        -d '[{"id": "'$RID'", "name": "'$ROLE'"}]'
    fi
  fi
  echo "  ✓ User '$USERNAME' created (role: $ROLE, password: test123)"
}

create_user "claimsclerk" "claims@medfund.healthcare" "Claims" "Clerk" "claims_clerk"
create_user "financeofficer" "finance@medfund.healthcare" "Finance" "Officer" "finance_officer"
create_user "contribofficer" "contrib@medfund.healthcare" "Contributions" "Officer" "contributions_officer"
create_user "testmember" "member@test.com" "John" "Doe" "member"
create_user "testprovider" "provider@test.com" "City" "Hospital" "provider"

echo ""
echo "=== Bootstrap Complete ==="
echo ""
echo "Test Accounts:"
echo "  ┌─────────────────┬───────────────┬──────────────────────┐"
echo "  │ Username         │ Password      │ Role                 │"
echo "  ├─────────────────┼───────────────┼──────────────────────┤"
echo "  │ superadmin       │ admin123      │ super_admin          │"
echo "  │ claimsclerk      │ test123       │ claims_clerk         │"
echo "  │ financeofficer   │ test123       │ finance_officer      │"
echo "  │ contribofficer   │ test123       │ contributions_officer│"
echo "  │ testmember       │ test123       │ member               │"
echo "  │ testprovider     │ test123       │ provider             │"
echo "  └─────────────────┴───────────────┴──────────────────────┘"
echo ""
echo "Angular App: $ANGULAR_URL"
echo "Keycloak:    $KEYCLOAK_URL/admin"
echo "Realm:       $REALM"
echo "Client ID:   $CLIENT_ID"
echo "Mailpit:     http://localhost:8025  (catches all outbound emails)"
