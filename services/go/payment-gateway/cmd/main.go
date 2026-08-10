package main

import (
	"log"
	"os"

	"github.com/gofiber/fiber/v2"
	"github.com/gofiber/fiber/v2/middleware/logger"
	"github.com/gofiber/fiber/v2/middleware/recover"

	"github.com/medfund/shared/httpserver"

	"github.com/medfund/payment-gateway/internal/handler"
	"github.com/medfund/payment-gateway/internal/payment"
)

func main() {
	app := httpserver.New(httpserver.Options{AppName: "MedFund Payment Gateway"})

	app.Use(recover.New())
	app.Use(logger.New())

	app.Get("/health", func(c *fiber.Ctx) error {
		return c.JSON(fiber.Map{"status": "ok", "service": "payment-gateway"})
	})

	provider := payment.NewMockProvider()
	ledger := payment.NewLedger()
	h := handler.New(provider, ledger)
	h.RegisterRoutes(app)

	port := os.Getenv("PORT")
	if port == "" {
		port = "3004"
	}

	log.Printf("Payment Gateway starting on port %s", port)
	log.Fatal(app.Listen(":" + port))
}
