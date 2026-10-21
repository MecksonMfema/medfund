package regulatory

import (
	"context"
	"fmt"
	"log"

	"github.com/medfund/notification-service/internal/mail"
)

// Result summarises one Dispatch call for logging + optional metrics.
// Every failure inside the recipient loop is counted but does not abort
// the loop — one bad address must not silence the others. Err only
// carries the initial recipient-fetch failure, which is the only case
// worth retrying at the consumer level.
type Result struct {
	Ok             bool
	Attempts       int
	Delivered      int
	Skipped        int
	FailedEmails   int
	Err            error
}

// Dispatcher wires the per-tenant recipient lookup + SMTP sender + the
// From address. Fields may be nil — a nil recipient lookup or sender
// short-circuits the pipeline into a warning log rather than a panic,
// matching the "fail-open" contract the rest of notification-service uses.
type Dispatcher struct {
	Recipients RecipientLookup
	Sender     mail.Sender
	From       string
}

func NewDispatcher(recipients RecipientLookup, sender mail.Sender, from string) *Dispatcher {
	return &Dispatcher{
		Recipients: recipients,
		Sender:     sender,
		From:       from,
	}
}

// Dispatch is the end-to-end pipeline for one due-date event. Returns
// Result.Err only when the initial recipient fetch fails — that's the
// only condition worth handing back to the consumer for offset retry.
// Everything else is per-recipient and logged.
func (d *Dispatcher) Dispatch(ctx context.Context, event Event) Result {
	if d == nil || d.Recipients == nil {
		return Result{Err: fmt.Errorf("dispatcher: recipient lookup not configured")}
	}
	if event.TenantID == "" || event.EventTier == "" || event.ReportKey == "" {
		log.Printf("[reg-due-date] drop malformed event: tenantId=%q tier=%q key=%q",
			event.TenantID, event.EventTier, event.ReportKey)
		return Result{Ok: true}
	}

	recipients, err := d.Recipients.ActiveFor(ctx, event.TenantID)
	if err != nil {
		log.Printf("[reg-due-date] fetch recipients failed tenant=%s: %v",
			event.TenantID, err)
		return Result{Err: fmt.Errorf("fetch recipients: %w", err)}
	}
	if len(recipients) == 0 {
		return Result{Ok: true}
	}

	subject := subjectFor(event)
	body := renderBody(event)

	res := Result{Attempts: len(recipients)}
	for _, r := range recipients {
		if !r.IsActive {
			res.Skipped++
			continue
		}
		if !r.SubscribedTo(event.EventTier) {
			res.Skipped++
			log.Printf("[reg-due-date] tier not subscribed tenant=%s key=%s tier=%s recipient=%s",
				event.TenantID, event.ReportKey, event.EventTier, r.Email)
			continue
		}
		if err := d.deliver(r.Email, subject, body); err != nil {
			res.FailedEmails++
			log.Printf("[reg-due-date] email delivery failed tenant=%s key=%s tier=%s recipient=%s: %v",
				event.TenantID, event.ReportKey, event.EventTier, r.Email, err)
			continue
		}
		res.Delivered++
	}

	res.Ok = true
	return res
}

func (d *Dispatcher) deliver(to, subject, body string) error {
	if d.Sender == nil {
		return fmt.Errorf("email sender not configured")
	}
	return d.Sender.Send(mail.Message{
		From:     d.From,
		To:       to,
		Subject:  subject,
		HTMLBody: body,
	})
}

// subjectFor renders a concise subject line the tenant admin scanning
// their inbox can triage. The report key + tier is enough — the body
// carries the period and due-date detail.
func subjectFor(e Event) string {
	switch e.EventTier {
	case TierDueDate7d:
		return fmt.Sprintf("[Regulator due date] %s due in 7 days", e.ReportKey)
	case TierDueDate1d:
		return fmt.Sprintf("[Regulator due date] %s due tomorrow", e.ReportKey)
	case TierDueDate0d:
		return fmt.Sprintf("[Regulator due date] %s due today", e.ReportKey)
	case TierDueDateOverdue:
		return fmt.Sprintf("[Regulator due date] %s OVERDUE", e.ReportKey)
	default:
		return fmt.Sprintf("[Regulator due date] %s - %s", e.ReportKey, e.EventTier)
	}
}

func renderBody(e Event) string {
	return fmt.Sprintf(
		`<p>Regulator report <strong>%s</strong> covering the period `+
			`<code>%s</code> to <code>%s</code> is due on <strong>%s</strong> `+
			`(<strong>%d</strong> day(s) from today).</p>`+
			`<p>Severity: <strong>%s</strong>.</p>`+
			`<p>Sign in to InsureFlow to generate and file the return.</p>`,
		e.ReportKey, e.PeriodStart, e.PeriodEnd, e.DueDate, e.DaysUntilDue, e.Severity)
}
