package main

import (
	"log"

	"github.com/gofiber/fiber/v2"
	"github.com/gofiber/fiber/v2/middleware/cors"
	"github.com/gofiber/fiber/v2/middleware/logger"
	"github.com/gofiber/fiber/v2/middleware/recover"

	"github.com/medfund/gateway/internal/config"
	"github.com/medfund/gateway/internal/events"
	"github.com/medfund/gateway/internal/middleware"
	"github.com/medfund/gateway/internal/routes"
)

func main() {
	cfg := config.Load()

	app := fiber.New(fiber.Config{
		AppName:      "MedFund API Gateway",
		ServerHeader: "MedFund",
	})

	// Global middleware
	app.Use(recover.New())
	app.Use(logger.New(logger.Config{
		Format: `{"time":"${time}","status":${status},"method":"${method}","path":"${path}","latency":"${latency}","ip":"${ip}"}` + "\n",
	}))
	app.Use(cors.New(cors.Config{
		AllowOrigins:     cfg.WebOrigins,
		AllowMethods:     "GET,POST,PUT,PATCH,DELETE,OPTIONS",
		AllowHeaders:     "Origin,Content-Type,Accept,Authorization,X-Tenant-ID",
		ExposeHeaders:    "X-Cache,X-Tenant-ID",
		AllowCredentials: true,
		MaxAge:           600,
	}))

	// Kafka publisher for security events (login, logout, failed auth).
	// If Kafka is unreachable the publisher is still created — writes fail
	// silently so auth is never blocked by Kafka availability.
	publisher := events.NewPublisher(cfg.KafkaBrokers)
	defer publisher.Close()

	// JWT middleware — receives the publisher so it can emit security events
	jwtMw := middleware.NewJWTMiddleware(cfg.KeycloakURL, cfg.KeycloakRealm, publisher)

	// Health check and auth endpoints — no JWT required
	app.Get("/health", func(c *fiber.Ctx) error {
		return c.JSON(fiber.Map{"status": "ok", "service": "gateway"})
	})
	app.Post("/auth/session", jwtMw.SessionHandler())
	app.Post("/auth/logout", jwtMw.LogoutHandler())

	// JWT validation for all other routes
	app.Use(jwtMw.Handler())

	// Tenant resolution
	app.Use(middleware.TenantResolver())

	// Rate limiting
	app.Use(middleware.NewRateLimiter(cfg.RateLimitPerMin))

	// Service routes
	routes.Register(app, cfg)

	log.Printf("MedFund API Gateway starting on port %s", cfg.Port)
	log.Fatal(app.Listen(":" + cfg.Port))
}
