package invoice

import (
	"bytes"
	"context"
	_ "embed"
	"fmt"
	"html/template"
	"log"
	"os/exec"
	"time"
)

//go:embed template.html
var defaultTemplate string

// Payload is the projection of an InvoiceIssued event the template binds
// to. Mirrors the wire shape published by contributions-service
// (ContributionEventPublisher.InvoiceIssuedPayload) but resolves
// RecipientLabel inside Renderer so the template stays neutral about
// group-vs-individual routing.
type Payload struct {
	InvoiceID      string
	InvoiceNumber  string
	TenantID       string
	GroupID        string
	MemberID       string
	CurrencyCode   string
	TotalAmount    string
	PeriodStart    string
	PeriodEnd      string
	DueDate        string
	IssuedDate     string
	RecipientLabel string
}

// PdfGenerator turns rendered HTML into PDF bytes. Modeled as an
// interface so tests don't need wkhtmltopdf installed.
type PdfGenerator interface {
	GeneratePDF(ctx context.Context, html []byte) ([]byte, error)
}

// Renderer composes the HTML template + a PdfGenerator. Created once at
// boot — both the template and the binary path are immutable for the
// lifetime of the service.
type Renderer struct {
	tmpl *template.Template
	pdf  PdfGenerator
}

func NewRenderer(pdf PdfGenerator) (*Renderer, error) {
	tmpl, err := template.New("invoice").Parse(defaultTemplate)
	if err != nil {
		return nil, fmt.Errorf("parse invoice template: %w", err)
	}
	return &Renderer{tmpl: tmpl, pdf: pdf}, nil
}

// Render binds the payload to the template and produces the final PDF
// bytes. RecipientLabel is computed from group/member ids if it isn't
// already set — caller can override (e.g. after looking up the actual
// liaison name) by populating Payload.RecipientLabel before calling.
func (r *Renderer) Render(ctx context.Context, p Payload) ([]byte, error) {
	if p.RecipientLabel == "" {
		p.RecipientLabel = defaultRecipientLabel(p)
	}
	if p.IssuedDate == "" {
		p.IssuedDate = time.Now().UTC().Format("2006-01-02")
	}

	var buf bytes.Buffer
	if err := r.tmpl.Execute(&buf, p); err != nil {
		return nil, fmt.Errorf("execute invoice template: %w", err)
	}
	return r.pdf.GeneratePDF(ctx, buf.Bytes())
}

// HTML returns the rendered HTML without invoking the PDF generator —
// useful for tests and for the future "preview in browser" affordance
// without paying the wkhtmltopdf round-trip.
func (r *Renderer) HTML(p Payload) ([]byte, error) {
	if p.RecipientLabel == "" {
		p.RecipientLabel = defaultRecipientLabel(p)
	}
	if p.IssuedDate == "" {
		p.IssuedDate = time.Now().UTC().Format("2006-01-02")
	}
	var buf bytes.Buffer
	if err := r.tmpl.Execute(&buf, p); err != nil {
		return nil, fmt.Errorf("execute invoice template: %w", err)
	}
	return buf.Bytes(), nil
}

func defaultRecipientLabel(p Payload) string {
	switch {
	case p.GroupID != "":
		return "Group " + shortID(p.GroupID)
	case p.MemberID != "":
		return "Member " + shortID(p.MemberID)
	default:
		// Should never happen — the publisher elides whichever side is
		// null. Fall back to the invoice number so the PDF still
		// renders something meaningful.
		return "Invoice " + p.InvoiceNumber
	}
}

func shortID(id string) string {
	if len(id) <= 8 {
		return id
	}
	return id[:8]
}

// ─── wkhtmltopdf-backed PdfGenerator ─────────────────────────────────

// WkhtmltopdfGenerator runs the wkhtmltopdf binary with HTML on stdin
// and reads the PDF from stdout. The binary is whatever path
// {@code config.WkhtmltopdfBin} resolves to (default {@code wkhtmltopdf}
// on PATH).
type WkhtmltopdfGenerator struct {
	BinPath string
	Timeout time.Duration
}

func NewWkhtmltopdfGenerator(binPath string) *WkhtmltopdfGenerator {
	return &WkhtmltopdfGenerator{BinPath: binPath, Timeout: 30 * time.Second}
}

func (w *WkhtmltopdfGenerator) GeneratePDF(ctx context.Context, html []byte) ([]byte, error) {
	if w.BinPath == "" {
		return nil, fmt.Errorf("wkhtmltopdf binary not configured")
	}
	ctx, cancel := context.WithTimeout(ctx, w.Timeout)
	defer cancel()

	cmd := exec.CommandContext(ctx, w.BinPath,
		"--quiet",
		"--encoding", "utf-8",
		"--enable-local-file-access", // safe — we control the input
		"-",                          // read HTML from stdin
		"-",                          // write PDF to stdout
	)
	cmd.Stdin = bytes.NewReader(html)
	var stdout, stderr bytes.Buffer
	cmd.Stdout = &stdout
	cmd.Stderr = &stderr

	if err := cmd.Run(); err != nil {
		return nil, fmt.Errorf("wkhtmltopdf failed: %w: %s", err, stderr.String())
	}
	return stdout.Bytes(), nil
}

// ─── Stub PdfGenerator for dev/test ──────────────────────────────────

// StubPdfGenerator returns a one-line PDF header — same shape as the
// legacy export.GeneratePDF behaviour. Used when wkhtmltopdf isn't
// installed (CI without the binary, fast local-dev iteration) so the
// rest of the pipeline can be exercised end-to-end.
type StubPdfGenerator struct{}

func (StubPdfGenerator) GeneratePDF(ctx context.Context, html []byte) ([]byte, error) {
	log.Printf("[invoice] PDF stub: html=%d bytes (wkhtmltopdf disabled)", len(html))
	return []byte("%PDF-1.4 stub"), nil
}
