package main

import (
	"context"
	"encoding/json"
	"log"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/gofiber/fiber/v2"
	"github.com/gofiber/fiber/v2/middleware/logger"
	"github.com/gofiber/fiber/v2/middleware/recover"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/medfund/notification-service/internal/config"
	"github.com/medfund/notification-service/internal/events"
	"github.com/medfund/notification-service/internal/handler"
	"github.com/medfund/notification-service/internal/invoice"
	"github.com/medfund/notification-service/internal/mail"
	"github.com/medfund/notification-service/internal/notification"
	"github.com/medfund/notification-service/internal/recipient"
	"github.com/medfund/notification-service/internal/storage"
	"github.com/medfund/notification-service/internal/template"
)

func main() {
	cfg := config.Load()

	app := fiber.New(fiber.Config{AppName: "MedFund Notification Service"})
	app.Use(recover.New())
	app.Use(logger.New())
	app.Get("/health", func(c *fiber.Ctx) error {
		return c.JSON(fiber.Map{"status": "ok", "service": "notification-service"})
	})

	// Legacy HTTP routes (catch-all for the older ProcessEvent stub).
	svc := notification.NewService()
	h := handler.New(svc)
	h.RegisterRoutes(app)

	// ── Invoice email pipeline ──────────────────────────────────────
	// InvoicePdfReady → resolve recipient → fetch PDF → SMTP send →
	// NotificationSent. Each dependency fails open so a missing one
	// disables only the invoice pipeline.
	pool, err := pgxpool.New(context.Background(), cfg.DatabaseURL)
	if err != nil {
		log.Printf("[notification] postgres unavailable: %v — recipient lookup disabled", err)
	}
	var resolver *recipient.Resolver
	if pool != nil {
		resolver = recipient.NewResolver(pool)
	}

	fetcher, err := storage.NewMinIOFetcher(cfg.MinIOEndpoint, cfg.MinIOAccessKey, cfg.MinIOSecretKey,
		cfg.MinIOUseSSL == "true")
	if err != nil {
		log.Printf("[notification] MinIO unavailable: %v — invoice attachments disabled", err)
	}

	sender := mail.Sender(mail.NewSMTPSender(cfg.SMTPHost, cfg.SMTPPort, cfg.SMTPUser, cfg.SMTPPassword))
	if cfg.SMTPHost == "" {
		log.Printf("[notification] SMTP_HOST empty — falling back to MockSender (logs only)")
		sender = &mail.MockSender{}
	}

	// Templates are tenant-overrideable via public.tenant_email_templates
	// (key INVOICE_ISSUED). Resolver gracefully degrades to the embedded
	// default when the row isn't present, so a tenant who hasn't
	// customised their template still gets a working email.
	templates := template.NewResolver(pool, invoice.DefaultSubject, invoice.DefaultHTMLBody())

	dispatcher, err := invoice.NewDispatcher(resolver, fetcher, sender, templates, cfg.SMTPFrom)
	if err != nil {
		log.Fatalf("invoice dispatcher: %v", err)
	}

	publisher := events.NewPublisher(cfg.KafkaBrokers)
	defer publisher.Close()

	ctx, cancel := context.WithCancel(context.Background())
	if cfg.KafkaBrokers != "" && fetcher != nil {
		go runInvoiceConsumer(ctx, cfg.KafkaBrokers, cfg.ConsumerGroupID, dispatcher, publisher)
	} else {
		log.Printf("[notification] invoice consumer disabled (kafka=%q minio=%v)",
			cfg.KafkaBrokers, fetcher != nil)
	}

	go func() {
		sig := make(chan os.Signal, 1)
		signal.Notify(sig, syscall.SIGINT, syscall.SIGTERM)
		<-sig
		cancel()
		_ = app.ShutdownWithTimeout(5 * time.Second)
	}()

	log.Printf("Notification Service starting on port %s", cfg.Port)
	log.Fatal(app.Listen(":" + cfg.Port))
}

// runInvoiceConsumer pulls InvoicePdfReady events, dispatches each,
// and publishes a NotificationSent with the actual outcome so audit /
// dashboards distinguish successful sends from quiet failures.
func runInvoiceConsumer(ctx context.Context, brokers, groupID string,
	dispatcher *invoice.Dispatcher, publisher *events.Publisher) {

	sub := events.NewSubscriber(brokers, "medfund.contributions.invoice-pdf-ready", groupID)
	sub.Run(ctx, func(payload []byte) {
		var e invoice.Event
		if err := json.Unmarshal(payload, &e); err != nil {
			log.Printf("[notification] drop malformed InvoicePdfReady: %v", err)
			return
		}
		res := dispatcher.Dispatch(ctx, e)

		sent := events.NotificationSent{
			InvoiceID:     e.InvoiceID,
			InvoiceNumber: e.InvoiceNumber,
			TenantID:      e.TenantID,
			Recipient:     res.Recipient,
			Channel:       "EMAIL",
			Status:        "SENT",
		}
		if !res.Ok {
			sent.Status = "FAILED"
			if res.Err != nil {
				sent.Error = res.Err.Error()
			}
		}
		publisher.Publish(ctx, sent)
	})
}
