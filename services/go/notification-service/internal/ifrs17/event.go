// Package ifrs17 consumes medfund.ifrs17.material-event and delivers
// the payload as an email and/or webhook to the recipients configured
// on public.tenant_ifrs17_notification_config (per tenant, per event
// type, with a redis-backed throttle so a burst of the same event
// type collapses to one delivery).
//
// The producers on the Java side are user-service
// KafkaIfrs17MaterialEventPublisher (cohort ONEROUS_TRANSITION) and
// finance-service Ifrs17MaterialEventPublisher (CSM_NEGATIVE,
// LOCKED_IN_CURVE_FALLBACK, OPENING_BALANCE_AUTO_DERIVED,
// IBNR_SUB_JOB_STALE). Both flatten every field to a string, so the
// wire shape here is a plain map decoded through Event.
package ifrs17

import "time"

const topic = "medfund.ifrs17.material-event"

// Event is the decoded material-event payload. Producers serialise
// everything as strings (see KafkaIfrs17MaterialEventPublisher on the
// Java side); the empty strings for optional fields are load-bearing
// so keep the JSON tags matching.
type Event struct {
	Event         string `json:"event"`
	SchemaVersion string `json:"schemaVersion"`
	TenantID      string `json:"tenantId"`
	CohortID      string `json:"cohortId,omitempty"`
	PortfolioID   string `json:"portfolioId,omitempty"`
	EventType     string `json:"eventType"`
	Severity      string `json:"severity"`
	Message       string `json:"message"`
	SourceRunID   string `json:"sourceRunId,omitempty"`
	OccurredAt    string `json:"occurredAt"`
}

// ParsedOccurredAt tries to interpret OccurredAt as RFC3339. Falls
// back to time.Now() so template rendering never blocks on a badly
// formatted producer.
func (e Event) ParsedOccurredAt() time.Time {
	if e.OccurredAt == "" {
		return time.Now()
	}
	if t, err := time.Parse(time.RFC3339, e.OccurredAt); err == nil {
		return t
	}
	if t, err := time.Parse(time.RFC3339Nano, e.OccurredAt); err == nil {
		return t
	}
	return time.Now()
}

// EventType constants — the closed set producers use. Kept in one
// place so the template registry and the fallback in dispatcher can
// share a single source of truth.
const (
	EventTypeOnerousTransition        = "ONEROUS_TRANSITION"
	EventTypeCsmNegative              = "CSM_NEGATIVE"
	EventTypeLockedInCurveFallback    = "LOCKED_IN_CURVE_FALLBACK"
	EventTypeIbnrSubJobStale          = "IBNR_SUB_JOB_STALE"
	EventTypeOpeningBalanceAutoDerive = "OPENING_BALANCE_AUTO_DERIVED"
)

// KnownEventTypes lists every event type the dispatcher has a
// dedicated template for. Anything outside this set still delivers,
// but uses the generic body.
var KnownEventTypes = []string{
	EventTypeOnerousTransition,
	EventTypeCsmNegative,
	EventTypeLockedInCurveFallback,
	EventTypeIbnrSubJobStale,
	EventTypeOpeningBalanceAutoDerive,
}
