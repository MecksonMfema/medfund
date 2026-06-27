package main

import (
	"context"
	"encoding/json"
	"fmt"
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
	"github.com/medfund/notification-service/internal/retry"
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

	// Retry scheduler reuses the same Dispatcher for re-sends. Backoff
	// defaults to retry.DefaultBackoff ({30s, 2m, 5m, 15m} ≈ 22 min);
	// concurrency cap defends against a SMTP outage spawning thousands
	// of waiting goroutines when an entire billing run fails.
	retrySched := retry.New(nil, 50)

	ctx, cancel := context.WithCancel(context.Background())
	if cfg.KafkaBrokers != "" && fetcher != nil {
		go runInvoiceConsumer(ctx, cfg.KafkaBrokers, cfg.ConsumerGroupID,
			dispatcher, publisher, retrySched)
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
//
// On an initial failure, the event is handed off to retrySched for
// background re-dispatch with backoff. Re-dispatches do not publish
// their own per-attempt outcome — only the terminal result (SENT or
// DEAD_LETTERED) lands in the audit feed, so a worst-case failed
// invoice produces exactly two rows: the initial FAILED and a final
// DEAD_LETTERED. Per-attempt detail lives in notification-service logs.
func runInvoiceConsumer(ctx context.Context, brokers, groupID string,
	dispatcher *invoice.Dispatcher, publisher *events.Publisher,
	retrySched *retry.Scheduler) {

	sub := events.NewSubscriber(brokers, "medfund.contributions.invoice-pdf-ready", groupID)
	sub.Run(ctx, func(payload []byte) {
		var e invoice.Event
		if err := json.Unmarshal(payload, &e); err != nil {
			log.Printf("[notification] drop malformed InvoicePdfReady: %v", err)
			return
		}
		res := dispatcher.Dispatch(ctx, e)
		publisher.Publish(ctx, buildNotificationSent(e, res, "SENT", "FAILED"))

		if res.Ok {
			return
		}
		// Hand off to the retry scheduler. The retry chain re-runs
		// Dispatch silently; the onFinal callback publishes the
		// terminal outcome — SENT if a retry succeeded, DEAD_LETTERED
		// if every retry failed and we're giving up. No further
		// retries fire after the dead-letter, matching the explicit
		// "do not continue sending" requirement.
		scheduleRetry(ctx, retrySched, dispatcher, publisher, e)
	})
}

// scheduleRetry wires one event into retrySched and converts the
// terminal Outcome into the final NotificationSent on the audit feed.
func scheduleRetry(ctx context.Context, sched *retry.Scheduler,
	dispatcher *invoice.Dispatcher, publisher *events.Publisher, e invoice.Event) {

	var lastResult invoice.Result
	sched.RunAsync(ctx, e.InvoiceNumber,
		func(ctx context.Context, attempt int) error {
			r := dispatcher.Dispatch(ctx, e)
			lastResult = r
			if r.Ok {
				return nil
			}
			if r.Err != nil {
				return r.Err
			}
			// Dispatch can fail without a typed error (e.g. resolver
			// nil). Surface a synthetic one so the scheduler's retry
			// loop treats it as a failure rather than success.
			return errDispatchFailed
		},
		func(o retry.Outcome) {
			if o.Ok {
				publisher.Publish(ctx, buildNotificationSent(e, lastResult, "SENT", "SENT"))
				return
			}
			// Exhausted (or context-cancelled). Publish DEAD_LETTERED
			// with a synthetic error string that includes the attempt
			// count so the audit row is self-explanatory.
			cause := "unknown"
			if o.Err != nil {
				cause = o.Err.Error()
			}
			publisher.Publish(ctx, events.NotificationSent{
				InvoiceID:     e.InvoiceID,
				InvoiceNumber: e.InvoiceNumber,
				TenantID:      e.TenantID,
				Recipient:     lastResult.Recipient,
				Channel:       "EMAIL",
				Status:        "DEAD_LETTERED",
				Error:         fmt.Sprintf("exhausted %d retries: %s", o.Attempts, cause),
			})
		})
}

// buildNotificationSent translates a Dispatcher.Result into the wire
// shape. successStatus is the status applied when Result.Ok; failure
// applies when not — separate params so the initial-dispatch path
// can use SENT/FAILED while the retry success path stays SENT.
func buildNotificationSent(e invoice.Event, r invoice.Result,
	successStatus, failureStatus string) events.NotificationSent {

	sent := events.NotificationSent{
		InvoiceID:     e.InvoiceID,
		InvoiceNumber: e.InvoiceNumber,
		TenantID:      e.TenantID,
		Recipient:     r.Recipient,
		Channel:       "EMAIL",
		Status:        successStatus,
	}
	if !r.Ok {
		sent.Status = failureStatus
		if r.Err != nil {
			sent.Error = r.Err.Error()
		}
	}
	return sent
}

// Sentinel — only used when Dispatch returns Ok=false with a nil Err
// (a defensive belt-and-braces for a future Dispatch refactor).
var errDispatchFailed = fmt.Errorf("dispatch returned failure with no error")
