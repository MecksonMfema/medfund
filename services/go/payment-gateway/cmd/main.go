package main

import (
	"context"
	"log"
	"os"
	"os/signal"
	"strconv"
	"syscall"

	"github.com/gofiber/fiber/v2"
	"github.com/gofiber/fiber/v2/middleware/logger"
	"github.com/gofiber/fiber/v2/middleware/recover"

	"github.com/medfund/shared/httpserver"

	"github.com/medfund/payment-gateway/internal/events"
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

	// V075 — Kafka round-trip with finance-service:
	// consume medfund.payments.run.executed, process each item through
	// MockProvider (always succeeds), publish medfund.payments.gateway.settled
	// per item. finance-service's PaymentGatewaySettledConsumer flips the
	// referenced Payment.status → paid.
	brokers := envOr("KAFKA_BROKERS", "localhost:9092")
	subscriber := events.NewSubscriber(brokers, "payment-gateway")
	publisher := events.NewPublisher(brokers)
	defer publisher.Close()

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	go subscriber.Run(ctx, func(payload []byte) {
		evt, ok := events.ParseRunExecuted(payload)
		if !ok {
			return
		}
		log.Printf("[payment-gateway] processing run %s (tenant=%s, items=%d)",
			evt.RunID, evt.TenantID, len(evt.Items))
		for _, item := range evt.Items {
			amount, _ := strconv.ParseFloat(item.Amount, 64)
			req := payment.InitiateRequest{
				TenantID:       evt.TenantID,
				Amount:         amount,
				Currency:       item.CurrencyCode,
				Method:         "bank_transfer",
				Reference:      item.ItemID,
				Description:    "Payout for run " + evt.RunNumber,
				IdempotencyKey: item.ItemID,
				BankAccountID:  evt.SourceBankAccountID,
				Direction:      "outbound",
			}
			resp, err := provider.Initiate(req)
			if err != nil {
				log.Printf("[payment-gateway] provider Initiate failed for item %s: %v", item.ItemID, err)
				continue
			}
			txn := ledger.Record(req, resp, provider.Name(), "outbound")
			publisher.PublishSettled(ctx, events.SettledEvent{
				ItemID:        item.ItemID,
				PaymentID:     item.PaymentID,
				TenantID:      evt.TenantID,
				TransactionID: txn.ID,
				ProviderRef:   resp.ProviderRef,
				Status:        string(resp.Status),
				Amount:        item.Amount,
				CurrencyCode:  item.CurrencyCode,
			})
		}
	})

	// Graceful shutdown: cancel the subscriber context on SIGINT/SIGTERM
	// so the Kafka reader closes cleanly and offsets flush.
	go func() {
		sig := make(chan os.Signal, 1)
		signal.Notify(sig, syscall.SIGINT, syscall.SIGTERM)
		<-sig
		log.Printf("[payment-gateway] shutting down")
		cancel()
		_ = app.Shutdown()
	}()

	port := envOr("PORT", "3004")
	log.Printf("Payment Gateway starting on port %s", port)
	log.Fatal(app.Listen(":" + port))
}

func envOr(key, def string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return def
}
