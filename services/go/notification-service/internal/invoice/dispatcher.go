package invoice

import (
	"bytes"
	"context"
	_ "embed"
	"fmt"
	"html/template"
	"log"

	"github.com/medfund/notification-service/internal/mail"
	"github.com/medfund/notification-service/internal/recipient"
)

//go:embed body.html
var bodyTemplate string

// PdfFetcher pulls a rendered PDF back from object storage. The
// dispatcher doesn't care which backend — MinIO in dev, S3 in prod,
// in-memory fake in tests.
type PdfFetcher interface {
	GetObject(ctx context.Context, bucket, key string) ([]byte, error)
}

// Event is the projection of the InvoicePdfReady wire payload.
// Mirrors the Publisher in file-service exactly so the unmarshal Just
// Works without a translation layer.
type Event struct {
	InvoiceID     string `json:"invoiceId"`
	InvoiceNumber string `json:"invoiceNumber"`
	TenantID      string `json:"tenantId"`
	GroupID       string `json:"groupId,omitempty"`
	MemberID      string `json:"memberId,omitempty"`
	CurrencyCode  string `json:"currencyCode"`
	TotalAmount   string `json:"totalAmount"`
	PeriodStart   string `json:"periodStart"`
	PeriodEnd     string `json:"periodEnd"`
	DueDate       string `json:"dueDate"`
	PdfBucket     string `json:"pdfBucket"`
	PdfObjectKey  string `json:"pdfObjectKey"`
}

// Dispatcher is the orchestrator: resolve recipient → fetch PDF →
// render email body → send via SMTP.
type Dispatcher struct {
	Resolver *recipient.Resolver // nil = recipient lookup disabled (logs and skips)
	Fetcher  PdfFetcher
	Sender   mail.Sender
	From     string
	tmpl     *template.Template
}

func NewDispatcher(r *recipient.Resolver, f PdfFetcher, s mail.Sender, from string) (*Dispatcher, error) {
	t, err := template.New("invoice-email").Parse(bodyTemplate)
	if err != nil {
		return nil, fmt.Errorf("parse invoice email template: %w", err)
	}
	return &Dispatcher{Resolver: r, Fetcher: f, Sender: s, From: from, tmpl: t}, nil
}

// Dispatch handles one InvoicePdfReady event end-to-end. Errors are
// logged and swallowed so a single bad event can't crash the consumer;
// the caller commits the offset regardless. Retry semantics happen at
// the topic level (set the consumer group's auto-offset-reset
// appropriately if you need at-least-once email delivery).
func (d *Dispatcher) Dispatch(ctx context.Context, e Event) {
	if d.Resolver == nil {
		log.Printf("[invoice] %s — recipient lookup disabled, dropping", e.InvoiceNumber)
		return
	}

	var rcpt recipient.Recipient
	var err error
	switch {
	case e.GroupID != "":
		rcpt, err = d.Resolver.ForGroup(ctx, e.TenantID, e.GroupID)
	case e.MemberID != "":
		rcpt, err = d.Resolver.ForMember(ctx, e.TenantID, e.MemberID)
	default:
		log.Printf("[invoice] %s — event has neither groupId nor memberId, dropping", e.InvoiceNumber)
		return
	}
	if err != nil {
		log.Printf("[invoice] %s — resolve recipient: %v", e.InvoiceNumber, err)
		return
	}

	pdf, err := d.Fetcher.GetObject(ctx, e.PdfBucket, e.PdfObjectKey)
	if err != nil {
		log.Printf("[invoice] %s — fetch PDF s3://%s/%s: %v",
			e.InvoiceNumber, e.PdfBucket, e.PdfObjectKey, err)
		return
	}

	body, err := d.renderBody(rcpt, e)
	if err != nil {
		log.Printf("[invoice] %s — render body: %v", e.InvoiceNumber, err)
		return
	}

	msg := mail.Message{
		From:     d.From,
		To:       rcpt.Email,
		Subject:  fmt.Sprintf("Invoice %s — %s %s due %s",
			e.InvoiceNumber, e.CurrencyCode, e.TotalAmount, e.DueDate),
		HTMLBody: body,
		Attachments: []mail.Attachment{{
			Filename:    e.InvoiceNumber + ".pdf",
			ContentType: "application/pdf",
			Data:        pdf,
		}},
	}
	if err := d.Sender.Send(msg); err != nil {
		log.Printf("[invoice] %s — SMTP send to %s: %v", e.InvoiceNumber, rcpt.Email, err)
		return
	}
	log.Printf("[invoice] %s — sent to %s (%s)", e.InvoiceNumber, rcpt.Email, rcpt.Kind)
}

func (d *Dispatcher) renderBody(r recipient.Recipient, e Event) (string, error) {
	var buf bytes.Buffer
	if err := d.tmpl.Execute(&buf, struct {
		DisplayName, InvoiceNumber, CurrencyCode, TotalAmount,
		PeriodStart, PeriodEnd, DueDate string
	}{
		DisplayName:   r.DisplayName,
		InvoiceNumber: e.InvoiceNumber,
		CurrencyCode:  e.CurrencyCode,
		TotalAmount:   e.TotalAmount,
		PeriodStart:   e.PeriodStart,
		PeriodEnd:     e.PeriodEnd,
		DueDate:       e.DueDate,
	}); err != nil {
		return "", err
	}
	return buf.String(), nil
}
