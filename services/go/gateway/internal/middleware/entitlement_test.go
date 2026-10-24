package middleware

import (
	"net/http/httptest"
	"testing"

	"github.com/gofiber/fiber/v2"
	"github.com/medfund/shared/entitlements"
)

// fakeChecker is a hand-seeded EntitlementChecker for the gate tests.
type fakeChecker struct {
	entitled map[string]bool // keyed by tenantID|module
}

func (f fakeChecker) IsEntitled(tenantID, module string) bool {
	return f.entitled[tenantID+"|"+module]
}

// callGate mounts EntitlementGate with the given registry + prefix map,
// injecting tenant_id into Locals the way TenantResolver would, and returns the
// status the probe endpoint sees.
func callGate(t *testing.T, reg EntitlementChecker, prefixMap map[string]string, tenantID, path string) int {
	t.Helper()
	app := fiber.New()
	app.Use(func(c *fiber.Ctx) error {
		if tenantID != "" {
			c.Locals("tenant_id", tenantID)
		}
		return c.Next()
	})
	app.Use(EntitlementGate(reg, prefixMap))
	app.All(path, func(c *fiber.Ctx) error {
		return c.SendStatus(fiber.StatusOK)
	})
	resp, err := app.Test(httptest.NewRequest("GET", path, nil))
	if err != nil {
		t.Fatalf("app.Test: %v", err)
	}
	return resp.StatusCode
}

var kycPrefixMap = map[string]string{"/api/v1/verifications": "KYC"}

func TestEntitlementGate_allowsEntitledTenant(t *testing.T) {
	reg := fakeChecker{entitled: map[string]bool{"tenant-1|KYC": true}}
	if got := callGate(t, reg, kycPrefixMap, "tenant-1", "/api/v1/verifications"); got != fiber.StatusOK {
		t.Fatalf("entitled tenant should pass the gate, got %d", got)
	}
}

func TestEntitlementGate_rejectsUnentitledTenant(t *testing.T) {
	reg := fakeChecker{entitled: map[string]bool{}} // tenant-2 has no KYC row
	if got := callGate(t, reg, kycPrefixMap, "tenant-2", "/api/v1/verifications"); got != fiber.StatusForbidden {
		t.Fatalf("un-entitled tenant should be 403, got %d", got)
	}
}

func TestEntitlementGate_rejectsMissingTenant(t *testing.T) {
	reg := fakeChecker{entitled: map[string]bool{"|KYC": true}}
	if got := callGate(t, reg, kycPrefixMap, "", "/api/v1/verifications"); got != fiber.StatusForbidden {
		t.Fatalf("a request with no tenant on a gated route must be 403, got %d", got)
	}
}

func TestEntitlementGate_passesUngatedRoute(t *testing.T) {
	reg := fakeChecker{entitled: map[string]bool{}}
	// A non-module route is not in the prefix map — it must pass untouched even
	// with no tenant and an empty registry.
	if got := callGate(t, reg, kycPrefixMap, "", "/api/v1/claims"); got != fiber.StatusOK {
		t.Fatalf("un-gated route should pass, got %d", got)
	}
}

// G3: a cold/empty real registry (bootstrap failed, nothing cached) must 403,
// not panic. Uses the real *entitlements.Registry type, not the fake.
func TestEntitlementGate_coldRegistryFailsClosed(t *testing.T) {
	reg := entitlements.New("http://unused.invalid") // never bootstrapped
	if got := callGate(t, reg, kycPrefixMap, "tenant-1", "/api/v1/verifications"); got != fiber.StatusForbidden {
		t.Fatalf("a cold registry must fail closed with 403, got %d", got)
	}
}

func TestMatchPrefix_longestWins(t *testing.T) {
	m := map[string]string{
		"/api/v1/verifications":           "KYC",
		"/api/v1/verifications/biometric": "KYC_BIOMETRICS",
	}
	if mod, ok := matchPrefix("/api/v1/verifications/biometric/match", m); !ok || mod != "KYC_BIOMETRICS" {
		t.Fatalf("expected KYC_BIOMETRICS (longest match), got %q ok=%v", mod, ok)
	}
	if mod, ok := matchPrefix("/api/v1/verifications/123", m); !ok || mod != "KYC" {
		t.Fatalf("expected KYC, got %q ok=%v", mod, ok)
	}
	if _, ok := matchPrefix("/api/v1/claims", m); ok {
		t.Fatal("un-gated path must not match")
	}
}
