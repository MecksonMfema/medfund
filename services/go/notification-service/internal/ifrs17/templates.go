package ifrs17

import (
	"bytes"
	"embed"
	"fmt"
	htmltemplate "html/template"
)

//go:embed templates/*.html
var templateFS embed.FS

// templateView is what every ifrs17 body.html renders against.
// Every field is a string so the html/template zero-value ({{if .X}})
// short-circuits cleanly when the producer omitted the field.
type templateView struct {
	TenantID    string
	CohortID    string
	PortfolioID string
	EventType   string
	Severity    string
	Message     string
	SourceRunID string
	OccurredAt  string
}

// templateFor picks the body file for a given event type. Unknown or
// blank event types drop through to generic.html so a producer that
// invents a new eventType still delivers, just with generic copy.
func templateFor(eventType string) string {
	switch eventType {
	case EventTypeOnerousTransition:
		return "templates/onerous_transition.html"
	case EventTypeCsmNegative:
		return "templates/csm_negative.html"
	case EventTypeLockedInCurveFallback:
		return "templates/locked_in_curve_fallback.html"
	case EventTypeIbnrSubJobStale:
		return "templates/ibnr_sub_job_stale.html"
	case EventTypeOpeningBalanceAutoDerive:
		return "templates/opening_balance_auto_derived.html"
	default:
		return "templates/generic.html"
	}
}

// subjectFor picks the email subject line. Kept in code (not in the
// template body) so a downstream email client's preview text lands
// on the right words without needing to render the HTML.
func subjectFor(eventType string) string {
	switch eventType {
	case EventTypeOnerousTransition:
		return "[IFRS 17] Cohort transitioned to onerous"
	case EventTypeCsmNegative:
		return "[IFRS 17] CSM went negative — loss recognised"
	case EventTypeLockedInCurveFallback:
		return "[IFRS 17] Locked-in yield curve fallback engaged"
	case EventTypeIbnrSubJobStale:
		return "[IFRS 17] IBNR triangle stale — sub-job triggered"
	case EventTypeOpeningBalanceAutoDerive:
		return "[IFRS 17] Opening balance auto-derived"
	default:
		return fmt.Sprintf("[IFRS 17] %s", eventType)
	}
}

// renderBody loads the right template + renders it against the event.
// Time formatting is done once here so both html and webhook payloads
// share the same string.
func renderBody(e Event) (string, error) {
	name := templateFor(e.EventType)
	raw, err := templateFS.ReadFile(name)
	if err != nil {
		return "", fmt.Errorf("read %s: %w", name, err)
	}
	tmpl, err := htmltemplate.New(name).Parse(string(raw))
	if err != nil {
		return "", fmt.Errorf("parse %s: %w", name, err)
	}
	view := templateView{
		TenantID:    e.TenantID,
		CohortID:    e.CohortID,
		PortfolioID: e.PortfolioID,
		EventType:   e.EventType,
		Severity:    e.Severity,
		Message:     e.Message,
		SourceRunID: e.SourceRunID,
		OccurredAt:  e.ParsedOccurredAt().Format("2006-01-02 15:04:05 MST"),
	}
	var buf bytes.Buffer
	if err := tmpl.Execute(&buf, view); err != nil {
		return "", fmt.Errorf("execute %s: %w", name, err)
	}
	return buf.String(), nil
}
