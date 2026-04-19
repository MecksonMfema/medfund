package middleware

import (
	"strings"

	"github.com/gofiber/fiber/v2"
)

// platformPaths are platform-level endpoints that do not belong to any single
// tenant and must be accessible without a tenant context (super admin operations).
var platformPaths = []string{
	"/health",
	"/swagger",
	"/auth/",
	"/api/v1/tenants",
	"/api/v1/plans",
	"/api/v1/staff-users",
	"/api/v1/platform",
	"/api/v1/roles",
}

// TenantResolver returns a Fiber middleware that resolves the current tenant from
// multiple sources in priority order:
//  1. JWT claims (set by JWTMiddleware)
//  2. X-Tenant-ID header (service-to-service or super admin explicit context)
//  3. Subdomain extraction (e.g., zmmas.api.medfund.healthcare)
//
// Platform-level endpoints (tenants, staff-users, platform, roles) are allowed
// without a tenant context.
func TenantResolver() fiber.Handler {
	return func(c *fiber.Ctx) error {
		path := c.Path()

		// Allow platform-level paths without tenant context
		for _, prefix := range platformPaths {
			if strings.HasPrefix(path, prefix) {
				return c.Next()
			}
		}

		tenantID := ""

		// 1. Check if JWT middleware already set it
		if id, ok := c.Locals("tenant_id").(string); ok && id != "" {
			tenantID = id
		}

		// 2. Check X-Tenant-ID header (service-to-service or super admin explicit context)
		if tenantID == "" {
			tenantID = c.Get("X-Tenant-ID")
		}

		// 3. Extract from subdomain (e.g., zmmas.api.medfund.healthcare)
		if tenantID == "" {
			host := c.Hostname()
			parts := strings.Split(host, ".")
			if len(parts) > 2 {
				tenantID = parts[0]
			}
		}

		if tenantID == "" {
			return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{
				"error": "tenant could not be resolved",
			})
		}

		c.Locals("tenant_id", tenantID)
		c.Request().Header.Set("X-Tenant-ID", tenantID)
		return c.Next()
	}
}
