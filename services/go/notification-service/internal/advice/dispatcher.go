// Package advice consumes PAYMENT_ADVICE_GENERATED events from
// finance-service and sends an email to the payee (provider or member).
// The email body is a compact summary of the ledger totals; full
// line-item detail lives on the /tenant/finance/advices/:id page in
// the portal.
package advice

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

const DefaultSubject = "Payment advice {{.AdviceNumber}} — {{.CurrencyCode}} {{.NetDue}} net due"

func DefaultHTMLBody() string { return defaultBodyTemplate }

const templateKey = "PAYMENT_ADVICE_GENERATED"

// Event is the wire projection of medfund.payments.advice.generated —
// mirrors the JSON payload FinanceEventPublisher.publishAdviceGenerated
// writes. Every field is a string because the Java publisher stringifies
// numeric values for envelope uniformity.
type Event struct {
	Event        string `json:"event"`
	AdviceID     string `json:"adviceId"`
	AdviceNumber string `json:"adviceNumber"`
	PaymentRunID string `json:"paymentRunId"`
	PayeeType    string `json:"payeeType"` // PROVIDER | MEMBER
	ProviderID   string `json:"providerId,omitempty"`
	MemberID     string `json:"memberId,omitempty"`
	CurrencyCode string `json:"currencyCode"`
	NetDueAmount string `json:"netDueAmount"`
	TenantID     string `json:"tenantId"`
}

type Result struct {
	Ok        bool
	Recipient string
	Source    string
	Err       error
}

type TemplateResolver interface {
	Resolve(ctx context.Context, tenantID, key string) template.Template
}

// AdviceLookup fetches the ledger totals for an advice so the email
// body can show carried-forward, claims-paid, deductions, and net due
// without another cross-service round-trip. Modelled as an interface so
// tests can inject canned totals.
type AdviceLookup interface {
	Fetch(ctx context.Context, tenantID, adviceID string) (Totals, error)
}

// Totals is what AdviceLookup returns — decoupled from the event
// payload so we don't have to redeploy finance-service to change what
// the email surfaces.
type Totals struct {
	CarriedIn      string
	ClaimsPaid     string
	CtcApplied     string
	AdvanceApplied string
	TaxWithheld    string
	Shortfall      string
	NetDue         string
}

type Dispatcher struct {
	Resolver  *recipient.Resolver
	Sender    mail.Sender
	Templates TemplateResolver
	Lookup    AdviceLookup // may be nil — email still sends with just the header numbers
	From      string
}

func NewDispatcher(r *recipient.Resolver, s mail.Sender, t TemplateResolver,
	lookup AdviceLookup, from string) (*Dispatcher, error) {
	if t == nil {
		return nil, fmt.Errorf("template resolver is required")
	}
	return &Dispatcher{Resolver: r, Sender: s, Templates: t, Lookup: lookup, From: from}, nil
}

func (d *Dispatcher) Dispatch(ctx context.Context, e Event) Result {
	if d.Resolver == nil {
		return Result{Err: fmt.Errorf("recipient lookup disabled")}
	}
	var rcpt recipient.Recipient
	var err error
	switch e.PayeeType {
	case "PROVIDER":
		if e.ProviderID == "" {
			return Result{Err: fmt.Errorf("PROVIDER advice missing providerId")}
		}
		rcpt, err = d.Resolver.ForProvider(ctx, e.TenantID, e.ProviderID)
	case "MEMBER":
		if e.MemberID == "" {
			return Result{Err: fmt.Errorf("MEMBER advice missing memberId")}
		}
		rcpt, err = d.Resolver.ForMember(ctx, e.TenantID, e.MemberID)
	default:
		err = fmt.Errorf("unknown payeeType %q", e.PayeeType)
	}
	if err != nil {
		log.Printf("[advice] %s — resolve recipient: %v", e.AdviceNumber, err)
		return Result{Err: err}
	}

	totals := Totals{NetDue: e.NetDueAmount}
	if d.Lookup != nil {
		if t, err := d.Lookup.Fetch(ctx, e.TenantID, e.AdviceID); err != nil {
			log.Printf("[advice] %s — totals lookup failed, falling back to header-only email: %v",
				e.AdviceNumber, err)
		} else {
			totals = t
		}
	}

	tmpl := d.Templates.Resolve(ctx, e.TenantID, templateKey)
	view := buildView(e, rcpt, totals)
	subject, err := renderText(tmpl.Subject, view)
	if err != nil {
		return Result{Recipient: rcpt.Email, Source: tmpl.Source, Err: err}
	}
	body, err := renderHTML(tmpl.HTMLBody, view)
	if err != nil {
		return Result{Recipient: rcpt.Email, Source: tmpl.Source, Err: err}
	}
	msg := mail.Message{From: d.From, To: rcpt.Email, Subject: subject, HTMLBody: body}
	if err := d.Sender.Send(msg); err != nil {
		log.Printf("[advice] %s — SMTP send to %s: %v", e.AdviceNumber, rcpt.Email, err)
		return Result{Recipient: rcpt.Email, Source: tmpl.Source, Err: err}
	}
	log.Printf("[advice] %s — sent to %s (%s) via %s template",
		e.AdviceNumber, rcpt.Email, rcpt.Kind, tmpl.Source)
	return Result{Ok: true, Recipient: rcpt.Email, Source: tmpl.Source}
}

type renderView struct {
	DisplayName, AdviceNumber, PaymentRunID string
	CurrencyCode                             string
	CarriedIn, ClaimsPaid, CtcApplied        string
	AdvanceApplied, TaxWithheld, Shortfall   string
	NetDue                                   string
	HasCtc, HasAdvance, HasTax, HasShortfall bool
}

func buildView(e Event, r recipient.Recipient, t Totals) renderView {
	return renderView{
		DisplayName:    displayOrFallback(r),
		AdviceNumber:   e.AdviceNumber,
		PaymentRunID:   e.PaymentRunID,
		CurrencyCode:   e.CurrencyCode,
		CarriedIn:      orZero(t.CarriedIn),
		ClaimsPaid:     orZero(t.ClaimsPaid),
		CtcApplied:     orZero(t.CtcApplied),
		AdvanceApplied: orZero(t.AdvanceApplied),
		TaxWithheld:    orZero(t.TaxWithheld),
		Shortfall:      orZero(t.Shortfall),
		NetDue:         orZero(t.NetDue),
		HasCtc:         nonZero(t.CtcApplied),
		HasAdvance:     nonZero(t.AdvanceApplied),
		HasTax:         nonZero(t.TaxWithheld),
		HasShortfall:   nonZero(t.Shortfall),
	}
}

func displayOrFallback(r recipient.Recipient) string {
	if r.DisplayName != "" {
		return r.DisplayName
	}
	return r.Email
}

func orZero(v string) string {
	if v == "" {
		return "0.00"
	}
	return v
}

func nonZero(v string) bool {
	if v == "" {
		return false
	}
	// Strip commas and check for anything other than zeros / punctuation.
	for _, r := range v {
		if r >= '1' && r <= '9' {
			return true
		}
	}
	return false
}

func renderText(src string, v renderView) (string, error) {
	t, err := texttemplate.New("subject").Parse(src)
	if err != nil {
		return "", err
	}
	var buf bytes.Buffer
	if err := t.Execute(&buf, v); err != nil {
		return "", err
	}
	return buf.String(), nil
}

func renderHTML(src string, v renderView) (string, error) {
	t, err := htmltemplate.New("body").Parse(src)
	if err != nil {
		return "", err
	}
	var buf bytes.Buffer
	if err := t.Execute(&buf, v); err != nil {
		return "", err
	}
	return buf.String(), nil
}
