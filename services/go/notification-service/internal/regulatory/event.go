// Package regulatory consumes medfund.regulatory.due-date-approaching
// and emails Phase-16 regulator report reminders to the recipients
// configured on public.tenant_regulatory_recipient (per tenant, per
// event tier).
//
// Producer is finance-service's RegulatoryDueDateScanner
// (services/java/finance-service/src/main/java/com/medfund/finance/regulatory/scheduler/RegulatoryDueDateScanner.java)
// — a daily cron that emits at four tier boundaries per (tenant,
// report_key):
//
//	daysUntilDue ==  7 → DUE_DATE_7D    (INFO)
//	daysUntilDue ==  1 → DUE_DATE_1D    (AMBER)
//	daysUntilDue ==  0 → DUE_DATE_0D    (AMBER)
//	daysUntilDue == -1 → DUE_DATE_OVERDUE (RED)
//
// The scanner-side dedupe table (public.regulatory_due_date_notification_sent)
// blocks a duplicate publish within 24 hours, so no throttle backend is
// wired here; the dispatcher fans out on every event received.
package regulatory

import "time"

// Topic is the Kafka topic the Java producer writes to.
const Topic = "medfund.regulatory.due-date-approaching"

// Event mirrors com.medfund.shared.report.RegulatoryDueDateApproachingEvent.
// Producer serialises via Jackson: numeric fields stay numeric, dates
// stay ISO-8601. Field order in the struct doesn't matter — the wire
// shape is JSON-object.
type Event struct {
	SchemaVersion int    `json:"schemaVersion"`
	TenantID      string `json:"tenantId"`
	ReportKey     string `json:"reportKey"`
	PeriodStart   string `json:"periodStart"`
	PeriodEnd     string `json:"periodEnd"`
	DueDate       string `json:"dueDate"`
	DaysUntilDue  int64  `json:"daysUntilDue"`
	Severity      string `json:"severity"`  // INFO / AMBER / RED
	EventTier     string `json:"eventTier"` // DUE_DATE_7D / DUE_DATE_1D / DUE_DATE_0D / DUE_DATE_OVERDUE
	OccurredAt    string `json:"occurredAt"`
}

// ParsedOccurredAt best-effort parses the event's occurredAt as RFC3339.
// Falls back to time.Now() so template rendering never blocks on a
// badly-formatted producer.
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

// Allowed tier constants — mirror the Java enum + Postgres CHECK on
// public.regulatory_due_date_notification_sent.event_tier.
const (
	TierDueDate7d       = "DUE_DATE_7D"
	TierDueDate1d       = "DUE_DATE_1D"
	TierDueDate0d       = "DUE_DATE_0D"
	TierDueDateOverdue  = "DUE_DATE_OVERDUE"
)
