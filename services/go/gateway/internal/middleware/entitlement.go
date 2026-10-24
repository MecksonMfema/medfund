package middleware

import (
	"strings"

	"github.com/gofiber/fiber/v2"
)

// EntitlementChecker is the read-side of the shared entitlements.Registry that
// the gate needs. Declared here as an interface so the gate can be unit-tested
// with a fake and so the middleware package does not import the registry's
// Kafka/HTTP machinery.
type EntitlementChecker interface {
	IsEntitled(tenantID, module string) bool
}

// EntitlementGate rejects module routes a tenant is not entitled to. This is
// the primary edge gate for the sellable-horizontal seam (the Java
// @RequiresEntitlement aspect is the in-service safety net, G4).
//
// Registered AFTER TenantResolver() so c.Locals("tenant_id") is populated. A
// request whose path matches no gated prefix passes straight through. The gate
// fails CLOSED: a missing tenant, or a tenant the registry does not know is
// entitled, gets 403 — never give a paid module away during uncertainty (G3).
//
// prefixToModule maps a route prefix to the ModuleKey it requires, e.g.
// "/api/v1/verifications" -> "KYC". The map is hand-maintained in main.go; a
// forgotten entry means the gateway won't gate that route — which is exactly
// why the in-service aspect exists as a backstop.
func EntitlementGate(reg EntitlementChecker, prefixToModule map[string]string) fiber.Handler {
	return func(c *fiber.Ctx) error {
		module, gated := matchPrefix(c.Path(), prefixToModule)
		if !gated {
			return c.Next()
		}
		tenantID, _ := c.Locals("tenant_id").(string)
		if tenantID == "" || !reg.IsEntitled(tenantID, module) {
			return c.Status(fiber.StatusForbidden).JSON(fiber.Map{
				"error": "module not enabled for tenant",
			})
		}
		return c.Next()
	}
}

// matchPrefix returns the module for the longest gated prefix that path starts
// with. Longest-match so that a more specific prefix (e.g. a biometric subpath
// requiring KYC_BIOMETRICS) can override a broader one without ordering
// surprises.
func matchPrefix(path string, prefixToModule map[string]string) (string, bool) {
	bestLen := -1
	bestModule := ""
	for prefix, module := range prefixToModule {
		if strings.HasPrefix(path, prefix) && len(prefix) > bestLen {
			bestLen = len(prefix)
			bestModule = module
		}
	}
	return bestModule, bestLen >= 0
}
