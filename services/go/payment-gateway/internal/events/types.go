package events

import (
	"encoding/json"
	"log"
)

// PaymentRunItem is one inlined item on a PaymentRunExecuted event.
// Field names + amount-as-string match the Java-side
// {@code PaymentRunItemPayload} record.
type PaymentRunItem struct {
	ItemID       string `json:"itemId"`
	PaymentID    string `json:"paymentId"`
	ProviderID   string `json:"providerId"`
	MemberID     string `json:"memberId"`
	Amount       string `json:"amount"`
	CurrencyCode string `json:"currencyCode"`
}

// PaymentRunExecuted mirrors the V075 fat payload emitted by
// finance-service's FinanceEventPublisher.publishPaymentRunExecuted.
// Only the fields the payment-gateway needs are decoded; unknown
// fields are ignored by the standard json unmarshaler.
type PaymentRunExecuted struct {
	Event               string           `json:"event"`
	RunID               string           `json:"runId"`
	RunNumber           string           `json:"runNumber"`
	TenantID            string           `json:"tenantId"`
	SourceBankAccountID string           `json:"sourceBankAccountId"`
	CurrencyCode        string           `json:"currencyCode"`
	PaymentCount        string           `json:"paymentCount"`
	Items               []PaymentRunItem `json:"items"`
}

// ParseRunExecuted unmarshals + validates the incoming Kafka body.
// Returns (event, true) on success; (zero, false) with a log line when
// the payload is malformed or missing the two fields the consumer
// needs to route (tenantId, runId). Commit-and-skip semantics — the
// caller acks the offset either way.
func ParseRunExecuted(body []byte) (PaymentRunExecuted, bool) {
	var e PaymentRunExecuted
	if err := json.Unmarshal(body, &e); err != nil {
		log.Printf("[payment-gateway] drop malformed PaymentRunExecuted: %v", err)
		return PaymentRunExecuted{}, false
	}
	if e.TenantID == "" || e.RunID == "" {
		log.Printf("[payment-gateway] drop PaymentRunExecuted missing required fields: %+v", e)
		return PaymentRunExecuted{}, false
	}
	return e, true
}
