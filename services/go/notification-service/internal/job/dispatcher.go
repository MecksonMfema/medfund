// Package job consumes JobCompleted events from the scheduler and
// emails the triggering user when a long-running job finishes.
//
// "Long-running" is a configurable duration threshold (default 30s)
// so a quick preview commit doesn't flood the inbox while an hours-
// long billing run still produces a "your job finished" mail.
package job

import (
	"bytes"
	"context"
	_ "embed"
	"fmt"
	htmltemplate "html/template"
	"log"
	"strconv"
	texttemplate "text/template"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/medfund/notification-service/internal/mail"
	"github.com/medfund/notification-service/internal/template"
)

//go:embed body.html
var defaultBodyTemplate string

// DefaultSubject and DefaultHTMLBody mirror the invoice helpers — they
// expose the embedded fallbacks so the cmd/main wiring can hand them
// to the per-tenant template resolver.
const DefaultSubject = "Your {{.KindLabel}} {{.Outcome}} after {{.Duration}}"

func DefaultHTMLBody() string { return defaultBodyTemplate }

// Event projects the JobCompleted wire payload. Fields are strings
// because the publisher serialises everything as a flat map for
// AuditPublisher parity — the consumer parses what it needs.
type Event struct {
	Event         string `json:"event"`
	RunID         string `json:"runId"`
	ConfigID      string `json:"configId"`
	TenantID      string `json:"tenantId"`
	Kind          string `json:"kind"`
	Status        string `json:"status"`
	TriggerKind   string `json:"triggerKind"`
	TriggeredBy   string `json:"triggeredBy"`
	StartedAt     string `json:"startedAt"`
	EndedAt       string `json:"endedAt"`
	DurationMs    string `json:"durationMs"`
	ResultPayload string `json:"resultPayload"`
	ErrorMessage  string `json:"errorMessage"`
}

const templateKey = "JOB_COMPLETED"

// StaffUser is the projection of public.staff_users a job email needs.
type StaffUser struct {
	Email       string
	DisplayName string
}

// StaffLookup is the seam tests use to bypass Postgres. Production
// uses DBStaffLookup against the real pool.
type StaffLookup interface {
	ForActor(ctx context.Context, actorID string) (StaffUser, error)
}

// DBStaffLookup queries public.staff_users by id.
type DBStaffLookup struct{ Pool *pgxpool.Pool }

func (l DBStaffLookup) ForActor(ctx context.Context, actorID string) (StaffUser, error) {
	if l.Pool == nil {
		return StaffUser{}, fmt.Errorf("postgres pool unavailable")
	}
	var email, first, last string
	err := l.Pool.QueryRow(ctx,
		`SELECT email, first_name, last_name FROM public.staff_users WHERE id = $1`,
		actorID).Scan(&email, &first, &last)
	if err != nil {
		return StaffUser{}, fmt.Errorf("lookup staff user %s: %w", actorID, err)
	}
	return StaffUser{Email: email, DisplayName: first + " " + last}, nil
}

// Result captures the dispatcher outcome, matching invoice.Result so
// the consumer can publish NotificationSent uniformly across event
// kinds.
type Result struct {
	Ok        bool
	Recipient string
	Source    string
	Err       error
	// Skipped is true when the dispatcher chose not to send (duration
	// below threshold, no actor, system job). Distinct from Err so the
	// consumer can suppress NotificationSent for these instead of
	// emitting a misleading FAILED row.
	Skipped bool
}

// TemplateResolver is satisfied by *template.Resolver (production) and
// by test fakes. Same shape as invoice.TemplateResolver so a future
// shared dispatcher base could absorb both.
type TemplateResolver interface {
	Resolve(ctx context.Context, tenantID, key string) template.Template
}

type Dispatcher struct {
	Lookup      StaffLookup
	Sender      mail.Sender
	Templates   TemplateResolver
	From        string
	MinDuration time.Duration
}

// NewDispatcher builds a Dispatcher. minDuration of 0 sends on every
// job; the default 30s is the value cmd/main passes.
func NewDispatcher(l StaffLookup, s mail.Sender, t TemplateResolver, from string, minDuration time.Duration) (*Dispatcher, error) {
	if t == nil {
		return nil, fmt.Errorf("template resolver is required")
	}
	return &Dispatcher{
		Lookup: l, Sender: s, Templates: t, From: from, MinDuration: minDuration,
	}, nil
}

// Dispatch resolves the actor → renders the email → sends it. Returns
// Skipped=true (no Err) when the event doesn't meet the email criteria
// — caller should not publish NotificationSent in that case.
func (d *Dispatcher) Dispatch(ctx context.Context, e Event) Result {
	// Filter 1: scheduled jobs (no human actor) — no inbox to email.
	if e.TriggerKind == "schedule" || e.TriggeredBy == "" {
		return Result{Skipped: true}
	}
	// Filter 2: long-running threshold.
	dur := parseDuration(e.DurationMs)
	if dur < d.MinDuration {
		log.Printf("[job] %s — duration %v below threshold %v, skipping email", e.RunID, dur, d.MinDuration)
		return Result{Skipped: true}
	}

	if d.Lookup == nil {
		return Result{Err: fmt.Errorf("staff lookup unavailable")}
	}
	user, err := d.Lookup.ForActor(ctx, e.TriggeredBy)
	if err != nil {
		log.Printf("[job] %s — actor lookup failed: %v", e.RunID, err)
		return Result{Err: err}
	}

	tmpl := d.Templates.Resolve(ctx, e.TenantID, templateKey)
	view := renderView(e, user, dur)
	subject, err := renderText(tmpl.Subject, view)
	if err != nil {
		return Result{Recipient: user.Email, Source: tmpl.Source, Err: err}
	}
	body, err := renderHTML(tmpl.HTMLBody, view)
	if err != nil {
		return Result{Recipient: user.Email, Source: tmpl.Source, Err: err}
	}

	msg := mail.Message{
		From: d.From, To: user.Email, Subject: subject, HTMLBody: body,
	}
	if err := d.Sender.Send(msg); err != nil {
		return Result{Recipient: user.Email, Source: tmpl.Source, Err: err}
	}
	log.Printf("[job] %s — sent to %s via %s template (kind=%s status=%s duration=%v)",
		e.RunID, user.Email, tmpl.Source, e.Kind, e.Status, dur)
	return Result{Ok: true, Recipient: user.Email, Source: tmpl.Source}
}

func parseDuration(ms string) time.Duration {
	if ms == "" {
		return 0
	}
	n, err := strconv.ParseInt(ms, 10, 64)
	if err != nil {
		return 0
	}
	return time.Duration(n) * time.Millisecond
}

type renderData struct {
	DisplayName, Kind, KindLabel, Status, Outcome, Duration,
	ResultPayload, ErrorMessage, RunID, TenantID string
}

func renderView(e Event, u StaffUser, dur time.Duration) renderData {
	return renderData{
		DisplayName:   u.DisplayName,
		Kind:          e.Kind,
		KindLabel:     friendlyKind(e.Kind),
		Status:        e.Status,
		Outcome:       outcomeLabel(e.Status),
		Duration:      humanDuration(dur),
		ResultPayload: e.ResultPayload,
		ErrorMessage:  e.ErrorMessage,
		RunID:         e.RunID,
		TenantID:      e.TenantID,
	}
}

func outcomeLabel(status string) string {
	if status == "SUCCESS" {
		return "finished"
	}
	return "failed"
}

func friendlyKind(k string) string {
	switch k {
	case "BILLING_COMMIT":
		return "billing commit"
	case "BILLING_PREVIEW":
		return "billing preview"
	case "BILLING_CYCLE":
		return "billing cycle"
	case "OVERDUE_CHECK":
		return "overdue check"
	default:
		return "job"
	}
}

func humanDuration(d time.Duration) string {
	switch {
	case d < time.Second:
		return fmt.Sprintf("%dms", d.Milliseconds())
	case d < time.Minute:
		return fmt.Sprintf("%.0fs", d.Seconds())
	case d < time.Hour:
		return fmt.Sprintf("%dm%ds", int(d.Minutes()), int(d.Seconds())%60)
	default:
		return fmt.Sprintf("%dh%dm", int(d.Hours()), int(d.Minutes())%60)
	}
}

func renderText(src string, v renderData) (string, error) {
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

func renderHTML(src string, v renderData) (string, error) {
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
