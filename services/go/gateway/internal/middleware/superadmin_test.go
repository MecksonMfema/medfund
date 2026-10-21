package middleware

import (
	"net/http/httptest"
	"testing"

	"github.com/gofiber/fiber/v2"
	"github.com/golang-jwt/jwt/v5"
)

// callWithClaims exercises RequireSuperAdmin() by mounting it on a Fiber app
// with a probe endpoint that echoes 200. The claims are injected into Locals
// via a preceding handler so the tests don't need a real JWT to validate.
func callWithClaims(t *testing.T, claims jwt.MapClaims) int {
	t.Helper()
	app := fiber.New()
	app.Use(func(c *fiber.Ctx) error {
		if claims != nil {
			c.Locals("jwt_claims", claims)
		}
		return c.Next()
	})
	app.Get("/probe", RequireSuperAdmin(), func(c *fiber.Ctx) error {
		return c.SendStatus(fiber.StatusOK)
	})
	resp, err := app.Test(httptest.NewRequest("GET", "/probe", nil))
	if err != nil {
		t.Fatalf("app.Test: %v", err)
	}
	return resp.StatusCode
}

func TestRequireSuperAdmin_allowsSuperAdmin(t *testing.T) {
	claims := jwt.MapClaims{
		"sub": "user-1",
		"realm_access": map[string]interface{}{
			"roles": []interface{}{"user", "super_admin"},
		},
	}
	if got := callWithClaims(t, claims); got != fiber.StatusOK {
		t.Fatalf("super_admin should have been allowed, got %d", got)
	}
}

func TestRequireSuperAdmin_rejectsNonSuperAdmin(t *testing.T) {
	claims := jwt.MapClaims{
		"sub": "user-1",
		"realm_access": map[string]interface{}{
			"roles": []interface{}{"user", "tenant_admin"},
		},
	}
	if got := callWithClaims(t, claims); got != fiber.StatusForbidden {
		t.Fatalf("non-super_admin should have been 403, got %d", got)
	}
}

func TestRequireSuperAdmin_rejectsMissingClaims(t *testing.T) {
	if got := callWithClaims(t, nil); got != fiber.StatusForbidden {
		t.Fatalf("missing claims should have been 403, got %d", got)
	}
}

func TestRequireSuperAdmin_rejectsMissingRealmAccess(t *testing.T) {
	claims := jwt.MapClaims{"sub": "user-1"}
	if got := callWithClaims(t, claims); got != fiber.StatusForbidden {
		t.Fatalf("missing realm_access should have been 403, got %d", got)
	}
}
