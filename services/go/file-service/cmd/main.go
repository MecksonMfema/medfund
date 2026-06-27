package main

import (
	"context"
	"fmt"
	"log"
	"os"
	"os/signal"
	"syscall"

	"github.com/gofiber/fiber/v2"
	"github.com/gofiber/fiber/v2/middleware/logger"
	"github.com/gofiber/fiber/v2/middleware/recover"

	"github.com/medfund/file-service/internal/config"
	"github.com/medfund/file-service/internal/events"
	exportpkg "github.com/medfund/file-service/internal/export"
	"github.com/medfund/file-service/internal/handler"
	"github.com/medfund/file-service/internal/invoice"
	"github.com/medfund/file-service/internal/storage"
)

func main() {
	cfg := config.Load()

	app := fiber.New(fiber.Config{AppName: "MedFund File Service"})
	app.Use(recover.New())
	app.Use(logger.New())
	app.Get("/health", func(c *fiber.Ctx) error {
		return c.JSON(fiber.Map{"status": "ok", "service": "file-service"})
	})

	// Legacy upload/download/export routes — keep working off MockStorage
	// for now. The MinIO-backed invoice pipeline lives alongside.
	mockStore := storage.NewMockStorage()
	exportSvc := exportpkg.NewService()
	h := handler.New(mockStore, exportSvc)
	h.RegisterRoutes(app)

	// ── Invoice PDF pipeline ────────────────────────────────────────
	// Subscribe to InvoiceIssued, render the PDF, upload to MinIO,
	// emit InvoicePdfReady. Each side fails open: a missing MinIO or
	// Kafka leaves the rest of the service usable.
	minio, err := storage.NewMinIOStore(cfg.MinIOEndpoint, cfg.MinIOAccessKey, cfg.MinIOSecretKey,
		cfg.MinIOBucket, cfg.MinIOUseSSL == "true")
	if err != nil {
		log.Printf("[file-service] MinIO unavailable: %v — invoice PDFs disabled", err)
	}

	pdf := invoice.PdfGenerator(invoice.StubPdfGenerator{})
	if cfg.WkhtmltopdfBin != "" {
		pdf = invoice.NewWkhtmltopdfGenerator(cfg.WkhtmltopdfBin)
	}
	renderer, err := invoice.NewRenderer(pdf)
	if err != nil {
		log.Fatalf("invoice renderer: %v", err)
	}

	publisher := events.NewPublisher(cfg.KafkaBrokers)
	defer publisher.Close()

	ctx, cancel := context.WithCancel(context.Background())
	if cfg.KafkaBrokers != "" && minio != nil {
		go runInvoiceConsumer(ctx, cfg.KafkaBrokers, renderer, minio, publisher)
	} else {
		log.Printf("[file-service] invoice consumer disabled (kafka=%q minio=%v)", cfg.KafkaBrokers, minio != nil)
	}

	// Graceful shutdown — stop the Kafka consumer before the HTTP server
	// so in-flight messages either finish or roll back to uncommitted.
	go func() {
		sig := make(chan os.Signal, 1)
		signal.Notify(sig, syscall.SIGINT, syscall.SIGTERM)
		<-sig
		cancel()
		_ = app.ShutdownWithTimeout(5 * 1000)
	}()

	port := cfg.Port
	if port == "" {
		port = "3003"
	}
	log.Printf("File Service starting on port %s", port)
	log.Fatal(app.Listen(":" + port))
}

// runInvoiceConsumer is the per-event handler loop: parse → render →
// upload → publish. Logged errors only — never panics out of the
// consumer, so a single bad event can't take down the rest of the run.
func runInvoiceConsumer(ctx context.Context, brokers string, renderer *invoice.Renderer,
	store *storage.MinIOStore, publisher *events.Publisher) {

	sub := events.NewSubscriber(brokers, "medfund.contributions.invoice-issued", "file-service")
	sub.Run(ctx, func(payload []byte) {
		evt, ok := events.ParseInvoiceIssued(payload)
		if !ok {
			return
		}
		log.Printf("[file-service] rendering invoice %s (tenant=%s)", evt.InvoiceNumber, evt.TenantID)

		pdf, err := renderer.Render(ctx, invoice.Payload{
			InvoiceID:     evt.InvoiceID,
			InvoiceNumber: evt.InvoiceNumber,
			TenantID:      evt.TenantID,
			GroupID:       evt.GroupID,
			MemberID:      evt.MemberID,
			CurrencyCode:  evt.CurrencyCode,
			TotalAmount:   evt.TotalAmount,
			PeriodStart:   evt.PeriodStart,
			PeriodEnd:     evt.PeriodEnd,
			DueDate:       evt.DueDate,
		})
		if err != nil {
			log.Printf("[file-service] render %s failed: %v", evt.InvoiceNumber, err)
			return
		}

		key := fmt.Sprintf("invoices/%s/%s.pdf", evt.TenantID, evt.InvoiceNumber)
		if _, err := store.PutObject(ctx, key, pdf, "application/pdf"); err != nil {
			log.Printf("[file-service] upload %s failed: %v", key, err)
			return
		}

		publisher.Publish(ctx, events.InvoicePdfReady{
			InvoiceID:     evt.InvoiceID,
			InvoiceNumber: evt.InvoiceNumber,
			TenantID:      evt.TenantID,
			GroupID:       evt.GroupID,
			MemberID:      evt.MemberID,
			CurrencyCode:  evt.CurrencyCode,
			TotalAmount:   evt.TotalAmount,
			PeriodStart:   evt.PeriodStart,
			PeriodEnd:     evt.PeriodEnd,
			DueDate:       evt.DueDate,
			PdfBucket:     store.Bucket(),
			PdfObjectKey:  key,
		})
		log.Printf("[file-service] published InvoicePdfReady for %s (%d bytes at %s)",
			evt.InvoiceNumber, len(pdf), key)
	})
}
