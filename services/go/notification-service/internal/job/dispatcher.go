// Package job consumes JobCompleted events from the scheduler and
// emails the triggering user when a job finishes. Every manual-triggered
// completion produces a mail — no duration gate — because the operator
// who kicked the run is the person who needs to know it finished.
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
	// TriggeredByEmail is the actor email captured from the JWT at
	// enqueue time (JobDispatcher.runNowAsync). When present the
	// dispatcher uses it verbatim — no staff_users lookup required.
	// Empty for scheduled / system jobs; falls through to StaffLookup
	// for the legacy path so cron-driven runs still email their owner
	// if one is provisioned in staff_users.
	TriggeredByEmail string `json:"triggeredByEmail"`
	StartedAt        string `json:"startedAt"`
	EndedAt          string `json:"endedAt"`
	DurationMs       string `json:"durationMs"`
	ResultPayload    string `json:"resultPayload"`
	ErrorMessage     string `json:"errorMessage"`
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
	// actorID here is the JWT `sub` claim — a Keycloak subject UUID as a
	// string. `staff_users.id` is a locally-generated UUID; the Keycloak
	// side lives in `keycloak_user_id`. The earlier `WHERE id = $1` query
	// silently returned "no rows" for every JobCompleted event, dropping
	// every commit-completed notification. Match the string column and
	// fall back to `id` for legacy rows that were seeded before Keycloak
	// linkage — that way locally-provisioned admin accounts still work.
	var email, first, last string
	err := l.Pool.QueryRow(ctx, `
		SELECT email, first_name, last_name
		  FROM public.staff_users
		 WHERE keycloak_user_id = $1
		    OR id::text          = $1
		 LIMIT 1`, actorID).Scan(&email, &first, &last)
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
	Lookup    StaffLookup
	Sender    mail.Sender
	Templates TemplateResolver
	From      string
}

func NewDispatcher(l StaffLookup, s mail.Sender, t TemplateResolver, from string) (*Dispatcher, error) {
	if t == nil {
		return nil, fmt.Errorf("template resolver is required")
	}
	return &Dispatcher{Lookup: l, Sender: s, Templates: t, From: from}, nil
}

// Dispatch resolves the actor → renders the email → sends it. Returns
// Skipped=true (no Err) when the event has no human actor to email —
// caller should not publish NotificationSent in that case.
func (d *Dispatcher) Dispatch(ctx context.Context, e Event) Result {
	// Scheduled/system jobs have no human inbox — skip. Every other
	// manual completion emails, regardless of how quick the run was.
	if e.TriggerKind == "schedule" || e.TriggeredBy == "" {
		return Result{Skipped: true}
	}
	dur := parseDuration(e.DurationMs)

	// Prefer the email captured on the event itself (populated from the
	// caller's JWT at enqueue time). Falls back to the staff_users lookup
	// only when the event predates the plumbing OR the caller was a
	// scheduled job whose owner is provisioned in staff_users.
	var user StaffUser
	if e.TriggeredByEmail != "" {
		user = StaffUser{Email: e.TriggeredByEmail, DisplayName: e.TriggeredByEmail}
	} else {
		if d.Lookup == nil {
			return Result{Err: fmt.Errorf("staff lookup unavailable and event carries no triggeredByEmail")}
		}
		var err error
		user, err = d.Lookup.ForActor(ctx, e.TriggeredBy)
		if err != nil {
			log.Printf("[job] %s — actor lookup failed: %v", e.RunID, err)
			return Result{Err: err}
		}
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
