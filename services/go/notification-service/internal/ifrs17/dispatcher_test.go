package ifrs17

import (
	"context"
	"errors"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/medfund/notification-service/internal/mail"
)

// ── Test doubles ─────────────────────────────────────────────────────

type stubConfigs struct {
	rows []NotificationConfig
	err  error
	// gotEventTypes captures every eventType ActiveFor was called
	// with so tests can assert routing.
	gotEventTypes []string
}

func (s *stubConfigs) ActiveFor(_ context.Context, _, eventType string) ([]NotificationConfig, error) {
	s.gotEventTypes = append(s.gotEventTypes, eventType)
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

type recordingWebhook struct {
	posts []struct {
		URL   string
		Event Event
	}
	err error
}

func (w *recordingWebhook) Send(_ context.Context, url string, event Event) error {
	if w.err != nil {
		return w.err
	}
	w.posts = append(w.posts, struct {
		URL   string
		Event Event
	}{url, event})
	return nil
}

// inMemoryThrottle is a small SetNX-style throttle used to prove the
// dedupe behaviour without a real Redis. The window is honoured via
// wall-clock; tests use a very short window so they run fast.
type inMemoryThrottle struct {
	mu   sync.Mutex
	seen map[string]time.Time
}

func newInMemoryThrottle() *inMemoryThrottle {
	return &inMemoryThrottle{seen: map[string]time.Time{}}
}

func (t *inMemoryThrottle) Acquire(_ context.Context, key string, window time.Duration) (bool, error) {
	t.mu.Lock()
	defer t.mu.Unlock()
	if window <= 0 {
		return true, nil
	}
	if last, ok := t.seen[key]; ok && time.Since(last) < window {
		return false, nil
	}
	t.seen[key] = time.Now()
	return true, nil
}

// errorThrottle simulates a backend outage — Acquire always errors.
// The dispatcher must still deliver (fail-open policy).
type errorThrottle struct{}

func (errorThrottle) Acquire(_ context.Context, _ string, _ time.Duration) (bool, error) {
	return false, errors.New("redis down")
}

// baseEvent is the shape every test starts from.
func baseEvent() Event {
	return Event{
		Event:         "IFRS17_MATERIAL_EVENT",
		SchemaVersion: "1",
		TenantID:      "tnt-1",
		CohortID:      "coh-1",
		PortfolioID:   "prt-1",
		EventType:     EventTypeOnerousTransition,
		Severity:      "WARN",
		Message:       "cohort flipped ONEROUS after auto-test",
		SourceRunID:   "run-1",
		OccurredAt:    time.Now().UTC().Format(time.RFC3339),
	}
}

// ── Tests ────────────────────────────────────────────────────────────

func TestDispatch_emailFanout_sendsToEveryActiveRecipient(t *testing.T) {
	configs := &stubConfigs{rows: []NotificationConfig{
		{Recipient: "alice@acme.test", DeliveryMethod: "EMAIL", TenantID: "tnt-1",
			EventType: EventTypeOnerousTransition, IsActive: true, ThrottleMinutes: 30},
		{Recipient: "bob@acme.test", DeliveryMethod: "EMAIL", TenantID: "tnt-1",
			EventType: EventTypeOnerousTransition, IsActive: true, ThrottleMinutes: 30},
		{Recipient: "carol@acme.test", DeliveryMethod: "EMAIL", TenantID: "tnt-1",
			EventType: EventTypeOnerousTransition, IsActive: false, ThrottleMinutes: 30},
	}}
	sender := &recordingSender{}
	d := NewDispatcher(configs, sender, nil, newInMemoryThrottle(), "no-reply@medfund")

	res := d.Dispatch(context.Background(), baseEvent())

	if res.Err != nil {
		t.Fatalf("unexpected err: %v", res.Err)
	}
	if !res.Ok {
		t.Fatalf("expected Ok")
	}
	if res.Delivered != 2 {
		t.Fatalf("expected 2 delivered (inactive row skipped), got %d", res.Delivered)
	}
	if len(sender.sent) != 2 {
		t.Fatalf("expected 2 messages sent, got %d", len(sender.sent))
	}
	if !strings.Contains(sender.sent[0].Subject, "onerous") {
		t.Errorf("subject should mention onerous transition: %q", sender.sent[0].Subject)
	}
	if !strings.Contains(sender.sent[0].HTMLBody, "cohort flipped ONEROUS") {
		t.Errorf("body should contain the event message: %q", sender.sent[0].HTMLBody)
	}
}

func TestDispatch_throttle_deduplicatesSecondEventInWindow(t *testing.T) {
	configs := &stubConfigs{rows: []NotificationConfig{
		{Recipient: "alice@acme.test", DeliveryMethod: "EMAIL", TenantID: "tnt-1",
			EventType: EventTypeOnerousTransition, IsActive: true, ThrottleMinutes: 30},
	}}
	sender := &recordingSender{}
	throt := newInMemoryThrottle()
	d := NewDispatcher(configs, sender, nil, throt, "no-reply@medfund")

	first := d.Dispatch(context.Background(), baseEvent())
	second := d.Dispatch(context.Background(), baseEvent())

	if first.Delivered != 1 || second.Delivered != 0 {
		t.Fatalf("expected 1+0 delivered, got %d+%d", first.Delivered, second.Delivered)
	}
	if second.Throttled != 1 {
		t.Fatalf("expected second call throttled, got %d", second.Throttled)
	}
	if len(sender.sent) != 1 {
		t.Fatalf("expected one email in total, got %d", len(sender.sent))
	}
}

func TestDispatch_deliveryMethodBoth_sendsEmailAndWebhook(t *testing.T) {
	configs := &stubConfigs{rows: []NotificationConfig{
		{Recipient: "https://hook.acme.test/ifrs17", DeliveryMethod: "BOTH", TenantID: "tnt-1",
			EventType: EventTypeCsmNegative, IsActive: true, ThrottleMinutes: 0},
	}}
	sender := &recordingSender{}
	wh := &recordingWebhook{}
	d := NewDispatcher(configs, sender, wh, newInMemoryThrottle(), "no-reply@medfund")

	ev := baseEvent()
	ev.EventType = EventTypeCsmNegative
	res := d.Dispatch(context.Background(), ev)

	if res.Delivered != 2 {
		t.Fatalf("expected 2 deliveries (email + webhook), got %d", res.Delivered)
	}
	if len(sender.sent) != 1 {
		t.Fatalf("expected 1 email, got %d", len(sender.sent))
	}
	if len(wh.posts) != 1 {
		t.Fatalf("expected 1 webhook post, got %d", len(wh.posts))
	}
	if wh.posts[0].URL != "https://hook.acme.test/ifrs17" {
		t.Errorf("wrong webhook URL: %q", wh.posts[0].URL)
	}
	if wh.posts[0].Event.EventType != EventTypeCsmNegative {
		t.Errorf("webhook event type not propagated: %+v", wh.posts[0].Event)
	}
}

func TestDispatch_deliveryMethodWebhookOnly_skipsEmail(t *testing.T) {
	configs := &stubConfigs{rows: []NotificationConfig{
		{Recipient: "https://hook.acme.test/ifrs17", DeliveryMethod: "WEBHOOK", TenantID: "tnt-1",
			EventType: EventTypeIbnrSubJobStale, IsActive: true, ThrottleMinutes: 0},
	}}
	sender := &recordingSender{}
	wh := &recordingWebhook{}
	d := NewDispatcher(configs, sender, wh, newInMemoryThrottle(), "no-reply@medfund")

	ev := baseEvent()
	ev.EventType = EventTypeIbnrSubJobStale
	res := d.Dispatch(context.Background(), ev)

	if res.Delivered != 1 || len(wh.posts) != 1 || len(sender.sent) != 0 {
		t.Fatalf("WEBHOOK-only routing: expected 1 webhook + 0 email, got delivered=%d webhooks=%d emails=%d",
			res.Delivered, len(wh.posts), len(sender.sent))
	}
}

func TestDispatch_configFetchError_bubblesUp(t *testing.T) {
	configs := &stubConfigs{err: errors.New("tenancy service 500")}
	d := NewDispatcher(configs, &recordingSender{}, nil, newInMemoryThrottle(), "no-reply@medfund")

	res := d.Dispatch(context.Background(), baseEvent())

	if res.Err == nil {
		t.Fatalf("expected fetch error to bubble as Result.Err")
	}
	if !strings.Contains(res.Err.Error(), "tenancy service 500") {
		t.Errorf("expected inner error to be wrapped, got %v", res.Err)
	}
}

func TestDispatch_unknownEventType_usesGenericTemplate(t *testing.T) {
	configs := &stubConfigs{rows: []NotificationConfig{
		{Recipient: "alice@acme.test", DeliveryMethod: "EMAIL", TenantID: "tnt-1",
			EventType: "SOMETHING_NEW", IsActive: true, ThrottleMinutes: 0},
	}}
	sender := &recordingSender{}
	d := NewDispatcher(configs, sender, nil, newInMemoryThrottle(), "no-reply@medfund")

	ev := baseEvent()
	ev.EventType = "SOMETHING_NEW"
	res := d.Dispatch(context.Background(), ev)

	if res.Delivered != 1 {
		t.Fatalf("unknown event types should still deliver, got %d", res.Delivered)
	}
	if !strings.Contains(sender.sent[0].Subject, "SOMETHING_NEW") {
		t.Errorf("generic subject should include eventType, got %q", sender.sent[0].Subject)
	}
	if !strings.Contains(sender.sent[0].HTMLBody, "IFRS 17 material event") {
		t.Errorf("expected generic body copy, got %q", sender.sent[0].HTMLBody)
	}
}

func TestDispatch_nilThrottle_deliversWithoutThrottleCheck(t *testing.T) {
	configs := &stubConfigs{rows: []NotificationConfig{
		{Recipient: "alice@acme.test", DeliveryMethod: "EMAIL", TenantID: "tnt-1",
			EventType: EventTypeOnerousTransition, IsActive: true, ThrottleMinutes: 30},
	}}
	sender := &recordingSender{}
	d := NewDispatcher(configs, sender, nil, nil, "no-reply@medfund")

	first := d.Dispatch(context.Background(), baseEvent())
	second := d.Dispatch(context.Background(), baseEvent())

	if first.Delivered != 1 || second.Delivered != 1 {
		t.Fatalf("nil throttle should not suppress: got %d then %d", first.Delivered, second.Delivered)
	}
	if second.Throttled != 0 {
		t.Fatalf("nil throttle should never mark throttled, got %d", second.Throttled)
	}
}

func TestDispatch_throttleBackendError_stillDelivers(t *testing.T) {
	configs := &stubConfigs{rows: []NotificationConfig{
		{Recipient: "alice@acme.test", DeliveryMethod: "EMAIL", TenantID: "tnt-1",
			EventType: EventTypeOnerousTransition, IsActive: true, ThrottleMinutes: 30},
	}}
	sender := &recordingSender{}
	d := NewDispatcher(configs, sender, nil, errorThrottle{}, "no-reply@medfund")

	res := d.Dispatch(context.Background(), baseEvent())

	if res.Delivered != 1 {
		t.Fatalf("fail-open: throttle error must not suppress delivery, got %d", res.Delivered)
	}
	if res.Throttled != 0 {
		t.Fatalf("errored throttle should not be counted as suppressed, got %d", res.Throttled)
	}
}

func TestDispatch_emptyConfigs_isSilentNoop(t *testing.T) {
	configs := &stubConfigs{rows: []NotificationConfig{}}
	d := NewDispatcher(configs, &recordingSender{}, nil, newInMemoryThrottle(), "no-reply@medfund")

	res := d.Dispatch(context.Background(), baseEvent())

	if !res.Ok {
		t.Fatalf("empty subscribers should still succeed")
	}
	if res.Delivered != 0 || res.Attempts != 0 {
		t.Fatalf("empty subscribers should produce 0/0 counts, got delivered=%d attempts=%d",
			res.Delivered, res.Attempts)
	}
}

func TestDispatch_malformedEvent_isSilentNoop(t *testing.T) {
	configs := &stubConfigs{rows: []NotificationConfig{
		{Recipient: "alice@acme.test", DeliveryMethod: "EMAIL", TenantID: "tnt-1",
			EventType: EventTypeOnerousTransition, IsActive: true, ThrottleMinutes: 0},
	}}
	sender := &recordingSender{}
	d := NewDispatcher(configs, sender, nil, newInMemoryThrottle(), "no-reply@medfund")

	ev := baseEvent()
	ev.TenantID = "" // malformed — no tenant
	res := d.Dispatch(context.Background(), ev)

	if !res.Ok {
		t.Fatalf("malformed event should short-circuit as Ok so consumer commits offset")
	}
	if len(sender.sent) != 0 {
		t.Fatalf("malformed event should not fan out any delivery, got %d messages", len(sender.sent))
	}
	// The config lookup should not have been consulted at all.
	if len(configs.gotEventTypes) != 0 {
		t.Fatalf("malformed event should skip config lookup, got %d lookups", len(configs.gotEventTypes))
	}
}

func TestDispatch_emailFailure_perRecipientDoesNotAbortOthers(t *testing.T) {
	configs := &stubConfigs{rows: []NotificationConfig{
		{Recipient: "alice@acme.test", DeliveryMethod: "EMAIL", TenantID: "tnt-1",
			EventType: EventTypeOnerousTransition, IsActive: true, ThrottleMinutes: 0},
		{Recipient: "https://hook.acme.test", DeliveryMethod: "WEBHOOK", TenantID: "tnt-1",
			EventType: EventTypeOnerousTransition, IsActive: true, ThrottleMinutes: 0},
	}}
	sender := &recordingSender{err: errors.New("smtp dead")}
	wh := &recordingWebhook{}
	d := NewDispatcher(configs, sender, wh, newInMemoryThrottle(), "no-reply@medfund")

	res := d.Dispatch(context.Background(), baseEvent())

	if res.FailedEmails != 1 {
		t.Fatalf("expected 1 failed email, got %d", res.FailedEmails)
	}
	if res.Delivered != 1 {
		t.Fatalf("webhook should still deliver despite email failure, got delivered=%d",
			res.Delivered)
	}
	if !res.Ok {
		t.Fatalf("Ok must still be true — per-recipient failures don't fail the batch")
	}
}
