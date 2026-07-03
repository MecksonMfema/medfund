package job

import (
	"context"
	"fmt"
	"strings"
	"testing"
	"time"

	"github.com/medfund/notification-service/internal/mail"
	"github.com/medfund/notification-service/internal/template"
)

type fakeLookup struct {
	user      StaffUser
	err       error
	callCount int
}

// ForActor records call count so tests can assert whether the lookup was
// consulted at all (the TriggeredByEmail preference test relies on it not
// being called when the field is set).
func (f *fakeLookup) ForActor(_ context.Context, _ string) (StaffUser, error) {
	f.callCount++
	return f.user, f.err
}

type fakeResolver struct{ subj, body string }

func (f fakeResolver) Resolve(_ context.Context, _, _ string) template.Template {
	return template.Template{Subject: f.subj, HTMLBody: f.body, Source: "default"}
}

func newTestDispatcher(t *testing.T, sender mail.Sender) *Dispatcher {
	t.Helper()
	d, err := NewDispatcher(
		&fakeLookup{user: StaffUser{Email: "ops@medfund", DisplayName: "Ops Person"}},
		sender,
		fakeResolver{subj: DefaultSubject, body: DefaultHTMLBody()},
		"no-reply@medfund.healthcare",
	)
	if err != nil {
		t.Fatal(err)
	}
	return d
}

// newTestDispatcherWithLookup exposes the injected lookup so tests can inspect
// call counts (specifically to prove TriggeredByEmail short-circuits the DB
// path).
func newTestDispatcherWithLookup(t *testing.T, sender mail.Sender, lookup *fakeLookup) *Dispatcher {
	t.Helper()
	d, err := NewDispatcher(
		lookup, sender,
		fakeResolver{subj: DefaultSubject, body: DefaultHTMLBody()},
		"no-reply@medfund.healthcare",
	)
	if err != nil {
		t.Fatal(err)
	}
	return d
}

func TestDispatch_skipsScheduledTrigger(t *testing.T) {
	sender := &mail.MockSender{}
	d := newTestDispatcher(t, sender)

	res := d.Dispatch(context.Background(), Event{
		TriggerKind: "schedule", TriggeredBy: "uuid-1", DurationMs: "60000",
	})

	if !res.Skipped {
		t.Errorf("scheduled trigger should be skipped, got %+v", res)
	}
	if len(sender.Sent) != 0 {
		t.Errorf("no email expected for scheduled trigger")
	}
}

// Every manual completion emails, regardless of how quick. The earlier
// duration gate silently dropped the "your commit finished" mail for the
// fast paths (revoke, quick single-scheme commit) — Filter 2 removed.
func TestDispatch_sendsEvenForVeryShortManualRun(t *testing.T) {
	sender := &mail.MockSender{}
	d := newTestDispatcher(t, sender)

	res := d.Dispatch(context.Background(), Event{
		RunID: "r-quick", TriggerKind: "manual", TriggeredBy: "uuid-1",
		Kind: "BILLING_COMMIT", Status: "SUCCESS",
		DurationMs: "250", // 250ms — well below the old 30s gate
	})

	if !res.Ok {
		t.Errorf("quick manual job must still email the actor, got %+v", res)
	}
	if len(sender.Sent) != 1 {
		t.Errorf("expected 1 email, got %d", len(sender.Sent))
	}
}

func TestDispatch_sendsForLongManualRun(t *testing.T) {
	sender := &mail.MockSender{}
	d := newTestDispatcher(t, sender)

	res := d.Dispatch(context.Background(), Event{
		RunID: "r-1", TriggerKind: "manual", TriggeredBy: "uuid-1",
		Kind: "BILLING_COMMIT", Status: "SUCCESS",
		DurationMs: "90000", // 1m30s
	})

	if !res.Ok || res.Recipient != "ops@medfund" {
		t.Errorf("expected Ok send to ops@medfund, got %+v", res)
	}
	if len(sender.Sent) != 1 {
		t.Fatalf("expected 1 sent message, got %d", len(sender.Sent))
	}
	msg := sender.Sent[0]
	if !strings.Contains(msg.Subject, "billing commit") || !strings.Contains(msg.Subject, "finished") {
		t.Errorf("subject should be friendly for SUCCESS, got %q", msg.Subject)
	}
	if !strings.Contains(msg.HTMLBody, "Ops Person") {
		t.Errorf("body should greet the actor, got:\n%s", msg.HTMLBody)
	}
}

func TestDispatch_failedJobMentionsError(t *testing.T) {
	sender := &mail.MockSender{}
	d := newTestDispatcher(t, sender)

	d.Dispatch(context.Background(), Event{
		RunID: "r-2", TriggerKind: "manual", TriggeredBy: "uuid-1",
		Kind: "BILLING_COMMIT", Status: "FAILED",
		DurationMs:   "60000",
		ErrorMessage: "Constraint violation: contributions_period_unique",
	})

	if len(sender.Sent) != 1 {
		t.Fatalf("expected one sent message, got %d", len(sender.Sent))
	}
	msg := sender.Sent[0]
	if !strings.Contains(msg.Subject, "failed") {
		t.Errorf("subject should mention failed, got %q", msg.Subject)
	}
	if !strings.Contains(msg.HTMLBody, "Constraint violation") {
		t.Errorf("body should surface error message")
	}
}

// The event now carries triggeredByEmail (populated at enqueue time from the
// JWT via JobDispatcher.runNowAsync). When set, the dispatcher must send to
// that address WITHOUT touching StaffLookup — the original design forced a
// staff_users query keyed on a Keycloak sub that was rarely provisioned,
// which silently dropped every commit-completed email during 2026-07-02.
func TestDispatch_prefersTriggeredByEmail_skipsStaffLookup(t *testing.T) {
	sender := &mail.MockSender{}
	lookup := &fakeLookup{err: fmt.Errorf("staff_users must NOT be consulted here")}
	d := newTestDispatcherWithLookup(t, sender, lookup)

	res := d.Dispatch(context.Background(), Event{
		RunID:            "r-email-1",
		TriggerKind:      "manual",
		TriggeredBy:      "kc-sub-uuid",
		TriggeredByEmail: "admin@medfund.com",
		Kind:             "BILLING_COMMIT",
		Status:           "SUCCESS",
		DurationMs:       "1500",
	})

	if !res.Ok {
		t.Fatalf("expected Ok result, got %+v", res)
	}
	if res.Recipient != "admin@medfund.com" {
		t.Errorf("expected recipient admin@medfund.com, got %q", res.Recipient)
	}
	if lookup.callCount != 0 {
		t.Errorf("StaffLookup must not be consulted when TriggeredByEmail is set; got %d calls",
			lookup.callCount)
	}
	if len(sender.Sent) != 1 || sender.Sent[0].To != "admin@medfund.com" {
		t.Errorf("expected 1 email to admin@medfund.com, got %+v", sender.Sent)
	}
}

// When TriggeredByEmail is empty (older events, scheduled jobs re-triggered
// as manual, background system runs) the dispatcher must fall through to the
// StaffLookup path so callers with a locally-provisioned staff_users row
// still receive their email. Guard here so a future edit that "simplifies"
// the branching doesn't accidentally drop the legacy path.
func TestDispatch_missingTriggeredByEmail_fallsBackToStaffLookup(t *testing.T) {
	sender := &mail.MockSender{}
	lookup := &fakeLookup{user: StaffUser{Email: "ops@medfund", DisplayName: "Ops"}}
	d := newTestDispatcherWithLookup(t, sender, lookup)

	res := d.Dispatch(context.Background(), Event{
		RunID:       "r-fallback",
		TriggerKind: "manual",
		TriggeredBy: "actor-uuid",
		// TriggeredByEmail intentionally empty
		Kind:       "BILLING_COMMIT",
		Status:     "SUCCESS",
		DurationMs: "5000",
	})

	if !res.Ok {
		t.Fatalf("expected Ok, got %+v", res)
	}
	if lookup.callCount != 1 {
		t.Errorf("StaffLookup must run when TriggeredByEmail is empty; got %d calls", lookup.callCount)
	}
	if res.Recipient != "ops@medfund" {
		t.Errorf("expected recipient ops@medfund from StaffLookup, got %q", res.Recipient)
	}
}

// TriggeredByEmail plus a failing lookup ⇒ the send must still succeed via
// the direct email. This is the outage's key correctness property: no email
// address is ever coupled to a DB row.
func TestDispatch_triggeredByEmail_immuneToLookupFailure(t *testing.T) {
	sender := &mail.MockSender{}
	lookup := &fakeLookup{err: fmt.Errorf("simulated DB outage")}
	d := newTestDispatcherWithLookup(t, sender, lookup)

	res := d.Dispatch(context.Background(), Event{
		RunID:            "r-outage",
		TriggerKind:      "manual",
		TriggeredBy:      "actor-uuid",
		TriggeredByEmail: "finance@medfund.com",
		Kind:             "BILLING_COMMIT",
		Status:           "SUCCESS",
		DurationMs:       "1000",
	})

	if !res.Ok || res.Err != nil {
		t.Errorf("send should succeed via the event-carried email even when the DB is down; got %+v",
			res)
	}
	if lookup.callCount != 0 {
		t.Errorf("lookup must not be called at all; got %d", lookup.callCount)
	}
}

func TestHumanDuration_picksAppropriateUnit(t *testing.T) {
	cases := map[time.Duration]string{
		500 * time.Millisecond: "500ms",
		45 * time.Second:       "45s",
		3 * time.Minute:        "3m0s",
		2 * time.Hour:          "2h0m",
	}
	for d, want := range cases {
		if got := humanDuration(d); got != want {
			t.Errorf("humanDuration(%v) = %q, want %q", d, got, want)
		}
	}
}
