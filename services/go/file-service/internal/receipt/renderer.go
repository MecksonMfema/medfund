// Package receipt renders payment-receipt PDFs. Mirrors the shape of
// invoice.Renderer so the two pipelines are symmetrical: consume a
// domain event (TransactionRecorded), stamp the template, run the
// same wkhtmltopdf-backed PdfGenerator, upload to MinIO.
package receipt

import (
	"bytes"
	"context"
	_ "embed"
	"fmt"
	"html/template"
	"time"
)

//go:embed template.html
var defaultTemplate string

// Payload is the input envelope for one receipt render. All fields are
// strings so the caller doesn't have to reconstruct the numeric shape
// from the Kafka payload — just pass them through.
type Payload struct {
	TransactionID     string
	TransactionNumber string
	TenantID          string
	GroupID           string
	MemberID          string
	Amount            string
	CurrencyCode      string
	TransactionType   string
	PaymentMethod     string
	Reference         string
	TransactionDate   string
	// RecipientLabel is the friendly name of the group or member the
	// payment came from — used on the "Received from" line at the top
	// of the receipt. Falls back to a truncated id if empty.
	RecipientLabel string
}

// PdfGenerator turns rendered HTML into PDF bytes. Shared shape with
// invoice.PdfGenerator so the wkhtmltopdf-backed generator can serve
// both renderers — passed in at construction so tests can stub.
type PdfGenerator interface {
	GeneratePDF(ctx context.Context, html []byte) ([]byte, error)
}

// Renderer holds a parsed template + a PdfGenerator. Long-lived — the
// template is parsed once at construction time.
type Renderer struct {
	tmpl *template.Template
	pdf  PdfGenerator
}

func NewRenderer(pdf PdfGenerator) (*Renderer, error) {
	t, err := template.New("receipt").Parse(defaultTemplate)
	if err != nil {
		return nil, fmt.Errorf("parse receipt template: %w", err)
	}
	return &Renderer{tmpl: t, pdf: pdf}, nil
}

// Render stamps the template with p and returns the PDF bytes.
func (r *Renderer) Render(ctx context.Context, p Payload) ([]byte, error) {
	p = withFallbacks(p)
	var buf bytes.Buffer
	if err := r.tmpl.Execute(&buf, p); err != nil {
		return nil, fmt.Errorf("render receipt HTML: %w", err)
	}
	return r.pdf.GeneratePDF(ctx, buf.Bytes())
}

// withFallbacks fills cosmetic gaps so the template doesn't render
// blanks. Recipient label falls back to a generic string — never a
// UUID fragment; no UUIDs should appear on a client-facing receipt.
func withFallbacks(p Payload) Payload {
	if p.RecipientLabel == "" {
		switch {
		case p.GroupID != "":
			p.RecipientLabel = "Valued group"
		case p.MemberID != "":
			p.RecipientLabel = "Valued member"
		default:
			p.RecipientLabel = "Valued payer"
		}
	}
	if p.PaymentMethod == "" {
		p.PaymentMethod = "—"
	}
	p.TransactionDate = HumaniseDate(p.TransactionDate)
	return p
}

// HumaniseDate turns an ISO 8601 timestamp into a reader-friendly
// string like "03 Jul 2026, 21:23 UTC". Returns the input verbatim if
// it can't be parsed — the receipt still renders, just less pretty.
// Exported so cmd/main can normalise the ReceiptPdfReady event's
// TransactionDate to the same shape shown on the PDF.
func HumaniseDate(iso string) string {
	if iso == "" {
		return "—"
	}
	for _, layout := range []string{
		time.RFC3339Nano,
		time.RFC3339,
		"2006-01-02T15:04:05.999999999",
		"2006-01-02T15:04:05",
		"2006-01-02 15:04:05",
	} {
		if t, err := time.Parse(layout, iso); err == nil {
			return t.UTC().Format("02 Jan 2006, 15:04 UTC")
		}
	}
	return iso
}
