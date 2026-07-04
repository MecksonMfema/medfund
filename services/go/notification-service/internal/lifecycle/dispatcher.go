// Package lifecycle consumes MEMBER_STATUS_CHANGED and
// GROUP_STATUS_CHANGED events and emails the affected member or group
// liaison whenever the new status is one operators or payers actually
// care about hearing: suspended, deactivated, or active (a
// reactivation after suspension).
//
// This covers both operator-triggered flips and arrears-triggered
// flips (the arrears executor calls user-service which then emits
// these same events), so we don't double-notify. Terminated
// transitions are intentionally skipped — that's an off-boarding
// action the operator communicates out-of-band.
package lifecycle

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

const DefaultSubject = "{{.HeadlineShort}}"

func DefaultHTMLBody() string { return defaultBodyTemplate }

const templateKey = "STATUS_LIFECYCLE"

// SubjectType — what to route to.
type SubjectType string

const (
	SubjectMember SubjectType = "MEMBER"
	SubjectGroup  SubjectType = "GROUP"
)

// Event is the normalised shape both member-lifecycle and
// group-lifecycle events collapse into. The consumer glue in
// cmd/main pulls the right fields off each raw payload.
type Event struct {
	TenantID    string
	SubjectType SubjectType
	SubjectID   string
	Status      string // active | suspended | deactivated | terminated
	Reason      string // optional, e.g. ARREARS_ESCALATION | ARREARS_CLEARED | OPERATOR
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

// interestedIn returns true when the status change is worth an email.
// Terminated flips are intentionally skipped — the operator ends the
// relationship out-of-band and a "we've closed your account" email
// isn't the right touch. Everything else is a payer-visible signal.
func interestedIn(status string) bool {
	return status == "suspended" || status == "deactivated" || status == "active"
}

// Dispatch runs the end-to-end email pipeline for one lifecycle event.
// Returns a Result mirroring the invoice / arrears dispatchers so the
// consumer loop uses one retry helper.
func (d *Dispatcher) Dispatch(ctx context.Context, e Event) Result {
	if !interestedIn(e.Status) {
		return Result{Ok: true} // no-op — treat as success so retry doesn't fire
	}
	if d.Resolver == nil {
		return Result{Err: fmt.Errorf("recipient lookup disabled")}
	}

	var rcpt recipient.Recipient
	var err error
	switch e.SubjectType {
	case SubjectGroup:
		rcpt, err = d.Resolver.ForGroup(ctx, e.TenantID, e.SubjectID)
	case SubjectMember:
		rcpt, err = d.Resolver.ForMember(ctx, e.TenantID, e.SubjectID)
	default:
		err = fmt.Errorf("unknown subjectType %q", e.SubjectType)
	}
	if err != nil {
		log.Printf("[lifecycle] %s %s — resolve recipient: %v", e.Status, e.SubjectID, err)
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
		log.Printf("[lifecycle] %s %s — SMTP send to %s: %v",
			e.Status, e.SubjectID, rcpt.Email, err)
		return Result{Recipient: rcpt.Email, Source: tmpl.Source, Err: err}
	}
	log.Printf("[lifecycle] %s %s — sent to %s (%s) via %s template",
		e.Status, e.SubjectID, rcpt.Email, rcpt.Kind, tmpl.Source)
	return Result{Ok: true, Recipient: rcpt.Email, Source: tmpl.Source}
}

type renderView struct {
	DisplayName   string
	HeadlineShort string
	Lead          string
	CallToAction  string
	ReasonLine    string
}

func buildView(e Event, r recipient.Recipient) renderView {
	head, lead, cta := copyFor(e.Status, e.Reason)
	return renderView{
		DisplayName:   r.DisplayName,
		HeadlineShort: head,
		Lead:          lead,
		CallToAction:  cta,
		ReasonLine:    reasonLine(e.Reason),
	}
}

// copyFor picks subject headline + body copy for a (status, reason)
// pair. Kept in one place so a tone tweak lands consistently.
func copyFor(status, reason string) (string, string, string) {
	switch status {
	case "suspended":
		return "Your account has been suspended",
			"Your account has been suspended. During suspension you continue to be billed, but service delivery may be limited at the tenant's discretion.",
			"To restore access, settle the outstanding balance — accounts suspended for arrears reactivate automatically on the next daily sweep after payment lands."
	case "deactivated":
		return "Your account has been deactivated",
			"Your account has been deactivated. Billing has stopped and any outstanding balance has been written off as bad debt.",
			"To reactivate please contact your account manager to arrange settlement. Deactivated accounts are not reactivated automatically."
	case "active":
		if reason == "ARREARS_CLEARED" {
			return "Your account has been reactivated",
				"Your outstanding balance has cleared and your account has been reactivated automatically.",
				"You'll receive normal statements again from the next billing cycle."
		}
		return "Your account has been reactivated",
			"Your account has been reactivated.",
			"Welcome back — you'll continue to receive statements as before."
	default:
		return "Account status change",
			fmt.Sprintf("Your account status changed to %s.", status),
			"Contact your account manager if you have any questions."
	}
}

// reasonLine renders the machine reason as an operator-friendly line
// on the email. Blank when the reason is one we've already reflected
// in the body copy — we don't want to double-explain.
func reasonLine(reason string) string {
	switch reason {
	case "", "OPERATOR", "ARREARS_ESCALATION", "ARREARS_CLEARED":
		return "" // handled in body copy already
	default:
		return reason
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
