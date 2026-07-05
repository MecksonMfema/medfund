package invoice

import (
	"bytes"
	"context"
	_ "embed"
	"encoding/json"
	"fmt"
	"html/template"
	"log"
	"os/exec"
	"strconv"
	"time"

	"github.com/medfund/file-service/internal/contributions"
)

//go:embed template.html
var defaultTemplate string

// Payload is the identity envelope for one PDF render. It is the input
// to Render — a caller populates the fields it knows from the
// InvoiceIssued Kafka event and (optionally) the enriched RenderPayload
// pulled from contributions-service. When RenderData is nil the
// renderer falls back to the legacy summary view (period + total only).
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

	// RenderData is the full statement snapshot fetched from
	// contributions-service. When present the template shows the
	// same rows the Angular statement page does: opening balance →
	// per-scheme contributions → transactions → closing balance.
	RenderData *contributions.RenderPayload
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

	view := buildView(p)
	var buf bytes.Buffer
	if err := r.tmpl.Execute(&buf, view); err != nil {
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
	view := buildView(p)
	var buf bytes.Buffer
	if err := r.tmpl.Execute(&buf, view); err != nil {
		return nil, fmt.Errorf("execute invoice template: %w", err)
	}
	return buf.Bytes(), nil
}

// ─── Template view model ─────────────────────────────────────────────
//
// A pre-formatted, template-friendly projection of Payload. All money
// values are already rendered as strings with 2 decimal places so the
// template stays dumb.

type templateView struct {
	InvoiceNumber string
	IssuedDate    string
	DueDate       string
	PeriodStart   string
	PeriodEnd     string
	Recipient     string
	CurrencyCode  string

	HasSnapshot     bool
	OpeningBalance  string
	ClosingBalance  string
	TotalCharges    string
	TotalPayments   string
	TotalAdjustments string
	AmountDue       string
	SubtotalAmount  string

	Schemes      []schemeGroup
	Transactions []transactionRow

	// Bookend ledger rows — the backend now emits OPENING_BALANCE and
	// CLOSING_BALANCE marker lines at the start / end of statement.lines
	// so PDF, Excel and Angular render the ledger in proper double-entry
	// format. Split into their own fields here so the template can style
	// them as visually-distinct rows without special-casing the loop.
	OpeningRow *bookendRow
	ClosingRow *bookendRow
}

type bookendRow struct {
	Date        string
	Description string
	Balance     string
}

type schemeGroup struct {
	SchemeName string
	Rows       []contributionRow
	Subtotal   string
}

type contributionRow struct {
	Reference string
	Name      string
	AgeBand   string
	Amount    string
}

type transactionRow struct {
	Date        string
	Description string
	Reference   string
	Debit       string
	Credit      string
	Balance     string
}

func buildView(p Payload) templateView {
	v := templateView{
		InvoiceNumber:  p.InvoiceNumber,
		IssuedDate:     p.IssuedDate,
		DueDate:        p.DueDate,
		PeriodStart:    p.PeriodStart,
		PeriodEnd:      p.PeriodEnd,
		Recipient:      p.RecipientLabel,
		CurrencyCode:   p.CurrencyCode,
		SubtotalAmount: money2dpStr(p.TotalAmount),
		AmountDue:      money2dpStr(p.TotalAmount),
	}

	rd := p.RenderData
	if rd == nil {
		return v
	}

	v.HasSnapshot = true
	// Prefer statement header's balances; fall back to invoice snapshot fields.
	v.OpeningBalance = money2dpNum(pickNumber(rd.Statement.Header.OpeningBalance, rd.Invoice.OpeningBalance))
	v.ClosingBalance = money2dpNum(pickNumber(rd.Statement.Header.ClosingBalance, rd.Invoice.ClosingBalance))
	v.TotalCharges = money2dpNum(rd.Statement.Header.TotalCharges)
	v.TotalPayments = money2dpNum(rd.Statement.Header.TotalPayments)
	v.TotalAdjustments = money2dpNum(rd.Invoice.AdjustmentsInWindow)
	v.AmountDue = money2dpNum(pickNumber(rd.Statement.Header.ClosingBalance, rd.Invoice.ClosingBalance))

	// Group contributions by scheme (preserve first-seen order).
	seen := map[string]int{}
	for _, row := range rd.Contributions {
		idx, ok := seen[row.SchemeName]
		if !ok {
			idx = len(v.Schemes)
			seen[row.SchemeName] = idx
			v.Schemes = append(v.Schemes, schemeGroup{SchemeName: row.SchemeName})
		}
		name := row.MemberName
		if row.PersonType == "DEPENDANT" && row.DependantName != "" {
			name = row.DependantName
		}
		ref := row.MemberNumber
		if row.PersonType == "DEPENDANT" {
			ref = row.MemberNumber + " · dep"
		}
		v.Schemes[idx].Rows = append(v.Schemes[idx].Rows, contributionRow{
			Reference: ref,
			Name:      name,
			AgeBand:   row.AgeBand,
			Amount:    money2dpNum(row.Amount),
		})
	}
	// Compute per-scheme subtotals.
	for i := range v.Schemes {
		sum := 0.0
		for _, r := range v.Schemes[i].Rows {
			amt, _ := strconv.ParseFloat(stripCommas(r.Amount), 64)
			sum += amt
		}
		v.Schemes[i].Subtotal = fmt.Sprintf("%.2f", sum)
	}

	// Walk the statement lines once: bookend rows split into their
	// own fields (rendered as visually-distinct b/f + c/f rows);
	// CONTRIBUTION lines skipped (already rendered per-scheme above);
	// everything else lands in the transactions ledger.
	for _, ln := range rd.Statement.Lines {
		switch ln.Type {
		case "OPENING_BALANCE":
			v.OpeningRow = &bookendRow{
				Date:        formatDate(ln.Date),
				Description: ln.Description,
				Balance:     money2dpNum(ln.RunningBalance),
			}
		case "CLOSING_BALANCE":
			v.ClosingRow = &bookendRow{
				Date:        formatDate(ln.Date),
				Description: ln.Description,
				Balance:     money2dpNum(ln.RunningBalance),
			}
		case "CONTRIBUTION":
			continue
		default:
			v.Transactions = append(v.Transactions, transactionRow{
				Date:        formatDate(ln.Date),
				Description: ln.Description,
				Reference:   ln.Reference,
				Debit:       money2dpNum(ln.Debit),
				Credit:      money2dpNum(ln.Credit),
				Balance:     money2dpNum(ln.RunningBalance),
			})
		}
	}

	return v
}

func defaultRecipientLabel(p Payload) string {
	switch {
	case p.GroupID != "":
		return "Valued group"
	case p.MemberID != "":
		return "Valued member"
	default:
		// Should never happen — the publisher elides whichever side is
		// null. Fall back to the invoice number so the PDF still
		// renders something meaningful.
		return "Invoice " + p.InvoiceNumber
	}
}

// money2dpStr formats a BigDecimal-as-string with 2 decimal places.
// Empty or unparsable inputs render as "0.00" so grand-total cells
// never show blank — safer for a financial document than a dash.
func money2dpStr(s string) string {
	if s == "" {
		return "0.00"
	}
	f, err := strconv.ParseFloat(s, 64)
	if err != nil {
		return s
	}
	return fmt.Sprintf("%.2f", f)
}

// money2dpNum is the json.Number variant used for optional money
// fields coming off the render-payload wire (opening/closing balance,
// per-line debit/credit). An empty Number renders as an empty string
// so debit/credit cells only fill on the side that carries the amount.
func money2dpNum(n json.Number) string {
	s := string(n)
	if s == "" {
		return ""
	}
	f, err := strconv.ParseFloat(s, 64)
	if err != nil {
		return s
	}
	return fmt.Sprintf("%.2f", f)
}

// pickNumber returns the first non-empty json.Number, falling back to
// the second. Used when the same value might be present on both the
// statement header and the invoice snapshot — the header is authoritative.
func pickNumber(a, b json.Number) json.Number {
	if string(a) != "" {
		return a
	}
	return b
}

func stripCommas(s string) string {
	out := make([]byte, 0, len(s))
	for i := 0; i < len(s); i++ {
		if s[i] == ',' {
			continue
		}
		out = append(out, s[i])
	}
	return string(out)
}

// formatDate turns an ISO-8601 instant ("2026-07-01T10:00:00Z") into a
// short human date ("01 Jul 2026") — matches the statement page.
func formatDate(iso string) string {
	if iso == "" {
		return ""
	}
	t, err := time.Parse(time.RFC3339, iso)
	if err != nil {
		return iso
	}
	return t.UTC().Format("02 Jan 2006")
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
