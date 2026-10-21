package regulatory

import (
	"context"
	"errors"
	"strings"
	"testing"

	"github.com/medfund/notification-service/internal/mail"
)

// ── Test doubles ─────────────────────────────────────────────────────

type stubRecipients struct {
	rows       []Recipient
	err        error
	seenTenant string
}

func (s *stubRecipients) ActiveFor(_ context.Context, tenantID string) ([]Recipient, error) {
	s.seenTenant = tenantID
	if s.err != nil {
		return nil, s.err
	}
	return s.rows, nil
}

type recordingSender struct {
	sent []mail.Message
	err  error
}

func (s *recordingSender) Send(m mail.Message) error {
	if s.err != nil {
		return s.err
	}
	s.sent = append(s.sent, m)
	return nil
}

// ── Tests ────────────────────────────────────────────────────────────

func sampleEvent() Event {
	return Event{
		SchemaVersion: 1,
		TenantID:      "t-1",
		ReportKey:     "IPEC_QUARTERLY_RETURN",
		PeriodStart:   "2026-04-01",
		PeriodEnd:     "2026-06-30",
		DueDate:       "2026-07-30",
		DaysUntilDue:  7,
		Severity:      "AMBER",
		EventTier:     TierDueDate7d,
		OccurredAt:    "2026-07-23T03:00:00Z",
	}
}

func TestDispatch_deliversToSubscribedRecipients(t *testing.T) {
	sender := &recordingSender{}
	stub := &stubRecipients{rows: []Recipient{
		{Email: "compliance@acme.com", IsActive: true,
			SubscribedEventTiers: []string{TierDueDate7d, TierDueDate1d}},
		{Email: "cfo@acme.com", IsActive: true,
			SubscribedEventTiers: []string{TierDueDate1d}}, // not subscribed to 7d
	}}
	d := NewDispatcher(stub, sender, "no-reply@medfund.io")

	res := d.Dispatch(context.Background(), sampleEvent())

	if !res.Ok || res.Delivered != 1 || res.Skipped != 1 || res.FailedEmails != 0 {
		t.Fatalf("expected 1 delivered + 1 skipped, got %+v", res)
	}
	if len(sender.sent) != 1 || sender.sent[0].To != "compliance@acme.com" {
		t.Fatalf("expected send to compliance@acme.com, got %+v", sender.sent)
	}
	if !strings.Contains(sender.sent[0].Subject, "IPEC_QUARTERLY_RETURN") {
		t.Fatalf("subject missing report key: %q", sender.sent[0].Subject)
	}
	if !strings.Contains(sender.sent[0].Subject, "7 days") {
		t.Fatalf("subject missing tier copy: %q", sender.sent[0].Subject)
	}
	if !strings.Contains(sender.sent[0].HTMLBody, "2026-07-30") {
		t.Fatalf("body missing due date: %q", sender.sent[0].HTMLBody)
	}
}

func TestDispatch_inactiveRecipientSkipped(t *testing.T) {
	sender := &recordingSender{}
	stub := &stubRecipients{rows: []Recipient{
		{Email: "gone@acme.com", IsActive: false,
			SubscribedEventTiers: []string{TierDueDate7d}},
	}}
	d := NewDispatcher(stub, sender, "from@medfund.io")

	res := d.Dispatch(context.Background(), sampleEvent())
	if res.Delivered != 0 || res.Skipped != 1 {
		t.Fatalf("expected 1 skipped, got %+v", res)
	}
	if len(sender.sent) != 0 {
		t.Fatalf("expected no sends, got %d", len(sender.sent))
	}
}

func TestDispatch_recipientFetchError_returnsErrForRetry(t *testing.T) {
	sender := &recordingSender{}
	stub := &stubRecipients{err: errors.New("tenancy service down")}
	d := NewDispatcher(stub, sender, "from@medfund.io")

	res := d.Dispatch(context.Background(), sampleEvent())
	if res.Err == nil {
		t.Fatalf("expected err set for retry, got %+v", res)
	}
	if len(sender.sent) != 0 {
		t.Fatalf("expected no sends, got %d", len(sender.sent))
	}
}

func TestDispatch_emailSenderErrorContinuesLoop(t *testing.T) {
	sender := &recordingSender{err: errors.New("smtp closed")}
	stub := &stubRecipients{rows: []Recipient{
		{Email: "a@acme.com", IsActive: true, SubscribedEventTiers: []string{TierDueDate7d}},
		{Email: "b@acme.com", IsActive: true, SubscribedEventTiers: []string{TierDueDate7d}},
	}}
	d := NewDispatcher(stub, sender, "from@medfund.io")

	res := d.Dispatch(context.Background(), sampleEvent())
	if res.FailedEmails != 2 || res.Delivered != 0 {
		t.Fatalf("expected 2 failed, 0 delivered, got %+v", res)
	}
	if !res.Ok {
		t.Fatalf("expected Ok=true (Err is only for fetch failures), got %+v", res)
	}
}

func TestDispatch_malformedEvent_dropsSilently(t *testing.T) {
	sender := &recordingSender{}
	stub := &stubRecipients{}
	d := NewDispatcher(stub, sender, "from@medfund.io")

	res := d.Dispatch(context.Background(), Event{TenantID: "t-1"})
	if !res.Ok || res.Attempts != 0 {
		t.Fatalf("expected clean drop, got %+v", res)
	}
	if stub.seenTenant != "" {
		t.Fatalf("expected no recipient lookup for malformed event, got %q", stub.seenTenant)
	}
}

func TestDispatch_emptyRecipientList_isNoop(t *testing.T) {
	sender := &recordingSender{}
	stub := &stubRecipients{rows: nil}
	d := NewDispatcher(stub, sender, "from@medfund.io")

	res := d.Dispatch(context.Background(), sampleEvent())
	if !res.Ok || res.Attempts != 0 || len(sender.sent) != 0 {
		t.Fatalf("expected clean noop, got %+v send=%d", res, len(sender.sent))
	}
}

func TestSubjectFor_coversAllFourTiers(t *testing.T) {
	cases := map[string]string{
		TierDueDate7d:      "due in 7 days",
		TierDueDate1d:      "due tomorrow",
		TierDueDate0d:      "due today",
		TierDueDateOverdue: "OVERDUE",
	}
	for tier, expected := range cases {
		got := subjectFor(Event{ReportKey: "AML_STR", EventTier: tier})
		if !strings.Contains(got, expected) {
			t.Fatalf("subject for tier %s missing %q: got %q", tier, expected, got)
		}
	}
}

func TestRecipient_SubscribedTo(t *testing.T) {
	r := Recipient{SubscribedEventTiers: []string{TierDueDate1d, TierDueDate0d}}
	if !r.SubscribedTo(TierDueDate1d) {
		t.Fatal("expected DUE_DATE_1D subscribed")
	}
	if r.SubscribedTo(TierDueDate7d) {
		t.Fatal("expected DUE_DATE_7D NOT subscribed")
	}
	empty := Recipient{}
	if empty.SubscribedTo(TierDueDate1d) {
		t.Fatal("expected empty tiers to reject")
	}
}
