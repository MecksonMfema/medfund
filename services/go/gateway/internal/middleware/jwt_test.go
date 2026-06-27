package middleware

import (
	"crypto/rand"
	"crypto/rsa"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/gofiber/fiber/v2"
	"github.com/golang-jwt/jwt/v5"
)

// newMiddlewareWithKey returns a JWTMiddleware whose public-key cache is
// pre-populated, so validateToken never touches the real Keycloak JWKS endpoint.
func newMiddlewareWithKey(t *testing.T) (*JWTMiddleware, *rsa.PrivateKey, string) {
	t.Helper()
	priv, err := rsa.GenerateKey(rand.Reader, 2048)
	if err != nil {
		t.Fatalf("generate key: %v", err)
	}
	m := NewJWTMiddleware("http://keycloak.invalid", "medfund", nil)
	const kid = "test-kid"
	m.publicKeys[kid] = &priv.PublicKey
	m.lastFetch = time.Now() // freshen the cache so getPublicKey returns immediately
	return m, priv, kid
}

func signToken(t *testing.T, priv *rsa.PrivateKey, kid string, claims jwt.MapClaims) string {
	t.Helper()
	tok := jwt.NewWithClaims(jwt.SigningMethodRS256, claims)
	tok.Header["kid"] = kid
	signed, err := tok.SignedString(priv)
	if err != nil {
		t.Fatalf("sign: %v", err)
	}
	return signed
}

// ─── validateToken ───────────────────────────────────────────────────────────

func TestValidateToken_validRS256TokenReturnsClaims(t *testing.T) {
	m, priv, kid := newMiddlewareWithKey(t)
	tokenStr := signToken(t, priv, kid, jwt.MapClaims{
		"sub": "user-1",
		"exp": time.Now().Add(5 * time.Minute).Unix(),
	})

	claims, err := m.validateToken(tokenStr)
	if err != nil {
		t.Fatalf("expected ok, got error: %v", err)
	}
	if claims["sub"] != "user-1" {
		t.Errorf("unexpected sub: %v", claims["sub"])
	}
}

func TestValidateToken_rejectsExpiredToken(t *testing.T) {
	m, priv, kid := newMiddlewareWithKey(t)
	tokenStr := signToken(t, priv, kid, jwt.MapClaims{
		"sub": "user-1",
		"exp": time.Now().Add(-5 * time.Minute).Unix(),
	})

	_, err := m.validateToken(tokenStr)
	if err == nil {
		t.Fatal("expected expired-token error, got nil")
	}
}

func TestValidateToken_rejectsHMACTokenWhenRSAExpected(t *testing.T) {
	m, _, _ := newMiddlewareWithKey(t)
	tok := jwt.NewWithClaims(jwt.SigningMethodHS256, jwt.MapClaims{"sub": "x"})
	signed, _ := tok.SignedString([]byte("secret"))

	_, err := m.validateToken(signed)
	if err == nil {
		t.Fatal("expected signing-method error, got nil")
	}
}

func TestValidateToken_rejectsTokenWithUnknownKid(t *testing.T) {
	m, priv, _ := newMiddlewareWithKey(t)
	// kid that's not in the cache — and not fetchable because the Keycloak URL
	// is unreachable in this test.
	tokenStr := signToken(t, priv, "unknown-kid", jwt.MapClaims{"sub": "user-1", "exp": time.Now().Add(time.Minute).Unix()})

	_, err := m.validateToken(tokenStr)
	if err == nil {
		t.Fatal("expected unknown-kid error, got nil")
	}
}

// ─── extractToken ────────────────────────────────────────────────────────────

func TestExtractToken_prefersCookieOverHeader(t *testing.T) {
	m, _, _ := newMiddlewareWithKey(t)
	app := fiber.New()

	var got string
	app.Get("/probe", func(c *fiber.Ctx) error {
		got = m.extractToken(c)
		return c.SendString("ok")
	})
	req := httptest.NewRequest("GET", "/probe", nil)
	req.AddCookie(&http.Cookie{Name: "access_token", Value: "from-cookie"})
	req.Header.Set("Authorization", "Bearer from-header")

	if _, err := app.Test(req); err != nil {
		t.Fatalf("app.Test: %v", err)
	}
	if got != "from-cookie" {
		t.Errorf("expected cookie token, got %q", got)
	}
}

func TestExtractToken_fallsBackToAuthorizationHeader(t *testing.T) {
	m, _, _ := newMiddlewareWithKey(t)
	app := fiber.New()
	var got string
	app.Get("/probe", func(c *fiber.Ctx) error {
		got = m.extractToken(c)
		return c.SendString("ok")
	})
	req := httptest.NewRequest("GET", "/probe", nil)
	req.Header.Set("Authorization", "Bearer header-token")

	if _, err := app.Test(req); err != nil {
		t.Fatalf("app.Test: %v", err)
	}
	if got != "header-token" {
		t.Errorf("expected header-token, got %q", got)
	}
}

func TestExtractToken_returnsEmptyWhenNoToken(t *testing.T) {
	m, _, _ := newMiddlewareWithKey(t)
	app := fiber.New()
	var got string
	app.Get("/probe", func(c *fiber.Ctx) error {
		got = m.extractToken(c)
		return c.SendString("ok")
	})
	req := httptest.NewRequest("GET", "/probe", nil)
	if _, err := app.Test(req); err != nil {
		t.Fatalf("app.Test: %v", err)
	}
	if got != "" {
		t.Errorf("expected empty token, got %q", got)
	}
}

// ─── Handler() — request-level behaviour ─────────────────────────────────────

func TestHandler_skipsAuthForHealthAndSwagger(t *testing.T) {
	m, _, _ := newMiddlewareWithKey(t)
	app := fiber.New()
	app.Use(m.Handler())
	app.Get("/health", func(c *fiber.Ctx) error { return c.SendString("up") })
	app.Get("/swagger/index.html", func(c *fiber.Ctx) error { return c.SendString("docs") })

	for _, path := range []string{"/health", "/swagger/index.html"} {
		resp, err := app.Test(httptest.NewRequest("GET", path, nil))
		if err != nil {
			t.Fatalf("path %s: %v", path, err)
		}
		if resp.StatusCode != 200 {
			t.Errorf("path %s expected 200, got %d", path, resp.StatusCode)
		}
	}
}

func TestHandler_returns401WhenTokenMissing(t *testing.T) {
	m, _, _ := newMiddlewareWithKey(t)
	app := fiber.New()
	app.Use(m.Handler())
	app.Get("/secret", func(c *fiber.Ctx) error { return c.SendString("secret") })

	resp, err := app.Test(httptest.NewRequest("GET", "/secret", nil))
	if err != nil {
		t.Fatalf("app.Test: %v", err)
	}
	if resp.StatusCode != 401 {
		t.Errorf("expected 401, got %d", resp.StatusCode)
	}
}

func TestHandler_setsXTenantIDOnlyWhenClaimIsValidUUID(t *testing.T) {
	m, priv, kid := newMiddlewareWithKey(t)
	app := fiber.New()
	app.Use(m.Handler())

	var seenTenantHeader string
	app.Get("/probe", func(c *fiber.Ctx) error {
		seenTenantHeader = string(c.Request().Header.Peek("X-Tenant-ID"))
		return c.SendString("ok")
	})

	cases := []struct {
		name      string
		tenantVal string
		wantHdr   string
	}{
		{"valid UUID is forwarded", "11111111-2222-3333-4444-555555555555", "11111111-2222-3333-4444-555555555555"},
		{"sentinel 'platform' is dropped", "platform", ""},
		{"empty string is dropped", "", ""},
		{"non-UUID slug is dropped", "tenant-acme", ""},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			seenTenantHeader = ""
			tokenStr := signToken(t, priv, kid, jwt.MapClaims{
				"sub":       "user-1",
				"tenant_id": tc.tenantVal,
				"exp":       time.Now().Add(time.Minute).Unix(),
			})
			req := httptest.NewRequest("GET", "/probe", nil)
			req.Header.Set("Authorization", "Bearer "+tokenStr)
			resp, err := app.Test(req)
			if err != nil {
				t.Fatalf("app.Test: %v", err)
			}
			if resp.StatusCode != 200 {
				t.Fatalf("expected 200, got %d", resp.StatusCode)
			}
			if seenTenantHeader != tc.wantHdr {
				t.Errorf("X-Tenant-ID = %q, want %q", seenTenantHeader, tc.wantHdr)
			}
		})
	}
}

// ─── X-Platform-Scope marker — gateway-only, super-admin only ────────────────

// probeScope spins up a tiny app that records both X-Tenant-ID and
// X-Platform-Scope as seen by the downstream handler after middleware runs.
func probeScope(t *testing.T, m *JWTMiddleware) (*fiber.App, *string, *string) {
	t.Helper()
	var tenantSeen, scopeSeen string
	app := fiber.New()
	app.Use(m.Handler())
	app.Get("/probe", func(c *fiber.Ctx) error {
		tenantSeen = string(c.Request().Header.Peek("X-Tenant-ID"))
		scopeSeen = string(c.Request().Header.Peek("X-Platform-Scope"))
		return c.SendString("ok")
	})
	return app, &tenantSeen, &scopeSeen
}

func TestHandler_setsPlatformScopeForSuperAdminWithoutTenant(t *testing.T) {
	m, priv, kid := newMiddlewareWithKey(t)
	app, tenantSeen, scopeSeen := probeScope(t, m)

	tokenStr := signToken(t, priv, kid, jwt.MapClaims{
		"sub":          "super-1",
		"realm_access": map[string]interface{}{"roles": []interface{}{"super_admin"}},
		"exp":          time.Now().Add(time.Minute).Unix(),
	})
	req := httptest.NewRequest("GET", "/probe", nil)
	req.Header.Set("Authorization", "Bearer "+tokenStr)
	if _, err := app.Test(req); err != nil {
		t.Fatalf("app.Test: %v", err)
	}
	if *tenantSeen != "" {
		t.Errorf("X-Tenant-ID = %q, want empty", *tenantSeen)
	}
	if *scopeSeen != "all" {
		t.Errorf("X-Platform-Scope = %q, want \"all\"", *scopeSeen)
	}
}

func TestHandler_doesNotSetPlatformScopeForNonSuperAdmin(t *testing.T) {
	m, priv, kid := newMiddlewareWithKey(t)
	app, _, scopeSeen := probeScope(t, m)

	tokenStr := signToken(t, priv, kid, jwt.MapClaims{
		"sub":          "user-1",
		"realm_access": map[string]interface{}{"roles": []interface{}{"operator"}},
		"exp":          time.Now().Add(time.Minute).Unix(),
	})
	req := httptest.NewRequest("GET", "/probe", nil)
	req.Header.Set("Authorization", "Bearer "+tokenStr)
	if _, err := app.Test(req); err != nil {
		t.Fatalf("app.Test: %v", err)
	}
	if *scopeSeen != "" {
		t.Errorf("X-Platform-Scope = %q, want empty for non-super-admin", *scopeSeen)
	}
}

func TestHandler_stripsClientSuppliedPlatformScope(t *testing.T) {
	m, priv, kid := newMiddlewareWithKey(t)
	app, _, scopeSeen := probeScope(t, m)

	// A regular tenant user shouldn't be able to spoof platform scope by
	// setting the header in their browser — the gateway must clear it.
	tokenStr := signToken(t, priv, kid, jwt.MapClaims{
		"sub": "user-1",
		"exp": time.Now().Add(time.Minute).Unix(),
	})
	req := httptest.NewRequest("GET", "/probe", nil)
	req.Header.Set("Authorization", "Bearer "+tokenStr)
	req.Header.Set("X-Platform-Scope", "all")
	if _, err := app.Test(req); err != nil {
		t.Fatalf("app.Test: %v", err)
	}
	if *scopeSeen != "" {
		t.Errorf("X-Platform-Scope = %q, want empty after strip", *scopeSeen)
	}
}

func TestHandler_doesNotOverridePlatformScopeWhenSuperAdminHasTenant(t *testing.T) {
	m, priv, kid := newMiddlewareWithKey(t)
	app, tenantSeen, scopeSeen := probeScope(t, m)

	// Super admin already pinned to a tenant context (via SPA-attached
	// X-Tenant-ID) is scoped to that tenant, not platform-wide.
	tokenStr := signToken(t, priv, kid, jwt.MapClaims{
		"sub":          "super-1",
		"realm_access": map[string]interface{}{"roles": []interface{}{"super_admin"}},
		"exp":          time.Now().Add(time.Minute).Unix(),
	})
	req := httptest.NewRequest("GET", "/probe", nil)
	req.Header.Set("Authorization", "Bearer "+tokenStr)
	req.Header.Set("X-Tenant-ID", "11111111-2222-3333-4444-555555555555")
	if _, err := app.Test(req); err != nil {
		t.Fatalf("app.Test: %v", err)
	}
	if *tenantSeen != "11111111-2222-3333-4444-555555555555" {
		t.Errorf("X-Tenant-ID = %q, want preserved", *tenantSeen)
	}
	if *scopeSeen != "" {
		t.Errorf("X-Platform-Scope = %q, want empty when tenant is pinned", *scopeSeen)
	}
}

func TestHandler_returnsErrorBodyWithMessageOnInvalidToken(t *testing.T) {
	m, _, _ := newMiddlewareWithKey(t)
	app := fiber.New()
	app.Use(m.Handler())
	app.Get("/secret", func(c *fiber.Ctx) error { return c.SendString("secret") })

	req := httptest.NewRequest("GET", "/secret", nil)
	req.Header.Set("Authorization", "Bearer not.a.real.jwt")
	resp, err := app.Test(req)
	if err != nil {
		t.Fatalf("app.Test: %v", err)
	}
	if resp.StatusCode != 401 {
		t.Fatalf("expected 401, got %d", resp.StatusCode)
	}
	body, _ := io.ReadAll(resp.Body)
	if !strings.Contains(string(body), "invalid token") {
		t.Errorf("body should mention invalid token, got %s", body)
	}
	var parsed map[string]string
	if err := json.Unmarshal(body, &parsed); err != nil {
		t.Errorf("body should be JSON: %v", err)
	}
}

