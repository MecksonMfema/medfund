package invoice

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

// DefaultSubject is exported so the cmd/main wiring can hand it to the
// template resolver as the platform-wide fallback.
const DefaultSubject = "Contribution statement {{.InvoiceNumber}} — {{.CurrencyCode}} {{.TotalAmount}} due {{.DueDate}}"

// DefaultHTMLBody returns the embedded fallback body template — same
// value the dispatcher used before per-tenant template lookup landed.
func DefaultHTMLBody() string { return defaultBodyTemplate }

// PdfFetcher pulls a rendered PDF back from object storage.
type PdfFetcher interface {
	GetObject(ctx context.Context, bucket, key string) ([]byte, error)
}

// TemplateResolver finds the right subject+body for a (tenant, key).
// Modelled as an interface so tests can pass in canned templates
// without spinning up Postgres.
type TemplateResolver interface {
	Resolve(ctx context.Context, tenantID, key string) template.Template
}

// Event is the projection of the InvoicePdfReady wire payload.
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

// Result captures the outcome of a single Dispatch so the consumer
// loop can publish an accurate NotificationSent event. Ok==true means
// the SMTP server accepted the message; Recipient is always populated
// when we made it past recipient resolution.
type Result struct {
	Ok        bool
	Recipient string
	Source    string // template source: "tenant" or "default"
	Err       error
}

// templateKey is the tenant_email_templates.template_key value we look
// up — kept as a single const so a future renames stays scoped.
const templateKey = "INVOICE_ISSUED"

type Dispatcher struct {
	Resolver  *recipient.Resolver // nil = recipient lookup disabled
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

// Dispatch runs the end-to-end invoice email pipeline for one event.
// Returns Result so the caller can publish NotificationSent with the
// correct Status — previously this swallowed errors and the consumer
// always emitted "SENT", masking real failures from audit + dashboards.
func (d *Dispatcher) Dispatch(ctx context.Context, e Event) Result {
	if d.Resolver == nil {
		err := fmt.Errorf("recipient lookup disabled")
		log.Printf("[invoice] %s — %v, dropping", e.InvoiceNumber, err)
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
		log.Printf("[invoice] %s — resolve recipient: %v", e.InvoiceNumber, err)
		return Result{Err: err}
	}

	pdf, err := d.Fetcher.GetObject(ctx, e.PdfBucket, e.PdfObjectKey)
	if err != nil {
		log.Printf("[invoice] %s — fetch PDF s3://%s/%s: %v",
			e.InvoiceNumber, e.PdfBucket, e.PdfObjectKey, err)
		return Result{Recipient: rcpt.Email, Err: err}
	}

	tmpl := d.Templates.Resolve(ctx, e.TenantID, templateKey)
	subject, err := renderText(tmpl.Subject, e, rcpt)
	if err != nil {
		log.Printf("[invoice] %s — render subject: %v", e.InvoiceNumber, err)
		return Result{Recipient: rcpt.Email, Source: tmpl.Source, Err: err}
	}
	body, err := renderHTML(tmpl.HTMLBody, e, rcpt)
	if err != nil {
		log.Printf("[invoice] %s — render body: %v", e.InvoiceNumber, err)
		return Result{Recipient: rcpt.Email, Source: tmpl.Source, Err: err}
	}

	msg := mail.Message{
		From:     d.From,
		To:       rcpt.Email,
		Subject:  subject,
		HTMLBody: body,
		Attachments: []mail.Attachment{{
			Filename:    e.InvoiceNumber + ".pdf",
			ContentType: "application/pdf",
			Data:        pdf,
		}},
	}
	if err := d.Sender.Send(msg); err != nil {
		log.Printf("[invoice] %s — SMTP send to %s: %v", e.InvoiceNumber, rcpt.Email, err)
		return Result{Recipient: rcpt.Email, Source: tmpl.Source, Err: err}
	}
	log.Printf("[invoice] %s — sent to %s (%s) via %s template",
		e.InvoiceNumber, rcpt.Email, rcpt.Kind, tmpl.Source)
	return Result{Ok: true, Recipient: rcpt.Email, Source: tmpl.Source}
}

// renderTextData / renderHTMLData both bind to this struct — same
// shape regardless of which template engine the source string targets.
type renderData struct {
	DisplayName, InvoiceNumber, CurrencyCode, TotalAmount,
	PeriodStart, PeriodEnd, DueDate string
}

func dataFor(e Event, r recipient.Recipient) renderData {
	return renderData{
		DisplayName:   r.DisplayName,
		InvoiceNumber: e.InvoiceNumber,
		CurrencyCode:  e.CurrencyCode,
		TotalAmount:   e.TotalAmount,
		PeriodStart:   e.PeriodStart,
		PeriodEnd:     e.PeriodEnd,
		DueDate:       e.DueDate,
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

// NopResolver returns a fixed (subject, body) pair regardless of
// (tenantID, key). Tests use it to bypass the database; production
// uses template.Resolver against tenant_email_templates.
type NopResolver struct {
	Subject  string
	HTMLBody string
}

func (n NopResolver) Resolve(_ context.Context, _, _ string) template.Template {
	return template.Template{Subject: n.Subject, HTMLBody: n.HTMLBody, Source: "default"}
}
