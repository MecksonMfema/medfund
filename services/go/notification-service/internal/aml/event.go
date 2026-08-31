// Package aml consumes medfund.aml.suspicious-transaction (Phase 22 REG8 +
// Phase 24) and currently just logs each transition. It is the Go
// deploy-first counterpart to finance-service's SuspiciousTransactionEventPublisher
// per F-REG7 (consumer must be live before the Java producer starts
// publishing — otherwise events land against an empty consumer group and
// are only picked up when the dispatcher rolls, arriving late).
//
// The dispatcher's Run function is a stub with an explicit hook slot for
// the future fraud-detector AI integration (Phase 24 §"hook slot for
// future AI integration"). Adding a hook — e.g. call ai-service on a
// RAISE transition to score fraud probability — replaces the stub log
// with the real handler; the surrounding subscribe / decode / dispatch
// scaffolding stays the same.
//
// Producer: finance-service
// (services/java/finance-service/src/main/java/com/medfund/finance/regulatory/aml/kafka/SuspiciousTransactionEventPublisher.java)
package aml

import "time"

// Topic is the Kafka topic the Java producer writes to.
const Topic = "medfund.aml.suspicious-transaction"

// Transition constants — mirror the SuspiciousTransactionEvent Java
// enum-style TRANSITION_* constants + the workflow status transitions
// enforced by AmlAlertService.
const (
	TransitionRaise  = "RAISE"
	TransitionReview = "REVIEW"
	TransitionFile   = "FILE"
	TransitionClose  = "CLOSE"

	StatusRaised   = "RAISED"
	StatusReviewed = "REVIEWED"
	StatusFiled    = "FILED"
	StatusClosed   = "CLOSED"
)

// Event mirrors com.medfund.shared.report.SuspiciousTransactionEvent.
// Producer serialises via Jackson (JavaTimeModule for Instant); the wire
// shape is a JSON object, field order does not matter.
//
// PriorStatus is null on RAISE and populated with the from-state on
// every other transition. Kept as a *string so a missing / null wire
// value survives decode as nil rather than an empty status literal.
type Event struct {
	SchemaVersion  int     `json:"schemaVersion"`
	TenantID       string  `json:"tenantId"`
	AlertID        string  `json:"alertId"`
	TransactionRef string  `json:"transactionRef"`
	TransactionType string `json:"transactionType"`
	AmountNative   string  `json:"amountNative"` // BigDecimal serialised as string
	Currency       string  `json:"currency"`
	MemberID       *string `json:"memberId,omitempty"`
	ProviderID     *string `json:"providerId,omitempty"`
	PriorStatus    *string `json:"priorStatus,omitempty"`
	NewStatus      string  `json:"newStatus"`
	Transition     string  `json:"transition"`
	ActorID        string  `json:"actorId"`
	ActorEmail     string  `json:"actorEmail"`
	OccurredAt     string  `json:"occurredAt"`
}

// ParsedOccurredAt best-effort parses the event's occurredAt as RFC3339.
// Falls back to time.Now() so downstream logging / hook code never
// blocks on a badly-formatted producer.
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
