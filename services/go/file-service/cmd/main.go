package main

import (
	"context"
	"fmt"
	"io"
	"log"
	"os"
	"os/signal"
	"syscall"

	"github.com/gofiber/fiber/v2"
	"github.com/gofiber/fiber/v2/middleware/logger"
	"github.com/gofiber/fiber/v2/middleware/recover"

	"github.com/medfund/file-service/internal/config"
	"github.com/medfund/file-service/internal/contributions"
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

	// GET /invoice-pdf?bucket=...&key=... — contributions-service
	// delegates here (plan §4b) so it doesn't have to dual-wire MinIO
	// credentials. 404 when MinIO is down or the object isn't found.
	app.Get("/invoice-pdf", func(c *fiber.Ctx) error {
		if minio == nil {
			return c.Status(fiber.StatusServiceUnavailable).
				JSON(fiber.Map{"detail": "MinIO unavailable"})
		}
		key := c.Query("key")
		if key == "" {
			return c.Status(fiber.StatusBadRequest).
				JSON(fiber.Map{"detail": "key query param is required"})
		}
		bucket := c.Query("bucket") // empty falls back to the configured default in GetObject
		obj, _, contentType, err := minio.GetObject(c.UserContext(), bucket, key)
		if err != nil {
			log.Printf("[file-service] /invoice-pdf miss bucket=%q key=%q: %v", bucket, key, err)
			return c.Status(fiber.StatusNotFound).
				JSON(fiber.Map{"detail": "object not found"})
		}
		// Read the whole object into memory then close — Fiber's SendStream
		// returns before the body is fully flushed in some setups and the
		// MinIO stream gets closed prematurely by the defer, causing the
		// downstream WebClient to see "Connection prematurely closed".
		// PDFs are small (tens of KB), so the buffer is harmless.
		body, err := io.ReadAll(obj)
		_ = obj.Close()
		if err != nil {
			log.Printf("[file-service] /invoice-pdf read failed bucket=%q key=%q: %v", bucket, key, err)
			return c.Status(fiber.StatusInternalServerError).
				JSON(fiber.Map{"detail": "object read failed"})
		}
		if contentType == "" {
			contentType = "application/pdf"
		}
		c.Set("Content-Type", contentType)
		return c.Send(body)
	})

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

	contribClient := contributions.New(cfg.ContributionsBaseURL)
	log.Printf("[file-service] contributions client base=%s", cfg.ContributionsBaseURL)

	// POST /invoice-pdf/render?invoice=UUID&tenant=UUID — on-demand
	// re-render for an existing invoice. Same code path as the Kafka
	// consumer but callable from the terminal so operators can refresh
	// a PDF after a template change without republishing the event.
	app.Post("/invoice-pdf/render", func(c *fiber.Ctx) error {
		invoiceID := c.Query("invoice")
		tenantID := c.Query("tenant")
		if invoiceID == "" || tenantID == "" {
			return c.Status(fiber.StatusBadRequest).
				JSON(fiber.Map{"detail": "invoice and tenant query params are required"})
		}
		if minio == nil {
			return c.Status(fiber.StatusServiceUnavailable).
				JSON(fiber.Map{"detail": "MinIO unavailable"})
		}
		rd, err := contribClient.FetchRenderPayload(c.UserContext(), tenantID, invoiceID)
		if err != nil {
			log.Printf("[file-service] rerender fetch payload failed: %v", err)
			return c.Status(fiber.StatusBadGateway).
				JSON(fiber.Map{"detail": err.Error()})
		}
		p := invoice.Payload{
			InvoiceID:     rd.Invoice.ID,
			InvoiceNumber: rd.Invoice.InvoiceNumber,
			TenantID:      tenantID,
			GroupID:       rd.Invoice.GroupID,
			MemberID:      rd.Invoice.MemberID,
			CurrencyCode:  rd.Invoice.CurrencyCode,
			TotalAmount:   rd.Invoice.TotalAmount.String(),
			PeriodStart:   rd.Invoice.PeriodStart,
			PeriodEnd:     rd.Invoice.PeriodEnd,
			DueDate:       rd.Invoice.DueDate,
			// Use the statement header's targetName so the recipient
			// label matches the Angular page — falls back to the
			// truncated-id label inside the renderer if empty.
			RecipientLabel: rd.Statement.Header.TargetName,
			RenderData:     rd,
		}
		pdf, err := renderer.Render(c.UserContext(), p)
		if err != nil {
			log.Printf("[file-service] rerender failed: %v", err)
			return c.Status(fiber.StatusInternalServerError).
				JSON(fiber.Map{"detail": err.Error()})
		}
		key := fmt.Sprintf("invoices/%s/%s.pdf", tenantID, rd.Invoice.InvoiceNumber)
		if _, err := minio.PutObject(c.UserContext(), key, pdf, "application/pdf"); err != nil {
			log.Printf("[file-service] rerender upload failed: %v", err)
			return c.Status(fiber.StatusInternalServerError).
				JSON(fiber.Map{"detail": err.Error()})
		}
		// Publish InvoicePdfReady so contributions-service upserts
		// the pointer if the invoice is new-to-us. Idempotent on the
		// contributions side.
		publisher.Publish(c.UserContext(), events.InvoicePdfReady{
			InvoiceID:     rd.Invoice.ID,
			InvoiceNumber: rd.Invoice.InvoiceNumber,
			TenantID:      tenantID,
			GroupID:       rd.Invoice.GroupID,
			MemberID:      rd.Invoice.MemberID,
			CurrencyCode:  rd.Invoice.CurrencyCode,
			TotalAmount:   rd.Invoice.TotalAmount.String(),
			PeriodStart:   rd.Invoice.PeriodStart,
			PeriodEnd:     rd.Invoice.PeriodEnd,
			DueDate:       rd.Invoice.DueDate,
			PdfBucket:     minio.Bucket(),
			PdfObjectKey:  key,
		})
		log.Printf("[file-service] rerender OK invoice=%s (%d bytes at %s)",
			rd.Invoice.InvoiceNumber, len(pdf), key)
		return c.JSON(fiber.Map{
			"invoiceNumber": rd.Invoice.InvoiceNumber,
			"bucket":        minio.Bucket(),
			"objectKey":     key,
			"bytes":         len(pdf),
		})
	})

	ctx, cancel := context.WithCancel(context.Background())
	if cfg.KafkaBrokers != "" && minio != nil {
		go runInvoiceConsumer(ctx, cfg.KafkaBrokers, renderer, minio, publisher, contribClient)
		// Mirror consumer: react to billing revoke by removing the
		// orphaned PDF blob from MinIO. Decoupled from the renderer
		// loop so a slow render doesn't block the cleanup pipeline.
		go runInvoicePdfDeletedConsumer(ctx, cfg.KafkaBrokers, minio)
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

// runInvoiceConsumer is the per-event handler loop: parse → fetch
// render data → render → upload → publish. Logged errors only — never
// panics out of the consumer, so a single bad event can't take down
// the rest of the run.
func runInvoiceConsumer(ctx context.Context, brokers string, renderer *invoice.Renderer,
	store *storage.MinIOStore, publisher *events.Publisher, contribClient *contributions.Client) {

	sub := events.NewSubscriber(brokers, "medfund.contributions.invoice-issued", "file-service")
	sub.Run(ctx, func(payload []byte) {
		evt, ok := events.ParseInvoiceIssued(payload)
		if !ok {
			return
		}
		log.Printf("[file-service] rendering invoice %s (tenant=%s)", evt.InvoiceNumber, evt.TenantID)

		// Pull the full statement + per-contribution rows so the PDF
		// mirrors the Angular statement page. Falls back to the sparse
		// event data on failure so we still ship *some* PDF — the
		// legacy summary layout — rather than nothing.
		renderData, err := contribClient.FetchRenderPayload(ctx, evt.TenantID, evt.InvoiceID)
		if err != nil {
			log.Printf("[file-service] render payload fetch failed invoice=%s: %v — falling back to summary layout",
				evt.InvoiceNumber, err)
			renderData = nil
		}

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
			// Real recipient name (resolved by contributions-service from
			// groups/members before publish). Renderer falls back to a
			// truncated-UUID label only when this is empty.
			RecipientLabel: evt.RecipientName,
			RenderData:     renderData,
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

// runInvoicePdfDeletedConsumer reacts to a billing revoke from
// contributions-service by removing the orphaned PDF blob from MinIO.
// The invoice_pdfs pointer row is already gone via DB cascade by the
// time this fires — we just need to clean up the object store side.
// Fire-and-forget per event: log and commit on success or failure so
// at-least-once redelivery never loops on a broken blob.
func runInvoicePdfDeletedConsumer(ctx context.Context, brokers string, store *storage.MinIOStore) {
	sub := events.NewSubscriber(brokers, "medfund.contributions.invoice-pdf-deleted", "file-service-pdf-cleanup")
	sub.Run(ctx, func(payload []byte) {
		evt, ok := events.ParseInvoicePdfDeleted(payload)
		if !ok {
			return
		}
		if err := store.RemoveObject(ctx, evt.Bucket, evt.ObjectKey); err != nil {
			log.Printf("[file-service] remove %s/%s failed: %v", evt.Bucket, evt.ObjectKey, err)
			return
		}
		log.Printf("[file-service] removed invoice PDF blob tenant=%s invoice=%s key=%s",
			evt.TenantID, evt.InvoiceID, evt.ObjectKey)
	})
}
