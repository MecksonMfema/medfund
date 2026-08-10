// Package httpserver builds Fiber v2 servers with tuned defaults for services
// that sit behind the InsureFlow Keycloak-authenticated gateway.
//
// Keycloak access tokens (with tenant realm-roles + resource-access claims)
// plus KEYCLOAK_IDENTITY/SESSION cookies routinely exceed Fiber's default
// 4 KiB ReadBufferSize, which triggers a silent 431 inside fasthttp's request
// parser — before any middleware runs. This package widens the buffer to
// 32 KiB and installs an ErrorHandler that surfaces such rejections in
// application logs.
package httpserver

import (
	"log"

	"github.com/gofiber/fiber/v2"
)

// Options configures the returned Fiber app. AppName is required.
type Options struct {
	AppName      string
	ServerHeader string // optional; set to "MedFund" on the gateway
}

// New returns a Fiber app tuned for services behind the InsureFlow gateway.
func New(opts Options) *fiber.App {
	return fiber.New(fiber.Config{
		AppName:         opts.AppName,
		ServerHeader:    opts.ServerHeader,
		ReadBufferSize:  32 * 1024,
		WriteBufferSize: 32 * 1024,
		ErrorHandler:    errorHandler,
	})
}

// errorHandler surfaces pre-handler errors — notably 431 Request Header Fields
// Too Large — in application logs. Fiber invokes this for errors returned from
// handlers AND for fasthttp-level parser errors it can propagate.
func errorHandler(c *fiber.Ctx, err error) error {
	code := fiber.StatusInternalServerError
	if e, ok := err.(*fiber.Error); ok {
		code = e.Code
	}
	if code == fiber.StatusRequestHeaderFieldsTooLarge {
		log.Printf("[httpserver] 431 headers-too-large path=%q header_bytes=%d client=%s",
			c.Path(), len(c.Request().Header.Header()), c.IP())
	}
	c.Set(fiber.HeaderContentType, fiber.MIMETextPlainCharsetUTF8)
	return c.Status(code).SendString(err.Error())
}
