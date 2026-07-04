// Package arrears consumes ARREARS_NOTICE events from contributions-service
// and dispatches an email to the group liaison or the member. Four kinds
// share one template + subject shape; the copy and next-step label differ:
//
//   REMINDER_PRE_SUSPENSION   → "Payment reminder — X days until suspension"
//   REMINDER_PRE_DEACTIVATION → "Payment reminder — X days until deactivation"
//   SUSPENDED_NOTICE          → "Your account has been suspended"
//   DEACTIVATED_NOTICE        → "Your account has been deactivated"
//
// Recipient resolution reuses recipient.Resolver — same code path as
// invoices and receipts — so a group notice goes to the liaison (with
// fallback to the group's email) and a member notice goes to the
// member directly.
package arrears

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

const DefaultSubject = "{{.HeadlineShort}} — {{.CurrencyCode}} {{.Balance}}"

func DefaultHTMLBody() string { return defaultBodyTemplate }

const templateKey = "ARREARS_NOTICE"

// Event is the wire projection of ARREARS_NOTICE. Strings across the
// board — the JSON envelope on the Java side stringifies everything for
// uniformity.
type Event struct {
	Event             string `json:"event"`
	Kind              string `json:"kind"`
	TenantID          string `json:"tenantId"`
	SubjectType       string `json:"subjectType"` // MEMBER | GROUP
	SubjectID         string `json:"subjectId"`
	CurrencyCode      string `json:"currencyCode"`
	Balance           string `json:"balance"`
	DaysOverdue       string `json:"daysOverdue"`
	DaysUntilNextStep string `json:"daysUntilNextStep"`
	NextStep          string `json:"nextStep"` // SUSPENSION | DEACTIVATION | DEACTIVATED
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
	var rcpt recipient.Recipient
	var err error
	switch e.SubjectType {
	case "GROUP":
		rcpt, err = d.Resolver.ForGroup(ctx, e.TenantID, e.SubjectID)
	case "MEMBER":
		rcpt, err = d.Resolver.ForMember(ctx, e.TenantID, e.SubjectID)
	default:
		err = fmt.Errorf("unknown subjectType %q", e.SubjectType)
	}
	if err != nil {
		log.Printf("[arrears] %s %s — resolve recipient: %v", e.Kind, e.SubjectID, err)
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
		log.Printf("[arrears] %s %s — SMTP send to %s: %v",
			e.Kind, e.SubjectID, rcpt.Email, err)
		return Result{Recipient: rcpt.Email, Source: tmpl.Source, Err: err}
	}
	log.Printf("[arrears] %s %s — sent to %s (%s) via %s template",
		e.Kind, e.SubjectID, rcpt.Email, rcpt.Kind, tmpl.Source)
	return Result{Ok: true, Recipient: rcpt.Email, Source: tmpl.Source}
}

type renderView struct {
	DisplayName, HeadlineShort, Lead, CallToAction string
	CurrencyCode, Balance                          string
	DaysOverdue, DaysUntilNextStep                 string
	NextStepLabel                                  string
}

func buildView(e Event, r recipient.Recipient) renderView {
	kindShort, lead, cta := copyForKind(e.Kind, e.NextStep, e.DaysUntilNextStep)
	nextStepLabel := "suspension"
	switch e.NextStep {
	case "DEACTIVATION":
		nextStepLabel = "deactivation"
	case "DEACTIVATED":
		nextStepLabel = ""
	}
	return renderView{
		DisplayName:       r.DisplayName,
		HeadlineShort:     kindShort,
		Lead:              lead,
		CallToAction:      cta,
		CurrencyCode:      e.CurrencyCode,
		Balance:           e.Balance,
		DaysOverdue:       e.DaysOverdue,
		DaysUntilNextStep: e.DaysUntilNextStep,
		NextStepLabel:     nextStepLabel,
	}
}

// copyForKind returns the (short subject headline, lead paragraph, call-to-action)
// tuple for a given kind. Kept in one place so a copy tweak lands consistently
// across email + subject.
func copyForKind(kind, nextStep, daysUntilNext string) (string, string, string) {
	switch kind {
	case "REMINDER_PRE_SUSPENSION":
		return "Payment reminder",
			fmt.Sprintf("Your account has an overdue balance. Please settle it within %s day(s) to avoid a suspension.", daysUntilNext),
			"You can pay via any of the tenant's supported channels — bank transfer, mobile money, or card."
	case "REMINDER_PRE_DEACTIVATION":
		return "Suspended account reminder",
			fmt.Sprintf("Your account is currently suspended and has %s day(s) remaining before it is deactivated.", daysUntilNext),
			"Once deactivated, the balance is written off as bad debt and requires operator involvement to restore. Please settle it soon."
	case "SUSPENDED_NOTICE":
		return "Account suspended",
			"Your account has been suspended due to overdue contributions.",
			"You will continue to be billed while suspended. Once you settle the outstanding balance the account will be reactivated automatically on the next daily sweep."
	case "DEACTIVATED_NOTICE":
		return "Account deactivated",
			"Your account has been deactivated due to sustained non-payment. Billing has stopped and the outstanding balance has been recorded as bad debt.",
			"To reactivate the account please contact your account manager to arrange settlement."
	default:
		return "Payment notice",
			"There is an outstanding balance on your account.",
			"Please contact your account manager for details."
	}
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
