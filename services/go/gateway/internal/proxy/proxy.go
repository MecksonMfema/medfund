package proxy

import (
	"github.com/gofiber/fiber/v2"
	"github.com/valyala/fasthttp"
)

// Route defines a mapping from URL prefix to backend service.
type Route struct {
	Prefix  string
	Backend string
}

// Handler creates a reverse proxy handler that forwards requests to the given
// backend URL. It preserves the original request URI, forwards tenant and auth
// headers, and copies the full response back to the client.
func Handler(backendURL string) fiber.Handler {
	return func(c *fiber.Ctx) error {
		req := fasthttp.AcquireRequest()
		resp := fasthttp.AcquireResponse()
		defer fasthttp.ReleaseRequest(req)
		defer fasthttp.ReleaseResponse(resp)

		// Copy the original request
		c.Request().CopyTo(req)

		// Set the backend URL
		req.SetRequestURI(backendURL + string(c.Request().RequestURI()))
		req.Header.SetHost("") // Let fasthttp set the host from URI

		// Forward tenant header
		if tenantID := c.Get("X-Tenant-ID"); tenantID != "" {
			req.Header.Set("X-Tenant-ID", tenantID)
		}

		// Inject Authorization header from validated JWT (cookie auth sends no header)
		if token, ok := c.Locals("jwt_token").(string); ok && token != "" {
			req.Header.Set("Authorization", "Bearer "+token)
		} else if auth := c.Get("Authorization"); auth != "" {
			req.Header.Set("Authorization", auth)
		}

		// Execute the request
		if err := fasthttp.Do(req, resp); err != nil {
			return c.Status(fiber.StatusBadGateway).JSON(fiber.Map{
				"error":   "upstream service unavailable",
				"details": err.Error(),
			})
		}

		// Copy response back
		c.Status(resp.StatusCode())
		resp.Header.VisitAll(func(key, value []byte) {
			c.Set(string(key), string(value))
		})
		return c.Send(resp.Body())
	}
}
