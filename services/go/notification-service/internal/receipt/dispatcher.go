// Package receipt consumes TransactionRecorded events and emails a
// receipt to the paying group (via its liaison) or member. Mirrors the
// invoice dispatcher shape so the two pipelines stay symmetrical, but
// there's no PDF attachment — the email body carries the full receipt.
package receipt

import (
	"bytes"
	"context"
	_ "embed"
	"fmt"
	htmltemplate "html/template"
	"log"
	texttemplate "text/template"

	"github.com/medfund/notification-service/internal/mail"
	"github.com/medfund/notification-service/internal/recipient"
	"github.com/medfund/notification-service/internal/template"
)

//go:embed body.html
var defaultBodyTemplate string

// DefaultSubject and DefaultHTMLBody expose the platform-wide fallback
// so the cmd/main wiring can hand them to the per-tenant resolver.
const DefaultSubject = "Payment received — {{.CurrencyCode}} {{.Amount}} ({{.TransactionNumber}})"

func DefaultHTMLBody() string { return defaultBodyTemplate }

// templateKey — tenant admins can override the default via
// tenant_email_templates for this key.
const templateKey = "TRANSACTION_RECEIPT"

// Event is the projection of the ReceiptPdfReady wire payload —
// published by file-service after it renders the PDF and uploads it
// to MinIO. Carries the (PdfBucket, PdfObjectKey) tuple so the
// dispatcher can fetch the PDF and attach it to the email.
type Event struct {
	TransactionID     string `json:"transactionId"`
	TransactionNumber string `json:"transactionNumber"`
	TenantID          string `json:"tenantId"`
	GroupID           string `json:"groupId,omitempty"`
	MemberID          string `json:"memberId,omitempty"`
	Amount            string `json:"amount"`
	CurrencyCode      string `json:"currencyCode"`
	TransactionType   string `json:"transactionType"`
	PaymentMethod     string `json:"paymentMethod,omitempty"`
	Reference         string `json:"reference,omitempty"`
	TransactionDate   string `json:"transactionDate,omitempty"`
	// Recipient name resolved upstream (contributions-service). When
	// present the dispatcher uses this in the greeting instead of the
	// resolver's DisplayName so the email and the PDF header always
	// show the same friendly label.
	RecipientName string `json:"recipientName,omitempty"`
	PdfBucket     string `json:"pdfBucket"`
	PdfObjectKey  string `json:"pdfObjectKey"`
}

// PdfFetcher pulls the rendered receipt PDF back from object storage —
// same shape as invoice.PdfFetcher (MinIOFetcher satisfies both).
type PdfFetcher interface {
	GetObject(ctx context.Context, bucket, key string) ([]byte, error)
}

// Result matches invoice.Result so the consumer loop treats both
// pipelines uniformly when publishing NotificationSent.
type Result struct {
	Ok        bool
	Recipient string
	Source    string
	Err       error
}

// TemplateResolver is the same interface as invoice.TemplateResolver,
// duplicated here so a change to one signature doesn't ripple across
// packages before we're ready.
type TemplateResolver interface {
	Resolve(ctx context.Context, tenantID, key string) template.Template
}

type Dispatcher struct {
	Resolver  *recipient.Resolver
	Fetcher   PdfFetcher
	Sender    mail.Sender
	Templates TemplateResolver
	From      string
}

func NewDispatcher(r *recipient.Resolver, f PdfFetcher, s mail.Sender, t TemplateResolver, from string) (*Dispatcher, error) {
	if t == nil {
		return nil, fmt.Errorf("template resolver is required (use NopResolver in tests)")
	}
	return &Dispatcher{Resolver: r, Fetcher: f, Sender: s, Templates: t, From: from}, nil
}

// Dispatch runs the end-to-end receipt-email pipeline for one event.
func (d *Dispatcher) Dispatch(ctx context.Context, e Event) Result {
	if d.Resolver == nil {
		err := fmt.Errorf("recipient lookup disabled")
		log.Printf("[receipt] %s — %v, dropping", e.TransactionNumber, err)
		return Result{Err: err}
	}

	var rcpt recipient.Recipient
	var err error
	switch {
	case e.GroupID != "":
		rcpt, err = d.Resolver.ForGroup(ctx, e.TenantID, e.GroupID)
	case e.MemberID != "":
		rcpt, err = d.Resolver.ForMember(ctx, e.TenantID, e.MemberID)
	default:
		err = fmt.Errorf("event has neither groupId nor memberId")
	}
	if err != nil {
		log.Printf("[receipt] %s — resolve recipient: %v", e.TransactionNumber, err)
		return Result{Err: err}
	}

	// Fetch the rendered receipt PDF up front — an SMTP send with a
	// missing attachment is a bug, not an "email without attachment"
	// fallback. A fetch failure is retried like any other transient
	// error by the retry scheduler wrapping this dispatcher.
	var pdf []byte
	if d.Fetcher != nil && e.PdfObjectKey != "" {
		pdf, err = d.Fetcher.GetObject(ctx, e.PdfBucket, e.PdfObjectKey)
		if err != nil {
			log.Printf("[receipt] %s — fetch PDF s3://%s/%s: %v",
				e.TransactionNumber, e.PdfBucket, e.PdfObjectKey, err)
			return Result{Recipient: rcpt.Email, Err: err}
		}
	}

	tmpl := d.Templates.Resolve(ctx, e.TenantID, templateKey)
	subject, err := renderText(tmpl.Subject, e, rcpt)
	if err != nil {
		return Result{Recipient: rcpt.Email, Source: tmpl.Source, Err: err}
	}
	body, err := renderHTML(tmpl.HTMLBody, e, rcpt)
	if err != nil {
		return Result{Recipient: rcpt.Email, Source: tmpl.Source, Err: err}
	}

	msg := mail.Message{
		From:     d.From,
		To:       rcpt.Email,
		Subject:  subject,
		HTMLBody: body,
	}
	if len(pdf) > 0 {
		msg.Attachments = []mail.Attachment{{
			Filename:    "receipt-" + e.TransactionNumber + ".pdf",
			ContentType: "application/pdf",
			Data:        pdf,
		}}
	}
	if err := d.Sender.Send(msg); err != nil {
		log.Printf("[receipt] %s — SMTP send to %s: %v", e.TransactionNumber, rcpt.Email, err)
		return Result{Recipient: rcpt.Email, Source: tmpl.Source, Err: err}
	}
	log.Printf("[receipt] %s — sent to %s (%s) via %s template",
		e.TransactionNumber, rcpt.Email, rcpt.Kind, tmpl.Source)
	return Result{Ok: true, Recipient: rcpt.Email, Source: tmpl.Source}
}

type renderData struct {
	DisplayName, TransactionNumber, Amount, CurrencyCode,
	TransactionType, PaymentMethod, Reference, TransactionDate string
}

func dataFor(e Event, r recipient.Recipient) renderData {
	pm := e.PaymentMethod
	if pm == "" {
		pm = "—"
	}
	// Prefer the friendly name resolved upstream — matches what's on
	// the PDF header. Falls back to the resolver's DisplayName when the
	// upstream field is absent (legacy events).
	display := e.RecipientName
	if display == "" {
		display = r.DisplayName
	}
	return renderData{
		DisplayName:       display,
		TransactionNumber: e.TransactionNumber,
		Amount:            e.Amount,
		CurrencyCode:      e.CurrencyCode,
		TransactionType:   e.TransactionType,
		PaymentMethod:     pm,
		Reference:         e.Reference,
		TransactionDate:   e.TransactionDate,
	}
}

func renderText(src string, e Event, r recipient.Recipient) (string, error) {
	t, err := texttemplate.New("subject").Parse(src)
	if err != nil {
		return "", err
	}
	var buf bytes.Buffer
	if err := t.Execute(&buf, dataFor(e, r)); err != nil {
		return "", err
	}
	return buf.String(), nil
}

func renderHTML(src string, e Event, r recipient.Recipient) (string, error) {
	t, err := htmltemplate.New("body").Parse(src)
	if err != nil {
		return "", err
	}
	var buf bytes.Buffer
	if err := t.Execute(&buf, dataFor(e, r)); err != nil {
		return "", err
	}
	return buf.String(), nil
}
