package consumer

import (
	"encoding/json"
	"sync"
	"testing"
	"time"

	"github.com/medfund/audit-service/internal/audit"
)

// stubStore captures Append / AppendSecurity calls so the test can assert
// what reached the persistence layer without a real Postgres pool.
type stubStore struct {
	mu       sync.Mutex
	events   []audit.Event
	security []audit.SecurityEvent
}

func (s *stubStore) Append(e audit.Event) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.events = append(s.events, e)
}

func (s *stubStore) AppendSecurity(e audit.SecurityEvent) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.security = append(s.security, e)
}

func (s *stubStore) snapshot() ([]audit.Event, []audit.SecurityEvent) {
	s.mu.Lock()
	defer s.mu.Unlock()
	return append([]audit.Event(nil), s.events...), append([]audit.SecurityEvent(nil), s.security...)
}

func newConsumerWithStub(store eventStore) *AuditConsumer {
	return &AuditConsumer{brokers: "localhost:9092", store: store}
}

func TestHandleAuditEvent_persistsValidPayload(t *testing.T) {
	stub := &stubStore{}
	c := newConsumerWithStub(stub)

	event := audit.Event{
		ID:         "evt-1",
		TenantID:   "tenant-1",
		EntityType: "Scheme",
		EntityID:   "scheme-9",
		Action:     "CREATE",
		ActorID:    "user-1",
		Timestamp:  time.Date(2026, 1, 15, 10, 30, 0, 0, time.UTC),
	}
	payload, err := json.Marshal(event)
	if err != nil {
		t.Fatalf("marshal: %v", err)
	}

	c.handleAuditEvent(payload)

	events, _ := stub.snapshot()
	if len(events) != 1 {
		t.Fatalf("expected 1 event persisted, got %d", len(events))
	}
	if events[0].ID != "evt-1" || events[0].Action != "CREATE" {
		t.Errorf("unexpected event: %+v", events[0])
	}
	if events[0].Timestamp.IsZero() {
		t.Errorf("timestamp should not be zero")
	}
}

func TestHandleAuditEvent_fillsMissingTimestamp(t *testing.T) {
	stub := &stubStore{}
	c := newConsumerWithStub(stub)

	event := audit.Event{ID: "evt-2", Action: "UPDATE"} // no timestamp
	payload, _ := json.Marshal(event)

	before := time.Now()
	c.handleAuditEvent(payload)
	after := time.Now()

	events, _ := stub.snapshot()
	if len(events) != 1 {
		t.Fatalf("expected 1 event persisted, got %d", len(events))
	}
	ts := events[0].Timestamp
	if ts.Before(before) || ts.After(after) {
		t.Errorf("filled timestamp %v should be within [%v, %v]", ts, before, after)
	}
}

func TestHandleAuditEvent_dropsMalformedJSON(t *testing.T) {
	stub := &stubStore{}
	c := newConsumerWithStub(stub)

	c.handleAuditEvent([]byte("{not valid json"))

	events, _ := stub.snapshot()
	if len(events) != 0 {
		t.Errorf("malformed payload should not be persisted, got %d events", len(events))
	}
}

func TestHandleAuditEvent_persistsEmptyValueObjectsWithoutPanic(t *testing.T) {
	stub := &stubStore{}
	c := newConsumerWithStub(stub)

	// Java services emit explicit `null` for oldValue on CREATE.
	c.handleAuditEvent([]byte(`{"id":"evt-3","action":"CREATE","oldValue":null,"newValue":{"name":"Plan"}}`))

	events, _ := stub.snapshot()
	if len(events) != 1 {
		t.Fatalf("expected 1 event, got %d", len(events))
	}
	if events[0].NewValue["name"] != "Plan" {
		t.Errorf("unexpected newValue: %v", events[0].NewValue)
	}
}

func TestHandleSecurityEvent_persistsValidPayload(t *testing.T) {
	stub := &stubStore{}
	c := newConsumerWithStub(stub)

	event := audit.SecurityEvent{
		ID:        "sec-1",
		TenantID:  "tenant-1",
		EventType: "LOGIN",
		UserID:    "user-1",
		IPAddress: "10.0.0.1",
		Timestamp: time.Date(2026, 2, 1, 12, 0, 0, 0, time.UTC),
	}
	payload, _ := json.Marshal(event)

	c.handleSecurityEvent(payload)

	_, secs := stub.snapshot()
	if len(secs) != 1 {
		t.Fatalf("expected 1 security event persisted, got %d", len(secs))
	}
	if secs[0].EventType != "LOGIN" {
		t.Errorf("unexpected eventType: %q", secs[0].EventType)
	}
}

func TestHandleSecurityEvent_fillsMissingTimestamp(t *testing.T) {
	stub := &stubStore{}
	c := newConsumerWithStub(stub)

	payload := []byte(`{"id":"sec-2","eventType":"LOGIN_ERROR","userId":"user-2"}`)
	before := time.Now()
	c.handleSecurityEvent(payload)
	after := time.Now()

	_, secs := stub.snapshot()
	if len(secs) != 1 {
		t.Fatalf("expected 1 security event, got %d", len(secs))
	}
	ts := secs[0].Timestamp
	if ts.Before(before) || ts.After(after) {
		t.Errorf("filled timestamp %v should be within [%v, %v]", ts, before, after)
	}
}

func TestHandleSecurityEvent_dropsMalformedJSON(t *testing.T) {
	stub := &stubStore{}
	c := newConsumerWithStub(stub)

	c.handleSecurityEvent([]byte("not-json"))

	_, secs := stub.snapshot()
	if len(secs) != 0 {
		t.Errorf("malformed security payload should not be persisted")
	}
}

func TestHandlersAreIndependent(t *testing.T) {
	stub := &stubStore{}
	c := newConsumerWithStub(stub)

	auditPayload, _ := json.Marshal(audit.Event{ID: "a", Action: "CREATE"})
	secPayload, _ := json.Marshal(audit.SecurityEvent{ID: "s", EventType: "LOGIN"})

	c.handleAuditEvent(auditPayload)
	c.handleSecurityEvent(secPayload)

	events, secs := stub.snapshot()
	if len(events) != 1 || len(secs) != 1 {
		t.Errorf("expected 1 audit and 1 security event, got %d/%d", len(events), len(secs))
	}
}
