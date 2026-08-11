// Package eob consumes CLAIM_EOB_ISSUED events emitted by claims-service
// and sends the member an explanation-of-benefits email.
//
// Wire model mirrors the advice pipeline (see internal/advice) — one
// event → one email, template resolver falls back to embedded default
// when no tenant override exists. PDF generation deferred; current MVP
// is HTML email only.
package eob

import (
	"bytes"
	"context"
	_ "embed"
	"encoding/json"
	"fmt"
	htmltemplate "html/template"
	"log"
	"math/big"
	texttemplate "text/template"

	"github.com/medfund/notification-service/internal/mail"
	"github.com/medfund/notification-service/internal/recipient"
	"github.com/medfund/notification-service/internal/template"
)

//go:embed body.html
var defaultBodyTemplate string

const DefaultSubject = "Explanation of benefits — claim {{.ClaimNumber}}"

func DefaultHTMLBody() string { return defaultBodyTemplate }

const templateKey = "CLAIM_EOB_ISSUED"

// Event mirrors the flat LinkedHashMap payload
// ClaimEventPublisher.publishEobIssued writes. Every field is a string
// because the Java publisher stringifies numeric values for envelope
// uniformity.
type Event struct {
	Event                string `json:"event"`
	ClaimID              string `json:"claimId"`
	ClaimNumber          string `json:"claimNumber"`
	MemberID             string `json:"memberId"`
	CurrencyCode         string `json:"currencyCode"`
	AllowedAmount        string `json:"allowedAmount"`
	DeductibleApplied    string `json:"deductibleApplied"`
	CopayAmount          string `json:"copayAmount"`
	CoinsuranceAmount    string `json:"coinsuranceAmount"`
	NotCoveredAmount     string `json:"notCoveredAmount"`
	ShortfallAmount      string `json:"shortfallAmount"`
	MemberResponsibility string `json:"memberResponsibility"`
	// ReasonCodes is a JSON-encoded array of {carc, rarc, amount, description}
	// tuples; unpacked lazily only when the template needs them.
	ReasonCodes string `json:"reasonCodes"`
	TenantID    string `json:"tenantId"`
}

// Result carries the outcome of one dispatch attempt.
type Result struct {
	Ok        bool
	Recipient string
	Source    string
	Err       error
}

// TemplateResolver mirrors the interface used by the other dispatchers so
// tenant-specific overrides in public.tenant_email_templates apply here too.
type TemplateResolver interface {
	Resolve(ctx context.Context, tenantID, key string) template.Template
}

type Dispatcher struct {
	Resolver  *recipient.Resolver
	Sender    mail.Sender
	Templates TemplateResolver
	From      string
}

func NewDispatcher(r *recipient.Resolver, s mail.Sender, t TemplateResolver, from string) (*Dispatcher, error) {
	if t == nil {
		return nil, fmt.Errorf("template resolver is required")
	}
	return &Dispatcher{Resolver: r, Sender: s, Templates: t, From: from}, nil
}

func (d *Dispatcher) Dispatch(ctx context.Context, e Event) Result {
	if d.Resolver == nil {
		return Result{Err: fmt.Errorf("recipient lookup disabled")}
	}
	if e.MemberID == "" {
		return Result{Err: fmt.Errorf("EOB event missing memberId")}
	}
	rcpt, err := d.Resolver.ForMember(ctx, e.TenantID, e.MemberID)
	if err != nil {
		log.Printf("[eob] %s — resolve recipient: %v", e.ClaimNumber, err)
		return Result{Err: err}
	}

	tmpl := d.Templates.Resolve(ctx, e.TenantID, templateKey)
	view := buildView(e, rcpt)
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
		log.Printf("[eob] %s — SMTP send to %s: %v", e.ClaimNumber, rcpt.Email, err)
		return Result{Recipient: rcpt.Email, Source: tmpl.Source, Err: err}
	}
	log.Printf("[eob] %s — sent to %s (%s) via %s template",
		e.ClaimNumber, rcpt.Email, rcpt.Kind, tmpl.Source)
	return Result{Ok: true, Recipient: rcpt.Email, Source: tmpl.Source}
}

// ReasonCode is the wire shape of one CARC/RARC entry produced by
// claims-service's CarcRarcMapper. Exposed on the view so the template
// can iterate them if a tenant wants to render them inline.
type ReasonCode struct {
	Carc        string `json:"carc"`
	Rarc        string `json:"rarc"`
	Amount      string `json:"amount"`
	Description string `json:"description"`
}

type renderView struct {
	DisplayName          string
	ClaimNumber          string
	CurrencyCode         string
	AllowedAmount        string
	DeductibleApplied    string
	CopayAmount          string
	CoinsuranceAmount    string
	NotCoveredAmount     string
	ShortfallAmount      string
	MemberResponsibility string
	PlanPaid             string
	HasDeductible        bool
	HasCopay             bool
	HasCoinsurance       bool
	HasNotCovered        bool
	HasShortfall         bool
	ReasonCodes          []ReasonCode
}

func buildView(e Event, r recipient.Recipient) renderView {
	planPaid := subtract(e.AllowedAmount, e.MemberResponsibility)
	var codes []ReasonCode
	if e.ReasonCodes != "" && e.ReasonCodes != "[]" {
		_ = json.Unmarshal([]byte(e.ReasonCodes), &codes)
	}
	return renderView{
		DisplayName:          displayOrFallback(r),
		ClaimNumber:          e.ClaimNumber,
		CurrencyCode:         orDefault(e.CurrencyCode, "USD"),
		AllowedAmount:        orZero(e.AllowedAmount),
		DeductibleApplied:    orZero(e.DeductibleApplied),
		CopayAmount:          orZero(e.CopayAmount),
		CoinsuranceAmount:    orZero(e.CoinsuranceAmount),
		NotCoveredAmount:     orZero(e.NotCoveredAmount),
		ShortfallAmount:      orZero(e.ShortfallAmount),
		MemberResponsibility: orZero(e.MemberResponsibility),
		PlanPaid:             orZero(planPaid),
		HasDeductible:        nonZero(e.DeductibleApplied),
		HasCopay:             nonZero(e.CopayAmount),
		HasCoinsurance:       nonZero(e.CoinsuranceAmount),
		HasNotCovered:        nonZero(e.NotCoveredAmount),
		HasShortfall:         nonZero(e.ShortfallAmount),
		ReasonCodes:          codes,
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

func orDefault(v, def string) string {
	if v == "" {
		return def
	}
	return v
}

func nonZero(v string) bool {
	if v == "" {
		return false
	}
	for _, r := range v {
		if r >= '1' && r <= '9' {
			return true
		}
	}
	return false
}

// subtract does allowed − memberResponsibility for the PlanPaid display.
// Uses big.Rat so decimal strings survive without rounding to zero.
func subtract(a, b string) string {
	if a == "" {
		return "0"
	}
	ar, ok := new(big.Rat).SetString(a)
	if !ok {
		return "0"
	}
	br, ok := new(big.Rat).SetString(b)
	if !ok {
		br = new(big.Rat)
	}
	return ar.Sub(ar, br).FloatString(2)
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
