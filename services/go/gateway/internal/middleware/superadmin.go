package middleware

import (
	"github.com/gofiber/fiber/v2"
	"github.com/golang-jwt/jwt/v5"
)

// RequireSuperAdmin rejects requests whose caller is not a super_admin.
// Applied to the /api/v1/platform group so every cross-tenant aggregate
// endpoint (tenant-count, claims-stats, user-stats, member-growth,
// claims-distribution, stats, activity, health, and all analytics feeds)
// enforces the same permission boundary the Angular roleGuard applies
// client-side.
//
// Runs AFTER JWTMiddleware.Handler, which populates jwt_claims in Locals.
// If the JWT wasn't validated (Locals empty), the request is rejected — the
// only path with no claims should be pre-auth endpoints, and none of those
// are mounted under /api/v1/platform.
func RequireSuperAdmin() fiber.Handler {
	return func(c *fiber.Ctx) error {
		raw := c.Locals("jwt_claims")
		claims, ok := raw.(jwt.MapClaims)
		if !ok {
			return c.Status(fiber.StatusForbidden).JSON(fiber.Map{
				"error": "super admin access required",
			})
		}
		if !hasSuperAdminRole(claims) {
			return c.Status(fiber.StatusForbidden).JSON(fiber.Map{
				"error": "super admin access required",
			})
		}
		return c.Next()
	}
}
