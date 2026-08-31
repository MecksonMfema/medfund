package aml

import (
	"context"
	"errors"
	"testing"
)

func sampleEvent() Event {
	prior := "RAISED"
	member := "member-1"
	return Event{
		SchemaVersion:   1,
		TenantID:        "t-1",
		AlertID:         "alert-1",
		TransactionRef:  "TXN-2026-000001",
		TransactionType: "PREMIUM",
		AmountNative:    "15000.00",
		Currency:        "USD",
		MemberID:        &member,
		PriorStatus:     &prior,
		NewStatus:       StatusReviewed,
		Transition:      TransitionReview,
		ActorID:         "actor-1",
		ActorEmail:      "reviewer@medfund",
		OccurredAt:      "2026-08-30T12:00:00Z",
	}
}

func TestDispatch_stubMode_logsOnlyReturnsOk(t *testing.T) {
	d := NewDispatcher(nil)
	res := d.Dispatch(context.Background(), sampleEvent())
	if !res.Ok {
		t.Fatalf("expected Ok=true in stub mode, got %+v", res)
	}
	if res.HookInvoked {
		t.Fatalf("expected HookInvoked=false when Hook is nil, got %+v", res)
	}
	if res.HookErr != nil {
		t.Fatalf("expected nil HookErr in stub mode, got %v", res.HookErr)
	}
}

func TestDispatch_hookInvoked_reportsResult(t *testing.T) {
	var seen Event
	hook := func(_ context.Context, e Event) error {
		seen = e
		return nil
	}
	d := NewDispatcher(hook)
	res := d.Dispatch(context.Background(), sampleEvent())
	if !res.Ok || !res.HookInvoked || res.HookErr != nil {
		t.Fatalf("expected Ok+HookInvoked+no err, got %+v", res)
	}
	if seen.AlertID != "alert-1" || seen.Transition != TransitionReview {
		t.Fatalf("hook received wrong event: %+v", seen)
	}
}

func TestDispatch_hookError_isCapturedButDoesNotBlockConsumer(t *testing.T) {
	hookErr := errors.New("ai-service down")
	hook := func(_ context.Context, _ Event) error { return hookErr }
	d := NewDispatcher(hook)
	res := d.Dispatch(context.Background(), sampleEvent())
	if !res.Ok {
		t.Fatalf("Ok must stay true so consumer commits; got %+v", res)
	}
	if !res.HookInvoked || res.HookErr == nil || res.HookErr.Error() != "ai-service down" {
		t.Fatalf("expected HookInvoked with captured err, got %+v", res)
	}
}

func TestDispatch_dropsMalformedEvent(t *testing.T) {
	d := NewDispatcher(nil)
	// Missing alertId / transition / tenantId — treated as malformed.
	res := d.Dispatch(context.Background(), Event{SchemaVersion: 1})
	if !res.Ok {
		t.Fatalf("malformed event must Ok=true (drop, don't retry): %+v", res)
	}
}

func TestParsedOccurredAt_fallsBackWhenBlank(t *testing.T) {
	e := Event{OccurredAt: ""}
	got := e.ParsedOccurredAt()
	if got.IsZero() {
		t.Fatalf("expected fallback to Now(), got zero time")
	}
}

func TestParsedOccurredAt_parsesRfc3339(t *testing.T) {
	e := Event{OccurredAt: "2026-08-30T12:34:56Z"}
	got := e.ParsedOccurredAt()
	if got.Year() != 2026 || got.Month() != 8 || got.Day() != 30 {
		t.Fatalf("bad parse: %v", got)
	}
}

func TestNilDispatcher_returnsOkAndLogs(t *testing.T) {
	var d *Dispatcher
	res := d.Dispatch(context.Background(), sampleEvent())
	if !res.Ok {
		t.Fatalf("nil-receiver dispatch must Ok=true, got %+v", res)
	}
}
